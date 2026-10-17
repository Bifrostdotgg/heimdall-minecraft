package com.heimdall.platform.common;

import com.heimdall.core.util.Registration;
import com.heimdall.module.punishments.ActivePunishment;
import com.heimdall.module.punishments.HeimdallPunishmentsModule;
import com.heimdall.shell.contract.PunishmentBackend;
import com.heimdall.shell.contract.PunishmentRecord;
import com.heimdall.shell.contract.ShellContext;
import java.util.UUID;

/**
 * This core generation's punishment mirror, offered to the shell as the backend of the public
 * {@code HeimdallPunishments} API.
 *
 * <p>The counterpart of {@link TunnelSpiService}: the object other plugins hold is the shell's
 * ({@code ShellPunishments}), permanent across hot swaps, and this class only answers the two
 * questions it forwards. It lives here rather than in the module because this package is the one
 * place that already depends on both the feature modules and the shell contract (see
 * {@link HeimdallModules}).
 *
 * <p>Every answer is a read of the module's in-memory mirror ({@link
 * HeimdallPunishmentsModule#activeFor}); no call reaches the bot or the disk. A disabled module
 * answers unavailable and "not punished" (departure D89).
 */
final class PunishmentSpiService implements PunishmentBackend {

    private final HeimdallPunishmentsModule module;

    PunishmentSpiService(HeimdallPunishmentsModule module) {
        this.module = module;
    }

    /** Binds {@code module} as the shell's punishment backend; close the handle before the runtime. */
    static Registration install(HeimdallPunishmentsModule module, ShellContext shell) {
        if (module == null || shell == null) {
            return Registration.NONE;
        }
        return shell.bindPunishments(new PunishmentSpiService(module));
    }

    @Override
    public boolean isAvailable() {
        return module.isAvailable();
    }

    @Override
    public PunishmentRecord active(UUID player, String type) {
        ActivePunishment found = module.activeFor(type, player);
        return found == null ? null : record(found, player, type);
    }

    static PunishmentRecord record(ActivePunishment p, UUID player, String type) {
        Long expires = p.expiresAtMillis();
        return PunishmentRecord.builder(type, player)
                .id(p.id)
                .playerName(p.targetName)
                .reason(p.reason)
                .issuedAtMillis(p.issuedAtMillis())
                .expiresAtMillis(expires == null ? 0L : expires.longValue())
                .issuerName(p.issuedByName)
                .issuerUuid(uuidOrNull(p.issuedByUuid))
                .hidden(p.hidden)
                .silent(p.silent)
                .build();
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
