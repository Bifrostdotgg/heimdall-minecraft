package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.CoreIdentity;
import com.heimdall.shell.contract.LoginGate;
import com.heimdall.shell.contract.ShellContract;
import com.heimdall.shell.contract.StagedCore;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shell's lifecycle against real core jars in real child classloaders (departure D87).
 *
 * <p>Every core here is a fixture jar the build produced, loaded through {@link CoreLoader} exactly
 * as a production core is, with its classes off the test classpath. So "the old core stopped", "the
 * new core got the handoff" and "the sweep removed what the old core left behind" are statements
 * about two genuinely separate classloaders, not about two objects of one class.
 */
class ShellHostTest {

    /**
     * Not cleaned up by JUnit: on Windows a jar a live classloader holds cannot be deleted, and the
     * shell deliberately leaves the running core's loader open at shutdown. {@link #shutDown()}
     * closes what it can and deletes best effort.
     */
    @TempDir(cleanup = CleanupMode.NEVER)
    Path data;

    private FixturePlatform platform;
    private ShellHost host;

    private ShellHost bootWith(String variant) throws Exception {
        platform = new FixturePlatform(data);
        platform.shellJar = Fixtures.release(data, variant, "1.0.0-" + variant,
                ShellContract.VERSION, null).toFile();
        host = new ShellHost(platform, "shell-test");
        host.loaderGraceMs = 50L;
        host.boot();
        return host;
    }

    private CoreArchive.CoreJar jar(String variant) throws Exception {
        return host.stage(Fixtures.copyOf(variant, data));
    }

    /** Stages a release the way the updater does: with the hash its download was verified against. */
    private StagedCore staged(Path release) throws Exception {
        return host.stageForSwap(release, Sha256.of(release));
    }

    /** Records each progress line with the login state at the moment it was said. */
    private static final class StateProbe implements SwapListener {

        final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final AtomicReference<SwapOutcome> done = new AtomicReference<SwapOutcome>();
        private final LoginGateHolder gates;

        StateProbe(LoginGateHolder gates) {
            this.gates = gates;
        }

        @Override
        public void progress(String line) {
            lines.add(gates.state() + " gate=" + (gates.current() != null) + " " + line);
        }

        @Override
        public void finished(SwapOutcome outcome) {
            done.set(outcome);
        }
    }

    @AfterEach
    void shutDown() throws Exception {
        if (host != null) {
            ShellHost.Generation last = host.currentGeneration();
            host.shutdown();
            if (last != null) {
                last.loaded.closeLoader(platform.log);
            }
        }
        // Retired loaders close after the (shortened) grace period; give them the chance.
        Thread.sleep(150L);
        deleteQuietly(data);
    }

    private static void deleteQuietly(Path root) {
        try {
            java.nio.file.Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        } catch (java.io.IOException ignored) {
            // Best effort: a temp directory left behind on Windows is not a test failure.
        }
    }

    @Test
    @DisplayName("boot extracts the nested core, checks it, loads it in its own loader and starts it")
    void bootsTheEmbeddedCore() throws Exception {
        bootWith("alpha");

        CoreIdentity running = host.runningCore();
        assertNotNull(running);
        assertEquals("1.0.0-alpha", running.version());
        assertTrue(Sha256.isWellFormed(running.sha256()));
        assertEquals(1, platform.journal().size());
        assertEquals("start 1.0.0-alpha count=0", platform.journal().get(0));
        assertTrue(Files.list(host.coreDirectory()).anyMatch(
                p -> p.getFileName().toString().startsWith("heimdall-core-1.0.0-alpha-")));
        assertEquals("1.0.0-alpha", host.tunnel().version(), "the tunnel forwards to the core");
        assertSame(LoginGateHolder.State.RUNNING, host.loginGate().state());
        assertNotNull(host.loginGate().current());
        assertNotNull(platform.published, "the public tunnel is published once, at boot");
        assertTrue(host.currentGeneration().loaded.loader() instanceof URLClassLoader);
        assertTrue(host.currentGeneration().loaded.loader() != ShellHostTest.class.getClassLoader());
    }

