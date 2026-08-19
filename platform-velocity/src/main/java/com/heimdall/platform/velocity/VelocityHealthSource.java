package com.heimdall.platform.velocity;

import com.heimdall.core.json.Payload;
import com.heimdall.core.tunnel.HealthSnapshotSource;
import com.heimdall.platform.common.JvmHealth;
import com.heimdall.platform.common.StatusHealth;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.util.Favicon;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * The proxy's periodic health snapshot: player count, memory, MOTD and favicon.
 *
 * <p><strong>No TPS and no MSPT, ever.</strong> A proxy has no tick loop - there is nothing to
 * measure - so those fields are omitted rather than sent as zero or as some invented figure.
 * {@link HealthSnapshotSource} returns a {@link Payload} rather than a typed record precisely so
 * this is expressible: a dashboard that charted a healthy proxy at 0 TPS would be indistinguishable
 * from one showing a real outage.
 *
 * <p>{@code maxPlayers} comes from the proxy's configured slot count, which is what a player sees
 * in the server list - the backends' own limits are separate numbers and each backend reports its.
 *
 * <p>MOTD is the proxy's Adventure {@code Component}. It is read and serialised through
 * reflection so Shadow's relocation of Heimdall's Adventure cannot rewrite the call onto a type
 * Velocity does not have (the same constraint {@link VelocityText} exists for).
 */
final class VelocityHealthSource implements HealthSnapshotSource {

    /**
     * Fragments, joined at runtime. Shadow remaps string constants that match
     * {@code net.kyori.adventure}, including the descriptor of a direct {@code getMotd()} call.
     */
    private static final String[] ADVENTURE = {"net", "kyori", "adventure"};

    private static final Method GET_MOTD = resolveGetMotd();

    private final ProxyServer proxy;

    VelocityHealthSource(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Override
    public Payload snapshot() {
        Payload.Builder builder = Payload.builder();
        try {
            builder.put("onlinePlayers", proxy.getPlayerCount());
            int slots = proxy.getConfiguration().getShowMaxPlayers();
            if (slots > 0) {
                builder.put("maxPlayers", slots);
            }
        } catch (RuntimeException notReady) {
            // Called on heimdall-ws every heartbeat. Throwing costs the tick both its health and
            // its liveness refresh, so the counts are dropped and the beat still goes.
        }

        String motdClean = "";
        String motdRaw = "";
        byte[] iconPng = null;
        try {
            ProxyConfig config = proxy.getConfiguration();
            String[] motd = motdOf(config);
            motdClean = motd[0];
            motdRaw = motd[1];
            iconPng = faviconPng(config);
        } catch (RuntimeException unread) {
            // MOTD and favicon fail independently of the counts. motdClean stays empty.
        }

        return JvmHealth.memory(StatusHealth.apply(builder, motdClean, motdRaw, iconPng)).build();
    }

    private static byte[] faviconPng(ProxyConfig config) {
        if (config == null) {
            return null;
        }
        Optional<Favicon> favicon = config.getFavicon();
        if (favicon == null || !favicon.isPresent()) {
            return null;
        }
        return StatusHealth.decodeIcon(favicon.get().getBase64Url());
    }

    /**
     * {@code [motdClean, motdRaw]}. Clean comes from the server's
     * {@code PlainTextComponentSerializer}; raw from {@code LegacyComponentSerializer}. Either
     * half may be empty when that serializer is missing.
     */
    private static String[] motdOf(Object config) {
        if (config == null || GET_MOTD == null) {
            return new String[] {"", ""};
        }
        try {
            Object component = GET_MOTD.invoke(config);
            if (component == null) {
                return new String[] {"", ""};
            }
            String clean = serialize(
                    component, "plain", "PlainTextComponentSerializer", "plainText");
            String raw = serialize(
                    component, "legacy", "LegacyComponentSerializer", "legacySection");
            if (clean == null && raw != null) {
                clean = StatusHealth.clean(raw);
            }
            return new String[] {clean == null ? "" : clean, raw == null ? "" : raw};
        } catch (Throwable failed) {
            return new String[] {"", ""};
        }
    }

    private static String serialize(
            Object component, String serializerPackage, String serializerClass, String factory) {
        try {
            ClassLoader loader = component.getClass().getClassLoader();
            if (loader == null) {
                loader = GET_MOTD.getReturnType().getClassLoader();
            }
            Class<?> componentType = GET_MOTD.getReturnType();
            Class<?> type = Class.forName(
                    adventureName("text", "serializer", serializerPackage, serializerClass),
                    true,
                    loader);
            Object serializer = type.getMethod(factory).invoke(null);
            Object text = type.getMethod("serialize", componentType).invoke(serializer, component);
            return text == null ? null : text.toString();
        } catch (Throwable failed) {
            return null;
        }
    }

    private static Method resolveGetMotd() {
        try {
            return ProxyConfig.class.getMethod("getMotd");
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static String adventureName(String... parts) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < ADVENTURE.length; i++) {
            if (i > 0) {
                name.append('.');
            }
            name.append(ADVENTURE[i]);
        }
        for (int i = 0; i < parts.length; i++) {
            name.append('.').append(parts[i]);
        }
        return name.toString();
    }
}
