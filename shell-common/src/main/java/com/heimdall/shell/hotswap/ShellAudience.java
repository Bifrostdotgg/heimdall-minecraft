package com.heimdall.shell.hotswap;

/**
 * How the shell talks to a command sender, without knowing which platform it is on.
 *
 * <p>Text is a legacy {@code §}-coded string. The shell owns no text library: Adventure is shaded
 * into the core, and the Velocity shell uses the proxy's own. Each platform turns the string into
 * whatever its senders accept.
 */
public interface ShellAudience {

    /** Sends one line to {@code sender}, a platform sender object. Never throws. */
    void send(Object sender, String legacyText);

    /** Whether {@code sender} holds {@code node}. An unknown sender is treated as not holding it. */
    boolean hasPermission(Object sender, String node);

    /**
     * Tells every online player who holds {@code node} something an admin must act on. The console
     * line is the caller's job, through {@link ShellLog}.
     */
    void alertOnline(String node, String legacyText);
}
