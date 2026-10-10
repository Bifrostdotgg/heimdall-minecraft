package com.heimdall.shell.hotswap;

/**
 * Told how a swap went, in sentences an operator can be shown.
 *
 * <p>Implemented by the shell, never by a core: the swap outlives the core that asked for it, so a
 * listener that was core code would be running in a stopped generation by the time it heard back.
 * Called on the swap thread.
 */
public interface SwapListener {

    /** A listener that only the console hears through. */
    SwapListener NONE = new SwapListener() {
        @Override
        public void progress(String line) {
        }

        @Override
        public void finished(SwapOutcome outcome) {
        }
    };

    /** An intermediate step worth telling the requester about. */
    void progress(String line);

    /** The end of the swap, whichever way it went. */
    void finished(SwapOutcome outcome);
}
