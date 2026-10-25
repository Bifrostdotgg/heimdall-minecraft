package com.heimdall.core.admin;

import com.heimdall.core.command.CommandCompleter;
import com.heimdall.core.command.CommandHandler;
import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.command.CommandSource;
import com.heimdall.core.command.CommandSpec;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The admin command itself: one verb, ten subcommands, and one deprecated alias.
 *
 * <h2>Two names, and they cannot collide</h2>
 *
 * <p>{@code /hd} (also {@code /heimdall}) on the Bukkit family, {@code /hdp} (also
 * {@code /heimdallproxy}) on the proxy. Departure D47 has the reasoning: in a proxied network both
 * plugins are installed and the proxy claims a name before the backend ever sees it, so a single
 * shared verb means a command whose meaning depends on where the player is standing.
 *
 * <h2>{@code /hwl} still answers, once, and then says so</h2>
 *
 * <p>Every v2 install's operators, runbooks and staff macros say {@code /hwl}. Removing it outright
 * would turn "Unknown command" into the first thing a migrating server sees, so it is registered on
 * <em>both</em> platforms — v2 used it on both — forwards to the same tree, and warns the sender
 * <strong>once per server start</strong> rather than once per use. Once per use trains people to
 * ignore it; never is how a deprecated name survives forever. It is excluded from the help listing
 * for the same reason: help is for what to type next, not for what used to work.
 *
 * <h2>Permission</h2>
 *
 * <p>Per verb, not at the door. Every verb needs {@code heimdall.admin}, except the punishment
 * verbs, which also accept their own {@code heimdall.punishments.*} node (see
 * {@link AdminSubcommand#permits}). A moderator granted {@code heimdall.punishments.ban} can run
 * {@code /hd ban} and nothing else here, and sees only that verb in help and completion.
 *
 * <p>So the spec declares no permission, because one node cannot say "admin or any of these", and
 * this class does the gating itself in {@link #dispatch} and {@link #suggest}. A sender who may run
 * no verb at all gets the same refusal the registrar used to give. On the Bukkit family the
 * descriptor in {@code plugin.yml} still names the nodes, separated by {@code ;} (which Bukkit reads
 * as "any of"), so the command stays hidden from players who hold none of them. The proxies have no
 * descriptor, so there {@code /hdp} is visible to everybody and refused here.
 *
 * <p>Both a player and the console may run everything. That is load-bearing for
 * {@code /hd setup} — the smoke harness drives it down a console pipe, and an operator claiming a
 * server usually does so from a terminal rather than from in-game.
 *
 * <h2>Threading</h2>
 *
 * <p>Dispatch is on whatever thread the platform used. Subcommands that block hand off themselves;
 * see {@link AdminSubcommand}.
 */
public final class AdminCommand {

    /** The node that runs every verb here; the punishment verbs also take their own node. */
    public static final String PERMISSION = "heimdall.admin";

    /** v2's admin verb, kept as a hidden forwarding alias on both platforms. */
    public static final String DEPRECATED_NAME = "hwl";

    private final AdminContext context;

    /** The verb this tree is really called on this platform — {@code hd} or {@code hdp}. */
    private final String primaryName;

    private final Map<String, AdminSubcommand> subcommands = new LinkedHashMap<String, AdminSubcommand>();

    /**
     * Whether the {@code /hwl} deprecation notice has already gone out this session.
     *
     * <p>Per server start, not per sender: the point is to tell whoever is running the server that
     * the name changed, and a warning that repeats is a warning people learn to scroll past.
     */
    private final AtomicBoolean warnedAboutDeprecatedName = new AtomicBoolean();

    private AdminCommand(AdminContext context, String primaryName) {
        this.context = context;
        this.primaryName = primaryName;
        for (AdminSubcommand subcommand : defaultSubcommands()) {
            subcommands.put(subcommand.name(), subcommand);
        }
    }

    /** The tree every platform ships. Order is the order the help listing prints them in. */
    private static List<AdminSubcommand> defaultSubcommands() {
        return Arrays.asList(
                new SetupSubcommand(),
                new StatusSubcommand(),
                new IdentitySubcommand(),
                new RuntimeSubcommands.Reload(),
                new RuntimeSubcommands.Modules(),
                new RuntimeSubcommands.Enable(),
                new RuntimeSubcommands.Disable(),
                new WhitelistSubcommands.Test(),
                new WhitelistSubcommands.Cache(),
                new PunishmentSubcommands.Offense(),
                PunishmentSubcommands.verb("ban", "<player> [duration] [reason]", "ban a player", "ban"),
                PunishmentSubcommands.verb("tempban", "<player> <duration> [reason]", "temporarily ban a player", "tempban"),
                PunishmentSubcommands.verb("ipban", "<player> [duration] [reason]", "IP-ban a player", "ipban"),
                PunishmentSubcommands.verb("mute", "<player> [duration] [reason]", "mute a player", "mute"),
                PunishmentSubcommands.verb("tempmute", "<player> <duration> [reason]", "temporarily mute a player", "tempmute"),
                PunishmentSubcommands.verb("unmute", "<player> [reason]", "revoke a mute", "unmute"),
                PunishmentSubcommands.verb("kick", "<player> [reason]", "kick a player", "kick"),
                PunishmentSubcommands.verb("warn", "<player> [reason]", "warn a player", "warn"),
                PunishmentSubcommands.verb("unwarn", "<player> [reason]", "revoke a warn", "unwarn"),
                PunishmentSubcommands.verb("unban", "<player> [reason]", "revoke a ban", "unban"),
                PunishmentSubcommands.verb("rollback", "<player>", "revoke the latest active punishment", "rollback"),
                PunishmentSubcommands.verb("history", "<player>", "punishment history", "history"),
                PunishmentSubcommands.verb("staffhistory", "<staff>", "punishments issued by staff", "staffhistory"),
                PunishmentSubcommands.verb("banlist", "", "list active bans", "banlist"),
                PunishmentSubcommands.verb("dupeip", "<player>", "accounts sharing a last address", "dupeip"),
                PunishmentSubcommands.verb("iphistory", "<player>", "local address history", "iphistory"),
                PunishmentSubcommands.verb("import", "[--dry-run]", "import punishment history (LiteBans and others)", "import"),
                new CaseSubcommand(),
                new UpdateSubcommands.Version(),
                new UpdateSubcommands.Update(),
                new UpdateSubcommands.Swap(),
                new RuntimeSubcommands.Debug());
    }

    /**
     * Builds the tree and registers it under {@code primary}, plus the hidden {@code /hwl} alias.
     *
     * <p>Registered through {@link CommandRegistrar} rather than by each platform's own idiom, so
     * both get the same permission gate, the same containment of a handler that throws, and the same
     * behaviour when another plugin has claimed the name.
     *
     * @param primary {@code hd} on the Bukkit family, {@code hdp} on the proxy
     * @param aliases the spelled-out form — {@code heimdall} or {@code heimdallproxy}
     * @return a handle that unregisters everything this installed, in reverse
     */
    public static Registration install(
            CommandRegistrar registrar, AdminContext context, String primary, List<String> aliases) {
        if (registrar == null || context == null) {
            throw new IllegalArgumentException("a registrar and a context are required");
        }
        AdminCommand command = new AdminCommand(context, primary);
        final List<Registration> handles = new ArrayList<Registration>();
        handles.add(registrar.register(command.spec(primary, aliases, false)));
        // `heimdallwhitelist` was v2's spelled-out form of /hwl on BOTH platforms, so it comes along
        // as an alias of the deprecated verb — a runbook that spells it out keeps working. On Bukkit
        // the descriptor must also declare it (plugin.yml does); on Velocity the registrar binds it.
        handles.add(registrar.register(
                command.spec(DEPRECATED_NAME, Collections.singletonList("heimdallwhitelist"), true)));
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                Collections.reverse(handles);
                for (Registration handle : handles) {
                    handle.close();
                }
            }
        });
    }

    /** One registrable command, either the real verb or the deprecated alias forwarding to it. */
    private CommandSpec spec(final String name, List<String> aliases, final boolean deprecated) {
        return CommandSpec.named(name)
                .aliases(aliases)
                .permission("")
                .usage("/" + name + " <" + String.join("|", subcommands.keySet()) + ">")
                .description("Heimdall administration")
                .handler(new CommandHandler() {
                    @Override
                    public void execute(CommandSource source, List<String> args) {
                        if (!permitsAny(source)) {
                            source.sendMessage(Msg.legacy(
                                    "§cYou do not have permission to use that."));
                            return;
                        }
                        // Only for someone who runs the server: a moderator using /hwl ban would
                        // otherwise spend the once-per-start notice before an admin ever saw it.
                        if (deprecated && source.hasPermission(PERMISSION)) {
                            warnAboutDeprecatedName(source);
                        }
                        dispatch(source, args, name);
                    }
                })
                .completer(new CommandCompleter() {
                    @Override
                    public List<String> complete(CommandSource source, List<String> args) {
                        return suggest(source, args);
                    }
                })
                .build();
    }

    /**
     * Says the name changed, once, and then gets out of the way.
     *
     * <p>Once per server start rather than once per invocation, and the trade is deliberate: a
     * notice attached to every use is one people learn to scroll past, and the command still works
     * either way, so the cost of a second operator never seeing it is that they keep typing a verb
     * that keeps working. The cost of the other choice is a line of noise on every staff macro that
     * has not been updated yet.
     */
    private void warnAboutDeprecatedName(CommandSource source) {
        if (!warnedAboutDeprecatedName.compareAndSet(false, true)) {
            return;
        }
        source.sendMessage(Msg.legacy("§e/" + DEPRECATED_NAME + " is v2's name for this command. "
                + "Use §f/" + primaryName + "§e from now on; the old name still works but will be "
                + "removed."));
    }

    private void dispatch(CommandSource source, List<String> args, String label) {
        if (args.isEmpty()) {
            help(source, label);
            return;
        }
        String verb = args.get(0).toLowerCase(Locale.ROOT);
        AdminSubcommand subcommand = subcommands.get(verb);
        if (subcommand == null) {
            source.sendMessage(Msg.legacy("§cNo such subcommand: §f" + verb));
            help(source, label);
            return;
        }
        if (!subcommand.permits(source)) {
            source.sendMessage(Msg.legacy("§cYou do not have permission to use that."));
            return;
        }
        subcommand.run(source, args.subList(1, args.size()), context);
    }

    private void help(CommandSource source, String label) {
        // The connection line names the guild, this machine's identity and the bot's last error,
        // which is the administrator's business, not a moderator's who can only run /hd ban.
        source.sendMessage(Msg.legacy("§6Heimdall §7v" + context.pluginVersion()
                + (source.hasPermission(PERMISSION)
                        ? " §8- §7" + context.runtime().connectionStatus()
                        : "")));
        for (AdminSubcommand subcommand : subcommands.values()) {
            if (!subcommand.permits(source)) {
                continue;
            }
            String usage = subcommand.usage().isEmpty() ? "" : " " + subcommand.usage();
            source.sendMessage(Msg.legacy(
                    "§7/" + label + " §f" + subcommand.name() + "§7" + usage
                            + " §8- §7" + subcommand.description()));
        }
    }

    /**
     * Completion for the tree.
     *
     * <p>Filtered by prefix here rather than in each subcommand, so ten handlers do not each carry
     * their own copy of a {@code startsWith} loop that one of them would eventually get wrong.
     */
    private List<String> suggest(CommandSource source, List<String> args) {
        if (args.size() <= 1) {
            String prefix = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
            List<String> verbs = new ArrayList<String>();
            for (AdminSubcommand subcommand : subcommands.values()) {
                if (subcommand.permits(source)) {
                    verbs.add(subcommand.name());
                }
            }
            return matching(verbs, prefix);
        }
        AdminSubcommand subcommand = subcommands.get(args.get(0).toLowerCase(Locale.ROOT));
        if (subcommand == null || !subcommand.permits(source)) {
            return Collections.emptyList();
        }
        List<String> rest = args.subList(1, args.size());
        return matching(
                subcommand.complete(source, rest, context),
                rest.get(rest.size() - 1).toLowerCase(Locale.ROOT));
    }

    /** Whether {@code source} may run any verb here at all. */
    private boolean permitsAny(CommandSource source) {
        for (AdminSubcommand subcommand : subcommands.values()) {
            if (subcommand.permits(source)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<String>();
        for (String candidate : candidates) {
            if (candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
