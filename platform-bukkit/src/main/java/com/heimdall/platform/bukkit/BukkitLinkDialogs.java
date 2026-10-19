package com.heimdall.platform.bukkit;

import com.heimdall.core.link.LinkCodePrompt;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.platform.LinkDialogs;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.platform.SchedulerBridge;
import com.heimdall.platform.bukkit.adapter.BukkitAdapters;
import com.heimdall.platform.bukkit.adapter.LinkDialogPresenter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.bukkit.entity.Player;

/**
 * Link codes as a dialog on a Paper that can send one; {@link LinkDialogs#NONE} everywhere else.
 *
 * <p>Core calls {@link #show} from {@code heimdall-io}, where nothing about a Bukkit player may be
 * touched. So this answers {@code true} as soon as the server has the API, and every question about
 * the player (still online, a client new enough to draw a dialog) is asked on the player's own
 * thread, where the dialog is then built and sent. Any answer there other than "sent" runs core's
 * chat fallback, so the player always gets the code exactly once, by one route or the other.
 *
 * <p>The dialog itself is {@code :platform-bukkit-paper}'s, reached through
 * {@link BukkitAdapters#linkDialogs}.
 */
final class BukkitLinkDialogs implements LinkDialogs {

    private final HeimdallLogger logger;
    private final SchedulerBridge scheduler;
    private final LinkDialogPresenter presenter;
    private final LongSupplier clock;

    BukkitLinkDialogs(
            HeimdallLogger logger,
            SchedulerBridge scheduler,
            LinkDialogPresenter presenter,
            LongSupplier clock) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.presenter = presenter;
        this.clock = clock;
    }

    /** The dialog where this server has Paper's API, chat everywhere else. */
    static LinkDialogs create(HeimdallLogger logger, SchedulerBridge scheduler) {
        LinkDialogPresenter presenter = BukkitAdapters.linkDialogs(logger);
        if (presenter == null) {
            return LinkDialogs.NONE;
        }
        return new BukkitLinkDialogs(logger, scheduler, presenter, System::currentTimeMillis);
    }

    @Override
    public boolean show(final PlayerHandle handle, final LinkCodePrompt prompt, Runnable fallback) {
        if (!(handle instanceof BukkitPlayerHandle)) {
            return false;
        }
        final Player player = ((BukkitPlayerHandle) handle).player();
        final Runnable once = once(fallback);
        try {
            scheduler.runOnEntityThread(handle, new Runnable() {
                @Override
                public void run() {
                    if (!present(player, prompt)) {
                        once.run();
                    }
                }
            });
        } catch (RuntimeException notScheduled) {
            // A scheduler that refuses outright (the plugin disabling) has run nothing, so the
            // caller is still free to use chat, which is the honest answer.
            logger.debug(() -> "could not schedule a link dialog for " + handle.name() + ": "
                    + notScheduled);
            return false;
        }
        return true;
    }

    /** On the player's thread: whether the dialog went out. */
    private boolean present(Player player, LinkCodePrompt prompt) {
        try {
            if (!player.isOnline()) {
                // The chat fallback skips an offline player too, so this sends nothing at all.
                return false;
            }
            if (!presenter.canShow(player)) {
                logger.debug(() -> player.getName() + "'s client cannot draw a dialog; "
                        + "the link code goes to chat");
                return false;
            }
            return presenter.show(player, prompt, clock.getAsLong());
        } catch (Throwable failed) {
            logger.debug(() -> "link dialog for " + player.getName() + " failed, using chat: "
                    + failed);
            return false;
        }
    }

    /** Guards core's "at most once" against a scheduler that ever ran a task twice. */
    private static Runnable once(final Runnable fallback) {
        final AtomicBoolean ran = new AtomicBoolean();
        return new Runnable() {
            @Override
            public void run() {
                if (ran.compareAndSet(false, true)) {
                    fallback.run();
                }
            }
        };
    }
}
