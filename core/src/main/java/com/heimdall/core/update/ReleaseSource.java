package com.heimdall.core.update;

import com.heimdall.core.http.model.PluginRelease;
import java.util.concurrent.CompletableFuture;

/**
 * Where {@link UpdateService} gets "what is the newest published release" from.
 *
 * <h2>Why this exists rather than an {@code ApiClient} field</h2>
 *
 * <p>v2's {@code UpdateChecker} held an {@code ApiClient} directly, and the cost was that its state
 * machine — is there an update, what is it, has a check run, what does an admin get told on join —
 * could not be exercised without a live HTTP client. So it never was: v2 had no update-checker
 * tests at all, and both the "newer version" and the "same version" branches shipped unproven.
 * Behind this interface the whole state machine is a fake away from being testable, which is what
 * {@code UpdateServiceTest} is.
 *
 * <p>It also keeps the dependency pointing the right way. The updater needs one endpoint out of the
 * fifteen {@code ApiClient} exposes; depending on the whole client to reach it means the update
 * feature is coupled to every change made for any other endpoint. The production implementation is
 * a two-line adapter over {@code ApiClient.latestRelease()}.
 *
 * <h2>The timeout is the update-check budget, not the login budget</h2>
 *
 * <p>{@link #joinTimeoutMs()} is on this interface rather than being a constant in the service
 * because the right value is a property of the endpoint, and {@code plugin/latest} runs with a
 * longer per-attempt timeout than the login path does — {@code ApiSettings.updateCheckTimeoutMs()}
 * floors it at 8 s against a 5 s default. Its retry-inclusive worst case is therefore around 26 s
 * at the defaults, where the login budget reports 17 s. Bounding a wait on the login number would
 * abandon a request the retry loop was still legitimately working on, which is departure D16 — the
 * same shape as issue #797 / MC-6, on a different endpoint. The production implementation answers
 * {@code ApiSettings.updateCheckJoinTimeoutMs()}, and nothing else should.
 *
 * <p><strong>Threading.</strong> {@link #latestRelease()} must return immediately and do its work
 * on an executor the implementation names — never on the caller's thread, and never on the common
 * pool. {@link UpdateService} blocks on the returned future, on a thread it has already established
 * is safe to block; the implementation must not assume anything about which thread that is.
 */
public interface ReleaseSource {

    /**
     * The newest published release.
     *
     * <p>Never blocks the caller. The future completes exceptionally if the bot could not be
     * reached or answered a failure; it may also complete with {@code null}, or with a release
     * whose version is blank, when the bot has no release to report. {@link UpdateService} treats
     * all three the same way — as "no answer", not as an error worth an operator's attention.
     */
    CompletableFuture<PluginRelease> latestRelease();

    /**
     * How long a caller may block on {@link #latestRelease()} before giving up.
     *
     * <p>Must cover the whole retry sequence plus slack, not one attempt. See the note on the
     * interface.
     */
    long joinTimeoutMs();
}
