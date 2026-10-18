package litebans.api;

/**
 * Compile-only stub of LiteBans 2.x {@code litebans.api.Entry}. Not shipped.
 */
public abstract class Entry {

    public abstract String getType();

    public abstract String getUuid();

    public abstract String getIp();

    public abstract String getReason();

    public abstract String getExecutorUUID();

    public abstract String getExecutorName();

    public abstract long getDateStart();

    public abstract long getDateEnd();

    public abstract boolean isSilent();

    public abstract boolean isIpban();

    public abstract boolean isActive();
}
