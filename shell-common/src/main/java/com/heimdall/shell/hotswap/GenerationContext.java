package com.heimdall.shell.hotswap;

import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.Registrations;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.ShellContract;
import java.io.File;
import java.nio.file.Path;

/**
 * The {@link ShellContext} one core generation is handed.
 *
 * <p>Owns that generation's tracked registrations. {@link #retire(ShellLog)} closes whatever is still
 * tracked, newest first, and from then on every registration this context is offered is closed on
 * arrival: an old core's late work cannot attach itself to the server again after its generation
 * has gone.
 */
class GenerationContext implements ShellContext {

    private final ShellHost host;
    private final ShellPlatform platform;
    private final CoreIdentity core;
    private final Registrations tracked = new Registrations();

    GenerationContext(ShellHost host, ShellPlatform platform, CoreIdentity core) {
        this.host = host;
        this.platform = platform;
        this.core = core;
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
