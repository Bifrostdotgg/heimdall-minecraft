/**
 * Commands, described without naming a platform.
 *
 * <p>Four small types — a spec, a handler, a completer, a source — and a registrar behind the
 * platform facade. A feature module builds a {@code CommandSpec} and hands it to its
 * {@code ModuleContext}; what happens next is Bukkit's {@code PluginCommand} on one side and
 * Velocity's {@code CommandManager} on the other, and the module never learns which.
 *
 * <p>The registration is a {@link com.heimdall.core.util.Registration}, so a disabled module's
 * commands genuinely stop answering. v2 could not do that: its command handling was a chain of
 * {@code if (commandName.equals(...))} inside a 1,086-line entry point, and a disabled feature was
 * one whose branch checked a boolean.
 */
package com.heimdall.core.command;
