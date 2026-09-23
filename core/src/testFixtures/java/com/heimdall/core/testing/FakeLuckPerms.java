package com.heimdall.core.testing;

import com.heimdall.core.platform.GroupsChangedListener;
import com.heimdall.core.platform.LuckPermsBridge;
import com.heimdall.core.util.Registration;
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

    private final CopyOnWriteArrayList<GroupsChangedListener> groupListeners =
            new CopyOnWriteArrayList<GroupsChangedListener>();

    private volatile boolean available = true;
    private volatile boolean refusingListeners;
    private volatile RuntimeException failure;

    /**
     * Loads a player holding {@code groups}: what {@link #getPlayerGroups} reports for them. With no
     * groups this is "loaded, holds nothing", which is a real answer and not the same as unknown.
     */
    public FakeLuckPerms holding(UUID uuid, String... groups) {
        currentGroups.put(uuid, Collections.unmodifiableList(java.util.Arrays.asList(groups)));
        return this;
    }

    /** Loads a player who holds no groups: {@link #getPlayerGroups} answers {@code []}. */
    public FakeLuckPerms known(UUID uuid) {
        return holding(uuid);
    }

    /** Makes the bridge report itself absent, as a server without LuckPerms would. */
    public FakeLuckPerms unavailable() {
        this.available = false;
        return this;
    }

    /**
     * Makes {@link #onGroupsChanged} answer {@link Registration#NONE} while still reporting itself
     * available: a LuckPerms that is up but will not take a listener, so a test can prove the caller
     * does not mistake that for a subscription.
     */
    public FakeLuckPerms refusingListeners(boolean value) {
        this.refusingListeners = value;
        return this;
    }

    /** Makes every call fail — the path where LuckPerms is present but its storage is not. */
    public FakeLuckPerms failing(RuntimeException cause) {
        this.failure = cause;
        return this;
    }

    /**
     * Changes what a player holds and tells every {@link #onGroupsChanged} listener, inline.
     *
     * <p>Both halves, because that is what LuckPerms does: the new state is what a later
     * {@link #getPlayerGroups} reads, and the listener is handed the whole list rather than a delta.
     * Inline rather than on an executor so a test controls the ordering; the real bridge's hop off
     * LuckPerms' thread is {@code LuckPermsIntegration}'s to prove.
     */
    public FakeLuckPerms fireGroupsChanged(UUID uuid, List<String> groups) {
        List<String> now = Collections.unmodifiableList(new ArrayList<String>(groups));
        currentGroups.put(uuid, now);
        for (GroupsChangedListener listener : groupListeners) {
            listener.onGroupsChanged(uuid, now);
        }
        return this;
    }

    /** How many group-change listeners are subscribed: the leak assertion for a module toggle. */
    public int groupListenerCount() {
        return groupListeners.size();
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
        List<String> held = available ? currentGroups.get(playerUuid) : null;
        if (held == null) {
            // The real bridge's contract: a player it cannot load, or no LuckPerms at all, is
            // unknown, and unknown is a failed future rather than an empty list.
            CompletableFuture<List<String>> unknown = new CompletableFuture<List<String>>();
            unknown.completeExceptionally(new IllegalStateException(available
                    ? "no such user loaded: " + playerUuid : "LuckPerms is not available"));
            return unknown;
        }
        return CompletableFuture.completedFuture(held);
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

    /** Honours {@link #unavailable()} the way the real bridge does: no LuckPerms, no listener. */
    @Override
    public Registration onGroupsChanged(final GroupsChangedListener listener) {
        if (!available || refusingListeners || listener == null) {
            return Registration.NONE;
        }
        groupListeners.add(listener);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                groupListeners.remove(listener);
            }
        });
    }
}
