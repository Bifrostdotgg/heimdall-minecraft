package com.heimdall.shell.contract;

/**
 * A core's login decision, called by the shell's permanent login listener.
 *
 * <h2>Why the shell owns the listener</h2>
 *
 * <p>The login gate is the one registration that may never have a gap. If the core registered its
 * own listener, every swap would open a window with no listener at all, and in that window everyone
 * would be admitted. So the shell registers the platform listener once, at enable, and asks the
 * current core's gate; with no gate bound (mid-swap, or no core at all) it holds the login briefly
 * and then refuses it. It never admits a login no core has decided on (departure D87).
 *
 * <h2>Contract</h2>
 *
 * <p>{@link #decide} is synchronous and may block: the shell only calls it where blocking is
 * acceptable (Bukkit's per-connection pre-login thread, Velocity's async event executor, the shell's
 * own login threads on BungeeCord). The event is the platform's own object:
 * {@code AsyncPlayerPreLoginEvent}, Velocity's {@code LoginEvent} or BungeeCord's
 * {@code LoginEvent}. A denial is expressed on the event; returning normally means "decided".
 * On BungeeCord the shell registers and completes the event's intent, so a gate must not.
 */
public interface LoginGate {

    void decide(Object event);
}
