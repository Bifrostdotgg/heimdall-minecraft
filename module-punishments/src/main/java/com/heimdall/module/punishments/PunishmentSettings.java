package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.punish.PunishmentScreens;
import com.heimdall.core.remoteconfig.ModuleConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class PunishmentSettings {

    static final List<String> DEFAULT_BLOCKED = Collections.unmodifiableList(
            Arrays.asList("msg", "tell", "me", "r", "whisper", "w"));

    final boolean enabled;
    final String mode;
    final boolean rootAliases;
    final boolean silentByDefault;
    final List<String> blockedCommands;
    final String ipSalt;
    final String appealUrl;
    /** The guild's name, for {@code {server}}. Empty until a bot new enough to send it pushes. */
    final String serverName;
    final String screenBase;
    final String banScreen;
    final String banPermanentScreen;
    final String muteScreen;
    final String mutePermanentScreen;
    final String kickScreen;
    final String warnScreen;
    final String announceIssue;
    final String announceRevoke;

    private PunishmentSettings(ModuleConfig config) {
        Payload settings = config.settings();
        this.enabled = config.enabled();
        this.mode = settings.string("mode", "offenses-only");
        this.rootAliases = settings.bool("rootAliases", false);
        this.silentByDefault = settings.bool("silentByDefault", false);
        this.ipSalt = settings.string("ipSalt", "");
        String appeal = settings.string("appealUrl", "");
        if (appeal.isEmpty()) {
            appeal = settings.string("appeal_url", "");
        }
        this.appealUrl = appeal;
        this.serverName = settings.string(PunishmentScreens.KEY_SERVER_NAME, "");
        // Absent means the bot never sent one, which is what an older bot does for every key
        // here, so the shared default applies. Present and empty is a guild that cleared the box,
        // and means render nothing - which is why these read through Payload.string, whose
        // fallback only fires on an absent key.
        this.screenBase = settings.string(PunishmentScreens.KEY_BASE, PunishmentScreens.BASE);
        this.banScreen = settings.string(PunishmentScreens.KEY_BAN, PunishmentScreens.BAN);
        this.banPermanentScreen = settings.string(
                PunishmentScreens.KEY_BAN_PERMANENT, PunishmentScreens.BAN_PERMANENT);
        this.muteScreen = settings.string(PunishmentScreens.KEY_MUTE, PunishmentScreens.MUTE);
        this.mutePermanentScreen = settings.string(
                PunishmentScreens.KEY_MUTE_PERMANENT, PunishmentScreens.MUTE_PERMANENT);
        this.kickScreen = settings.string(PunishmentScreens.KEY_KICK, PunishmentScreens.KICK);
        this.warnScreen = settings.string(PunishmentScreens.KEY_WARN, PunishmentScreens.WARN);
        this.announceIssue = settings.string(
                PunishmentScreens.KEY_ANNOUNCE_ISSUE, PunishmentScreens.ANNOUNCE_ISSUE);
        this.announceRevoke = settings.string(
                PunishmentScreens.KEY_ANNOUNCE_REVOKE, PunishmentScreens.ANNOUNCE_REVOKE);
        List<String> blocked = new ArrayList<String>(settings.strings("blockedCommands"));
        this.blockedCommands = blocked.isEmpty()
                ? DEFAULT_BLOCKED
                : Collections.unmodifiableList(blocked);
    }

    static PunishmentSettings from(ModuleConfig config) {
        return new PunishmentSettings(config);
    }

    boolean replaceMode() {
        return "replace".equalsIgnoreCase(mode);
    }

    boolean hookMode() {
        return "hook".equalsIgnoreCase(mode);
    }

    /**
     * The screen for one punishment, chosen by family and by whether it ever ends.
     *
     * <p>An empty permanent variant falls back to the temporary one rather than rendering
     * nothing, which is what makes the default configuration - blank permanent variants - correct
     * rather than broken: the optional-segment rule already removes the Length and Expires rows
     * when there is no expiry, so one template covers both cases until a guild wants a different
     * layout for forever.
     *
     * @param family {@code ban}, {@code mute}, {@code kick} or {@code warn}
     */
    String screenFor(String family, boolean permanent) {
        if ("mute".equals(family)) {
            return permanent && !mutePermanentScreen.isEmpty() ? mutePermanentScreen : muteScreen;
        }
        if ("kick".equals(family)) {
            return kickScreen;
        }
        if ("warn".equals(family)) {
            return warnScreen;
        }
        return permanent && !banPermanentScreen.isEmpty() ? banPermanentScreen : banScreen;
    }
}
