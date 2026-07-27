package com.heimdall.core.tunnel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands out {@link FakeTunnelSocket}s and remembers every one of them.
 *
 * <p>The count is what makes invariant (b) testable at all: "exactly one reconnect happened" is
 * unobservable from outside, but "exactly one more socket was created" is not.
 *
 * <p>{@link #failNextConnects(int)} models the reconnect-storm case — a bot that is redeploying
 * refuses connections for a while and then starts accepting them — without needing a real server
 * that can be told to stop listening.
 */
final class FakeTunnelSocketFactory implements TunnelSocketFactory {

    private final List<FakeTunnelSocket> created =
            Collections.synchronizedList(new ArrayList<FakeTunnelSocket>());
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private volatile boolean throwOnCreate;
    private volatile boolean throwOnConnect;

    /** The next {@code count} attempts report a connect error instead of opening. */
    FakeTunnelSocketFactory failNextConnects(int count) {
        failuresRemaining.set(count);
        return this;
    }

    /**
     * Makes {@link #create} itself throw.
     *
     * <p>The fourth reconnect trigger: a socket that could not even be constructed — a malformed
     * URL, an SSL context that will not initialise. It reaches {@code scheduleReconnect} by a
     * different path from the other three, which is exactly why it has to be exercised.
     */
    FakeTunnelSocketFactory throwOnCreate(boolean value) {
        this.throwOnCreate = value;
        return this;
    }

    /** Every socket handed out throws from {@code connect()} rather than opening. */
    FakeTunnelSocketFactory throwOnConnect(boolean value) {
        this.throwOnConnect = value;
        return this;
    }

    @Override
    public TunnelSocket create(String url, TunnelSocketListener listener) {
        if (throwOnCreate) {
            throw new IllegalStateException("cannot create a socket (fake)");
        }
        FakeTunnelSocket socket = new FakeTunnelSocket(url, listener);
        if (throwOnConnect) {
            socket.throwOnConnect();
        }
        if (failuresRemaining.getAndUpdate(current -> current > 0 ? current - 1 : 0) > 0) {
            socket.failToConnect();
        }
        created.add(socket);
        return socket;
    }

    /** How many sockets have been handed out. */
    int createdCount() {
        return created.size();
    }

    /** Every socket handed out, in order. */
    List<FakeTunnelSocket> created() {
        synchronized (created) {
            return new ArrayList<FakeTunnelSocket>(created);
        }
    }

    /** The most recent socket, or null. */
    FakeTunnelSocket latest() {
        synchronized (created) {
            return created.isEmpty() ? null : created.get(created.size() - 1);
        }
    }

    /** The first socket, or null. */
    FakeTunnelSocket first() {
        synchronized (created) {
            return created.isEmpty() ? null : created.get(0);
        }
    }
}
