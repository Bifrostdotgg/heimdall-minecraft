package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Durable issue/revoke queue. Local mirror is applied first; these rows upload when the bot is
 * reachable again. Duplicate {@code opId} is skipped. Order is {@code issuedAt}, then
 * {@code serverId}.
 */
final class PunishmentOutbox {

    static final class Entry {
        final String opId;
        final long issuedAt;
        final String serverId;
        final String op;
        final Payload payload;

        Entry(String opId, long issuedAt, String serverId, String op, Payload payload) {
            this.opId = opId;
            this.issuedAt = issuedAt;
            this.serverId = serverId == null ? "" : serverId;
            this.op = op;
            this.payload = payload == null ? Payload.empty() : payload;
        }
    }

    private static final Comparator<Entry> ORDER = new Comparator<Entry>() {
        @Override
        public int compare(Entry left, Entry right) {
            if (left.issuedAt != right.issuedAt) {
                return left.issuedAt < right.issuedAt ? -1 : 1;
            }
            return left.serverId.compareTo(right.serverId);
        }
    };

    private final HeimdallLogger logger;
    private final Path path;
    private final List<Entry> entries = new ArrayList<Entry>();

    PunishmentOutbox(HeimdallLogger logger, Path path) {
        this.logger = logger;
        this.path = path;
        load();
    }

    synchronized boolean enqueue(Entry entry) {
        if (entry == null || entry.opId == null || entry.opId.isEmpty()) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entry.opId.equals(entries.get(i).opId)) {
                return false;
            }
        }
        entries.add(entry);
        save();
        return true;
    }

    synchronized void remove(String opId) {
        if (opId == null) {
            return;
        }
        boolean changed = false;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            if (opId.equals(it.next().opId)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    synchronized List<Entry> snapshot() {
        List<Entry> copy = new ArrayList<Entry>(entries);
        Collections.sort(copy, ORDER);
        return copy;
    }

    synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    synchronized int size() {
        return entries.size();
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Payload root = Payload.parse(json);
            for (Payload row : root.children("entries")) {
                String opId = row.string("opId", "");
                if (opId.isEmpty()) {
                    continue;
                }
                entries.add(new Entry(
                        opId,
                        row.longValue("issuedAt", 0L),
                        row.string("serverId", ""),
                        row.string("op", ""),
                        row.child("payload")));
            }
        } catch (IOException e) {
            logger.error("Could not read punishment outbox", e);
        } catch (RuntimeException e) {
            logger.error("Could not parse punishment outbox", e);
        }
    }

    private void save() {
        List<Payload> rows = new ArrayList<Payload>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            rows.add(Payload.builder()
                    .put("opId", entry.opId)
                    .put("issuedAt", entry.issuedAt)
                    .put("serverId", entry.serverId)
                    .put("op", entry.op)
                    .put("payload", entry.payload)
                    .build());
        }
        String json = Payload.builder().putChildren("entries", rows).build().toJson();
        try {
            AtomicFiles.writeUtf8(path, json);
        } catch (IOException e) {
            logger.error("Could not write punishment outbox", e);
        }
    }
}
