package com.heimdall.core.migrate;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.json.Payload;
import com.heimdall.core.util.Strings;
import java.nio.file.Path;

/**
 * What {@link V2Migration#run} did, and what the caller has to do next.
 *
 * <h2>Named factories, not a constructor</h2>
 *
 * <p>Departure D21. Four of these seven fields are null in most outcomes, and a constructor taking
 * {@code (Status, Path, Path, BootstrapConfig, Payload, String, boolean)} is the shape that still
 * compiles when the source and the backup are swapped. The factories below cannot be called wrongly:
 * each takes exactly what its outcome carries, and every field it does not carry is not a parameter
 * to get wrong.
 *
 * <h2>Reading it</h2>
 *
 * <p>{@link #status()} is the branch. {@link #detail()} is what an operator reads — one sentence,
 * already logged by {@link V2Migration} at the right level, exposed here so {@code /hd status} and
 * the setup flow can repeat it without re-deriving it.
 *
 * <p>{@link #modules()} is only worth posting on {@link Status#MIGRATED}, and it is
 * {@link Payload#empty()} on every other outcome so a caller that forgets to check the status sends
 * nothing rather than a document of defaults. Posting it is the caller's job, not this package's:
 * the migration is file work and must finish whether or not the bot is reachable, and the import
 * endpoint is write-once anyway, so a failed post is a retry rather than a lost migration.
 */
public final class MigrationResult {

    /** Which of the four things happened. */
    public enum Status {

        /** No v2 config in any of the directories searched. The ordinary case for a fresh install. */
        NOT_FOUND,

        /**
         * A {@code bootstrap.yml} already exists, so nothing was looked at, moved or written.
         *
         * <p>This is what makes the migration safe to run on every boot rather than once.
         */
        ALREADY_CONFIGURED,

        /**
         * A v2 config was found and could not be used — unparseable, no credentials, or the
         * {@code bootstrap.yml} write failed.
         *
         * <p><strong>Nothing was changed on disk.</strong> An operator who has to fix their old file
         * still has it exactly where they left it.
         */
        UNUSABLE,

        /** A {@code bootstrap.yml} was written and the v2 file was kept as a backup. */
        MIGRATED
    }

    private final Status status;
    private final Path source;
    private final Path backup;
    private final BootstrapConfig bootstrap;
    private final Payload modules;
    private final String detail;

    private MigrationResult(Status status, Path source, Path backup, BootstrapConfig bootstrap,
            Payload modules, String detail) {
        this.status = status;
        this.source = source;
        this.backup = backup;
        this.bootstrap = bootstrap;
        this.modules = modules == null ? Payload.empty() : modules;
        this.detail = detail == null ? "" : detail;
    }

    /** Nothing to migrate. */
    public static MigrationResult notFound(String detail) {
        return new MigrationResult(Status.NOT_FOUND, null, null, null, Payload.empty(), detail);
    }

    /** A v3 bootstrap is already in place; the migration did not run. */
    public static MigrationResult alreadyConfigured(String detail) {
        return new MigrationResult(Status.ALREADY_CONFIGURED, null, null, null, Payload.empty(), detail);
    }

    /**
     * A v2 config was found but could not be migrated.
     *
     * @param source the file that was found, which is still exactly where it was
     */
    public static MigrationResult unusable(Path source, String detail) {
        return new MigrationResult(Status.UNUSABLE, source, null, null, Payload.empty(), detail);
    }

    /**
     * The migration succeeded.
     *
     * @param backup where the v2 file was renamed to, or {@code null} if the rename failed — which
     *     does not undo the migration, since the bootstrap is already written
     */
    public static MigrationResult migrated(Path source, Path backup, BootstrapConfig bootstrap,
            Payload modules, String detail) {
        return new MigrationResult(Status.MIGRATED, source, backup, bootstrap, modules, detail);
    }

    /** Which of the four things happened. Never {@code null}. */
    public Status status() {
        return status;
    }

    /** The v2 file that was read, or {@code null} when none was found or looked at. */
    public Path source() {
        return source;
    }

    /**
     * Where the v2 file was renamed to, or {@code null}.
     *
     * <p>{@code null} on a {@link Status#MIGRATED} result means the rename failed and the original is
     * still at {@link #source()}. The migration still succeeded — the bootstrap is written — and the
     * next boot will see the bootstrap and answer {@link Status#ALREADY_CONFIGURED} without touching
     * the leftover file.
     */
    public Path backup() {
        return backup;
    }

    /** The {@code bootstrap.yml} that was written, or {@code null} when none was. */
    public BootstrapConfig bootstrap() {
        return bootstrap;
    }

    /**
     * The document for {@code POST …/servers/{serverId}/config/import}, or {@link Payload#empty()}.
     *
     * <p>Never {@code null}, so a caller can post it without a null check and an empty one is a
     * no-op rather than a crash.
     */
    public Payload modules() {
        return modules;
    }

    /** One operator-facing sentence. Never {@code null}; empty only when there is nothing to say. */
    public String detail() {
        return detail;
    }

    /**
     * Whether the credentials carried over are a v2 key with no token id — <em>legacy mode</em>.
     *
     * <p>Derived rather than stored, so it cannot disagree with {@link #bootstrap()}. v2 had no
     * concept of a token id: the HMAC signature over the request is what authenticates, and the bot
     * accepts a bare guild key on both the HTTP API and the WebSocket upgrade. So a migrated server
     * signs with an empty {@code tokenId} and the {@code X-Token-Id} header is simply omitted.
     *
     * <p>Worth surfacing because it is the one thing a migrated install has that a freshly claimed
     * one does not, and it is what a support conversation needs to know first when such a server
     * behaves differently.
     */
    public boolean legacyToken() {
        return bootstrap != null
                && Strings.isNotBlank(bootstrap.token())
                && Strings.isBlank(bootstrap.tokenId());
    }

    @Override
    public String toString() {
        return "MigrationResult{status=" + status
                + ", source=" + source
                + ", backup=" + backup
                + ", legacyToken=" + legacyToken()
                + ", detail='" + detail
                + "'}";
    }
}