    @Test
    @DisplayName("a swap stops the old core with isSwapping set, and the new one gets its handoff")
    void swapHandsOver() throws Exception {
        bootWith("alpha");
        LoadedCore old = host.currentGeneration().loaded;

        SwapOutcome outcome = host.swap(jar("beta"), SwapListener.NONE);

        assertTrue(outcome.succeeded(), outcome.toString());
        assertEquals("1.0.0-beta", host.runningCore().version());
        List<String> journal = platform.journal();
        int stopped = journal.indexOf("stop 1.0.0-alpha swapping=true");
        int started = journal.indexOf("start 1.0.0-beta count=1");
        assertTrue(stopped > 0, "the old core did not stop for a swap: " + journal);
        assertTrue(started > stopped, "the handoff did not cross the swap: " + journal);
        assertEquals("1.0.0-beta", host.tunnel().version());
        assertFalse(host.isSwapping());
        assertSame(LoginGateHolder.State.RUNNING, host.loginGate().state());
        assertTrue(old.loader() != host.currentGeneration().loaded.loader());
    }

    @Test
    @DisplayName("a command never disappears across a swap: same relay, rebound, never unregistered")
    void commandsSurviveASwap() throws Exception {
        bootWith("alpha");
        Relay before = host.relays().find("fixture");
        assertNotNull(before);

        host.swap(jar("beta"), SwapListener.NONE);

        assertSame(before, host.relays().find("fixture"));
        assertTrue(platform.commands.unregistered.isEmpty(),
                "the platform command was taken away and put back: " + platform.commands.unregistered);
        assertEquals(1, platform.commands.registered.size());
        before.execute("console", "fixture", new String[0]);
        assertTrue(platform.journal().contains("command 1.0.0-beta fixture"), platform.journal().toString());
    }

    @Test
    @DisplayName("the retired core's classloader closes after the grace period, not before")
    void oldLoaderClosesLater() throws Exception {
        bootWith("alpha");
        final URLClassLoader old = (URLClassLoader) host.currentGeneration().loaded.loader();
        assertNotNull(old.findResource("META-INF/heimdall-fixture.properties"));

        host.swap(jar("beta"), SwapListener.NONE);

        assertTrue(Fixtures.eventually(
                () -> old.findResource("META-INF/heimdall-fixture.properties") == null),
                "the old loader was never closed");
    }

    @Test
    @DisplayName("a core that fails to start is unwound and the previous core runs again, freshly loaded")
    void failedStartRollsBack() throws Exception {
        bootWith("alpha");
        ClassLoader original = host.currentGeneration().loaded.loader();

        SwapOutcome outcome = host.swap(jar("failing"), SwapListener.NONE);

        assertSame(SwapOutcome.Kind.ROLLED_BACK, outcome.kind(), outcome.toString());
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertTrue(host.currentGeneration().loaded.loader() != original,
                "a rollback must not restart the old instance in its torn-down loader");
        List<String> journal = platform.journal();
        assertTrue(journal.contains("start 1.0.0-failing count=1"), journal.toString());
        assertTrue(journal.contains("stop 1.0.0-failing swapping=true"), journal.toString());
        assertEquals("start 1.0.0-alpha count=1", journal.get(journal.size() - 1),
                "the rollback gets the same handoff the failed core was offered: " + journal);
        assertNotNull(host.relays().find("fixture").bound(), "the command is bound again");
        assertSame(LoginGateHolder.State.RUNNING, host.loginGate().state());
        assertTrue(host.lastProblem().contains("1.0.0-failing"), host.lastProblem());
    }

