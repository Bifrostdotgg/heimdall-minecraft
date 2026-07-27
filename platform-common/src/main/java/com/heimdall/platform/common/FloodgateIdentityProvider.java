package com.heimdall.platform.common;

import com.heimdall.core.http.BedrockIdentity;
import com.heimdall.core.http.BedrockIdentityProvider;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Resolves a Bedrock player's real gamertag and XUID through Floodgate, reflectively.
 *
 * <p>No compile-time and no run-time dependency on Floodgate: most servers do not have it, and a
 * hard reference would mean the plugin failed to load on every one of them. Reflection is also why
 * this lives in a platform module rather than in core — it is invisible to the ArchUnit conformance
 * rules, so confining it here is the only way "core is platform-free" stays a checkable claim
 * (departure D9).
 *
 * <h2>Why explicit fields rather than letting the bot infer</h2>
 *
 * <p>Floodgate rewrites Bedrock usernames with a configurable prefix (default {@code .}) and hands
 * the server a synthetic UUID. The bot can infer "this is Bedrock" from the UUID shape and strip its
 * <em>configured</em> prefix — but a server running a non-default prefix that the dashboard does not
 * mirror breaks that inference silently, and the symptom is a Bedrock player who can never be
 * matched to their Discord link. Sending the prefix-free gamertag and the XUID makes the match
 * robust regardless of how the server has Floodgate configured.
 *
 * <h2>Tolerance</h2>
 *
 * <p>Every step is guarded and every failure resolves to {@code null}, which the API client reads as
 * "an ordinary Java player". That is the correct fallback: a Bedrock player misread as Java still
 * gets a whitelist decision, while a thrown exception on the login path would not.
 *
 * <p>The availability probe is cached because it answers a question about the classpath, which
 * cannot change while the JVM is running. The <em>API instance</em> is not cached — it is fetched
 * per call, so a Floodgate that started after Heimdall still works.
 */
public final class FloodgateIdentityProvider implements BedrockIdentityProvider {

    private static final String API_CLASS = "org.geysermc.floodgate.api.FloodgateApi";

    /** Tri-state: null = not asked yet. Answers a classpath question, so it cannot go stale. */
    private static volatile Boolean present;

    /** Whether Floodgate's API class is on the classpath at all. */
    public static boolean isPresent() {
        Boolean cached = present;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean found;
        try {
            Class.forName(API_CLASS);
            found = true;
        } catch (Throwable absent) {
            found = false;
        }
        present = Boolean.valueOf(found);
        return found;
    }

    /**
     * A provider for this server.
     *
     * @return a real provider when Floodgate is installed, {@link BedrockIdentityProvider#NONE}
     *     otherwise — so callers never branch on availability
     */
    public static BedrockIdentityProvider create() {
        return isPresent() ? new FloodgateIdentityProvider() : BedrockIdentityProvider.NONE;
    }

    private FloodgateIdentityProvider() {
    }

    @Override
    public BedrockIdentity resolve(String uuidString) {
        if (uuidString == null || uuidString.isEmpty() || !isPresent()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(uuidString);
            Class<?> apiClass = Class.forName(API_CLASS);
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return null;
            }
            Object isFloodgate = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
            if (!Boolean.TRUE.equals(isFloodgate)) {
                return null;
            }
            Object player = apiClass.getMethod("getPlayer", UUID.class).invoke(api, uuid);
            if (player == null) {
                return null;
            }
            // getJavaUsername() is the Java-safe gamertag WITHOUT Floodgate's configured prefix,
            // which is exactly the value the bot can match against a Discord link.
            Object gamertag = player.getClass().getMethod("getJavaUsername").invoke(player);
            if (gamertag == null || gamertag.toString().trim().isEmpty()) {
                return null;
            }
            return new BedrockIdentity(gamertag.toString(), xuidOf(player));
        } catch (Throwable unusable) {
            return null;
        }
    }

    /** The XUID is a bonus — the gamertag alone is enough to match, so its absence is not a failure. */
    private static String xuidOf(Object player) {
        try {
            Method method = player.getClass().getMethod("getXuid");
            Object xuid = method.invoke(player);
            return xuid == null ? null : xuid.toString();
        } catch (Throwable notThere) {
            return null;
        }
    }
}
