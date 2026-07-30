package com.heimdall.platform.common;

import com.heimdall.core.log.HeimdallLogger;
import java.text.MessageFormat;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The {@code java.util.logging} half of {@link ConsoleTap} — BungeeCord.
 *
 * <h2>Why this exists at all, when the other two platforms share one tap</h2>
 *
 * <p>The Bukkit family and Velocity both run log4j2, so {@link Log4jConsoleTap} serves both.
 * BungeeCord does not run log4j at any version: its console is
 * {@code net.md_5.bungee.log.BungeeLogger}, a plain {@code java.util.logging.Logger} subclass with a
 * {@code FileHandler} and a jline-aware {@code ColouredWriter} on it. Attaching a log4j appender on
 * a BungeeCord proxy attaches it to nothing.
 *
 * <h2>The attach point is the proxy's own logger, NOT the JUL root</h2>
 *
 * <p>This is the part that is easy to get wrong and impossible to notice, so it is written down
 * rather than assumed. {@code BungeeLogger}'s constructor is
 * {@code super(loggerName, null); setLevel(Level.ALL); setUseParentHandlers(false);} — it is built
 * through {@link Logger}'s <em>protected</em> constructor, which does not register the logger with
 * the {@code LogManager} and does not give it a parent. So:
 *
 * <ul>
 *   <li>Nothing the proxy logs ever reaches {@code Logger.getLogger("")}. A handler on the JUL root
 *       would attach perfectly, capture nothing, and look exactly like a quiet server.
 *   <li>Every plugin's logger <em>does</em> reach it: {@code PluginLogger}'s constructor ends with
 *       {@code setParent(plugin.getProxy().getLogger())}, and leaves {@code useParentHandlers} at
 *       its default of true. So one handler on the proxy logger sees the proxy's own lines and every
 *       plugin's — which is precisely the console feed the dashboard wants.
 * </ul>
 *
 * <p>Which is also why {@link #emitProbe} logs on the target logger itself rather than on a
 * {@code com.heimdall.consoletap} logger the way {@link Log4jConsoleTap} does: a logger obtained
 * from {@code Logger.getLogger(name)} has the JUL root as its parent, so its records would never
 * arrive here and the self-test would fail on a perfectly good tap.
 *
 * <h2>Delivery is already off the calling thread, and the guard cannot see that</h2>
 *
 * <p>{@code BungeeLogger.log(LogRecord)} queues onto its own {@code LogDispatcher} thread and the
 * handlers run there — so capture never costs the netty thread that emitted the line, and equally
 * the re-entrancy guard in {@link ConsoleTap} cannot catch a consumer that logs. That limitation is
 * general (Paper's async loggers have it too) and is stated in {@link ConsoleTap}'s own javadoc.
 */
public final class JulConsoleTap extends ConsoleTap {

    private final Logger target;

    private volatile Handler handler;

    /**
     * @param executor where consumers run; {@code heimdall-io} in production
     * @param target the logger to attach to — the <em>proxy's</em> own logger, never the JUL root.
     *     See the class javadoc for why that distinction decides whether this works at all.
     */
    public JulConsoleTap(HeimdallLogger logger, Executor executor, Logger target) {
        super(logger, executor);
        if (target == null) {
            throw new IllegalArgumentException("a logger to attach to is required");
        }
        this.target = target;
    }

    @Override
    protected void attachSink() {
        Handler attached = new TapHandler();
        // ALL, deliberately: the INFO floor belongs in capture() so that changing it cannot change
        // what the proxy's OWN handlers see. A Handler's level filters only that handler, but the
        // habit of setting levels at attach time is how the log4j side would have broken the
        // server's console, and one rule across both taps is worth more than one saved comparison.
        attached.setLevel(Level.ALL);
        target.addHandler(attached);
        this.handler = attached;
    }

    @Override
    protected void detachSink() {
        Handler attached = this.handler;
        this.handler = null;
        if (attached != null) {
            target.removeHandler(attached);
        }
    }

    /**
     * On the target logger itself — see the class javadoc.
     *
     * <p>The line is visible in the proxy's console, exactly as the log4j tap's probe is on a
     * Bukkit server. That is the cost of proving the tap works on every boot rather than the first
     * time somebody switches the dashboard console on.
     */
    @Override
    protected void emitProbe(String marker) {
        target.log(Level.INFO, marker);
    }

    @Override
    protected String deafExplanation() {
        return "this proxy's logger did not deliver to a handler Heimdall attached to it, so console "
                + "streaming is off";
    }

    /**
     * One record, filtered and handed to the shared capture path.
     *
     * <p>Package-private rather than private for the same reason the log4j side's is: {@code attach()}
     * mutates a logger the whole JVM shares, which a unit test should neither do nor leave behind.
     */
    void capture(LogRecord record) {
        try {
            if (record == null || !capturing()) {
                return;
            }
            Level level = record.getLevel();
            // JUL severities run the OTHER way from log4j's — higher is more severe — so the same
            // "INFO and above" floor is the opposite comparison. Writing it as the log4j one is the
            // single most likely way to end up shipping every FINE line over a WebSocket.
            if (level == null || level.intValue() < Level.INFO.intValue()) {
                return;
            }
            capture(levelName(level), render(record));
        } catch (Throwable ignored) {
            // A broken publish() must not break the proxy's logging.
        }
    }

    /**
     * JUL's level names, mapped onto the ones every other platform sends.
     *
     * <p>The console feed crosses the wire with a {@code level} string on every line and the
     * dashboard renders it, so {@code SEVERE} arriving from a proxy while {@code ERROR} arrives from
     * every backend behind it would be one feature displaying two vocabularies. Only the two names
     * that actually differ are mapped; anything else — {@code INFO}, and a custom level a plugin
     * invented — passes through as the backend spelled it.
     */
    private static String levelName(Level level) {
        String name = level.getName();
        if (Level.WARNING.getName().equals(name)) {
            return "WARN";
        }
        if (Level.SEVERE.getName().equals(name)) {
            return "ERROR";
        }
        return name;
    }

    /**
     * The record's message, with its parameters substituted.
     *
     * <p>Not simply {@code getMessage()}: JUL's parameterised form is
     * {@code log(Level.INFO, "Disconnecting {0} connections", count)}, and BungeeCord uses it on its
     * own shutdown path — so reading the raw message would ship the literal {@code {0}} to the
     * dashboard.
     *
     * <p>The {@code {0}}…{@code {3}} probe is {@link java.util.logging.Formatter#formatMessage}'s own
     * heuristic, copied deliberately rather than improved on: it is what every JUL formatter on the
     * proxy already applies, so this renders the line the way the console does. Formatting is
     * attempted only when it would be, and a message that turns out not to be a valid
     * {@link MessageFormat} pattern falls back to itself rather than throwing on the logging path.
     *
     * <p>Resource-bundle localisation is deliberately not applied. BungeeCord's own logger is
     * constructed with a {@code null} bundle name, so there is nothing to look a key up in.
     */
    private static String render(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        if (message.indexOf("{0") < 0 && message.indexOf("{1") < 0
                && message.indexOf("{2") < 0 && message.indexOf("{3") < 0) {
            return message;
        }
        try {
            return MessageFormat.format(message, parameters);
        } catch (RuntimeException notAPattern) {
            return message;
        }
    }

    /**
     * The handler itself.
     *
     * <p>{@link Handler#flush()} and {@link Handler#close()} are no-ops on purpose: this handler owns
     * no stream and no buffer of its own — the buffering is {@link ConsoleTap}'s bounded queue, which
     * the tap's own {@code close()} empties. A {@code close()} that tore anything down here would
     * also be reachable from outside, since BungeeCord closes a disabling plugin's logger handlers
     * for it.
     */
    private final class TapHandler extends Handler {

        @Override
        public void publish(LogRecord record) {
            capture(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
