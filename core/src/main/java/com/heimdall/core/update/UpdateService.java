package com.heimdall.core.update;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import com.heimdall.core.util.Strings;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Knows whether this server is behind, and installs the fix when asked.
 *
 * <p>v2's {@code UpdateChecker} state machine, plus the scheduling that v2 spread across its two
 * plugin classes, minus the {@code ApiClient} and {@code File} dependencies that made it
 * untestable. Everything platform-shaped is behind {@link ReleaseSource} and
 * {@link UpdateInstaller}.
 *
 * <h2>An update check is nobody's emergency</h2>
 *
 * <p>Every public method here is documented as never throwing, and that is a design position rather
 * than defensive coding. The bot being unreachable, answering nonsense, or reporting no release at
 * all are all ordinary states — a bot redeploy makes every server in the fleet see them at once —
 * and none of them is worth a stack trace in a server log, let alone a failed boot. So a failed
 * check logs one debug line, publishes nothing, and leaves the previous answer in place. What
 * <em>is</em> worth an operator's attention is the opposite case, and that gets v2's five-line
 * warning banner.
 *
 * <h2>Published state is one object, not four fields</h2>
 *
 * <p>v2 had {@code volatile PluginReleaseInfo latestRelease} and {@code volatile boolean
 * updateAvailable} written one after the other. Two threads can reach {@link #checkNow()} at once —
 * the periodic tick and an operator running {@code /hd version} — and with separate volatiles a
 * reader between those two writes sees one check's release with the other check's verdict. The
 * window is small and the consequence is real: {@link #joinNotice} would render "3.0.0 → 3.0.0", or
 * a command would offer an update for a release that is not newer.
 *
 * <p>Here the three published facts — the release, the verdict, and whether any check has ever
 * succeeded — live on one immutable {@link Published} object in a single volatile field. Concurrent
 * checks then race to <em>replace</em> it, which is fine: both answers were true when they were
 * computed, and whichever lands last is the more recent. No reader can observe a mixture. Reads
 * that need more than one fact ({@link #joinNotice}, {@link #updateNow()}) take one snapshot into a
 * local and use that, rather than reading the field twice.
 *
 * <h2>Threading and ownership</h2>
 *
 * <p>{@link #checkNow()} and {@link #updateNow()} <strong>block</strong> — on the release future and
 * on a multi-megabyte download respectively — and must never be called on a server or proxy thread.
 * Their callers are the periodic task on {@code heimdall-sched} and the platform command handlers,
 * which run them on an async task.
 *
 * <p><strong>This service does not own the scheduler it is handed.</strong> {@link Registration}
 * from {@link #startPeriodicChecks} cancels the repeating task and nothing else; the caller shares
 * that scheduler with everything else Heimdall runs on a timer. It does not own the
 * {@link UpdateDownloader} or the {@link UpdateInstaller} either — both are wiring-supplied and
 * outlive it.
 */
public final class UpdateService {

    private final HeimdallLogger logger;
    private final String currentVersion;
    private final ReleaseSource releases;
    private final UpdateInstaller installer;
    private final UpdateDownloader downloader;
    private final ScheduledExecutorService scheduler;

    private volatile Published published = Published.NOTHING;
    private volatile Registration periodic = Registration.NONE;
    private boolean started;

    public UpdateService(
            HeimdallLogger logger,
            String currentVersion,
            ReleaseSource releases,
            UpdateInstaller installer,
            UpdateDownloader downloader,
            ScheduledExecutorService scheduler) {
        if (logger == null || releases == null || scheduler == null) {
            throw new IllegalArgumentException("logger, release source and scheduler are required");
        }
        this.logger = logger;
        this.currentVersion = Versions.normalize(currentVersion);
        this.releases = releases;
        this.installer = installer;
        this.downloader = downloader;
        this.scheduler = scheduler;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /** The version this server is running, with any leading {@code v} stripped. */
    public String currentVersion() {
        return currentVersion;
    }

    /** Whether the bot has reported something newer than {@link #currentVersion()}. */
    public boolean isUpdateAvailable() {
        return published.available;
    }

    /** The latest release the bot reported, or {@code null} before a successful check. */
    public PluginRelease latestRelease() {
        return published.release;
    }

    /**
     * Whether a check has completed and published an answer.
     *
     * <p>False after a check that failed, and also after one the bot answered with no release —
     * "we asked and got nothing" is not an answer worth telling an operator they have.
     */
    public boolean hasChecked() {
        return published.checked;
    }

    // ── The check ────────────────────────────────────────────────────────────

    /**
     * Asks for the latest release and updates the published state.
     *
     * <p><strong>Blocking</strong>, bounded by {@link ReleaseSource#joinTimeoutMs()}. Never throws.
     *
     * @return whether an update is available as of this check; {@code false} on any failure
     */
    public boolean checkNow() {
        try {
            PluginRelease release =
                    releases.latestRelease().get(releases.joinTimeoutMs(), TimeUnit.MILLISECONDS);
            if (release == null || Strings.isBlank(release.version())) {
                logger.debug("no release information returned by the bot");
                return false;
            }
            boolean available = Versions.isNewer(release.version(), currentVersion);
            published = new Published(release, available, true);
            if (available) {
                announce(release);
            } else {
                logger.info("Heimdall is up to date (" + currentVersion + ").");
            }
            return available;
        } catch (Exception failed) {
            // Everything, including a timeout and an interrupt. The previous answer stands: a bot
            // that is briefly down must not make a server forget an update it already knows about.
            //
            // Thread.interrupted() clears the flag rather than restoring it, matching
            // WhitelistMirrorService.syncNow(). This runs on a shared pool thread, and leaving it
            // interrupted would make the *next* unrelated task on that thread fail at its first
            // blocking call, arbitrarily far from here.
            Thread.interrupted();
            logger.debug("update check failed: " + rootMessage(failed));
            return false;
        }
    }

    /**
     * Checks, then installs if there is something newer.
     *
     * <p><strong>Blocking</strong> — the check plus a multi-megabyte download. Never throws; a
     * failure comes back as {@link InstallOutcome#failed(String)}, because every caller is a command
     * handler or a tunnel reply that has to print something either way.
     */
    public InstallOutcome updateNow() {
        checkNow();
        Published current = published;
        if (!current.available || current.release == null) {
            return InstallOutcome.upToDate("Heimdall " + currentVersion + " is already the latest version.");
        }
        if (installer == null || downloader == null) {
            return InstallOutcome.failed("This platform cannot install updates automatically.");
        }
        String version = Versions.normalize(current.release.version());
        try {
            InstallOutcome outcome = installer.install(current.release, downloader);
            if (outcome == null) {
                return InstallOutcome.failed("The installer reported nothing for " + version + ".");
            }
            return outcome;
        } catch (Exception failed) {
            logger.warn("could not install " + version + ": " + rootMessage(failed));
            return InstallOutcome.failed("Update failed: " + rootMessage(failed));
        }
    }

    // ── Scheduling ───────────────────────────────────────────────────────────

    /**
     * Starts the periodic check, running the first one immediately.
     *
     * <p>Immediately rather than after the first interval, because a server that has just started is
     * the one most likely to be behind — v2 waited a full twelve hours before its first check, so a
     * server restarted daily never checked at all.
     *
     * <p>{@code settings} is re-read <strong>on every tick</strong>, so switching
     * {@link UpdateSettings#checkEnabled()} off in the dashboard skips the next check without
     * needing a restart, and switching it back on resumes. The <em>interval</em> is deliberately
     * <strong>not</strong> re-read: the fixed rate is derived from the settings once, here, and
     * changing {@link UpdateSettings#checkIntervalHours()} does not take effect until the plugin
     * restarts. Re-scheduling on an interval change is out of scope for phase 1e, and the cost of
     * not doing it is bounded — the worst case is a check happening on yesterday's cadence.
     *
     * <p>Idempotent: a second call returns the same handle rather than starting a second timer.
     *
     * @return a handle that stops further checks; {@link Registration#NONE} if the scheduler is
     *     already shutting down
     */
    public synchronized Registration startPeriodicChecks(final Supplier<UpdateSettings> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("a settings supplier is required");
        }
        if (started) {
            return periodic;
        }
        long intervalMs = Math.max(1L, currentInterval(settings));
        try {
            final ScheduledFuture<?> handle = scheduler.scheduleAtFixedRate(
                    guard(tick(settings)), 0L, intervalMs, TimeUnit.MILLISECONDS);
            started = true;
            periodic = Registration.once(new Runnable() {
                @Override
                public void run() {
                    handle.cancel(false);
                    stopped();
                }
            });
            logger.debug("update checks every " + intervalMs + "ms, starting now");
            return periodic;
        } catch (RejectedExecutionException e) {
            logger.debug("not scheduling update checks: the scheduler is shutting down");
            return Registration.NONE;
        }
    }

    /**
     * The line an admin joining the server should be shown, or {@code null} when there is nothing
     * to say.
     *
     * <p>Null unless notifications are on, a check has succeeded, and that check found something
     * newer. Cheap and non-blocking — safe on the join path.
     */
    public String joinNotice(UpdateSettings settings) {
        if (settings == null || !settings.notifyAdmins()) {
            return null;
        }
        Published current = published;
        if (!current.checked || !current.available || current.release == null) {
            return null;
        }
        return "An update is available: " + currentVersion + " → "
                + Versions.normalize(current.release.version()) + ". Run /hd update to download it.";
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private synchronized void stopped() {
        started = false;
        periodic = Registration.NONE;
    }

    /**
     * The interval to schedule at, from the supplier if it can answer and from the defaults if not.
     *
     * <p>The supplier is somebody else's code, reached at boot before remote config has necessarily
     * arrived. A throw here is not worth failing start-up over — the fallback is a twelve-hour
     * cadence, which is what the setting defaults to anyway — and the per-tick read (which
     * {@link #guard} covers) will see the real settings once they load.
     */
    private long currentInterval(Supplier<UpdateSettings> settings) {
        try {
            UpdateSettings current = settings.get();
            if (current != null) {
                return current.checkIntervalMs();
            }
        } catch (RuntimeException e) {
            logger.debug("could not read the update settings yet; scheduling at the default interval: "
                    + rootMessage(e));
        }
        return UpdateSettings.defaults().checkIntervalMs();
    }

    private Runnable tick(final Supplier<UpdateSettings> settings) {
        return new Runnable() {
            @Override
            public void run() {
                UpdateSettings current = settings.get();
                if (current != null && !current.checkEnabled()) {
                    logger.debug("skipping the update check: it is switched off");
                    return;
                }
                checkNow();
            }
        };
    }

    /**
     * Stops a throw from silently cancelling the repeat.
     *
     * <p>{@code scheduleAtFixedRate} drops a task that throws, without a word — see
     * {@code ModuleContextImpl.guard}. {@link #checkNow()} does not throw, but the settings supplier
     * is somebody else's code and can.
     */
    private Runnable guard(final Runnable task) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.error("the periodic update check failed", e);
                }
            }
        };
    }

    /** v2's banner, with {@code /hd update} in place of {@code /hwl update}. */
    private void announce(PluginRelease release) {
        logger.warn("================================================");
        logger.warn("  A new Heimdall version is available!");
        logger.warn("  Installed: " + currentVersion + "   Latest: " + Versions.normalize(release.version()));
        logger.warn("  Run '/hd update' to download it (applied on restart).");
        if (Strings.isNotBlank(release.htmlUrl())) {
            logger.warn("  Release notes: " + release.htmlUrl());
        }
        logger.warn("================================================");
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        String message = t.getMessage();
        while (current.getCause() != null) {
            current = current.getCause();
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message != null ? message : t.getClass().getSimpleName();
    }

    /** The three facts a reader must never see a mixture of. See the class javadoc. */
    private static final class Published {

        static final Published NOTHING = new Published(null, false, false);

        final PluginRelease release;
        final boolean available;
        final boolean checked;

        Published(PluginRelease release, boolean available, boolean checked) {
            this.release = release;
            this.available = available;
            this.checked = checked;
        }
    }
}
