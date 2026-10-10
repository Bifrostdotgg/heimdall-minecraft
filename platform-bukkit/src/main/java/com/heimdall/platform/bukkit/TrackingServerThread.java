package com.heimdall.platform.bukkit;

import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.util.Registration;
import com.heimdall.platform.common.CoreRegistrations;
import java.util.function.Function;

/**
 * A {@link ServerThread} whose delayed tasks are tracked against the core generation that
 * scheduled them.
 *
 * <p>Delayed work is the one scheduler path that can fire long after the code that scheduled it has
 * stopped: a kick a second after a ban, a retry ten seconds out. Before the hot-swap split a plugin
 * disable cancelled all of it. A swap cancels nothing on its own, and {@code cancelTasks(plugin)} is
 * not an option because the plugin is the shell's, so each delayed task is tracked here until it
 * runs or is cancelled, and the generation's teardown cancels whatever is still pending (departure
 * D87).
 *
 * <p>Immediate hops ({@link #execute}) are not tracked: they run within a tick, and the retired
 * core's classloader stays open long enough for them (see the shell's loader grace period).
 */
final class TrackingServerThread implements ServerThread {

    private final ServerThread delegate;
    private final CoreRegistrations registrations;

    TrackingServerThread(ServerThread delegate, CoreRegistrations registrations) {
        this.delegate = delegate;
        this.registrations = registrations;
    }

    @Override
    public String describe() {
        return delegate.describe();
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @Override
    public void runOnEntityThread(PlayerHandle player, Runnable task) {
        delegate.runOnEntityThread(player, task);
    }

    @Override
    public Registration runLater(Runnable task, final long delayMs) {
        if (task == null) {
            return Registration.NONE;
        }
        return registrations.oneShot(new Function<Runnable, Registration>() {
            @Override
            public Registration apply(Runnable wrapped) {
                return delegate.runLater(wrapped, delayMs);
            }
        }, task);
    }
}
