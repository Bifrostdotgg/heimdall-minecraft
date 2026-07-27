/**
 * Paper-specific extensions to the Bukkit binding, compiled against the Paper 1.16.5 API.
 *
 * <p>Never linked eagerly — the shared jar has to keep starting on plain Spigot 1.8.8.
 * {@code BukkitAdapters} in {@code :platform-bukkit} probes for the capability and then loads the
 * class here by name, which is the only arrangement that survives class-link-time verification on a
 * server that has none of this API.
 *
 * <p><strong>Smaller than it was planned to be, on purpose.</strong> Two things that looked like
 * they belonged here do not:
 *
 * <ul>
 *   <li><em>Paper's {@code AsyncChatEvent}</em> cannot be used at all. Its {@code message()}
 *       returns a {@code net.kyori.adventure.text.Component} supplied by the server, while Heimdall
 *       shades and relocates its own Adventure — and Shadow rewrites every {@code net.kyori}
 *       reference in every class it merges, including that call's descriptor. The compiled call
 *       would look for a relocated type on an event that returns the server's own.
 *       {@code AsyncPlayerChatEvent} is a plain String, fires everywhere, and has none of that
 *       problem (departure D43).
 *   <li><em>Velocity-forwarding detection</em> is a file read in {@code :platform-bukkit} instead.
 *       Paper's switch has moved class three times since 1.16 while the YAML key it is loaded from
 *       has changed once — and a file read needs no version guard and can be tested against a
 *       fixture directory.
 * </ul>
 *
 * <p>What is left is the one thing a direct Paper call genuinely does better than reflection or a
 * file: a measured mean tick time.
 */
package com.heimdall.platform.bukkit.paper;
