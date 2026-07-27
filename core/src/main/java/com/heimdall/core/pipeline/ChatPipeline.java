package com.heimdall.core.pipeline;

import com.heimdall.core.log.HeimdallLogger;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chat: the checks that can block a message, then the observers that only watch.
 *
 * <h2>Relay-only, forever</h2>
 *
 * <p><strong>Chat is relayed and never stored.</strong> That is a product decision, and this API is
 * shaped to make violating it require adding a method rather than calling one. There is no buffer,
 * no history, no {@code getLast}, no size, nothing that returns a message the pipeline has already
 * seen. A {@link ChatMessage} exists for the duration of one {@link #dispatch} and then nothing in
 * core holds a reference to it.
 *
 * <p>Observers get the same immutable value and are told, in {@link ChatObserver}, not to retain it.
 * That instruction cannot be enforced by a compiler — but the absence of any accumulating structure
 * here means the only way to build a chat log is for somebody to write one on purpose, in a review
 * that can ask why.
 *
 * <h2>The default is allow</h2>
 *
 * <p>Same reasoning as the login pipeline: no interceptors means no chat moderation is enabled, and
 * a server with no chat moderation lets people talk.
 *
 * <h2>Observers run only for messages that were allowed</h2>
 *
 * <p>A blocked message did not happen as far as the server is concerned, so relaying it to Discord
 * would put the thing that was just censored in front of a wider audience.
 */
public final class ChatPipeline extends Pipeline<ChatMessage> {

    private final HeimdallLogger logger;
    private final CopyOnWriteArrayList<ChatObserver> observers =
            new CopyOnWriteArrayList<ChatObserver>();

    public ChatPipeline(HeimdallLogger logger) {
        super("chat", logger, Verdict.Decision.ALLOW);
        this.logger = logger;
    }

    /**
     * Registers a read-only observer, appended after the ones already registered.
     *
     * <p>Ordered by registration rather than by priority: observers cannot affect the outcome, so
     * there is nothing for a priority to arbitrate, and offering one would invite the belief that
     * an earlier observer can influence a later one.
     *
     * @return a handle that removes exactly this observer
     */
    public Registration observe(final ChatObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer is required");
        }
        observers.add(observer);
        return Registration.once(new Runnable() {
            @Override
            public void run() {
                observers.remove(observer);
            }
        });
    }

    /** How many observers are registered. */
    public int observerCount() {
        return observers.size();
    }

    /**
     * Runs the checks and, if the message survives, the observers.
     *
     * @return the verdict; observers have already run by the time this returns
     */
    public Verdict dispatchWithObservers(ChatMessage message) {
        Verdict verdict = dispatch(message);
        if (verdict.isDeny()) {
            return verdict;
        }
        for (ChatObserver observer : observers) {
            try {
                observer.onChat(message);
            } catch (RuntimeException e) {
                // One observer failing must not stop the relay reaching the others, and must not
                // turn an allowed message into a blocked one.
                logger.error("chat observer threw", e);
            }
        }
        return verdict;
    }

    /** The observers, in order. A copy — the live list is never handed out. */
    List<ChatObserver> observers() {
        return Collections.unmodifiableList(new ArrayList<ChatObserver>(observers));
    }

    /** Drops every observer as well as every interceptor. */
    public void clearObservers() {
        observers.clear();
    }
}
