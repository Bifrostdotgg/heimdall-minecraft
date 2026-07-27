package com.heimdall.core.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.heimdall.core.http.model.ConnectionAction;
import com.heimdall.core.http.model.ConnectionAttemptResult;
import com.heimdall.core.http.model.LinkCodeResult;
import com.heimdall.core.http.model.OffenseResult;
import com.heimdall.core.http.model.OffenseType;
import com.heimdall.core.http.model.PluginRelease;
import com.heimdall.core.http.model.RoleSyncDirective;
import com.heimdall.core.http.model.WhitelistSyncEntry;
import com.heimdall.core.http.model.WhitelistSyncResult;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the bot's JSON into the immutable models.
 *
 * <p>Kept out of {@link ApiClient} so the client is a list of endpoints and this is a list of wire
 * shapes; a field the bot renames is then a one-line change here rather than an edit inside a
 * method that also builds a request and manages a future.
 */
final class ApiResponses {

    private ApiResponses() {
    }

    /**
     * The connection-attempt verdict, including the derivation of {@link ConnectionAction} from the
     * booleans the bot actually sends.
     *
     * <p>The order of the branches is the contract and is transcribed from v2's
     * {@code parseWhitelistResponse}:
     *
     * <ol>
     *   <li>{@code whitelisted && !existingPlayerLink} → allow, and the message is <em>dropped</em>
     *       — it is a welcome-back line, not a kick reason, and v2 nulled it here for that reason.
     *   <li>{@code existingPlayerLink} → they are let in <em>and</em> shown a code. Note this comes
     *       before the plain {@code pendingAuth} branch and carries {@code whitelisted: true},
     *       which is the combination that looks contradictory until you know it means "on the
     *       server whitelist, not yet linked to Discord".
     *   <li>{@code pendingAuth} → show the code, do not let them in.
     *   <li>{@code pendingApproval} → linked, waiting.
     *   <li>otherwise → deny.
     * </ol>
     */
    static ConnectionAttemptResult connectionAttempt(RawResponse response) {
        JsonObject data = Envelopes.unwrapObject(response.status(), response.body());

        boolean whitelisted = bool(data, "whitelisted");
        boolean pendingAuth = bool(data, "pendingAuth");
        boolean pendingApproval = bool(data, "pendingApproval");
        boolean existingPlayerLink = bool(data, "existingPlayerLink");
        boolean revoked = bool(data, "revoked");
        String message = string(data, "message");

        ConnectionAction action;
        if (whitelisted && !existingPlayerLink) {
            action = ConnectionAction.ALLOW;
            message = null;
        } else if (existingPlayerLink) {
            action = ConnectionAction.SHOW_AUTH_CODE;
        } else if (pendingAuth) {
            action = ConnectionAction.SHOW_AUTH_CODE;
        } else if (pendingApproval) {
            action = ConnectionAction.PENDING_APPROVAL;
        } else {
            action = ConnectionAction.DENY;
        }

        return ConnectionAttemptResult.builder()
                .whitelisted(whitelisted)
                .action(action)
                .message(message)
                .authCode(string(data, "authCode"))
                .queuePosition(integer(data, "queuePosition"))
                .revoked(revoked)
                .roleSync(roleSync(data))
                .build();
    }

