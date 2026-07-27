package com.heimdall.stubbot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stub's mutable world: which players exist and what the whitelist currently contains.
 *
 * <p>Kept separate from the two servers so a test can mutate it mid-run — revoke a player, add one,
 * and watch the whitelist-sync ETag change — without restarting anything.
 */
public final class FixtureStore {

    private final Map<String, PlayerFixture> byUuid = new ConcurrentHashMap<>();
    private volatile Outcome defaultOutcome;

    public FixtureStore(Outcome defaultOutcome) {
        this.defaultOutcome = defaultOutcome;
    }

    /** The outcome served for a UUID that has no fixture. */
    public Outcome defaultOutcome() {
        return defaultOutcome;
    }

    public void setDefaultOutcome(Outcome outcome) {
        this.defaultOutcome = outcome;
    }

    public void put(PlayerFixture fixture) {
        fixture.validate();
        byUuid.put(normalise(fixture.uuid()), fixture);
    }

    public void putAll(Collection<PlayerFixture> fixtures) {
        for (PlayerFixture fixture : fixtures) {
            put(fixture);
        }
    }

    public void remove(String uuid) {
        byUuid.remove(normalise(uuid));
    }

    public void clear() {
        byUuid.clear();
    }

    /** The fixture for a UUID, or null if none is configured. */
    public PlayerFixture find(String uuid) {
        return uuid == null ? null : byUuid.get(normalise(uuid));
    }

    /**
     * The UUIDs currently on the whitelist, in insertion-independent order.
     *
     * <p>"Whitelisted" is derived from the outcome rather than tracked separately, so there is
     * exactly one place to change a player's state and the two endpoints can never disagree — which
     * is precisely the bug class ({@code connection-attempt} says yes, {@code whitelist/sync} says
     * no) that the pre-warm cache would otherwise hide until a bot restart.
     */
    public List<String> whitelistedUuids() {
        List<String> uuids = new ArrayList<>();
        for (PlayerFixture fixture : byUuid.values()) {
            if (fixture.outcome().isWhitelisted()) {
                uuids.add(fixture.uuid());
            }
        }
        return uuids;
    }

    /** The whitelisted players, as {@code {uuid, username}} pairs for the sync payload. */
    public List<PlayerFixture> whitelistedPlayers() {
        List<PlayerFixture> players = new ArrayList<>();
        for (PlayerFixture fixture : byUuid.values()) {
            if (fixture.outcome().isWhitelisted()) {
                players.add(fixture);
            }
        }
        return players;
    }

    /**
     * The whitelist-sync ETag: SHA-1 over the sorted UUIDs, each followed by a newline.
     *
     * <p>Transcribed from {@code computeHash} in the bot's {@code api/whitelistSync.ts}. Sorting is
     * what makes it order-independent, so it changes only when membership actually changes — the
     * property the plugin's cheap-poll loop depends on.
     */
    public static String etag(Collection<String> uuids) {
        List<String> sorted = new ArrayList<>(uuids);
        sorted.sort(null);
        StringBuilder joined = new StringBuilder();
        for (String uuid : sorted) {
            joined.append(uuid).append('\n');
        }
        return Hmac.sha1Hex(joined.toString());
    }

    /** The current whitelist ETag. */
    public String currentEtag() {
        return etag(whitelistedUuids());
    }

    private static String normalise(String uuid) {
        return uuid.trim().toLowerCase(Locale.ROOT);
    }
}
