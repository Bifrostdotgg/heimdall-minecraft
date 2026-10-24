package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.config.BootstrapConfig;
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
import java.util.List;
import java.util.Locale;
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
 * Tunnel, Modules, Mirror, Updates), one line per actual problem, and a footer naming the detail
 * views. Healthy is seven lines and it never passes ten, because a status screen that scrolls off the
 * top of the chat box is one nobody reads. The rows never move, so the eye learns where to look.
 *
 * <p>{@code status bot|modules|server} each print one section in full, and {@code status all}
 * prints all three for the console and for pasting into a support ticket. Everything the old single
 * screen printed is still in one of them.
 *
 * <h2>Every confusable state is still a different line</h2>
 *
 * <p>The summary is shorter, not vaguer. These pairs in particular each still produce a visibly
 * different row and a problem line:
 *
 * <ul>
 *   <li><em>Not set up</em> versus <em>set up but still discovering its guild</em> versus <em>set up
 *       and the bot refused the token</em>. The middle one looks like a network problem and is not;
 *       the third one looks like a network problem and is a revoked credential. The problem line is
 *       {@link HeimdallRuntime#connectionStatus()} verbatim, so both platforms say the same thing.
 *   <li><em>v3</em> versus <em>v2-compat</em>. A tunnel that negotiated down runs on built-in
 *       defaults with no dashboard control at all, and looks perfectly connected while doing it.
 *   <li>Per module: <em>switched off</em>, <em>running</em>, <em>failed to start</em>, <em>switched
 *       off locally</em>. A failed module is named in its own problem line.
 *   <li>The console feed being quiet because nothing is happening, versus being quiet because a
 *       consumer threw and was silently unsubscribed. That is the {@code droppedTapConsumers}
 *       counter, and this is the only place it is ever reported, because the drop cannot log for
 *       itself: it happens inside the logging pipeline.
 * </ul>
 *
 * <h2>Colour is never the only signal</h2>
 *
 * <p>Each row's dot is coloured by its health, and its words say the same thing ("FAILED", "not
 * connected"), because the console log and a pasted screenshot-as-text lose the colour. The rows and
 * the footer are clickable on servers that render click events (Paper through Adventure, Velocity);
 * BungeeCord and the console get legacy text, so nothing is only behind a click or a hover.
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

    /** The endpoint {@code /hd setup} uses when given none; only a different one is worth a word. */
    private static final String DEFAULT_ENDPOINT = SetupSubcommand.DEFAULT_ENDPOINT;

    /**
     * The floor below which a mirror is never called stale, whatever the poll interval says.
     *
     * <p>A short interval and one slow poll should not turn the row yellow.
     */
    private static final long STALE_FLOOR_MS = TimeUnit.MINUTES.toMillis(15);

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
                summary(source, context);
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
                source.sendMessage(Msg.legacy(header(context) + " §8- §7full status"));
                botSection(source, context);
                modulesSection(source, context);
                serverSection(source, context);
                return;
            default:
                source.sendMessage(Msg.legacy("§cUnknown section \"" + args.get(0) + "\". §7Try §f/"
                        + context.label() + " status " + String.join("|", SECTIONS) + "§7."));
        }
    }

    // ── The summary ──────────────────────────────────────────────────────────

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

    /** One line under the rows: what is wrong, in a sentence that says what to do about it. */
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
    }

    private void summary(CommandSource source, AdminContext context) {
        Report report = assess(context);
        String label = context.label();

        source.sendMessage(Msg.legacy(header(context)));
        for (Row row : report.rows) {
            Component line = Msg.legacy(" " + row.health.colour + DOT + " §7" + row.label + " §f"
                    + row.value);
            source.sendMessage(clickable(line, label, row.section));
        }

        List<Problem> ordered = new ArrayList<Problem>();
        for (Problem problem : report.problems) {
            if (problem.health == Health.BROKEN) {
                ordered.add(problem);
            }
        }
        for (Problem problem : report.problems) {
            if (problem.health != Health.BROKEN) {
                ordered.add(problem);
            }
        }
        int shown = ordered.size() <= MAX_PROBLEM_LINES ? ordered.size() : MAX_PROBLEM_LINES - 1;
        for (int i = 0; i < shown; i++) {
            Problem problem = ordered.get(i);
            source.sendMessage(Msg.legacy(" " + problem.health.colour + WARN + " §f" + problem.text));
        }
        if (ordered.size() > shown) {
            int more = ordered.size() - shown;
            source.sendMessage(clickable(Msg.legacy(" §8+" + more + " more: §7/" + label
                    + " status all"), label, "all"));
        }

        source.sendMessage(footer(label));
    }

    /** {@code Heimdall 3.0.0-rc.22 · enforcer · Purpur 26.2}: what this is, in one short line. */
    private static String header(AdminContext context) {
        return "§6Heimdall §f" + context.pluginVersion() + " §8· §7" + context.role().wireName()
                + " §8· §7" + shortServer(context.runtime());
    }

    /**
     * {@code » /hd status bot · modules · server · all}, each word running its view when clicked.
     */
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

    /** Works out every row and every problem. Package-private so the tests can read it directly. */
    static Report assess(AdminContext context) {
        Report report = new Report();
        HeimdallRuntime runtime = context.runtime();
        String label = context.label();

        boolean botUp = assessBot(report, runtime, label);
        assessTunnel(report, runtime.tunnel(), botUp, label);
        assessModules(report, runtime, label);
        assessMirror(report, context.whitelist(), label);
        assessUpdates(report, context.updates(), label);

        int dropped = droppedTapConsumers(runtime);
        if (dropped > 0) {
            report.problem(Health.BROKEN, dropped + " console feed reader(s) threw and were "
                    + "unsubscribed, so whatever was reading the console has stopped receiving it.");
        }
        return report;
    }

    /**
     * The Bot row, from the same states {@link HeimdallRuntime#connectionStatus()} describes.
     *
     * @return whether the bot side is far enough along that the tunnel row should be judged at all
     */
    private static boolean assessBot(Report report, HeimdallRuntime runtime, String label) {
        if (!runtime.isConfigured()) {
            report.rows.add(new Row("Bot", Health.BROKEN, "not set up", "bot"));
            report.problem(Health.BROKEN, "This server is not set up. Mint a code on the "
                    + "Minecraft page of the dashboard, then run /" + label + " setup <code>.");
            return false;
        }
        IdentityGuard.Decision identity = runtime.identity();
        if (identity != null && identity.blocksTunnel()) {
            report.rows.add(new Row("Bot", Health.BROKEN, "blocked: identity mismatch", "bot"));
            report.problem(Health.BROKEN, runtime.connectionStatus());
            return false;
        }
        GuildDiscovery.Status discovery = runtime.discoveryStatus();
        if (discovery != null) {
            switch (discovery) {
                case TOKEN_REFUSED:
                    report.rows.add(new Row("Bot", Health.BROKEN, "token refused", "bot"));
                    report.problem(Health.BROKEN, runtime.connectionStatus());
                    return false;
                case UNREACHABLE:
                    report.rows.add(new Row("Bot", Health.BROKEN, "cannot reach the bot", "bot"));
                    report.problem(Health.BROKEN, runtime.connectionStatus());
                    return false;
                case DISCOVERING:
                default:
                    report.rows.add(new Row("Bot", Health.ATTENTION, "finding its guild", "bot"));
                    report.problem(Health.ATTENTION, runtime.connectionStatus());
                    return false;
            }
        }

        String via = endpointHost(runtime.bootstrap());
        String value = "connected, guild " + orNone(runtime.guildId())
                + (via.isEmpty() ? "" : " via " + via);
        Health health = Health.OK;
        if (identity != null && identity.isMismatch()) {
            health = Health.ATTENTION;
            report.problem(Health.ATTENTION, "These credentials were bound to another machine "
                    + "(warning only). Run /" + label + " identity.");
        }
        if (runtime.api().availability() != HeimdallApi.Availability.READY) {
            health = Health.ATTENTION;
            report.problem(Health.ATTENTION, "The tunnel is up but the bot API says \""
                    + runtime.api().describe() + "\". Details: /" + label + " status bot.");
        }
        report.rows.add(new Row("Bot", health, value, "bot"));
        return true;
    }

    /**
     * The Tunnel row. A down tunnel is idle while the bot row is already the problem, so one cause
     * is one line. A connected one is judged either way: a server still rediscovering its guild can
     * be running on the cached one, tunnel and all.
     */
    private static void assessTunnel(Report report, TunnelClient tunnel, boolean botUp, String label) {
        if (!tunnel.isConnected()) {
            if (!botUp) {
                report.rows.add(new Row("Tunnel", Health.IDLE, "waiting on the bot", "bot"));
                return;
            }
            report.rows.add(new Row("Tunnel", Health.BROKEN, "not connected", "bot"));
            report.problem(Health.BROKEN, "The live connection to the bot is down, so dashboard "
                    + "changes are not reaching this server. Details: /" + label + " status bot.");
            return;
        }
        int version = tunnel.configVersion();
        if (tunnel.mode() == ProtocolMode.V2_COMPAT) {
            report.rows.add(new Row("Tunnel", Health.BROKEN, "protocol v2-compat", "bot"));
            report.problem(Health.BROKEN, "The bot only offered the old v2 protocol, so this server "
                    + "runs on built-in defaults and dashboard settings do not reach it.");
            return;
        }
        if (version < 0) {
            report.rows.add(new Row("Tunnel", Health.ATTENTION, "protocol v3, no config yet", "bot"));
            report.problem(Health.ATTENTION, "Connected, but the bot has not sent this server's "
                    + "settings yet.");
            return;
        }
        report.rows.add(new Row("Tunnel", Health.OK, "protocol v3, config v" + version, "bot"));
    }

    /** The Modules row: counts, with failed and locally disabled modules named underneath. */
    private static void assessModules(Report report, HeimdallRuntime runtime, String label) {
        ModuleManager modules = runtime.modules();
        Set<String> ids = modules.registeredIds();
        if (ids.isEmpty()) {
            report.rows.add(new Row("Modules", Health.IDLE, "none in this build", "modules"));
            return;
        }
        Set<String> locallyOff = runtime.locallyDisabledModules();
        int running = 0;
        int off = 0;
        List<String> failed = new ArrayList<String>();
        for (String id : ids) {
            ModuleState state = modules.state(id);
            if (locallyOff.contains(id)) {
                off++;
            } else if (state == ModuleState.ENABLED) {
                running++;
            } else if (state == ModuleState.FAILED) {
                failed.add(id);
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
                report.problem(Health.BROKEN, id + " failed to start. See the server log, then "
                        + "toggle it off and on.");
            }
        }
        if (!locallyOff.isEmpty()) {
            if (health == Health.OK) {
                health = Health.ATTENTION;
            }
            report.problem(Health.ATTENTION, "Locally disabled: " + String.join(", ", locallyOff)
                    + ". /" + label + " enable <module> hands it back to the dashboard.");
        }
        report.rows.add(new Row("Modules", health, value.toString(), "modules"));
    }

    /** The Mirror row: how many players, and how long since the bot last confirmed them. */
    private static void assessMirror(Report report, WhitelistAdmin whitelist, String label) {
        if (!whitelist.isAvailable()) {
            report.rows.add(new Row("Mirror", Health.IDLE, "not used (whitelist is off)", "modules"));
            return;
        }
        int entries = whitelist.mirrorEntries();
        String count = entries < 0 ? "unknown size" : plural(entries, "player");
        long interval = whitelist.syncIntervalMs();
        long at = whitelist.lastSyncAtMs();
        if (interval <= 0L) {
            report.rows.add(new Row("Mirror", Health.OK, count + ", pre-warm off", "modules"));
            return;
        }
        if (at <= 0L) {
            report.rows.add(new Row("Mirror", Health.ATTENTION, count + ", never synced", "modules"));
            report.problem(Health.ATTENTION, "The whitelist mirror has not synced with the bot yet. "
                    + "/" + label + " cache sync pulls it now.");
            return;
        }
        long age = Math.max(0L, System.currentTimeMillis() - at);
        String value = count + ", synced " + ago(age);
        if (age > Math.max(STALE_FLOOR_MS, interval * 3L)) {
            report.rows.add(new Row("Mirror", Health.ATTENTION, value, "modules"));
            report.problem(Health.ATTENTION, "The whitelist mirror last synced " + ago(age)
                    + " but should every " + span(interval) + ". /" + label
                    + " cache sync pulls it now.");
            return;
        }
        report.rows.add(new Row("Mirror", Health.OK, value, "modules"));
    }

    /** The Updates row. */
    private static void assessUpdates(Report report, UpdateAdmin updates, String label) {
        if (!updates.isSupported()) {
            report.rows.add(new Row("Updates", Health.IDLE, "no self-updater in this build", "server"));
            return;
        }
        if (updates.isUpdateAvailable()) {
            report.rows.add(new Row("Updates", Health.ATTENTION,
                    updates.latestVersion() + " available", "server"));
            report.problem(Health.ATTENTION, updates.latestVersion() + " is out: /" + label
                    + " update");
            return;
        }
        report.rows.add(new Row("Updates", Health.OK, "up to date", "server"));
    }

    // ── The detail views ─────────────────────────────────────────────────────

    /** Everything about how this server reaches its bot. */
    private static void botSection(CommandSource source, AdminContext context) {
        HeimdallRuntime runtime = context.runtime();
        BootstrapConfig bootstrap = runtime.bootstrap();
        source.sendMessage(Msg.legacy("§6Bot connection"));
        // The endpoint is the whitelabel field: a customer claiming against their own instance has a
        // different one, and "which bot is this server actually talking to?" is the first question a
        // whitelabel support conversation asks.
        detail(source, "endpoint", orNone(bootstrap.endpoint()));
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
        ServerIdentity identity = identity(runtime);
        if (identity == null) {
            return "unknown server software";
        }
        String software = Strings.isBlank(identity.serverSoftware())
                ? identity.platform() : identity.serverSoftware();
        String version = Strings.isBlank(identity.mcVersion()) ? "" : " " + identity.mcVersion();
        return orNone(software) + version;
    }

    /**
     * The header's version of {@link #describeServer}: the software's name and its leading version
     * number, so {@code Purpur 26.2-2622-48bcd91 (MC: 26.2) 26.2.build.2622-stable} reads
     * {@code Purpur 26.2}. The full string is in {@code status server}.
     */
    static String shortServer(HeimdallRuntime runtime) {
        ServerIdentity identity = identity(runtime);
        if (identity == null) {
            return "unknown server";
        }
        return shortServer(identity.serverSoftware(), identity.platform(), identity.mcVersion());
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

    private static ServerIdentity identity(HeimdallRuntime runtime) {
        IdentitySource source = runtime.identitySource();
        if (source == null) {
            return null;
        }
        try {
            return source.identity();
        } catch (RuntimeException unavailable) {
            return null;
        }
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
            return "not connected (" + tunnel.mode().name().toLowerCase(Locale.ROOT) + ")";
        }
        int version = tunnel.configVersion();
        return "connected, protocol " + tunnel.mode().name().toLowerCase(Locale.ROOT)
                + ", bot config version " + (version < 0 ? "none yet" : Integer.toString(version));
    }

    /** Whether anything reading the console feed has been dropped for throwing. */
    private static String describeConsole(HeimdallRuntime runtime) {
        int dropped = droppedTapConsumers(runtime);
        if (dropped < 0) {
            return "unknown";
        }
        return dropped == 0
                ? "ok"
                : dropped + " consumer(s) threw and were unsubscribed; whatever was reading the "
                        + "console feed has stopped receiving it";
    }

    /**
     * The dropped-consumer count, or {@code -1} if the platform could not say.
     *
     * <p>This is the only place that number is ever reported, and it has to be: the drop happens
     * inside the log-capture path, where writing a line would be captured and fed back into the loop
     * the re-entrancy guard exists to break. So the tap counts silently and this asks.
     */
    private static int droppedTapConsumers(HeimdallRuntime runtime) {
        try {
            return runtime.platform().console().droppedTapConsumers();
        } catch (RuntimeException unavailable) {
            return -1;
        }
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
     * The endpoint's host when it is not the default, else {@code ""}.
     *
     * <p>Only a whitelabel endpoint earns space on the summary row; everybody else's is the same.
     */
    static String endpointHost(BootstrapConfig bootstrap) {
        String endpoint = Strings.trimToEmpty(bootstrap.endpoint());
        if (endpoint.isEmpty() || endpoint.equalsIgnoreCase(DEFAULT_ENDPOINT)) {
            return "";
        }
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

    private static String plural(int count, String noun) {
        return String.format(Locale.ROOT, "%,d", count) + " " + noun + (count == 1 ? "" : "s");
    }

    private static String orNone(String value) {
        return Strings.isBlank(value) ? "none" : value;
    }
}
