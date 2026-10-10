package com.heimdall.shell.hotswap;

/**
 * A jar that cannot be used as a core, with the sentence an operator should be shown.
 *
 * <p>Checked, because every caller is about to tell somebody something (a command sender, the
 * console, the dashboard) and none of them can do anything useful with a stack trace.
 */
public final class CoreArchiveException extends Exception {

    private static final long serialVersionUID = 1L;

    public CoreArchiveException(String message) {
        super(message);
    }

    public CoreArchiveException(String message, Throwable cause) {
        super(message, cause);
    }
}
