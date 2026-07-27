package com.heimdall.core.command;

import com.heimdall.core.util.Registration;

/**
 * Puts a {@link CommandSpec} in front of players, and takes it away again.
 *
 * <p>The fifth focused interface behind {@code PlatformFacade}, and it is here for the same reason
 * as the other four (departure D49): a module that registers a command should be testable with a
 * few lines of fake, not with a whole server.
 *
 * <p><strong>The handle is the whole point.</strong> v2 had no way to unregister a command, so a
 * feature that was "off" still answered — which is exactly the class of bug departure D30 exists to
 * close. A module registers through its {@code ModuleContext} and the registration is unwound when
 * it is disabled, whether or not the module remembered.
 *
 * <p>Implementations are safe to call from any thread and must tolerate a name the platform will not
 * give them: on the Bukkit family a command that {@code plugin.yml} never declared cannot be
 * created at runtime, and the honest answer is a warning and {@link Registration#NONE} rather than
 * an exception that fails a module's enable.
 */
public interface CommandRegistrar {

    /**
     * A registrar for a platform with no command system at all.
     *
     * <p>Used by {@code ModuleContext} when nothing supplied one, so a module never has to branch on
     * whether commands exist. Registering with it is a no-op that logs nothing: the platform that
     * chose it has already said why.
     */
    CommandRegistrar NONE = new CommandRegistrar() {
        @Override
        public Registration register(CommandSpec spec) {
            return Registration.NONE;
        }
    };

    /**
     * Registers a command.
     *
     * @return a handle that unregisters it; closing it twice is a no-op
     */
    Registration register(CommandSpec spec);
}
