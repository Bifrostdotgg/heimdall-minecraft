package com.heimdall.shell.hotswap;

/**
 * The sentences the shell itself says to players and staff, in one place.
 *
 * <p>Legacy {@code §} codes, because the shell has no text library of its own (see
 * {@link ShellAudience}).
 */
public final class ShellMessages {

    /** A command arrived between two core generations. */
    public static final String UPDATING = "§eHeimdall is updating, try again in a moment.";

    /** A login arrived mid-swap and the new core did not take over in time. */
    public static final String LOGIN_UPDATING = "Server is updating, try again in a moment";

    /** A login arrived with no core running at all, after a failed swap and a failed rollback. */
    public static final String LOGIN_NO_CORE =
            "This server cannot check logins right now. Please try again later.";

    /** A login refused because too many are already being decided at once. */
    public static final String LOGIN_BUSY =
            "This server is busy checking logins. Please try again in a moment.";

    /** A command arrived with no core running. */
    public static final String NOT_RUNNING =
            "§cHeimdall is not running right now. An admin can check §f/%s status§c.";

    /** A relay for a command whose module is switched off. */
    public static final String SWITCHED_OFF =
            "§cThat feature is switched off. Enable §f%s§c on the Minecraft page of the Heimdall "
                    + "dashboard.";

    /** A command target threw. */
    public static final String COMMAND_FAILED = "§cThat command failed. Check the server log.";

    /** The permission behind every shell-handled admin verb. */
    public static final String ADMIN_PERMISSION = "heimdall.admin";

    private ShellMessages() {
    }
}
