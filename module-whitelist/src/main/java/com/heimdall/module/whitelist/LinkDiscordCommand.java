package com.heimdall.module.whitelist;

import com.heimdall.core.command.CommandHandler;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.LinkCodeResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Strings;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * {@code /linkdiscord} and its alias {@code /link}: a six-digit code, and a cooldown.
 *
 * <h2>Why the cooldown is in memory</h2>
 *
 * <p>v2 kept these in its config file, which grew one key per player forever and — worse — was wiped
 * by {@code /hwl reload}, so reloading handed every player a fresh allowance. A map that dies with
 * the server is the right lifetime for a thirty-second window: the only thing lost on a restart is a
 * partial cooldown, and a restart is not something a player can trigger to farm codes.
 *
 * <p>{@code heimdall.bypass} skips it. Unlike the login bypass — which cannot be a permission at all,
 * because permissions are not attached during pre-login (issue #796 / MC-2) — this one is checked
 * with the player very much online, so the node works exactly as an operator expects.
 *
 * <h2>Already-linked is an answer, not a failure</h2>
 *
 * <p>v2 threw a {@code RuntimeException} carrying the "already linked to …" sentence, which turned
 * an ordinary expected outcome into an error, discarded the structured Discord fields, and left the
 * command handler string-matching an exception message to tell it apart from a real failure.
 * {@link LinkCodeResult#alreadyLinked()} is a boolean now and the fields are populated — departure
 * D4 — so this can say <em>who</em> the account is linked to.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #handler} runs on the platform's command thread and must not block, so the request is
 * fired and the reply is sent from the future's completion on {@code heimdall-io}. Sending a message
 * from there is safe: {@code CommandSource} hops if its platform needs it.
 */
final class LinkDiscordCommand {

    /** v2's window, to the millisecond. */
    static final long COOLDOWN_MS = TimeUnit.SECONDS.toMillis(30);

    static final String PERMISSION = "heimdall.linkdiscord";

    /** Skips the cooldown. Checked while the player is online, so a permission works here. */
    static final String BYPASS_PERMISSION = "heimdall.bypass";

    private static final String BORDER = "&a==================================================";

    private final HeimdallLogger logger;
    private final HeimdallApi api;

    /**
     * Last use per player.
     *
     * <p>Bounded by the set of players who have ever run the command this session, which on a busy
     * server is smaller than the online list and on a quiet one is a handful of entries. Cleaning it
     * would need a sweep, and a sweep for a map of longs is more moving parts than the thing it
     * tidies.
     */
    private final Map<UUID, Long> lastUsed = new ConcurrentHashMap<UUID, Long>();

    LinkDiscordCommand(HeimdallLogger logger, HeimdallApi api) {
        this.logger = logger;
        this.api = api;
    }

    /** The spec, with {@code /link} as an alias — both platforms register the pair. */
    CommandSpec spec() {
        return CommandSpec.named("linkdiscord")
                .aliases(Arrays.asList("link"))
                .permission(PERMISSION)
                .description("Get a code to link this Minecraft account to Discord")
                .usage("/linkdiscord")
                .handler(handler())
                .build();
    }

    private CommandHandler handler() {
        return new CommandHandler() {
            @Override
            public void execute(CommandSource source, List<String> args) {
                run(source);
            }
        };
    }

    private void run(final CommandSource source) {
        if (!source.isPlayer() || source.uuid() == null) {
            // The code links a Minecraft account to a Discord one, and the console does not have a
            // Minecraft account to link.
            send(source, "&cOnly a player can link a Discord account.");
            return;
        }
        if (!api.isUsable()) {
            send(source, "&cThis server is not connected to Discord yet. Ask an administrator.");
            return;
        }

        long now = System.currentTimeMillis();
        long remaining = remainingCooldownMs(source, now);
        if (remaining > 0) {
            // Rounded up, so "please wait 0 seconds" is never printed at the tail of the window.
            long seconds = (remaining + 999L) / 1000L;
            send(source, "&cPlease wait " + seconds + " second" + (seconds == 1 ? "" : "s")
                    + " before using this command again.");
            return;
        }
        // Stamped before the request rather than after it: the window is there to stop somebody
        // holding the button down, and a slow bot must not become a way to send several at once.
        lastUsed.put(source.uuid(), Long.valueOf(now));

        send(source, "&eRequesting Discord link code...");
        final String username = source.name();
        api.requestLinkCode(username, source.uuid().toString())
                .whenComplete(new BiConsumer<LinkCodeResult, Throwable>() {
                    @Override
                    public void accept(LinkCodeResult result, Throwable failure) {
                        if (failure != null) {
                            reportFailure(source, username, failure);
                            return;
                        }
                        reportResult(source, result);
                    }
                });
    }

    private long remainingCooldownMs(CommandSource source, long now) {
        if (source.hasPermission(BYPASS_PERMISSION)) {
            return 0L;
        }
        Long previous = lastUsed.get(source.uuid());
        if (previous == null) {
            return 0L;
        }
        long elapsed = now - previous.longValue();
        // A clock that went backwards — an NTP step — would otherwise leave a player waiting out a
        // cooldown measured from the future.
        if (elapsed < 0) {
            return 0L;
        }
        return Math.max(0L, COOLDOWN_MS - elapsed);
    }

    private void reportResult(CommandSource source, LinkCodeResult result) {
        if (result.alreadyLinked()) {
            send(source, "&eThis Minecraft account is already linked.");
            String who = describeLink(result);
            if (Strings.isNotBlank(who)) {
                send(source, "&7" + who);
            }
            return;
        }
        if (Strings.isBlank(result.code())) {
            // A 200 with no code. Worth its own branch: the generic failure message would send the
            // player back to staff with nothing, and staff to the bot with nothing either.
            logger.warn("the bot returned a link response with no code for " + source.name());
            send(source, "&cThe bot did not return a code. Please try again, or contact staff.");
            return;
        }
        send(source, BORDER);
        send(source, "&eYour Discord Link Code: &a&l" + result.code());
        send(source, "&7Go to Discord and use: &f/confirm-code " + result.code());
        send(source, "&7This code expires in 5 minutes");
        send(source, BORDER);
    }

    /** The Discord side of an existing link, in whatever detail the bot supplied. */
    private static String describeLink(LinkCodeResult result) {
        if (Strings.isNotBlank(result.message())) {
            return result.message();
        }
        String name = Strings.isNotBlank(result.discordDisplayName())
                ? result.discordDisplayName() : result.discordUsername();
        if (Strings.isNotBlank(name)) {
            return "Linked to " + name + ".";
        }
        return Strings.isNotBlank(result.discordId()) ? "Linked to Discord ID " + result.discordId() : "";
    }

    private void reportFailure(CommandSource source, String username, Throwable failure) {
        String reason = rootMessage(failure);
        logger.warn("link code generation failed for " + username + ": " + reason);
        if (reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("no linkable account")) {
            send(source, "&cYou don't have a linkable account. You may already be linked, or you "
                    + "are not whitelisted on this server.");
            return;
        }
        send(source, "&cFailed to generate link code. Please try again in a moment, or contact "
                + "staff if this persists.");
    }

    private void send(CommandSource source, String legacy) {
        source.sendMessage(Msg.legacy(legacy.replace('&', '§')));
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
        return message;
    }
}
