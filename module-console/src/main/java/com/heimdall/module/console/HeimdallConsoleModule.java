package com.heimdall.module.console;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Streams the server's console to the dashboard, batched once a second.
 *
 * <p>Everything about <em>capturing</em> a line — the appender, the ANSI strip, the {@code INFO}
 * floor, the never-log/never-throw/never-block rules for the callback itself — belongs to
 * {@code Log4jConsoleTap} in {@code :platform-common} and is attached eagerly at plugin boot
 * (departure D45), not by this module. What this class owns is everything downstream of one
 * captured line reaching {@link #enqueue}: buffering it, batching it, and shipping it as a
 * {@code console_line} frame — or discarding it, while the tunnel is down, so the buffer this class
 * owns cannot grow without bound either.
 *
 * <h2>Two queues, two different things being bounded</h2>
 *
 * <p>{@code Log4jConsoleTap} already bounds its own queue at 1000 lines, dropping the oldest, so
 * that a slow or absent consumer cannot make the shared tap grow — that queue exists for every
 * consumer of the console feed, this module being only one of them today. This class keeps a
 * <strong>second</strong>, independent queue of the same size, for a different reason: the tap
 * hands a line to this module's {@link #enqueue} as soon as it drains its own buffer (on
 * {@code heimdall-io}, per {@link com.heimdall.core.platform.ConsoleBridge#attachLogTap}), which
 * can run far more often than once a second, while this module only ships a frame on its own
 * one-second tick and sends at most {@value #MAX_BATCH} lines per tick. Without a bound here, a
 * sustained burst that arrives faster than {@value #MAX_BATCH} lines/second would still grow this
 * module's own buffer forever, even though the tap upstream of it is perfectly healthy and bounded.
 * The cap and the batch size are v2's {@code ConsoleStreamer} numbers, kept for parity: this module
 * replaces that class's queueing and batching now that the capture half has moved to the platform
 * layer (departure D45), and there was no reason to pick different constants for the same job.
 *
 * <h2>Drain-and-discard is not a bug</h2>
 *
 * <p>{@link #flush} always removes up to {@value #MAX_BATCH} queued lines, whether or not the
 * tunnel is connected, and only decides <em>after</em> draining whether to ship them. A drained
 * batch is never put back. That is deliberate and it is v2's behaviour, reproduced on purpose: the
 * alternative — only draining once a bot is there to receive it — is exactly how a queue grows
 * without bound while a bot is down, which is the one thing every bound in this class exists to
 * prevent. The cost is that lines produced while disconnected are gone for good; the bot is the
 * source of truth for everything else this plugin does, and console history is not an exception
 * worth a durable queue for.
 *
 * <h2>The tap registration is this module's alone to close</h2>
 *
 * <p>{@link ModuleContext} tracks every registration made <em>through</em> it — subscriptions,
 * interceptors, scheduled tasks — so {@code ModuleManager} can unwind a module that forgot to clean
 * up (departure D30). The {@link Registration} {@link #enable} gets back from
 * {@code context.platform().console().attachLogTap(...)} is <strong>not</strong> one of those: it
 * comes from {@link com.heimdall.core.platform.PlatformFacade}, which {@code ModuleContext} exposes
 * directly rather than wrapping, because nothing about "watch the console" needs undoing by the
 * manager the way a tunnel subscription or a login interceptor does — except that, for this one
 * registration, it does. So this module holds the handle itself and closes it in {@link #disable},
 * and it is not belt-and-braces: if this class did not close it, nothing else would. The failure
 * mode is worse than an ordinary leak, too, because it is invisible everywhere except the console
 * feed itself — a detached-but-still-attached appender keeps calling a consumer for a module the
 * dashboard says is off, and the only symptom is lines nobody asked for still arriving.
 *
 * <h2>No second on/off switch</h2>
 *
 * <p>v2's {@code console.stream} setting was the <em>only</em> thing that made streaming optional —
 * v2 had no module system (departure D30), so {@code ConsoleStreamer} existed whether or not
 * anybody wanted it, and a config flag was the sole way to turn it off. Here, {@link ModuleManager}
 * already will not call {@link #enable} at all unless the dashboard's {@code enabled} flag for this
 * module is set — that is {@code ModuleConfig.enabled()}, checked before this class ever runs, and
 * checked again on every dashboard toggle. A second, module-local "stream on/off" setting read from
 * {@link ModuleContext#settings()} would therefore gate nothing that {@code ModuleConfig.enabled()}
 * does not already gate — so this module reads no settings of its own. If a future need arises for
 * something genuinely settings-shaped (a level floor stricter than the tap's, a per-server rate
 * limit), it belongs here, read live from {@link ModuleContext#settings()} on every use exactly as
 * the interface's javadoc requires — never captured in {@link #enable}, where a later dashboard
 * edit would never be seen.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #enqueue} runs on whatever executor {@code Log4jConsoleTap} fans out on
 * ({@code heimdall-io} in production) — never on the server's own logging thread, and never
 * re-entrant with another delivery to this consumer, but concurrently with everything else running
 * on that pool. It must stay exactly what it is: an offer onto a lock-free queue, nothing more. It
 * must not log (the platform's tap forbids that of every consumer — logging would re-enter the
 * capture path one layer further down), must not block, and must not throw. {@link #flush} runs on
 * {@code heimdall-sched}, once a second, and is the only place a frame is built and sent.
 * {@link #enable} and {@link #disable} run on whichever thread is driving module reconciliation and
 * are never called concurrently with each other for this module ({@link HeimdallModule}'s own
 * contract) — but a scheduled {@link #flush} can still be mid-run when {@link #disable} is called,
 * since cancelling a {@link java.util.concurrent.ScheduledFuture} does not interrupt a run already
 * in progress. {@link #flush} therefore takes its own local snapshot of the tunnel before using it,
 * rather than re-reading the field, so a concurrent {@link #disable} cannot hand it a half-torn
 * reference.
 */
public final class HeimdallConsoleModule implements HeimdallModule {

    /** The module's stable identifier, matching its key in the remote-config document. */
    public static final String ID = "console";

    /**
     * Lines shipped per {@link #flush}. v2's {@code MAX_BATCH_SIZE}, kept for parity — see the
     * class javadoc.
     */
    static final int MAX_BATCH = 200;

    /**
     * Hard cap on this module's own buffered lines, independent of the tap's. v2's
     * {@code MAX_QUEUE_SIZE}, kept for parity — see the class javadoc.
     */
    static final int MAX_QUEUE_SIZE = 1000;

    /** How often {@link #flush} runs. v2 flushed on the same cadence. */
    private static final long FLUSH_PERIOD_MS = 1000L;

    /** The wire frame type a batch of lines is shipped as. */
    private static final String FRAME_TYPE = "console_line";

    private final ConcurrentLinkedQueue<LogLine> queue = new ConcurrentLinkedQueue<LogLine>();
    private final AtomicInteger queued = new AtomicInteger();

    /**
     * The tap registration this module holds and is solely responsible for closing. See the class
     * javadoc, "The tap registration is this module's alone to close".
     */
    private volatile Registration tapRegistration = Registration.NONE;

    /** Snapshotted at {@link #enable}; {@code null} whenever this module is not enabled. */
    private volatile TunnelBus tunnel;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.CONSOLE);
    }

    @Override
    public Set<ServerRole> roles() {
        // Empty means "any role" (HeimdallModule#roles). A proxy's console is exactly as worth
        // streaming as a backend's — this is not about who owns the login decision, so there is
        // nothing here that would ever exclude a role.
        return Collections.emptySet();
    }

    @Override
    public void enable(ModuleContext context) {
        // Defensive: this module instance is reused across enable/disable cycles by ModuleManager,
        // so a fresh enable starts from an empty buffer rather than whatever a previous cycle left
        // behind (disable() already clears it, but starting clean here costs nothing and does not
        // depend on that ordering holding forever).
        queue.clear();
        queued.set(0);

        this.tunnel = context.tunnel();
        this.tapRegistration =
                context.platform().console().attachLogTap(new Consumer<LogLine>() {
                    @Override
                    public void accept(LogLine line) {
                        enqueue(line);
                    }
                });
        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS);
    }

    @Override
    public void disable() {
        // Stop new lines arriving before discarding what is buffered, not the other way around —
        // otherwise a line could land in a queue this method has already decided is empty.
        tapRegistration.close();
        tapRegistration = Registration.NONE;
        tunnel = null;
        queue.clear();
        queued.set(0);
    }

    /**
     * Offers a captured line onto this module's own bounded queue.
     *
     * <p>See the class javadoc's threading section: this must stay an offer and nothing else.
     */
    private void enqueue(LogLine line) {
        queue.add(line);
        // incrementAndGet first, so concurrent producers converge on the same ceiling rather than
        // each independently deciding they are the one under it.
        if (queued.incrementAndGet() > MAX_QUEUE_SIZE && queue.poll() != null) {
            queued.decrementAndGet();
        }
    }

    /**
     * Drains up to {@link #MAX_BATCH} lines and, if the tunnel is connected, ships them as one
     * {@code console_line} frame. Package-private rather than private so a test can call it
     * directly instead of waiting on the real one-second scheduler tick.
     *
     * <p>Draining happens unconditionally; only the send is conditional. See the class javadoc,
     * "Drain-and-discard is not a bug".
     */
    void flush() {
        TunnelBus bus = tunnel;
        if (bus == null || queue.isEmpty()) {
            return;
        }

        List<LogLine> batch = new ArrayList<LogLine>(MAX_BATCH);
        for (int i = 0; i < MAX_BATCH; i++) {
            LogLine line = queue.poll();
            if (line == null) {
                break;
            }
            queued.decrementAndGet();
            batch.add(line);
        }
        if (batch.isEmpty()) {
            return;
        }

        // The batch is already drained at this point. Whether or not this send happens, the lines
        // above are gone from the queue for good — that is the discard half of drain-and-discard.
        if (!bus.isConnected()) {
            return;
        }

        List<Payload> lines = new ArrayList<Payload>(batch.size());
        for (LogLine line : batch) {
            lines.add(Payload.builder()
                    .put("ts", line.timestampMs())
                    .put("level", line.level())
                    .put("msg", line.message())
                    .build());
        }
        bus.send(FRAME_TYPE, Payload.builder().putChildren("lines", lines).build());
    }

    /** How many lines are currently buffered. Visible for testing only. */
    int queuedCount() {
        return queued.get();
    }
}
