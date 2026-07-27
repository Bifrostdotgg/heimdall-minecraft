package com.heimdall.core.remoteconfig;

import com.heimdall.core.json.Payload;

/**
 * One module's slice of the configuration document: whether it runs, and what it runs with.
 *
 * <p><strong>{@link #isPresent()} is not the same as {@link #enabled()}.</strong> The bot narrows
 * its config push to the capabilities the client declared, so a module with no entry means "nothing
 * upstream has an opinion about this" — which is what lets {@link RemoteConfig} fall through to the
 * built-in defaults for it — whereas an entry with {@code enabled: false} is an explicit
 * instruction to stop. Collapsing the two would make "the dashboard has not been told about this
 * module yet" indistinguishable from "an operator turned it off", and the second must survive a
 * default that says otherwise.
 *
 * <p>Immutable, with value semantics: change detection is a comparison, and identity comparison
 * would report a change on every poll (departure D22).
 */
public final class ModuleConfig {

    private static final ModuleConfig ABSENT = new ModuleConfig(false, false, Payload.empty());

    private final boolean present;
    private final boolean enabled;
    private final Payload settings;

    private ModuleConfig(boolean present, boolean enabled, Payload settings) {
        this.present = present;
        this.enabled = enabled;
        this.settings = settings == null ? Payload.empty() : settings;
    }

    /** No entry at all — nothing upstream mentioned this module. */
    public static ModuleConfig absent() {
        return ABSENT;
    }

    /** An explicit entry. */
    public static ModuleConfig of(boolean enabled, Payload settings) {
        return new ModuleConfig(true, enabled, settings);
    }

    /**
     * Parses one {@code {enabled, settings}} entry.
     *
     * <p>An absent {@code enabled} defaults to <strong>off</strong>. The bot deciding not to say
     * should not start something, and a module that has to run has an entry saying so.
     */
    static ModuleConfig fromPayload(Payload payload) {
        if (payload == null || payload.isEmpty()) {
            return ABSENT;
        }
        return new ModuleConfig(true, payload.bool("enabled", false), payload.child("settings"));
    }

    /** Whether the document carried an entry for this module. */
    public boolean isPresent() {
        return present;
    }

    /** Whether the module should be running. Always {@code false} when {@link #isPresent()} is not. */
    public boolean enabled() {
        return enabled;
    }

    /**
     * The module's settings, as a typed view with defaults.
     *
     * <p>Never {@code null}; {@link Payload#empty()} when there are none, so a module reading
     * {@code settings().intValue("window-minutes", 60)} works identically before and after the bot
     * has ever configured it.
     */
    public Payload settings() {
        return settings;
    }

    /** This entry as the {@code {enabled, settings}} shape, for the disk cache. */
    Payload toPayload() {
        return Payload.builder().put("enabled", enabled).put("settings", settings).build();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModuleConfig)) {
            return false;
        }
        ModuleConfig that = (ModuleConfig) other;
        return present == that.present && enabled == that.enabled && settings.equals(that.settings);
    }

    @Override
    public int hashCode() {
        int result = present ? 1 : 0;
        result = 31 * result + (enabled ? 1 : 0);
        result = 31 * result + settings.hashCode();
        return result;
    }

    @Override
    public String toString() {
        if (!present) {
            return "ModuleConfig{absent}";
        }
        return "ModuleConfig{enabled=" + enabled + ", settings=" + settings + "}";
    }
}
