package com.heimdall.core.remoteconfig;

/**
 * Notified when the effective configuration document changes at all.
 *
 * <p>For the one consumer that genuinely cares about the whole thing: {@code ModuleManager}, which
 * has to diff the enabled set to decide what to start and stop. Everything else should subscribe to
 * its own section instead — see {@link ModuleConfigListener}.
 *
 * <p>Fired only on a real change, and on the socket's reading thread. Listeners must not block.
 */
public interface ConfigListener {

    /**
     * @param previous the document that was in force
     * @param current the document now in force; never equal to {@code previous}
     */
    void onConfigChanged(ConfigDocument previous, ConfigDocument current);
}
