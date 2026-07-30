package com.heimdall.core.wiring;

import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.http.model.ConfigImportResult;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.migrate.MigrationResult;
import com.heimdall.core.migrate.V2Migration;
import com.heimdall.core.util.Strings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Where a v2 install becomes a v3 one, on the first boot after the jar is swapped.
 *
 * <p>{@link V2Migration} does the file half — find {@code config.yml} or {@code config.json}, write
 * a {@code bootstrap.yml} from its credentials, translate the rest, rename the original to
 * {@code .v2-backup}. This is the boot half: <em>where</em> to look, and what to do with the
 * translated settings afterwards.
 *
 * <h2>Two directories, because v3's is not v2's</h2>
 *
 * <p>v2's plugin was called {@code HeimdallWhitelist} and v3's is called {@code Heimdall}, so a
 * server that has just had its jar replaced has a brand-new empty {@code plugins/Heimdall/} and its
 * entire configuration sitting in {@code plugins/HeimdallWhitelist/} next door. Looking only in the
 * new directory would find nothing on every single real upgrade — which is the one case this exists
 * for. The new directory is searched first anyway, because an operator who moved the file by hand
 * meant it.
 *
 * <p>On Velocity the names are the two plugins' ids rather than their display names, because the
 * proxy derives a plugin's directory from its id: v3's is {@code heimdall} and v2's is
 * {@code heimdall-whitelist}.
 *
 * <h2>The imported settings are inert until the server is claimed, and the log says so</h2>
 *
 * <p>{@code POST …/servers/{id}/config/import} accepts a document for a server id the bot has no
 * registry row for — deliberately, so a migration does not have to claim first — but the bot never
 * reads a config store for an unregistered server, so the document sits there doing nothing. A
 * migrated server therefore runs on <em>built-in defaults</em>, which are v2's shipped values and so
 * are the behaviour it had yesterday, until {@code /hd setup} claims it. At that moment the imported
 * settings become the ones the dashboard serves.
 *
 * <p>That is correct and it is surprising, so the migration line says it in as many words. Without
 * it the operator's experience is: migration succeeded, dashboard shows my settings, server ignores
 * them.
 *
 * <h2>Why the import is retried rather than done inline</h2>
 *
 * <p>It is a signed, guild-scoped call, and a server that has just migrated has credentials but no
 * guild yet — {@code identify} has not answered. Posting immediately would fail on every upgrade.
 * So it is deferred onto {@code heimdall-sched} and retried on a short interval for a couple of
 * minutes, which covers a bot that is still starting up. Giving up after that is the right answer
 * rather than retrying forever: the document is write-once, nothing downstream depends on it having
 * landed, and an operator can re-run the whole thing by restoring the backup file.
 */
public final class MigrationBoot {

    /**
     * v2's plugin directory on the Bukkit family — v2's {@code plugin.yml} {@code name:} verbatim,
     * because Bukkit names a plugin's directory after its declared name.
     */
    public static final String V2_BUKKIT_DIRECTORY = "HeimdallWhitelist";

    /**
     * v2's plugin directory on Velocity — v2's {@code @Plugin} <em>id</em> verbatim, hyphen and all,
     * because Velocity names a plugin's directory after its id rather than its display name.
     *
     * <p>The id is {@code heimdall-whitelist} in every v2 release (v2.0.0 through v2.4.0 and the
     * {@code v2-maintenance} branch all declare it on {@code HeimdallVelocityPlugin}), so there is
     * exactly one name to look for. It is spelled out here rather than derived from the display
     * name, which is what went wrong: this was a hand-lowercased {@code heimdallwhitelist} that no
     * v2 install has ever had, so on a case-sensitive filesystem a v2 → v3 upgrade on Velocity found
     * nothing and booted unconfigured.
     */
    public static final String V2_VELOCITY_DIRECTORY = "heimdall-whitelist";

    /**
     * What a platform passes when v2 never had a build for it — BungeeCord.
     *
     * <p>v2 shipped exactly two entry points, Bukkit and Velocity, so no BungeeCord proxy has ever
     * had a {@code plugins/HeimdallWhitelist/} or {@code plugins/heimdall-whitelist/} of its own.
     * There is no sibling directory to name, and naming one anyway would be worse than useless: it
     * would be a guess presented as a fact in the one log line an operator reads when an upgrade
     * appears to have lost their configuration.
     *
     * <p>The plugin's <em>own</em> data directory is still searched, which is deliberate and is the
     * same rule the other two platforms follow — "a config an operator has already dropped into the
     * v3 directory by hand is the one they meant". So a v2 config copied across from a backend
     * server still migrates, and one left in a directory that merely looks like v2's still produces
     * {@link com.heimdall.core.migrate.V2Migration}'s near-miss line telling the operator where to
     * put it (departure D70). What is skipped is only the guess about where v2 <em>would</em> have
     * kept it.
     */
    public static final String NO_V2_DIRECTORY = "";

    /** How often the deferred import re-checks whether the bot can be asked. */
    private static final long IMPORT_RETRY_MS = 10_000L;

    /** How many times, before giving up. Two minutes, which covers a bot that is still booting. */
    private static final int IMPORT_ATTEMPTS = 12;

    private MigrationBoot() {
    }

