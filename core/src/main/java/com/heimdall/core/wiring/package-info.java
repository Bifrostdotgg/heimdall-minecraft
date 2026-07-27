/**
 * The composition layer: the only place in core allowed to know about two other packages at once.
 *
 * <p>{@code config} describes what is on disk, {@code http} describes how to talk to the bot, and
 * neither should have to import the other. The adapter between them used to live on {@code
 * ApiSettings}, which made every consumer of the HTTP client transitively depend on the bootstrap
 * file format — and phase 1b adds a second source for the same settings (remote config pushed over
 * the tunnel), which that arrangement would have had no room for.
 *
 * <p>Anything here is a factory or an assembler. No behaviour, no state.
 */
package com.heimdall.core.wiring;
