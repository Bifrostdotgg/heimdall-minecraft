package com.heimdall.whitelist.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Platform-agnostic console streamer.
 *
 * <p>
 * Both Paper and Velocity run on Log4j2. This attaches a programmatic
 * {@link AbstractAppender} to the root {@link LoggerConfig} so every INFO+ log
 * line the server emits is captured, ANSI-stripped, and pushed onto a bounded
 * queue. A platform-driven flush task then drains the queue and ships batched
 * {@code console_line} messages over the existing {@link WebSocketClient}
 * tunnel — but only while the socket is connected. When disconnected the drain
 * still happens (and discards) so the queue can never grow without bound.
 *
 * <p>
 * The appender itself never logs (that would recurse through Log4j into our own
 * {@code append()} again), and TRACE/DEBUG are skipped to keep volume sane.
 */
public class ConsoleStreamer {

  /** Hard cap on buffered lines. Oldest are dropped once exceeded. */
  private static final int MAX_QUEUE_SIZE = 1000;

  /** Max lines drained and shipped per flush tick. */
  private static final int MAX_BATCH_SIZE = 200;

  /** Matches ANSI/VT100 escape sequences (colour codes) so we can strip them. */
  private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[;\\d]*[ -/]*[@-~]");

  /** Unique-ish appender names so multiple instances never clash. */
  private static final AtomicInteger APPENDER_SEQ = new AtomicInteger();

  private final WebSocketClient wsClient;
  private final PluginLogger logger;

  private final ConcurrentLinkedQueue<LogLine> queue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger queueSize = new AtomicInteger();

  private final String appenderName = "HeimdallConsoleStreamer-" + APPENDER_SEQ.incrementAndGet();

  private volatile AbstractAppender appender;
  private volatile LoggerConfig attachedTo;

  public ConsoleStreamer(WebSocketClient wsClient, PluginLogger logger) {
    this.wsClient = wsClient;
    this.logger = logger;
  }

  /** Captured log line, kept minimal to bound memory. */
  private static final class LogLine {
    final long ts;
    final String level;
    final String msg;

    LogLine(long ts, String level, String msg) {
      this.ts = ts;
      this.level = level;
      this.msg = msg;
    }
  }

  /**
   * Build and attach the Log4j2 appender to the root logger config. Safe to call
   * once; a second call is a no-op while already attached.
   */
  public synchronized void attach() {
    if (appender != null) {
      return;
    }
    try {
      LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
      LoggerConfig root = ctx.getConfiguration().getRootLogger();

      AbstractAppender app = new AbstractAppender(appenderName, null, null, true, null) {
        @Override
        public void append(LogEvent event) {
          enqueue(event);
        }
      };
      app.start();

      // Attach with no level filter here — we filter inside enqueue() so we never
      // alter the root logger's own level (which would change what other
      // appenders, e.g. the console, see).
      root.addAppender(app, null, null);

      this.appender = app;
      this.attachedTo = root;
      logger.info("[Console] Console streaming attached");
    } catch (Throwable t) {
      // Never let a logging-internals failure take down the plugin.
      logger.warning("[Console] Failed to attach console streamer: " + t.getMessage());
    }
  }

  /** Detach the appender and stop it. Safe to call repeatedly. */
  public synchronized void detach() {
    AbstractAppender app = this.appender;
    LoggerConfig root = this.attachedTo;
    this.appender = null;
    this.attachedTo = null;
    if (app == null) {
      return;
    }
    try {
      if (root != null) {
        root.removeAppender(appenderName);
      }
      app.stop();
    } catch (Throwable t) {
      logger.warning("[Console] Failed to detach console streamer: " + t.getMessage());
    }
    queue.clear();
    queueSize.set(0);
  }

  /**
   * Push a captured event onto the bounded queue. Called from arbitrary Log4j
   * threads — must never log (recursion) and must stay cheap.
   */
  private void enqueue(LogEvent event) {
    try {
      // Only INFO and above. Log4j levels are more-severe = lower intLevel, so
      // INFO's intLevel is the ceiling we accept.
      int level = event.getLevel().intLevel();
      if (level > org.apache.logging.log4j.Level.INFO.intLevel()) {
        return;
      }

      String raw = event.getMessage().getFormattedMessage();
      if (raw == null) {
        raw = "";
      }
      String msg = ANSI_PATTERN.matcher(raw).replaceAll("");

      queue.add(new LogLine(event.getTimeMillis(), event.getLevel().name(), msg));

      // Bound the queue: drop oldest until back under the cap. incrementAndGet
      // first so concurrent producers converge on the same ceiling.
      if (queueSize.incrementAndGet() > MAX_QUEUE_SIZE) {
        if (queue.poll() != null) {
          queueSize.decrementAndGet();
        }
      }
    } catch (Throwable ignored) {
      // Swallow everything — a broken append() must not break the server's logging.
    }
  }

  /**
   * Drain up to {@link #MAX_BATCH_SIZE} queued lines and, if the tunnel is
   * connected, ship them as one {@code console_line} message. When disconnected
   * the lines are dropped so the queue stays bounded. Intended to be called on a
   * platform scheduler roughly once per second.
   */
  public void flush() {
    if (queue.isEmpty()) {
      return;
    }

    List<LogLine> batch = new ArrayList<>(Math.min(MAX_BATCH_SIZE, MAX_QUEUE_SIZE));
    for (int i = 0; i < MAX_BATCH_SIZE; i++) {
      LogLine line = queue.poll();
      if (line == null) {
        break;
      }
      queueSize.decrementAndGet();
      batch.add(line);
    }

    if (batch.isEmpty()) {
      return;
    }

    // Drain-and-discard when offline so the queue can't grow unbounded.
    if (!wsClient.isConnected()) {
      return;
    }

    JsonArray lines = new JsonArray();
    for (LogLine line : batch) {
      JsonObject obj = new JsonObject();
      obj.addProperty("ts", line.ts);
      obj.addProperty("level", line.level);
      obj.addProperty("msg", line.msg);
      lines.add(obj);
    }
    JsonObject payload = new JsonObject();
    payload.add("lines", lines);
    wsClient.send("console_line", payload);
  }
}
