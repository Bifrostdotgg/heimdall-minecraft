package com.heimdall.shell.hotswap;

import java.util.List;

/**
 * Puts a shell {@link Relay} in front of players under a name, and takes it away again.
 *
 * <p>One implementation per platform. The relay is the only object the platform ever holds for a
 * Heimdall command, which is what lets a swap change the code behind a command without touching the
 * platform's command map.
 */
public interface CommandPlatform {

    /**
     * Registers {@code relay} under {@code name} and {@code aliases}.
     *
     * @return the platform's handle, or {@code null} if the platform refused (the caller warns)
     */
    Handle register(
            String name,
            List<String> aliases,
            String permission,
            String description,
            String usage,
            Relay relay);

    /** One registered relay command. */
    interface Handle {

        /** Takes the command away again. A no-op for a {@link #permanent()} one. */
        void unregister();

        /**
         * Whether the platform will never let this command go: a {@code plugin.yml} command on
         * Bukkit, or a proxy command Heimdall took over from another plugin, which unregistering
         * would delete outright.
         */
        boolean permanent();

        /** The aliases it was registered with, lower-case. */
        List<String> aliases();
    }
}
