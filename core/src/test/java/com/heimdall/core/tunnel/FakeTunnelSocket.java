package com.heimdall.core.tunnel;

import com.heimdall.core.json.Envelope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A socket that does exactly what a test tells it to.
 *
 * <p>The reason the {@link TunnelSocket} seam exists. Every invariant worth testing on the tunnel
 * is about what happens when a connection <em>misbehaves</em> — goes silent, closes without
 * warning, errors and closes together, refuses to open at all — and none of those can be produced
 * on demand against a real server. Here each one is a method call.
 *
 * <p>It also records what was written, so "did the client send identify before starting the
 * heartbeat?" is an assertion rather than an inference.
 *
 * <p>Thread-safe: the client writes from caller threads and tests drive callbacks from the test
 * thread.
 */
final class FakeTunnelSocket implements TunnelSocket {

    private final String url;
    private final TunnelSocketListener listener;
    private final List<String> sent = Collections.synchronizedList(new ArrayList<String>());

    /**
     * The thread each frame was written on, index-aligned with {@link #sent}.
     *
     * <p>Recorded because "the pong is written synchronously rather than queued behind a module
     * handler" is a real production property — the bot's sweep closes any connection that has not
     * answered — and it cannot be asserted by reading the current thread from the test, which is
     * the delivering thread either way.
     */
    private final List<String> sentOn = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicBoolean open = new AtomicBoolean();

    private volatile boolean aborted;
    private volatile boolean closedGracefully;
    private volatile int closeCode;
    private volatile String closeReason = "";
    private volatile boolean connectFails;
    private volatile boolean connectThrows;

    FakeTunnelSocket(String url, TunnelSocketListener listener) {
        this.url = url;
        this.listener = listener;
    }

    /** Makes {@link #connect()} report a connect error instead of opening. */
    FakeTunnelSocket failToConnect() {
        this.connectFails = true;
        return this;
    }

    /**
     * Makes {@link #connect()} throw rather than report an error.
     *
     * <p>A different path from {@link #failToConnect()}: the exception unwinds through
     * {@code doConnect}'s own catch instead of arriving as a listener callback, and the socket has
     * already been published to the client's field by the time it happens.
     */
    FakeTunnelSocket throwOnConnect() {
        this.connectThrows = true;
        return this;
    }

    @Override
    public void connect() {
        if (connectThrows) {
            throw new IllegalStateException("connect() blew up (fake)");
        }
        if (connectFails) {
            listener.onError(new IllegalStateException("connection refused (fake)"));
            return;
        }
        open.set(true);
        listener.onOpen();
    }

    @Override
    public void sendText(String text) {
        if (open.get()) {
            synchronized (sent) {
                sent.add(text);
                sentOn.add(Thread.currentThread().getName());
            }
        }
    }

    @Override
    public void abort() {
        aborted = true;
        open.set(false);
    }

    @Override
    public void close(int code, String reason) {
        closedGracefully = true;
        closeCode = code;
        closeReason = reason;
        open.set(false);
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    // ── Test drivers ─────────────────────────────────────────────────────────

    /** Delivers an inbound frame, as the reading thread would. */
    void deliver(Envelope envelope) {
        listener.onText(envelope.toJson());
    }

    /** Delivers raw text, for the malformed-frame cases. */
    void deliverRaw(String text) {
        listener.onText(text);
    }

    /** Fires the close callback, as a peer hanging up would. */
    void fireClose(int code, String reason) {
        open.set(false);
        listener.onClose(code, reason);
    }

    /** Fires the error callback. */
    void fireError(Throwable error) {
        listener.onError(error);
    }

    // ── Assertions ───────────────────────────────────────────────────────────

    String url() {
        return url;
    }

    /** Whether {@link #abort()} was called — invariant (a)'s observable form. */
    boolean wasAborted() {
        return aborted;
    }

    /** Whether a graceful close frame was sent. */
    boolean wasClosedGracefully() {
        return closedGracefully;
    }

    int closeCode() {
        return closeCode;
    }

    String closeReason() {
        return closeReason;
    }

    /** Every frame written, as raw JSON, in order. */
    List<String> sentRaw() {
        synchronized (sent) {
            return new ArrayList<String>(sent);
        }
    }

    /** Every frame written, parsed. */
    List<Envelope> sentFrames() {
        List<Envelope> frames = new ArrayList<Envelope>();
        for (String raw : sentRaw()) {
            Envelope parsed = Envelope.parse(raw);
            if (parsed != null) {
                frames.add(parsed);
            }
        }
        return frames;
    }

    /**
     * The name of the thread the first frame of a type was written on, or null.
     *
     * <p>The assertion this exists for: a pong must leave on the thread that delivered the ping.
     */
    String threadThatSentFirst(String type) {
        synchronized (sent) {
            for (int i = 0; i < sent.size(); i++) {
                Envelope parsed = Envelope.parse(sent.get(i));
                if (parsed != null && type.equals(parsed.type())) {
                    return sentOn.get(i);
                }
            }
        }
        return null;
    }

    /** The first frame of a type, or null. */
    Envelope firstFrameOfType(String type) {
        for (Envelope frame : sentFrames()) {
            if (type.equals(frame.type())) {
                return frame;
            }
        }
        return null;
    }

    /** How many frames of a type were written. */
    int countFramesOfType(String type) {
        int count = 0;
        for (Envelope frame : sentFrames()) {
            if (type.equals(frame.type())) {
                count++;
            }
        }
        return count;
    }
}
