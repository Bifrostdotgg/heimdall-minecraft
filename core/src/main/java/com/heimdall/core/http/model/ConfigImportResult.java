package com.heimdall.core.http.model;

import com.heimdall.core.util.Strings;

/**
 * The answer to a one-time settings import, and specifically whether it took.
 *
 * <p>{@code POST …/servers/{serverId}/config/import} is <strong>write-once</strong>. A second import
 * against a server that already has a configuration document is a {@code 200} with
 * {@link #imported()} {@code false} and the stored settings untouched — not a conflict, and not an
 * error. That shape is deliberate bot-side: a plugin migrating a v2 config file can therefore call
 * it unconditionally on first boot without having to ask permission first, and cannot clobber
 * whatever an operator has since edited in the dashboard.
 *
 * <p>So {@code imported() == false} is a perfectly successful outcome, and the migration reports it
 * as "the dashboard already has settings for this server" rather than as a failure. Treating it as
 * one is the mistake this type exists to make hard.
 *
 * <p>Immutable.
 */
public final class ConfigImportResult {

    private final String serverId;
    private final boolean imported;
    private final int version;

    public ConfigImportResult(String serverId, boolean imported, int version) {
        this.serverId = Strings.trimToEmpty(serverId);
        this.imported = imported;
        this.version = version;
    }

    /** The server the document belongs to, echoed back. */
    public String serverId() {
        return serverId;
    }

    /** Whether this call is what created the document. {@code false} means one already existed. */
    public boolean imported() {
        return imported;
    }

    /** The stored document's version — 1 for a document this call created. */
    public int version() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConfigImportResult)) {
            return false;
        }
        ConfigImportResult that = (ConfigImportResult) other;
        return imported == that.imported && version == that.version && serverId.equals(that.serverId);
    }

    @Override
    public int hashCode() {
        int result = serverId.hashCode();
        result = 31 * result + (imported ? 1 : 0);
        result = 31 * result + version;
        return result;
    }

    @Override
    public String toString() {
        return "ConfigImportResult{serverId='" + serverId + "', imported=" + imported
                + ", version=" + version + "}";
    }
}
