package com.heimdall.core.remoteconfig;

/**
 * Notified when one module's slice of the configuration changes.
 *
 * <p>Section-scoped rather than document-scoped so a module is not woken by every unrelated change:
 * a dashboard edit to the offense templates should not make the whitelist module re-read and
 * re-apply its cache window.
 *
 * <p><strong>Fired only on a real change.</strong> The old and new {@link ModuleConfig} are compared
 * by value, so a re-push of identical settings — which a reconnect produces every time — is silent.
 * That is what makes it safe for a listener to do expensive work.
 *
 * <p>Fired on the socket's reading thread, from inside the config-push handler. Listeners must be
 * quick and must not block; anything substantial belongs on an executor the listener owns.
 */
public interface ModuleConfigListener {

    /**
     * @param moduleId the module whose section changed
     * @param previous what it was; {@link ModuleConfig#absent()} if there was no entry
     * @param current what it is now; never equal to {@code previous}
     */
    void onModuleConfigChanged(String moduleId, ModuleConfig previous, ModuleConfig current);
}
