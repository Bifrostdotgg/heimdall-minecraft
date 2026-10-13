package litebans.api;

import java.util.UUID;

/**
 * Compile-only stub of LiteBans 2.x {@code litebans.api.Database}. Not shipped.
 *
 * <p>Only the two lookups the revoke lift reads. The real class throws on the Bukkit main thread,
 * so callers query it from an IO thread.
 */
public abstract class Database {

    public static Database get() {
        throw new UnsupportedOperationException("stub");
    }

    public abstract boolean isPlayerBanned(UUID uuid, String ip);

    public abstract boolean isPlayerMuted(UUID uuid, String ip);
}
