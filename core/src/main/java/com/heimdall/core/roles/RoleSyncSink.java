package com.heimdall.core.roles;

import com.heimdall.core.http.model.RoleSyncDirective;
import java.util.UUID;

/**
 * Whoever applies a role snapshot that arrived on a login response.
 *
 * <h2>Why this is in core rather than in either module</h2>
 *
 * <p>The bot returns a {@code roleSync} block on {@code connection-attempt}, so the module that
 * makes that call — whitelist — is the one holding the directive, and the module that knows what
 * LuckPerms is — rolesync — is the one that can apply it. Neither may depend on the other: they are
 * independently toggleable, and a compile edge between them would mean disabling one broke the
 * build of the other.
 *
 * <p>So the vocabulary lives here, beside {@link RoleSyncDirective}, which core already owns. It is
 * one method and a no-op constant, which is the whole of what the two modules have to agree on.
 *
 * <h2>The sink is always present, and may be inert</h2>
 *
 * <p>A whitelist module wired to {@link #NONE} — because rolesync is not installed, or is switched
 * off — must behave exactly as it does with a live one. That is why this is a no-op default rather
 * than a nullable reference: a null would put the same "is role sync available" branch at every
 * call site, and somebody would eventually forget it.
 *
 * <p>An implementation is reached while the rolesync module may itself be disabled, so deciding
 * whether to do anything is the implementation's job, not the caller's.
 *
 * <h2>Threading</h2>
 *
 * <p>Called from the login path — a platform's async pre-login thread — and from the fire-and-forget
 * report that rides a mirror hit, which completes on {@code heimdall-io}. Implementations must not
 * block the caller: the login path is a player waiting to join.
 */
public interface RoleSyncSink {

    /** Applies nothing. What a whitelist module runs against when role sync is not available. */
    RoleSyncSink NONE = new RoleSyncSink() {
        @Override
        public void applyOnJoin(UUID playerUuid, String username, RoleSyncDirective directive) {
        }
    };

    /**
     * Applies the snapshot the bot returned for a player who is joining.
     *
     * <p>The directive's three states are the implementation's to honour, and they are not the same:
     * absent means no snapshot exists yet, disabled means the bot is driving LuckPerms over RCON and
     * the plugin must keep out, and only the third carries groups. See departure D2.
     *
     * @param username the name as the platform reported it, for logging — never normalised (D8)
     */
    void applyOnJoin(UUID playerUuid, String username, RoleSyncDirective directive);
}
