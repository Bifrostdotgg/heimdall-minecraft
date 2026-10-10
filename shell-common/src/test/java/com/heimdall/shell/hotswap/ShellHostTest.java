package com.heimdall.shell.hotswap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.shell.contract.CoreIdentity;
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

        StagedCore beta = host.stageForSwap(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null));
        assertTrue(beta.swappable(), beta.toString());

        StagedCore restartOnly = host.stageForSwap(
                Fixtures.release(data, "contract2", "1.0.0-contract2", 2, null));
        assertFalse(restartOnly.swappable());
        assertTrue(restartOnly.problem().contains("needs a restart"), restartOnly.problem());

        StagedCore same = host.stageForSwap(
                Fixtures.release(data, "alpha", "1.0.0-alpha", ShellContract.VERSION, null));
        assertFalse(same.swappable());
        assertTrue(same.problem().contains("already running"), same.problem());

        StagedCore damaged = host.stageForSwap(Fixtures.release(data, "beta", "1.0.0-beta",
                ShellContract.VERSION, "0000000000000000000000000000000000000000000000000000000000000000"));
        assertFalse(damaged.swappable());
        assertTrue(damaged.problem().contains("damaged"), damaged.problem());
    }

    @Test
    @DisplayName("swapTo runs on the swap thread and reports to the sender that asked")
    void swapToReportsToTheSender() throws Exception {
        bootWith("alpha");
        StagedCore beta = host.stageForSwap(
                Fixtures.release(data, "beta", "1.0.0-beta", ShellContract.VERSION, null));

        assertTrue(host.swapTo(beta, "admin"));

        assertTrue(Fixtures.eventually(() -> "1.0.0-beta".equals(
                host.runningCore() == null ? null : host.runningCore().version())));
        assertTrue(Fixtures.eventually(() -> platform.audience.last().startsWith("admin: §a")),
                platform.audience.sent.toString());
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
