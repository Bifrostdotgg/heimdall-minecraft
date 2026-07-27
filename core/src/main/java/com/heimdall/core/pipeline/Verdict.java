package com.heimdall.core.pipeline;

import net.kyori.adventure.text.Component;

/**
 * One interceptor's answer: allow, deny with a reason, or have no opinion.
 *
 * <p><strong>{@link #abstain()} is not "allow".</strong> The distinction is the reason this is an
 * enum-plus-payload rather than a boolean. An interceptor that has nothing to say — the module is
 * disabled, the player is on the bypass list, the check does not apply to this platform — must not
 * be able to overrule one that does. Collapsing abstain into allow means the first indifferent
 * interceptor in the chain silently vetoes every stricter one behind it, and nothing in any log
 * would say so.
 *
 * <p>Immutable. {@link #allow()} and {@link #abstain()} are shared instances; only a denial carries
 * state.
 */
public final class Verdict {

    /** What an interceptor decided. */
    public enum Decision {

        /** This interceptor is satisfied, but a later one may still deny. */
        ALLOW,

        /** Stop here. Carries the reason the player is shown. */
        DENY,

        /** No opinion. Does not affect the outcome either way. */
        ABSTAIN
    }

    private static final Verdict ALLOW = new Verdict(Decision.ALLOW, null);
    private static final Verdict ABSTAIN = new Verdict(Decision.ABSTAIN, null);

    private final Decision decision;
    private final Component reason;

    private Verdict(Decision decision, Component reason) {
        this.decision = decision;
        this.reason = reason;
    }

    /** This interceptor is satisfied. */
    public static Verdict allow() {
        return ALLOW;
    }

    /** This interceptor has no opinion. */
    public static Verdict abstain() {
        return ABSTAIN;
    }

    /**
     * Stop, and show this reason.
     *
     * @param reason what the player sees; {@code null} becomes an empty component rather than a
     *     null-pointer on the login path, though a denial with no reason is a support ticket
     */
    public static Verdict deny(Component reason) {
        return new Verdict(Decision.DENY, reason == null ? Component.empty() : reason);
    }

    /** What was decided. */
    public Decision decision() {
        return decision;
    }

    /** Whether this is a denial. */
    public boolean isDeny() {
        return decision == Decision.DENY;
    }

    /** Whether this interceptor had an opinion at all. */
    public boolean isAbstain() {
        return decision == Decision.ABSTAIN;
    }

    /** The denial reason, or {@code null} for anything that is not a denial. */
    public Component reason() {
        return reason;
    }

    @Override
    public String toString() {
        return decision == Decision.DENY ? "Verdict{DENY}" : "Verdict{" + decision + "}";
    }
}
