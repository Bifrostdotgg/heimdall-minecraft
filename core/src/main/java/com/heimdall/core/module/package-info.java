/**
 * Modules: features the dashboard can switch on and off while the server is running.
 *
 * <p>The design decision underneath everything here is that <strong>a module does not have to clean
 * up after itself</strong>. Every registry it can reach — tunnel subscriptions, pipeline
 * interceptors, chat observers, scheduled tasks, config listeners, mirrors — is reached through its
 * {@link com.heimdall.core.module.ModuleContext}, which records what it registered, so
 * {@code ModuleManager} can unwind all of it without knowing what any of it was.
 *
 * <p>The alternative is trusting each module's {@code disable()} to undo its own work, and that
 * fails the first time somebody adds a listener and forgets. The symptom — a "disabled" module still
 * reacting to events — is one nobody attributes to the module that was turned off weeks ago. v2 had
 * no disable path at all: a feature was switched off by a boolean its own code checked on every
 * call, with its listeners still registered.
 *
 * <p>Everything else follows from that: reconciliation as the single lifecycle entry point, failure
 * containment (a module that throws on start is unwound and marked failed rather than taking the
 * plugin with it), and role eligibility that outranks configuration.
 */
package com.heimdall.core.module;
