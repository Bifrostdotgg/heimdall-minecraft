package com.heimdall.shell.hotswap;

/**
 * Reports a swap's progress and result to the command sender that asked for it.
 *
 * <p>A shell object holding a platform sender, never core code: the swap stops the core that asked
 * for it, so a listener that lived in the core would be running in a stopped generation by the time
 * it heard back. A {@code null} sender (the dashboard, a periodic update) is told nothing here; the
 * shell logs every swap to the console regardless.
 */
final class AudienceListener implements SwapListener {

    private final ShellAudience audience;
    private final Object sender;

    AudienceListener(ShellAudience audience, Object sender) {
        this.audience = audience;
        this.sender = sender;
    }

    @Override
    public void progress(String line) {
        if (sender != null) {
            audience.send(sender, "§7" + line);
        }
    }

    @Override
    public void finished(SwapOutcome outcome) {
        if (sender == null) {
            return;
        }
        String colour = outcome.succeeded() ? "§a"
                : outcome.kind() == SwapOutcome.Kind.NO_CORE ? "§4" : "§c";
        audience.send(sender, colour + outcome.message());
    }
}
