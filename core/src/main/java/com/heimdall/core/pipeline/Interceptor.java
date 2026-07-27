package com.heimdall.core.pipeline;

/**
 * A single check in a {@link Pipeline}.
 *
 * <p><strong>Runs synchronously, on the caller's thread.</strong> On Bukkit that is the async
 * pre-login thread; on Velocity it is an event-executor thread. Both can be blocked on safely, which
 * is why the pipeline does not impose an executor of its own — an interceptor that needs to call the
 * bot needs the answer <em>now</em>, and hopping to another pool to wait would add a context switch
 * and change nothing about the waiting.
 *
 * <p>The consequence is that <strong>each interceptor owns its own blocking budget</strong>. There
 * is no pipeline-wide deadline, because there is no sensible one: a login gate that consults the bot
 * legitimately takes seconds, and a bypass-list check takes microseconds. An interceptor that can
 * block bounds its own wait and abstains or denies when it runs out.
 *
 * <p><strong>An escaped exception is a bug in the interceptor, not a supported control flow.</strong>
 * The pipeline contains it — see {@link #failureVerdict()} — but that containment is a backstop, and
 * a check that has a considered answer for "the bot is unreachable" must return that answer itself.
 *
 * @param <C> the immutable context the check reads
 */
public interface Interceptor<C> {

    /** Decides. Must not mutate {@code context} or retain it. */
    Verdict intercept(C context);

    /**
     * What this check means when it throws.
     *
     * <p><strong>The default is {@link Verdict#abstain()}, which on the login pipeline means fail
     * open.</strong> That is stated plainly rather than left implicit, because the alternative —
     * defaulting to deny — turns any bug in any interceptor into a server nobody can join, which is
     * a worse outage than the one it would be guarding against. A check whose failure genuinely
     * should keep players out overrides this and says so.
     *
     * <p><strong>This is a backstop, not the mechanism.</strong> "The bot is unreachable — admit or
     * refuse?" is a policy a server owner configures, and it belongs inside the interceptor, which
     * is the only thing that knows the request failed rather than that something unexpected
     * happened. v2 caught its API exception at exactly that level and consulted its fallback mode
     * there; the whitelist module in phase 1d must do the same. If a module is relying on this
     * method to implement its offline policy, the policy is in the wrong place.
     *
     * <p>Called after the pipeline has logged the exception. It must not throw; one that does is
     * treated as an abstain.
     *
     * @param cause the exception that escaped {@link #intercept}
     */
    default Verdict failureVerdict(RuntimeException cause) {
        return Verdict.abstain();
    }
}
