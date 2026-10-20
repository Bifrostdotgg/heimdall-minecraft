package com.heimdall.module.rolesync;

import com.heimdall.core.log.HeimdallLogger;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs tasks one at a time, in submission order, on a shared pool.
 *
 * <p>What {@code role_sync} is dispatched on. The tunnel hands each frame to the subscription's
 * executor in wire order, and on the plain multi-thread {@code heimdall-io} two frames for one
 * player could then be handled in either order, so an older snapshot could be applied after a newer
 * one. Through this lane they are handled in the order they arrived. The handler only parses the
 * frame and queues the LuckPerms write (see {@link RoleSyncApplier}), so one lane for every player
 * costs nothing that matters.
 *
 * <p>A task that throws is logged and does not stop the ones behind it. A rejection by the pool (it
 * is shutting down) drops what is queued and is rethrown to the caller, like any executor.
 */
final class SerialLane implements Executor {

    private final Executor delegate;
    private final HeimdallLogger logger;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
    private boolean draining;

    SerialLane(Executor delegate, HeimdallLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public void execute(Runnable task) {
        synchronized (queue) {
            queue.addLast(task);
            if (draining) {
                return;
            }
            draining = true;
        }
        try {
            delegate.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (queue) {
                queue.clear();
                draining = false;
            }
            throw e;
        }
    }

    private void drain() {
        while (true) {
            Runnable next;
            synchronized (queue) {
                next = queue.pollFirst();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                logger.warn("a queued role-sync task failed: " + e);
            }
        }
    }
}
