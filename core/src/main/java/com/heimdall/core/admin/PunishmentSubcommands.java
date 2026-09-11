package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.text.Msg;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hd offense} plus the native punishment verbs. {@code /hd ban} and family stay registered
 * even when root {@code /ban} aliases are off.
 *
 * <p>{@code /offend} itself is not here. It is player-facing and belongs to the offenses module.
 */
final class PunishmentSubcommands {

    private PunishmentSubcommands() {
    }

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
                        list(source, offenses.types());
                    }
                });
                return;
            }
            if ("types".equals(verb)) {
                list(source, offenses.types());
                return;
            }
            source.sendMessage(Msg.legacy("§cUsage: §f/" + context.label() + " offense <reload|types>"));
        }

        @Override
        public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
            return args.size() <= 1
                    ? Arrays.asList("reload", "types")
                    : Collections.<String>emptyList();
        }

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

    static AdminSubcommand verb(String name, String usage, String description, String type) {
        return new Verb(name, usage, description, type);
    }

    private static final class Verb implements AdminSubcommand {
        private final String name;
        private final String usage;
        private final String description;
        private final String type;

        Verb(String name, String usage, String description, String type) {
            this.name = name;
            this.usage = usage;
            this.description = description;
            this.type = type;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String usage() {
            return usage;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void run(CommandSource source, List<String> args, AdminContext context) {
            delegate(source, type, args, context);
        }
    }

    /**
     * Hands one verb to the punishments module.
     *
     * <p>Through {@link PunishmentAdmin}, which the wiring in {@code :platform-common} fills in,
     * rather than through reflection. The reflective version looked up a field
     * ({@code HeimdallPunishmentsModule.INSTANCE}) that was declared package-private, so
     * {@code getField} threw {@code NoSuchFieldException("INSTANCE")} before any command could run
     * and every punishment verb answered "Could not issue the punishment: INSTANCE". A compiler
     * checks an interface; nothing checks a string naming a field in another module.
     *
     * <p>The remaining catch is for a module that throws, not for a lookup that cannot resolve, and
     * it names the exception type as well as its message: a {@code NullPointerException} carries no
     * message at all, and "Could not issue the punishment: null" is the same dead end by another
     * spelling.
     */
    private static void delegate(CommandSource source, String type, List<String> args,
            AdminContext context) {
        PunishmentAdmin punishments = context.punishments();
        if (!punishments.isAvailable()) {
            source.sendMessage(Msg.legacy("§eThe punishments module is not running."));
            return;
        }
        try {
            punishments.onStaffCommand(source, type, args);
        } catch (RuntimeException e) {
            source.sendMessage(Msg.legacy("§cCould not issue the punishment: "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage())));
        }
    }
}