    /**
     * Runs the file half of the migration, before the runtime reads {@code bootstrap.yml}.
     *
     * <p>Must be called <em>before</em> {@link HeimdallRuntime.Builder#build()}, which loads the
     * bootstrap into an immutable object. Running it afterwards would write a file nothing re-reads.
     *
     * @param dataDirectory this plugin's own data directory
     * @param v2DirectoryName what v2's directory is called on this platform — see the constants;
     *     {@link #NO_V2_DIRECTORY} for a platform v2 never shipped a build for, which searches this
     *     plugin's own directory and nothing beside it
     * @return what happened; safe to call on every boot, since an existing {@code bootstrap.yml}
     *     short-circuits it
     */
    public static MigrationResult migrate(
            HeimdallLogger logger, BootstrapStore store, Path dataDirectory, String v2DirectoryName) {
        List<Path> searchDirectories = new ArrayList<Path>();
        searchDirectories.add(dataDirectory);
        Path parent = dataDirectory.getParent();
        if (parent != null && !Strings.isBlank(v2DirectoryName)) {
            searchDirectories.add(parent.resolve(v2DirectoryName));
        }
        return new V2Migration(logger).run(searchDirectories, store);
    }

    /**
     * Hands the translated settings to the bot, once it can be asked.
     *
     * <p>A no-op for anything other than a migration that produced settings. Never throws, and never
     * blocks the caller: it schedules and returns.
     */
    public static void scheduleImport(
            final HeimdallLogger logger, final HeimdallRuntime runtime, final MigrationResult result) {
        if (result == null) {
            return;
        }
        // Both a first migration and a "restore the backup" re-import hand the same settings to the
        // dashboard; the import is write-once bot-side, so re-offering costs nothing.
        boolean importable = result.status() == MigrationResult.Status.MIGRATED
                || result.status() == MigrationResult.Status.REIMPORT;
        if (!importable || result.modules() == null || result.modules().isEmpty()) {
            return;
        }
        final String serverId = runtime.bootstrap().serverId();
        if (Strings.isBlank(serverId)) {
            // v2 allowed a blank server.serverId and plenty of installs have one. There is nothing
            // to import against until /hd setup assigns an id, and the operator loses only the
            // settings translation — the credentials came across fine.
            logger.info("the migrated v2 config had no server id, so its settings cannot be handed "
                    + "to the dashboard yet; they will have to be set there once this server is "
                    + "claimed with /hd setup");
            return;
        }
        schedule(logger, runtime, result, serverId, new AtomicInteger());
    }

    private static void schedule(
            final HeimdallLogger logger,
            final HeimdallRuntime runtime,
            final MigrationResult result,
            final String serverId,
            final AtomicInteger attempts) {
        try {
            @SuppressWarnings("unused")
            ScheduledFuture<?> ignored = runtime.executors().scheduler().schedule(new Runnable() {
                @Override
                public void run() {
                    attemptImport(logger, runtime, result, serverId, attempts);
                }
            }, IMPORT_RETRY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            logger.debug("not importing the migrated settings: the scheduler is shutting down");
        }
    }

    private static void attemptImport(
            final HeimdallLogger logger,
            final HeimdallRuntime runtime,
            final MigrationResult result,
            final String serverId,
            final AtomicInteger attempts) {
        if (!runtime.api().isUsable()) {
            if (attempts.incrementAndGet() >= IMPORT_ATTEMPTS) {
                logger.warn("gave up handing the migrated v2 settings to the dashboard: "
                        + runtime.api().describe() + ". The credentials migrated fine — only the "
                        + "settings translation was lost, and they can be set in the dashboard.");
                return;
            }
            schedule(logger, runtime, result, serverId, attempts);
            return;
        }
        runtime.api().importConfig(serverId, result.modules())
                .whenComplete(new BiConsumer<ConfigImportResult, Throwable>() {
                    @Override
                    public void accept(ConfigImportResult imported, Throwable failure) {
                        if (failure != null) {
                            // A failed POST is retried within the same window as a not-yet-usable
                            // API. The window is what was promised, and a bot that 500s once or drops
                            // the connection mid-request is exactly the transient this is for — giving
                            // up after a single try would lose the operator's settings to one blip.
                            if (attempts.incrementAndGet() < IMPORT_ATTEMPTS) {
                                logger.debug(() -> "the migrated-settings import failed ("
                                        + failure + "); retrying");
                                schedule(logger, runtime, result, serverId, attempts);
                            } else {
                                logger.warn("gave up handing the migrated v2 settings to the "
                                        + "dashboard after repeated failures (" + failure
                                        + "); they can be set there by hand");
                            }
                            return;
                        }
                        if (imported.imported()) {
                            logger.info("handed this server's migrated v2 settings to the dashboard "
                                    + "as configuration version " + imported.version()
                                    + ". They take effect once this server is claimed with "
                                    + "/hd setup <code>.");
                        } else {
                            // Write-once, and not an error: the dashboard already had settings for
                            // this server id, and overwriting them with a translation of a file
                            // somebody stopped editing months ago would be the wrong answer.
                            logger.info("the dashboard already has settings for this server, so the "
                                    + "migrated v2 values were not imported — what is in the "
                                    + "dashboard wins.");
                        }
                    }
                });
    }
}
