package com.heimdall.module.punishments;

import com.heimdall.core.json.Payload;
import com.heimdall.core.pipeline.CommandAttempt;
import com.heimdall.core.punish.PunishmentParser;
import com.heimdall.core.punish.PunishmentScreens;
import com.heimdall.core.remoteconfig.ModuleConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class PunishmentSettings {

    static final List<String> DEFAULT_BLOCKED = Collections.unmodifiableList(
            Arrays.asList("msg", "tell", "me", "r", "whisper", "w"));

    /** Wire key: ban a player who logs out while frozen. Matches the bot's punishmentModule.ts. */
    static final String KEY_FREEZE_LOGOUT_BAN = "freezeLogoutBan";

    /** Wire key: that ban's length in seconds, 0 or absent for permanent. */
    static final String KEY_FREEZE_LOGOUT_BAN_SECONDS = "freezeLogoutBanSeconds";

    final boolean enabled;
    final String mode;
    final boolean rootAliases;
    final boolean silentByDefault;
    /** Normalised by {@link #commandWords}: {@code msg}, {@code ch send}. */
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
    /** Never blank: {@link PunishmentScreens#MUTE_REMINDER} stands in for an empty segment. */
    final String muteReminderScreen;
    final String kickScreen;
    final String warnScreen;
    /** Never blank: {@link PunishmentScreens#FREEZE} stands in for an empty segment. */
    final String freezeScreen;
    /**
     * Whether a player who logs out while frozen is banned for it. Off unless a guild turns it
     * on: a disconnect can be a crash, and a ban nobody chose is a ban somebody has to appeal.
     */
    final boolean freezeLogoutBan;
    /** How long that ban lasts, in seconds; 0 is permanent. Never negative. */
    final long freezeLogoutBanSeconds;
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
        String reminder = settings.string(
                PunishmentScreens.KEY_MUTE_REMINDER, PunishmentScreens.MUTE_REMINDER);
        this.muteReminderScreen = reminder == null || reminder.trim().isEmpty()
                ? PunishmentScreens.MUTE_REMINDER
                : reminder;
        this.kickScreen = settings.string(PunishmentScreens.KEY_KICK, PunishmentScreens.KICK);
        this.warnScreen = settings.string(PunishmentScreens.KEY_WARN, PunishmentScreens.WARN);
        String freeze = settings.string(PunishmentScreens.KEY_FREEZE, PunishmentScreens.FREEZE);
        this.freezeScreen = freeze == null || freeze.trim().isEmpty()
                ? PunishmentScreens.FREEZE
                : freeze;
        this.freezeLogoutBan = settings.bool(KEY_FREEZE_LOGOUT_BAN, false);
        // Clamped to the longest length any surface may issue rather than trusted: the bot
        // validates it on save, and a value that slipped past that would be refused by the
        // bot's issue route, leaving a ban this server enforces and nothing else has heard of.
        long banSeconds = settings.longValue(KEY_FREEZE_LOGOUT_BAN_SECONDS, 0L);
        this.freezeLogoutBanSeconds = banSeconds <= 0L
                ? 0L
                : Math.min(banSeconds, PunishmentParser.MAX_ISSUE_SECONDS);
        this.announceIssue = settings.string(
                PunishmentScreens.KEY_ANNOUNCE_ISSUE, PunishmentScreens.ANNOUNCE_ISSUE);
        this.announceRevoke = settings.string(
                PunishmentScreens.KEY_ANNOUNCE_REVOKE, PunishmentScreens.ANNOUNCE_REVOKE);
        List<String> blocked = new ArrayList<String>();
        for (String entry : settings.strings("blockedCommands")) {
            // A trailing "*" is what an operator types to mean "and anything after it", which
            // every entry already means. Kept, it would be a literal word nobody can type.
            String words = commandWords(entry == null ? "" : entry.replaceAll("[\\s*]+$", ""));
            if (!words.isEmpty() && !blocked.contains(words)) blocked.add(words);
        }
        this.blockedCommands = blocked.isEmpty()
                ? DEFAULT_BLOCKED
                : Collections.unmodifiableList(blocked);
    }

    /**
     * Whether a muted player may not run this command line.
     *
     * <p>An entry blocks the command it names and anything typed after it, a whole word at a
     * time: {@code ch send} blocks {@code /ch send hi} and {@code /chat:ch send hi}, but not
     * {@code /ch join}; {@code me} blocks {@code /me waves} but not {@code /menu}, and {@code r}
     * not {@code /rtp}. A plain character prefix would have muted players out of every command
     * that starts with the same letters as a short entry.
     */
    boolean blocksCommand(String rawLine) {
        String line = commandWords(rawLine);
        if (line.isEmpty()) return false;
        for (String entry : blockedCommands) {
            if (line.equals(entry) || line.startsWith(entry + " ")) return true;
        }
        return false;
    }

    /**
     * A command line or blocked entry in one spelling: the leading slash gone, the {@code plugin:}
     * namespace dropped from the first word as {@link CommandAttempt#commandLabel} does, words
     * lower-cased and separated by single spaces.
     */
    static String commandWords(String raw) {
        // commandLabel strips the one leading slash, as the label always did: "//set" is
        // WorldEdit's own command, not "set".
        String text = raw == null ? "" : raw.trim();
        String[] words = text.split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return "";
        StringBuilder out = new StringBuilder(CommandAttempt.commandLabel(words[0]));
        if (out.length() == 0) return "";
        for (int i = 1; i < words.length; i++) {
            out.append(' ').append(words[i].toLowerCase(Locale.ROOT));
        }
        return out.toString();
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
     * @param family {@code ban}, {@code mute}, {@code kick}, {@code warn} or {@code freeze}
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
        if ("freeze".equals(family)) {
            return freezeScreen;
        }
        return permanent && !banPermanentScreen.isEmpty() ? banPermanentScreen : banScreen;
    }
}
