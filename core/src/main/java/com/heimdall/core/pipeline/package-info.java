/**
 * Interceptor chains over the two events Heimdall gates: logins and chat.
 *
 * <p>One engine, two instances. A check registers with a priority, the chain runs in ascending
 * order on the calling thread, and the first denial wins. Abstain is a real third answer rather than
 * a synonym for allow — an interceptor whose module is switched off must not be able to overrule one
 * that is running.
 *
 * <p><strong>Chat is relay-only, forever.</strong> {@link com.heimdall.core.pipeline.ChatPipeline}
 * exposes no buffer, no history and no accessor that returns a message it has already dispatched, so
 * building a chat log would mean adding a method rather than calling one.
 */
package com.heimdall.core.pipeline;
