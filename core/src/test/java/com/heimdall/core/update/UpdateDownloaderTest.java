package com.heimdall.core.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.log.RecordingLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The download hardening, which v2 shipped and never proved.
 *
 * <p>Two halves. {@code ProductionPolicy} needs no server at all — the host and scheme refusals
 * happen before a socket is opened, which is the point of them — and exists so that a loosened test
 * policy cannot quietly become the shipped default. {@code Transfers} runs against a loopback
 * server, because the properties that actually protect a server are all about what is left on disk
 * when a transfer does <em>not</em> complete, and there is no way to observe that without failing a
 * real one.
 *
 * <p>{@code RecordingHttpServer} in {@code com.heimdall.core.http} is not reused: it is
 * package-private there, and it only speaks JSON strings, where every interesting case here is
 * about raw bytes and about a body that is deliberately larger than the reader will accept.
 */
class UpdateDownloaderTest {

    private static final byte[] JAR_BYTES = "PK pretend this is a plugin jar"
            .getBytes(StandardCharsets.UTF_8);

    private final RecordingLogger logger = new RecordingLogger(true);

    @Nested
    @DisplayName("the production policy")
    class ProductionPolicy {

        private final UpdateDownloader downloader =
                new UpdateDownloader(logger, DownloadPolicy.github());

        @Test
        @DisplayName("refuses a host that is not GitHub, naming it")
        void refusesLoopback(@TempDir Path dir) {
            File target = dir.resolve("plugin.jar").toFile();

            IOException refused = assertThrows(IOException.class,
                    () -> downloader.download("https://127.0.0.1:8080/heimdall.jar", target));

            assertTrue(refused.getMessage().contains("127.0.0.1"),
                    "the refusal must name the host: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("untrusted host"), refused.getMessage());
            assertFalse(target.exists());
        }

        @Test
        @DisplayName("refuses plain HTTP even for an allowed host")
        void refusesPlainHttp(@TempDir Path dir) {
            File target = dir.resolve("plugin.jar").toFile();

            IOException refused = assertThrows(IOException.class,
                    () -> downloader.download("http://github.com/x/y/releases/heimdall.jar", target));

            assertTrue(refused.getMessage().contains("insecure protocol"), refused.getMessage());
            assertTrue(refused.getMessage().contains("http"), refused.getMessage());
            assertFalse(target.exists());
        }

        @Test
        @DisplayName("an allowlist entry is not a suffix match")
        void allowlistIsNotASuffixMatch() {
            DownloadPolicy policy = DownloadPolicy.github();
            assertTrue(policy.allowsHost("github.com"));
            assertTrue(policy.allowsHost("objects.githubusercontent.com"));
            assertTrue(policy.allowsHost("GITHUB.COM"));
            assertFalse(policy.allowsHost("evilgithub.com"));
            assertFalse(policy.allowsHost("github.com.attacker.example"));
            assertFalse(policy.allowsHost(null));
        }

        @Test
        @DisplayName("keeps v2's ceiling and timeouts")
        void keepsV2Numbers() {
            DownloadPolicy policy = DownloadPolicy.github();
            assertEquals(50L * 1024 * 1024, policy.maxBytes());
            assertEquals(10_000, policy.connectTimeoutMs());
            assertEquals(60_000, policy.readTimeoutMs());
            assertTrue(policy.requireHttps());
        }

        @Test
        @DisplayName("a missing download URL is refused before anything is touched")
        void refusesAMissingUrl(@TempDir Path dir) {
            File target = dir.resolve("plugin.jar").toFile();
            assertThrows(IOException.class, () -> downloader.download(null, target));
            assertThrows(IOException.class, () -> downloader.download("   ", target));
            assertFalse(target.exists());
        }
    }

    @Nested
    @DisplayName("transfers")
    class Transfers {

        private LoopbackServer server;
        private UpdateDownloader downloader;

        @BeforeEach
        void start() {
            server = new LoopbackServer();
            downloader = new UpdateDownloader(logger, loopbackPolicy(DownloadPolicy.MAX_DOWNLOAD_BYTES));
        }

        @AfterEach
        void stop() {
            server.close();
        }

