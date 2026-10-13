package com.heimdall.module.punishments;

import java.util.UUID;
import litebans.api.Database;

/**
 * Asks LiteBans whether it still enforces a ban or a mute on a player.
 *
 * <p>Loaded only after {@code litebans.api.Events} is known to exist, like
 * {@link LiteBansEventBridge}, so the module still loads when LiteBans is absent. Reached through
 * {@link LiteBansRevokeLift.ActiveCheck} so nothing else in the module names a LiteBans type.
 *
 * <p>Must not be called on the Bukkit main thread: LiteBans refuses a query there.
 */
final class LiteBansActiveCheck implements LiteBansRevokeLift.ActiveCheck {

    @Override
    public boolean isActive(LiteBansRevokeLift.Family family, UUID uuid) {
        Database database = Database.get();
        return family == LiteBansRevokeLift.Family.MUTE
                ? database.isPlayerMuted(uuid, null)
                : database.isPlayerBanned(uuid, null);
    }
}
