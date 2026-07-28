package com.heimdall.core.migrate;

import com.heimdall.core.config.BootstrapConfig;
import com.heimdall.core.config.BootstrapStore;
import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a v2 install into a v3 one, on the first boot that finds no {@code bootstrap.yml}.
 *
 * <h2>What is actually being migrated</h2>
 *
 * <p>v2 kept a ~200-line config file per server. v3 keeps six keys and a cache (departure D17); every
 * other setting is owned by the dashboard. So a migration is two unrelated moves at once: three
 * fields become {@code bootstrap.yml}, and the rest becomes a document for
 * {@code POST …/servers/{serverId}/config/import} so the operator's tuning is not silently reset to
 * defaults the moment they upgrade. This class does the first and <em>builds</em> the second;
 * posting it belongs to the caller, because file work has to succeed whether or not the bot is up.
 *
 * <h2>Legacy token mode</h2>
 *
 * <p>A migrated server has a token and no {@code tokenId}, because v2 had no such concept — the HMAC
 * signature is what authenticates and the bot accepts a bare guild key on both the HTTP API and the
 * WS upgrade. {@link MigrationResult#legacyToken()} reports it. Nothing needs to be re-issued for the
 * server to work; running {@code /hd setup} later replaces both halves with a proper pair.
 *
 * <h2>The guild is not carried over as a setting</h2>
 *
 * <p>v2's {@code api.guildId} was hand-typed and was its single most common support problem
 * (departure D54). It is copied into the {@code guildIdCache} slot, which is provisional: the tunnel
 * can dial with it immediately, and whatever {@code identify} answers overwrites it. It is
 * deliberately <em>not</em> treated as authoritative, because a snowflake that was wrong in v2 is
 * still wrong now.
 *
 * <h2>Never destructive</h2>
 *
 * <p>Three rules, in the order they matter:
 *
 * <ul>
 *   <li><strong>An existing {@code bootstrap.yml} stops everything.</strong> Not just the write — the
 *       search too. That is what makes this safe to call unconditionally on every boot, and it means
 *       a v2 file left beside a working v3 install is never touched.
 *   <li><strong>Nothing moves until the bootstrap is written.</strong> An unparseable file, a file
 *       with no credentials, a failed save: in every one of those the v2 config is still exactly
 *       where the operator left it, which is what they need in order to fix it.
 *   <li><strong>The v2 file is renamed, never deleted.</strong> To {@code <name>.v2-backup}, and to
 *       {@code .1}, {@code .2}… if that is taken. A failed rename is a warning, not a failure — the
 *       migration has already happened, and the next boot sees the bootstrap and stops at rule one.
 * </ul>
 *
 * <h2>The consequence nobody expects</h2>
 *
 * <p>An imported config document is inert until the server is registered: the bot writes it, and
 * serves it only once a registry row points at that {@code serverId}. So a migrated-but-unclaimed
 * server runs v3's built-in defaults — which are v2's shipped values, deliberately — and the imported
 * settings light up the moment {@code /hd setup} claims it. That is stated in {@link
 * MigrationResult#detail()} rather than left implicit, because otherwise an operator watches a
 * migration succeed and concludes their settings did not come across.
 *
 * <p>Not thread-safe and not meant to be: this runs once, on the boot path, before anything else has
 * a reference to the config.
 */
public final class V2Migration {

    /** What a migrated v2 config file is renamed to. */
    public static final String BACKUP_SUFFIX = ".v2-backup";

    /**
     * The file names looked for, in this order, within each directory.
     *
     * <p>YAML first because it is the overwhelmingly common case — v2's Bukkit build outnumbers its
     * Velocity build by a wide margin — and because a directory holding both is a proxy install
     * somebody has also dropped a Bukkit config into, where the YAML is the one that was in use.
     */
    private static final List<String> CANDIDATE_FILE_NAMES =
            Arrays.asList(V2ConfigReader.YAML_FILE_NAME, V2ConfigReader.JSON_FILE_NAME);

    /** How many {@code .1}, {@code .2}… suffixes to try before giving up on a backup name. */
    private static final int MAX_BACKUP_ATTEMPTS = 100;

    private final HeimdallLogger logger;
    private final V2ConfigReader reader;

    public V2Migration(HeimdallLogger logger) {
        if (logger == null) {
            throw new IllegalArgumentException("logger is required");
        }
        this.logger = logger;
        this.reader = new V2ConfigReader(logger);
    }

    /**
     * Migrates the first v2 config found, if there is one and this server is not already configured.
     *
     * @param searchDirectories where to look, in order. The caller passes v3's own data directory
     *     first and then the sibling {@code HeimdallWhitelist} directory: v3's plugin is named
     *     {@code Heimdall}, so a v2 install's config sits next door rather than in v3's folder, and
     *     the two orders are not interchangeable — a config an operator has already dropped into the
     *     v3 directory by hand is the one they meant.
     * @param store the v3 bootstrap this would write, and the thing whose existence vetoes the whole
     *     operation
     * @return what happened; never {@code null}, and never throwing
     */
    public MigrationResult run(List<Path> searchDirectories, BootstrapStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store is required");
        }

        if (store.exists()) {
            MigrationResult result = MigrationResult.alreadyConfigured(
                    store.file() + " already exists — no v2 migration attempted.");
            logger.debug(result::detail);
            return result;
        }

        Path source = findV2Config(searchDirectories);
        if (source == null) {
            // Silent. A fresh install has no v2 config and does not need to be told so.
            return MigrationResult.notFound("No v2 config found.");
        }

        logger.info("Found a v2 config at " + source + " — migrating it to this version's layout.");

        V2Config config = reader.read(source);
        if (config == null) {
            return unusable(source, "Found a v2 config at " + source + " but could not parse it. "
                    + "Nothing has been changed — fix or remove the file, or run /hd setup to "
                    + "configure this server from scratch.");
        }
        if (!config.hasCredentials()) {
            return unusable(source, "Found a v2 config at " + source + " but it carries no usable "
                    + "credentials (api.baseUrl or api.apiKey is blank, or apiKey is still the "
                    + "placeholder '" + V2Config.PLACEHOLDER_API_KEY + "'). Nothing has been changed "
                    + "— run /hd setup to configure this server.");
        }

        BootstrapConfig bootstrap = toBootstrap(config);
        try {
            store.save(bootstrap);
        } catch (IOException e) {
            return unusable(source, "Found a usable v2 config at " + source + " but could not write "
                    + store.file() + " (" + e.getMessage() + "). Nothing has been changed — the v2 "
                    + "file is still where it was, so fixing the permissions on the data directory "
                    + "and restarting will retry the migration.");
        }

        Path backup = backUp(source);
        MigrationResult result = MigrationResult.migrated(
                source, backup, bootstrap, toModules(config), describe(source, backup, store.file()));
        logger.info(result.detail());
        return result;
    }

    private MigrationResult unusable(Path source, String detail) {
        logger.warn(detail);
        return MigrationResult.unusable(source, detail);
    }

    /** The first {@code config.yml} then {@code config.json} in the first directory that has one. */
    private Path findV2Config(List<Path> searchDirectories) {
        if (searchDirectories == null) {
            return null;
        }
        for (Path directory : searchDirectories) {
            if (directory == null || !Files.isDirectory(directory)) {
                continue;
            }
            for (String name : CANDIDATE_FILE_NAMES) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * The three v2 fields that survive into v3's local config, plus the provisional guild.
     *
     * <p>{@code serverId} is copied verbatim, blank included. v2 generated one lazily and plenty of
     * installs never had one written back to their file; a blank id is a state v3 already handles,
     * and inventing one here would produce a server the dashboard has never heard of under a name
     * nobody chose.
     *
     * <p>The role is {@link ServerRole#AUTO} rather than derived from which file was found. Reading
     * {@code config.json} as "this is a proxy" would be a guess dressed as a fact, and the detector
     * v3 already has answers it from the platform it is actually running on.
     */
    private static BootstrapConfig toBootstrap(V2Config config) {
        return BootstrapConfig.builder()
                .endpoint(config.baseUrl())
                .tokenId("")
                .token(config.apiKey())
                .serverId(config.serverId())
                .guildId(config.guildId())
                .role(ServerRole.AUTO)
                .debug(config.debug())
                .build();
    }

    /**
     * The {@code modules} document for the config-import endpoint.
     *
     * <p>Nested form — {@code {enabled, settings}} — which is the shape the v3 design specifies;
     * remote config reads the flat form too (departure D33) but there is no reason to emit the
     * ambiguous one.
     *
     * <p><strong>Every key is written, including ones whose value equals the default.</strong> The
     * imported document is meant to be a complete statement of what that server was running, and an
     * omitted key is indistinguishable from one the migration did not know about. Being clever about
     * omission also means a later change to a v3 default silently rewrites the history of a server
     * that was migrated before it.
     *
     * <p>Four of these key names are not v2's: {@code cache.cleanupInterval} becomes
     * {@code cleanupIntervalMinutes}, {@code cache.prewarm.*} flattens to {@code prewarmEnabled} and
     * {@code prewarmIntervalMinutes}, and the two {@code messages.*} entries gain their
     * {@code Message} suffix. Those are the names {@code WhitelistSettings} reads, and the names it
     * reads are the only ones that do anything.
     */
    private static Payload toModules(V2Config config) {
        Payload whitelistSettings = Payload.builder()
                .put("cacheWindow", config.cacheWindowMinutes())
                .put("extendOnJoin", config.extendOnJoinMinutes())
                .put("extendOnLeave", config.extendOnLeaveMinutes())
                .put("maxExtensionHours", config.maxExtensionHours())
                .put("prewarmEnabled", config.prewarmEnabled())
                .put("prewarmIntervalMinutes", config.prewarmIntervalMinutes())
                .put("cleanupIntervalMinutes", config.cleanupIntervalMinutes())
                .put("apiFallbackMode", config.apiFallbackMode())
                .putStrings("bypassUuids", config.bypassUuids())
                .put("apiUnavailableMessage", config.apiUnavailableMessage())
                .put("apiUnavailableAllowedMessage", config.apiUnavailableAllowedMessage())
                .build();

        Payload updateSettings = Payload.builder()
                .put("notifyAdmins", config.updateNotifyAdmins())
                .put("checkIntervalHours", config.updateCheckIntervalHours())
                .build();

        return Payload.builder()
                .put("whitelist", module(config.pluginEnabled(), whitelistSettings))
                .put("rolesync", module(config.roleSyncEnabled(), Payload.empty()))
                .put("console", module(config.consoleStream(), Payload.empty()))
                .put("updates", module(config.updateCheckEnabled(), updateSettings))
                .build();
    }

    private static Payload module(boolean enabled, Payload settings) {
        return Payload.builder().put("enabled", enabled).put("settings", settings).build();
    }

    /**
     * Renames the v2 file out of the way.
     *
     * @return where it went, or {@code null} if it could not be moved — which is logged as a warning
     *     and nothing more. The bootstrap is already on disk, so the migration has happened; the only
     *     cost of a leftover {@code config.yml} is that it is confusing, and deleting it to avoid
     *     that would be strictly worse.
     */
    private Path backUp(Path source) {
        Path target = freeBackupName(source);
        if (target == null) {
            logger.warn("Migrated " + source + " but could not find a free backup name beside it; "
                    + "the v2 file has been left in place. It is no longer read.");
            return null;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException | RuntimeException atomicFailed) {
            // A data directory can be a bind mount or a network share that refuses an atomic move;
            // a plain rename within one directory is still worth trying.
            try {
                Files.move(source, target);
                return target;
            } catch (IOException e) {
                logger.warn("Migrated " + source + " but could not rename it to " + target + " ("
                        + e.getMessage() + "); the v2 file has been left in place. It is no longer "
                        + "read, and can be deleted once the migration has been checked.");
                return null;
            }
        }
    }

    /**
     * {@code <name>.v2-backup}, or the first free {@code .1}, {@code .2}… beside it.
     *
     * <p>Collisions are real rather than theoretical: an operator who migrates, rolls back to v2, and
     * migrates again gets here twice, and overwriting the first backup would destroy the only copy of
     * the config they were actually running.
     *
     * @return a path that does not exist, or {@code null} after {@link #MAX_BACKUP_ATTEMPTS} — a
     *     bound rather than an unbounded loop, because a directory that reports every candidate as
     *     existing is a filesystem problem this must not spin on
     */
    private static Path freeBackupName(Path source) {
        Path base = source.resolveSibling(source.getFileName().toString() + BACKUP_SUFFIX);
        if (!Files.exists(base)) {
            return base;
        }
        for (int index = 1; index <= MAX_BACKUP_ATTEMPTS; index++) {
            Path candidate = source.resolveSibling(
                    source.getFileName().toString() + BACKUP_SUFFIX + "." + index);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The one sentence an operator reads about all of this. */
    private static String describe(Path source, Path backup, Path bootstrap) {
        String moved = backup == null
                ? "the v2 file could not be renamed and is still at " + source + " (it is no longer read)"
                : "the v2 file has been kept as " + backup;
        return "Migrated the v2 config at " + source + " — wrote " + bootstrap + " and " + moved
                + ". Its other settings have been prepared for import into the dashboard, but an "
                + "imported configuration stays inert until this server is claimed: run /hd setup to "
                + "claim it and they take effect. Until then Heimdall runs its built-in defaults, "
                + "which are v2's, so nothing changes for your players in the meantime.";
    }
}
