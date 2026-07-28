package com.heimdall.core.admin;

import com.heimdall.core.http.model.OffenseType;
import java.util.Collections;
import java.util.List;

/**
 * The offenses module's operator surface, as core sees it.
 *
 * <p>Same arrangement as {@link WhitelistAdmin} and for the same reason: {@code /hd offense reload}
 * and {@code /hd offense types} are administration, so they belong to the admin tree, but the cache
 * they act on belongs to a module core must not depend on.
 *
 * <p>{@link OffenseType} is a core model rather than a module one, so it crosses this boundary
 * unchanged — there is nothing to translate and nothing to lose.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #reload()} blocks on a signed round trip and belongs on {@code heimdall-io}.
 * {@link #types()} is a snapshot read and is safe from anywhere.
 */
public interface OffenseAdmin {

    /** What an installation without the offenses module answers. */
    OffenseAdmin NONE = new OffenseAdmin() {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void reload() {
        }

        @Override
        public List<OffenseType> types() {
            return Collections.emptyList();
        }
    };

    /** Whether the module is enabled right now. */
    boolean isAvailable();

    /**
     * Re-reads the offense types from the bot. Blocking; never throws.
     *
     * <p>A failed refresh leaves the previous list intact, which is why the command reports the list
     * afterwards rather than reporting success: "it reloaded" and "it tried and kept what it had"
     * are indistinguishable from the return value, and distinguishable from the list.
     */
    void reload();

    /**
     * Every cached type, enabled and disabled alike.
     *
     * <p>Unfiltered on purpose: "the type exists but is switched off" and "no such type" are
     * different answers to the question an operator is asking when a slug will not tab-complete.
     */
    List<OffenseType> types();
}
