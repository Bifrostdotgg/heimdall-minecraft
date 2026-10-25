package com.heimdall.platform.common;

import com.heimdall.module.punishments.HeimdallPunishmentsModule;
import java.util.UUID;

/**
 * The proxy's half of a freeze: refusing the commands the proxy runs itself.
 *
 * <p>Here rather than in each proxy platform because this package is the one both proxies
 * already depend on that can see the punishments module. See
 * {@link HeimdallPunishmentsModule#refuseFrozenProxyCommand} for what is refused and why server
 * switches are not.
 */
public final class FrozenProxyCommands {

    private FrozenProxyCommands() {
    }

    /**
     * Whether a proxy-owned command from this player is refused because they are frozen. The
     * player has been told when it is. False when the punishments module is not running, so a
     * proxy without it behaves exactly as before.
     */
    public static boolean refuse(UUID player) {
        HeimdallPunishmentsModule module = HeimdallPunishmentsModule.INSTANCE;
        return module != null && player != null && module.refuseFrozenProxyCommand(player);
    }
}