    @Test
    @DisplayName("when the rollback fails too, logins are refused and admins are told")
    void failedRollbackLeavesNoCore() throws Exception {
        bootWith("fragile");

        SwapOutcome outcome = host.swap(jar("failing"), SwapListener.NONE);

        assertSame(SwapOutcome.Kind.NO_CORE, outcome.kind(), outcome.toString());
        assertNull(host.runningCore());
        assertSame(LoginGateHolder.State.DOWN, host.loginGate().state());
        LoginGateHolder.Decision login = host.loginGate().await(1_000L);
        assertNull(login.gate());
        assertEquals(ShellMessages.LOGIN_NO_CORE, login.refusal());
        assertEquals(1, platform.audience.alerts.size(), "admins online must be told");
        assertTrue(platform.log.errors().get(platform.log.errors().size() - 1)
                .contains("logins are refused"), platform.log.lines().toString());
        assertFalse(host.tunnel().isConnected());

        Relay relay = host.relays().find("fixture");
        if (relay != null) {
            relay.execute("player", "fixture", new String[0]);
            assertTrue(platform.audience.last().contains("not running"), platform.audience.last());
        }
    }

    @Test
    @DisplayName("a core that cannot start at boot leaves no core and logins refused")
    void bootFailureIsFailClosed() throws Exception {
        bootWith("failing");

        assertNull(host.runningCore());
        assertSame(LoginGateHolder.State.DOWN, host.loginGate().state());
        assertTrue(platform.journal().contains("stop 1.0.0-failing swapping=false"),
                "a failed start must be unwound: " + platform.journal());
        assertNull(host.relays().find("fixture"), "nothing the failed core bound may survive it");
    }

    @Test
    @DisplayName("the sweep removes what a core left behind its back, and the shell closes its leftovers")
    void sweepAfterALeakyCore() throws Exception {
        bootWith("leaky");
        assertEquals(1, platform.registry.size());

        SwapOutcome outcome = host.swap(jar("alpha"), SwapListener.NONE);

        assertTrue(outcome.succeeded(), outcome.toString());
        assertTrue(platform.registry.isEmpty(),
                "the leaked registration still points into the old core: " + platform.registry);
        assertTrue(platform.journal().contains("untrack 1.0.0-leaky"),
                "the shell must close what the core forgot: " + platform.journal());
        assertTrue(platform.log.contains("left 1 registration(s) behind"), platform.log.lines().toString());
        assertTrue(platform.log.contains("still pointed at core"), platform.log.lines().toString());
    }

    @Test
    @DisplayName("a core built for another contract is refused before anything is touched")
    void contractMismatchIsRefused() throws Exception {
        bootWith("alpha");

        SwapOutcome outcome = host.swap(jar("contract2"), SwapListener.NONE);

        assertSame(SwapOutcome.Kind.REFUSED, outcome.kind());
        assertTrue(outcome.message().contains("contract 2"), outcome.message());
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertEquals(1, platform.journal().size(), "the running core was touched");
    }

    @Test
    @DisplayName("a core whose code and manifest disagree about the contract is refused")
    void selfContradictingCoreIsRefused() throws Exception {
        bootWith("alpha");

        SwapOutcome outcome = host.swap(jar("contradicts"), SwapListener.NONE);

        assertSame(SwapOutcome.Kind.REFUSED, outcome.kind());
        assertTrue(outcome.message().contains("disagrees with itself"), outcome.message());
        assertEquals("1.0.0-alpha", host.runningCore().version());
    }

    @Test
    @DisplayName("staging tells a live swap from a restart-only release, and refuses a damaged one")
    void stagingDecidesLiveOrRestart() throws Exception {
        bootWith("alpha");

        StagedCore beta = staged(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null));
        assertTrue(beta.swappable(), beta.toString());

        StagedCore restartOnly = staged(
                Fixtures.release(data, "contract2", "1.0.0-contract2", 2, null));
        assertFalse(restartOnly.swappable());
        assertTrue(restartOnly.problem().contains("needs a restart"), restartOnly.problem());

        StagedCore same = staged(
                Fixtures.release(data, "alpha", "1.0.0-alpha", ShellContract.VERSION, null));
        assertFalse(same.swappable());
        assertTrue(same.problem().contains("already running"), same.problem());

