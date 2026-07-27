package com.heimdall.core.pipeline;

/**
 * Watches chat that was allowed through, and cannot change it.
 *
 * <p>What the Discord relay is. Separate from {@link Interceptor} because the two roles have
 * genuinely different rights, and an interface that can only observe is the cheapest way to say so:
 * an observer registered where an interceptor was expected cannot accidentally start blocking
 * messages.
 *
 * <p><strong>Do not retain the message.</strong> Chat is relayed and never stored — see
 * {@link ChatPipeline}. Storing it in a field, a queue or a collection here is the one thing this
 * design exists to prevent, and it is the reviewer's job rather than the compiler's.
 *
 * <p>Runs synchronously on the calling thread, after the verdict, and only for allowed messages. An
 * observer that has to do network work — the relay does — hands it to an executor rather than
 * blocking here: on Bukkit this is the main server thread.
 *
 * <p>Throwing is contained: the pipeline logs it and the other observers still run.
 */
public interface ChatObserver {

    /** Called once per allowed message. Must not mutate or retain {@code message}. */
    void onChat(ChatMessage message);
}
