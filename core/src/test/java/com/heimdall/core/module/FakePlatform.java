package com.heimdall.core.module;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.platform.PlatformFacade;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/**
 * A {@link PlatformFacade} with no server behind it.
 *
 * <p>The stand-in for phase 1c. Its main-thread executor runs inline, which is a legitimate
 * implementation of the contract — the interface deliberately promises an executor rather than a
 * specific thread, precisely so a platform with no main-thread constraint (or a test) does not have
 * to invent one.
 */
final class FakePlatform implements PlatformFacade {

    private final ServerRole role;
    private final Path dataDirectory;

    FakePlatform(ServerRole role, Path dataDirectory) {
        this.role = role;
        this.dataDirectory = dataDirectory;
    }

    @Override
    public ServerRole role() {
        return role;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public Executor mainThread() {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }
}
