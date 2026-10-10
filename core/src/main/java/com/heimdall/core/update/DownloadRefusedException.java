package com.heimdall.core.update;

import java.io.IOException;

/**
 * A download {@link UpdateDownloader} refused on purpose: a host, scheme or repository outside
 * the {@link DownloadPolicy}, a malformed published hash, or a body that does not match it.
 *
 * <p>Its own type so an installer can tell it from a file it could not write. A jar that is locked
 * is worth retrying somewhere else; a jar that failed verification is not worth fetching again
 * anywhere, and retrying it into the data directory would only download the same refused bytes a
 * second time.
 */
public final class DownloadRefusedException extends IOException {

    private static final long serialVersionUID = 1L;

    public DownloadRefusedException(String message) {
        super(message);
    }
}
