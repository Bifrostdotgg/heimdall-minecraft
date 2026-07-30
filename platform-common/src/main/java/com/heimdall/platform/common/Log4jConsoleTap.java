package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * The log4j2 half of {@link ConsoleTap} — the Bukkit family and Velocity.
 *
 * <p>Both run log4j2, so this is one implementation for two platforms. The appender is attached to
 * the root {@link LoggerConfig} with no level filter of its own, deliberately: setting a level there
 * would change what the server's <em>own</em> console appender sees. The {@code INFO} floor is
 * applied in {@link #capture(LogEvent)} instead.
 *
 * <h2>The API surface is chosen for Minecraft 1.8.8, not for the version this compiles against</h2>
 *
 * <p>1.8.8 ships log4j <strong>2.0-beta9</strong>. Two consequences, and both are the reason this
 * class is not simply v2's {@code ConsoleStreamer} moved:
 *
 * <ul>
 *   <li>v2 used the five-argument {@code AbstractAppender(name, filter, layout, ignoreExceptions,
 *       properties)} constructor, which did not exist until 2.11.2. On 1.8.8 that is a
 *       {@code NoSuchMethodError} the first time console streaming is switched on. The four-argument
 *       form used here is deprecated on modern log4j but present in every 2.x, which is the only
 *       property that matters when the runtime version spans a decade.
 *   <li>{@code LogEvent.getTimeMillis()} arrived in 2.4; beta9 only has {@code getMillis()}. Rather
 *       than reflect over two spellings, {@link ConsoleTap#capture} stamps the line itself — see
 *       the note there.
 * </ul>
 *
 * <p>Which is also why {@link ConsoleTap#attach()} ends in a self-test rather than trusting that
 * {@code addAppender} returning normally meant anything (departure D45).
 */
public final class Log4jConsoleTap extends ConsoleTap {

    /** The logger the attach-time probe line is emitted through. */
    private static final String SELF_TEST_LOGGER = "com.heimdall.consoletap";

    private volatile AbstractAppender appender;
    private volatile LoggerConfig attachedTo;

    /**
     * @param executor where consumers run; {@code heimdall-io} in production
     */
    public Log4jConsoleTap(HeimdallLogger logger, Executor executor) {
        super(logger, executor);
    }

    @Override
    protected void attachSink() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig root = context.getConfiguration().getRootLogger();
        AbstractAppender attached = new TapAppender(instanceName());
        attached.start();
        root.addAppender(attached, null, null);
        this.appender = attached;
        this.attachedTo = root;
    }

    /**
     * Removes the appender from the root logger — and deliberately never stops it.
     *
     * <p>That looks like a leak and is the opposite. On a server with async loggers — Paper 1.16.5
     * and every version since — log events are queued and delivered on a background thread, so an
     * event that entered the ring buffer <em>before</em> the appender was removed can be delivered
     * after. Log4j's {@code AppenderControl} then finds a stopped appender and its status logger
     * prints {@code ERROR Attempted to append to non-started appender HeimdallConsoleTap-N} — an
     * ERROR line, in the server's log, naming Heimdall, during shutdown, caused by nothing being
     * wrong. It failed the smoke matrix's own error check, which is exactly the outcome the check
     * exists to produce for a real fault.
     *
     * <p>Removing without stopping closes that window: {@code LoggerConfig.removeAppender} does not
     * stop the appender, so anything still in flight arrives at a started appender that discards it
     * (the base class clears the taps before calling this) and nothing is logged. An
     * {@link AbstractAppender} that is never stopped holds no resources — no thread, no handle, no
     * buffer beyond the queue the base class has just emptied — so there is nothing left to release.
     */
    @Override
    protected void detachSink() {
        LoggerConfig root = this.attachedTo;
        this.appender = null;
        this.attachedTo = null;
        if (root != null) {
            root.removeAppender(instanceName());
        }
    }

    /**
     * Through Log4j itself, not through Heimdall's own logger: the point is to exercise the real
     * appender path, and on Bukkit the plugin logger is JUL bridged into log4j, which would prove a
     * different thing.
     */
    @Override
    protected void emitProbe(String marker) {
        LogManager.getLogger(SELF_TEST_LOGGER).info(marker);
    }

    @Override
    protected String deafExplanation() {
        return "this server's log4j does not speak the API Heimdall compiled against, so console "
                + "streaming is off";
    }

    /**
     * One log event, filtered and handed to the shared capture path.
     *
     * <p>Package-private rather than private because it is what the tests drive: {@code attach()}
     * puts an appender on the JVM's root logger, which is a bad thing for a unit test to do and a
     * worse thing for one to leave behind.
     */
    void capture(LogEvent event) {
        try {
            if (!capturing()) {
                return;
            }
            // Log4j levels run more-severe = lower, so INFO's intLevel is the ceiling we accept.
            if (event.getLevel().intLevel() > Level.INFO.intLevel()) {
                return;
            }
            capture(event.getLevel().name(), event.getMessage().getFormattedMessage());
        } catch (Throwable ignored) {
            // A broken append() must not break the server's logging. The base class contains its own
            // half; this one covers reading the level and rendering the message, which is where a
            // hostile Message implementation throws.
        }
    }

    /**
     * The appender itself.
     *
     * <p>A named inner class rather than an anonymous one so its constructor call is legible: the
     * four-argument super is the compatibility decision this whole file exists to make.
     */
    private final class TapAppender extends AbstractAppender {

        @SuppressWarnings("deprecation")
        TapAppender(String name) {
            // (name, filter, layout, ignoreExceptions). Deprecated on modern log4j, present in
            // every 2.x including the 2.0-beta9 that Minecraft 1.8.8 ships. The five-argument
            // overload v2 used arrived in 2.11.2 and would be a NoSuchMethodError there.
            super(name, null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            capture(event);
        }
    }
}