        @Test
        @DisplayName("writes the bytes, returns the count, and leaves no .part behind")
        void happyPath(@TempDir Path dir) throws IOException {
            server.serve(200, JAR_BYTES);
            File target = dir.resolve("nested").resolve("plugin.jar").toFile();

            long written = downloader.download(server.url("/heimdall.jar"), target);

            assertEquals(JAR_BYTES.length, written);
            assertArrayEquals(JAR_BYTES, Files.readAllBytes(target.toPath()));
            assertNoPartFile(target);
        }

        @Test
        @DisplayName("replaces an existing target")
        void replacesAnExistingTarget(@TempDir Path dir) throws IOException {
            server.serve(200, JAR_BYTES);
            File target = dir.resolve("plugin.jar").toFile();
            Files.write(target.toPath(), "the old jar".getBytes(StandardCharsets.UTF_8));

            long written = downloader.download(server.url("/heimdall.jar"), target);

            assertEquals(JAR_BYTES.length, written);
            assertArrayEquals(JAR_BYTES, Files.readAllBytes(target.toPath()));
            assertNoPartFile(target);
        }

        @Test
        @DisplayName("the size cap aborts mid-stream, leaving no .part and no target")
        void sizeCapAbortsMidStream(@TempDir Path dir) {
            // Far bigger than the cap and bigger than the 8 KiB read buffer, so the abort has to
            // happen while the body is still arriving rather than at the end of it.
            server.serve(200, new byte[512 * 1024]);
            downloader = new UpdateDownloader(logger, loopbackPolicy(4096L));
            File target = dir.resolve("plugin.jar").toFile();

            IOException tooBig = assertThrows(IOException.class,
                    () -> downloader.download(server.url("/heimdall.jar"), target));

            assertTrue(tooBig.getMessage().contains("maximum allowed size"), tooBig.getMessage());
            assertFalse(target.exists(), "a refused download must not leave a target file");
            assertNoPartFile(target);
        }

        @Test
        @DisplayName("a 404 is an IOException naming the status, with no .part")
        void notFound(@TempDir Path dir) {
            server.serve(404, "no such release".getBytes(StandardCharsets.UTF_8));
            File target = dir.resolve("plugin.jar").toFile();

            IOException failed = assertThrows(IOException.class,
                    () -> downloader.download(server.url("/heimdall.jar"), target));

            assertTrue(failed.getMessage().contains("404"), failed.getMessage());
            assertFalse(target.exists());
            assertNoPartFile(target);
        }

        @Test
        @DisplayName("a failed download leaves an existing target untouched")
        void failureLeavesTheOldJarAlone(@TempDir Path dir) throws IOException {
            server.serve(500, new byte[0]);
            File target = dir.resolve("plugin.jar").toFile();
            byte[] existing = "the old jar".getBytes(StandardCharsets.UTF_8);
            Files.write(target.toPath(), existing);

            assertThrows(IOException.class, () -> downloader.download(server.url("/x.jar"), target));

            assertArrayEquals(existing, Files.readAllBytes(target.toPath()));
            assertNoPartFile(target);
        }

        private DownloadPolicy loopbackPolicy(long maxBytes) {
            return DownloadPolicy.builder()
                    .allowedHosts("127.0.0.1")
                    .requireHttps(false)
                    .maxBytes(maxBytes)
                    .connectTimeoutMs(5000)
                    .readTimeoutMs(5000)
                    .build();
        }
    }

    @Nested
    @DisplayName("redirects and concurrency")
    class RedirectsAndConcurrency {

        private LoopbackServer server;

        @BeforeEach
        void start() {
            server = new LoopbackServer();
        }

        @AfterEach
        void stop() {
            server.close();
        }

        private DownloadPolicy loopbackPolicy() {
            return DownloadPolicy.builder()
                    .allowedHosts("127.0.0.1")
                    .requireHttps(false)
                    .maxBytes(DownloadPolicy.MAX_DOWNLOAD_BYTES)
                    .connectTimeoutMs(5000)
                    .readTimeoutMs(5000)
                    .build();
        }

        @Test
        @DisplayName("a same-host redirect is followed to the jar")
        void followsAnAllowedRedirect(@TempDir Path dir) throws IOException {
            server.serve(200, JAR_BYTES);
            server.redirectTo(server.url("/final.jar"));
            UpdateDownloader downloader = new UpdateDownloader(logger, loopbackPolicy());
            File target = dir.resolve("plugin.jar").toFile();

            long written = downloader.download(server.url("/start.jar"), target);

            assertEquals(JAR_BYTES.length, written);
            assertArrayEquals(JAR_BYTES, Files.readAllBytes(target.toPath()));
        }

