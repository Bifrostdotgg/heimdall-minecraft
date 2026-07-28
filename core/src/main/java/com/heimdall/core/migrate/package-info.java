/**
 * One-way migration from a v2 install to a v3 one.
 *
 * <p>Everything here runs at most once per server, on the first boot that finds no
 * {@code bootstrap.yml}, and is inert forever after — an existing v3 bootstrap vetoes the whole
 * operation before anything is even looked for. That is why
 * {@link com.heimdall.core.migrate.V2Migration#run} is safe to call unconditionally at start rather
 * than being something an operator has to remember to invoke.
 *
 * <p>The package exists as its own thing, rather than living in {@code config}, because it is the
 * only code in the plugin that has to understand v2's file format at all. Keeping that knowledge in
 * one place is what lets the whole package be deleted in a later major version without touching
 * anything else — and it stops v2's ~200-key vocabulary leaking back into
 * {@link com.heimdall.core.config.BootstrapConfig}, whose entire point is that it holds six keys.
 *
 * <p>Nothing here throws for a bad input file, and nothing here deletes anything. See
 * {@link com.heimdall.core.migrate.V2Migration} for both rules and why they are absolute.
 */
package com.heimdall.core.migrate;
