package com.heimdall.core.mirror;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Everything about a mirror that touches the disk: the Gson codec, the atomic write, and the
 * debounce.
 *
 * <p>Split out of {@link MirrorStore} so that class is only the expiry and reconciliation
 * semantics. The two failure modes here — a truncated file on a crash, and a whole-file synchronous
 * rewrite on the login thread — were both v2 defects, and both are properties of persistence rather
 * than of what a mirror <em>means</em>.
 *
 * <p>{@link #load()} never throws: an unreadable or half-written file yields an empty snapshot and
 * a logged error, because the next fetch repopulates an empty mirror whereas a failed boot means
 * nobody can join at all.
 *
 * @param <T> the mirrored value type
 */
final class MirrorFile<T> implements AutoCloseable {

    private final HeimdallLogger logger;
    private final Path path;
    private final Gson gson = new Gson();
    private final Type snapshotType;
    private final DebouncedWriter writer;

    /**
     * @param valueType the mirrored value's type, since Gson cannot see {@code T}
     * @param snapshotSource produces the current state at write time, so the writer never holds a
     *     stale copy between the mutation and the debounced save
     */
    MirrorFile(
            HeimdallLogger logger,
            Path path,
            Type valueType,
            long debounceMs,
            ScheduledExecutorService scheduler,
            final Supplier<MirrorSnapshot<T>> snapshotSource) {
        this.logger = logger;
        this.path = path;
        this.snapshotType = TypeToken.getParameterized(MirrorSnapshot.class, valueType).getType();
        this.writer = new DebouncedWriter(logger, scheduler, debounceMs, path.toString(), new Runnable() {
            @Override
            public void run() {
                write(snapshotSource.get());
            }
        });
    }

    /** The persisted state, or an empty snapshot if there is none or it could not be read. */
    MirrorSnapshot<T> load() {
        if (!Files.isRegularFile(path)) {
            return new MirrorSnapshot<T>();
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            MirrorSnapshot<T> snapshot = gson.fromJson(json, snapshotType);
            if (snapshot == null || snapshot.entries == null) {
                return new MirrorSnapshot<T>();
            }
            return snapshot;
        } catch (IOException e) {
            logger.error("Could not read " + path + " — starting with an empty mirror", e);
        } catch (RuntimeException e) {
            logger.error("Could not parse " + path + " — starting with an empty mirror", e);
        }
        return new MirrorSnapshot<T>();
    }

    /** Notes that there is something to save; the write happens on the scheduler. */
    void markDirty() {
        writer.markDirty();
    }

    /** Writes any pending state now. */
    void flush() {
        writer.flush();
    }

    /** Flushes and stops debouncing. */
    @Override
    public void close() {
        writer.close();
    }

    /** How many writes have actually happened. Diagnostics and tests. */
    long writeCount() {
        return writer.writeCount();
    }

    /** The file's name, for log lines that should not carry a whole path. */
    String name() {
        return String.valueOf(path.getFileName());
    }

    private void write(MirrorSnapshot<T> snapshot) {
        try {
            AtomicFiles.writeUtf8(path, gson.toJson(snapshot, snapshotType));
        } catch (IOException e) {
            // Surfaced to DebouncedWriter, which logs it and keeps the dirty flag set so the next
            // flush retries rather than silently dropping the change.
            throw new UncheckedIOException(e);
        }
    }
}