        @Test
        @DisplayName("a redirect to a DISALLOWED host is refused — the JDK would have followed it")
        void refusesARedirectOffTheAllowlist(@TempDir Path dir) {
            // The whole point: the JDK's automatic follower re-checks only the scheme, not the host,
            // so github.com -> attacker.example would sail past a single up-front check. Manual
            // following re-validates every hop, so this is refused before a socket is even opened to
            // the attacker's host.
            server.serve(200, JAR_BYTES);
            server.redirectTo("http://attacker.example.com/evil.jar");
            UpdateDownloader downloader = new UpdateDownloader(logger, loopbackPolicy());
            File target = dir.resolve("plugin.jar").toFile();

            IOException refused = assertThrows(IOException.class,
                    () -> downloader.download(server.url("/start.jar"), target));

            assertTrue(refused.getMessage().contains("attacker.example.com"), refused.getMessage());
            assertFalse(target.exists());
        }

        @Test
        @DisplayName("two downloads to the same target — the second is refused, not interleaved")
        void concurrentDownloadsToOneTargetAreGuarded(@TempDir Path dir) throws Exception {
            // The first download blocks in the server until released, so it is provably in flight
            // when the second starts. Interleaving two transfers into one .part is how a
            // half-of-each corrupt jar reaches plugins/update/.
            server.blockUntilReleased();
            server.serve(200, JAR_BYTES);
            UpdateDownloader downloader = new UpdateDownloader(logger, loopbackPolicy());
            File target = dir.resolve("plugin.jar").toFile();

            java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread first = new Thread(() -> {
                try {
                    downloader.download(server.url("/a.jar"), target);
                } catch (Throwable t) {
                    firstError.set(t);
                }
            });
            first.start();
            assertTrue(server.awaitRequest(5000), "the first download never reached the server");

            IOException refused = assertThrows(IOException.class,
                    () -> downloader.download(server.url("/b.jar"), target));
            assertTrue(refused.getMessage().toLowerCase().contains("in progress"), refused.getMessage());

            server.release();
            first.join(10_000);
            assertEquals(null, firstError.get(), "the first download should have completed cleanly");
        }
    }

    private static void assertNoPartFile(File target) {
        File part = new File(target.getAbsolutePath() + ".part");
        assertFalse(part.exists(), "a .part file was left behind at " + part);
    }

    /** A loopback server that answers one scripted status and body for any path. */
    private static final class LoopbackServer implements AutoCloseable {

        private final HttpServer server;

        private volatile int status = 200;
        private volatile byte[] body = new byte[0];
        private volatile String redirectLocation;
        private volatile java.util.concurrent.CountDownLatch release;
        private final java.util.concurrent.CountDownLatch requestSeen =
                new java.util.concurrent.CountDownLatch(1);

        LoopbackServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", this::handle);
            server.start();
        }

        void serve(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        /** A path not containing "final" gets a 302 here; "final" paths serve the body. */
        void redirectTo(String location) {
            this.redirectLocation = location;
        }

        /** Makes the next served body block until {@link #release()}, so a download can be caught mid-flight. */
        void blockUntilReleased() {
            this.release = new java.util.concurrent.CountDownLatch(1);
        }

        void release() {
            java.util.concurrent.CountDownLatch latch = release;
            if (latch != null) {
                latch.countDown();
            }
        }

        boolean awaitRequest(long ms) throws InterruptedException {
            return requestSeen.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] payload = body;
            requestSeen.countDown();
            try {
                exchange.getRequestBody().close();
                String path = exchange.getRequestURI().getPath();
                if (redirectLocation != null && !path.contains("final")) {
                    exchange.getResponseHeaders().set("Location", redirectLocation);
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                java.util.concurrent.CountDownLatch latch = release;
                if (latch != null) {
                    try {
                        latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                // -1 rather than 0 for an empty body: com.sun's HttpServer reads 0 as "chunked,
                // length unknown", which is not what an empty response means.
                exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
                OutputStream out = exchange.getResponseBody();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            } catch (IOException broken) {
                // Expected on the size-cap case: the client stops reading and closes the socket
                // part-way through a 512 KB body. Not a test failure — it is the behaviour.
            } finally {
                exchange.close();
            }
        }
    }
}
