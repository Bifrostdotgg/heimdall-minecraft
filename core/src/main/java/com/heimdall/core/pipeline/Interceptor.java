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
 * <p>Throwing is contained: the pipeline logs it and treats the interceptor as having abstained. A
 * broken check must not be able to lock every player out of a server, and must not be able to let
 * every player in either — which is exactly what abstain means.
 *
 * @param <C> the immutable context the check reads
 */
public interface Interceptor<C> {

    /** Decides. Must not mutate {@code context} or retain it. */
    Verdict intercept(C context);
}
