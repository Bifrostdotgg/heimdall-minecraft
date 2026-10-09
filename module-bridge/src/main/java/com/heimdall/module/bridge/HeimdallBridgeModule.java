package com.heimdall.module.bridge;

import com.heimdall.core.config.ServerRole;
import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.HoverTags;
import com.heimdall.core.json.Envelope;
import com.heimdall.core.json.Payload;
import com.heimdall.core.module.HeimdallModule;
import com.heimdall.core.module.ModuleContext;
import com.heimdall.core.pipeline.ChatMessage;
import com.heimdall.core.pipeline.ChatObserver;
import com.heimdall.core.platform.ChatChannels;
import com.heimdall.core.platform.ItemImages;
import com.heimdall.core.platform.PlayerHandle;
import com.heimdall.core.remoteconfig.ModuleConfig;
import com.heimdall.core.remoteconfig.ModuleConfigListener;
import com.heimdall.core.session.PlayerDeathListener;
import com.heimdall.core.session.PlayerSessionListener;
import com.heimdall.core.text.Msg;
import com.heimdall.core.tunnel.Capabilities;
import com.heimdall.core.tunnel.ProtocolMode;
import com.heimdall.core.tunnel.ProtocolModeListener;
import com.heimdall.core.tunnel.TunnelBus;
import com.heimdall.core.tunnel.TunnelMessageHandler;
import com.heimdall.core.util.Registration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
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
 * <h2>{@code relayChat} and {@code relayEvents}: settings, not eligibility rules</h2>
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
 * <p>{@code relayEvents} is the same kind of setting for the join/leave/death half, and it exists
 * for the same reason: which instance <em>announces</em> a network's sessions is a per-deployment
 * answer ("the proxy announces joins, the backends only relay chat"), and before it existed the
 * three enqueues below ran unconditionally whenever the module was enabled, so there was no way to
 * express that at all.
 *
 * <p><strong>Its default is a flat {@code true} on every role, which is deliberately not
 * {@code relayChat}'s role-derived shape.</strong> The reason is upgrade continuity, and it carries
 * the default on its own: before this setting existed every enabled instance relayed its events, so
 * {@code true} everywhere is exactly today's behaviour and nobody's Discord goes quiet on upgrade.
 * {@code relayChat} could not be defaulted that way — chat is deduped nowhere, so a proxy relaying
 * alongside its backends puts every line into Discord twice, and its default has to encode a
 * topology to be correct at all.
 *
 * <p>The bot does collapse duplicate join/leave/death, but that is a <strong>best-effort defensive
 * drop rather than a guarantee</strong>, and it is not what makes this default safe. Its own
 * documentation calls it "a defensive drop, not the mechanism": it matches on a ~1 s timestamp
 * bucket, so two instances whose clocks differ by more than that leak a duplicate — as do a
 * uuid-carrying and a name-only observer of the same event, and an offline-mode backend behind an
 * online-mode proxy, whose UUIDs genuinely disagree. Several origins therefore degrade to a
 * <em>rare</em> duplicate rather than a guaranteed double: enough that this setting need not encode
 * a topology the way {@code relayChat}'s default does, not enough to call safe.
 *
 * <p>Only the <strong>outbound</strong> halves are gated. {@code bridge.discord} is delivered
 * whenever the module is enabled: a proxy that relays nothing outbound is still a perfectly good
 * place to show players what was said in Discord, and a network that turned relay off would
 * otherwise silently lose the inbound direction too.
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
 * <h2>Why {@code relayEvents} is gated at the enqueue instead, and not the same way</h2>
 *
 * <p>The requirement is identical — a dashboard flip has to take effect on a live {@code config.push}
 * without the module being switched off and on — but the mechanism is not, and the difference is
 * worth stating because the obvious move is to copy {@link #reconcileChatObserver()} three times.
 *
 * <p>{@link #relayEvents()} is read at the enqueue, on every event. That satisfies the liveness
 * requirement <strong>by construction</strong> rather than by a mechanism that has to be kept
 * correct: {@link ModuleContext#settings()} is documented as a live read, so there is no cached
 * state that could go stale and therefore nothing to reconcile. There is no registration lifecycle
 * here at all — no handle, no idempotency to preserve, no window in which a setting and a
 * registration disagree.
 *
 * <p>That is the whole argument for it. The registration approach would need three handles (join,
 * quit and death are three separate registrations), three branches under {@link #observerLock}, and
 * a check-then-act on each — which is precisely the shape whose race had to be fixed once already,
 * multiplied by three, to buy nothing. The cost of the alternative is one volatile read and a map
 * lookup per event, and session events arrive at human rates: a busy server produces a few a second,
 * against chat's hundreds. Cost is not what decides this, but it is what makes the simple option
 * available.
 *
 * <p>Chat is not moved to match, and that is not inconsistency. An unregistered {@code ChatObserver}
 * is a <em>structural</em> statement — the pipeline cannot hand this module a message it has not
 * subscribed to — and that is worth a lifecycle for the one thing here carrying player-authored text
 * (see the relay-only section above). A join is the player's own name and a timestamp, so there is
 * no equivalent property to buy.
 *
 * <p><strong>These are the only three session registrations the bridge makes, and they feed nothing
 * else.</strong> The whitelist module's mirror slides on its <em>own</em> {@code onPlayerJoin} /
 * {@code onPlayerQuit} registrations, made from its own {@code ModuleContext}, so declining to
 * enqueue here cannot affect it. Skipping the enqueue is genuinely local to the relay.
 *
 * <h2>Chat-plugin channels: a staff channel is not public chat</h2>
 *
 * <p>On a server running ChatControl channels, a line typed into {@code staff} was addressed to
 * staff. The platform tags such a line with its channel ({@link ChatMessage#channel()}), and this
 * module relays a tagged line <strong>only if that channel is in the {@code chatChannels}
 * setting</strong>: the per-server allowlist the bot computes from every Discord mapping's picked
 * channels. An absent or empty setting relays no channel lines at all, so the failure mode of a
 * missing config is silence, never a leak. Untagged lines (ordinary chat, a player not using
 * channels) are unaffected.
 *
 * <p>Inbound is the mirror image. A {@code bridge.discord} message naming a channel is shown only to
 * that channel's members; one naming none is shown to everybody, but only on a server where channels
 * are not in play, because on a channelled server "everybody" is not an audience anybody picked. And
 * {@link #FRAME_CHANNELS} tells the bot what channels exist, so the dashboard has something to pick
 * from. Departure D85.
 *
 * <h2>Items shown in chat: the one edit to a relayed line</h2>
 *
 * <p>ChatControl's {@code [item]} puts a MiniMessage {@code show_item} hover into the line. Each
 * well-formed one, with the text it decorates, is replaced by the plain {@code [Name]} the players
 * saw ({@link HoverTags}), and where the platform can draw ({@link ItemImages}, Bukkit backends
 * only) and the {@code itemImages} setting allows, the line also carries a tooltip-card PNG per item
 * in {@code items}. A line with no item hover is untouched, byte for byte. Departure D86.
 *
 * <p>An item line waits for its images for at most {@value #ITEM_IMAGE_BUDGET_MS} ms and then ships
 * with whatever finished; lines without items never wait for it, so an item line can reach the bot
 * after a later plain line (its {@code ts} still says when it was said). The wait holds the line in
 * memory for that budget and no longer, and at most {@value #MAX_PENDING_ITEM_LINES} lines at once;
 * beyond that a line ships as text. Images travel with the line and are not kept here.
 *
 * <h2>Threading</h2>
 *
 * <p>The chat observer runs on whatever thread the platform dispatched chat on — Bukkit's async chat
 * thread, a proxy's event executor — and does two things: offer onto a lock-free queue, and ask for
 * an immediate drain (a compare-and-set and an executor offer). Join, quit and death listeners run on
 * {@code heimdall-io} and do the same. The drain and the one-second {@link #flush()} both run on the
 * single {@code heimdall-sched} thread and are the only places a frame is built; they snapshot the
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

    /**
     * Whether this instance relays its own join/leave/death. Flat boolean, per-server,
     * dashboard-owned.
     *
     * <p>Defaults to {@code true} on every role — see {@link #DEFAULT_RELAY_EVENTS} for why that is
     * not {@code relayChat}'s role-derived default.
     */
    static final String SETTING_RELAY_EVENTS = "relayEvents";

    /**
     * The default for {@code relayEvents}, on every role.
     *
     * <p>A constant rather than a {@code defaultRelayEvents(ServerRole)} alongside
     * {@link #defaultRelayChat(ServerRole)}, because the role genuinely does not enter into it and a
     * method taking one would imply it might. Two reasons it is {@code true} everywhere:
     *
     * <ul>
     *   <li><strong>It keeps today's behaviour exactly</strong>, and this is the reason that carries
     *       it. Before this setting existed the three enqueues were unconditional whenever the
     *       module was enabled, so any other default would silently stop relaying events for every
     *       deployment that upgrades.
     *   <li><strong>Duplicate events degrade rather than double.</strong> The bot drops duplicate
     *       join/leave/death defensively, which is why this default need not encode a topology the
     *       way {@code relayChat}'s must — chat is deduped nowhere and really would appear twice.
     *       But it is best-effort, not a guarantee: it matches within a ~1 s timestamp bucket and
     *       leaks past clock skew, a uuid-vs-name key mismatch, or an offline-mode backend behind
     *       an online-mode proxy. Multiple origins mean a rare duplicate, not a safe configuration.
     * </ul>
     */
    static final boolean DEFAULT_RELAY_EVENTS = true;

    /** Batched chat, plugin → bot. */
    static final String FRAME_CHAT = "bridge.chat";

    /** Batched join/leave/death, plugin → bot. */
    static final String FRAME_EVENT = "bridge.event";

    /** Rendered Discord messages, bot → plugin. */
    static final String FRAME_DISCORD = "bridge.discord";

    /**
     * The chat-plugin channel inventory, plugin to bot: {@code {"state", "channels"}}.
     *
     * <p>Sent on the first flush after enable, again on the first flush after every reconnect (a
     * frame sent into a dying socket is lost silently, so the bot is assumed to know nothing after
     * one), and whenever the state or the channel list changes. Never otherwise. See
     * {@link #reportInventory}.
     */
    static final String FRAME_CHANNELS = "bridge.channels";

    /**
     * The chat-plugin channels this server relays to Discord: a JSON array of channel names, read
     * live like {@code relayChat}. Absent means an empty list, which relays no channel lines at all.
     */
    static final String SETTING_CHAT_CHANNELS = "chatChannels";

    /**
     * Whether this server attaches tooltip images to lines that show an item. Flat boolean,
     * per-server, read live; default on. Off still rewrites each item hover to {@code [Name]}: that
     * part is about the text being readable, not about images. Departure D86.
     */
    static final String SETTING_ITEM_IMAGES = "itemImages";

    /** The default for {@link #SETTING_ITEM_IMAGES}. */
    static final boolean DEFAULT_ITEM_IMAGES = true;

    /** Images attached to one line at most; the rest are dropped and counted. The wire contract. */
    static final int MAX_ITEMS_PER_LINE = 4;

    /** Largest PNG attached, before base64. A bigger one is dropped and counted. The wire contract. */
    static final int MAX_ITEM_PNG_BYTES = 512 * 1024;

    /**
     * How long an item line waits for its images before shipping with whatever finished. Long
     * enough for a cached or warm render, short enough that the line still reads as live.
     */
    static final long ITEM_IMAGE_BUDGET_MS = 750L;

    /**
     * Item lines allowed to wait for images at once. Past this a line ships as text straight away:
     * a burst of item spam must not turn into a growing set of held lines.
     */
    static final int MAX_PENDING_ITEM_LINES = 8;

    /**
     * The most a {@code bridge.chat} frame may carry, by estimated encoded size: 3 MiB, under the
     * new bot's 4 MiB tunnel cap with room for the envelope. A line that would push a frame past it
     * waits for the next frame; a line that alone would exceed it loses its largest images first.
     */
    static final long MAX_CHAT_FRAME_BYTES = 3L * 1024 * 1024;

    /** What a frame's envelope and array punctuation are allowed, inside {@link #MAX_CHAT_FRAME_BYTES}. */
    static final long FRAME_OVERHEAD_BYTES = 4L * 1024;

    /**
     * How many one-second flushes pass between inventory polls. A ChatControl reload is an operator
     * action, so five seconds of lag before the dashboard sees a new channel is not worth a tighter
     * loop of reflective calls.
     */
    static final int INVENTORY_POLL_FLUSHES = 5;

    /**
     * Hard cap on queued items, per family. The design's number, and half the console module's for a
     * feed that is worth far less stale — see {@link FrameBatcher}.
     */
    static final int MAX_QUEUE_SIZE = 500;

    /** Items shipped per flush, per family. The console module's {@code MAX_BATCH}. */
    static final int MAX_BATCH = 200;

    /**
     * Messages relayed in-game from a single {@code bridge.discord} frame; the rest are dropped.
     *
     * <p>Smaller than the outbound caps on purpose, because this is the only loop here whose work is
     * multiplicative — messages × online players, each send a main-thread task on Bukkit — and
     * because the bot coalesces before sending, so a frame anywhere near this size already means
     * something upstream is wrong. See {@link #deliverToPlayers}.
     */
    static final int MAX_INBOUND_MESSAGES = 50;

    /** How often {@link #flush} runs. The console module's cadence, and the design's. */
    private static final long FLUSH_PERIOD_MS = 1000L;

    /**
     * Set while an immediate drain is queued on {@code heimdall-sched}, so a burst of lines queues
     * ONE drain rather than one per line.
     *
     * <p>Chat used to wait for the one-second {@link #flush} tick, and the bot then waited for its
     * own one-second tick, so a line took up to two seconds to reach Discord while a Discord message
     * reached the channel instantly: conversations crossed in flight. A line now asks for a drain
     * the moment it is queued. Lines that arrive while that drain is waiting to run ride in the same
     * frame, which is where the batching now comes from: only as much as the moment requires.
     */
    private final AtomicBoolean drainRequested = new AtomicBoolean();

    /**
     * Shortest gap between two drains. Without it, sustained chat (a spam bot, a broadcast plugin)
     * became one frame per line, each a full route through the gateway and the bot. Fifty
     * milliseconds is below anything a reader notices and still packs a flood into about twenty
     * frames a second.
     */
    static final long MIN_DRAIN_SPACING_MS = 50L;

    /** When the last drain started, in {@code System.nanoTime()} milliseconds; 0 for never. */
    private volatile long lastDrainAtMs;

    /** Schedules an immediate drain. The production one is {@code heimdall-sched}. */
    interface DrainScheduler {
        void schedule(Runnable drain, long delayMs);
    }

    /**
     * Where item-image budget timeouts are scheduled. {@code null} means {@code heimdall-sched}.
     * Tests substitute a manual one so "the budget ran out" happens when they say.
     */
    private volatile DrainScheduler budgetScheduler;

    /** Item lines currently waiting for images. Bounded by {@link #MAX_PENDING_ITEM_LINES}. */
    private final AtomicInteger pendingItemLines = new AtomicInteger();

    /**
     * Where immediate drains are scheduled. {@code null} means {@code heimdall-sched}, the single
     * thread {@link #flush} already runs on, so a drain and a tick can never overlap. Tests
     * substitute a manual scheduler so a queued line stays queued until they say otherwise.
     */
    private volatile DrainScheduler drainScheduler;

    private final Runnable drainNow = new Runnable() {
        @Override
        public void run() {
            lastDrainAtMs = nowMs();
            // Cleared BEFORE draining, so a line queued while this runs can ask for the next drain.
            // The backlog check below is the belt for the same case: whatever is still queued once
            // this drain has shipped asks again rather than waiting for the tick.
            drainRequested.set(false);
            drainQueues();
            // A drain ships at most MAX_BATCH per family. A burst bigger than that asks again
            // straight away rather than leaving the rest for the tick.
            if (chat.queuedCount() > 0 || events.queuedCount() > 0) {
                requestDrain();
            }
        }
    };

    private static long nowMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private final FrameBatcher<ChatLine> chat = new FrameBatcher<ChatLine>(
            FRAME_CHAT, "lines", new FrameBatcher.Encoder<ChatLine>() {
                @Override
                public Payload encode(ChatLine line) {
                    // Checked again at the wire, not only when the line was queued: a reconnect in
                    // between can land on an older bot that would close the socket on an image.
                    return line.toPayload(imagesAccepted(tunnel));
                }
            }, MAX_QUEUE_SIZE, MAX_BATCH, new FrameBatcher.Sizer<ChatLine>() {
                @Override
                public long estimatedBytes(ChatLine line) {
                    return line.estimatedBytes();
                }
            }, MAX_CHAT_FRAME_BYTES - FRAME_OVERHEAD_BYTES);

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

    /**
     * Guards what the bot was last told. Taken by {@link #reportInventory} alone, around the
     * compare-and-send, and never while calling the channel integration (which can reach the
     * server's plugin-manager lock). Nothing else takes it, so no thread can ever wait on it; see
     * {@link #forgetInventory} for why that matters.
     */
    private final Object inventoryLock = new Object();

    /**
     * What the bot was last told, or {@code null} for "nothing yet, or nothing it can be assumed to
     * still know". Guarded by {@link #inventoryLock}.
     */
    private ChatChannels.State reportedState;

    /** The names that went with {@link #reportedState}. Guarded by {@link #inventoryLock}. */
    private List<String> reportedNames = Collections.emptyList();

    /** Flushes since the last inventory poll. Only {@code heimdall-sched} touches it. */
    private int flushesSinceInventoryPoll;

    /**
     * Set by {@link #forgetInventory}; applied, and cleared, by the next report.
     *
     * <p>This and {@link #inventoryReportRequested} overlap on purpose: each alone makes a
     * reconnect resend the inventory (one by making the next poll find a change, the other by
     * forcing the next flush), so a cleanup may remove one, never both.
     */
    private final AtomicBoolean forgetRequested = new AtomicBoolean();

    /** Set by enable and by a reconnect; the next flush makes a forced report and clears it. */
    private final AtomicBoolean inventoryReportRequested = new AtomicBoolean();

    /** Issued to each inventory read before it starts; see {@link #reportInventory}. */
    private final AtomicLong inventoryStamp = new AtomicLong();

    /** The stamp of the read that was last sent. Guarded by {@link #inventoryLock}. */
    private long sentStamp;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<String> capabilities() {
        // Both, always. CHAT_CHANNELS is a build capability like STATUS, not a module of its own: it
        // tells the bot this client understands the channel key, the allowlist and the inventory
        // frame, and it is true of this build whether or not ChatControl is installed.
        // ITEM_IMAGES likewise: it says this client rewrites item hovers and may attach `items`. A
        // proxy or an image-less backend declares it too and simply never attaches any.
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
                Capabilities.BRIDGE, Capabilities.CHAT_CHANNELS, Capabilities.ITEM_IMAGES)));
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
        // A drain refused or abandoned by a previous cycle must not leave the request latched.
        drainRequested.set(false);
        lastDrainAtMs = 0L;

        this.context = context;
        this.tunnel = context.tunnel();

        reconcileChatObserver();
        prepareItemImages();
        context.onConfigChanged(new ModuleConfigListener() {
            @Override
            public void onModuleConfigChanged(
                    String moduleId, ModuleConfig previous, ModuleConfig current) {
                // Fired on the socket's reading thread and fired only on a real change, so this is
                // as cheap as it looks: a boolean read and, at most, one registration. Preparing
                // item images only submits work to the renderer's own thread.
                reconcileChatObserver();
                prepareItemImages();
            }
        });

        // All three go through relayEvent, so the relayEvents gate is ONE decision rather than three
        // that have to agree. A fourth kind added later is gated as long as it is routed the same
        // way — a convention this file keeps, not something the types enforce: `events` is still an
        // ordinary field any method here could enqueue onto directly.
        context.onPlayerJoin(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                relayEvent("join", player, null, timestampMs);
            }
        });
        context.onPlayerQuit(new PlayerSessionListener() {
            @Override
            public void onPlayerSession(PlayerHandle player, long timestampMs) {
                relayEvent("leave", player, null, timestampMs);
            }
        });
        // Never fires on a proxy — neither Velocity nor BungeeCord has a death event. The backends
        // behind it report their own, which is where the message is authoritative. Departure D80.
        context.onPlayerDeath(new PlayerDeathListener() {
            @Override
            public void onPlayerDeath(PlayerHandle player, String deathMessage, long timestampMs) {
                relayEvent("death", player, deathMessage, timestampMs);
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

        // A reconnect means the bot may know nothing about this server's channels: whatever was sent
        // before went to a socket that is gone, possibly to a bot that has since restarted. So the
        // record of what it was told is wiped on the way down and a report is requested on the way
        // up. Tracked by the context like every other registration here.
        //
        // Requested, never made here. The negotiator invokes this listener while holding its own
        // monitor, and reading the channel integration can reach Bukkit's plugin manager, whose
        // monitor the server holds while disabling plugins; a disable tears the tunnel down, which
        // needs the negotiator's monitor. Reporting inline would be a deadlock at shutdown or
        // /reload. The next flush, on heimdall-sched with no lock held, makes the report.
        context.tunnel().onModeChange(new ProtocolModeListener() {
            @Override
            public void onModeChanged(ProtocolMode previous, ProtocolMode current) {
                if (current == ProtocolMode.UNKNOWN) {
                    forgetInventory();
                } else {
                    inventoryReportRequested.set(true);
                }
            }
        });

        context.scheduleRepeating(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, FLUSH_PERIOD_MS, FLUSH_PERIOD_MS);

        // The first report goes out on the first flush, a second from now, for the same reason as
        // the reconnect above: enable can run under the module manager's own lock, and the server
        // can hold its plugin-manager monitor while waiting for that lock during a disable.
        forgetInventory();
        inventoryReportRequested.set(true);
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
        forgetInventory();
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
                        // A channel line is relayed only if this server was told to relay that
                        // channel. Fail closed: a staff channel nobody mapped stays in the game.
                        String channel = message.channel();
                        if (channel != null && !channelAllowed(channel)) {
                            return;
                        }
                        relayLine(message, channel);
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
     * Queues one observed line: verbatim when it shows no item, otherwise with each item hover
     * replaced by {@code [Name]} and, where possible, tooltip images attached.
     *
     * <p>Runs on the chat thread, so it keeps the observer's rules: no blocking, no throwing, and
     * nothing logged that carries text. The rewrite is string work bounded by {@link HoverTags}; the
     * rendering happens on the platform's own executor and is only waited for off this thread.
     */
    private void relayLine(ChatMessage message, String channel) {
        long timestampMs = System.currentTimeMillis();
        String text = message.message();
        if (!HoverTags.mightContainItem(text)) {
            // Verbatim. Not trimmed, not normalised, not formatted - the bot renders, and a relay
            // that silently edited what a player typed is worse than one that does not relay at all.
            enqueueLine(new ChatLine(message.senderUuid(), message.senderName(), text, channel,
                    timestampMs, NO_ITEMS));
            return;
        }
        ModuleContext ctx = context;
        ItemImages images = itemImages(ctx);
        HoverTags.Rewrite rewrite;
        try {
            rewrite = HoverTags.rewrite(text, images);
        } catch (RuntimeException unexpected) {
            // HoverTags never throws on any input; a translation source might. Either way the line
            // still relays, exactly as it was.
            rewrite = null;
        }
        if (rewrite == null) {
            enqueueLine(new ChatLine(message.senderUuid(), message.senderName(), text, channel,
                    timestampMs, NO_ITEMS));
            return;
        }
        ChatLine textOnly = new ChatLine(message.senderUuid(), message.senderName(),
                rewrite.text(), channel, timestampMs, NO_ITEMS);
        // Only to a bot that accepted itemimages@1 in this connection's handshake. A released bot
        // closes its socket on a frame over its (1 MiB) payload cap, so it gets the [Name] text and
        // nothing is drawn for it at all.
        if (ctx == null || !itemImagesEnabled(ctx) || !imagesAccepted(tunnel)
                || !available(images)) {
            enqueueLine(textOnly);
            return;
        }
        attachImages(ctx, images, textOnly, rewrite);
    }

    private void enqueueLine(ChatLine line) {
        chat.enqueue(line);
        requestDrain();
    }

    /**
     * Starts one render per distinct item (at most {@value #MAX_ITEMS_PER_LINE}) and ships the line
     * when they have all finished or {@value #ITEM_IMAGE_BUDGET_MS} ms have passed, whichever is
     * first. Exactly one of those ships it.
     */
    private void attachImages(
            final ModuleContext ctx, ItemImages images, final ChatLine textOnly,
            HoverTags.Rewrite rewrite) {
        // One image per distinct item: the same item shown twice is the same picture.
        Map<String, Integer> seen = new HashMap<String, Integer>();
        List<ChatItem> distinct = new ArrayList<ChatItem>();
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < rewrite.items().size(); i++) {
            ChatItem item = rewrite.items().get(i);
            if (seen.put(item.cacheKey(), i) == null) {
                distinct.add(item);
                names.add(rewrite.names().get(i));
            }
        }
        final int extra = Math.max(0, distinct.size() - MAX_ITEMS_PER_LINE);
        if (extra > 0) {
            distinct = distinct.subList(0, MAX_ITEMS_PER_LINE);
            names = names.subList(0, MAX_ITEMS_PER_LINE);
            ctx.logger().debug(() -> "a chat line showed more than " + MAX_ITEMS_PER_LINE
                    + " items; dropped " + extra + " image(s)");
        }

        if (pendingItemLines.incrementAndGet() > MAX_PENDING_ITEM_LINES) {
            pendingItemLines.decrementAndGet();
            ctx.logger().debug("too many chat lines waiting on item images; sent one as text");
            enqueueLine(textOnly);
            return;
        }

        List<CompletableFuture<byte[]>> renders = new ArrayList<CompletableFuture<byte[]>>();
        for (ChatItem item : distinct) {
            renders.add(startRender(images, item));
        }
        final PendingItemLine pending = new PendingItemLine(textOnly, names, renders);
        for (CompletableFuture<byte[]> render : renders) {
            // Plain whenComplete, never the executor-less *Async: this runs on whichever thread
            // finished the render (or inline, if it was already done), and all it does is a check
            // and, once, a lock-free enqueue.
            render.whenComplete(new BiConsumer<byte[], Throwable>() {
                @Override
                public void accept(byte[] png, Throwable failed) {
                    if (pending.allDone()) {
                        shipPending(pending);
                    }
                }
            });
        }
        if (pending.isShipped()) {
            return;
        }
        Runnable budget = new Runnable() {
            @Override
            public void run() {
                shipPending(pending);
            }
        };
        try {
            DrainScheduler scheduler = budgetScheduler;
            if (scheduler != null) {
                scheduler.schedule(budget, ITEM_IMAGE_BUDGET_MS);
            } else {
                ctx.executors().scheduler()
                        .schedule(budget, ITEM_IMAGE_BUDGET_MS, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException refused) {
            // Shutting down: there is nobody to wait for, so ship what there is now.
            shipPending(pending);
        }
    }

    /** {@link ItemImages#render}, with a throw or a {@code null} future turned into "no image". */
    private static CompletableFuture<byte[]> startRender(ItemImages images, ChatItem item) {
        try {
            CompletableFuture<byte[]> render = images.render(item);
            return render != null ? render : CompletableFuture.<byte[]>completedFuture(null);
        } catch (Throwable failed) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Ships a pending item line with whatever images finished, once. Called by the last render to
     * complete and by the budget timeout; the loser of that race does nothing.
     */
    private void shipPending(PendingItemLine pending) {
        if (!pending.markShipped()) {
            return;
        }
        pendingItemLines.decrementAndGet();
        List<Payload> items = new ArrayList<Payload>();
        int missing = 0;
        int oversized = 0;
        for (int i = 0; i < pending.renders.size(); i++) {
            byte[] png = finishedPng(pending.renders.get(i));
            if (png == null || png.length == 0) {
                missing++;
                continue;
            }
            if (png.length > MAX_ITEM_PNG_BYTES) {
                oversized++;
                continue;
            }
            items.add(Payload.builder()
                    .put("name", pending.names.get(i))
                    .put("png", Base64.getEncoder().encodeToString(png))
                    .build());
        }
        ModuleContext ctx = context;
        if (ctx == null) {
            // Disabled while the images were drawing. The queue has been cleared and there is no
            // flush to bound it, so the line goes nowhere, like anything else queued at disable.
            return;
        }
        ChatLine line = pending.line.withItems(items);
        int trimmed = 0;
        while (line.estimatedBytes() > MAX_CHAT_FRAME_BYTES - FRAME_OVERHEAD_BYTES
                && !line.items.isEmpty()) {
            line = line.withoutLargestItem();
            trimmed++;
        }
        enqueueLine(line);
        final int attached = line.items.size();
        final int none = missing;
        if (trimmed > 0) {
            ctx.logger().warn("dropped " + trimmed + " item image(s) so a relayed chat line fits "
                    + "the " + MAX_CHAT_FRAME_BYTES + "-byte frame budget");
        }
        if (oversized > 0) {
            ctx.logger().warn("dropped " + oversized + " item image(s) larger than "
                    + MAX_ITEM_PNG_BYTES + " bytes from a relayed chat line");
        }
        ctx.logger().debug(() -> "relayed a chat line with " + attached + " item image(s)"
                + (none == 0 ? "" : "; " + none + " not ready or failed"));
    }

    /** A render's PNG if it has finished successfully, else {@code null}. Never waits. */
    private static byte[] finishedPng(CompletableFuture<byte[]> render) {
        if (!render.isDone() || render.isCompletedExceptionally() || render.isCancelled()) {
            return null;
        }
        try {
            return render.getNow(null);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /** Whether the bot on {@code bus} accepted {@code itemimages@1}; false for no bus. */
    private static boolean imagesAccepted(TunnelBus bus) {
        if (bus == null) {
            return false;
        }
        try {
            return bus.peerAccepts(Capabilities.ITEM_IMAGES);
        } catch (RuntimeException failed) {
            return false;
        }
    }

    /** Whether item images are wanted on this server, read live like {@link #relayChat()}. */
    private static boolean itemImagesEnabled(ModuleContext ctx) {
        return ctx.settings().bool(SETTING_ITEM_IMAGES, DEFAULT_ITEM_IMAGES);
    }

    /** The platform's renderer, never {@code null}. */
    private static ItemImages itemImages(ModuleContext ctx) {
        if (ctx == null) {
            return ItemImages.NONE;
        }
        try {
            ItemImages images = ctx.platform().integrations().itemImages();
            return images == null ? ItemImages.NONE : images;
        } catch (RuntimeException failed) {
            return ItemImages.NONE;
        }
    }

    private static boolean available(ItemImages images) {
        try {
            return images.available();
        } catch (RuntimeException failed) {
            return false;
        }
    }

    /**
     * Asks the renderer to get its assets ready, when images are wanted and relay is on. Called at
     * enable and on every config change; {@link ItemImages#prepare} is idempotent and non-blocking,
     * so a repeat costs nothing. With {@code itemImages} off nothing is prepared, which is what keeps
     * a server that opted out from ever downloading the vanilla assets.
     */
    private void prepareItemImages() {
        ModuleContext ctx = context;
        if (ctx == null || !relayChat() || !itemImagesEnabled(ctx)) {
            return;
        }
        ItemImages images = itemImages(ctx);
        try {
            if (images.available()) {
                images.prepare();
            }
        } catch (RuntimeException failed) {
            ctx.logger().debug(() -> "item image renderer could not start preparing: "
                    + failed.getClass().getName());
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
     * Whether a channel line may be relayed: {@code channel} is in the {@code chatChannels} setting,
     * compared case-insensitively.
     *
     * <p>Read live per line, like {@link #relayChat()}, so a dashboard change takes effect on the
     * next {@code config.push} with nothing to reconcile. The list is a handful of names, so a scan
     * per line costs nothing worth caching, and a cache would be one more thing that could go stale.
     *
     * <p>Case-insensitive because the names reach the setting through a dashboard picker rather than
     * from ChatControl directly, and a difference in case is not a reason to silently drop a mapped
     * channel. It cannot widen anything: a name only matches a channel the operator picked.
     */
    private boolean channelAllowed(String channel) {
        ModuleContext ctx = context;
        if (ctx == null) {
            return false;
        }
        for (String allowed : ctx.settings().strings(SETTING_CHAT_CHANNELS)) {
            if (allowed != null && allowed.equalsIgnoreCase(channel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queues one session event, unless {@code relayEvents} says this instance is not the origin.
     *
     * <p>The single choke point for all three kinds — see the class javadoc for why the gate is
     * here, at the enqueue, rather than in a reconcile that registers and unregisters the three
     * listeners the way {@link #reconcileChatObserver()} does for chat.
     *
     * <p>The setting is read per event, which is what makes a dashboard flip take effect on a live
     * {@code config.push} with no module restart and nothing to reconcile. Session events arrive at
     * human rates, so a volatile read and a map lookup each is not a cost worth designing around.
     *
     * <p>What is already queued when the setting goes off is left to the next flush rather than
     * dropped, exactly as {@link #reconcileChatObserver} leaves observed chat: those events happened
     * while this instance was legitimately the origin. Turning it off stops NEW events being taken,
     * which is what the setting means.
     */
    private void relayEvent(String kind, PlayerHandle player, String detail, long timestampMs) {
        if (!relayEvents()) {
            return;
        }
        events.enqueue(SessionEvent.of(kind, player, detail, timestampMs));
        requestDrain();
    }

    /**
     * Asks for the queues to be shipped now, on {@code heimdall-sched}.
     *
     * <p>Called from wherever a line or event arrived, so it keeps {@link FrameBatcher#enqueue}'s
     * rules: no blocking, no logging, no throwing. An executor that refuses (shutting down) simply
     * leaves the line to the one-second tick, or to {@link #disable}'s clear, and resets the request
     * so the next line can try again.
     *
     * <p>Spaced at least {@link #MIN_DRAIN_SPACING_MS} after the previous drain, so a quiet line
     * still leaves at once and a flood is packed rather than shipped line by line.
     */
    private void requestDrain() {
        if (context == null || !drainRequested.compareAndSet(false, true)) {
            return;
        }
        try {
            long last = lastDrainAtMs;
            long delayMs = last == 0L ? 0L : Math.max(0L, last + MIN_DRAIN_SPACING_MS - nowMs());
            DrainScheduler scheduler = drainScheduler;
            if (scheduler != null) {
                scheduler.schedule(drainNow, delayMs);
                return;
            }
            ModuleContext ctx = context;
            if (ctx == null) {
                drainRequested.set(false);
                return;
            }
            ctx.executors().scheduler().schedule(drainNow, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            drainRequested.set(false);
        } catch (RuntimeException unexpected) {
            drainRequested.set(false);
        }
    }

    /**
     * Whether this instance relays its own join/leave/death, read live on every use.
     *
     * <p>Never cached in a field, for the same reason {@link #relayChat()} is not: {@link
     * ModuleContext#settings()} is documented as a live read precisely because a settings change
     * does not re-enable the module.
     */
    private boolean relayEvents() {
        ModuleContext ctx = context;
        if (ctx == null) {
            // Disabled, or mid-teardown. The listeners are unregistered by then, so this is the belt
            // rather than the braces — but a "disabled" module queueing an event would be the same
            // failure the tracked-registration design exists to prevent.
            return false;
        }
        return ctx.settings().bool(SETTING_RELAY_EVENTS, DEFAULT_RELAY_EVENTS);
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
        flushTail();
    }

    /**
     * Ships the chat and event queues and nothing else: the immediate path. The inventory poll stays
     * on the one-second {@link #flush} so its cadence does not speed up with chat.
     */
    void drainQueues() {
        TunnelBus bus = tunnel;
        if (bus == null) {
            return;
        }
        chat.flush(bus);
        events.flush(bus);
    }

    /** The once-a-second part of {@link #flush}: the channel inventory poll. */
    private void flushTail() {
        // The inventory poll rides on the flush rather than a schedule of its own: one fewer
        // registration to track, and the cadence only has to be roughly right. A requested report
        // (enable, reconnect) is made here too, and forced: this is the one place the integration is
        // read with no lock of Heimdall's, the tunnel's or the module manager's held.
        boolean requested = inventoryReportRequested.getAndSet(false);
        if (requested || ++flushesSinceInventoryPoll >= INVENTORY_POLL_FLUSHES) {
            flushesSinceInventoryPoll = 0;
            reportInventory(requested);
        }
    }

    // ── Channel inventory ────────────────────────────────────────────────────

    /**
     * Sends {@link #FRAME_CHANNELS} if the bot has not been told, or if what it was told has changed.
     *
     * <p><strong>Called from {@link #flush} only</strong> (and tests), never from a listener or a
     * lifecycle method: see the comments in {@link #enable} for the locks that rule those out.
     *
     * <p>{@code force} sends regardless of what was last reported, and is what a requested report
     * (enable, reconnect) uses. Either way nothing is recorded as sent while the tunnel is down: the frame would
     * go nowhere, and recording it would suppress the resend the next poll would otherwise make.
     *
     * <p>Package-private so a test can drive a poll without running five flushes.
     */
    void reportInventory(boolean force) {
        ModuleContext ctx = context;
        TunnelBus bus = tunnel;
        if (ctx == null || bus == null) {
            return;
        }
        // Read OUTSIDE inventoryLock, always. The integration can reach the server's plugin manager
        // (Bukkit's getPlugin is synchronized on it), and the server disables plugins while holding
        // that same monitor; a disable reaches forgetInventory(), which takes inventoryLock. Reading
        // under inventoryLock would be one half of a lock-order inversion, a deadlock at shutdown or
        // /reload.
        //
        // The stamp is what reading under the lock used to buy: a forced report on the socket thread
        // and a poll on heimdall-sched can each read, then reach the send in the opposite order. The
        // stamp is taken before reading, so the read that started later carries the larger one, and
        // an older read arriving after a newer send is dropped rather than overwriting it.
        long stamp = inventoryStamp.incrementAndGet();
        ChatChannels channels = chatChannels(ctx);
        ChatChannels.State state = stateOf(channels);
        List<String> names = Collections.emptyList();
        if (state == ChatChannels.State.ACTIVE) {
            try {
                names = Collections.unmodifiableList(new ArrayList<String>(channels.channelNames()));
            } catch (RuntimeException failed) {
                // The interface says this cannot throw. If it does anyway, the honest report is that
                // the channel plugin cannot be read, which is exactly what BROKEN means.
                state = ChatChannels.State.BROKEN;
            }
        }

        synchronized (inventoryLock) {
            if (forgetRequested.getAndSet(false)) {
                reportedState = null;
                reportedNames = Collections.emptyList();
            }
            if (stamp < sentStamp) {
                return;
            }
            if (!force && state == reportedState && names.equals(reportedNames)) {
                // A newer read confirmed what the bot already has. Recording its stamp is what
                // stops an older read still in flight (one that saw a state since reverted) from
                // being sent after it: X, then a slow read of Y, then X again must leave the bot on X.
                sentStamp = stamp;
                return;
            }
            if (!bus.isConnected()) {
                reportedState = null;
                reportedNames = Collections.emptyList();
                return;
            }
            bus.send(FRAME_CHANNELS, Payload.builder()
                    .put("state", state.wireName())
                    .putStrings("channels", names)
                    .build());
            reportedState = state;
            reportedNames = names;
            sentStamp = stamp;
        }
        final ChatChannels.State sent = state;
        final int count = names.size();
        ctx.logger().debug(() -> "reported chat channels to the bot: " + sent.wireName() + ", "
                + count + " channel(s)");
    }

    /**
     * Forgets what the bot was told, so the next report sends whatever it finds.
     *
     * <p>Lock-free: it only raises a flag that the next {@link #reportInventory} applies under
     * {@link #inventoryLock}. This runs from the tunnel's mode listener, which the negotiator calls
     * while holding its own monitor, and from disable, which can run under the server's
     * plugin-manager monitor. Taking {@code inventoryLock} here would make those threads wait on a
     * lock that is held around a socket send, and whether the WebSocket library's own locks could
     * close that loop is not something this class can see. So nothing but
     * {@link #reportInventory} ever takes {@code inventoryLock}, and nothing can wait on it.
     */
    private void forgetInventory() {
        forgetRequested.set(true);
    }

    /** The platform's channel integration, never {@code null}. */
    private static ChatChannels chatChannels(ModuleContext ctx) {
        try {
            ChatChannels channels = ctx.platform().integrations().chatChannels();
            return channels == null ? BROKEN_CHANNELS : channels;
        } catch (RuntimeException failed) {
            return BROKEN_CHANNELS;
        }
    }

    /** {@link ChatChannels#state()}, with a throw or a null treated as the BROKEN it would mean. */
    private static ChatChannels.State stateOf(ChatChannels channels) {
        try {
            ChatChannels.State state = channels.state();
            return state == null ? ChatChannels.State.BROKEN : state;
        } catch (RuntimeException failed) {
            return ChatChannels.State.BROKEN;
        }
    }

    /**
     * What a platform whose integration accessor threw, or answered {@code null}, is treated as. Fail
     * closed: nothing routed by channel, nothing broadcast on the assumption that channels are not in
     * play.
     */
    private static final ChatChannels BROKEN_CHANNELS = new ChatChannels() {
        @Override
        public State state() {
            return State.BROKEN;
        }

        @Override
        public List<String> channelNames() {
            return Collections.emptyList();
        }

        @Override
        public Optional<Collection<PlayerHandle>> members(String channel) {
            return Optional.empty();
        }
    };

    // ── Inbound ──────────────────────────────────────────────────────────────

    /**
     * Renders {@code bridge.discord} and shows it to the audience it was addressed to.
     *
     * <h2>Routing</h2>
     *
     * <p>Four cases, decided per message against one read of {@link ChatChannels#state()} per frame:
     *
     * <ul>
     *   <li>a {@code channel}, with the hook {@code active} and the channel known here: that channel's
     *       members only;
     *   <li>a {@code channel} in any other situation: dropped, never widened to everyone;
     *   <li>no {@code channel}, on a server with no channels in play ({@code none}): everybody
     *       online, which is exactly what happened before channels existed;
     *   <li>no {@code channel}, on a server whose channels are {@code active} or {@code broken}:
     *       dropped. A mapping with no channels picked delivers nowhere on a channelled server.
     * </ul>
     *
     * <p>A drop is counted in the debug line, never described.
     *
     * <p>Each {@code text} is a <strong>finished</strong> legacy-§ string. The bot resolved its
     * template and inserted the user's content after formatting, so nothing a Discord user types can
     * inject a colour code or template syntax here — which is why this may use {@link Msg#legacy}
     * rather than {@link Msg#plain}. The plugin carries no MiniMessage parser and never sees a
     * channel id, a Discord id, or raw user content outside the rendered line.
     *
     * <p>There is no broadcast primitive on {@code PlayerDirectory}, and that is fine: iterating
     * {@code onlinePlayers()} is what a broadcast would do anyway.
     *
     * <h2>Bounded at {@value #MAX_INBOUND_MESSAGES} per frame</h2>
     *
     * <p><strong>Defence in depth, and consistency, rather than a threat model.</strong> The peer on
     * the other end of this socket is the guild's own bot — it authenticated with the server's HMAC
     * key and it coalesces before it sends — so nothing is expected to arrive that needs capping.
     * The bound exists anyway for two reasons.
     *
     * <p>The first is cost shape. This is the one loop in the module whose work is
     * <em>multiplicative</em>: messages × online players, and on the Bukkit family every
     * {@code sendMessage} is a task hopped onto the main server thread. A frame that arrived with a
     * few thousand entries would put a few thousand × everyone-online tasks on the tick loop from
     * one socket read, which is a stall rather than an error — the hardest kind of incident to
     * attribute afterwards.
     *
     * <p>The second is that every other path here states and tests a bound (500 queued, 200 per
     * frame, drop-oldest), and an unbounded one in the middle of them is the sentence a future
     * reader believes rather than the code. A bug in the bot's coalescer is a likelier source of a
     * ten-thousand-entry frame than malice is, and a bound that only holds while the peer is
     * healthy is not a bound.
     *
     * <p>The overflow is dropped rather than deferred — there is no queue on this side, and a
     * relay's value is entirely in being current — and it emits one count-only line so silence is
     * never mistaken for quiet.
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
        if (messages.size() > MAX_INBOUND_MESSAGES) {
            // Counts only, never content — the same rule as everywhere else in this class.
            final int dropped = messages.size() - MAX_INBOUND_MESSAGES;
            ctx.logger().warn("a bridge.discord frame carried " + messages.size()
                    + " messages; relaying the first " + MAX_INBOUND_MESSAGES + " and dropping "
                    + dropped + ". The bot coalesces before sending, so this frame is a bot-side "
                    + "problem rather than a busy server.");
        }

        // Read once per frame, so every message in it is routed against the same answer.
        ChatChannels channels = chatChannels(ctx);
        ChatChannels.State state = stateOf(channels);

        // Taken lazily: a frame of nothing but channel messages never needs the whole server.
        Collection<PlayerHandle> online = null;
        Set<UUID> reached = new HashSet<UUID>();

        int rendered = 0;
        int unrouted = 0;
        int considered = 0;
        for (Payload message : messages) {
            if (considered++ >= MAX_INBOUND_MESSAGES) {
                break;
            }
            String text = message.string("text", "");
            if (text.isEmpty()) {
                continue;
            }

            Collection<PlayerHandle> audience;
            String channel = message.string("channel", "");
            if (!channel.isEmpty()) {
                // Meant for one channel's members. Only deliverable when the hook is live and the
                // channel exists here; anything else is dropped, never widened to everyone, because
                // a staff channel's Discord side posting to the whole server is the leak this exists
                // to prevent.
                Optional<Collection<PlayerHandle>> members = state == ChatChannels.State.ACTIVE
                        ? membersOf(channels, channel)
                        : Optional.<Collection<PlayerHandle>>empty();
                if (!members.isPresent()) {
                    unrouted++;
                    continue;
                }
                audience = members.get();
            } else if (state != ChatChannels.State.NONE) {
                // No channel named, on a server where channels are in play (or might be, if the hook
                // is broken). A mapping with no channels picked delivers nowhere here: "everybody"
                // is not an audience anybody chose on a channelled server.
                unrouted++;
                continue;
            } else {
                if (online == null) {
                    try {
                        online = ctx.platform().players().onlinePlayers();
                    } catch (RuntimeException raced) {
                        // The directory is allowed to throw rather than pretend the server is empty
                        // (see PlayerDirectory#onlinePlayers). A relayed line lost to that is one
                        // line; the next one is a second away.
                        ctx.logger().debug(() -> "could not read the online list for a Discord "
                                + "relay: " + raced);
                        return;
                    }
                }
                audience = online;
            }

            Component component = Msg.legacy(text);
            for (PlayerHandle player : audience) {
                try {
                    player.sendMessage(component);
                    reached.add(player.uuid());
                } catch (RuntimeException gone) {
                    // A player who left between the snapshot and the send is the ordinary race, not
                    // an error — and every handle already tolerates it. This is the belt for a
                    // platform whose braces slipped.
                }
            }
            rendered++;
        }

        // Counts, never content. This line is also what the connected smoke asserts on, which is
        // only possible because it says how MANY rather than what. State is read once per frame, so
        // a frame is either broadcast (everybody online, exactly as before channels existed) or
        // routed by channel (the distinct members reached), never a mix of the two.
        final int count = rendered;
        final int audienceSize = online != null ? online.size() : reached.size();
        final int dropped = unrouted;
        final ChatChannels.State routedBy = state;
        ctx.logger().debug(() -> "relayed " + count + " discord message(s) to " + audienceSize
                + " online player(s)" + (dropped == 0 ? "" : "; dropped " + dropped
                + " with no deliverable audience (chat channels: " + routedBy.wireName() + ")"));
    }

    /** {@link ChatChannels#members}, with a throw treated as "no such channel". */
    private static Optional<Collection<PlayerHandle>> membersOf(
            ChatChannels channels, String channel) {
        try {
            Optional<Collection<PlayerHandle>> members = channels.members(channel);
            return members == null ? Optional.<Collection<PlayerHandle>>empty() : members;
        } catch (RuntimeException failed) {
            return Optional.empty();
        }
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
        private final String channel;
        private final long timestampMs;
        private final List<Payload> items;
        private final long estimatedBytes;

        ChatLine(UUID uuid, String name, String message, String channel, long timestampMs,
                List<Payload> items) {
            this.uuid = uuid;
            this.name = name;
            this.message = message;
            this.channel = channel;
            this.timestampMs = timestampMs;
            this.items = items == null ? NO_ITEMS : items;
            long estimate = 160L + jsonBytes(name) + jsonBytes(message) + jsonBytes(channel);
            for (Payload item : this.items) {
                estimate += 32L + jsonBytes(item.string("name", ""))
                        + jsonBytes(item.string("png", ""));
            }
            this.estimatedBytes = estimate;
        }

        /** An upper estimate of this line's encoded size, computed once. */
        long estimatedBytes() {
            return estimatedBytes;
        }

        /** This line without its largest image. */
        ChatLine withoutLargestItem() {
            if (items.isEmpty()) {
                return this;
            }
            int largest = 0;
            for (int i = 1; i < items.size(); i++) {
                if (items.get(i).string("png", "").length()
                        > items.get(largest).string("png", "").length()) {
                    largest = i;
                }
            }
            List<Payload> kept = new ArrayList<Payload>(items);
            kept.remove(largest);
            return withItems(kept);
        }

        /**
         * Bytes a string can take in the frame's JSON, pessimistically: escapes, including the HTML
         * characters a JSON writer may escape, count as six.
         */
        static long jsonBytes(String text) {
            if (text == null) {
                return 0L;
            }
            long bytes = 2L;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20 || c == '"' || c == '\\' || c == '<' || c == '>' || c == '&'
                        || c == '=' || c == '\'') {
                    bytes += 6;
                } else if (c < 0x80) {
                    bytes += 1;
                } else if (c < 0x800) {
                    bytes += 2;
                } else {
                    bytes += 3;
                }
            }
            return bytes;
        }

        /** This line with {@code attached} as its item images. */
        ChatLine withItems(List<Payload> attached) {
            return new ChatLine(uuid, name, message, channel, timestampMs,
                    attached == null || attached.isEmpty()
                            ? NO_ITEMS : Collections.unmodifiableList(attached));
        }

        Payload toPayload(boolean withImages) {
            Payload.Builder builder = Payload.builder()
                    .put("uuid", uuid == null ? "" : uuid.toString())
                    .put("name", name == null ? "" : name)
                    // Verbatim: exactly what ChatMessage carried, which is exactly what the player
                    // typed.
                    .put("msg", message == null ? "" : message)
                    .put("ts", timestampMs);
            if (channel != null && !channel.isEmpty()) {
                // Omitted rather than sent as null or "" for ordinary chat: the bot reads presence
                // as "this came from a chat-plugin channel", so an empty string would be a channel
                // with no name rather than no channel.
                builder.put("channel", channel);
            }
            if (withImages && !items.isEmpty()) {
                // Omitted entirely for a line with no images, and for a bot that did not accept
                // itemimages@1, so an ordinary line stays exactly
                // the shape a bot without itemimages@1 already reads.
                builder.putChildren("items", items);
            }
            return builder.build();
        }

        /**
         * Renders the sender and the length, never the body — the same rule
         * {@code ChatMessage.toString()} follows, and for the same reason: {@code toString()} ends
         * up in debug logs and exception messages.
         */
        @Override
        public String toString() {
            return "ChatLine{sender='" + name + "', length="
                    + (message == null ? 0 : message.length())
                    + (channel == null ? "" : ", channel='" + channel + "'")
                    + (items.isEmpty() ? "" : ", items=" + items.size()) + "}";
        }
    }

    /** The empty item list every ordinary line carries. */
    private static final List<Payload> NO_ITEMS = Collections.emptyList();

    /**
     * An item line waiting for its images. Exists for at most {@value #ITEM_IMAGE_BUDGET_MS} ms: the
     * budget timeout ships it if the renders have not. Holds the line and the futures, nothing else.
     */
    private static final class PendingItemLine {

        final ChatLine line;
        final List<String> names;
        final List<CompletableFuture<byte[]>> renders;
        private final AtomicBoolean shipped = new AtomicBoolean();

        PendingItemLine(ChatLine line, List<String> names,
                List<CompletableFuture<byte[]>> renders) {
            this.line = line;
            this.names = new ArrayList<String>(names);
            this.renders = renders;
        }

        boolean allDone() {
            for (CompletableFuture<byte[]> render : renders) {
                if (!render.isDone()) {
                    return false;
                }
            }
            return true;
        }

        /** {@code true} for exactly one caller. */
        boolean markShipped() {
            return shipped.compareAndSet(false, true);
        }

        boolean isShipped() {
            return shipped.get();
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

    /** Routes immediate drains through {@code scheduler} instead of {@code heimdall-sched}. */
    void drainSchedulerForTests(DrainScheduler scheduler) {
        this.drainScheduler = scheduler;
    }

    /** Routes item-image budget timeouts through {@code scheduler} instead of {@code heimdall-sched}. */
    void budgetSchedulerForTests(DrainScheduler scheduler) {
        this.budgetScheduler = scheduler;
    }

    /** How many item lines are waiting on images. */
    int pendingItemLineCount() {
        return pendingItemLines.get();
    }

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
