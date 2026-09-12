package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import java.util.Collections;
import java.util.List;

/**
 * The punishments module's operator surface, as core sees it.
 *
 * <p>Same arrangement as {@link WhitelistAdmin} and {@link OffenseAdmin}, and introduced for the
 * same reason: {@code /hd ban} and its fifteen siblings are administration, so they belong to the
 * admin tree, but the mirror and the outbox they act on belong to a module core must not depend on.
 *
 * <p><strong>This replaces a reflective lookup.</strong> {@code PunishmentSubcommands} used to
 * reach the module with {@code Class.forName(...).getField("INSTANCE")}, and the field it looked
 * for was package-private, so every punishment verb died with
 * {@code NoSuchFieldException: INSTANCE}, reported to the operator as the bare word
 * {@code INSTANCE}. Nothing could have caught that: a reflective edge is invisible to the compiler,
 * to the conformance rules (which read bytecode, and so see only direct references) and to any test
 * that does not itself go through the same reflection. An interface the compiler checks cannot
 * break that way.
 *
 * <p>{@link #NONE} exists so a build compiled without the module still has a coherent command tree:
 * {@code /hd ban} says the feature is not running rather than being a verb that silently does
 * nothing.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onStaffCommand} is called on whatever thread the platform dispatched the command on,
 * which on the Bukkit family is the main server thread. It must not block; the implementation hands
 * anything that does off to {@code heimdall-io} itself.
 *
 * <p>{@link #complete} is stricter still. It runs on a keystroke, and on the proxies it runs off
 * the server thread entirely, so it must be a read of something already in memory: no bot call, no
 * roster snapshot, no lock a command handler also takes.
 */
public interface PunishmentAdmin {

    /** What an installation without the punishments module answers. */
    PunishmentAdmin NONE = new PunishmentAdmin() {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void onStaffCommand(CommandSource source, String verb, List<String> args) {
        }
    };

    /** How many suggestions any one completion may return. */
    int COMPLETION_LIMIT = 100;

    /** Whether the module is enabled right now. */
    boolean isAvailable();

    /**
     * Runs one punishment verb.
     *
     * @param source whoever typed it, which the module records as the issuer
     * @param verb the canonical verb - {@code ban}, {@code tempmute}, {@code history} and the rest,
     *     as named in {@code AdminCommand}'s subcommand table
     * @param args everything after the verb, flags included and unparsed
     */
    void onStaffCommand(CommandSource source, String verb, List<String> args);

    /**
     * Suggestions for the word a moderator is part-way through typing.
     *
     * <p>Takes the sender for the same reason {@link #onStaffCommand} does: {@code -s} and
     * {@code -p} are gated on {@code heimdall.punishments.silent}, and a completer that offered
     * them to everybody would advertise a flag the command then refuses.
     *
     * <p>Defaulted to nothing so an implementation that has no names to offer - {@link #NONE}, and
     * anything else that ever implements this - keeps compiling and keeps answering coherently.
     *
     * @param source whoever is typing
     * @param verb the canonical verb, as {@link #onStaffCommand} takes it
     * @param args everything after the verb, flags included, with the partial word last
     * @return at most {@link #COMPLETION_LIMIT} suggestions, already filtered to the partial word
     */
    default List<String> complete(CommandSource source, String verb, List<String> args) {
        return Collections.emptyList();
    }
}
