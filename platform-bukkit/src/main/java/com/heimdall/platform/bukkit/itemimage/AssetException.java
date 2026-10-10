package com.heimdall.platform.bukkit.itemimage;

import java.io.IOException;

/**
 * An asset failure whose message this package wrote itself, and which is therefore safe to log:
 * it names a host, a version, a size or a check that failed, never a full URL (a resource-pack URL
 * can carry a token in its query) and never anything read from a pack.
 *
 * <p>Any other exception is logged by class name only. {@link HttpSource.Url} wraps transport
 * errors into this type for that reason, keeping just the class and the host.
 */
class AssetException extends IOException {

    private static final long serialVersionUID = 1L;

    AssetException(String message) {
        super(message);
    }

    /** What a log line may say about {@code failure}. */
    static String describe(Throwable failure) {
        if (failure instanceof AssetException) {
            return failure.getMessage();
        }
        return failure == null ? "no cause" : failure.getClass().getSimpleName();
    }
}