    /**
     * The three shapes of {@code roleSync}.
     *
     * <p>Absent and explicit-null are the same thing (no snapshot); {@code {enabled: false}} is a
     * different thing (the bot is driving groups itself). See {@link RoleSyncDirective}.
     */
    private static RoleSyncDirective roleSync(JsonObject data) {
        JsonElement element = data.get("roleSync");
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return RoleSyncDirective.absent();
        }
        JsonObject object = element.getAsJsonObject();
        if (!bool(object, "enabled")) {
            return RoleSyncDirective.disabled();
        }
        return RoleSyncDirective.enabled(strings(object, "targetGroups"), strings(object, "managedGroups"));
    }

    static LinkCodeResult linkCode(RawResponse response) {
        JsonObject data = Envelopes.unwrapObject(response.status(), response.body());
        if (bool(data, "alreadyLinked")) {
            return LinkCodeResult.linkedTo()
                    .message(string(data, "message"))
                    .discordId(string(data, "discordId"))
                    .discordUsername(string(data, "discordUsername"))
                    .discordDisplayName(string(data, "discordDisplayName"))
                    .build();
        }
        return LinkCodeResult.code(string(data, "code"));
    }

    static List<OffenseType> offenseTypes(RawResponse response) {
        JsonArray types = Envelopes.unwrapArray(response.status(), response.body());
        List<OffenseType> result = new ArrayList<OffenseType>(types.size());
        for (int i = 0; i < types.size(); i++) {
            if (!types.get(i).isJsonObject()) {
                continue;
            }
            JsonObject type = types.get(i).getAsJsonObject();
            result.add(OffenseType.builder()
                    .typeId(string(type, "typeId"))
                    .displayName(string(type, "displayName"))
                    .description(string(type, "description"))
                    .offenses(strings(type, "offenses"))
                    .enabled(bool(type, "enabled"))
                    .build());
        }
        return result;
    }

    static OffenseResult offense(RawResponse response) {
        JsonObject data = Envelopes.unwrapObject(response.status(), response.body());

        String infractionId = "";
        JsonElement infraction = data.get("infraction");
        if (infraction != null && infraction.isJsonObject()) {
            infractionId = string(infraction.getAsJsonObject(), "_id");
        }

        return OffenseResult.builder()
                .infractionId(infractionId)
                .command(string(data, "command"))
                .action(string(data, "action"))
                .durationMinutes(integer(data, "duration"))
                .totalPoints(intOr(data, "totalPoints", 0))
                .tierApplied(intOr(data, "tierApplied", 0))
                .tierDescription(string(data, "tierDescription"))
                .offenseType(string(data, "offenseType"))
                .build();
    }

    static PluginRelease pluginRelease(RawResponse response) {
        JsonObject data = Envelopes.unwrapObject(response.status(), response.body());
        return PluginRelease.builder()
                .version(string(data, "version"))
                .downloadUrl(string(data, "downloadUrl"))
                .releaseNotes(string(data, "releaseNotes"))
                .htmlUrl(string(data, "htmlUrl"))
                .publishedAt(string(data, "publishedAt"))
                .build();
    }

    static WhitelistSyncResult whitelistSync(RawResponse response) {
        if (response.status() == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return WhitelistSyncResult.notModified(response.etag());
        }
        JsonObject data = Envelopes.unwrapObject(response.status(), response.body());
        List<WhitelistSyncEntry> players = new ArrayList<WhitelistSyncEntry>();
        JsonElement raw = data.get("players");
        if (raw != null && raw.isJsonArray()) {
            JsonArray array = raw.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                if (!array.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject player = array.get(i).getAsJsonObject();
                String uuid = string(player, "uuid");
                if (uuid == null || uuid.trim().isEmpty()) {
                    // A whitelist entry with no UUID cannot be matched against a joining player, so
                    // keeping it would only inflate the count.
                    continue;
                }
                players.add(new WhitelistSyncEntry(uuid, string(player, "username")));
            }
        }
        return WhitelistSyncResult.modified()
                .etag(response.etag())
                .hash(string(data, "hash"))
                .count(intOr(data, "count", players.size()))
                .generatedAt(string(data, "generatedAt"))
                .players(players)
                .build();
    }

    // ── JSON readers ─────────────────────────────────────────────────────────
    //
    // All null-tolerant: the bot serialises explicit nulls rather than omitting keys, so
    // `has(key)` alone is never a sufficient guard.

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** A nullable integer: {@code null} means the key was absent or explicitly null, not zero. */
    private static Integer integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        return Integer.valueOf(value.getAsInt());
    }

    private static int intOr(JsonObject object, String key, int fallback) {
        Integer value = integer(object, key);
        return value == null ? fallback : value.intValue();
    }

    private static List<String> strings(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return new ArrayList<String>();
        }
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<String>(array.size());
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonNull()) {
                result.add(array.get(i).getAsString());
            }
        }
        return result;
    }
}
