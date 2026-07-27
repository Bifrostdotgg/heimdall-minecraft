package com.heimdall.platform.velocity;

import com.heimdall.core.platform.PlayerDirectory;
import com.heimdall.core.platform.PlayerHandle;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The proxy's connected-player list, as a {@link PlayerDirectory}.
 *
 * <p>"Online" on a proxy means connected to the <em>network</em>, not to any particular backend
 * server, which is the right scope for everything Heimdall does with it: a whitelist decision and a
 * role sync are about the person, and the proxy is the only place that sees all of them.
 *
 * <p>Velocity's own lookups already return {@link Optional} and are thread-safe, so this is close to
 * a straight adaptation. The copy in {@link #onlinePlayers()} is still worth making: Velocity's
 * collection is a live view of a concurrent map, and a caller iterating it while a player joins
 * would see a list that changed underneath it.
 */
final class VelocityPlayerDirectory implements PlayerDirectory {

    private final ProxyServer proxy;
    private final VelocityText text;

    VelocityPlayerDirectory(ProxyServer proxy, VelocityText text) {
        this.proxy = proxy;
        this.text = text;
    }

    @Override
    public Optional<PlayerHandle> byUuid(UUID uuid) {
        return uuid == null ? Optional.empty() : wrap(proxy.getPlayer(uuid));
    }

    @Override
    public Optional<PlayerHandle> byName(String username) {
        // Velocity matches this case-insensitively and exactly — no prefix matching, so a command
        // cannot resolve "ste" to the wrong Steve.
        return username == null || username.isEmpty()
                ? Optional.<PlayerHandle>empty()
                : wrap(proxy.getPlayer(username));
    }

    @Override
    public Collection<PlayerHandle> onlinePlayers() {
        Collection<Player> connected = proxy.getAllPlayers();
        List<PlayerHandle> handles = new ArrayList<>(connected.size());
        for (Player player : connected) {
            if (player != null) {
                handles.add(new VelocityPlayerHandle(player, text));
            }
        }
        return Collections.unmodifiableList(handles);
    }

    private Optional<PlayerHandle> wrap(Optional<Player> player) {
        return player.map(found -> (PlayerHandle) new VelocityPlayerHandle(found, text));
    }
}
