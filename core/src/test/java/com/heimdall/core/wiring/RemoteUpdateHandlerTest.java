package com.heimdall.core.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.RecordingLogger;
import com.heimdall.core.update.HotSwap;
import com.heimdall.core.update.InstallOutcome;
import com.heimdall.core.update.ReleaseSource;
import com.heimdall.core.update.UpdateDownloader;
import com.heimdall.core.update.UpdateInstaller;
import com.heimdall.core.update.UpdateService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dashboard's update button is always answered, even when it cannot do anything (B3).
 *
 * <p>The bot sends a correlated {@code update} frame and waits for {@code update_result}. A client
 * that does not reply leaves the operator's dashboard spinning for the bot's full 120-second timeout,
 * and the path that used to do exactly that was the most common one on Bukkit: a loader that would
 * not reveal the plugin's own jar meant no installer, which meant the whole updater — subscription
 * included — was never wired up.
 *
 * <p>These tests drive the handler directly with a fake {@link UpdateWiring.Replier} and assert the
 * reply is sent on every path. The last of them is the falsification the reviewer asked for:
 * neutering the sole {@code reply(...)} call site makes {@link #missingInstallerStillReplies} fail.
 */
class RemoteUpdateHandlerTest {

    private final RecordingLogger logger = new RecordingLogger(true);
    private ScheduledExecutorService scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** A release source that answers immediately with the given version. */
    private static ReleaseSource sourceFor(final String version) {
        return sourceFor(version, null);
    }

    /** As {@link #sourceFor(String)}, with a published SHA-256. */
    private static ReleaseSource sourceFor(final String version, final String sha256) {
        return new ReleaseSource() {
            @Override
            public CompletableFuture<PluginRelease> latestRelease(boolean fresh) {
                return CompletableFuture.completedFuture(PluginRelease.builder()
                        .version(version)
                        .downloadUrl("https://github.com/x/y/releases/download/" + version + "/p.jar")
                        .sha256(sha256)
                        .build());
            }

            @Override
            public long joinTimeoutMs() {
                return 2_000L;
            }
        };
    }

    private UpdateService service(String latest, UpdateInstaller installer, UpdateDownloader downloader) {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        return new UpdateService(logger, "3.0.0", sourceFor(latest), installer, downloader, scheduler);
    }

    /** Records what the handler replied with, so a test can prove it replied at all. */
    private static final class RecordingReplier implements UpdateWiring.Replier {

        final List<Payload> replies = new ArrayList<Payload>();

        @Override
        public void reply(String id, Payload payload) {
            replies.add(payload);
        }
    }

    private static Envelope updateFrame() {
        return Envelope.fresh("update", Payload.empty());
    }

    @Test
    @DisplayName("a missing installer STILL replies — the Bukkit default path that used to dangle")
    void missingInstallerStillReplies() {
        // No installer, no downloader: exactly the state a Bukkit server with an unusable getFile()
        // is in. Before B3 this had no handler at all; now the frame is answered with a failure.
        UpdateService service = service("3.1.0", null, null);
        RecordingReplier replier = new RecordingReplier();

        new UpdateWiring.RemoteUpdateHandler(logger, service, replier).onMessage(updateFrame());

        assertEquals(1, replier.replies.size(), "the frame MUST be answered, or the bot spins 120s");
        Payload reply = replier.replies.get(0);
        assertFalse(reply.bool("success", true), "it could not install, so success is false");
        assertFalse(reply.string("message", "").isEmpty(), "and it says why");
    }

    /**
     * Drives one dashboard update against a shell that answers the swap request with
     * {@code accept}, and returns the single reply.
     */
    private Payload updateWithShell(final boolean accept, final List<Integer> repliesAtRequest) {
        final RecordingReplier replier = new RecordingReplier();
        HotSwap shell = new HotSwap() {
            @Override
            public Staged stage(java.nio.file.Path releaseJar, String expectedSha256) {
                return new Staged() {
                    @Override
                    public boolean swappable() {
                        return true;
                    }

                    @Override
                    public String problem() {
                        return "";
                    }

                    @Override
                    public String version() {
                        return "3.4.0";
                    }

                    @Override
                    public boolean swap(Object audience) {
                        repliesAtRequest.add(replier.replies.size());
                        return accept;
                    }
                };
            }
        };
        UpdateInstaller installer = new UpdateInstaller() {
            @Override
            public InstallOutcome install(PluginRelease release, UpdateDownloader downloader) {
                return InstallOutcome.installed(java.nio.file.Paths.get("plugins", "update", "h.jar"),
                        "installed 3.4.0 for the next restart");
            }
        };
        scheduler = Executors.newSingleThreadScheduledExecutor();
        UpdateService service = new UpdateService(logger, "3.0.0",
                sourceFor("3.4.0", "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"),
                installer,
                new UpdateDownloader(logger, com.heimdall.core.update.DownloadPolicy.github()),
                scheduler, shell);

        new UpdateWiring.RemoteUpdateHandler(logger, service, replier).onMessage(updateFrame());

        assertEquals(1, replier.replies.size(), "exactly one answer");
        return replier.replies.get(0);
    }

    @Test
    @DisplayName("a live swap is requested before the reply, which says it was accepted (D87)")
    void acceptedSwapIsReported() {
        List<Integer> repliesAtRequest = new ArrayList<Integer>();

        Payload reply = updateWithShell(true, repliesAtRequest);

        // Requested first so the reply can be truthful; the shell holds the swap back long enough
        // for the reply to go out on the tunnel it closes (ShellContract.SWAP_SETTLE_MS).
        assertEquals(java.util.Collections.singletonList(0), repliesAtRequest,
                "the swap is requested once, before the reply");
        assertTrue(reply.bool("success", false));
        assertTrue(reply.string("message", "").contains("Swapping it in now"), reply.toString());
    }

    @Test
    @DisplayName("a swap the shell will not start is reported as restart-only, and logged")
    void refusedSwapIsReported() {
        Payload reply = updateWithShell(false, new ArrayList<Integer>());

        String message = reply.string("message", "");
        assertTrue(reply.bool("success", false), "the install for the next restart still worked");
        assertFalse(message.contains("Swapping it in now"), message);
        assertTrue(message.contains("could not start"), message);
        assertTrue(logger.logged(com.heimdall.core.log.LogLevel.WARN, "live swap could not start"),
                logger.records().toString());
    }

    @Test
    @DisplayName("a successful install replies success=true with the version")
    void successReplies() {
        UpdateInstaller installer = new UpdateInstaller() {
            @Override
            public InstallOutcome install(PluginRelease release, UpdateDownloader downloader) {
                return InstallOutcome.installed(null, "installed 3.2.0 — restart to apply");
            }
        };
        UpdateService service = service("3.2.0", installer,
                new UpdateDownloader(logger, com.heimdall.core.update.DownloadPolicy.github()));
        RecordingReplier replier = new RecordingReplier();

        new UpdateWiring.RemoteUpdateHandler(logger, service, replier).onMessage(updateFrame());

        assertEquals(1, replier.replies.size());
        assertTrue(replier.replies.get(0).bool("success", false));
        assertEquals("3.2.0", replier.replies.get(0).string("version", ""));
    }

    @Test
    @DisplayName("an installer that throws is contained, and the frame is still answered")
    void throwingInstallerStillReplies() {
        UpdateInstaller installer = new UpdateInstaller() {
            @Override
            public InstallOutcome install(PluginRelease release, UpdateDownloader downloader) {
                throw new RuntimeException("disk full");
            }
        };
        UpdateService service = service("3.3.0", installer,
                new UpdateDownloader(logger, com.heimdall.core.update.DownloadPolicy.github()));
        RecordingReplier replier = new RecordingReplier();

        new UpdateWiring.RemoteUpdateHandler(logger, service, replier).onMessage(updateFrame());

        assertEquals(1, replier.replies.size());
        assertFalse(replier.replies.get(0).bool("success", true));
    }

    @Test
    @DisplayName("a reply that throws does not escape the handler")
    void replyFailureIsContained() {
        UpdateService service = service("3.1.0", null, null);
        final AtomicReference<Boolean> attempted = new AtomicReference<Boolean>(false);
        UpdateWiring.Replier failing = new UpdateWiring.Replier() {
            @Override
            public void reply(String id, Payload payload) {
                attempted.set(true);
                throw new IllegalStateException("tunnel dropped");
            }
        };

        // Must not throw: a dropped tunnel between receiving the frame and answering it is the bot's
        // problem to time out, not an exception that unwinds the IO pool's task.
        new UpdateWiring.RemoteUpdateHandler(logger, service, failing).onMessage(updateFrame());

        assertTrue(attempted.get(), "it still tried to reply");
    }
}
