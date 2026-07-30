package com.heimdall.platform.bungee;

import com.heimdall.core.text.Msg;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Where Heimdall's text meets BungeeCord's — as ordinary method calls, which is the point.
 *
 * <h2>Why this is nine lines and {@code VelocityText} is three hundred</h2>
 *
 * <p>Velocity's API is built on Adventure, and Heimdall shades and <em>relocates</em> its own copy
 * into {@code com.heimdall.libs.kyori}. Shadow rewrites every {@code net.kyori} reference in every
 * class it merges, including the descriptor at a call site — so on Velocity
 * {@code player.disconnect(component)} compiles into a call that asks for a relocated
 * {@code Component} from a method that takes the server's, and fails with a
 * {@link NoSuchMethodError} at the exact moment somebody is supposed to be told why they were
 * refused. That is departure D44, and it is why the Velocity binding reflects.
 *
 * <p>BungeeCord's text API is {@code net.md_5.bungee.api.chat.BaseComponent}, which is not Adventure
 * and is not relocated by anything in {@code :app}'s shadow configuration. There is no collision to
 * work around: Heimdall's {@code Component} and BungeeCord's {@code BaseComponent} are simply two
 * unrelated types, and converting between them is a normal call. Departure D76.
 *
 * <h2>Legacy §-codes are the conversion, deliberately</h2>
 *
 * <p>{@link Msg#toLegacy} then {@link TextComponent#fromLegacyText} — the same round trip the Bukkit
 * binding uses for its kick screen and the Velocity one uses across its reflective boundary, so all
 * three platforms render a dashboard message identically.
 *
 * <p>The alternative was JSON: serialise with Adventure's Gson serializer and parse with BungeeCord's
 * {@code ComponentSerializer}. It preserves click and hover handlers, which the legacy form loses —
 * and nothing on any path this bridge serves has one (a kick screen, a command reply, a relayed
 * message). It would also mean shading {@code adventure-text-serializer-gson} for one platform, and
 * pinning two JSON component <em>schemas</em> against each other across the decade of protocol
 * versions a single BungeeCord speaks to. Legacy text is the format both sides have always agreed
 * on; that is the whole reason to use it.
 *
 * <p>{@code fromLegacyText} rather than the newer {@code fromLegacy}: the latter arrived in the 1.20
 * line and would be a {@link NoSuchMethodError} on every older proxy, which is exactly the floor
 * decision the API version pin exists to make (departure D74). Hex colours ride along in the
 * {@code §x§r§r§g§g§b§b} form {@link Msg} already emits, which BungeeCord has parsed since 1.16 and
 * which — like on 1.8.8 — simply never appears unless a dashboard template used a hex colour.
 *
 * <p>Stateless, and safe from any thread.
 */
final class BungeeText {

    /**
     * Converts one of Heimdall's components into BungeeCord's.
     *
     * <p>Never {@code null} and never empty: {@code fromLegacyText("")} still yields a one-element
     * array, and every caller passes the result straight to an API that would reject a null.
     */
    @SuppressWarnings("deprecation")
    BaseComponent[] toComponents(Component message) {
        // fromLegacyText is deprecated on modern BungeeCord in favour of the single-component
        // fromLegacy, which does not exist below the 1.20 line. Deprecated-but-present beats
        // absent-on-half-the-fleet; the same trade the log4j tap's four-argument super makes.
        return TextComponent.fromLegacyText(Msg.toLegacy(message));
    }
}
