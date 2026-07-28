package com.heimdall.module.offenses;

import com.heimdall.core.command.CommandCompleter;
import com.heimdall.core.command.CommandHandler;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.http.model.OffenseReport;
import com.heimdall.core.http.model.OffenseResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /offend <player> <offense> [notes]} — file an infraction and run what the bot sends back.
 *
 * <h2>The plugin does not decide the punishment</h2>
 *
 * <p>Everything about escalation — the running point total, which tier fires, the duration, the
 * command line and its placeholder substitution — is the bot's. This handler sends three fields and
 * dispatches the string it is given. Mirroring the tier table here would put the maths in two places
 * and the plugin's copy is the one that goes stale, silently, on every server that has not updated.
 *
 * <h2>Never fabricate a UUID (#797 / MC-7)</h2>
 *
 * <p>The bot keys infraction history by UUID. A made-up one — v2 briefly had a {@code createTestUuid}
 * path — files the infraction under an identity that never matches the player's real one, so their
 * history splits in two and every escalation tier is computed from half a record. The failure is
 * silent and it is discovered when a repeat offender receives a first-offense warning.
 *
 * <p>So the target is resolved from a {@link PlayerHandle}, and the handle's {@link
 * PlayerHandle#name() name} is what is sent — not what the operator typed. {@code
 * PlayerDirectory.byName} matches case-insensitively, so {@code /offend steve xray} against
 * {@code Steve} would otherwise record the username in whatever casing the operator happened to use.
 *
 * <h2>An offline target is refused, on every platform</h2>
 *
 * <p>{@link PlayerDirectory} is deliberately online-only — "resolve this name to a UUID" has a
 * different answer on every platform, the wrong answer is silent, and the bot already knows the
 * mapping. v2's Bukkit path reached past that with {@code getOfflinePlayerIfCached}; v2's proxy path
 * refused outright, because a Velocity proxy has no such cache to reach into. v3 applies the proxy
 * behaviour everywhere rather than being right on one platform and differently right on the other.
 *
 * <p>That is a deliberate, stated gap and not an oversight: offending a player who has logged out is
 * a real workflow. Closing it properly means asking the bot to resolve the name — it holds the
 * link records and its answer is the same on every platform — which is a new endpoint, so it is
 * phase 1e/1f work rather than a {@code PlayerDirectory} extension invented here.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #execute} runs on whatever thread the platform dispatches commands on — the main server
 * thread on Bukkit — and <strong>never blocks</strong>. The API call is handed to {@code
 * heimdall-io} by {@link HeimdallApi} and the sender is answered from the future's completion. Waiting
 * inline would stall the tick loop for the endpoint's whole retry budget, which at the defaults is
 * measured in tens of seconds.
 *
 * <p>{@link #complete} is a read of {@link OffenseTypeCache} and the online-player list, and nothing
 * else. It runs on a server thread on every tab keystroke.
 */
final class OffendCommand implements CommandHandler, CommandCompleter {

    /** The label. Must also appear in the Bukkit family's {@code plugin.yml} — see D53. */
    static final String NAME = "offend";

    /** v2's node, kept: an operator's existing permission setup must not need editing. */
    static final String PERMISSION = "heimdall.offend";

    static final String USAGE = "/offend <player> <offense> [notes]";

    private final HeimdallLogger logger;

    /** Never {@code null}; it answers "not set up" rather than being absent. See departure D56. */
    private final HeimdallApi api;

    private final OffenseTypeCache cache;
    private final PlayerDirectory players;
    private final ConsoleBridge console;

    OffendCommand(
            HeimdallLogger logger,
            HeimdallApi api,
            OffenseTypeCache cache,
            PlayerDirectory players,
            ConsoleBridge console) {
        if (logger == null || api == null || cache == null || players == null || console == null) {
            throw new IllegalArgumentException(
                    "logger, api, cache, players and console are all required");
        }
        this.logger = logger;
        this.api = api;
        this.cache = cache;
        this.players = players;
        this.console = console;
    }

    /** The registration this command is put in front of players with. */
    CommandSpec spec() {
        return CommandSpec.named(NAME)
                .permission(PERMISSION)
                .usage(USAGE)
                .description("Record an offense against a player and apply the escalated punishment")
                .handler(this)
                .completer(this)
                .build();
    }

    // ── Execution ────────────────────────────────────────────────────────────

    @Override
    public void execute(CommandSource source, List<String> args) {
        if (args == null || args.size() < 2) {
            source.sendMessage(Msg.legacy("§cUsage: §f" + USAGE));
            return;
        }
        if (!api.isUsable()) {
            // Not an error: a freshly-installed server has no credentials yet, and the operator's
            // next step is the setup flow rather than a bug report. Asked rather than caught so the
            // sentence names the state — "not set up" and "still resolving its guild" want
            // different things done about them.
            source.sendMessage(Msg.legacy("§cOffenses cannot be recorded yet — " + api.describe()
                    + ". Run §f/hd setup <code>§c if this server has never been claimed."));
            return;
        }

        String requested = args.get(0);
        PlayerHandle target = players.byName(requested).orElse(null);
        if (target == null) {
            source.sendMessage(Msg.legacy("§cCould not resolve §f" + requested
                    + "§c — they must be online to receive an offense."));
            return;
        }

        // The handle's own casing, not the operator's. See the class javadoc.
        String targetName = target.name();
        String notes = args.size() > 2 ? join(args.subList(2, args.size())) : null;

        OffenseReport report;
        try {
            report = OffenseReport.builder(target.uuid().toString(), targetName, args.get(1))
                    .issuedBy(issuerUuid(source), source.name())
                    .notes(notes)
                    .build();
        } catch (IllegalArgumentException e) {
            // The only way here is a blank slug — `/offend Steve "  "`. The usage line is a better
            // answer than the builder's own message, which names a field the operator never typed.
            source.sendMessage(Msg.legacy("§cUsage: §f" + USAGE));
            return;
        }

        source.sendMessage(Msg.legacy("§eRecording offense §f" + report.offenseSlug()
                + " §eagainst §f" + targetName + "§e..."));

        final String recordedName = targetName;
        final String recordedSlug = report.offenseSlug();
        api.offend(report).whenComplete((result, failure) -> {
            try {
                if (failure != null) {
                    reportFailure(source, recordedName, recordedSlug, failure);
                } else {
                    reportSuccess(source, recordedName, result);
                }
            } catch (RuntimeException e) {
                // A sender that disconnected mid-request, or a platform that threw on send. The
                // infraction is already recorded either way; losing the acknowledgement must not
                // surface as an uncaught exception on heimdall-io.
                logger.error("could not tell " + source.name()
                        + " what happened to their /offend for " + recordedName, e);
            }
        });
    }

    /**
     * The issuer's UUID, or {@code null} for the console.
     *
     * <p>{@code CommandSource.uuid()} being null for the console is load-bearing rather than
     * incidental, and {@code OffenseReport} tolerates it: the field is simply omitted from the
     * request body. An operator running {@code /offend} from a server terminal is ordinary, and a
     * handler that assumed a player would throw the first time one did.
     */
    private static String issuerUuid(CommandSource source) {
        UUID uuid = source.uuid();
        return uuid == null ? null : uuid.toString();
    }

    private void reportSuccess(CommandSource source, String targetName, OffenseResult result) {
        // v2's four lines, unchanged: an operator reading this in chat should not have to relearn it.
        source.sendMessage(Msg.legacy("§aOffense recorded for §f" + targetName + "§a:"));
        source.sendMessage(Msg.legacy("§7Type: §f" + result.offenseType()));
        source.sendMessage(Msg.legacy("§7Action: §f" + result.tierDescription()
                + " §7(tier " + result.tierApplied() + ")"));
        source.sendMessage(Msg.legacy("§7Total points: §f" + result.totalPoints()));

        final String command = result.command();
        if (Strings.isBlank(command)) {
            // A tier may legitimately record points and punish nothing.
            logger.debug(() -> "no punishment command for the tier applied to " + targetName);
            return;
        }
        source.sendMessage(Msg.legacy("§7Dispatching: §f" + command));

        // Always as the console, even when a player typed the command. v2 ran it through
        // `player.performCommand` for a player sender, which re-checks the punishment plugin's own
        // permissions against the moderator — so the same offense landed or did not depending on who
        // reported it, and the bot recorded an infraction either way. The command is the bot's
        // decision, so the server executes it with the server's authority.
        console.dispatchCommand(command).whenComplete((acknowledgement, failure) -> {
            if (failure != null) {
                // Reported, never swallowed: the infraction IS recorded and the punishment is NOT
                // applied, and the only person who can reconcile that is the one who is standing
                // there.
                logger.error("the punishment command for " + targetName
                        + " was refused by the server: " + command, failure);
                source.sendMessage(Msg.legacy("§cThe offense was recorded, but the server refused "
                        + "to run §f" + command + "§c: " + Failures.describe(failure)));
            } else {
                logger.debug(() -> "punishment command for " + targetName + ": " + acknowledgement);
            }
        });
    }

    private void reportFailure(
            CommandSource source, String targetName, String slug, Throwable failure) {
        String described = Failures.describe(failure);
        source.sendMessage(Msg.legacy("§cFailed to record offense: §f" + described));
        // warn rather than error: an unknown slug or an unreachable bot is an operational fact, and
        // the sender has already been told. A stack trace per mistyped slug is noise.
        logger.warn("recording offense '" + slug + "' against " + targetName + " failed: "
                + described);
    }

    /** The trailing arguments, joined the way an operator typed them. */
    private static String join(List<String> words) {
        StringBuilder joined = new StringBuilder();
        for (String word : words) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(word);
        }
        return joined.toString();
    }

    // ── Completion ───────────────────────────────────────────────────────────

    /**
     * Online names for the first argument, cached slugs for the second, nothing after that.
     *
     * <p>Notes are free text; suggesting anything there would be inventing content for a field the
     * operator is writing. Returning an empty list on the Bukkit family falls back to the server's
     * own player-name completion, which is wrong for notes but is what every other command does, and
     * is not worth a special case.
     *
     * <p>Permission is already checked by the registrar before this is reached — on both platforms —
     * so a player who may not run {@code /offend} is not shown the offense catalogue.
     */
    @Override
    public List<String> complete(CommandSource source, List<String> args) {
        if (args == null || args.isEmpty()) {
            // Tab pressed with nothing typed: everything is the right answer.
            return onlineNames("");
        }
        if (args.size() == 1) {
            return onlineNames(args.get(0));
        }
        if (args.size() == 2) {
            return cache.matchingSlugs(args.get(1));
        }
        return Collections.emptyList();
    }

    private List<String> onlineNames(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<String>();
        for (PlayerHandle player : players.onlinePlayers()) {
            String name = player.name();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }
}
