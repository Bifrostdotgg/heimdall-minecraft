package com.heimdall.core.mirror;

/** What one {@link MirrorStore#reconcile} pass did. */
public final class ReconcileResult {

    private final int added;
    private final int updated;
    private final int pruned;

    ReconcileResult(int added, int updated, int pruned) {
        this.added = added;
        this.updated = updated;
        this.pruned = pruned;
    }

    /** Keys the mirror had never seen. */
    public int added() {
        return added;
    }

    /** Keys already mirrored, whose verification was refreshed. */
    public int updated() {
        return updated;
    }

    /**
     * Keys dropped because the authoritative set no longer lists them.
     *
     * <p>This is how a revocation propagates promptly instead of lingering until the entry would
     * have expired on its own.
     */
    public int pruned() {
        return pruned;
    }

    @Override
    public String toString() {
        return added + " added, " + updated + " refreshed, " + pruned + " pruned";
    }
}
