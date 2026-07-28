package com.heimdall.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.LogLevel;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.util.Registration;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The update state machine, which v2 had no tests for at all.
 *
 * <p>Both of v2's branches — "there is something newer" and "there is not" — shipped unproven, and
 * so did every failure path, because {@code UpdateChecker} held a live {@code ApiClient} and a
 * {@code File}. {@link ReleaseSource} and {@link UpdateInstaller} exist so this file can be written.
 *
 * <p>Scheduling is driven by a capturing scheduler rather than by a real one with a short interval:
 * "the handle stops further checks" is an assertion about what does <em>not</em> happen, and a
 * timing-based version of that test can only ever be a sleep that is long enough on this machine.
 * {@code CapturingScheduler} in {@code com.heimdall.core.tunnel} is package-private there, so the
 * same shape is repeated here as {@link CapturingScheduler}.
 */
class UpdateServiceTest {

    private static final String CURRENT = "3.0.0";

    private final RecordingLogger logger = new RecordingLogger(true);
    private final CapturingScheduler scheduler = new CapturingScheduler();
    private final FakeInstaller installer = new FakeInstaller();

    private UpdateService serviceFor(ReleaseSource releases) {
        return new UpdateService(logger, CURRENT, releases, installer,
                new UpdateDownloader(logger, DownloadPolicy.github()), scheduler);
    }

    private static PluginRelease release(String version) {
        return PluginRelease.builder()
                .version(version)
                .downloadUrl("https://github.com/Bifrostdotgg/heimdall-minecraft/releases/x.jar")
                .htmlUrl("https://github.com/Bifrostdotgg/heimdall-minecraft/releases/tag/" + version)
                .build();
    }

    @Nested
    @DisplayName("checkNow")
    class CheckNow {

        @Test
        @DisplayName("a newer version is published and announced")
        void newerVersion() {
            UpdateService service = serviceFor(new FakeSource(release("v3.1.0")));

            assertTrue(service.checkNow());

            assertTrue(service.isUpdateAvailable());
            assertTrue(service.hasChecked());
            assertNotNull(service.latestRelease());
            assertEquals("v3.1.0", service.latestRelease().version());
            assertTrue(logger.logged(LogLevel.WARN, "A new Heimdall version is available!"));
            assertTrue(logger.logged(LogLevel.WARN, "Installed: 3.0.0   Latest: 3.1.0"));
            assertTrue(logger.logged(LogLevel.WARN, "/hd update"),
                    "the banner must offer v3's command, not v2's /hwl update");
            assertFalse(logger.logged(LogLevel.WARN, "/hwl"));
            assertTrue(logger.logged(LogLevel.WARN, "Release notes: "));
        }

        @Test
        @DisplayName("the same version is not an update, and gets no banner")
        void sameVersion() {
            UpdateService service = serviceFor(new FakeSource(release("3.0.0")));

            assertFalse(service.checkNow());

            assertFalse(service.isUpdateAvailable());
            assertTrue(service.hasChecked());
            assertNotNull(service.latestRelease());
            assertNoBanner();
        }

        @Test
        @DisplayName("an older version is not an update either")
        void olderVersion() {
            UpdateService service = serviceFor(new FakeSource(release("2.4.0")));

            assertFalse(service.checkNow());

            assertFalse(service.isUpdateAvailable());
            assertNoBanner();
        }

        @Test
        @DisplayName("a failing source is a debug line, not an exception")
        void failingSource() {
            UpdateService service = serviceFor(FakeSource.failing(new IOException("bot is down")));

            assertFalse(service.checkNow());

            assertFalse(service.isUpdateAvailable());
            assertFalse(service.hasChecked());
            assertNull(service.latestRelease());
            assertTrue(logger.logged(LogLevel.DEBUG, "update check failed"));
            assertTrue(logger.logged(LogLevel.DEBUG, "bot is down"),
                    "the root cause should survive into the debug line");
            assertNoBanner();
        }

        @Test
        @DisplayName("a blank or absent version publishes nothing")
        void blankVersion() {
            UpdateService service = serviceFor(new FakeSource(release("   ")));
            assertFalse(service.checkNow());
            assertFalse(service.hasChecked());
            assertNull(service.latestRelease());
            assertTrue(logger.logged(LogLevel.DEBUG, "no release information"));

            logger.clear();
            UpdateService noRelease = serviceFor(new FakeSource(null));
            assertFalse(noRelease.checkNow());
            assertFalse(noRelease.hasChecked());
            assertTrue(logger.logged(LogLevel.DEBUG, "no release information"));
            assertNoBanner();
        }

