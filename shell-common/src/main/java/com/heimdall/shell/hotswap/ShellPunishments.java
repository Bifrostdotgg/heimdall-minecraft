package com.heimdall.shell.hotswap;

import com.heimdall.api.HeimdallPunishment;
import com.heimdall.api.HeimdallPunishments;
import com.heimdall.core.util.Registration;
import com.heimdall.shell.contract.PunishmentBackend;
import com.heimdall.shell.contract.PunishmentRecord;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@link HeimdallPunishments} other plugins hold: permanent, and forwarding to whichever core
 * is running.
 *
 * <p>Installed once, at shell enable, into {@code HeimdallPunishmentsProvider} and (on Bukkit) the
 * {@code ServicesManager}, and never replaced, the way {@link ShellTunnel} is. See
 * {@link PunishmentBackend} for why the object a plugin holds must not be a core's.
 *
 * <p>With no backend bound, and whenever a backend misbehaves, every query answers "not punished".
 * That is the documented answer for "no core", and a consumer's chat thread must never see an
 * exception from here.
 */
public final class ShellPunishments implements HeimdallPunishments {

    private final ShellLog log;
    private final AtomicReference<PunishmentBackend> backend =
            new AtomicReference<PunishmentBackend>();

    /** Set once a broken backend has been logged, so a chat flood logs it once. */
    private volatile boolean warned;

    public ShellPunishments(ShellLog log) {
        this.log = log;
    }

    /** Makes {@code next} the backend; closing the handle clears it if it is still the one bound. */
    public Registration bind(final PunishmentBackend next) {
        if (next == null) {
            return Registration.NONE;
        }
        backend.set(next);
        warned = false;
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                backend.compareAndSet(next, null);
            }
        });
    }

    /** Clears the backend if its class belongs to {@code retired}; answers whether it did. */
    boolean sweep(LoadedCore retired) {
        PunishmentBackend current = backend.get();
        return current != null && retired.owns(current.getClass())
                && backend.compareAndSet(current, null);
    }

    /** Whether a core has bound a backend. */
    boolean hasBackend() {
        return backend.get() != null;
    }

    @Override
    public boolean isAvailable() {
        PunishmentBackend current = backend.get();
        if (current == null) {
            return false;
        }
        try {
            return current.isAvailable();
        } catch (Throwable broken) {
            complain(broken);
            return false;
        }
    }

    @Override
    public boolean isMuted(UUID player) {
        return record(player, TYPE_MUTE) != null;
    }

    @Override
    public boolean isMuteHidden(UUID player) {
        PunishmentRecord mute = record(player, TYPE_MUTE);
        return mute != null && mute.hidden();
    }

    @Override
    public boolean isBanned(UUID player) {
        return record(player, TYPE_BAN) != null;
    }

    @Override
    public boolean isFrozen(UUID player) {
        return record(player, TYPE_FREEZE) != null;
    }

    @Override
    public HeimdallPunishment activePunishment(UUID player, String type) {
        PunishmentRecord found = record(player, type);
        return found == null ? null : new Snapshot(found);
    }

    private PunishmentRecord record(UUID player, String type) {
        if (player == null || !known(type)) {
            return null;
        }
        PunishmentBackend current = backend.get();
        if (current == null) {
            return null;
        }
        try {
            return current.active(player, type);
        } catch (Throwable broken) {
            complain(broken);
            return null;
        }
    }

    private static boolean known(String type) {
        return TYPE_MUTE.equals(type) || TYPE_BAN.equals(type) || TYPE_FREEZE.equals(type);
    }

    private void complain(Throwable broken) {
        if (!warned) {
            warned = true;
            log.error("the core's punishment backend threw; answering 'not punished'", broken);
        }
    }

    /** The public view of one record. */
    private static final class Snapshot implements HeimdallPunishment {

        private final PunishmentRecord record;

        Snapshot(PunishmentRecord record) {
            this.record = record;
        }

        @Override
        public String id() {
            return record.id();
        }

        @Override
        public String type() {
            return record.type();
        }

        @Override
        public UUID player() {
            return record.player();
        }

        @Override
        public String playerName() {
            return record.playerName();
        }

        @Override
        public String reason() {
            return record.reason();
        }

        @Override
        public long issuedAtMillis() {
            return record.issuedAtMillis();
        }

        @Override
        public long expiresAtMillis() {
            return record.expiresAtMillis();
        }

        @Override
        public boolean isPermanent() {
            return record.expiresAtMillis() == 0L;
        }

        @Override
        public String issuerName() {
            return record.issuerName();
        }

        @Override
        public UUID issuerUuid() {
            return record.issuerUuid();
        }

        @Override
        public boolean hidden() {
            return record.hidden();
        }

        @Override
        public boolean silent() {
            return record.silent();
        }

        @Override
        public String toString() {
            return record.toString();
        }
    }
}
