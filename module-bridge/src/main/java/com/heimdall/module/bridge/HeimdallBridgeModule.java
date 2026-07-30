package com.heimdall.module.bridge;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerDeathListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * The Discord chat bridge: chat and player events out, rendered Discord messages back in.
 *
 * <h2>Relay only, and the shape of the code is the guarantee</h2>
 *
 * <p>Chat is relayed and never stored. Core makes that structural for the pipeline — a
 * {@code ChatObserver} is read-only <em>by type</em>, and {@code ChatPipeline} has no buffer, no
 * history and nothing that returns a message it has already dispatched. This module is the first
 * thing downstream of that guarantee, so it keeps it the same way:
 *
 * <ul>
 *   <li>The only place a message rests is a {@link FrameBatcher}'s queue: bounded at
 *       {@value #MAX_QUEUE_SIZE}, drop-oldest, drained every second and discarded if there is no bot
 *       to send it to. Nothing here writes a mirror, a file or a cache.
 *   <li><strong>No log line ever carries message text.</strong> Counts and lengths only. That
 *       applies to error paths too, which is where it is usually lost — an exception message
 *       naming the line that broke the relay would be a chat log written one entry at a time.
 *   <li>Player text goes on the wire <strong>verbatim</strong>: not trimmed, not normalised, not
 *       formatted, not colour-stripped. The bot owns rendering, so anything done here would be a
 *       second opinion the operator cannot see or configure — and the plugin would have to be
 *       released to change it. Departure D79.
 * </ul>
 *
 * <h2>{@code relayChat}: a setting, not an eligibility rule</h2>
 *
 * <p>{@link #roles()} is empty — any role — for the same reason the whitelist module's is. Whether a
 * given instance should be the one relaying its network's chat is a per-deployment answer, and a
 * {@code roles()} exclusion would mark the module {@code INELIGIBLE} on a proxy with no dashboard
 * toggle able to bring it back. So it is the flat {@code relayChat} boolean instead, and it can be
 * flipped either way at runtime.
 *
 * <p>Its <em>default</em> is where the role comes in: {@code true} on {@code STANDALONE} and
 * {@code ENFORCER}, {@code false} on {@code GATEKEEPER}. That is the sanctioned topology — each
 * backend relays its own chat, server-tagged, and the proxy relays nothing, so nothing is relayed
 * twice on a network where the plugin is installed everywhere. An owner who wants proxy-origin
 * relay instead flips the booleans in the dashboard, in both directions.
 *
 * <p>Only the <strong>outbound chat</strong> half is gated. {@code bridge.discord} is delivered
 * whenever the module is enabled: a proxy that relays no chat is still a perfectly good place to
 * show players what was said in Discord, and a network that turned relay off would otherwise
 * silently lose the inbound direction too.
 *
 * <h2>Why the observer is registered from a config listener rather than once at enable</h2>
 *
 * <p>A settings change does not re-enable a module — {@code ModuleManager} reconciles on the
 * {@code enabled} flag, and {@code ModuleContext.settings()} is documented as "read on every use"
 * for exactly that reason. So a module that decided at {@code enable()} whether to observe would be
 * permanently stuck on whatever {@code relayChat} said at that moment, and the dashboard toggle
 * would appear to do nothing until somebody switched the whole module off and on.
 *
 * <p>{@link #reconcileChatObserver()} is therefore called both at enable and from
 * {@link ModuleContext#onConfigChanged}, and it is idempotent: it registers when the setting says
 * yes and it has no registration, and closes when the setting says no and it has one. The handle it
 * holds is also tracked by the context, so a module disabled mid-flip is unwound either way.
 *
 * <h2>Threading</h2>
 *
 * <p>The chat observer runs on whatever thread the platform dispatched chat on — Bukkit's async chat
 * thread, a proxy's event executor — and does one thing: offer onto a lock-free queue. Join, quit
 * and death listeners run on {@code heimdall-io} and do the same. {@link #flush()} runs on
 * {@code heimdall-sched} once a second and is the only place a frame is built; it snapshots the
 * tunnel into a local before using it, because a scheduled flush can still be mid-run when
 * {@link #disable()} clears the field (cancelling a {@code ScheduledFuture} does not interrupt a run
 * already in progress).
 *
 * <p>The {@code bridge.discord} handler runs on {@code heimdall-io} — the default subscription
 * executor — never on the socket's reading thread. Sending to a player from there is safe on every
 * platform: {@code PlayerHandle} hops to the main thread itself where the platform needs it.
 */
public final class HeimdallBridgeModule implements HeimdallModule {

    /** The module's stable identifier, matching its key in the remote-config document. */
    public static final String ID = "bridge";

    /**
     * Whether this instance relays its own chat. Flat boolean, per-server, dashboard-owned.
     *
     * <p>Default depends on the role — see {@link #defaultRelayChat(ServerRole)}.
     */
    static final String SETTING_RELAY_CHAT = "relayChat";

    /** Batched chat, plugin → bot. */
    static final String FRAME_CHAT = "bridge.chat";

    /** Batched join/leave/death, plugin → bot. */
    static final String FRAME_EVENT = "bridge.event";

    /** Rendered Discord messages, bot → plugin. */
    static final String FRAME_DISCORD = "bridge.discord";

    /**
     * Hard cap on queued items, per family. The design's number, and half the console module's for a
     * feed that is worth far less stale — see {@link FrameBatcher}.
     */
    static final int MAX_QUEUE_SIZE = 500;

    /** Items shipped per flush, per family. The console module's {@code MAX_BATCH}. */
    static final int MAX_BATCH = 200;

    /** How often {@link #flush} runs. The console module's cadence, and the design's. */
    private static final long FLUSH_PERIOD_MS = 1000L;

    private final FrameBatcher<ChatLine> chat = new FrameBatcher<ChatLine>(
            FRAME_CHAT, "lines", new FrameBatcher.Encoder<ChatLine>() {
                @Override
                public Payload encode(ChatLine line) {
                    return line.toPayload();
                }
            }, MAX_QUEUE_SIZE, MAX_BATCH);

    private final FrameBatcher<SessionEvent> events = new FrameBatcher<SessionEvent>(
            FRAME_EVENT, "events", new FrameBatcher.Encoder<SessionEvent>() {
                @Override
                public Payload encode(SessionEvent event) {
                    return event.toPayload();
                }
            }, MAX_QUEUE_SIZE, MAX_BATCH);

    /** Snapshotted at {@link #enable}; {@code null} whenever this module is not enabled. */
    private volatile ModuleContext context;

    private volatile TunnelBus tunnel;

    /**
     * The chat observer's handle, held so {@link #reconcileChatObserver} can take it back when
     * {@code relayChat} is turned off without the module being disabled. {@link Registration#NONE}
     * means "not observing".
     *
     * <p>Guarded by {@link #observerLock} for writes, and volatile so {@link #isObservingChat} can
     * read it without taking the lock.
     */
    private volatile Registration chatObserver = Registration.NONE;

    /**
     * Serialises the check-then-act in {@link #reconcileChatObserver}.
     *
     * <p>Two threads really can be in there at once: {@link #enable} runs on whichever thread drives
     * module reconciliation, and the config listener fires on the socket's reading thread. Without
     * this, two calls could both observe {@link Registration#NONE} and both register — leaving a
     * doubled observer that relays every line twice and a handle nothing will ever close.
     *
     * <p>A dedicated lock rather than {@code synchronized} on the module, so it cannot ever contend
     * with something the manager holds.
     */
    private final Object observerLock = new Object();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        return Collections.singleton(Capabilities.BRIDGE);
    }

    @Override
    public Set<ServerRole> roles() {
        // Empty means "any role" (HeimdallModule#roles). Whether this instance relays is the
        // relayChat SETTING, not an eligibility rule — see the class javadoc. Excluding a role here
        // would mark the module INELIGIBLE and no dashboard toggle could bring it back, which is
        // exactly the trap the whitelist module's enforceOnBackend setting avoids.
        return Collections.emptySet();
    }

    @Override
    public void enable(ModuleContext context) {
        // Defensive: this instance is reused across enable/disable cycles by ModuleManager, so a
        // fresh enable starts from empty queues rather than whatever a previous cycle left behind.
        chat.clear();
        events.clear();

        this.context = context;
        this.tunnel = context.tunnel();

        reconcileChatObserver();
        context.onConfigChanged(new ModuleConfigListener() {
            @Override
            public void onModuleConfigChanged(
                    String moduleId, ModuleConfig previous, ModuleConfig current) {
                // Fired on the socket's reading thread and fired only on a real change, so this is
                // as cheap as it looks: a boolean read and, at most, one registration.
                reconcileChatObserver();
            }
        });

        context.onPlayerJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                events.enqueue(SessionEvent.of("join", player, null, timestampMs));
            }
        });
        context.onPlayerQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                events.enqueue(SessionEvent.of("leave", player, null, timestampMs));
            }
        });
        // Never fires on a proxy — neither Velocity nor BungeeCord has a death event. The backends
        // behind it report their own, which is where the message is authoritative. Departure D80.
        context.onPlayerDeath(new PlayerDeathListener() {
            @Override
            public void onPlayerDeath(PlayerHandle player, String deathMessage, long timestampMs) {
                events.enqueue(SessionEvent.of("death", player, deathMessage, timestampMs));
            }
        });

        // heimdall-io by default, which is what this needs: never the socket's reading thread, and
        // free to hop to the main thread inside PlayerHandle.sendMessage. Tracked by the context, so
        // a disabled module stops receiving without this class holding the handle.
        context.tunnel().subscribe(FRAME_DISCORD, new TunnelMessageHandler() {
            @Override
            public void onMessage(Envelope envelope) {
                deliverToPlayers(envelope.payload());
            }
        });

        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS);
    }

    @Override
    public void disable() {
        // Stop new lines arriving before discarding what is buffered, not the other way around —
        // otherwise a message could land in a queue this method has already decided is empty.
        //
        // Under the same lock as reconcileChatObserver, so a config push landing mid-teardown
        // cannot re-register an observer this method has just closed.
        synchronized (observerLock) {
            chatObserver.close();
            chatObserver = Registration.NONE;
            context = null;
        }
        tunnel = null;
        chat.clear();
        events.clear();
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    /**
     * Registers or unregisters the chat observer to match {@code relayChat}.
     *
     * <p>Idempotent, and called both at enable and on every config change — see the class javadoc
     * for why deciding once at enable would leave the dashboard toggle apparently dead.
     */
    private void reconcileChatObserver() {
        synchronized (observerLock) {
            boolean wanted = relayChat();
            if (wanted && chatObserver == Registration.NONE) {
                ModuleContext ctx = context;
                if (ctx == null) {
                    return;
                }
                chatObserver = ctx.observeChat(new ChatObserver() {
                    @Override
                    public void onChat(ChatMessage message) {
                        // Verbatim. Not trimmed, not normalised, not formatted — the bot renders,
                        // and a relay that silently edited what a player typed is worse than one
                        // that does not relay at all.
                        chat.enqueue(new ChatLine(
                                message.senderUuid(),
                                message.senderName(),
                                message.message(),
                                System.currentTimeMillis()));
                    }
                });
            } else if (!wanted && chatObserver != Registration.NONE) {
                chatObserver.close();
                chatObserver = Registration.NONE;
                // What is already queued is left to the next flush rather than dropped: it is chat
                // that was legitimately observed while relay was on, and one more second of it is
                // not a policy violation. Turning relay off stops NEW messages being taken, which
                // is what the setting means.
            }
        }
    }

    /**
     * Whether this instance relays its own chat, read live on every use.
     *
     * <p>Never cached in a field: {@link ModuleContext#settings()} is documented as a live read
     * precisely because a settings change does not re-enable the module.
     */
    private boolean relayChat() {
        ModuleContext ctx = context;
        if (ctx == null) {
            return false;
        }
        return ctx.settings().bool(SETTING_RELAY_CHAT, defaultRelayChat(ctx.platform().role()));
    }

    /**
     * The default for {@code relayChat} on a given role.
     *
     * <p>Backends relay, the gatekeeper does not. That is the locked default topology: per-server
     * mappings are the natural shape, mute enforcement will later live exactly where relay happens,
     * and a proxy relaying as well as its backends would double every line. A role the enum grows
     * later lands on the relaying side, which is the same answer {@code STANDALONE} gets and the one
     * that is right for anything that is not a proxy.
     */
    static boolean defaultRelayChat(ServerRole role) {
        return role != ServerRole.GATEKEEPER;
    }

    /**
     * Ships whatever is queued. Package-private so a test can call it directly instead of waiting on
     * the real one-second scheduler tick.
     */
    void flush() {
        // Snapshotted, not re-read: a concurrent disable() must not hand this a half-torn reference.
        TunnelBus bus = tunnel;
        if (bus == null) {
            return;
        }
        chat.flush(bus);
        events.flush(bus);
    }

    // ── Inbound ──────────────────────────────────────────────────────────────

    /**
     * Renders {@code bridge.discord} and shows it to everybody online.
     *
     * <p>Each {@code text} is a <strong>finished</strong> legacy-§ string. The bot resolved its
     * template and inserted the user's content after formatting, so nothing a Discord user types can
     * inject a colour code or template syntax here — which is why this may use {@link Msg#legacy}
     * rather than {@link Msg#plain}. The plugin carries no MiniMessage parser and never sees a
     * channel id, a Discord id, or raw user content outside the rendered line.
     *
     * <p>There is no broadcast primitive on {@code PlayerDirectory}, and that is fine: iterating
     * {@code onlinePlayers()} is what a broadcast would do anyway.
     */
    private void deliverToPlayers(Payload payload) {
        ModuleContext ctx = context;
        if (ctx == null) {
            return;
        }
        List<Payload> messages = payload.children("messages");
        if (messages.isEmpty()) {
            return;
        }

        Collection<PlayerHandle> online;
        try {
            online = ctx.platform().players().onlinePlayers();
        } catch (RuntimeException raced) {
            // The directory is allowed to throw rather than pretend the server is empty (see
            // PlayerDirectory#onlinePlayers). A relayed line lost to that is one line; the next one
            // is a second away.
            ctx.logger().debug(() -> "could not read the online list for a Discord relay: " + raced);
            return;
        }

        int rendered = 0;
        for (Payload message : messages) {
            String text = message.string("text", "");
            if (text.isEmpty()) {
                continue;
            }
            Component component = Msg.legacy(text);
            for (PlayerHandle player : online) {
                try {
                    player.sendMessage(component);
                } catch (RuntimeException gone) {
                    // A player who left between the snapshot and the send is the ordinary race, not
                    // an error — and every handle already tolerates it. This is the belt for a
                    // platform whose braces slipped.
                }
            }
            rendered++;
        }

        // Counts, never content. This line is also what the connected smoke asserts on, which is
        // only possible because it says how MANY rather than what.
        final int count = rendered;
        final int audience = online.size();
        ctx.logger().debug(() -> "relayed " + count + " discord message(s) to " + audience
                + " online player(s)");
    }

    // ── Wire values ──────────────────────────────────────────────────────────

    /**
     * One chat line, in flight.
     *
     * <p>Immutable, and it exists for the length of one queue hop. It is the only place a message
     * body lives between the pipeline and the wire; nothing reads it back out except
     * {@link #toPayload()}.
     */
    private static final class ChatLine {

        private final UUID uuid;
        private final String name;
        private final String message;
        private final long timestampMs;

        ChatLine(UUID uuid, String name, String message, long timestampMs) {
            this.uuid = uuid;
            this.name = name;
            this.message = message;
            this.timestampMs = timestampMs;
        }

        Payload toPayload() {
            return Payload.builder()
                    .put("uuid", uuid == null ? "" : uuid.toString())
                    .put("name", name == null ? "" : name)
                    // Verbatim: exactly what ChatMessage carried, which is exactly what the player
                    // typed.
                    .put("msg", message == null ? "" : message)
                    .put("ts", timestampMs)
                    .build();
        }

        /**
         * Renders the sender and the length, never the body — the same rule
         * {@code ChatMessage.toString()} follows, and for the same reason: {@code toString()} ends
         * up in debug logs and exception messages.
         */
        @Override
        public String toString() {
            return "ChatLine{sender='" + name + "', length="
                    + (message == null ? 0 : message.length()) + "}";
        }
    }

    /**
     * One join, leave or death, in flight.
     *
     * <p>{@code detail} carries the server's death message and is absent for everything else. It is
     * the server's own sentence rather than the player's, so unlike a chat body it is not sensitive
     * — but it is still not logged anywhere, because the cheapest rule to keep is one rule.
     */
    private static final class SessionEvent {

        private final String kind;
        private final UUID uuid;
        private final String name;
        private final String detail;
        private final long timestampMs;

        private SessionEvent(String kind, UUID uuid, String name, String detail, long timestampMs) {
            this.kind = kind;
            this.uuid = uuid;
            this.name = name;
            this.detail = detail;
            this.timestampMs = timestampMs;
        }

        /** @return {@code null} for a null handle, which {@link FrameBatcher#enqueue} then ignores */
        static SessionEvent of(String kind, PlayerHandle player, String detail, long timestampMs) {
            if (player == null) {
                return null;
            }
            return new SessionEvent(kind, player.uuid(), player.name(), detail, timestampMs);
        }

        Payload toPayload() {
            Payload.Builder builder = Payload.builder()
                    .put("kind", kind)
                    .put("uuid", uuid == null ? "" : uuid.toString())
                    .put("name", name == null ? "" : name)
                    .put("ts", timestampMs);
            if (detail != null && !detail.isEmpty()) {
                // Omitted rather than sent as null or "": the bot distinguishes "there was no death
                // message" from "the death message was empty", and a suppressed one is the first.
                builder.put("detail", detail);
            }
            return builder.build();
        }

        @Override
        public String toString() {
            return "SessionEvent{" + kind + " " + name + "}";
        }
    }

    // ── Visible for testing ──────────────────────────────────────────────────

    /** How many chat lines are currently queued. */
    int queuedChatCount() {
        return chat.queuedCount();
    }

    /** How many player events are currently queued. */
    int queuedEventCount() {
        return events.queuedCount();
    }

    /** Whether a chat observer is currently registered. */
    boolean isObservingChat() {
        return chatObserver != Registration.NONE;
    }
}
