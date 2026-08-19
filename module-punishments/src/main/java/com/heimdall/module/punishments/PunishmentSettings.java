package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
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
    final String banScreen;
    final String muteScreen;
    final String kickScreen;
    final String warnScreen;

    private PunishmentSettings(ModuleConfig config) {
        Payload settings = config.settings();
        this.enabled = config.enabled();
        this.mode = settings.string("mode", "offenses-only");
        this.rootAliases = settings.bool("rootAliases", false);
        this.silentByDefault = settings.bool("silentByDefault", false);
        this.ipSalt = settings.string("ipSalt", "");
        this.banScreen = settings.string("banScreen",
                "<red>You are banned.</red>\n<gray>{reason}</gray>");
        this.muteScreen = settings.string("muteScreen",
                "<red>You are muted.</red> <gray>{reason}</gray>");
        this.kickScreen = settings.string("kickScreen",
                "<red>Kicked.</red> <gray>{reason}</gray>");
        this.warnScreen = settings.string("warnScreen",
                "<yellow>You have been warned.</yellow> <gray>{reason}</gray>");
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
}
