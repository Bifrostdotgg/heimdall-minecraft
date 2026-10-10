package com.heimdall.core.testing;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link ShellContext} with no shell behind it: it records what a core binds, tracks what it
 * registers, and lets a test say whether a swap is in progress.
 *
 * <p>Its tracked registrations behave like the real shell's: {@link #retire()} closes whatever is
 * left, newest first, and refuses anything later, which is what lets a test prove a core undid its
 * own registrations rather than relying on the shell to.
 */
public final class FakeShellContext implements ShellContext {

    private final String platform;
    private final Path dataDirectory;
    private final Registrations tracked = new Registrations();
    private final Map<String, CommandBinding> commands =
            Collections.synchronizedMap(new LinkedHashMap<String, CommandBinding>());
    private final List<String> delivered = Collections.synchronizedList(new ArrayList<String>());

    private volatile boolean swapping;
    private volatile TunnelBackend backend;
    private volatile LoginGate loginGate;
    private volatile Map<String, Object> handoffIn = Collections.emptyMap();
    private volatile Map<String, Object> handoffOut = Collections.emptyMap();
    private volatile boolean deliverAccepts;
    private volatile StagedCore staging = StagedCore.unusable("no staging set up in this test");
    private final List<String> swapsRequested = Collections.synchronizedList(new ArrayList<String>());

    public FakeShellContext(String platform, Path dataDirectory) {
        this.platform = platform;
        this.dataDirectory = dataDirectory;
    }

    // ── Test controls ────────────────────────────────────────────────────────

    public FakeShellContext swapping(boolean value) {
        this.swapping = value;
        return this;
    }

    public FakeShellContext handoffIn(Map<String, ?> value) {
        this.handoffIn = Handoff.copyOf(value);
        return this;
    }

    /** Whether {@link #deliverUnclaimed} reports that a subscriber took the message. */
    public FakeShellContext deliverAccepts(boolean value) {
        this.deliverAccepts = value;
        return this;
    }

    /** What {@link #stageRelease} answers. */
    public FakeShellContext staging(StagedCore value) {
        this.staging = value;
        return this;
    }

    /** {@code "<version>/<audience>"} for each swap a core started. */
    public List<String> swapsRequested() {
        synchronized (swapsRequested) {
            return new ArrayList<String>(swapsRequested);
        }
    }

    /** What the core handed over, if anything. */
    public Map<String, Object> handoffOut() {
        return handoffOut;
    }

    /** The command currently bound under {@code name}, or {@code null}. */
    public CommandBinding command(String name) {
        return commands.get(name);
    }

    /** Every name currently bound. */
    public List<String> commandNames() {
        synchronized (commands) {
            return new ArrayList<String>(commands.keySet());
        }
    }

    /** The bound login gate, or {@code null}. */
    public LoginGate loginGate() {
        return loginGate;
    }

    /** The bound tunnel backend, or {@code null}. */
    public TunnelBackend backend() {
        return backend;
    }

    /** {@code "<id>/<type>"} for each unclaimed message delivered. */
    public List<String> delivered() {
        synchronized (delivered) {
            return new ArrayList<String>(delivered);
        }
    }

    /** How many tracked registrations are still open. */
    public int open() {
        return tracked.size();
    }

    /** Closes what is left, like the shell after {@code stop()}; returns how many there were. */
    public int retire() {
        return tracked.closeAll();
    }

    // ── ShellContext ─────────────────────────────────────────────────────────

    @Override
    public int contractVersion() {
        return ShellContract.VERSION;
    }

    @Override
    public String platform() {
        return platform;
    }

    @Override
    public Object platformPlugin() {
        return null;
    }

    @Override
    public Object platformServer() {
        return null;
    }

    @Override
    public Object platformLogger() {
        return null;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public File shellJar() {
        return null;
    }

    @Override
    public String shellVersion() {
        return "test";
    }

    @Override
    public CoreIdentity core() {
        return new CoreIdentity("test", "");
    }

    @Override
    public boolean isSwapping() {
        return swapping;
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
        Map<String, Object> copy = Handoff.copyOf(state);
        if (swapping) {
            handoffOut = copy;
        }
    }

    @Override
    public Registration bindCommand(final CommandBinding binding) {
        commands.put(binding.name(), binding);
        return tracked.add(Registration.once(new Runnable() {
            @Override
            public void run() {
                commands.remove(binding.name(), binding);
            }
        }));
    }

    @Override
    public Registration bindLoginGate(final LoginGate gate) {
        loginGate = gate;
        return tracked.add(Registration.once(new Runnable() {
            @Override
            public void run() {
                if (loginGate == gate) {
                    loginGate = null;
                }
            }
        }));
    }

    @Override
    public Registration bindTunnel(final TunnelBackend next) {
        backend = next;
        return tracked.add(Registration.once(new Runnable() {
            @Override
            public void run() {
                if (backend == next) {
                    backend = null;
                }
            }
        }));
    }

    @Override
    public StagedCore stageRelease(Path releaseJar) {
        return staging;
    }

    @Override
    public boolean swapTo(StagedCore staged, Object audience) {
        if (staged == null || !staged.swappable()) {
            return false;
        }
        swapsRequested.add(staged.identity().version() + "/" + audience);
        return true;
    }

    @Override
    public boolean deliverUnclaimed(String requestId, String type, Payload payload) {
        delivered.add(requestId + "/" + type);
        return deliverAccepts;
    }
}
