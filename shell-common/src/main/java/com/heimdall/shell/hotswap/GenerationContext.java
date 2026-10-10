package com.heimdall.shell.hotswap;

import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CommandBinding;
import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.Handoff;
import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.contract.Registrations;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.contract.StagedCore;
import com.heimdall.shell.contract.TunnelBackend;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * The {@link ShellContext} one core generation is handed.
 *
 * <p>Owns that generation's tracked registrations, including every command binding and tunnel
 * binding it makes. {@link #retire(ShellLog)} closes whatever is still tracked, newest first, and
 * from then on every registration this context is offered is closed on arrival and every bind is
 * refused: an old core's late work cannot attach itself to the server again after its generation
 * has gone.
 */
class GenerationContext implements ShellContext {

    private final ShellHost host;
    private final ShellPlatform platform;
    private final CoreIdentity core;
    private final Map<String, Object> handoffIn;
    private final Registrations tracked = new Registrations();

    private volatile boolean stoppingForSwap;
    private volatile boolean stopping;
    private volatile Map<String, Object> handoffOut = Collections.emptyMap();

    GenerationContext(
            ShellHost host, ShellPlatform platform, CoreIdentity core, Map<String, Object> handoff) {
        this.host = host;
        this.platform = platform;
        this.core = core;
        this.handoffIn = handoff == null ? Collections.<String, Object>emptyMap() : handoff;
    }

    @Override
    public int contractVersion() {
        return ShellContract.VERSION;
    }

    @Override
    public String platform() {
        return platform.name();
    }

    @Override
    public Object platformPlugin() {
        return platform.plugin();
    }

    @Override
    public Object platformServer() {
        return platform.server();
    }

    @Override
    public Object platformLogger() {
        return platform.logger();
    }

    @Override
    public Path dataDirectory() {
        return platform.dataDirectory();
    }

    @Override
    public File shellJar() {
        return platform.shellJar();
    }

    @Override
    public String shellVersion() {
        return host.shellVersion();
    }

    @Override
    public CoreIdentity core() {
        return core;
    }

    @Override
    public boolean isSwapping() {
        return host.isSwapping();
    }

    @Override
    public Registration track(Registration registration) {
        return tracked.add(registration);
    }

    @Override
    public Map<String, Object> handoff() {
        return handoffIn;
    }

    @Override
    public void handOff(Map<String, ?> state) {
        // Validated whatever happens to it, so a core that breaks the plain-types rule finds out on
        // every stop, not only on the stops that happen to be swaps.
        Map<String, Object> copy = Handoff.copyOf(state);
        if (stopping && stoppingForSwap) {
            handoffOut = copy;
        }
    }

    @Override
    public Registration bindCommand(CommandBinding binding) {
        if (binding == null || tracked.isClosed()) {
            return Registration.NONE;
        }
        return tracked.add(host.bindCommand(binding));
    }

    @Override
    public Registration bindLoginGate(LoginGate gate) {
        if (gate == null || tracked.isClosed()) {
            return Registration.NONE;
        }
        return tracked.add(host.bindLoginGate(gate));
    }

    @Override
    public Registration bindTunnel(TunnelBackend backend) {
        if (backend == null || tracked.isClosed()) {
            return Registration.NONE;
        }
        return tracked.add(host.bindTunnel(backend));
    }

    @Override
    public boolean deliverUnclaimed(String requestId, String type, Payload payload) {
        if (tracked.isClosed()) {
            return false;
        }
        return host.deliverUnclaimed(requestId, type, payload);
    }

    @Override
    public StagedCore stageRelease(Path releaseJar) {
        return host.stageForSwap(releaseJar);
    }

    @Override
    public boolean swapTo(StagedCore staged, Object audience) {
        // A generation already stopping or stopped cannot ask for a swap: the swap would stop a core
        // that is not the one asking.
        if (stopping || tracked.isClosed()) {
            return false;
        }
        return host.swapTo(staged, audience);
    }

    /** Marks the start of this generation's {@code stop()}. */
    void beginStopping(boolean forSwap) {
        this.stoppingForSwap = forSwap;
        this.stopping = true;
    }

    /** What the core handed over during a stop for a swap. */
    Map<String, Object> leftBehind() {
        return handoffOut;
    }

    /** Whether this generation has been retired. */
    boolean isRetired() {
        return tracked.isClosed();
    }

    /**
     * Closes everything still tracked and refuses anything new.
     *
     * @return how many registrations the core had left behind
     */
    int retire(final ShellLog log) {
        return tracked.closeAll(new Registrations.FailureSink() {
            @Override
            public void failed(Throwable failure) {
                log.warn("undoing a registration core " + core + " left behind failed: " + failure);
            }
        });
    }
}
