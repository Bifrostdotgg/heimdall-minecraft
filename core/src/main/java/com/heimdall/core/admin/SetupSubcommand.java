package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.http.ApiError;
import com.heimdall.core.http.BotEndpoint;
import com.heimdall.core.http.ClaimClient;
import com.heimdall.core.http.ClaimRequest;
import com.heimdall.core.http.model.ClaimResult;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import com.heimdall.core.util.Strings;
import com.heimdall.core.wiring.HeimdallRuntime;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code /hd setup <code> [endpoint]} — a setup code becomes a connected server, without a restart.
 *
 * <h2>What actually happens</h2>
 *
 * <ol>
 *   <li>{@code POST /api/minecraft/claim} with the code, unsigned, because there is nothing to sign
 *       with yet and that is the point of the endpoint.
 *   <li>The answer — guild, token id, token, server id — is written to {@code bootstrap.yml}.
 *   <li>{@link HeimdallRuntime#applySetup} re-points the HTTP client and the tunnel in place,
 *       confirms the guild and dials.
 * </ol>
 *
 * <p>Step 3 is the one that took a phase to make possible. Until 1e the modules held whatever API
 * client existed at registration — {@code null} on a server that had never been set up — so a claim
 * left an operator with a connected tunnel and an {@code /offend} that still refused. Departure D56
 * has the whole story; the visible consequence here is that this command finishes with a working
 * server rather than with "now restart".
 *
 * <h2>The optional endpoint, and who it is for</h2>
 *
 * <p>Whitelabel instances keep their setup codes in their own database, so a customer's code is not
 * claimable against {@code api.bifrost.gg} at all — it has to go to the instance that minted it. The
 * argument is therefore not a debugging convenience but the documented answer for a whole class of
 * install, which is why it is positional and not hidden behind a flag.
 *
 * <p>Left off, it prefers the endpoint already in {@code bootstrap.yml} (re-claiming a server that
 * is being moved between guilds keeps talking to the same bot) and falls back to the public one.
 *
 * <h2>Why the token is never echoed</h2>
 *
 * <p>It is a bearer credential for this guild's Minecraft API and this command is routinely run down
 * a console pipe whose output ends up in a log file, a screen session and a support ticket. The
 * <em>code</em> is fine to have been typed in the open — it is single-use and the claim has just
 * spent it — but the token it bought is not, and the success message says nothing about it.
 *
 * <h2>Threading</h2>
 *
 * <p>The claim blocks, so everything after the argument check runs on {@code heimdall-io}. The
 * sender is acknowledged first, because a claim against an unreachable endpoint takes fifteen
 * seconds to fail and silence for fifteen seconds reads as a command that did not register.
 */
final class SetupSubcommand implements AdminSubcommand {

    /**
     * Where a code is claimed when nothing says otherwise.
     *
     * <p>The same value v2 shipped as {@code api.baseUrl}, so an operator who has done this before
     * does not have to know it changed. It did not.
     */
    static final String DEFAULT_ENDPOINT = "https://api.bifrost.gg";

    /**
     * How long to block on the claim before giving up on the {@code get()}.
     *
     * <p>Derived from {@link ClaimClient#TIMEOUT_MS} rather than guessed, and the factor of two is
     * not padding. {@code HttpURLConnection} applies its timeout <strong>twice</strong> — once as the
     * connect timeout and once as the read timeout — so a single attempt that stalls at both ends
     * costs nearly {@code 2 × TIMEOUT_MS} of wall clock. This is the same reason {@code JOIN_SLACK_MS}
     * exists on the login path (departure D16). A wait shorter than the request's own worst case is
     * the B1 bug: the {@code get()} abandons the future, {@code supplyAsync} keeps running, the bot
     * still spends the code and mints a token nobody reads, and the operator is told nothing happened.
     */
    private static final long CLAIM_WAIT_MS = 2L * ClaimClient.TIMEOUT_MS + 1_000L;

    @Override
    public String name() {
        return "setup";
    }

    @Override
    public String usage() {
        return "<code> [endpoint]";
    }

    @Override
    public String description() {
        return "claim this server with a setup code from the dashboard";
    }

    @Override
    public void run(final CommandSource source, List<String> args, final AdminContext context) {
        if (args.isEmpty()) {
            source.sendMessage(Msg.legacy("§cUsage: §f/" + context.label() + " setup <code> [endpoint]"));
            source.sendMessage(Msg.legacy(
                    "§7Mint a code on the Minecraft page of the Heimdall dashboard."));
            return;
        }
        final HeimdallRuntime runtime = context.runtime();
        final String code = args.get(0);

        // Validated BEFORE the code is spent. A bad endpoint rejected here costs nothing; a bad
        // endpoint discovered after the claim has burned a single-use code — and a bad endpoint that
        // is NOT discovered is written to bootstrap.yml and becomes the bot that chooses this
        // server's token, answers its login gate and dispatches its console commands. See
        // BotEndpoint for the whole threat.
        BotEndpoint.Result validated = BotEndpoint.validate(resolveEndpoint(args, runtime.bootstrap()));
        if (!validated.valid()) {
            source.sendMessage(Msg.legacy("§c" + validated.error()));
            source.sendMessage(Msg.legacy("§7No code was claimed. Fix the endpoint and try again."));
            return;
        }
        final String endpoint = validated.endpoint();

        if (runtime.isConfigured()) {
            source.sendMessage(Msg.legacy("§eThis server is already set up. Claiming a new code "
                    + "replaces its credentials and moves it to whichever guild the code belongs to."));
        }
        source.sendMessage(Msg.legacy("§7Claiming that code against §f" + endpoint + "§7…"));

        context.async(new Runnable() {
            @Override
            public void run() {
                claim(source, context, runtime, code, endpoint);
            }
        });
    }

    /**
     * Prefers what was typed, then what is configured, then the public bot.
     *
     * <p>The middle rung is the one worth having: a server being re-claimed into another guild is
     * still talking to the same instance, and making the operator retype its URL is how a whitelabel
     * customer ends up accidentally claiming against the public bot and getting an unhelpful
     * {@code INVALID_CODE}.
     */
    private static String resolveEndpoint(List<String> args, BootstrapConfig bootstrap) {
        if (args.size() > 1 && Strings.isNotBlank(args.get(1))) {
            return args.get(1).trim();
        }
        return Strings.isNotBlank(bootstrap.endpoint()) ? bootstrap.endpoint() : DEFAULT_ENDPOINT;
    }

    private void claim(
            CommandSource source,
            AdminContext context,
            HeimdallRuntime runtime,
            String code,
            String endpoint) {
        ClaimResult claimed;
        try {
            claimed = runtime.claimClient()
                    .claim(endpoint, request(code, context, runtime))
                    .get(CLAIM_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Same honesty as the transport branch below: the request may already be in flight, so
            // "nothing has changed" would be a claim this cannot make.
            source.sendMessage(Msg.legacy("§cSetup was interrupted before it could be confirmed. "
                    + "The code may already have been used — if this server does not connect "
                    + "shortly, mint a new one."));
            return;
        } catch (Exception failed) {
            source.sendMessage(Msg.legacy("§c" + explain(failed, endpoint)));
            return;
        }

        if (!claimed.isComplete()) {
            // A 200 that named no guild or no token. Writing it would produce a bootstrap.yml that
            // looks configured and fails every request afterwards, which is a far harder thing to
            // diagnose than this sentence.
            source.sendMessage(Msg.legacy("§cThe bot accepted that code but its answer was "
                    + "incomplete — no guild or no token. Nothing has been written; try again, and "
                    + "if it repeats the problem is bot-side."));
            return;
        }

        BootstrapConfig updated = runtime.bootstrap().toBuilder()
                .endpoint(endpoint)
                .tokenId(claimed.tokenId())
                .token(claimed.token())
                .serverId(claimed.serverId())
                .guildId(claimed.guildId())
                .build();
        try {
            runtime.applySetup(updated);
        } catch (IOException notWritten) {
            source.sendMessage(Msg.legacy("§cThat code was accepted, but " + runtime.bootstrapStore().file()
                    + " could not be written (" + notWritten.getMessage() + "). The code is now "
                    + "spent — fix the permissions on that directory and mint a new one."));
            return;
        } catch (RuntimeException broken) {
            source.sendMessage(Msg.legacy("§cThat code was accepted but this server could not be "
                    + "reconfigured: " + broken));
            return;
        }

        source.sendMessage(Msg.legacy("§aSet up as §f"
                + (Strings.isBlank(claimed.serverName()) ? claimed.serverId() : claimed.serverName())
                + "§a in guild §f" + claimed.guildId() + "§a."));
        source.sendMessage(Msg.legacy("§7Connecting now — no restart needed. §f/" + context.label()
                + " status§7 will show the tunnel once it is up."));
    }

    /**
     * Builds the claim body from what this server already knows about itself.
     *
     * <p>Everything except the code is metadata the dashboard shows against the new server, and all
     * of it is optional — a platform that supplies no {@link IdentitySource} produces a claim with a
     * code and nothing else, which the bot accepts.
     */
    private static ClaimRequest request(String code, AdminContext context, HeimdallRuntime runtime) {
        ClaimRequest.Builder request = ClaimRequest.forCode(code).role(context.role());
        IdentitySource identity = runtime.identitySource();
        if (identity != null) {
            try {
                ServerIdentity self = identity.identity();
                request.platform(self.platform()).mcVersion(self.mcVersion());
            } catch (RuntimeException unavailable) {
                // Identity is decoration on this call. A platform probe that throws must not cost an
                // operator their setup code.
                request.platform("").mcVersion("");
            }
        }
        return request.build();
    }

    /**
     * Turns a failed claim into the sentence that names what to do about it.
     *
     * <p>Every branch here is a different action: mint a new code, wait, check the URL, check the
     * network. Collapsing them into "setup failed" is how an operator ends up restarting a server
     * over a typo in a URL.
     */
    private static String explain(Throwable failure, String endpoint) {
        ApiError apiError = findApiError(failure);
        if (apiError != null) {
            String code = apiError.code();
            if ("INVALID_CODE".equals(code)) {
                return "That setup code is invalid, expired, or has already been used. Codes are "
                        + "single-use — mint a fresh one on the dashboard.";
            }
            if ("TOO_MANY_ATTEMPTS".equals(code)) {
                return "Too many failed setup attempts from this server; the bot is refusing for "
                        + "now. Wait ten minutes and try again with a fresh code.";
            }
            if ("MISSING_PARAMS".equals(code)) {
                return "The bot did not see a code in that request, which should not happen — "
                        + "check the code for stray characters and try again.";
            }
            if (apiError.httpStatus() == 404) {
                return "Nothing at " + endpoint + " answers the setup endpoint. Check the URL — a "
                        + "whitelabel instance has its own, and codes minted there are not "
                        + "claimable anywhere else.";
            }
            return "The bot refused the claim: " + apiError.getMessage();
        }
        // NOT "the code has not been used". A timeout or an IOException means the get() gave up,
        // but the request may have reached the bot and been answered — the future is abandoned, not
        // cancelled, and supplyAsync keeps running. Telling the operator the code is safe to reuse
        // when it may already be spent is the B1 lie; this says what is actually true.
        return "Could not confirm the claim against " + endpoint + " (" + rootMessage(failure)
                + "). The code may already have been used — if this server does not connect "
                + "shortly, mint a new one and try again.";
    }

    private static ApiError findApiError(Throwable failure) {
        Throwable current = failure;
        int guard = 0;
        while (current != null && guard++ < 16) {
            if (current instanceof ApiError) {
                return (ApiError) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        String message = failure.getMessage();
        int guard = 0;
        while (current.getCause() != null && guard++ < 16) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
