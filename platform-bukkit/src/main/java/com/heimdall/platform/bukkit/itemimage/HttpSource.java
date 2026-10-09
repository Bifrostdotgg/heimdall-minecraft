package com.heimdall.platform.bukkit.itemimage;

import com.heimdall.core.BuildConstants;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Where asset bytes come from: Mojang's piston servers, or a server's resource-pack URL.
 *
 * <p>A seam rather than a direct {@link HttpURLConnection} so the asset cache can be tested against
 * a fake that serves a manifest, a version document and a small jar from memory, including ones
 * whose hashes do not match. Every implementation must bound what it reads: {@code maxBytes} is a
 * hard cap, not a hint.
 */
interface HttpSource {

    /** GETs {@code url} into memory; more than {@code maxBytes} is an {@link IOException}. */
    byte[] get(String url, long maxBytes) throws IOException;

    /**
     * GETs {@code url} into {@code target}, replacing it; more than {@code maxBytes} is an
     * {@link IOException} and leaves no file behind.
     *
     * @return the number of bytes written
     */
    long download(String url, Path target, long maxBytes) throws IOException;

    /**
     * The real network. Bounded four ways: connect and per-read timeouts, a wall-clock cap on the
     * whole transfer (a server dripping one byte a second never trips a read timeout, and preparing
     * must not stick), the byte cap, and at most a handful of redirects, never from https down to
     * http. Every failure surfaces as an {@link AssetException} naming the exception class and the
     * host only. Blocking: called on the asset thread only.
     */
    final class Url implements HttpSource {

        static final int CONNECT_TIMEOUT_MS = 10_000;
        static final int READ_TIMEOUT_MS = 30_000;
        static final long GET_WALL_CLOCK_MS = TimeUnit.MINUTES.toMillis(2);
        static final long DOWNLOAD_WALL_CLOCK_MS = TimeUnit.MINUTES.toMillis(5);
        private static final int MAX_REDIRECTS = 5;
        private static final String USER_AGENT = "Heimdall/" + BuildConstants.VERSION;

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            long deadline = System.currentTimeMillis() + GET_WALL_CLOCK_MS;
            String host = host(url);
            HttpURLConnection connection = open(url);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, maxBytes, deadline, host);
                return out.toByteArray();
            } catch (AssetException ours) {
                throw ours;
            } catch (IOException transport) {
                throw wrap(transport, host);
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public long download(String url, Path target, long maxBytes) throws IOException {
            long deadline = System.currentTimeMillis() + DOWNLOAD_WALL_CLOCK_MS;
            String host = host(url);
            HttpURLConnection connection = open(url);
            boolean ok = false;
            try (InputStream in = connection.getInputStream();
                    OutputStream out = Files.newOutputStream(target)) {
                long written = copy(in, out, maxBytes, deadline, host);
                ok = true;
                return written;
            } catch (AssetException ours) {
                throw ours;
            } catch (IOException transport) {
                throw wrap(transport, host);
            } finally {
                connection.disconnect();
                if (!ok) {
                    Files.deleteIfExists(target);
                }
            }
        }

        private static HttpURLConnection open(String start) throws IOException {
            String current = start;
            boolean secure = false;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                URL url = new URL(current);
                String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
                if (!"https".equals(protocol) && !"http".equals(protocol)) {
                    throw new AssetException("refusing a " + protocol + " URL");
                }
                if (secure && "http".equals(protocol)) {
                    throw new AssetException("refusing a redirect from https to http ("
                            + url.getHost() + ")");
                }
                secure = "https".equals(protocol);
                HttpURLConnection connection;
                int code;
                try {
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    code = connection.getResponseCode();
                } catch (IOException transport) {
                    throw wrap(transport, url.getHost());
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    return connection;
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) {
                        throw new AssetException("redirect without a Location from "
                                + url.getHost());
                    }
                    current = new URL(url, location).toString();
                    continue;
                }
                connection.disconnect();
                throw new AssetException("HTTP " + code + " from " + url.getHost());
            }
            throw new AssetException("too many redirects");
        }

        private static long copy(InputStream in, OutputStream out, long maxBytes, long deadline,
                String host) throws IOException {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new AssetException("response from " + host + " larger than " + maxBytes
                            + " bytes");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AssetException("transfer from " + host + " took too long");
                }
                out.write(buffer, 0, read);
            }
            return total;
        }

        private static AssetException wrap(IOException transport, String host) {
            return new AssetException(transport.getClass().getSimpleName() + " from " + host);
        }

        private static String host(String url) {
            try {
                return new URL(url).getHost();
            } catch (IOException malformed) {
                return "an unparseable URL";
            }
        }
    }
}
