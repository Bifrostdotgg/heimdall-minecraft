package com.heimdall.platform.bukkit;

import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.core.util.Registration;

/**
 * Runs every hop inline. The roster and describe tests are not about threading, and a real
 * scheduler would need a server.
 */
final class InlineScheduler implements SchedulerBridge {

    static final InlineScheduler INSTANCE = new InlineScheduler();

    private InlineScheduler() {
    }

    @Override
    public void runOnEntityThread(PlayerHandle player, Runnable task) {
        if (task != null) {
            task.run();
        }
    }

    @Override
    public Registration runLater(Runnable task, long delayMs) {
        if (task != null) {
            task.run();
        }
        return Registration.NONE;
    }
}
