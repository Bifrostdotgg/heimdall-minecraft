package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.text.Msg;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hd offense reload|types} — the offense-type cache behind {@code /offend}.
 *
 * <p>Both verbs exist for one support question: "why will that slug not tab-complete?" The answers
 * are that the type is disabled, that the cache is stale, or that the slug does not exist — and the
 * two verbs together tell them apart, which is why {@code types} lists disabled types rather than
 * filtering them out the way {@code /offend}'s own completion does.
 *
 * <p>{@code /offend} itself is not here. It is player-facing, it belongs to the module that
 * implements it, and it is registered and unregistered with that module so switching the feature off
 * really takes the verb away (departure D53). What lives in the admin tree is the operator half.
 */
final class PunishmentSubcommands {

    private PunishmentSubcommands() {
    }

    /** The one verb, with two arguments. */
    static final class Offense implements AdminSubcommand {

        @Override
        public String name() {
            return "offense";
        }

        @Override
        public String usage() {
            return "<reload|types>";
        }

        @Override
        public String description() {
            return "refresh or list the offense types /offend accepts";
        }

        @Override
        public void run(final CommandSource source, List<String> args, final AdminContext context) {
            final OffenseAdmin offenses = context.offenses();
            if (!offenses.isAvailable()) {
                source.sendMessage(Msg.legacy("§eThe offenses module is not running."));
                return;
            }
            String verb = args.isEmpty() ? "types" : args.get(0).toLowerCase(Locale.ROOT);
            if ("reload".equals(verb)) {
                source.sendMessage(Msg.legacy("§7Re-reading offense types from the bot…"));
                context.async(new Runnable() {
                    @Override
                    public void run() {
                        offenses.reload();
                        // The list rather than a success line, deliberately. A failed refresh keeps
                        // the previous cache and is indistinguishable from a successful one by any
                        // return value — but not by its contents.
                        list(source, offenses.types());
                    }
                });
                return;
            }
            if ("types".equals(verb)) {
                list(source, offenses.types());
                return;
            }
            source.sendMessage(Msg.legacy("§cUsage: §f/hd offense <reload|types>"));
        }

        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            return args.size() <= 1
                    ? Arrays.asList("reload", "types")
                    : Collections.<String>emptyList();
        }

        /**
         * Prints every cached type, disabled ones included and marked as such.
         *
         * <p>An empty list is its own line rather than silence: "no types are configured" and "the
         * refresh has not landed yet" are both real, and both look like a command that did nothing.
         */
        private static void list(CommandSource source, List<OffenseType> types) {
            if (types.isEmpty()) {
                source.sendMessage(Msg.legacy("§eNo offense types are cached. Either none are "
                        + "configured for this guild, or the last refresh did not reach the bot — "
                        + "§f/hd status§e says which."));
                return;
            }
            source.sendMessage(Msg.legacy("§6Offense types §7(" + types.size() + ")"));
            for (OffenseType type : types) {
                source.sendMessage(Msg.legacy("§7 - §f" + type.displayName()
                        + (type.enabled() ? "" : " §c(disabled)")
                        + " §8" + type.offenses()));
            }
        }
    }
}
