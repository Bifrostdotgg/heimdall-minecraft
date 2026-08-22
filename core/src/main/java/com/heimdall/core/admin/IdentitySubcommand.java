package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Strings;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.core.wiring.IdentityGuard;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hd identity} - which machine these credentials belong to, and how to change the answer.
 *
 * <h2>Why a verb rather than a log line</h2>
 *
 * <p>The mismatch this reports has two remedies with opposite consequences. "I moved this server"
 * wants {@code adopt}, which rebinds and reconnects. "I copied this server" wants {@code reset},
 * which throws the credentials away so the original keeps working. Guessing wrong the second way is
 * the incident this whole check exists to stop, and only the operator knows which happened, so the
 * plugin refuses to pick and puts both in front of them.
 *
 * <p>{@code reset} takes a separate {@code confirm} word because it is one keystroke away from
 * disconnecting a working server from Discord, and the setup code its token was claimed with cannot
 * be claimed twice.
 */
final class IdentitySubcommand implements AdminSubcommand {

    @Override
    public String name() {
        return "identity";
    }

    @Override
    public String usage() {
        return "[adopt|reset [confirm]]";
    }

    @Override
    public String description() {
        return "show or rebind which machine this server's credentials belong to";
    }

    @Override
    public void run(CommandSource source, List<String> args, AdminContext context) {
        if (args.isEmpty()) {
            show(source, context);
            return;
        }
        String verb = args.get(0).toLowerCase(Locale.ROOT);
        if ("adopt".equals(verb)) {
            adopt(source, context);
            return;
        }
        if ("reset".equals(verb)) {
            boolean confirmed = args.size() > 1 && "confirm".equalsIgnoreCase(args.get(1));
            if (confirmed) {
                reset(source, context);
            } else {
                explainReset(source, context);
            }
            return;
        }
        source.sendMessage(Msg.legacy("§cUnknown option '" + args.get(0) + "'. §7Usage: §f/"
                + context.label() + " identity " + usage()));
    }

    @Override
    public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
        if (args.size() <= 1) {
            return Arrays.asList("adopt", "reset");
        }
        if (args.size() == 2 && "reset".equalsIgnoreCase(args.get(0))) {
            return Collections.singletonList("confirm");
        }
        return Collections.emptyList();
    }

    private void show(CommandSource source, AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        IdentityGuard.Decision decision = runtime.identity();

        source.sendMessage(Msg.legacy("§6Instance identity"));
        source.sendMessage(Msg.legacy("§7state: §f" + describe(decision)));
        source.sendMessage(Msg.legacy("§7policy: §f" + decision.policy().wireName()
                + "   §7serverId: §f" + orNone(runtime.bootstrap().serverId())));
        source.sendMessage(Msg.legacy("§7recorded: §f" + orNone(decision.recorded())));
        source.sendMessage(Msg.legacy("§7current: §f" + decision.current()));
        String remedy = remedy(decision, context.label());
        if (!remedy.isEmpty()) {
            source.sendMessage(Msg.legacy("§e" + remedy));
        }
    }

    private void adopt(final CommandSource source, final AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        if (!runtime.isConfigured()) {
            source.sendMessage(Msg.legacy("§eThis server is not set up, so there is nothing to bind. "
                    + "§7Run §f/" + context.label() + " setup <code>§7."));
            return;
        }
        IdentityGuard.Decision before = runtime.identity();
        if (before.state() == IdentityGuard.State.BOUND) {
            source.sendMessage(Msg.legacy("§7Already bound to this instance: §f" + before.current()));
            return;
        }
        if (before.isMismatch()) {
            // Said before the write, not after: if the original is still up, adopting here starts
            // the fight this check exists to end, and the operator is the only one who knows.
            source.sendMessage(Msg.legacy("§eCaution: if the server this was copied from is still "
                    + "running, both will now claim the same serverId and keep evicting each other "
                    + "from the bot. In that case stop here and run §f/" + context.label()
                    + " identity reset confirm§e on this copy instead."));
        }
        source.sendMessage(Msg.legacy("§7Binding these credentials to this instance…"));
        context.async(new Runnable() {
            @Override
            public void run() {
                try {
                    IdentityGuard.Decision after = context.runtime().adoptInstanceIdentity();
                    source.sendMessage(Msg.legacy("§aBound to §f" + after.current()));
                    source.sendMessage(Msg.legacy("§7bot: §f"
                            + context.runtime().connectionStatus()));
                } catch (IOException | RuntimeException failed) {
                    source.sendMessage(Msg.legacy("§cCould not write bootstrap.yml, so nothing has "
                            + "changed: " + failed));
                }
            }
        });
    }

    private void explainReset(CommandSource source, AdminContext context) {
        source.sendMessage(Msg.legacy("§e/" + context.label() + " identity reset confirm §7would:"));
        source.sendMessage(Msg.legacy("§7 - disconnect this server from Discord"));
        source.sendMessage(Msg.legacy("§7 - clear its token, serverId and instance binding from "
                + context.runtime().bootstrapStore().file()));
        source.sendMessage(Msg.legacy("§7 - keep the endpoint, role and the other settings"));
        source.sendMessage(Msg.legacy("§7This server would then need a fresh code: §f/"
                + context.label() + " setup <code>§7. Nothing has been changed."));
    }

    private void reset(final CommandSource source, final AdminContext context) {
        if (!context.runtime().isConfigured()) {
            source.sendMessage(Msg.legacy("§eNothing to reset: this server is not set up. §7Run §f/"
                    + context.label() + " setup <code>§7 to connect it."));
            return;
        }
        source.sendMessage(Msg.legacy("§7Clearing this server's credentials…"));
        context.async(new Runnable() {
            @Override
            public void run() {
                try {
                    context.runtime().resetIdentity();
                    source.sendMessage(Msg.legacy("§aCredentials cleared. §7This server is no longer "
                            + "connected to Discord."));
                    source.sendMessage(Msg.legacy("§7Claim a new code with §f/" + context.label()
                            + " setup <code>§7."));
                } catch (IOException | RuntimeException failed) {
                    source.sendMessage(Msg.legacy("§cCould not write bootstrap.yml, so nothing has "
                            + "changed: " + failed));
                }
            }
        });
    }

    /** The state in words an operator can act on, rather than the enum constant. */
    private static String describe(IdentityGuard.Decision decision) {
        switch (decision.state()) {
            case NOT_SET_UP:
                return "not set up, so there is nothing bound yet";
            case DISABLED:
                return "not checked (identityCheck: off)";
            case ADOPTED:
                return "bound to this instance";
            case MISMATCH_BLOCKED:
                return "MISMATCH - the tunnel is not started";
            case MISMATCH_ADVISORY:
                return "MISMATCH - connected anyway, because identityCheck is auto";
            case BOUND:
            default:
                return "bound to this instance";
        }
    }

    private static String remedy(IdentityGuard.Decision decision, String label) {
        if (!decision.isMismatch()) {
            return "";
        }
        return "Moved this server? /" + label + " identity adopt. Is this a copy of another server? /"
                + label + " identity reset confirm, then /" + label + " setup <code>.";
    }

    private static String orNone(String value) {
        return Strings.isBlank(value) ? "none" : value;
    }
}