        StagedCore damaged = staged(Fixtures.release(data, "beta", "1.0.0-beta",
                ShellContract.VERSION, "0000000000000000000000000000000000000000000000000000000000000000"));
        assertFalse(damaged.swappable());
        assertTrue(damaged.problem().contains("damaged"), damaged.problem());
    }

    @Test
    @DisplayName("staging re-hashes the release itself: no checksum, or a different file, is refused")
    void stagingRechecksTheDownload() throws Exception {
        bootWith("alpha");
        Path release = Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null);

        StagedCore unverified = host.stageForSwap(release, null);
        assertFalse(unverified.swappable());
        assertTrue(unverified.problem().contains("no verified checksum"), unverified.problem());

        StagedCore malformed = host.stageForSwap(release, "abc");
        assertFalse(malformed.swappable());
        assertTrue(malformed.problem().contains("no verified checksum"), malformed.problem());

        StagedCore replaced = host.stageForSwap(release,
                "1111111111111111111111111111111111111111111111111111111111111111");
        assertFalse(replaced.swappable());
        assertTrue(replaced.problem().contains("no longer matches"), replaced.problem());

        assertTrue(staged(release).swappable(), "the same file with its own hash is fine");
    }

    @Test
    @DisplayName("a core jar changed on disk after it was checked is refused before it is loaded")
    void coreChangedAfterTheCheckIsRefused() throws Exception {
        bootWith("alpha");
        CoreArchive.CoreJar beta = jar("beta");
        Files.copy(Fixtures.core("leaky"), beta.path(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        SwapOutcome outcome = host.swap(beta, SwapListener.NONE);

        assertSame(SwapOutcome.Kind.REFUSED, outcome.kind(), outcome.toString());
        assertTrue(outcome.message().contains("changed on disk"), outcome.message());
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertEquals(1, platform.journal().size(), "the running core was touched");
    }

    @Test
    @DisplayName("swapTo runs on the swap thread, after the settle delay, and reports to the sender")
    void swapToReportsToTheSender() throws Exception {
        bootWith("alpha");
        host.settleMs = 300L;
        StagedCore beta = staged(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null));

        assertTrue(host.swapTo(beta, "admin"));
        Thread.sleep(100L);
        assertFalse(platform.journal().contains("stop 1.0.0-alpha swapping=true"),
                "the swap must wait out the settle delay so the caller's reply goes first");

        assertTrue(Fixtures.eventually(() -> "1.0.0-beta".equals(
                host.runningCore() == null ? null : host.runningCore().version())));
        assertTrue(Fixtures.eventually(() -> platform.audience.last().startsWith("admin: §a")),
                platform.audience.sent.toString());
    }

    @Test
    @DisplayName("a swap clears the gate and waits for a login holding the old core's gate")
    void swapDrainsLoginsInFlight() throws Exception {
        bootWith("alpha");
        final LoginGateHolder gates = host.loginGate();
        // Handed the old core's gate, not yet deciding: the window the lease exists to close.
        LoginGateHolder.Decision held = gates.lease();
        assertNotNull(held);
        StateProbe probe = new StateProbe(gates);

        assertTrue(host.requestSwap(jar("beta"), probe));
        Thread.sleep(200L);

        assertFalse(platform.journal().contains("stop 1.0.0-alpha swapping=true"),
                "the old core stopped under a login that held its gate");
        assertNull(gates.current(), "new logins must not reach the outgoing core");
        assertSame(LoginGateHolder.State.SWAPPING, gates.state());
        final AtomicReference<LoginGateHolder.Decision> next =
                new AtomicReference<LoginGateHolder.Decision>();
        Thread arriving = new Thread(() -> next.set(gates.await(5_000L)));
        arriving.start();

        assertTrue(gates.decide(held, "steve"), "decided while the old core still ran: it stands");

        assertTrue(Fixtures.eventually(() -> probe.done.get() != null));
        assertTrue(probe.done.get().succeeded(), probe.done.get().toString());
        List<String> journal = platform.journal();
        assertTrue(journal.contains("gate 1.0.0-alpha steve"), journal.toString());
        assertTrue(journal.indexOf("gate 1.0.0-alpha steve")
                        < journal.indexOf("stop 1.0.0-alpha swapping=true"),
                "the old core must decide before it stops: " + journal);
        arriving.join(2_000L);
        assertNotNull(next.get().gate(), "a login arriving mid-swap is held for the next core");
        assertSame(gates.current(), next.get().gate());
    }

    @Test
    @DisplayName("a login still out when the drain gives up is refused, and never reaches the old core")
    void loginOutlivingTheDrainIsRefused() throws Exception {
        bootWith("alpha");
        host.gateDrainMs = 100L;
        LoginGateHolder.Decision held = host.loginGate().lease();

        SwapOutcome outcome = host.swap(jar("beta"), SwapListener.NONE);

        assertTrue(outcome.succeeded(), outcome.toString());
        assertTrue(platform.log.contains("it will be refused"), platform.log.lines().toString());
        assertFalse(host.loginGate().decide(held, "steve"),
                "a decision through a stopped core must not stand");
        assertFalse(platform.journal().contains("gate 1.0.0-alpha steve"),
                "the stopped core was asked: " + platform.journal());
    }

    @Test
    @DisplayName("shutdown voids a login still holding the running core's gate")
    void shutdownVoidsLeases() throws Exception {
        ShellHost booted = bootWith("alpha");
        ShellHost.Generation last = booted.currentGeneration();
        LoginGateHolder.Decision held = booted.loginGate().lease();

        booted.shutdown();
        host = null;
        last.loaded.closeLoader(platform.log);

        assertFalse(booted.loginGate().decide(held, "steve"));
        assertFalse(platform.journal().contains("gate 1.0.0-alpha steve"), platform.journal().toString());
    }

    @Test
    @DisplayName("shutdown waits a bounded time for a hung swap, then stops without it")
    void shutdownDoesNotWaitForeverOnASwap() throws Exception {
        bootWith("alpha");
        host.shutdownWaitMs = 200L;
        final CountDownLatch hung = new CountDownLatch(1);
        final CountDownLatch inSwap = new CountDownLatch(1);
        // Holds the swap (and its transition lock) inside the first progress line, ignoring the
        // interrupt shutdown sends: a core whose start or stop hangs.
        SwapListener stuck = new SwapListener() {
            @Override
            public void progress(String line) {
                inSwap.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (hung.getCount() > 0 && System.nanoTime() < deadline) {
                    try {
                        hung.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Deliberately ignored, like a core that does not answer interrupts.
                    }
                }
            }

            @Override
            public void finished(SwapOutcome outcome) {
            }
        };
        assertTrue(host.requestSwap(jar("beta"), stuck));
        assertTrue(inSwap.await(2, TimeUnit.SECONDS));
        final ShellHost stopping = host;
        Thread shutdown = new Thread(stopping::shutdown);

        shutdown.start();
        shutdown.join(5_000L);

        boolean finished = !shutdown.isAlive();
        hung.countDown();
        shutdown.join(5_000L);
        assertTrue(finished, "server shutdown waited on a hung swap");
        assertTrue(platform.log.errors().stream().anyMatch(e -> e.contains("did not stop")),
                platform.log.lines().toString());
        assertTrue(Fixtures.eventually(() -> !stopping.isSwapping()));
    }

    @Test
    @DisplayName("a progress listener that throws cannot abort a swap halfway")
    void throwingListenerCannotAbortASwap() throws Exception {
        bootWith("alpha");
        SwapListener broken = new SwapListener() {
            @Override
            public void progress(String line) {
                throw new IllegalStateException("the sender went away");
            }

            @Override
            public void finished(SwapOutcome outcome) {
            }
        };

        SwapOutcome outcome = host.swap(jar("beta"), broken);

        assertTrue(outcome.succeeded(), outcome.toString());
        assertEquals("1.0.0-beta", host.runningCore().version());
        assertSame(LoginGateHolder.State.RUNNING, host.loginGate().state());
        assertNotNull(host.loginGate().current());
        assertFalse(host.isSwapping());
    }

    @Test
    @DisplayName("a swap cancelled by shutdown during its settle delay is not logged as a failure")
    void cancelledSettleIsQuiet() throws Exception {
        ShellHost booted = bootWith("alpha");
        ShellHost.Generation last = booted.currentGeneration();
        booted.settleMs = 5_000L;
        StagedCore beta = staged(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null));
        assertTrue(booted.swapTo(beta, "admin"));
        Thread.sleep(100L);

        booted.shutdown();
        host = null;
        last.loaded.closeLoader(platform.log);

        assertTrue(Fixtures.eventually(() -> platform.log.contains("cancelled")),
                platform.log.lines().toString());
        assertFalse(platform.log.errors().stream().anyMatch(e -> e.contains("unexpectedly")),
                platform.log.errors().toString());
        assertFalse(platform.journal().contains("start 1.0.0-beta count=1"), "the swap still ran");
    }

    @Test
    @DisplayName("from no core, a swap holds logins through the start instead of refusing them")
    void recoveryHoldsLogins() throws Exception {
        bootWith("failing");
        assertSame(LoginGateHolder.State.DOWN, host.loginGate().state());
        StateProbe probe = new StateProbe(host.loginGate());

        SwapOutcome outcome = host.swap(jar("beta"), probe);

        assertTrue(outcome.succeeded(), outcome.toString());
        assertTrue(probe.lines.get(0).startsWith("SWAPPING gate=false Swapping no core"),
                probe.lines.toString());
    }

    @Test
    @DisplayName("an outgoing core's gate is gone and logins hold by the time it has stopped")
    void stoppedCoreHasNoGate() throws Exception {
        bootWith("alpha");
        StateProbe probe = new StateProbe(host.loginGate());

        assertTrue(host.swap(jar("beta"), probe).succeeded());

        assertTrue(probe.lines.toString().contains("SWAPPING gate=false Stopped core"),
                probe.lines.toString());
    }

    @Test
    @DisplayName("an enable that throws after the gate is registered leaves logins refused")
    void enableFailsClosed() throws Exception {
        platform = new FixturePlatform(data);
        platform.shellJar = Fixtures.release(data, "alpha", "1.0.0-alpha",
                ShellContract.VERSION, null).toFile();
        LoginGateHolder gates = new LoginGateHolder(platform.log, 5_000L);

        host = ShellHost.enable(platform, "shell-test", gates, target -> {
            throw new IllegalStateException("relay install failed");
        });

        assertNotNull(host);
        assertNull(host.runningCore(), "nothing may boot after a failed preparation");
        assertSame(LoginGateHolder.State.DOWN, gates.state());
        assertEquals(ShellMessages.LOGIN_NO_CORE, gates.await(1_000L).refusal());
        assertTrue(platform.log.errors().get(platform.log.errors().size() - 1)
                .contains("logins are refused"), platform.log.lines().toString());
    }

    @Test
    @DisplayName("an enable that succeeds runs the preparation, then boots")
    void enableBoots() throws Exception {
        platform = new FixturePlatform(data);
        platform.shellJar = Fixtures.release(data, "alpha", "1.0.0-alpha",
                ShellContract.VERSION, null).toFile();
        LoginGateHolder gates = new LoginGateHolder(platform.log, 5_000L);
        final AtomicReference<CoreIdentity> runningAtPrepare = new AtomicReference<CoreIdentity>();

        host = ShellHost.enable(platform, "shell-test", gates,
                target -> runningAtPrepare.set(target.runningCore()));

        assertNull(runningAtPrepare.get(), "the preparation runs before any core");
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertSame(gates, host.loginGate());
        assertSame(LoginGateHolder.State.RUNNING, gates.state());
    }

    @Test
    @DisplayName("the shell's own swap verb is refused without heimdall.admin, and nothing starts")
    void shellSwapNeedsThePermission() throws Exception {
        bootWith("alpha");
        Files.copy(Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null),
                host.stagedPath());
        ShellAdmin admin = new ShellAdmin(host, platform.audience);
        platform.audience.grantAll = false;

        assertTrue(admin.handle("player", "hd", new String[] {"swap"}, true));

        assertTrue(platform.audience.last().contains("do not have permission"),
                platform.audience.sent.toString());
        Thread.sleep(200L);
        assertFalse(host.isSwapping());
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertEquals(1, platform.journal().size(), "a swap ran for a sender without permission");

        platform.audience.grantAll = true;
        assertTrue(admin.handle("admin", "hd", new String[] {"swap"}, true));
        assertTrue(Fixtures.eventually(() -> "1.0.0-beta".equals(
                host.runningCore() == null ? null : host.runningCore().version())),
                "the same staged release swaps for an admin: " + platform.audience.sent);
    }

    @Test
    @DisplayName("/hd swap stages a release dropped at core/staged.jar on the swap thread and swaps to it")
    void swapFromAStagedReleaseFile() throws Exception {
        bootWith("alpha");
        java.nio.file.Files.copy(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null),
                host.stagedPath());
        final AtomicReference<SwapOutcome> done = new AtomicReference<SwapOutcome>();

        assertTrue(host.requestSwapFromFile(host.stagedPath(), new SwapListener() {
            @Override
            public void progress(String line) {
            }

            @Override
            public void finished(SwapOutcome outcome) {
                done.set(outcome);
            }
        }));

        assertTrue(Fixtures.eventually(() -> done.get() != null));
        assertTrue(done.get().succeeded(), done.get().toString());
        assertEquals("1.0.0-beta", host.runningCore().version());
    }

    @Test
    @DisplayName("a staged file that is not a core is refused, and the running core is untouched")
    void unusableStagedFileIsRefused() throws Exception {
        bootWith("alpha");
        java.nio.file.Files.write(host.stagedPath(), "not a jar".getBytes("UTF-8"));
        final AtomicReference<SwapOutcome> done = new AtomicReference<SwapOutcome>();

        assertTrue(host.requestSwapFromFile(host.stagedPath(), new SwapListener() {
            @Override
            public void progress(String line) {
            }

            @Override
            public void finished(SwapOutcome outcome) {
                done.set(outcome);
            }
        }));

        assertTrue(Fixtures.eventually(() -> done.get() != null));
        assertSame(SwapOutcome.Kind.REFUSED, done.get().kind());
        assertTrue(done.get().message().contains("cannot be used"), done.get().message());
        assertEquals("1.0.0-alpha", host.runningCore().version());
        assertEquals(1, platform.journal().size());
    }

    @Test
    @DisplayName("a second swap request while one is running is refused")
    void oneSwapAtATime() throws Exception {
        bootWith("alpha");
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<SwapOutcome> first = new AtomicReference<SwapOutcome>();
        SwapListener slow = new SwapListener() {
            @Override
            public void progress(String line) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void finished(SwapOutcome outcome) {
                first.set(outcome);
            }
        };

        assertTrue(host.requestSwap(jar("beta"), slow));
        assertFalse(host.requestSwap(jar("leaky"), SwapListener.NONE));
        release.countDown();

        assertTrue(Fixtures.eventually(() -> first.get() != null));
        assertTrue(first.get().succeeded(), first.get().toString());
    }

    @Test
    @DisplayName("shutdown stops the core without a swap, and withdraws the public tunnel")
    void shutdownIsNotASwap() throws Exception {
        ShellHost booted = bootWith("alpha");
        ShellHost.Generation last = booted.currentGeneration();
        CoreArchive.CoreJar beta = jar("beta");

        booted.shutdown();
        host = null;
        last.loaded.closeLoader(platform.log);

        assertTrue(platform.journal().contains("stop 1.0.0-alpha swapping=false"));
        assertNull(platform.published, "the public tunnel must be withdrawn at shutdown");
        assertSame(LoginGateHolder.State.DOWN, booted.loginGate().state());
        assertFalse(booted.requestSwap(beta, SwapListener.NONE),
                "no swap may start once the shell is stopping");
    }
}
