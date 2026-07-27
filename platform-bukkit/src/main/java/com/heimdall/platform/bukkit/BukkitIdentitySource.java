package com.heimdall.platform.bukkit;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.IdentitySource;
import com.heimdall.core.tunnel.ServerIdentity;
import org.bukkit.Bukkit;

/**
 * What a Bukkit-family server tells the bot about itself.
 *
 * <h2>The platform string is honest now, and stays compatible</h2>
 *
 * <p>v2 sent {@code platform: "paper"} from its Bukkit entry point unconditionally, including from
 * the plain Spigot and CraftBukkit servers a good share of the fleet actually runs. Support then
 * had a dashboard that said "Paper" for a server whose problem was that it was not Paper.
 *
 * <p>v3 sends what the server actually reports — {@code paper}, {@code spigot}, {@code purpur},
 * whatever {@code Bukkit.getName()} says, lower-cased — and adds {@code platformFamily}, which
 * carries exactly the value v2's {@code platform} did. Anything bot-side matching on the old
 * spelling keeps matching; anything that wants the truth can have it. Collapsing the two into one
 * field would mean choosing between breaking the bot and lying to it (departure D42).
 *
 * <h2>The Minecraft version comes from the API version string</h2>
 *
 * <p>{@code Server#getMinecraftVersion()} is Paper-only. {@code Bukkit.getBukkitVersion()} is on
 * every server since 1.0 and reads {@code 1.8.8-R0.1-SNAPSHOT}, so the part before the first
 * {@code -R} is the version — with no reflection and no per-generation special case.
 *
 * <p>Rebuilt on every connect rather than cached: a reconnect after a proxy came online is exactly
 * when a resolved role can legitimately have changed.
 */
final class BukkitIdentitySource implements IdentitySource {

    private final ServerRole role;
    private final long startedAtMs;

    BukkitIdentitySource(ServerRole role, long startedAtMs) {
        this.role = role;
        this.startedAtMs = startedAtMs;
    }

    @Override
    public ServerIdentity identity() {
        return ServerIdentity.builder()
                // Deliberately not set. Bukkit's own getServerName() is deprecated and has been
                // removed outright on modern Paper — calling it would be a NoSuchMethodError on
                // exactly the servers most of the fleet runs — and the handshake already falls back
                // to the serverId from bootstrap.yml, which is the name the operator chose anyway.
                .platform(platform())
                .serverSoftware(safe(Bukkit.getName()) + " " + safe(Bukkit.getVersion()))
                .mcVersion(minecraftVersion())
                .role(role)
                .startedAtMs(startedAtMs)
                .extra(Payload.builder()
                        // v2's value for `platform`, kept under its own name so nothing bot-side
                        // that matches on it has to change.
                        .put("platformFamily", "paper")
                        .build())
                .build();
    }

    /** {@code Bukkit.getName()} lower-cased — {@code paper}, {@code spigot}, {@code craftbukkit}. */
    private static String platform() {
        String name = safe(Bukkit.getName()).toLowerCase(java.util.Locale.ROOT);
        return name.isEmpty() ? "bukkit" : name;
    }

    private static String minecraftVersion() {
        String bukkitVersion = safe(Bukkit.getBukkitVersion());
        int suffix = bukkitVersion.indexOf("-R");
        return suffix > 0 ? bukkitVersion.substring(0, suffix) : bukkitVersion;
    }

    /**
     * Guards every Bukkit accessor against a null.
     *
     * <p>Identity is built on the socket's reading thread, where an exception costs the connection
     * its metadata for the life of that connection — so nothing here is allowed to be the first
     * thing that throws.
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
