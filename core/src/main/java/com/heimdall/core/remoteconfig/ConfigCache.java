package com.heimdall.core.remoteconfig;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The last configuration the bot pushed, kept on disk so an offline boot is not an unconfigured one.
 *
 * <p><strong>This is the whole reason remote config is safe to depend on.</strong> Without it, a
 * server that restarts while the bot is redeploying comes up with nothing: no modules enabled, no
 * messages, no cache windows. With it, the server comes up exactly as it was and the outage is
 * invisible — which is the same bargain the whitelist mirror makes, for the same reason.
 *
 * <p>Writes go through {@link AtomicFiles}, so a crash mid-write cannot leave a truncated file that
 * fails to parse on the next boot. Reads never throw: an unreadable or corrupt cache is reported and
 * treated as absent, because falling back to the built-in defaults is always better than refusing to
 * start.
 *
 * <p>Not thread-safe by itself; {@link RemoteConfig} serialises access to it.
 */
final class ConfigCache {

    private final HeimdallLogger logger;
    private final Path path;

    ConfigCache(HeimdallLogger logger, Path path) {
        this.logger = logger;
        this.path = path;
    }

    /** Where the cache lives. */
    Path path() {
        return path;
    }

    /**
     * Reads the cached document.
     *
     * @return the document, or {@link ConfigDocument#empty()} if there is no usable cache
     */
    ConfigDocument load() {
        if (path == null || !Files.exists(path)) {
            return ConfigDocument.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            Payload payload = Payload.parse(new String(bytes, StandardCharsets.UTF_8));
            if (payload.isEmpty()) {
                logger.warn("the cached remote config at " + path + " is unreadable — using defaults");
                return ConfigDocument.empty();
            }
            ConfigDocument document = ConfigDocument.fromPayload(payload);
            logger.info("restored remote config version " + document.version() + " from disk");
            return document;
        } catch (IOException e) {
            logger.error("could not read the cached remote config at " + path, e);
            return ConfigDocument.empty();
        }
    }

    /**
     * Writes the document.
     *
     * <p>Failures are logged, not thrown. A server whose data directory is read-only should keep
     * running on the config it just received rather than refuse the push.
     */
    void save(ConfigDocument document) {
        if (path == null) {
            return;
        }
        try {
            AtomicFiles.writeUtf8(path, document.toPayload().toJson());
        } catch (IOException e) {
            logger.error("could not cache remote config version " + document.version()
                    + " to " + path + "; it will not survive a restart", e);
        }
    }
}
