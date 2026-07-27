package com.heimdall.core.platform;

/**
 * The server accepted the dispatch and then had no such command.
 *
 * <h2>Why this is a failure and not an acknowledgement</h2>
 *
 * <p>It was an acknowledgement, briefly, on the reasoning that "the command ran and printed an
 * error" is a successful dispatch. That reasoning is right about the transport and wrong about the
 * only caller that matters: {@code /offend} asks the bot what punishment to apply, is handed a
 * command string, and dispatches it. If that command does not exist on this server — the punishment
 * plugin is not installed, or was renamed, or the dashboard's template is out of date — then the
 * infraction has been <strong>recorded bot-side and no punishment has been applied</strong>, and the
 * moderator standing there is the only person who can reconcile that. Silently completing with
 * "dispatched" leaves them believing they banned somebody.
 *
 * <p>Both platforms already knew. Bukkit's {@code dispatchCommand} returns a boolean that was being
 * discarded; Velocity's {@code executeAsync} returns one that was being turned into a cheerful
 * string. Making it a typed failure means a caller cannot ignore it by accident, and does not have
 * to match on message text to tell it from a genuine platform refusal.
 *
 * <p>It is deliberately distinct from the plain refusals — a null command, a server shutting down —
 * which stay as their own exception types. A caller that wants to treat every dispatch failure the
 * same still can; one that wants to say "that punishment plugin is not installed" now has something
 * to branch on.
 */
public final class UnknownCommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String command;

    public UnknownCommandException(String command) {
        super("no such command: " + command);
        this.command = command;
    }

    /** The command line the server did not recognise, as it was dispatched. */
    public String command() {
        return command;
    }
}
