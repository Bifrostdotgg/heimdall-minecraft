package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
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
}
