package com.heimdall.core.testing;

import com.heimdall.core.platform.LuckPermsBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LuckPerms, as far as a module can tell.
 *
 * <p>Records every sync rather than applying one, because what the tests need to pin is the
 * <em>arguments</em>: which target list and which managed list a module passed. The diffing itself
 * belongs to {@code GroupDiff} in {@code :platform-common} and is tested there against the real
 * rules — a fake that also diffed would be a second implementation for the module tests to agree
 * with.
 *
 * <p>Thread-safe, since a role sync completes on {@code heimdall-io}.
 */
public final class FakeLuckPerms implements LuckPermsBridge {

    /** One recorded {@link #setPlayerGroups} call. */
    public static final class Sync {

        private final UUID uuid;
        private final List<String> target;
        private final List<String> managed;

        Sync(UUID uuid, List<String> target, List<String> managed) {
            this.uuid = uuid;
            this.target = target == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(target));
            this.managed = managed == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(managed));
        }

        public UUID uuid() {
            return uuid;
        }

        public List<String> targetGroups() {
            return target;
        }

        public List<String> managedGroups() {
            return managed;
        }

        @Override
        public String toString() {
            return "Sync{" + uuid + " target=" + target + " managed=" + managed + "}";
        }
    }

    private final CopyOnWriteArrayList<Sync> syncs = new CopyOnWriteArrayList<Sync>();
    private final Map<UUID, List<String>> currentGroups =
            Collections.synchronizedMap(new LinkedHashMap<UUID, List<String>>());

    private volatile boolean available = true;
    private volatile RuntimeException failure;

    /** Says what {@link #getPlayerGroups} should report for a player. */
    public FakeLuckPerms holding(UUID uuid, String... groups) {
        currentGroups.put(uuid, Collections.unmodifiableList(java.util.Arrays.asList(groups)));
        return this;
    }

    /** Makes the bridge report itself absent, as a server without LuckPerms would. */
    public FakeLuckPerms unavailable() {
        this.available = false;
        return this;
    }

    /** Makes every call fail — the path where LuckPerms is present but its storage is not. */
    public FakeLuckPerms failing(RuntimeException cause) {
        this.failure = cause;
        return this;
    }

    /** Every sync applied, oldest first. */
    public List<Sync> syncs() {
        return Collections.unmodifiableList(new ArrayList<Sync>(syncs));
    }

    /** The most recent sync, or {@code null} if there has not been one. */
    public Sync lastSync() {
        return syncs.isEmpty() ? null : syncs.get(syncs.size() - 1);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public CompletableFuture<List<String>> getPlayerGroups(UUID playerUuid) {
        RuntimeException broken = failure;
        if (broken != null) {
            CompletableFuture<List<String>> failed = new CompletableFuture<List<String>>();
            failed.completeExceptionally(broken);
            return failed;
        }
        List<String> held = currentGroups.get(playerUuid);
        return CompletableFuture.completedFuture(
                held == null ? Collections.<String>emptyList() : held);
    }

    @Override
    public CompletableFuture<Boolean> setPlayerGroups(
            UUID playerUuid, List<String> targetGroups, List<String> managedGroups) {
        RuntimeException broken = failure;
        if (broken != null) {
            CompletableFuture<Boolean> failed = new CompletableFuture<Boolean>();
            failed.completeExceptionally(broken);
            return failed;
        }
        if (!available) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        syncs.add(new Sync(playerUuid, targetGroups, managedGroups));
        return CompletableFuture.completedFuture(Boolean.TRUE);
    }
}
