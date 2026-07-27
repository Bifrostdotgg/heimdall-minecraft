package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.Verdict;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A module that records its lifecycle and, on request, registers things or refuses to start.
 *
 * <p>The point of most module tests is not what a module does but what happens <em>around</em> it —
 * whether its registrations are unwound, whether a failure is contained, whether it is enabled in
 * registration order. So this one deliberately does the minimum that makes those observable: it
 * registers one of everything the context offers, and counts its own calls.
 */
final class RecordingModule implements HeimdallModule {

    private final String id;
    private final Set<String> capabilities;
    private final Set<ServerRole> roles;

    private final AtomicInteger enableCalls = new AtomicInteger();
    private final AtomicInteger disableCalls = new AtomicInteger();
    private final AtomicInteger tickCount = new AtomicInteger();

    private volatile boolean failOnEnable;
    private volatile boolean failOnDisable;
    private volatile boolean registerEverything;
    private volatile ModuleContext lastContext;

    RecordingModule(String id) {
        this(id, Collections.<String>emptySet(), Collections.<ServerRole>emptySet());
    }

    RecordingModule(String id, Set<String> capabilities, Set<ServerRole> roles) {
        this.id = id;
        this.capabilities = capabilities;
        this.roles = roles;
    }

    static Set<String> caps(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(java.util.Arrays.asList(values)));
    }

    static Set<ServerRole> roles(ServerRole... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<ServerRole>(java.util.Arrays.asList(values)));
    }

    RecordingModule failOnEnable() {
        this.failOnEnable = true;
        return this;
    }

    RecordingModule failOnDisable() {
        this.failOnDisable = true;
        return this;
    }

    /** Registers one of everything, so the unwinding can be observed from outside. */
    RecordingModule registerEverything() {
        this.registerEverything = true;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> capabilities() {
        return capabilities;
    }

    @Override
    public Set<ServerRole> roles() {
        return roles;
    }

    @Override
    public void enable(ModuleContext context) {
        lastContext = context;
        enableCalls.incrementAndGet();
        if (registerEverything) {
            context.tunnel().subscribe("role_sync", envelope -> {
            });
            context.interceptLogin(attempt -> Verdict.abstain(), 10);
            context.interceptChat(message -> Verdict.abstain(), 10);
            context.observeChat(message -> {
            });
            context.onConfigChanged((moduleId, previous, current) -> {
            });
            context.scheduleRepeating(tickCount::incrementAndGet, 1_000_000L, 1_000_000L);
        }
        if (failOnEnable) {
            // Thrown AFTER registering, which is the case worth covering: a module that fails
            // halfway has already left things behind.
            throw new IllegalStateException("module '" + id + "' cannot start");
        }
    }

    @Override
    public void disable() {
        disableCalls.incrementAndGet();
        if (failOnDisable) {
            throw new IllegalStateException("module '" + id + "' threw on the way out");
        }
    }

    int enableCalls() {
        return enableCalls.get();
    }

    int disableCalls() {
        return disableCalls.get();
    }

    /** The context from the most recent enable — for the "used after disable" case. */
    ModuleContext lastContext() {
        return lastContext;
    }

    /** A settings read, so a test can prove the context reads live rather than a captured copy. */
    Payload settingsNow() {
        return lastContext.settings();
    }
}
