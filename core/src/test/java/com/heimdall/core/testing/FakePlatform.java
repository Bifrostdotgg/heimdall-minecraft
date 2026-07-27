package com.heimdall.core.testing;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.BedrockIdentityProvider;
import com.heimdall.core.json.Payload;
import com.heimdall.core.platform.ConsoleBridge;
import com.heimdall.core.platform.Integrations;
import com.heimdall.core.platform.LogLine;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.platform.PlatformFacade;
import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A {@link PlatformFacade} with no server behind it.
 *
 * <p>Everything runs inline and answers "nothing there", which is a legitimate implementation of
 * every contract in the package: the facade promises an {@link Executor} rather than a specific
 * thread precisely so a platform with no main-thread constraint — or a test — does not have to
 * invent one, and every integration is documented as optional.
 *
 * <p>{@link #dispatchedCommands} is the one piece of recording, because "did the runtime try to run
 * that" is a question tests ask and cannot otherwise see.
 */
public final class FakePlatform implements PlatformFacade {

    private final ServerRole role;
    private final Path dataDirectory;
    private final List<String> dispatchedCommands =
            Collections.synchronizedList(new ArrayList<String>());

    public FakePlatform(ServerRole role, Path dataDirectory) {
        this.role = role;
        this.dataDirectory = dataDirectory;
    }

    /** Every command handed to {@link ConsoleBridge#dispatchCommand}, in order. */
    public List<String> dispatchedCommands() {
        synchronized (dispatchedCommands) {
            return new ArrayList<String>(dispatchedCommands);
        }
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

    @Override
    public PlayerDirectory players() {
        return new PlayerDirectory() {
            @Override
            public Optional<PlayerHandle> byUuid(UUID uuid) {
                return Optional.empty();
            }

            @Override
            public Optional<PlayerHandle> byName(String username) {
                return Optional.empty();
            }

            @Override
            public Collection<PlayerHandle> onlinePlayers() {
                return Collections.emptyList();
            }
        };
    }

    @Override
    public SchedulerBridge scheduler() {
        return new SchedulerBridge() {
            @Override
            public void runOnEntityThread(PlayerHandle player, Runnable task) {
                task.run();
            }

            @Override
            public Registration runLater(Runnable task, long delayMs) {
                task.run();
                return Registration.NONE;
            }
        };
    }

    @Override
    public ConsoleBridge console() {
        return new ConsoleBridge() {
            @Override
            public CompletableFuture<String> dispatchCommand(String command) {
                dispatchedCommands.add(command);
                return CompletableFuture.completedFuture("dispatched: " + command);
            }

            @Override
            public Registration attachLogTap(Consumer<LogLine> consumer) {
                return Registration.NONE;
            }
        };
    }

    @Override
    public Integrations integrations() {
        return new Integrations() {
            @Override
            public Optional<LuckPermsBridge> luckPerms() {
                return Optional.empty();
            }

            @Override
            public BedrockIdentityProvider floodgate() {
                return BedrockIdentityProvider.NONE;
            }

            @Override
            public CompletableFuture<Payload> traceProbe(UUID playerUuid) {
                return CompletableFuture.completedFuture(
                        Payload.builder().put("error", "no platform").build());
            }
        };
    }
}
