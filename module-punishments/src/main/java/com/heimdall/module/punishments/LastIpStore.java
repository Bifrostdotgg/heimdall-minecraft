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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gatekeeper/standalone last-IP file. Raw addresses stay on this box; enforcer backends never
 * open one.
 */
final class LastIpStore {

    static final int HISTORY_LIMIT = 20;

    static final class Sighting {
        final String ip;
        final long seenAt;

        Sighting(String ip, long seenAt) {
            this.ip = ip;
            this.seenAt = seenAt;
        }
    }

    static final class PlayerIps {
        String uuid;
        String name;
        String lastIp;
        long seenAt;
        final List<Sighting> history = new ArrayList<Sighting>();
    }

    private final HeimdallLogger logger;
    private final Path path;
    private final Map<String, PlayerIps> byUuid = new LinkedHashMap<String, PlayerIps>();
    private boolean dirty;

    LastIpStore(HeimdallLogger logger, Path path) {
        this.logger = logger;
        this.path = path;
        load();
    }

    synchronized void record(String uuid, String name, String ip, long nowMillis) {
        if (uuid == null || uuid.isEmpty() || ip == null || ip.isEmpty()) {
            return;
        }
        String key = uuid.toLowerCase(Locale.ROOT);
        PlayerIps row = byUuid.get(key);
        if (row == null) {
            row = new PlayerIps();
            row.uuid = uuid;
            byUuid.put(key, row);
        }
        row.name = name == null ? row.name : name;
        row.lastIp = ip;
        row.seenAt = nowMillis;
        if (row.history.isEmpty()
                || !ip.equals(row.history.get(row.history.size() - 1).ip)) {
            row.history.add(new Sighting(ip, nowMillis));
            while (row.history.size() > HISTORY_LIMIT) {
                row.history.remove(0);
            }
        } else {
            Sighting last = row.history.get(row.history.size() - 1);
            row.history.set(row.history.size() - 1, new Sighting(last.ip, nowMillis));
        }
        dirty = true;
    }

    synchronized PlayerIps get(String uuid) {
        if (uuid == null) {
            return null;
        }
        return byUuid.get(uuid.toLowerCase(Locale.ROOT));
    }

    synchronized PlayerIps byName(String name) {
        if (name == null) {
            return null;
        }
        for (PlayerIps row : byUuid.values()) {
            if (name.equalsIgnoreCase(row.name)) {
                return row;
            }
        }
        return null;
    }

    synchronized List<PlayerIps> sharing(String ip) {
        if (ip == null || ip.isEmpty()) {
            return Collections.emptyList();
        }
        List<PlayerIps> out = new ArrayList<PlayerIps>();
        for (PlayerIps row : byUuid.values()) {
            if (ip.equals(row.lastIp)) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * The other accounts that share this player's last address, the target itself removed.
     *
     * <p>The matching is deliberately {@link #sharing}'s and nothing else: this is what backs both
     * the in-game {@code /dupeip} and the bot's {@code dupeip.query}, and two answers to the same
     * question that disagree is worse than one answer that is narrow. Narrow it is - a row only
     * matches on the address a player was last seen on, so an alt that has since moved to another
     * connection is not found. Widening it to the whole history is a change to make in one place,
     * for both callers at once.
     *
     * <p>Keyed by uuid, so the result is already deduplicated.
     *
     * @return rows to read {@code uuid}, {@code name} and {@code seenAt} from. Never the address:
     *     no caller outside this file is allowed to put one on a wire.
     */
    synchronized List<PlayerIps> altsOf(String uuid) {
        PlayerIps target = get(uuid);
        if (target == null || target.lastIp == null || target.lastIp.isEmpty()) {
            return Collections.emptyList();
        }
        String self = target.uuid == null ? "" : target.uuid.toLowerCase(Locale.ROOT);
        List<PlayerIps> out = new ArrayList<PlayerIps>();
        List<PlayerIps> candidates = sharing(target.lastIp);
        for (int i = 0; i < candidates.size(); i++) {
            PlayerIps candidate = candidates.get(i);
            String key = candidate.uuid == null ? "" : candidate.uuid.toLowerCase(Locale.ROOT);
            if (key.equals(self)) {
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    synchronized void flush() {
        if (!dirty) {
            return;
        }
        save();
        dirty = false;
    }

    /**
     * A form that is still unique enough to compare, without a screenshot of a full address.
     * IPv4 keeps the first three octets; IPv6 keeps the first two groups.
     */
    static String obfuscate(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "";
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0 && ip.indexOf(':') < 0) {
            return ip.substring(0, lastDot + 1) + "*";
        }
        int colon = indexOfNth(ip, ':', 2);
        if (colon > 0) {
            return ip.substring(0, colon + 1) + "*";
        }
        return "*";
    }

    private static int indexOfNth(String text, char needle, int n) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == needle) {
                found++;
                if (found == n) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Payload root = Payload.parse(json);
            for (Payload row : root.children("players")) {
                String uuid = row.string("uuid", "");
                if (uuid.isEmpty()) {
                    continue;
                }
                PlayerIps player = new PlayerIps();
                player.uuid = uuid;
                player.name = row.string("name", "");
                player.lastIp = row.string("ip", "");
                player.seenAt = row.longValue("seenAt", 0L);
                for (Payload sight : row.children("history")) {
                    String ip = sight.string("ip", "");
                    if (!ip.isEmpty()) {
                        player.history.add(new Sighting(ip, sight.longValue("seenAt", 0L)));
                    }
                }
                byUuid.put(uuid.toLowerCase(Locale.ROOT), player);
            }
        } catch (IOException e) {
            logger.error("Could not read last-IP file", e);
        } catch (RuntimeException e) {
            logger.error("Could not parse last-IP file", e);
        }
    }

    private void save() {
        List<Payload> players = new ArrayList<Payload>(byUuid.size());
        for (PlayerIps player : byUuid.values()) {
            List<Payload> history = new ArrayList<Payload>(player.history.size());
            for (int i = 0; i < player.history.size(); i++) {
                Sighting sight = player.history.get(i);
                history.add(Payload.builder()
                        .put("ip", sight.ip)
                        .put("seenAt", sight.seenAt)
                        .build());
            }
            players.add(Payload.builder()
                    .put("uuid", player.uuid)
                    .put("name", player.name == null ? "" : player.name)
                    .put("ip", player.lastIp == null ? "" : player.lastIp)
                    .put("seenAt", player.seenAt)
                    .putChildren("history", history)
                    .build());
        }
        try {
            AtomicFiles.writeUtf8(path, Payload.builder().putChildren("players", players).build().toJson());
        } catch (IOException e) {
            logger.error("Could not write last-IP file", e);
        }
    }
}