        @Test
        @DisplayName("a later failure does not erase what the last good check found")
        void failureKeepsThePreviousAnswer() {
            SwitchableSource source = new SwitchableSource(release("3.1.0"));
            UpdateService service = serviceFor(source);
            assertTrue(service.checkNow());

            source.failWith(new IOException("bot is down"));
            assertFalse(service.checkNow());

            assertTrue(service.isUpdateAvailable(), "the known update must survive a transient outage");
            assertEquals("3.1.0", service.latestRelease().version());
        }
    }

    @Nested
    @DisplayName("updateNow")
    class UpdateNow {

        @Test
        @DisplayName("does not call the installer when there is nothing newer")
        void nothingNewer() {
            UpdateService service = serviceFor(new FakeSource(release("3.0.0")));

            InstallOutcome outcome = service.updateNow();

            assertFalse(outcome.installed());
            assertEquals(0, installer.calls.get());
            assertTrue(outcome.message().contains("3.0.0"), outcome.message());
        }

        @Test
        @DisplayName("installs once and returns the installer's own outcome")
        void installsWhenNewer() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));

            InstallOutcome outcome = service.updateNow();

            assertEquals(1, installer.calls.get());
            assertSame(installer.outcome, outcome);
            assertTrue(outcome.installed());
            assertEquals("3.1.0", installer.installedVersion);
        }

        @Test
        @DisplayName("an installer that throws becomes a failed outcome, not an exception")
        void installerThrows() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            installer.failWith(new IOException("the jar is locked"));

            InstallOutcome outcome = service.updateNow();

            assertFalse(outcome.installed());
            assertTrue(outcome.message().contains("the jar is locked"), outcome.message());
            assertNull(outcome.target());
        }

        @Test
        @DisplayName("a failing source leaves nothing to install")
        void failingSource() {
            UpdateService service = serviceFor(FakeSource.failing(new IOException("bot is down")));

            InstallOutcome outcome = service.updateNow();

            assertFalse(outcome.installed());
            assertEquals(0, installer.calls.get());
        }
    }

    @Nested
    @DisplayName("joinNotice")
    class JoinNotice {

        @Test
        @DisplayName("null until a check has found something")
        void nullUntilThereIsSomethingToSay() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            assertNull(service.joinNotice(UpdateSettings.defaults()), "no check has run yet");

            service.checkNow();
            assertNotNull(service.joinNotice(UpdateSettings.defaults()));
        }

        @Test
        @DisplayName("null when notifyAdmins is off, even with an update pending")
        void silencedByTheSetting() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            service.checkNow();

            assertNull(service.joinNotice(settings(false, true, 12)));
            assertNotNull(service.joinNotice(settings(true, true, 12)));
        }

        @Test
        @DisplayName("null when the latest release is what is already running")
        void nullWhenUpToDate() {
            UpdateService service = serviceFor(new FakeSource(release("3.0.0")));
            service.checkNow();

            assertNull(service.joinNotice(UpdateSettings.defaults()));
        }

        @Test
        @DisplayName("names both versions and the command")
        void namesBothVersions() {
            UpdateService service = serviceFor(new FakeSource(release("v3.1.0")));
            service.checkNow();

            String notice = service.joinNotice(UpdateSettings.defaults());

            assertTrue(notice.contains("3.0.0"), notice);
            assertTrue(notice.contains("3.1.0"), notice);
            assertFalse(notice.contains("v3.1.0"), "the leading v should be normalised away: " + notice);
            assertTrue(notice.contains("/hd update"), notice);
        }

        @Test
        @DisplayName("null settings are tolerated")
        void nullSettings() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            service.checkNow();
            assertNull(service.joinNotice(null));
        }
    }

    @Nested
    @DisplayName("startPeriodicChecks")
    class PeriodicChecks {

        @Test
        @DisplayName("the first check runs immediately, at the configured rate")
        void firstCheckIsImmediate() {
            FakeSource source = new FakeSource(release("3.1.0"));
            UpdateService service = serviceFor(source);

            service.startPeriodicChecks(constant(UpdateSettings.defaults()));

            assertEquals(1, scheduler.captured.size());
            assertEquals(0L, scheduler.captured.get(0).initialDelayMs, "the first check must not wait");
            assertEquals(TimeUnit.HOURS.toMillis(12), scheduler.captured.get(0).periodMs);
            assertEquals(0, source.calls.get(), "nothing runs until the scheduler dispatches");

            scheduler.runPending();
            assertEquals(1, source.calls.get());
            assertTrue(service.isUpdateAvailable());
        }

        @Test
        @DisplayName("the handle stops further checks")
        void theHandleStopsIt() {
            FakeSource source = new FakeSource(release("3.1.0"));
            UpdateService service = serviceFor(source);

            Registration handle = service.startPeriodicChecks(constant(UpdateSettings.defaults()));
            scheduler.runPending();
            assertEquals(1, source.calls.get());

            handle.close();
            scheduler.runPending();

            assertEquals(1, source.calls.get(), "a closed handle must stop the repeat");
        }

        @Test
        @DisplayName("a disabled check is skipped per tick, without a restart")
        void settingsAreReReadPerTick() {
            FakeSource source = new FakeSource(release("3.1.0"));
            UpdateService service = serviceFor(source);
            final AtomicBoolean enabled = new AtomicBoolean(false);
            Supplier<UpdateSettings> live = new Supplier<UpdateSettings>() {
                @Override
                public UpdateSettings get() {
                    return settings(true, enabled.get(), 12);
                }
            };

            service.startPeriodicChecks(live);
            scheduler.runPending();
            assertEquals(0, source.calls.get(), "checkEnabled=false must skip the tick");
            assertTrue(logger.logged(LogLevel.DEBUG, "switched off"));

            enabled.set(true);
            scheduler.runPending();
            assertEquals(1, source.calls.get(), "re-enabling must resume without a restart");
        }

        @Test
        @DisplayName("a throwing settings supplier neither fails the start nor cancels the repeat")
        void aThrowingSupplierIsGuarded() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            Supplier<UpdateSettings> exploding = new Supplier<UpdateSettings>() {
                @Override
                public UpdateSettings get() {
                    throw new IllegalStateException("remote config is not loaded");
                }
            };

            // Start-up must survive it: this runs at boot, before remote config has necessarily
            // arrived, and a throw escaping here would take the whole plugin's enable down.
            service.startPeriodicChecks(exploding);
            assertEquals(1, scheduler.captured.size());
            assertEquals(TimeUnit.HOURS.toMillis(12), scheduler.captured.get(0).periodMs,
                    "an unreadable interval falls back to the default cadence");

            scheduler.runPending();

            assertTrue(logger.logged(LogLevel.SEVERE, "the periodic update check failed"));
            assertFalse(scheduler.captured.get(0).cancelled.get(),
                    "a throwing tick must not leave the task cancelled");
        }

        @Test
        @DisplayName("starting twice does not start a second timer")
        void idempotent() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));

            Registration first = service.startPeriodicChecks(constant(UpdateSettings.defaults()));
            Registration second = service.startPeriodicChecks(constant(UpdateSettings.defaults()));

            assertSame(first, second);
            assertEquals(1, scheduler.captured.size());
        }

        @Test
        @DisplayName("a scheduler that is shutting down is not an error")
        void rejectedAtShutdown() {
            UpdateService service = serviceFor(new FakeSource(release("3.1.0")));
            scheduler.shutdown();

            Registration handle = service.startPeriodicChecks(constant(UpdateSettings.defaults()));

            assertSame(Registration.NONE, handle);
            assertTrue(logger.logged(LogLevel.DEBUG, "shutting down"));
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void assertNoBanner() {
        assertFalse(logger.logged(LogLevel.WARN, "A new Heimdall version is available!"),
                "the banner should only appear when there is genuinely something newer");
    }

    private static UpdateSettings settings(boolean notifyAdmins, boolean checkEnabled, long hours) {
        return new UpdateSettings(Payload.builder()
                .put("notifyAdmins", notifyAdmins)
                .put("checkEnabled", checkEnabled)
                .put("checkIntervalHours", hours)
                .build());
    }

    private static Supplier<UpdateSettings> constant(final UpdateSettings value) {
        return new Supplier<UpdateSettings>() {
            @Override
            public UpdateSettings get() {
                return value;
            }
        };
    }

    /** A source that always answers the same way, and counts how often it was asked. */
    private static class FakeSource implements ReleaseSource {

        final AtomicInteger calls = new AtomicInteger();
        private final PluginRelease release;
        private final Throwable failure;

        FakeSource(PluginRelease release) {
            this(release, null);
        }

        private FakeSource(PluginRelease release, Throwable failure) {
            this.release = release;
            this.failure = failure;
        }

        static FakeSource failing(Throwable failure) {
            return new FakeSource(null, failure);
        }

        @Override
        public CompletableFuture<PluginRelease> latestRelease() {
            calls.incrementAndGet();
            if (failure != null) {
                CompletableFuture<PluginRelease> failed = new CompletableFuture<PluginRelease>();
                // Wrapped the way the real client's exceptions arrive, so rootMessage() is exercised
                // against the shape it will actually see.
                failed.completeExceptionally(new ExecutionException(failure));
                return failed;
            }
            return CompletableFuture.completedFuture(release);
        }

        @Override
        public long joinTimeoutMs() {
            return 1000L;
        }
    }

    /** A source whose answer a test can change between checks. */
    private static final class SwitchableSource implements ReleaseSource {

        private volatile PluginRelease release;
        private volatile Throwable failure;

        SwitchableSource(PluginRelease release) {
            this.release = release;
        }

        void failWith(Throwable value) {
            this.failure = value;
        }

        @Override
        public CompletableFuture<PluginRelease> latestRelease() {
            Throwable current = failure;
            if (current != null) {
                CompletableFuture<PluginRelease> failed = new CompletableFuture<PluginRelease>();
                failed.completeExceptionally(current);
                return failed;
            }
            return CompletableFuture.completedFuture(release);
        }

        @Override
        public long joinTimeoutMs() {
            return 1000L;
        }
    }

    /** An installer that records what it was asked to do. */
    private static final class FakeInstaller implements UpdateInstaller {

        final AtomicInteger calls = new AtomicInteger();
        final InstallOutcome outcome =
                InstallOutcome.installed(Paths.get("plugins", "update", "heimdall.jar"),
                        "Installed in place — restart to apply.");

        String installedVersion;
        private IOException failure;

        void failWith(IOException value) {
            this.failure = value;
        }

        @Override
        public InstallOutcome install(PluginRelease release, UpdateDownloader downloader)
                throws IOException {
            calls.incrementAndGet();
            installedVersion = release == null ? null : Versions.normalize(release.version());
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }
    }

    /**
     * A scheduler that hands its tasks to the test instead of running them.
     *
     * <p>Zero core pool, so nothing it does not capture can run either — a
     * {@link ScheduledThreadPoolExecutor} otherwise starts a worker for a periodic task and the
     * update check would tick in the background of every test here.
     */
    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {

        final List<CapturedTask> captured =
                Collections.synchronizedList(new ArrayList<CapturedTask>());

        private final AtomicBoolean shuttingDown = new AtomicBoolean();

        CapturingScheduler() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            if (shuttingDown.get()) {
                throw new java.util.concurrent.RejectedExecutionException("shutting down");
            }
            CapturedTask task = new CapturedTask(
                    command, unit.toMillis(initialDelay), unit.toMillis(period));
            captured.add(task);
            return task;
        }

        @Override
        public void shutdown() {
            shuttingDown.set(true);
        }

        /** Runs every captured task that has not been cancelled, in scheduling order. */
        void runPending() {
            CapturedTask[] due;
            synchronized (captured) {
                due = captured.toArray(new CapturedTask[0]);
            }
            for (CapturedTask task : due) {
                if (!task.cancelled.get()) {
                    task.command.run();
                }
            }
        }
    }

    /** A captured periodic task; cancelling it is what {@code Registration.close()} does. */
    private static final class CapturedTask implements ScheduledFuture<Object> {

        final Runnable command;
        final long initialDelayMs;
        final long periodMs;
        final AtomicBoolean cancelled = new AtomicBoolean();

        CapturedTask(Runnable command, long initialDelayMs, long periodMs) {
            this.command = command;
            this.initialDelayMs = initialDelayMs;
            this.periodMs = periodMs;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(initialDelayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}
