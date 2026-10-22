package com.heimdall.module.punishments;

import com.heimdall.core.http.ApiError;
import com.heimdall.core.http.model.PunishmentImportRow;
import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.module.ModuleContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One import pass: LiteBans SQL, AdvancedBan, BanManager, vanilla JSON.
 * Salt is required before any IP is hashed.
 *
 * <h2>In order, in batches, one at a time</h2>
 *
 * <p>Every row from every source is sorted by when it was issued before anything is sent, because
 * the bot numbers imported cases in the order they arrive: table order made case #811 a 2026 ban
 * and #812 a 2023 kick. Rows with no date (vanilla files) go last, since the bot dates them "now".
 *
 * <p>Then they go in sequential batches of {@link #BATCH_SIZE}. The bot refuses more than 5,000
 * rows in one request, and a single request for a whole history refused it all. A batch is small
 * enough to finish inside the gateway's 30 second proxy timeout, the limit that binds in production
 * (the bot looks every row up and numbers its case). Each batch is sent once, with
 * {@link com.heimdall.core.http.ApiClient#IMPORT_TIMEOUT_MS} rather than an ordinary call's few
 * seconds, because a retry of a batch still being written would run the two copies side by side. A failed
 * batch stops the run, saying which batch, so nothing is half-applied out of order behind a
 * failure nobody saw.
 *
 * <p>Runs on the IO executor, never on the tunnel or the main thread (it reads whole tables),
 * and never twice at once on one server.
 *
 * <h2>Previews</h2>
 *
 * <p>A preview reads and sends exactly what a real run does, with {@code dryRun} on every batch,
 * and the bot runs the same matching and writes nothing. Nothing is written here either: address
 * sightings from LiteBans' history are counted, not merged into the local IP store. Every batch
 * carries the run id, which is how the bot keeps a preview's counts across batches equal to the
 * counts the real run will produce.
 */
final class PunishmentImporters {

    static final int BATCH_SIZE = 100;
    /** Longer than the HTTP call's own timeout, so the call is what gives up and says why. */
    private static final long BATCH_WAIT_SECONDS =
            TimeUnit.MILLISECONDS.toSeconds(com.heimdall.core.http.ApiClient.IMPORT_TIMEOUT_MS) + 30L;
    /** How often a long read says it is still going. Well inside the dashboard's stall limit. */
    private static final long HEARTBEAT_MS = 30_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Where a run's progress lines go: the console for a dashboard run, the sender for a command. */
    interface Progress {
        void line(String message);
    }

    private PunishmentImporters() {
    }

    /**
     * Starts a run on the IO executor.
     *
     * @return {@code false} when a run is already in progress on this server
     */
    static boolean start(final ModuleContext context, final LastIpStore ips, final Progress progress) {
        return start(context, ips, progress, false);
    }

    /**
     * Starts a run, or a preview of one when {@code dryRun}. Only start a preview for a bot that
     * accepted {@code punishpreview@1}: an older one ignores the flag and imports for real.
     */
    static boolean start(final ModuleContext context, final LastIpStore ips, final Progress progress,
            final boolean dryRun) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        try {
            context.executors().io().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        importAll(context, ips, progress, dryRun);
                    } catch (RuntimeException e) {
                        context.logger().error("punishment import failed", e);
                        progress.line("Import failed: " + e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
                    } finally {
                        RUNNING.set(false);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            RUNNING.set(false);
            throw rejected;
        }
        return true;
    }

    static void importAll(ModuleContext context, LastIpStore ips, Progress progress) {
        importAll(context, ips, progress, false);
    }

    static void importAll(ModuleContext context, LastIpStore ips, Progress progress, boolean dryRun) {
        HeimdallLogger logger = context.logger();
        Run run = new Run(context, progress, dryRun);
        String salt = PunishmentSettings.from(context.config()).ipSalt;
        if (salt == null || salt.isEmpty()) {
            run.fail("the guild's IP salt has not reached this server yet. Wait for it to connect, "
                    + "then run the import again.");
            return;
        }
        run.report("reading");

        LiteBansImporter.SightingSink sink = null;
        final int[] sightings = new int[1];
        if (ips != null) {
            // A preview counts the sightings a real run would keep, and keeps none.
            final LastIpStore store = dryRun ? null : ips;
            sink = new LiteBansImporter.SightingSink() {
                @Override
                public void accept(LiteBansImporter.Sighting sighting) {
                    if (store != null) {
                        store.mergeHistorical(sighting.uuid, sighting.name, sighting.ip, sighting.seenAt);
                    }
                    sightings[0]++;
                }
            };
        }
        final Run heartbeat = run;
        LiteBansImporter.Result liteBans = LiteBansImporter.readAll(logger, sink, new LiteBansImporter.Ticker() {
            @Override
            public void tick() {
                heartbeat.heartbeat();
            }
        });
        if (ips != null && !dryRun && sightings[0] > 0) {
            ips.flush();
        }
        List<PunishmentImportRow> rows = new ArrayList<PunishmentImportRow>(liteBans.rows);
        run.sources.put("litebans", Integer.valueOf(liteBans.rows.size()));
        List<PunishmentImportRow> advancedBan = AdvancedBanImporter.read(logger);
        run.sources.put("advancedban", Integer.valueOf(advancedBan.size()));
        rows.addAll(advancedBan);
        List<PunishmentImportRow> banManager = BanManagerImporter.read(logger);
        run.sources.put("banmanager", Integer.valueOf(banManager.size()));
        rows.addAll(banManager);
        Path root = serverRoot(context.platform().dataDirectory());
        List<PunishmentImportRow> vanilla = VanillaBanImporter.read(root, logger);
        run.sources.put("vanilla", Integer.valueOf(vanilla.size()));
        rows.addAll(vanilla);
        run.unusable = liteBans.unusable;
        run.historyNames = liteBans.historyRead ? liteBans.historyRows : -1;
        run.ipSightings = ips == null ? -1 : sightings[0];
        for (PunishmentImportRow row : rows) {
            if (row.serverScope != null) run.serverScoped++;
        }

        progress.line("Found " + rows.size() + " punishments (" + describe(run.sources) + ")"
                + (run.unusable > 0 ? ", " + run.unusable + " LiteBans rows name nobody and are left out" : "")
                + (run.serverScoped > 0 ? ", " + run.serverScoped
                        + " scoped to one server, imported network-wide" : "")
                + ".");
        if (liteBans.installed) {
            progress.line(liteBans.historyRead
                    ? "Read " + liteBans.historyRows + " LiteBans history rows"
                            + (ips == null ? "" : ", " + sightings[0] + " address sightings "
                                    + (dryRun ? "a real run would keep on this server" : "kept on this server"))
                            + "."
                    : "LiteBans history could not be read; names will be filled in by Heimdall instead.");
        }
        if (rows.isEmpty()) {
            run.finish("nothing to import");
            return;
        }

        PunishmentImportRow.hashIps(rows, salt);
        sortForImport(rows);
        run.batches = (rows.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        run.found = rows.size();
        run.report("importing");
        for (int batch = 0; batch < run.batches; batch++) {
            List<PunishmentImportRow> slice = rows.subList(
                    batch * BATCH_SIZE, Math.min(rows.size(), (batch + 1) * BATCH_SIZE));
            Payload counts;
            try {
                counts = (dryRun
                        ? context.api().importPunishmentRows(slice, true, run.id)
                        : context.api().importPunishmentRows(slice))
                        .get(BATCH_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                run.fail("interrupted at batch " + (batch + 1) + " of " + run.batches);
                return;
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logger.error("punishment import batch " + (batch + 1) + " failed", cause);
                run.fail("batch " + (batch + 1) + " of " + run.batches + " failed ("
                        + cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                        + (dryRun
                                ? "). Nothing was written; run the preview again."
                                : "). Batches before it are in; run the import again to carry on, "
                                        + "rows already in are skipped."));
                return;
            }
            if (dryRun && (counts == null || !counts.bool("dryRun", false))) {
                // The run started against a bot that previews, and this answer is not a preview's:
                // the bot was redeployed or rolled back mid-run, or the guild moved instance. Such a
                // bot ignores the flag and imports, so stop before it is sent any more.
                logger.warn("punishment import preview stopped at batch " + (batch + 1)
                        + ": the bot did not answer it as a preview");
                run.fail("the bot did not answer batch " + (batch + 1) + " of " + run.batches
                        + " as a preview, so it may have been imported for real. Nothing after it"
                        + " was sent. Check the bot's version, then preview or import again.");
                return;
            }
            run.add(counts);
            run.batchesDone = batch + 1;
            progress.line("Batch " + run.batchesDone + "/" + run.batches + ": " + run.imported
                    + (dryRun ? " would be imported so far, " : " imported so far, ")
                    + run.skipped() + (dryRun ? " would be skipped." : " skipped."));
            run.report("importing");
        }
        run.finish(null);
    }

    private static String describeCounts(Map<String, Long> counts) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            if (entry.getValue().longValue() == 0L) continue;
            if (out.length() > 0) out.append(", ");
            out.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static Payload longs(Map<String, Long> counts) {
        Payload.Builder out = Payload.builder();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            out.put(entry.getKey(), entry.getValue().longValue());
        }
        return out.build();
    }

    /** Oldest first; undated rows last, because the bot dates them at arrival. Stable, so ties keep source order. */
    static void sortForImport(List<PunishmentImportRow> rows) {
        Collections.sort(rows, new Comparator<PunishmentImportRow>() {
            @Override
            public int compare(PunishmentImportRow a, PunishmentImportRow b) {
                boolean aDated = a.issuedAtMillis > 0;
                boolean bDated = b.issuedAtMillis > 0;
                if (aDated != bDated) return aDated ? -1 : 1;
                return Long.compare(a.issuedAtMillis, b.issuedAtMillis);
            }
        });
    }

    private static String describe(Map<String, Integer> sources) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> source : sources.entrySet()) {
            if (source.getValue().intValue() == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(source.getKey()).append(' ').append(source.getValue());
        }
        return out.length() == 0 ? "no source found" : out.toString();
    }

    static Path serverRoot(Path dataDirectory) {
        if (dataDirectory == null) return null;
        Path plugins = dataDirectory.getParent();
        if (plugins == null) return dataDirectory;
        Path root = plugins.getParent();
        return root == null ? plugins : root;
    }

    /**
     * Whether a failed report means the bot will never take one. A bot older than the route
     * answers 404, or 403 where the guild token's scope does not list the path yet; either way
     * every later report would be refused too, and logged on the bot each time.
     */
    static boolean refusesReports(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ApiError) && cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (!(cause instanceof ApiError)) return false;
        int status = ((ApiError) cause).httpStatus();
        return status == 403 || status == 404;
    }

    /** One run's running totals, and its reports to the bot. */
    private static final class Run {
        final ModuleContext context;
        final Progress progress;
        final String id = UUID.randomUUID().toString();
        final long startedAt = System.currentTimeMillis();
        final Map<String, Integer> sources = new LinkedHashMap<String, Integer>();
        final Map<String, Long> skippedBy = new LinkedHashMap<String, Long>();
        /** Bans, IP bans and mutes brought in that are still in force, by type. */
        final Map<String, Long> enforced = new LinkedHashMap<String, Long>();
        /** Where LiteBans and Heimdall disagreed: {@code lifted_to_match}, {@code heimdall_lifted}. */
        final Map<String, Long> reconciled = new LinkedHashMap<String, Long>();
        /** A preview: nothing is written, here or by the bot. */
        final boolean dryRun;
        int found;
        int unusable;
        int serverScoped;
        int historyNames = -1;
        int ipSightings = -1;
        int batches;
        int batchesDone;
        long imported;
        /** Whether the bot has said it does not know the report route, so stop asking. */
        volatile boolean reportsUnsupported;
        private long lastReportAt;

        Run(ModuleContext context, Progress progress, boolean dryRun) {
            this.context = context;
            this.progress = progress;
            this.dryRun = dryRun;
        }

        long skipped() {
            long total = 0L;
            for (Long n : skippedBy.values()) total += n.longValue();
            return total;
        }

        void add(Payload counts) {
            if (counts == null) return;
            imported += counts.longValue("imported", 0L);
            addAll(skippedBy, counts.child("skippedBy"));
            addAll(enforced, counts.child("enforced"));
            addAll(reconciled, counts.child("reconciled"));
        }

        private static void addAll(Map<String, Long> into, Payload by) {
            if (by == null) return;
            for (String key : by.keys()) {
                Long previous = into.get(key);
                into.put(key, Long.valueOf((previous == null ? 0L : previous.longValue())
                        + by.longValue(key, 0L)));
            }
        }

        void finish(String note) {
            StringBuilder line = new StringBuilder(dryRun ? "Preview finished: " : "Import finished: ")
                    .append(imported).append(dryRun ? " would be imported" : " imported");
            for (Map.Entry<String, Long> reason : skippedBy.entrySet()) {
                line.append(", ").append(reason.getValue()).append(' ').append(reason.getKey());
            }
            if (note != null) line.append(" (").append(note).append(')');
            progress.line(line.append('.').toString());
            if (!enforced.isEmpty()) {
                progress.line((dryRun ? "Would bring in " : "Brought in ") + describeCounts(enforced)
                        + " still in force, which Heimdall enforces once it replaces LiteBans.");
            }
            if (!reconciled.isEmpty()) {
                progress.line((dryRun ? "Would reconcile: " : "Reconciled: ") + describeCounts(reconciled)
                        + " (lifted_to_match: lifted in LiteBans, so lifted in Heimdall; "
                        + "heimdall_lifted: lifted in Heimdall and kept lifted).");
            }
            if (dryRun) {
                progress.line("Nothing was written. Run the import without --dry-run to bring it in.");
            }
            report("done");
        }

        void fail(String why) {
            progress.line((dryRun ? "Preview failed: " : "Import failed: ") + why);
            report("failed", why);
        }

        void report(String status) {
            report(status, null);
        }

        /** A "reading" report, at most every {@link #HEARTBEAT_MS}, while sources are read. */
        void heartbeat() {
            if (System.currentTimeMillis() - lastReportAt >= HEARTBEAT_MS) report("reading");
        }

        /** Best effort: the dashboard card reads these, the import never waits on or fails for one. */
        void report(String status, String error) {
            if (reportsUnsupported) return;
            lastReportAt = System.currentTimeMillis();
            Payload.Builder counts = Payload.builder();
            for (Map.Entry<String, Long> reason : skippedBy.entrySet()) {
                counts.put(reason.getKey(), reason.getValue().longValue());
            }
            Payload.Builder found = Payload.builder();
            for (Map.Entry<String, Integer> source : sources.entrySet()) {
                found.put(source.getKey(), source.getValue().intValue());
            }
            Payload.Builder body = Payload.builder()
                    .put("runId", id)
                    .put("status", status)
                    .put("startedAt", startedAt)
                    .put("found", this.found)
                    .put("sources", found.build())
                    .put("unusable", unusable)
                    .put("serverScoped", serverScoped)
                    .put("batches", batches)
                    .put("batchesDone", batchesDone)
                    .put("imported", imported)
                    .put("skippedBy", counts.build())
                    .put("enforced", longs(enforced))
                    .put("reconciled", longs(reconciled));
            if (dryRun) body.put("dryRun", true);
            if (historyNames >= 0) body.put("historyRows", historyNames);
            if (ipSightings >= 0) body.put("ipSightings", ipSightings);
            if ("done".equals(status) || "failed".equals(status)) {
                body.put("finishedAt", System.currentTimeMillis());
            }
            if (error != null) body.put("error", error);
            try {
                context.api().reportPunishmentImport(body.build()).whenComplete((ok, failure) -> {
                    if (failure != null) {
                        String message = String.valueOf(failure.getMessage());
                        if (refusesReports(failure)) reportsUnsupported = true;
                        context.logger().debug(() -> "import report not delivered: " + message);
                    }
                });
            } catch (RuntimeException e) {
                context.logger().debug(() -> "import report not delivered: " + e);
            }
        }
    }
}
