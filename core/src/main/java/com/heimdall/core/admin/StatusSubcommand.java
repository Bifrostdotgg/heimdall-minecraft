package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.HeimdallApi;
import com.heimdall.core.module.ModuleManager;
import com.heimdall.core.module.ModuleState;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.ServerIdentity;
import com.heimdall.core.tunnel.TunnelClient;
import com.heimdall.core.util.Strings;
import com.heimdall.core.wiring.GuildDiscovery;
import com.heimdall.core.wiring.HeimdallRuntime;
import com.heimdall.core.wiring.IdentityGuard;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * {@code /hd status} - everything a support conversation would otherwise start by asking for, in a
 * shape a person can read on one chat screen.
 *
 * <h2>Two layers</h2>
 *
 * <p>The bare verb prints a fixed summary: a header, the same five rows in the same order (Bot,
 * Tunnel, Modules, Mirror, Updates), one short line per actual problem, and a footer naming the
 * detail views. Healthy is seven lines and it never passes ten, because a status screen that scrolls
 * off the top of the chat box is one nobody reads. The rows never move, so the eye learns where to
 * look. Problem lines are kept to about one chat line each: they name the fault and the command that
 * fixes it, and the full sentence lives in the detail view.
 *
 * <p>{@code status bot|modules|server} each print one section in full, and {@code status all}
 * prints every problem and then all three sections, for the console and for pasting into a support
 * ticket. Everything the old single screen printed is still in one of them.
 *
 * <h2>Every confusable state is still a different line</h2>
 *
 * <p>The summary is shorter, not vaguer. These each still produce their own row value and their own
 * problem line:
 *
 * <ul>
 *   <li><em>Not set up</em>, <em>still discovering its guild</em>, <em>the bot refused the
 *       token</em>, <em>the bot is unreachable</em>. The second looks like a network problem and is
 *       not; the third looks like a network problem and is a revoked credential.
 *   <li>An identity mismatch that blocked the tunnel, and one that only warns. The warning is raised
 *       in every state, because it is usually the explanation for whatever else is wrong.
 *   <li><em>v3</em> versus <em>v2-compat</em>. A tunnel that negotiated down runs on built-in
 *       defaults with no dashboard control at all, and looks perfectly connected while doing it.
 *   <li>Per module: <em>switched off</em>, <em>running</em>, <em>failed to start</em>, <em>switched
 *       off locally</em>.
 *   <li>The console feed being quiet because nothing is happening, versus being quiet because a
 *       consumer threw and was silently unsubscribed. That is the {@code droppedTapConsumers}
 *       counter, and this is the only place it is ever reported, because the drop cannot log for
 *       itself: it happens inside the logging pipeline.
 * </ul>
 *
 * <h2>One cause, one line</h2>
 *
 * <p>While the bot side is the problem, the rows that depend on it go grey rather than raising
 * problems of their own: a tunnel that cannot start without a guild is not a second fault, and
 * advice to sync the mirror is useless while the bot cannot be reached.
 *
 * <h2>Colour is never the only signal</h2>
 *
 * <p>Each row's dot is coloured by its health, and its words say the same thing ("FAILED", "not
 * connected"), because the console log and pasted text lose the colour. The rows and the footer are
 * clickable on servers that render click events (Paper through Adventure, Velocity); BungeeCord and
 * the console get legacy text, so nothing is only behind a click or a hover.
 *
 * <h2>Testable without a running bot</h2>
 *
 * <p>Every judgement is made by {@link #assess(Snapshot)}, a pure function of a {@link Snapshot}
 * of plain values, and the summary is rendered by {@link #render(Snapshot)}. The states that need a
 * configured server and a live bot to reach for real are tested by building the snapshot directly.
 *
 * <p>Read-only and non-blocking throughout: every value is a field read or a cheap snapshot, so this
 * is safe to run on the server thread and safe to run while the bot is unreachable.
 */
final class StatusSubcommand implements AdminSubcommand {

    /** The detail views, in the order the footer and tab completion offer them. */
    static final List<String> SECTIONS = Collections.unmodifiableList(
            Arrays.asList("bot", "modules", "server", "all"));

    /**
     * The most lines the problems may take, the "+N more" line included.
     *
     * <p>With the header, five rows and the footer, this is what holds the summary at ten lines:
     * three problems print as three, four or more print as two and a "+N more".
     */
    static final int MAX_PROBLEM_LINES = 3;

    /**
     * The floor below which a mirror is never called stale, whatever the poll interval says.
     *
     * <p>A short interval and one slow poll should not turn the row yellow.
     */
    static final long STALE_FLOOR_MS = TimeUnit.MINUTES.toMillis(15);

    /** The longest whitelabel host the Bot row prints before abbreviating it. */
    static final int MAX_HOST_CHARS = 32;

    /** The longest list of locally disabled modules a problem line names before counting them. */
    static final int MAX_NAMES_CHARS = 22;

    /** How many missed polls in a row make a mirror stale. */
    static final long STALE_AFTER_POLLS = 3L;

    /** The leading dotted number of a version string: {@code 26.2} out of {@code 26.2.build.2622}. */
    private static final Pattern LEADING_VERSION = Pattern.compile("^\\d+(?:\\.\\d+)*");

    private static final String DOT = "●";
    private static final String WARN = "⚠";

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String usage() {
        return "[bot|modules|server|all]";
    }

    @Override
    public String description() {
        return "what this server is, and how it stands with its bot";
    }

    @Override
    public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
        return args.size() <= 1 ? SECTIONS : Collections.<String>emptyList();
    }

    @Override
    public void run(CommandSource source, List<String> args, AdminContext context) {
        String section = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        switch (section) {
            case "":
                for (Component line : render(Snapshot.of(context))) {
                    source.sendMessage(line);
                }
                return;
            case "bot":
                botSection(source, context);
                return;
            case "modules":
                modulesSection(source, context);
                return;
            case "server":
                serverSection(source, context);
                return;
            case "all":
                Snapshot snapshot = Snapshot.of(context);
                source.sendMessage(Msg.legacy(header(snapshot) + " §8- §7full status"));
                problemsSection(source, assess(snapshot));
                botSection(source, context);
                modulesSection(source, context);
                serverSection(source, context);
                return;
            default:
                source.sendMessage(Msg.legacy("§cUnknown section \"" + args.get(0) + "\". §7Try §f/"
                        + context.label() + " status " + String.join("|", SECTIONS) + "§7."));
        }
    }

    // ── What the summary is built from ───────────────────────────────────────

    /**
     * Every value the summary judges, read once.
     *
     * <p>Plain mutable fields, deliberately: the tests set exactly the state they mean and leave the
     * rest at a healthy default, and nothing outside this class and its test ever sees one.
     */
    static final class Snapshot {
        String label = "hd";
        String pluginVersion = "";
        String role = "";
        /**
         * The role the next start would resolve, when it differs from {@link #role}; {@code ""}
         * otherwise. {@link #nextRoleReason} says why.
         */
        String nextRole = "";
        String nextRoleReason = "";
        String software = "unknown server";

        boolean configured = true;
        boolean identityBlocks;
        boolean identityMismatch;
        /** {@code null} once discovery has resolved, or when there is nothing to discover. */
        GuildDiscovery.Status discovery;
        String guildId = "";
        String endpointHost = "";
        boolean apiReady = true;
        String apiDescribe = "";

        boolean tunnelConnected = true;
        boolean tunnelConfigured = true;
        ProtocolMode mode = ProtocolMode.V3;
        int configVersion = 1;

        final Map<String, ModuleState> modules = new LinkedHashMap<String, ModuleState>();
        final Set<String> locallyOff = new LinkedHashSet<String>();

        boolean whitelistAvailable;
        int mirrorEntries = -1;
        long lastSyncAtMs;
        long syncIntervalMs;

        boolean updatesSupported;
        boolean updateAvailable;
        String latestVersion = "";

        /** {@code -1} when the platform could not say. */
        int droppedTapConsumers;

        long nowMs = System.currentTimeMillis();

        /** Reads a live server. Every accessor here is a field read or a cheap snapshot. */
        static Snapshot of(AdminContext context) {
            Snapshot s = new Snapshot();
            HeimdallRuntime runtime = context.runtime();
            s.label = context.label();
            s.pluginVersion = context.pluginVersion();
            s.role = context.role().wireName();
            ServerRole next = runtime.roleAtNextStart();
            if (next != null && next != context.role()) {
                s.nextRole = next.wireName();
                s.nextRoleReason = nextRoleReason(runtime.bootstrap(), next);
            }
            s.software = shortServer(runtime);

            s.configured = runtime.isConfigured();
            IdentityGuard.Decision identity = runtime.identity();
            s.identityBlocks = identity != null && identity.blocksTunnel();
            s.identityMismatch = identity != null && identity.isMismatch();
            s.discovery = runtime.discoveryStatus();
            s.guildId = Strings.trimToEmpty(runtime.guildId());
            s.endpointHost = endpointHost(runtime.bootstrap().endpoint());
            HeimdallApi api = runtime.api();
            s.apiReady = api.availability() == HeimdallApi.Availability.READY;
            s.apiDescribe = api.describe();

            TunnelClient tunnel = runtime.tunnel();
            s.tunnelConnected = tunnel.isConnected();
            s.tunnelConfigured = tunnel.settings().isConfigured();
            s.mode = tunnel.mode();
            s.configVersion = tunnel.configVersion();

            ModuleManager modules = runtime.modules();
            for (String id : modules.registeredIds()) {
                s.modules.put(id, modules.state(id));
            }
            s.locallyOff.addAll(runtime.locallyDisabledModules());

            WhitelistAdmin whitelist = context.whitelist();
            s.whitelistAvailable = whitelist.isAvailable();
            s.mirrorEntries = whitelist.mirrorEntries();
            s.lastSyncAtMs = whitelist.lastSyncAtMs();
            s.syncIntervalMs = whitelist.syncIntervalMs();

            UpdateAdmin updates = context.updates();
            s.updatesSupported = updates.isSupported();
            s.updateAvailable = updates.isUpdateAvailable();
            s.latestVersion = updates.latestVersion();

            try {
                s.droppedTapConsumers = runtime.platform().console().droppedTapConsumers();
            } catch (RuntimeException unavailable) {
                s.droppedTapConsumers = -1;
            }
            return s;
        }
    }

    /** How one row stands. The colour of its dot, and the colour of any problem line it raises. */
    enum Health {
        OK("§a"),
        ATTENTION("§e"),
        BROKEN("§c"),
        /** Not applicable on this server, or waiting on another row. Never a problem by itself. */
        IDLE("§8");

        final String colour;

        Health(String colour) {
            this.colour = colour;
        }
    }

    /** One summary row: its label, how it stands, its short value, and the view behind it. */
    static final class Row {
        final String label;
        final Health health;
        final String value;
        final String section;

        Row(String label, Health health, String value, String section) {
            this.label = label;
            this.health = health;
            this.value = value;
            this.section = section;
        }
    }

    /** One line under the rows: what is wrong, and the command that does something about it. */
    static final class Problem {
        final Health health;
        final String text;

        Problem(Health health, String text) {
            this.health = health;
            this.text = text;
        }
    }

    /**
     * The rows and the problems, worked out together.
     *
     * <p>One pass decides both, so a row's dot and its problem line can never disagree: a red row
     * always comes with a line saying why, and a line never appears under a row that looks fine.
     */
    static final class Report {
        final List<Row> rows = new ArrayList<Row>();
        final List<Problem> problems = new ArrayList<Problem>();

        void problem(Health health, String text) {
            problems.add(new Problem(health, text));
        }

        /** Broken first, then everything else, each in the order raised. */
        List<Problem> ordered() {
            List<Problem> out = new ArrayList<Problem>();
            for (Problem problem : problems) {
                if (problem.health == Health.BROKEN) {
                    out.add(problem);
                }
            }
            for (Problem problem : problems) {
                if (problem.health != Health.BROKEN) {
                    out.add(problem);
                }
            }
            return out;
        }
    }

    // ── Judging ──────────────────────────────────────────────────────────────

    /**
     * Why the next start resolves {@code next}. A role the bot taught this server reads differently
     * from one an operator set, because only the first is a surprise.
     */
    private static String nextRoleReason(BootstrapConfig bootstrap, ServerRole next) {
        if (bootstrap.role() != ServerRole.AUTO) {
            return "set in bootstrap.yml";
        }
        if (next == ServerRole.STANDALONE && bootstrap.learnedRole() == ServerRole.STANDALONE) {
            return "no Heimdall proxy in this network";
        }
        if (next == ServerRole.ENFORCER) {
            return "a Heimdall proxy joined this network";
        }
        return "detected";
    }

    /** Works out every row and every problem. A pure function of the snapshot. */
    static Report assess(Snapshot s) {
        Report report = new Report();
        if (!s.nextRole.isEmpty()) {
            report.problem(Health.ATTENTION, "Restart to run as " + s.nextRole + ": "
                    + s.nextRoleReason);
        }
        boolean botUp = assessBot(report, s);
        assessTunnel(report, s, botUp);
        assessModules(report, s);
        assessMirror(report, s, botUp);
        assessUpdates(report, s);
        if (s.droppedTapConsumers > 0) {
            report.problem(Health.BROKEN, s.droppedTapConsumers + " console reader(s) dropped: /"
                    + s.label + " status server");
        }
        return report;
    }

    /**
     * The Bot row.
     *
     * @return whether the bot side is far enough along that the rows depending on it are judged
     */
    private static boolean assessBot(Report report, Snapshot s) {
        String l = s.label;
        // Raised in every state, not only the healthy one: a copied server directory whose twin
        // re-ran setup shows up here as a refused token, and the mismatch is the explanation.
        if (s.identityMismatch && !s.identityBlocks) {
            report.problem(Health.ATTENTION, "Credentials bound to another machine: /" + l
                    + " identity");
        }
        if (!s.configured) {
            report.rows.add(new Row("Bot", Health.BROKEN, "not set up", "bot"));
            report.problem(Health.BROKEN, "Not set up: /" + l + " setup <code> from the dashboard");
            return false;
        }
        if (s.identityBlocks) {
            report.rows.add(new Row("Bot", Health.BROKEN, "blocked: identity mismatch", "bot"));
            report.problem(Health.BROKEN, "Credentials belong to another machine: /" + l
                    + " identity");
            return false;
        }
        if (s.discovery != null) {
            switch (s.discovery) {
                case TOKEN_REFUSED:
                    report.rows.add(new Row("Bot", Health.BROKEN, "token refused", "bot"));
                    report.problem(Health.BROKEN, "Token refused: /" + l
                            + " setup <new code>");
                    return false;
                case UNREACHABLE:
                    report.rows.add(new Row("Bot", Health.BROKEN, "unreachable", "bot"));
                    report.problem(Health.BROKEN, "Cannot reach the bot, retrying: /" + l
                            + " status bot");
                    return false;
                case DISCOVERING:
                default:
                    report.rows.add(new Row("Bot", Health.ATTENTION, "finding its guild", "bot"));
                    report.problem(Health.ATTENTION, "Asking the bot which guild this is");
                    return false;
            }
        }

        // "linked", not "connected": the guild is known and the API works, but whether the live
        // connection is up is the Tunnel row's to say, and a green "connected" here above a red
        // tunnel would contradict it.
        // One or the other, so the row stays one chat line: a whitelabel server's host is what
        // support asks first, and its guild is a click away in status bot.
        String value = s.endpointHost.isEmpty()
                ? "linked, guild " + (s.guildId.isEmpty() ? "none" : s.guildId)
                : "linked via " + abbreviate(s.endpointHost, MAX_HOST_CHARS);
        Health health = s.identityMismatch ? Health.ATTENTION : Health.OK;
        if (!s.apiReady) {
            health = Health.ATTENTION;
            report.problem(Health.ATTENTION, "Bot API: " + s.apiDescribe);
        }
        report.rows.add(new Row("Bot", health, value, "bot"));
        return true;
    }

    /**
     * The Tunnel row. A down tunnel is idle while the bot row is already the problem. A connected
     * one is judged either way: a server still rediscovering its guild can be running on the cached
     * one, tunnel and all.
     */
    private static void assessTunnel(Report report, Snapshot s, boolean botUp) {
        String l = s.label;
        if (!s.tunnelConnected) {
            if (!botUp) {
                report.rows.add(new Row("Tunnel", Health.IDLE, "waiting on the bot", "bot"));
                return;
            }
            if (!s.tunnelConfigured) {
                // Idle on purpose: retrying will never fix it, so it must not read like a blip.
                report.rows.add(new Row("Tunnel", Health.BROKEN, "idle, settings incomplete", "bot"));
                report.problem(Health.BROKEN, "Tunnel settings incomplete: /" + l
                        + " setup <new code>");
                return;
            }
            report.rows.add(new Row("Tunnel", Health.BROKEN, "not connected", "bot"));
            report.problem(Health.BROKEN, "Live link to the bot is down, reconnecting");
            return;
        }
        if (s.mode == ProtocolMode.V2_COMPAT) {
            report.rows.add(new Row("Tunnel", Health.BROKEN, "protocol v2-compat", "bot"));
            report.problem(Health.BROKEN, "Bot only speaks v2: dashboard settings are ignored");
            return;
        }
        if (s.configVersion < 0) {
            report.rows.add(new Row("Tunnel", Health.ATTENTION, "protocol v3, no config yet", "bot"));
            report.problem(Health.ATTENTION, "Waiting for the bot to send this server's settings");
            return;
        }
        report.rows.add(new Row("Tunnel", Health.OK, "protocol v3, config v" + s.configVersion, "bot"));
    }

    /** The Modules row: counts, with failed and locally disabled modules named underneath. */
    private static void assessModules(Report report, Snapshot s) {
        if (s.modules.isEmpty()) {
            report.rows.add(new Row("Modules", Health.IDLE, "none in this build", "modules"));
            return;
        }
        int running = 0;
        int off = 0;
        List<String> failed = new ArrayList<String>();
        for (Map.Entry<String, ModuleState> entry : s.modules.entrySet()) {
            ModuleState state = entry.getValue();
            if (s.locallyOff.contains(entry.getKey())) {
                off++;
            } else if (state == ModuleState.ENABLED) {
                running++;
            } else if (state == ModuleState.FAILED) {
                failed.add(entry.getKey());
            } else if (state == ModuleState.STOPPED) {
                off++;
            }
            // INELIGIBLE (and an unknown state) are not counted: a module that cannot run with this
            // role is not something anybody switched off. status modules lists them.
        }
        StringBuilder value = new StringBuilder().append(running).append(" running, ")
                .append(off).append(" off");
        Health health = Health.OK;
        if (!failed.isEmpty()) {
            value.append(", ").append(failed.size()).append(" FAILED");
            health = Health.BROKEN;
            for (String id : failed) {
                report.problem(Health.BROKEN, id + " failed to start: check the server log");
            }
        }
        if (!s.locallyOff.isEmpty()) {
            if (health == Health.OK) {
                health = Health.ATTENTION;
            }
            String names = String.join(", ", s.locallyOff);
            String which = names.length() <= MAX_NAMES_CHARS ? names
                    : plural(s.locallyOff.size(), "module");
            report.problem(Health.ATTENTION, "Locally disabled: " + which
                    + " (/" + s.label + " enable <module>)");
        }
        report.rows.add(new Row("Modules", health, value.toString(), "modules"));
    }

    /** The Mirror row: how many players, and how long since the bot last confirmed them. */
    private static void assessMirror(Report report, Snapshot s, boolean botUp) {
        if (!s.whitelistAvailable) {
            report.rows.add(new Row("Mirror", Health.IDLE, "not used (whitelist is off)", "modules"));
            return;
        }
        String count = s.mirrorEntries < 0 ? "unknown size" : plural(s.mirrorEntries, "player");
        if (s.syncIntervalMs <= 0L) {
            report.rows.add(new Row("Mirror", Health.OK, count + ", pre-warm off", "modules"));
            return;
        }
        // lastSyncAtMs is per process: a mirror loaded from disk has entries and no sync yet.
        long age = s.lastSyncAtMs <= 0L ? -1L : Math.max(0L, s.nowMs - s.lastSyncAtMs);
        String value = count + (age < 0L ? ", not synced since start" : ", synced " + ago(age));
        if (!botUp) {
            // The bot is the problem, and a sync cannot happen until it is back. Saying so twice,
            // with advice that cannot work, would bury the line that matters.
            report.rows.add(new Row("Mirror", Health.IDLE, value, "modules"));
            return;
        }
        if (age < 0L) {
            report.rows.add(new Row("Mirror", Health.ATTENTION, value, "modules"));
            report.problem(Health.ATTENTION, "Mirror not synced since start: /" + s.label
                    + " cache sync");
            return;
        }
        if (age > Math.max(STALE_FLOOR_MS, s.syncIntervalMs * STALE_AFTER_POLLS)) {
            report.rows.add(new Row("Mirror", Health.ATTENTION, value, "modules"));
            report.problem(Health.ATTENTION, "Mirror synced " + ago(age) + ", due every "
                    + span(s.syncIntervalMs) + ": /" + s.label + " cache sync");
            return;
        }
        report.rows.add(new Row("Mirror", Health.OK, value, "modules"));
    }

    /** The Updates row. */
    private static void assessUpdates(Report report, Snapshot s) {
        if (!s.updatesSupported) {
            report.rows.add(new Row("Updates", Health.IDLE, "no self-updater in this build", "server"));
            return;
        }
        if (s.updateAvailable) {
            report.rows.add(new Row("Updates", Health.ATTENTION, s.latestVersion + " available",
                    "server"));
            report.problem(Health.ATTENTION, s.latestVersion + " is out: /" + s.label + " update");
            return;
        }
        report.rows.add(new Row("Updates", Health.OK, "up to date", "server"));
    }

    // ── Rendering the summary ────────────────────────────────────────────────

    /** The summary, one component per chat line. */
    static List<Component> render(Snapshot s) {
        Report report = assess(s);
        List<Component> lines = new ArrayList<Component>();
        lines.add(Msg.legacy(header(s)));
        for (Row row : report.rows) {
            lines.add(clickable(Msg.legacy(" " + row.health.colour + DOT + " §7" + row.label + " §f"
                    + row.value), s.label, row.section));
        }
        List<Problem> ordered = report.ordered();
        int shown = ordered.size() <= MAX_PROBLEM_LINES ? ordered.size() : MAX_PROBLEM_LINES - 1;
        for (int i = 0; i < shown; i++) {
            lines.add(problemLine(ordered.get(i)));
        }
        if (ordered.size() > shown) {
            lines.add(clickable(Msg.legacy(" §8+" + (ordered.size() - shown) + " more: §7/" + s.label
                    + " status all"), s.label, "all"));
        }
        lines.add(footer(s.label));
        return lines;
    }

    private static Component problemLine(Problem problem) {
        return Msg.legacy(" " + problem.health.colour + WARN + " §f" + problem.text);
    }

    /** {@code Heimdall 3.0.0-rc.22 · enforcer · Purpur 26.2}: what this is, in one short line. */
    private static String header(Snapshot s) {
        return "§6Heimdall §f" + s.pluginVersion + " §8· §7" + s.role + " §8· §7"
                + s.software;
    }

    /** {@code » /hd status bot · modules · server · all}, each word running its view. */
    private static Component footer(String label) {
        TextComponent.Builder footer = Component.text()
                .append(Msg.legacy(" §8» §7/" + label + " status "));
        for (int i = 0; i < SECTIONS.size(); i++) {
            if (i > 0) {
                footer.append(Msg.legacy(" §8· "));
            }
            String section = SECTIONS.get(i);
            footer.append(clickable(Msg.legacy("§f" + section), label, section));
        }
        return footer.build();
    }

    /** Makes a line run {@code /<label> status <section>} when clicked, where clicks render. */
    private static Component clickable(Component line, String label, String section) {
        String command = "/" + label + " status " + section;
        return line.clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    // ── The detail views ─────────────────────────────────────────────────────

    /** Every problem, uncapped. What the summary's "+N more" points at. */
    private static void problemsSection(CommandSource source, Report report) {
        source.sendMessage(Msg.legacy("§6Problems"));
        List<Problem> ordered = report.ordered();
        if (ordered.isEmpty()) {
            source.sendMessage(Msg.legacy(" §anone"));
            return;
        }
        for (Problem problem : ordered) {
            source.sendMessage(problemLine(problem));
        }
    }

    /** Everything about how this server reaches its bot. */
    private static void botSection(CommandSource source, AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        BootstrapConfig bootstrap = runtime.bootstrap();
        source.sendMessage(Msg.legacy("§6Bot connection"));
        // The endpoint is the whitelabel field: a customer claiming against their own instance has a
        // different one, and "which bot is this server actually talking to?" is the first question a
        // whitelabel support conversation asks.
        detail(source, "endpoint", orNone(bootstrap.endpoint()));
        // connectionStatus() is the full sentence behind the summary's short Bot line, including
        // the last discovery failure and the cached guild a server is running on meanwhile.
        detail(source, "status", runtime.connectionStatus());
        detail(source, "guild", orNone(runtime.guildId()));
        detail(source, "serverId", orNone(bootstrap.serverId()));
        detail(source, "identity", describeIdentity(runtime, context));
        detail(source, "api", runtime.api().describe());
        detail(source, "tunnel", describeTunnel(runtime.tunnel()));
    }

    /** The module list {@code /hd modules} prints, and the mirror in full. */
    private static void modulesSection(CommandSource source, AdminContext context) {
        new RuntimeSubcommands.Modules().run(source, Collections.<String>emptyList(), context);
        detail(source, "whitelist mirror", context.whitelist().stats());
    }

    /** What is installed and running, down to the build. */
    private static void serverSection(CommandSource source, AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        source.sendMessage(Msg.legacy("§6Server"));
        detail(source, "software", describeServer(runtime));
        detail(source, "role", context.role().wireName());
        ServerRole next = runtime.roleAtNextStart();
        if (next != null && next != context.role()) {
            detail(source, "role after restart", next.wireName() + " ("
                    + nextRoleReason(runtime.bootstrap(), next) + ")");
        }
        detail(source, "plugin", context.pluginVersion());
        if (!context.core().isEmpty()) {
            detail(source, "core", context.core());
            detail(source, "shell", context.shellVersion());
        }
        detail(source, "console tap", describeConsole(runtime));
        detail(source, "updates", describeUpdates(context));
        if (!runtime.locallyDisabledModules().isEmpty()) {
            source.sendMessage(Msg.legacy(" §7locally disabled: §e"
                    + String.join(", ", runtime.locallyDisabledModules()) + " §8(/" + context.label()
                    + " enable <module> to restore)"));
        }
    }

    private static void detail(CommandSource source, String label, String value) {
        source.sendMessage(Msg.legacy(" §7" + label + ": §f" + value));
    }

    // ── Describers ───────────────────────────────────────────────────────────

    /**
     * One line on whether these credentials belong to this machine.
     *
     * <p>Short by design: the interesting case has four values behind it and its own verb.
     */
    private static String describeIdentity(HeimdallRuntime runtime, AdminContext context) {
        IdentityGuard.Decision decision = runtime.identity();
        if (!decision.isMismatch()) {
            return decision.summary();
        }
        return decision.summary() + " §8(/" + context.label() + " identity)";
    }

    /**
     * What the server is running, from the same source the bot is told about.
     *
     * <p>Deliberately the {@link IdentitySource} rather than anything read fresh: this is the string
     * the dashboard is showing for this server, so a status line that disagreed with it would send
     * somebody looking for a bug that is really a stale identity.
     */
    private static String describeServer(HeimdallRuntime runtime) {
        IdentitySource source = runtime.identitySource();
        if (source == null) {
            return "unknown server software";
        }
        try {
            ServerIdentity identity = source.identity();
            String software = Strings.isBlank(identity.serverSoftware())
                    ? identity.platform() : identity.serverSoftware();
            String version = Strings.isBlank(identity.mcVersion()) ? "" : " " + identity.mcVersion();
            return orNone(software) + version;
        } catch (RuntimeException unavailable) {
            return "unknown server software (" + unavailable + ")";
        }
    }

    /**
     * The header's version of {@link #describeServer}: the software's name and its leading version
     * number, so {@code Purpur 26.2-2622-48bcd91 (MC: 26.2) 26.2.build.2622-stable} reads
     * {@code Purpur 26.2}. The full string is in {@code status server}.
     */
    private static String shortServer(HeimdallRuntime runtime) {
        IdentitySource source = runtime.identitySource();
        if (source == null) {
            return "unknown server";
        }
        try {
            ServerIdentity identity = source.identity();
            return shortServer(identity.serverSoftware(), identity.platform(), identity.mcVersion());
        } catch (RuntimeException unavailable) {
            return "unknown server";
        }
    }

    static String shortServer(String software, String platform, String mcVersion) {
        String[] words = Strings.trimToEmpty(software).split("\\s+");
        String name = words.length > 0 && !words[0].isEmpty() ? words[0] : Strings.trimToEmpty(platform);
        if (name.isEmpty()) {
            return "unknown server";
        }
        // A backend's Minecraft version is the useful number. A proxy has none, so its own version,
        // the word after its name, stands in.
        String version = leadingVersion(mcVersion);
        if (version.isEmpty() && words.length > 1) {
            version = leadingVersion(words[1]);
        }
        return version.isEmpty() ? name : name + " " + version;
    }

    private static String leadingVersion(String raw) {
        Matcher matcher = LEADING_VERSION.matcher(Strings.trimToEmpty(raw));
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * The protocol the tunnel is actually speaking, and the config version behind it.
     *
     * <p>{@code v2-compat} on a connected socket is the failure worth surfacing: the bot believes it
     * is talking to a v2 plugin, pushes no configuration, and everything runs on built-in defaults
     * while the dashboard shows settings that are not reaching anybody.
     */
    private static String describeTunnel(TunnelClient tunnel) {
        if (!tunnel.isConnected()) {
            String why = tunnel.settings().isConfigured() ? "" : ", tunnel settings incomplete";
            return "not connected (" + tunnel.mode().name().toLowerCase(Locale.ROOT) + why + ")";
        }
        int version = tunnel.configVersion();
        return "connected, protocol " + tunnel.mode().name().toLowerCase(Locale.ROOT)
                + ", bot config version " + (version < 0 ? "none yet" : Integer.toString(version));
    }

    /**
     * Whether anything reading the console feed has been dropped for throwing.
     *
     * <p>This is the only place that number is ever reported, and it has to be: the drop happens
     * inside the log-capture path, where writing a line would be captured and fed back into the loop
     * the re-entrancy guard exists to break. So the tap counts silently and this asks.
     */
    private static String describeConsole(HeimdallRuntime runtime) {
        int dropped;
        try {
            dropped = runtime.platform().console().droppedTapConsumers();
        } catch (RuntimeException unavailable) {
            return "unknown (" + unavailable + ")";
        }
        return dropped == 0
                ? "ok"
                : dropped + " consumer(s) threw and were unsubscribed; whatever was reading the "
                        + "console feed has stopped receiving it";
    }

    private static String describeUpdates(AdminContext context) {
        UpdateAdmin updates = context.updates();
        if (!updates.isSupported()) {
            return "no self-updater in this build";
        }
        if (!updates.isUpdateAvailable()) {
            return "up to date as far as the last check knows";
        }
        return "version " + updates.latestVersion() + " is available, run /" + context.label()
                + " update";
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /**
     * The endpoint's host when it is not the default instance's, else {@code ""}.
     *
     * <p>Only a whitelabel endpoint earns space on the summary row; everybody else's is the same.
     * Compared by host, so a scheme, a port or a trailing path does not make the default look like
     * a whitelabel instance.
     */
    static String endpointHost(String endpoint) {
        String trimmed = Strings.trimToEmpty(endpoint);
        if (trimmed.isEmpty()) {
            return "";
        }
        String host = hostOf(trimmed);
        return host.equalsIgnoreCase(hostOf(SetupSubcommand.DEFAULT_ENDPOINT)) ? "" : host;
    }

    private static String hostOf(String endpoint) {
        try {
            String host = new URI(endpoint).getHost();
            return host == null ? endpoint : host;
        } catch (Exception unparseable) {
            return endpoint;
        }
    }

    /** {@code 45s ago}, {@code 4m ago}, {@code 3h ago}, {@code 2d ago}. */
    static String ago(long millis) {
        return span(millis) + " ago";
    }

    /** {@code 45s}, {@code 4m}, {@code 3h}, {@code 2d}: one unit, rounded down. */
    static String span(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + "m";
        }
        long hours = minutes / 60L;
        if (hours < 48L) {
            return hours + "h";
        }
        return (hours / 24L) + "d";
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "\u2026";
    }

    private static String plural(int count, String noun) {
        return String.format(Locale.ROOT, "%,d", count) + " " + noun + (count == 1 ? "" : "s");
    }

    private static String orNone(String value) {
        return Strings.isBlank(value) ? "none" : value;
    }
}
