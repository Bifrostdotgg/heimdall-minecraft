package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import java.util.Collections;
import java.util.List;

/**
 * One verb under {@code /hd}.
 *
 * <p>An interface with ten small implementations, rather than v2's {@code switch} inside a
 * thousand-line entry point. The switch is what made the two platforms drift — the proxy's
 * {@code cache} branch never grew the {@code cleanup} case the Paper one had, and nothing anywhere
 * could have noticed — and it is what made the tree untestable without starting a server.
 *
 * <p>Every implementation is platform-free and takes a {@link CommandSource}, so a test drives one
 * with a fake sender and asserts on the lines it produced.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #run} is called on whatever thread the platform delivers a command on — the main server
 * thread on the Bukkit family. An implementation that blocks must acknowledge and hand off through
 * {@link AdminContext#async(Runnable)}; there is no verb here for which freezing the tick loop on an
 * HTTP round trip is acceptable.
 */
public interface AdminSubcommand {

    /** The verb, lower-case, as it is typed. */
    String name();

    /** The argument syntax after the verb, e.g. {@code <stats|clear|cleanup>}, or {@code ""}. */
    String usage();

    /** One line for the help listing. */
    String description();

    /**
     * Runs the verb.
     *
     * @param args everything after the verb itself, never {@code null}
     */
    void run(CommandSource source, List<String> args, AdminContext context);

    /**
     * Suggestions for the argument currently being typed.
     *
     * @param args everything after the verb, with the partial word last
     * @return matching suggestions, unfiltered by prefix — the dispatcher filters
     */
    default List<String> complete(CommandSource source, List<String> args, AdminContext context) {
        return Collections.emptyList();
    }
}
