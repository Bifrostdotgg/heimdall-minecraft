package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.text.Msg;
import com.heimdall.core.util.Strings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The two verbs about the whitelist: {@code test} and {@code cache}.
 *
 * <p>Both reach the module through {@link WhitelistAdmin}, because core cannot depend on
 * {@code :module-whitelist} and a verb that only works when a module is installed still has to exist
 * when it is not — otherwise a build without the module has a command tree with a hole in it and
 * nothing to explain the hole.
 */
final class WhitelistSubcommands {

    private WhitelistSubcommands() {
    }

    /**
     * {@code /hd test <player>} — what would happen if this player joined, without them joining.
     *
     * <h2>It runs the interceptor, not a bot call</h2>
     *
     * <p>v2's {@code /hwl test} made a bare {@code connection-attempt} and printed four fields out
     * of the answer. That is a useful thing to know and it is <em>not</em> the question an operator
     * is asking, because the login decision is not the bot's alone: a bypassed UUID never reaches
     * the bot, a backend with {@code enforceOnBackend} off abstains, a warm mirror answers during an
     * outage, and the fallback mode decides when nothing else could. Every one of those is a real
     * support case and v2's test command was blind to all of them.
     *
     * <p>So this drives the whole interceptor and reports which check decided. The one difference
     * from a real login is that nothing is written — see {@link LoginProbe} — because an operator
     * testing whether somebody <em>can</em> join must not thereby cache them as somebody who did.
     *
     * <h2>Threading</h2>
     *
     * <p>Blocking, with a retry budget behind it, so it hands off to {@code heimdall-io} after
     * acknowledging. The acknowledgement is not politeness: at the default budget a probe against an
     * unreachable bot takes sixteen seconds, and sixteen seconds of silence reads as a command that
     * did not run.
     */
    static final class Test implements AdminSubcommand {

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String usage() {
            return "<player>";
        }

        @Override
        public String description() {
            return "run the whole login check for a player, changing nothing";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            if (args.isEmpty()) {
                source.sendMessage(Msg.legacy("§cUsage: §f/" + context.label() + " test <player>"));
                return;
            }
            if (!context.whitelist().isAvailable()) {
                source.sendMessage(Msg.legacy("§eThe whitelist module is not running, so nothing "
                        + "would gate that login."));
                return;
            }
            final String player = args.get(0);
            source.sendMessage(Msg.legacy("§7Testing the login path for §f" + player + "§7…"));
            context.async(new Runnable() {
                @Override
                public void run() {
                    report(source, context, player);
                }
            });
        }

        private static void report(CommandSource source, AdminContext context, String player) {
            LoginProbe probe;
            try {
                probe = context.whitelist().probe(player);
            } catch (RuntimeException failed) {
                source.sendMessage(Msg.legacy("§cThe probe itself failed: " + failed));
                return;
            }
            source.sendMessage(Msg.legacy("§6" + probe.username() + " §8(" + probe.uuid() + ")"));
            source.sendMessage(Msg.legacy("§7verdict: "
                    + (probe.allowed() ? "§awould be let in" : "§cwould be refused")));
            source.sendMessage(Msg.legacy("§7decided by: §f" + probe.stage()));
            source.sendMessage(Msg.legacy("§7in the local mirror: §f"
                    + (probe.mirrored() ? "yes" : "no")));
            if (probe.queuePosition() != null) {
                source.sendMessage(Msg.legacy("§7queue position: §f#" + probe.queuePosition()));
            }
            if (Strings.isNotBlank(probe.message())) {
                source.sendMessage(Msg.legacy("§7they would see: §f" + probe.message()));
            }
        }

        /** Online players only, which is all {@code PlayerDirectory} knows and all this can offer. */
        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            if (args.size() > 1) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<String>();
            for (PlayerHandle player : context.runtime().platform().players().onlinePlayers()) {
                names.add(player.name());
            }
            return names;
        }
    }

    /**
     * {@code /hd cache stats|clear|cleanup} — the local whitelist mirror.
     *
     * <p>All three on both platforms, which v2 did not manage: its proxy build shipped
     * {@code stats} and {@code clear} and never grew {@code cleanup}, so a Velocity network's mirror
     * only ever shrank when a read happened to evict an entry. Nothing anywhere said so.
     *
     * <p>{@code clear} is the destructive one and it says so before doing it, because the
     * consequence is delayed and asymmetric: with the default {@code whitelist-only} fallback, an
     * empty mirror means the next bot outage refuses everybody, and the pre-warm poll that refills
     * it runs every five minutes rather than immediately.
     */
    static final class Cache implements AdminSubcommand {

        @Override
        public String name() {
            return "cache";
        }

        @Override
        public String usage() {
            return "<stats|clear|cleanup|sync>";
        }

        @Override
        public String description() {
            return "inspect, empty, sweep or refresh the local whitelist mirror";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            final WhitelistAdmin whitelist = context.whitelist();
            if (!whitelist.isAvailable()) {
                source.sendMessage(Msg.legacy("§eThe whitelist module is not running, so there is "
                        + "no mirror to work on."));
                return;
            }
            String verb = args.isEmpty() ? "stats" : args.get(0).toLowerCase(Locale.ROOT);
            if ("stats".equals(verb)) {
                source.sendMessage(Msg.legacy("§7Whitelist mirror: §f" + whitelist.stats()));
                return;
            }
            if ("clear".equals(verb)) {
                whitelist.clear();
                source.sendMessage(Msg.legacy("§aMirror emptied. §7Until the next pre-warm poll "
                        + "lands, a bot outage would refuse every player."));
                return;
            }
            if ("cleanup".equals(verb)) {
                int removed = whitelist.cleanup();
                source.sendMessage(Msg.legacy("§aSwept §f" + removed
                        + "§a expired entr" + (removed == 1 ? "y" : "ies") + ". §7"
                        + whitelist.stats()));
                return;
            }
            if ("sync".equals(verb)) {
                source.sendMessage(Msg.legacy("§7Pulling the full whitelist from the bot…"));
                context.async(new Runnable() {
                    @Override
                    public void run() {
                        whitelist.syncNow();
                        source.sendMessage(Msg.legacy("§aSync finished. §7" + whitelist.stats()));
                    }
                });
                return;
            }
            source.sendMessage(Msg.legacy("§cUsage: §f/" + context.label() + " cache <stats|clear|cleanup|sync>"));
        }

        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            return args.size() <= 1
                    ? Arrays.asList("stats", "clear", "cleanup", "sync")
                    : Collections.<String>emptyList();
        }
    }
}
