package com.heimdall.core.testing;

import com.heimdall.core.command.CommandRegistrar;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.http.BedrockIdentity;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * A whole server, in one file, with nothing running behind it.
 *
 * <p>Everything is answerable and, from phase 1d, everything is <em>steerable</em>: put players
 * online, hand it a LuckPerms bridge or withhold one, feed the console tap a line, read back what
 * was dispatched as the console. The point is that a module test asserts against behaviour — "the
 * player was kicked with this text", "that command was dispatched" — rather than against a mock's
 * call log, which is the failure mode departure D49 splits the facade up to avoid.
 *
 * <p>Deliberately empty by default: no players, no LuckPerms, no Floodgate. That is the state a
 * fresh server is in, and a module that only works when every optional integration is present is a
 * module that breaks on somebody's box.
 *
 * <p><strong>Everything runs inline.</strong> {@link #mainThread()}, {@code runOnEntityThread} and
 * {@code runLater} all execute on the calling thread, so a test needs no latch to observe a hop.
 * {@link #deferringLaterTasks()} is the exception, and exists for the one case where the delay
 * itself is the behaviour under test.
 *
 * <p>Thread-safe.
 */
public final class FakePlatform implements PlatformFacade {

    private final ServerRole role;
    private final Path dataDirectory;
    private final List<String> dispatchedCommands =
            Collections.synchronizedList(new ArrayList<String>());
    private final Map<UUID, PlayerHandle> online =
            Collections.synchronizedMap(new LinkedHashMap<UUID, PlayerHandle>());
    private final CopyOnWriteArrayList<Consumer<LogLine>> consoleTaps =
            new CopyOnWriteArrayList<Consumer<LogLine>>();
    private final RecordingCommands commands = new RecordingCommands();
    private final List<Deferred> deferred = Collections.synchronizedList(new ArrayList<Deferred>());

    private volatile LuckPermsBridge luckPerms;
    private volatile BedrockIdentityProvider floodgate = BedrockIdentityProvider.NONE;
    private volatile boolean deferLater;
    private volatile RuntimeException dispatchFailure;
    private final java.util.Set<String> unknownCommands =
            Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    public FakePlatform(ServerRole role, Path dataDirectory) {
        this.role = role;
        this.dataDirectory = dataDirectory;
    }

    // ── Steering ─────────────────────────────────────────────────────────────

    /** Puts a player online, so {@link #players()} can find them. Returns them, for chaining. */
    public FakePlayer join(FakePlayer player) {
        online.put(player.uuid(), player);
        return player;
    }

    /** Takes a player back off the online list. */
    public void leave(PlayerHandle player) {
        if (player != null) {
            online.remove(player.uuid());
        }
    }

    /** Supplies — or, with {@code null}, withholds — a LuckPerms bridge. */
    public FakePlatform withLuckPerms(LuckPermsBridge bridge) {
        this.luckPerms = bridge;
        return this;
    }

    /** Supplies a Bedrock identity provider, as Floodgate would. */
    public FakePlatform withFloodgate(BedrockIdentityProvider provider) {
        this.floodgate = provider == null ? BedrockIdentityProvider.NONE : provider;
        return this;
    }

    /**
     * Makes {@link ConsoleBridge#dispatchCommand} fail.
     *
     * <p>The path where the bot returns a punishment command and the server refuses it — which the
     * caller has to report to whoever asked rather than swallow.
     */
    public FakePlatform failingDispatch(RuntimeException failure) {
        this.dispatchFailure = failure;
        return this;
    }

    /**
     * Makes a command the server does not have behave like one — a typed failure, not a success.
     *
     * <p>Matched on the first word, because a punishment command arrives as a whole line
     * ({@code tempban Steve 1d …}) and what is missing is the verb.
     */
    public FakePlatform withoutCommand(String verb) {
        unknownCommands.add(verb.trim().toLowerCase(java.util.Locale.ROOT));
        return this;
    }

    /**
     * Makes {@code runLater} queue rather than run inline.
     *
     * <p>Off by default, because inline is what most tests want. On, it is how a test proves a delay
     * really was scheduled: role sync's two-second defer is otherwise indistinguishable from a
     * direct call. {@link #runDeferred()} releases them.
     */
    public FakePlatform deferringLaterTasks() {
        this.deferLater = true;
        return this;
    }

    /** Runs everything {@link #deferringLaterTasks()} queued, oldest first. */
    public int runDeferred() {
        List<Deferred> due;
        synchronized (deferred) {
            due = new ArrayList<Deferred>(deferred);
            deferred.clear();
        }
        for (Deferred task : due) {
            task.task.run();
        }
        return due.size();
    }

    /** How many {@code runLater} tasks are waiting. */
    public int deferredCount() {
        return deferred.size();
    }

    /**
     * The delays the waiting tasks were scheduled with, oldest first.
     *
     * <p>Recorded because a module's choice of delay is otherwise structurally untestable: without
     * it, a two-second defer and a fifty-millisecond one are the same observation, so a test can
     * prove that something was deferred but never that it was deferred for the right reason.
     */
    public List<Long> deferredDelays() {
        List<Long> delays = new ArrayList<Long>();
        synchronized (deferred) {
            for (Deferred task : deferred) {
                delays.add(Long.valueOf(task.delayMs));
            }
        }
        return Collections.unmodifiableList(delays);
    }

    /** One task {@code runLater} was asked to defer, and how long for. */
    private static final class Deferred {

        private final Runnable task;
        private final long delayMs;

        Deferred(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    /** Feeds a line to every attached console tap, as the server's logging backend would. */
    public void emitConsoleLine(LogLine line) {
        for (Consumer<LogLine> tap : consoleTaps) {
            tap.accept(line);
        }
    }

    /** How many console taps are attached — the leak assertion for the console module. */
    public int consoleTapCount() {
        return consoleTaps.size();
    }

    /** Everything dispatched as the console, oldest first. */
    public List<String> dispatchedCommands() {
        synchronized (dispatchedCommands) {
            return new ArrayList<String>(dispatchedCommands);
        }
    }

    /** The command registrar, so a test can run what a module registered. */
    public RecordingCommands commandRegistry() {
        return commands;
    }

    private boolean isUnknown(String command) {
        if (command == null || unknownCommands.isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        String verb = (space < 0 ? trimmed : trimmed.substring(0, space))
                .toLowerCase(java.util.Locale.ROOT);
        return unknownCommands.contains(verb);
    }

    /** A Floodgate stand-in that reports exactly one UUID as a Bedrock player. */
    public static BedrockIdentityProvider bedrockFor(final String uuid, final BedrockIdentity identity) {
        return new BedrockIdentityProvider() {
            @Override
            public BedrockIdentity resolve(String playerUuid) {
                return uuid.equalsIgnoreCase(playerUuid) ? identity : null;
            }
        };
    }

    /** A UUID derived from a name, so a fixture reads as the player rather than as a snowflake. */
    public static UUID uuidFor(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    // ── PlatformFacade ───────────────────────────────────────────────────────

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
                return uuid == null
                        ? Optional.<PlayerHandle>empty()
                        : Optional.ofNullable(online.get(uuid));
            }

            @Override
            public Optional<PlayerHandle> byName(String username) {
                if (username == null) {
                    return Optional.empty();
                }
                for (PlayerHandle handle : onlinePlayers()) {
                    if (username.equalsIgnoreCase(handle.name())) {
                        return Optional.of(handle);
                    }
                }
                return Optional.empty();
            }

            @Override
            public Collection<PlayerHandle> onlinePlayers() {
                synchronized (online) {
                    return Collections.unmodifiableList(
                            new ArrayList<PlayerHandle>(online.values()));
                }
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
            public Registration runLater(final Runnable task, long delayMs) {
                if (!deferLater) {
                    task.run();
                    return Registration.NONE;
                }
                final Deferred pending = new Deferred(task, delayMs);
                deferred.add(pending);
                return Registration.once(new Runnable() {
                    @Override
                    public void run() {
                        deferred.remove(pending);
                    }
                });
            }
        };
    }

    @Override
    public ConsoleBridge console() {
        return new ConsoleBridge() {
            @Override
            public CompletableFuture<String> dispatchCommand(String command) {
                RuntimeException failure = dispatchFailure;
                if (failure != null) {
                    CompletableFuture<String> failed = new CompletableFuture<String>();
                    failed.completeExceptionally(failure);
                    return failed;
                }
                if (isUnknown(command)) {
                    // Recorded even so: the caller DID dispatch it, and a test asserting "nothing
                    // was run" must be able to tell that apart from "it was run and refused".
                    dispatchedCommands.add(command);
                    CompletableFuture<String> unknown = new CompletableFuture<String>();
                    unknown.completeExceptionally(
                            new com.heimdall.core.platform.UnknownCommandException(command));
                    return unknown;
                }
                dispatchedCommands.add(command);
                return CompletableFuture.completedFuture("dispatched: " + command);
            }

            @Override
            public Registration attachLogTap(final Consumer<LogLine> consumer) {
                consoleTaps.add(consumer);
                return Registration.once(new Runnable() {
                    @Override
                    public void run() {
                        consoleTaps.remove(consumer);
                    }
                });
            }
        };
    }

    @Override
    public CommandRegistrar commands() {
        return commands;
    }

    @Override
    public Integrations integrations() {
        return new Integrations() {
            @Override
            public Optional<LuckPermsBridge> luckPerms() {
                return Optional.ofNullable(luckPerms);
            }

            @Override
            public BedrockIdentityProvider floodgate() {
                return floodgate;
            }

            @Override
            public CompletableFuture<Payload> traceProbe(UUID playerUuid) {
                return CompletableFuture.completedFuture(
                        Payload.builder().put("error", "no platform").build());
            }
        };
    }
}
