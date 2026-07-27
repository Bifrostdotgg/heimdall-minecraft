/**
 * Message rendering: the one place a string becomes something a player can be shown.
 *
 * <p>Adventure's {@code net.kyori.adventure.text} half is pure model code with no server types in
 * it, which is why the conformance rules ban {@code net.kyori.adventure.platform..} from core and
 * not the rest of {@code net.kyori}. The bindings that actually put a component on a screen
 * (BukkitAudiences and friends) live in the platform modules, where they belong.
 */
package com.heimdall.core.text;
