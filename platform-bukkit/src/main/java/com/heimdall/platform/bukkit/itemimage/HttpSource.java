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
     * The real network, with bounded connect and read timeouts, at most a handful of redirects, and
     * only {@code http} and {@code https}. Blocking: called on the asset thread only.
     */
    final class Url implements HttpSource {

        static final int CONNECT_TIMEOUT_MS = 10_000;
        static final int READ_TIMEOUT_MS = 30_000;
        private static final int MAX_REDIRECTS = 5;
        private static final String USER_AGENT = "Heimdall/" + BuildConstants.VERSION;

        @Override
        public byte[] get(String url, long maxBytes) throws IOException {
            HttpURLConnection connection = open(url);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                copy(in, out, maxBytes);
                return out.toByteArray();
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public long download(String url, Path target, long maxBytes) throws IOException {
            HttpURLConnection connection = open(url);
            boolean ok = false;
            try (InputStream in = connection.getInputStream();
                    OutputStream out = Files.newOutputStream(target)) {
                long written = copy(in, out, maxBytes);
                ok = true;
                return written;
            } finally {
                connection.disconnect();
                if (!ok) {
                    Files.deleteIfExists(target);
                }
            }
        }

        private static HttpURLConnection open(String start) throws IOException {
            String current = start;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                URL url = new URL(current);
                String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
                if (!"https".equals(protocol) && !"http".equals(protocol)) {
                    throw new IOException("refusing a " + protocol + " URL");
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    return connection;
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null) {
                        throw new IOException("redirect without a Location (HTTP " + code + ")");
                    }
                    current = new URL(url, location).toString();
                    continue;
                }
                connection.disconnect();
                throw new IOException("HTTP " + code + " from " + url.getHost());
            }
            throw new IOException("too many redirects");
        }

        private static long copy(InputStream in, OutputStream out, long maxBytes)
                throws IOException {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("response larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, read);
            }
            return total;
        }
    }
}
