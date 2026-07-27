package com.heimdall.core.concurrent;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Names Heimdall's threads and marks them daemon.
 *
 * <p>Both matter operationally. A thread called {@code pool-3-thread-1} in a server's stack dump
 * tells an operator nothing, and "which plugin is doing this?" is the first question asked of any
 * hung server. Daemon status means a bug in Heimdall's own shutdown cannot be what stops a server
 * process from exiting.
 */
final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean numbered;
    private final AtomicInteger counter = new AtomicInteger();

    /** Produces {@code prefix-1}, {@code prefix-2}, … */
    static NamedThreadFactory numbered(String prefix) {
        return new NamedThreadFactory(prefix, true);
    }

    /** Produces {@code prefix} — for single-threaded executors, where a number is noise. */
    static NamedThreadFactory single(String name) {
        return new NamedThreadFactory(name, false);
    }

    private NamedThreadFactory(String prefix, boolean numbered) {
        this.prefix = prefix;
        this.numbered = numbered;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        String name = numbered ? prefix + "-" + counter.incrementAndGet() : prefix;
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        // Never inherit the creating thread's priority: these are created from whatever thread
        // happened to boot the plugin, which on Bukkit is the main server thread.
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }
}
