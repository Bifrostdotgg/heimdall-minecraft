# Where v3 deliberately differs from v2

The v3 rewrite ports v2's *semantics*, not its structure. Most of what v2 did was right and is
reproduced exactly — the max-extension ceiling, the connection-attempt action derivation, the retry
budget, the envelope tolerance. This file records the places where v3 does something **different on
purpose**, so a future phase can tell a deliberate decision from an accident.

**This is the canonical list.** Commit messages explain a change once, at the moment it lands, and
are then very hard to find. If you change v2 behaviour, add a row here in the same commit.

Each entry says what v2 did, what v3 does, and why. "Parity" items — places where v3 keeps
behaviour that looks wrong — are at the bottom, because "we thought about this and left it alone" is
just as easy to lose.

---

## Behaviour

### D1 — `queuePosition` is a nullable `Integer`

**v2:** primitive `int`, defaulting to 0.
**v3:** `Integer`, `null` when the key is absent.

The bot has two pending-approval branches. Staff approval computes a position and sends it;
scheduled auto-whitelist omits the key entirely (`...(queuePosition !== null && { queuePosition })`).
v2 could not tell that apart from "you are position zero", so the scheduled branch showed players a
queue position that did not exist. `stub-bot`'s ScheduledSam fixture exists for this case.

### D2 — `roleSync` is a tri-state, not a boolean

**v2:** `roleSyncEnabled` boolean plus two nullable lists.
**v3:** `RoleSyncDirective` with `absent()` / `disabled()` / `enabled(target, managed)`.

Three shapes arrive on the wire and they mean three different things: no object at all (no snapshot
yet — change nothing), `{enabled: false}` (the bot is driving LuckPerms over RCON — explicitly keep
out), and the full object. Collapsing the first two into one "not enabled" boolean is how a plugin
ends up fighting the bot over a group.

### D3 — 4xx responses are not retried

**v2:** every failure was retried `retries` times.
**v3:** only 5xx, 408 and 429. Everything else in the 4xx range fails immediately.

A `400 MISSING_FIELDS` is a statement about the request; it will say the same thing in a second's
time. v2 spent three signed round trips learning that, three times per login attempt.

### D4 — `alreadyLinked` is data, not an exception

**v2:** `parseLinkCodeResponse` threw a `RuntimeException` carrying the "already linked to …"
sentence.
**v3:** `LinkCodeResult.alreadyLinked()` is `true` and the Discord fields are populated.

Throwing turned an ordinary, expected answer into an error, discarded the structured fields, and
left the command handler string-matching an exception message to tell it apart from a real failure.

### D5 — `success: false` is an error whatever the status said

**v2:** the envelope's `success` flag was ignored; only the HTTP status decided.
**v3:** a body carrying `success: false` raises `ApiError` even on a 200.

Cheap, and it turns a would-be null-shaped result into a loud failure.

### D6 — `revoked` is exposed on the result

**v2:** parsed and discarded.
**v3:** `ConnectionAttemptResult.revoked()`.

Same `DENY` action either way, but "you were whitelisted and no longer are" deserves a different
message from "you never were", and the bot already sends the flag.

### D7 — v2's 30-second response cache is not ported

**v2:** `ApiClient` kept whole `WhitelistResponse` objects for 30 seconds and replayed them.
**v3:** `InFlight<K,V>` collapses *concurrent* callers onto one outstanding request and retains
nothing once it completes.

The cache replayed stale `roleSync` blocks to later joins, reverting groups that had just changed —
the 2.4.0 outage. A caller arriving one millisecond after a request finishes now makes a fresh one,
so there is no window in which a stale answer exists to be served.

### D8 — the username is sent verbatim

**v2:** lower-cased on both `connection-attempt` and `request-link-code`.
**v3:** sent exactly as the platform reported it, trimmed.

On `connection-attempt` the difference is invisible — `connection.ts` stores what it is handed and
matches case-insensitively. On `request-link-code` it was a data-quality bug: `link.ts` writes
`minecraftUsername` straight from the request body, so every link silently rewrote `Steve` to
`steve` in the bot's database, and the dashboard displayed it that way from then on. Normalising one
endpoint and not the other is exactly how that went unnoticed, so neither is normalised.

### D9 — Bedrock identity is injected, not resolved statically

**v2:** `ApiClient` called `FloodgateSupport.resolve(uuid)`, a static reflective helper, from core.
**v3:** an injectable `BedrockIdentityProvider` interface in core; the Floodgate reflection lives in
a platform module.

Reflection is invisible to the ArchUnit conformance rules, so confining it to a platform module is
the only way "core is platform-free" stays a checkable claim rather than a habit.

### D10 — mirror saves are debounced and atomic

**v2:** `WhitelistCache.saveCache()` serialised the entire file synchronously from
`isCachedWhitelisted`, `addWhitelistedPlayer`, `extendCacheOnJoin`, `extendCacheOnLeave`,
`reconcileFromSync` and `cleanupExpiredEntries` — several of which run on the login thread — using a
plain `FileWriter`.
**v3:** mutations mark the store dirty; the write is coalesced onto the scheduler and goes through
`AtomicFiles` (temp file, then rename), with a synchronous flush on close.

Every join and every leave rewrote the whole whitelist inline, and a pre-warm sync did it once per
player. The plain `FileWriter` also meant a crash mid-write left a truncated file that failed to
parse on the next boot.

### D11 — the mirror owns the sync ETag

**v2:** the ETag was not persisted; a restart pulled a full dump it already had.
**v3:** `MirrorStore.lastEtag()` is stored alongside the entries.

### D12 — a partial restore discards the ETag

**New in v3** (no v2 equivalent — v2 had no ETag to lose).

If loading the mirror drops an unusable entry, the stored ETag is cleared. The ETag asserts "this
mirror holds exactly what the bot last sent", which a partial restore makes false — and the bot
would keep answering `304` to it, so the missing rows would never come back. One full dump on the
next poll is the price.

### D13 — `MirrorEntry` is immutable and mutations go through `compute`

**v2:** mutable fields, unguarded read-modify-write, single-threaded by assumption.
**v3:** immutable entries published through `ConcurrentHashMap.compute`.

v3 genuinely has two threads on it (login thread extending, scheduler reconciling). It also removes
a torn-`long` hazard: the oldest supported hosts run 32-bit JVMs, where a non-volatile `long` read
may tear, and a torn `cacheExpiry` is a whitelist decision made against a timestamp that never
existed.

### D14 — `lastVerified` never moves backward

**New in v3**, found while writing the concurrency test for D13.

Two verifications in flight can arrive out of order. The older one would lower `lastVerified` while
reconcile's never-shrink rule kept the newer, larger expiry — an entry trusted past its own ceiling,
which is the one thing the #771 bound exists to prevent. `verify()` clamps both. In the
single-threaded case v2 was written for, `now` only increases and both clamps are no-ops, so parity
is unaffected.

### D15 — `touchValue` exists so `record` is not the only way to change a value

**v2:** refreshed a player's username in place on a cache hit, without touching `lastVerified`.
**v3:** `MirrorStore.touchValue(key, value)` does the same.

Without it the only option is `record()`, which advances `lastVerified` — so a revoked player would
renew their #771 ceiling every time they changed their name.

### D16 — per-endpoint retry budgets, and the join slack is back

**v2:** one `getOverallTimeoutMs()`; `WhitelistManager` waited `getOverallTimeoutMs() + 1000`.
**v3:** `overallTimeoutMsFor(perAttemptTimeoutMs)` with `whitelistSyncBudgetMs()` /
`updateCheckBudgetMs()`, plus `JOIN_SLACK_MS` and `joinTimeoutMs()`-style accessors.

Two endpoints deliberately run with a longer per-attempt timeout, so one budget could not cover
them: at the defaults the real worst case is 47 s for `whitelist/sync` and 26 s for `plugin/latest`
against a reported 17 s. Bounding a wait on the wrong one reopens #797 / MC-6 on those endpoints.
The `+ 1000` was silently dropped when v2's call site was left behind in the rewrite; it is doing
real work, because `HttpURLConnection` applies the timeout twice (connect, then read).

### D17 — `bootstrap.yml` holds only what is needed to reach the bot

**v2:** a ~200-line `config.yml` per server.
**v3:** endpoint, credentials, `serverId`, role, debug. Everything else arrives as remote config.

A fleet operator changing one message had to edit it on every box, and support could never be sure
what a given server was running.

### D18 — the debug toggle is a field, not a config read

**v2:** `config.getBoolean("logging.debug", false)` on **every** debug call, which on Bukkit
re-reads the backing configuration section.
**v3:** a volatile boolean on the logger, plus a `Supplier<String>` overload so an unread message is
never built.

Dozens of config reads per login, on the login thread.

### D19 — unknown `bootstrap.yml` keys survive in any call order

**New in v3.**

`save()` re-reads the file's unknown keys itself rather than remembering them from a `load()`, so a
caller that saves without loading first cannot silently delete a newer version's settings. The tell
would otherwise be a config file that quietly lost a field on downgrade.

### D24 — socket callbacks carry a generation, and stale ones are inert

**New in v3.**

Every connection attempt gets a monotonic id; a socket's listener captures it and ignores any
callback that arrives once it no longer matches.

v2 had two bugs this closes. A late `onClose` from a socket aborted minutes earlier would schedule a
reconnect that killed the *healthy* socket which had replaced it. And `disconnect()` — a deliberate,
graceful close — fires `onClose` too, which looked exactly like an unexpected drop, so the client
immediately reconnected itself; v2 papered over that by destroying its scheduler, which is why its
reconnect path had "no live scheduler" branches to rebuild one.

### D25 — any inbound frame refreshes liveness, not just pings and pongs

**v2:** `lastPong` moved only for a `ping` or a `pong`.
**v3:** every parseable inbound frame moves it.

A link that is delivering role syncs is demonstrably alive. Under v2's narrower rule a busy
connection could still be aborted by its own heartbeat because the bot happened to be too busy to
run its ping sweep — the client tearing down a connection that was working, on the evidence of a
timer rather than of the link.

### D26 — the tunnel has its own scheduler, and does not own it

**v2:** created and destroyed a private `heimdall-ws` scheduler inside the WebSocket client, on
every connect and every disconnect.
**v3:** `HeimdallExecutors` owns a `heimdall-ws` single-thread scheduler alongside `heimdall-io` and
`heimdall-sched`; the tunnel borrows it and never shuts it down.

Two separate points. *Owning* it was what forced v2's teardown paths to reconstruct one, and what
made "disconnect" and "shut down" hard to tell apart. *Separating* it from `heimdall-sched` is the
new part: both are single-threaded, so sharing would queue the heartbeat behind whatever periodic
work was running — and a whitelist poll that blocks for its full retry budget is tens of seconds. A
heartbeat tick that late is indistinguishable, to its own timeout check and to the bot's sweep, from
a dead link, so the tunnel would abort a healthy socket because a poll was slow.

### D27 — message handlers never run on the socket's reading thread

**v2:** a single `BiConsumer` message handler, invoked inline from the read callback.
**v3:** a subscription registry; every handler runs on `heimdall-io` or on an executor it named.

v2's role-sync handler made an HTTP call from that callback, so the socket stopped reading for the
length of a round trip — and the heartbeat check, seeing no traffic, aborted a connection that was
working perfectly. The consequence to know when writing one: handlers no longer run in wire order
relative to each other.

### D28 — the reconnect delay is atomic

**v2:** a plain `long`, written by whichever thread noticed the failure and read by another.
**v3:** an `AtomicLong` inside `ReconnectPolicy`.

The oldest supported hosts run 32-bit JVMs, where a non-volatile `long` read may tear. A torn
backoff delay is a reconnect scheduled for a duration nobody chose.

### D29 — remote-config version monotonicity is scoped to a connection

**New in v3.**

A `config.push` older than the one in force is ignored (and still acknowledged, so the bot does not
re-send it forever). But the floor **resets** when a connection negotiates v3, so the first push of
a session is always applied.

An absolute floor wedges. The disk cache outlives the bot's own counter: a guild document recreated
bot-side starts counting from 1 again, and a plugin holding a cached version 7 would then reject
every push it ever received and run on stale config permanently — fixable only by deleting a file on
the server. The bot is the source of truth and has just stated its version in `identify_ack`, so the
ordering guarantee is applied where the ordering hazard actually is: within one connection, where
fire-and-forget frames really can arrive replayed or out of order.

Two details of that rule are worth stating, because both are visible to an operator reading logs:

- **Within a session, an *equal* version is dropped as firmly as an older one**, even when it
  carries different content. The bot's counter is the authority on whether anything changed, and a
  re-push at an unchanged version is what a reconnect produces rather than what an edit produces.
- **A version that goes *backwards* across a reconnect is applied, and logs a warning.** That is the
  floor reset doing exactly what it exists for — a guild document recreated bot-side — but it is
  also the only situation in which the reset does something surprising, and nothing else in the
  plugin's logs would hint at the cause. A repeat of that line points upstream, at the bot.

### D30 — modules do not have to clean up after themselves

**v2:** no disable path at all. A feature was "off" because its own code checked a boolean on every
call; its listeners stayed registered for the life of the server.
**v3:** every registry a module can reach is reached through its `ModuleContext`, which records what
it registered, so `ModuleManager.disable(id)` unwinds all of it without knowing what any of it was.

Trusting each module's `disable()` fails the first time somebody adds a listener and forgets, and
the symptom — a disabled module still gating logins — is one nobody attributes to the module that
was turned off weeks ago. A module that throws on the way *out* still has its registrations unwound,
which is the case that justifies the whole mechanism.

### D31 — a module that fails to start is contained, and not retried

**New in v3** (v2 had nothing to fail).

`enable()` throwing unwinds the module's partial registrations, marks it `FAILED`, and leaves the
rest of the plugin running. It is not retried while it stays in the desired set: it will fail again
for the same reason, and retrying on every config push turns one severe line into a flood that
buries the cause. Toggling it off and on in the dashboard clears the state — which is what an
operator does after fixing the problem anyway.

### D32 — abstain is a third answer, not a synonym for allow

**New in v3** (v2 had no pipeline; each check was a hardcoded step in one method).

A login or chat interceptor returns allow, deny, or abstain. Collapsing abstain into allow means the
first indifferent check — a module that is switched off, a bypassed player, a check that does not
apply on this platform — silently vetoes every stricter check behind it, with nothing in any log to
say so. An interceptor that throws is treated as having abstained: a broken check must neither lock
a server's whole player base out nor wave them all through.

### D33 — remote-config module entries are accepted flat *and* nested

**New in v3.**

The v3 design specifies `{"enabled": true, "settings": {...}}`. `stub-bot` — the executable copy of
the bot's wire contract — sends settings flat alongside `enabled`:
`{"enabled": true, "mode": "websocket"}`. Both are read.

A parser that only understood the nested form would read every flat entry as having no settings, and
a module would silently run on its defaults while the dashboard showed the operator a value it had
definitely saved. Declaring one shape correct and letting the other fail silently is the worst
available outcome, so neither is.


### D38 — an inbound error abandons the connection, and always aborts the socket

**v2:** used `java.net.http.WebSocket`, whose `onError` is terminal.
**v3:** uses nv-websocket-client, whose `onError` is **not**.

nv fires `onError` immediately before every specific error callback, including recoverable ones —
verified against the 2.14 bytecode: `WritingThread` calls `callOnError` and then `callOnSendError`
for a failed frame write. The terminal callback is `onDisconnected`. So a failed write arrives as an
error on a socket that is still wide open, with its reading and writing threads alive.

Every lost-connection path therefore aborts the socket, not just the ones where it is presumed dead.
That makes the fatal/non-fatal distinction stop mattering: a recoverable error becomes one clean
reconnect instead of an orphaned live socket, two leaked threads and a duplicate connection opened
beside it. Treating a possibly-recoverable error as connection-abandoning is the deliberate side of
the trade — one reconnect is cheap, a half-dead socket that keeps failing sends is not.

### D39 — a login interceptor declares what its own failure means

**v2:** the login path caught its API exception inline and consulted the configured fallback mode
there.
**v3:** `Interceptor.failureVerdict(cause)`, defaulting to abstain, applied by the pipeline when a
check throws.

The default is v2's effective behaviour at this level — fail open — but stated rather than implied,
and the severe log line now names the module rather than a priority number. Defaulting to deny was
rejected: it would turn any bug in any interceptor into a server nobody can join, which is a worse
outage than the one it would guard against.

**This is a backstop, not the mechanism.** "The bot is unreachable — admit or refuse?" is a policy a
server owner configures and it belongs *inside* the interceptor, which is the only thing that knows
a request failed rather than that something unexpected happened. The whitelist module in 1d must
implement its fallback mode there, exactly as v2's catch did. A module using `failureVerdict` for
its offline policy has put the policy in the wrong place.

### D40 — numbers are compared by value, not by Gson's rules

**New in v3**, and a correction to v3's own first attempt.

Gson's `JsonPrimitive.equals` is not transitive across the parser and the builder: it compares two
numbers as integers only when both are `Long`/`Integer`/`Short`/`Byte`/`BigInteger`, and the parser
produces a `LazilyParsedNumber`, which falls to the `double` branch. So a parsed
`1234567890123456789` equalled a built `1234567890123456789` *and* a built `1234567890123456788`,
while those two were not equal to each other. Near 1.2e18 the gap between representable doubles is
256, and snowflakes minted seconds apart differ by less.

`Payload` now compares and hashes through one `BigDecimal` normalisation, which keeps 64-bit ids
exact, treats `1` and `1.0` as one number, and folds `-0.0` into `0.0`. It matters because
`RemoteConfig` fires a module's change listeners based on value equality, so a setting carrying a
snowflake would silently stop reporting changes.

Related, same class: `optInt` range-checks before narrowing. `getAsInt()` on a parsed number is a
cast that never complains, so `5000000000` came back as `705032704` — under the exact method
departure D1's nullable `queuePosition` depends on. And the boolean accessors are type-strict; Gson
would answer `false` for the string `"yes"`, which is a confident wrong answer about whether a
module is enabled.

### D41 — a correlated request's timeout is cancelled when its reply arrives

**v2:** scheduled a timeout per request and never cancelled it; every one sat on the scheduler until
its full deadline elapsed, however quickly the request had been answered.
**v3:** the handle is kept and cancelled on reply, on abandon, and on teardown.

With `setRemoveOnCancelPolicy` set on the pool, cancelling drops the task from the queue
immediately. Under a burst of fast requests that is the difference between a queue that drains and
one that grows for the length of the longest timeout.


### D42 — the platform string is honest, with the old value kept beside it

**v2:** the Bukkit entry point sent `platform: "paper"` unconditionally.
**v3:** sends what the server reports (`paper`, `spigot`, `craftbukkit`, `purpur`, …) and adds
`platformFamily`, carrying exactly the value v2's `platform` did.

A good share of the fleet is not Paper, and support had a dashboard saying "Paper" for a server
whose problem was that it was not Paper. Collapsing the two into one field would mean choosing
between breaking whatever bot-side code matches on the old spelling and lying to it, so both are
sent. On Velocity the two readings agree and `platformFamily` is `velocity`.

`serverName` is also no longer sent from the Bukkit side at all: `Bukkit.getServerName()` is
deprecated and has been removed outright on modern Paper, so calling it would be a
`NoSuchMethodError` on the servers most of the fleet runs. The handshake already falls back to the
`serverId` from `bootstrap.yml`, which is the name the operator chose anyway.

### D43 — chat uses the deprecated event, on every version

**v2:** Paper-only, and used `AsyncPlayerChatEvent`.
**v3:** the same event, and deliberately *not* Paper's `AsyncChatEvent`, despite compiling against
a Paper API that has it.

This is a shading constraint, not a preference. `AsyncChatEvent.message()` returns a
`net.kyori.adventure.text.Component` supplied by the **server**, while Heimdall shades its own
Adventure and `:app` relocates it into `com.heimdall.libs.kyori`. Shadow rewrites every `net.kyori`
reference in every class it merges — including that call's method descriptor — so the compiled call
would ask a server-supplied event for a relocated type and fail with `NoSuchMethodError` on exactly
the modern Paper servers the modern event was added for.

Un-relocating Adventure is not available as a fix: `adventure-platform-bukkit` pins the
`adventure-api` version it needs, and on Paper the server's own copy would win the parent-first
lookup at whatever version that Paper line carries. Relocation cannot be excluded for one consumer.
`AsyncPlayerChatEvent` is a plain `String`, fires from 1.8.8 to current, and has none of that
problem. Deprecated is not the same as absent.

**Two residual risks, neither of them closed.** They are written down here rather than in a comment
because the day either one bites, this is the file somebody will search.

1. *Deprecated-for-removal is a countdown.* Paper has marked `AsyncPlayerChatEvent` for removal, and
   the choice above is a bet that it outlives the shading constraint. If Paper drops it first, the
   options are a reflective `AsyncChatEvent` bridge that never names a `net.kyori` type (the
   `VelocityText` technique in D44, applied to a Bukkit event), or un-relocating Adventure and
   pinning a version that satisfies `adventure-platform-bukkit` on every supported Paper — which is
   the harder problem, not the easier one.
2. *Cancellation of signed chat is asserted, not verified.* Since 1.19 a client signs its messages,
   and cancelling a chat event on a modern Paper is believed to suppress the message without
   producing a client-side "chat validation failure" disconnect. Nothing in this repo has
   demonstrated that: the smoke matrix boots servers, it does not join them, so no chat has ever
   been cancelled on 1.21 by any test.

TODO(**1f**, moved from 1d and then from 1e): a headless-client join on the `paper-1.21.8` row —
connect, send a message, assert it is suppressed and that the client stays connected. That single
test settles risk 2 and turns the chat interceptor from a design into a behaviour.

1d added `smoke/connected.sh`, which boots a server against a real bot and asserts the whole wire, but
it still never joins a player: there is no headless client in the harness. What 1d *did* do is build
the container topology a headless client would plug into, so the remaining work is the client itself
rather than the scenario around it.

**1e narrowed the gap without closing it.** `/hd test <player>` drives the real login interceptor for
a named player and the smoke matrix asserts both a deny (corroborated by the stub) and an allow from
the pre-warmed mirror — so the login *decision* is now exercised end to end on a real server. What is
still unexercised is everything downstream of the decision: the platform's own login listener, the
kick screen, and chat cancellation. All three need a client to be connected, and none of them is
reachable from a console command.

### D44 — Velocity's text boundary is crossed reflectively

**v2:** no shading, so Velocity's `Component` was simply *the* `Component`.
**v3:** four calls — build a component, build a denial result, send a message, disconnect a player —
go through reflection in `VelocityText`, and nothing else on Velocity does.

Same root cause as D43, arriving somewhere it cannot be avoided: Velocity's API takes a `Component`
on every method that shows a player anything, and there is no String-shaped alternative. The class
names are assembled from an array of fragments rather than written as literals, because Shadow's
remapper rewrites string constants that match a relocation pattern and javac folds
`"net.kyori" + ".adventure"` into one constant before Shadow ever sees it.

If the bridge cannot resolve, a denied login is **still denied** — with Velocity's default message
instead of Heimdall's. Refusing without an explanation is bad; admitting somebody because a text
library did not load would be a security hole.

### D45 — the console tap is rebuilt for the versions it actually runs on

**v2:** `ConsoleStreamer` attached a Log4j appender using the five-argument `AbstractAppender`
constructor, stamped lines with `LogEvent.getTimeMillis()`, stripped ANSI with a pattern that had
lost its leading `ESC`, and fanned out on the logging thread.
**v3:** the four-argument constructor, `System.currentTimeMillis()` at capture, a corrected pattern,
and fan-out on `heimdall-io` behind a bounded queue and a re-entrancy guard.

Every one of those is a version or a correctness problem v2 did not have to face because it targeted
modern Paper only:

- the five-argument constructor arrived in Log4j **2.11.2**; Minecraft 1.8.8 ships **2.0-beta9**, so
  on the oldest supported server v2's code is a `NoSuchMethodError` the moment console streaming is
  switched on. The four-argument form is deprecated on modern Log4j and present in every 2.x, which
  is the only property that matters when the runtime version spans a decade;
- `getTimeMillis()` arrived in 2.4 — beta9 has only `getMillis()`. Stamping at capture is the same
  instant to within the time it takes Log4j to call an appender, and needs no reflection;
- v2's ANSI pattern began at the `[`, so it also ate ordinary bracketed text: `[12:00:00 INFO]` lost
  its closing bracket on the way to Discord;
- delivering to consumers on the logging thread puts a plugin's callback on the critical path of
  every line the server writes.

The appender is attached **eagerly at enable**, not lazily when the console module first asks, so
the boot-smoke matrix exercises the attach on all eight supported servers on every run —
including the two BungeeCord rows, which exercise a different implementation entirely (D77)
behind the same one-line assertion.

### D46 — one LuckPerms implementation, with both platforms' fixes

**v2:** two implementations that had drifted. The Velocity one re-resolved the API lazily, checked a
group existed before granting it, and awaited the save; the Bukkit one did none of those.
**v3:** one implementation in `:platform-common`, with all three behaviours.

`net.luckperms:api` is platform-neutral — the same artifact on Bukkit and on Velocity — so there was
never a reason for two files, only an opportunity for them to disagree. The Bukkit copy's
constructor-time resolve is issue #796 / MC-10: with no load-order guarantee between plugins, a
server where LuckPerms started second had role sync dead for the whole process. Not awaiting
`saveUser` meant a server stopped in the seconds after a sync lost it.

The group diff is extracted into `GroupDiff` as a pure function of three lists, because that is the
part that fails quietly. An empty managed list means **change nothing**, never "manage everything" —
reading it the other way would strip every group on the server on the first sync after a deploy.

### D47 — the admin command is `/hd`, and `/hdp` on the proxy

**v2:** `/hwl` on both.
**v3:** `/hd` on the Bukkit family, `/hdp` on Velocity.

In a proxied network both are installed, and a player typing one name reaches whichever component
claimed it first — which is always the proxy, because it intercepts before forwarding. v2 therefore
had a command whose meaning depended on where the player was standing. Two verbs mean "am I asking
the gatekeeper or this backend?" is answered by what you typed.

**Each has a spelled-out alias, and the pair is chosen for the same reason.** `/hd` also answers to
`/heimdall`, and `/hdp` to `/heimdallproxy`. The abbreviations are what anyone will actually type
once they know which is which; the long forms exist for the moment before that, and they cannot
collide with each other any more than the short ones can. There is deliberately **no bare
`/heimdall` on the proxy** — it is the one name an operator would expect to work on both, and a
name that resolves to whichever component claimed it first is precisely the v2 problem this
departure exists to close.

The module commands added in 1d follow the same rule from the other direction: `/linkdiscord`,
`/link` and `/offend` are registered **identically on both platforms**, because unlike the admin
command they mean the same thing wherever they are typed. A player linking their account does not
care whether the proxy or the backend answered.

**`/hwl` still answers, on both platforms, and says so once (1e).** Every v2 install's operators,
runbooks and staff macros say `/hwl`, and v2 registered it on the proxy as well as on the backend, so
removing it outright would make "Unknown command" the first thing a migrating server says to the
person who just upgraded it. It is registered on both, forwards to the same tree, and tells the
sender the name changed **once per server start** — not once per use, which is a notice people learn
to scroll past. It is excluded from the help listing, because help is for what to type next rather
than for what used to work.

It is a real command rather than an alias of `/hd`, and on the Bukkit family it has to be: aliases
are fixed in `plugin.yml` at load time and the plugin has to be able to tell the two names apart in
order to warn about one of them.

### D52 — session handlers run off the event thread, and carry their own timestamp

**New in 1d**, and the two parts of S1 that were left to the implementation.

`PlayerSessionEvents.join(handle, timestampMs)` hands off to `heimdall-io` and returns. On Bukkit,
`PlayerJoinEvent` is on the main server thread, so a listener that wrote a mirror entry — let alone
one that made a network call — would put that on the tick loop for every join. What actually runs
there is building a handle and queueing a task.

Two consequences, both deliberate:

- **join and quit are no longer ordered relative to each other.** A quit for somebody who
  reconnected immediately can in principle be delivered after the second join. This is the same
  trade departure D27 made for tunnel handlers, for the same reason, and it is why the timestamp is
  stamped on the event thread and carried rather than read by the listener — "now" at delivery is an
  unknown distance after the thing that happened.
- **A rejected hand-off is dropped with a debug line.** That only happens while the pools are
  shutting down, which is the one moment a missed cache extension costs nothing, and throwing would
  put a plugin fault in the server log for something the plugin was already stopping for.

Bukkit uses `PlayerJoinEvent` / `PlayerQuitEvent` at `MONITOR`. Velocity uses `PostLoginEvent`
rather than `LoginEvent` (the latter can still refuse the connection, so a join reported from it is
sometimes a join that never happened) and `DisconnectEvent` rather than `ServerDisconnectEvent` (a
player moving between backends has not left the network, and treating a `/server` as a quit would
slide a cache window on every one).

### D53 — a command is a registration, not a branch in the entry point

**v2:** every command was an `if (commandName.equalsIgnoreCase(...))` inside a 1,086-line
`JavaPlugin`, and there was no way to unregister one. A feature that was "off" still answered.
**v3:** `CommandRegistrar` is the fifth focused interface behind `PlatformFacade`; a module builds a
`CommandSpec` and hands it to `ModuleContext.registerCommand`, and the handle is unwound with
everything else when the module is disabled (departure D30 applied to commands).

The platforms differ in one visible way, and it is worth stating rather than smoothing over.
**Velocity really unregisters**; Bukkit cannot. A Bukkit command's existence and its aliases are
fixed at load time by `plugin.yml`, so disabling a module puts its executor back to the plugin's own
— the verb still exists and prints its usage line, but it no longer reaches the module. The command
map can be reached reflectively; v2 did not, and neither does v3. That map's shape has changed
across the decade of servers this jar supports, the reflective path is invisible to the conformance
rules, and the failure mode is a verb that silently does not exist on one server generation.

The same asymmetry is why `CommandSpec.aliases()` is documented as *advertised* rather than
guaranteed, and why `BukkitCommandRegistrar` warns when a spec names an alias the descriptor does
not. A silent difference between the two platforms is exactly how "it works on my proxy" is born.

### D54 — the guild is discovered from the token, never configured

**v2:** `api.guildId` in `config.yml`, filled in by hand.
**v3:** no guild field to configure at all. On start with credentials, `HeimdallRuntime` calls
`POST /api/minecraft/identify` (HMAC-signed, with an optional `X-Token-Id` header) and uses what the
bot answers.

v2's field was the single most common support problem, and its failure mode is the worst available:
a snowflake copied from the wrong server, or out of a message link, signs perfectly and is refused —
or, worse, succeeds against a guild the operator does not own. The token already encodes the answer,
so the token is asked.

Three details follow from it:

- **"Discovering" is a first-class state, beside "not set up".** While `GuildDiscovery` is retrying,
  commands answer and modules run; the one thing missing is the tunnel, whose URL is keyed by guild.
  The state is logged once at start, and the retry backs off from 5 s to 5 minutes and never gives
  up — a bot that is down for an hour must not leave a server unable to connect without a restart.
  The first failure is a warning and every one after it is debug, so an hour of downtime does not
  bury the rest of the log.
- **`bootstrap.yml` gains a `guildId` key, and it is a cache.** It is written by the plugin, never
  asked for by the setup flow, and overwritten by whatever `identify` next answers. It exists so a
  restart *during* a bot outage can still dial the tunnel it dialled yesterday instead of sitting in
  the discovering state until the bot returns. Persisting is best-effort: a read-only data directory
  costs one `identify` per boot, which is much better than refusing to connect.
- **A blank `guildId` in the response is an error, not an empty string.** Everything downstream
  builds a path out of it, so an empty guild yields `/api/guilds//minecraft/…` — a 404 on every
  endpoint, from a client that believes it is fully configured.

### D60 — an unresolved guild runs the fallback mode; it is not an automatic refusal

**v2:** the login listener checked its guild id and, if it was empty, disallowed with the
`apiUnavailable` message — unconditionally, whatever `advanced.apiFallbackMode` said.
**v3:** the same state runs the configured fallback, exactly as an unreachable bot does.

Security-relevant in both directions, so the reasoning matters more than the change.

v2 could afford to refuse, because its guild id came out of a config file: an empty one meant a
misconfigured server, which is a permanent state an operator has to fix, and refusing everybody is a
reasonable way to make them fix it. In v3 the guild is *discovered* (D54), so "unresolved" is a
transient state every server passes through on the way up — and one a restart during a bot outage
can leave a perfectly healthy server sitting in for minutes. Refusing there would mean a bot
redeploy locks out a server whose mirror holds the entire whitelist, which is exactly the outage the
pre-warm design exists to prevent, reintroduced at a different door.

So the state is treated as what it is: the bot cannot be consulted. `apiFallbackMode` decides, and
its default (`whitelist-only`) serves the mirror. The security properties are then the ones the
operator chose rather than ones this branch imposed:

- a genuinely fresh server has an empty mirror, so `whitelist-only` refuses everybody anyway — v2's
  outcome, reached by policy rather than by special case;
- an operator who wants v2's behaviour exactly sets `deny`, and gets it;
- `allow` admits everybody, which is what `allow` means and what that operator asked for.

The one thing this must not do is *report* while unresolved. A mirror hit fires a background
connection-attempt, and with no guild that builds `/api/guilds//minecraft/connection-attempt` — a
malformed, signed, 404'd request per returning player. The check sits before the report rather than
after it, and is tested.

### D55 — the declared capability set is what the build CAN run, not what is running

**New in 1d**, and a correction to v3's own first attempt.

`ModuleManager.capabilities()` was the union over **enabled** modules, on the reasoning that
claiming one for a switched-off module means receiving settings nothing reads. That reasoning is
right about the cost and wrong about the alternative, and the alternative is a deadlock:

- the bot narrows its `config.push` to the base ids of the capabilities the client declared;
- a module is enabled only because a push said it should be;
- so a server with no config cache has nothing enabled, declares nothing, receives no config, and
  can never enable anything — and every subsequent boot is in the same state.

It is worse than a missing feature. An **empty `capabilities` array is not a v3 handshake at all**
as far as the bot is concerned (`stub-bot/README.md`: the handshake is triggered by a non-empty
array alone), so the connection silently negotiates down to v2-compat and the plugin runs on its
built-in defaults with no dashboard control whatsoever, while the bot believes it is talking to a v2
plugin and behaves accordingly.

The set is now the union over **registered and eligible** modules, recomputed at registration rather
than only on a lifecycle transition. An `INELIGIBLE` module still declares nothing, and that
exclusion is the one that survives the argument above: it cannot run on this instance whatever the
dashboard says, so config for it really would be settings nothing reads. A `FAILED` one stays
declared — an operator fixing it should not also have to reconnect the tunnel.

**Found by `smoke/connected.sh` on its first run**, which was the first boot in the project's history
with real modules and a real bot in one process. 434 unit tests could not see it: the module tests
had no tunnel, and the tunnel tests had no modules. That is the whole argument for the connected
scenario existing.

### D56 — a module reaches the API through a gateway, not a captured client (RESOLVED in 1e)

**Raised in 1d as a seam that would have to change; 1e changed it.** The original entry is kept
below because the failure it describes is the one somebody will go looking for, and because the
answer 1e chose is not the one it proposed.

**What 1e built.** `ModuleContext.api()` returns a `HeimdallApi` and is **never null**. There is one
per plugin, created before any module is registered, and it wraps the single `ApiClient` core has
always reconfigured in place. Its three states are *derived from the settings* rather than tracked
separately, so they cannot disagree with what the next request would do:

| State | Meaning |
|---|---|
| `NOT_CONFIGURED` | no endpoint or no token. Run `/hd setup`. |
| `DISCOVERING` | credentials, but `identify` has not answered, so there is no guild for a path. |
| `READY` | go. |

In the first two, every endpoint returns an **already-failed** future carrying
`ApiUnavailableException` with the reason on it. Nothing is sent, and that is load-bearing rather
than tidy: without a guild the client would build `/api/guilds//minecraft/connection-attempt`, and a
warm mirror on a restarting server turns that into one signed, malformed, guaranteed-404 request per
returning player. A caller that would rather branch than catch asks `isUsable()` first, which is what
the whitelist interceptor does — "no guild yet" is a reason to run the configured fallback, not a
reason to report an error.

**Neither of the two options the original entry offered was taken.** `Optional` would have put a
branch at every call site and said nothing about *why*; a queue would have made a login wait for a
bot that might never answer. Deriving the state and failing fast is the third answer, and it fell out
of the observation the entry itself makes: guild discovery never had this problem because it
reconfigures one client in place. So everything else was made to work the same way.

**Three bugs, one shape.** The same "captured once, at enable, and never replaced" mistake had three
instances, and all three are closed by making the objects stable rather than by fixing each:

- a module's API client (this entry);
- a module's **tunnel subscriptions**, which went to the offline no-op bus on an unconfigured server
  and were never re-made — so a freshly claimed server received role syncs nothing was listening for.
  The `TunnelClient` is now built on every boot, configured or not, and `applySettings` re-points it;
- **`TunnelSpiService`**, which captured the same absent bus at enable, leaving a third-party
  plugin's SPI dead until a restart. That was the 1c TODO; it needed no re-install, only a tunnel
  that was already there.

`smoke/connected.sh`'s `paper-setup` row is the end-to-end proof: an unclaimed server, a code minted
in the stub, `/hd setup` typed into the console, and a tunnel that comes up and enables modules in
the same boot.

The original entry follows.

`ModuleContext` exposes the tunnel, the pipelines, the scheduler, the mirror factory, the platform
and the settings — but not `ApiClient`. Three of the four feature modules need it, so each takes it
as a constructor argument from the wiring in `HeimdallModules`, and each tolerates `null` (a server
that was never set up) rather than refusing to register.

Nullable constructor arguments in three places is not a good long-term answer, and the reason it is
the 1d answer is scope: exposing the client on `ModuleContext` means deciding what a module sees
when there is no client and when the guild is still being discovered, and that decision is better
made alongside the setup flow in 1e than in passing here. **Prerequisite for 1e:** decide between
`ModuleContext.api()` returning an `Optional`, and a core-owned gateway that queues or fails calls
made before the guild resolves. Do not add a fourth nullable constructor argument first.

*(In the event there were two nullable arguments, not three — the role-sync module never took one —
and neither of the two options above was chosen. See the resolution above.)*

**There is a live consequence, not only an aesthetic one.** Each module captures the reference it was
constructed with, once, at registration — before `start()`, and therefore before anything could have
resolved a guild. On a server that was never set up that reference is `null` forever, so when
`/hd setup` lands in 1e and configures a server *without a restart*, `/offend` and `/linkdiscord`
will still refuse: the modules are holding a null client and nothing re-hands them a live one.

Guild discovery does not have this problem, because it reconfigures the single `ApiClient` instance
in place rather than replacing it — which is the shape the gateway needs. Whatever 1e builds must
make a module's view of the API survive the server being configured underneath it, and the setup
flow is not testable end to end until it does.

### D61 — the admin command tree is one platform-free class, and the platforms only name it

**v2:** a chain of `equalsIgnoreCase` branches inside two entry points of 1,086 and 1,311 lines.
**v3:** `com.heimdall.core.admin`, registered through each platform's own `CommandRegistrar`.

The two halves of v2's tree had drifted and nothing could have noticed. `/hwl cache` on the proxy
never grew the `cleanup` branch the Paper one had; `/hwl test` printed different fields on each; and
neither could be exercised without starting a Minecraft server, so neither ever was. Both are the
same defect: a command tree that lives inside a `JavaPlugin` is a command tree nobody can run a test
against.

Everything in the new package takes a `CommandSource` and returns nothing platform-shaped, so the
whole tree is driven in `AdminCommandTest` with a fake sender. What differs per platform is one
string — `hd` or `hdp` — and one list of aliases.

Two consequences worth stating:

- **Three verbs need a feature module, and core cannot name one.** `test`, `cache` and `offense` go
  through small interfaces (`WhitelistAdmin`, `OffenseAdmin`, `UpdateAdmin`) that the modules
  implement and `HeimdallModules` — the one place that already depends on both sides — introduces.
  Each has a `NONE` implementation, so a build compiled without a module has a verb that says the
  feature is not installed rather than a hole in the tree.
- **Every blocking verb hands off to `heimdall-io` and acknowledges first.** A command handler is on
  the main server thread on the Bukkit family, and half of these make a signed round trip with a
  retry budget behind it — up to 47 seconds for a whitelist sync. The acknowledgement is not
  politeness: silence for that long reads as a command that did not register.

### D62 — a v2 config is migrated on first boot, and its settings stay inert until the server is claimed

**v2:** a ~200-line `config.yml` (Bukkit) or `config.json` (Velocity) per server.
**v3:** found on first boot, translated, and the original kept as `*.v2-backup`.

The credentials become a `bootstrap.yml` in **legacy mode** — v2 had no token id, and the guild key
signs identically on both HTTP and the WebSocket upgrade, so a migrated server connects with exactly
what it had. v2's `api.guildId` is carried across as the *cache* (departure D54), not as a setting:
it is provisional, and `identify` overwrites it.

Three details that are not obvious:

- **The v2 file is next door, not in the new plugin's directory.** v2's plugin is called
  `HeimdallWhitelist` and v3's is called `Heimdall`, so a server whose jar has just been swapped has
  a brand-new empty directory and its entire configuration in the sibling one. A migration that only
  searched its own directory would find nothing on every real upgrade — which is the one case it
  exists for. Both are searched, the new one first, because an operator who moved the file by hand
  meant it.
- **The translated settings are posted to `config/import`, which is write-once, and the document is
  inert until the server is claimed.** The bot never reads a config store for a serverId it has no
  registry row for, so a migrated-but-unclaimed server runs its **built-in defaults** — which are
  v2's shipped values, so nothing changes for its players. The imported settings activate the moment
  `/hd setup` claims it. That is correct and it is surprising, so the migration log line says it in
  as many words: otherwise the operator's experience is "migration succeeded, dashboard shows my
  settings, server ignores them".
- **Nothing is ever deleted, and nothing is renamed until the write succeeds.** A migration that
  could not write `bootstrap.yml` leaves the v2 file exactly where it was, so fixing the permissions
  and restarting retries it. A backup name that is taken gets `.1`, `.2`, and a rename that fails
  is a warning on a migration that has already succeeded.

### D63 — the update check runs immediately, and publishes one snapshot

**v2:** `UpdateChecker` with two independent volatile fields, and a periodic check that first ran
after a full interval.
**v3:** one immutable holder in one volatile field, and the first check runs at start.

The hardening is deliberately **verbatim** — the `github.com`/`githubusercontent.com` allowlist, the
HTTPS-only rule, the 50 MB streaming cap, the `.part` staging and atomic swap, both jar-install
strategies. What changed is two things v2 got wrong in ways that only show under concurrency or on
the first boot:

- v2's `latestRelease` and `updateAvailable` were separate volatiles written one after the other, so
  a reader between the two writes could render "3.0.0 → 3.0.0". They are now one object, assigned
  once, and every multi-fact reader takes a single snapshot into a local.
- v2 scheduled the first check a full interval out, so a server restarted more often than every
  twelve hours never checked at all. The first one is now immediate, which is also when the answer
  is most useful — a server that has just started is the one whose operator is looking at the log.

The download policy is an object rather than a set of constants so the hardening can be tested
against a loopback HTTP fixture, and there is a test asserting that the production policy refuses
both `127.0.0.1` and plain `http://` — so the loosening cannot quietly become the default.

### D64 — `/hd test` runs the real interceptor with its writes suppressed

**v2:** `/hwl test` made a bare `connection-attempt` call and printed four fields from the answer.
**v3:** the whole login path runs, and reports which check decided.

The bot's answer is not the login decision, and the gap between them is where the hard support cases
live: a bypassed UUID never reaches the bot, a backend with `enforceOnBackend` off abstains, a warm
mirror answers during an outage, and the fallback mode decides when nothing else could. v2's test
command was blind to all four.

So the interceptor gained one internal method taking a `commit` flag, and `intercept` is a one-line
wrapper over it. With `commit == false` nothing is written *locally*: no mirror extension, no verified
record, and no background connection report — because an operator asking whether somebody *can* join
must not thereby cache them as somebody who did. The probe also bypasses the in-flight request
collapsing for the same reason: joining a real login's outstanding request would mean its completion
applied a role snapshot on the probe's behalf.

**One thing a probe on a mirror MISS does write, and it is bot-side: a real `connection-attempt`.**
When the mirror does not hold the player, the probe asks the bot exactly as a login would, and the
bot records that as a connection-history / `lastSeen` / join-feed entry against that player — because
to the bot it is indistinguishable from a real attempt. This is v2's behaviour (its `/hwl test` made
the same call) and it is kept for parity, but it is the surprising survivor an operator running
`/hd test <someone>` in a loop must know about: they are writing to that player's history on the
dashboard, once per probe that misses the mirror. A probe that *hits* the mirror never reaches the
bot and writes nothing anywhere.

A reimplementation would have been the obvious alternative and is the wrong one. The states worth
testing are exactly the ones a second implementation would get subtly wrong, and a diagnostic that
disagrees with the thing it is diagnosing is worse than no diagnostic.

**Resolving the name is part of the answer.** Online player first, then the mirror's own
uuid-to-name mapping — which covers anybody on the whitelist, i.e. the case an operator actually has
— and only then vanilla's `OfflinePlayer:<name>` hash. The command prints whichever UUID it used, so
an operator can see for themselves when the answer is about a derived identity. Asking Mojang is
deliberately not a fourth option; the bot holds the mapping, and a fabricated identity is the failure
departure D58 is about.

### D65 — a setup code is never retried

**New in v3** (v2 had no setup flow).

Every other request in the plugin retries on a transport failure. The claim does not:
`ClaimClient` pins `retries` to 1. A setup code is single-use and the bot consumes it atomically, so
a retry after a response the client failed to read presents a code the bot has already spent — and
the operator is told their code is invalid while the credentials it bought are lost bot-side, with
no way to ask for them again.

Failing the first attempt and saying so is the honest outcome. The bot's own rate limit makes the
same point from the other end: ten failures and it answers `429` to everything, valid codes
included, which is worth knowing before somebody burns three of them.

The endpoint is also the one call in the plugin that carries **no signature**, because the caller has
nothing to sign with yet. That is modelled as a flag on `HttpCall` rather than as a second transport,
so departure D20's "one place a request actually happens" still holds — a fifth copy-pasted request
method for this would be v2's mistake in miniature.

**The wait on the claim is derived, not guessed, and a transport failure never claims the code is
unspent.** `HttpURLConnection` applies its timeout twice — once to connect, once to read — so the
real worst case of a `ClaimClient.TIMEOUT_MS` request is nearly `2 × TIMEOUT_MS`, which is the same
reason `JOIN_SLACK_MS` exists on the login path (D16). A wait shorter than that is the bug the gate
review caught: the `get()` abandons the future while `supplyAsync` keeps running, the bot still spends
the code and mints a token nobody reads, and the operator is told "nothing has changed, and the code
has not been used" — both false. The wait is `2 × TIMEOUT_MS + slack`, and on any transport failure
(timeout, `IOException`, interrupt) the message becomes "the code may already have been used; if this
server does not connect shortly, mint a new one".

### D66 — the endpoint is validated before the code is spent, and it is a security boundary

**New in 1e**, and the gate review's "do not ship without this".

`/hd setup [endpoint]` writes whatever it is handed to `bootstrap.yml`, and from then on that URL is
the bot: it chooses the token, answers the login gate, pushes the role-sync snapshots that grant
LuckPerms groups, and — through the offenses module dispatching the punishment it is handed — runs
console commands of its choosing. A typo'd or hostile endpoint is therefore **remote code execution
on that server**, and a plain `http://` endpoint leaks the token in cleartext.

`BotEndpoint.validate` applies the equivalent of `DownloadPolicy`'s jar guard, *before the code is
spent* so a rejection costs nothing:

- **HTTPS for a public host; http allowed for a loopback or private one** — a self-hosted bot on a
  LAN and the smoke harness's `http://stub-bot:8080` are legitimate and have no certificate. "Private"
  is decided from the host string with **no DNS lookup** (an IP literal is range-checked; a
  single-label name like `stub-bot` cannot be a public FQDN), because resolving a name here would
  itself be a network call driven by operator input.
- **No path, query, userinfo or fragment** — the endpoint is a base URL the client concatenates
  onto, and a `user:pass@` authority is a credential-in-a-config smell that has no business here.

The safe default to be wrong in is "assume public", so a genuinely-private host misjudged as public
costs its operator a `https://` they have to type, while the reverse would wave a cleartext token onto
the internet.

### D67 — v2's global enable/disable becomes a per-module *local* override

**v2:** `/hwl enable` / `/hwl disable` — a global switch (`config.enabled`) that turned all whitelist
checking on or off.
**v3:** `/hd disable [module]` / `/hd enable <module>` — a per-module override, persisted in
`bootstrap.yml`, that **wins over the dashboard** until cleared.

Module state in v3 is dashboard-owned and arrives over the tunnel, which is right almost always and
exactly wrong in the one case an operator most needs a lever: the whitelist is refusing everybody and
the bot cannot be reached to turn it off. So `/hd disable` writes a local set the module manager
subtracts from every desired set — a module named there is not started even if a `config.push` says
it should be, and it stays off across a restart because it is on disk. `/hd disable` with no argument
targets the `whitelist` module, which is what v2's bare `/hwl disable` did ("let everyone in").
`/hd status` lists what is locally off so it is never a silent mystery, and `/hd modules` stops
telling an operator to use the dashboard when the tunnel is down — it points at this instead.

The verbs were dropped in 1e's first cut with no replacement and no entry, which the gate review
caught: a tree called "full" that quietly lost v2's safety valve is a parity regression whether or
not anyone reaches for it.

### D68 — a few operational knobs live in `bootstrap.yml`, because the dashboard cannot deliver them

**New in 1e**, and a stated exception to D17's "everything but the credentials is remote config".

Three sets of settings moved *out* of remote config and into `bootstrap.yml`, each because the
dashboard genuinely cannot own it:

- **The login budget** (`timeoutMs`, `retries`, `retryDelayMs`). These shape the very request that
  would *fetch* the dashboard's config, so a server that got them wrong could never load the settings
  that would fix them. They also had to be a real thing a migration preserves: v2 shipped
  `timeout: 1500, retries: 1`, and inheriting v3's `5000 × 3` defaults balloons a tuned v2 server's
  login budget from ~1.5s to ~18s. `ApiSettingsFactory` reads them; `ApiSettings` clamps them, so a
  nonsense value cannot break the client. (This also corrects `DEFAULT_RETRIES`' old claim to "match
  v2's shipped config" — v2 shipped 1, not 3; v3's 3 is a deliberate resilience choice, and the
  migration overrides it with the operator's real value.)
- **The self-updater's knobs** (`updatesCheckEnabled`, `updatesNotifyAdmins`,
  `updatesCheckIntervalHours`). v3 has no `updates` capability, so the bot's `config.push` narrowing
  drops an `updates` section before it reaches the plugin — a dashboard value there would be
  permanently unread. Local is the only place an operator can actually turn the check off, which v2
  could.
- **The local module-disable set** (`disabledModules`) — departure D67.

Every one of these fails D17's test in the same way: *the dashboard cannot deliver it, and a broken
server still has to read it.* That, and only that, is what earns a field a place in `bootstrap.yml`.

### D69 — health reporting is a module an operator can switch off, and it defaults to on

**v2:** the heartbeat carried a health snapshot unconditionally. There was no capability for it, no
setting, and no way to stop it.
**v3:** `health@1` is a declared capability backed by a real module, and the heartbeat's health
payload is gated on that module's enabled state. **The `ping` is not gated.**

**Found live, on a real server.** A v3 plugin connected declaring
`["whitelist@1", "rolesync@1", "console@1"]` while sending health frames on every heartbeat — the
frames that drive `lastPing`, the bot's health-history ring buffer and its `low_tps`,
`player_surge` and `offline` alerts. The bot advertises `managedModules: ["whitelist", "rolesync",
"console", "health"]`, so the dashboard compared the two, found no `health` capability, and rendered
a **locked** row reading *"Health reporting — Not available in this plugin build"* about a feature
the jar was demonstrably exercising at that moment. The UI was stating something false about the jar.

The cause was structural rather than a missing string: health was emitted by **core** — the tunnel
heartbeat piggybacking a payload from the injected `HealthSnapshotSource` — so no feature module
existed to contribute a capability. Adding the constant to `identify` alone would have fixed the lie
and left the row a dead switch, which is the same lie one click later. So health became what the
capability list already claimed it was:

- **`HealthModule`** (in core, registered by `HeimdallRuntime` itself — core must not depend on the
  feature modules, and this one is core's own) declares `Capabilities.HEALTH` and does exactly one
  thing on enable and disable: flips `TunnelClient.setHealthReportingEnabled`.
- **The gate is in `TunnelHeartbeat.sendHealth()`**, *after* the ping has been written. Health
  doubles as a liveness signal — the bot's 90-second sweep refreshes last-seen on `pong` **and** on
  `health` — so gating the tick rather than the payload would have every server with health switched
  off reaped as dead ninety seconds later. An operator asking to stop publishing TPS is not asking
  to be marked offline.
- **Registered, not enabled**, so the capability is declared from construction. Departure D55 in
  full: a jar that declared `health@1` only once health was running could never be told to run it.

**The default is the part that needed care.** Making health a module subjects it to
`enabledModuleIds()`, and a module entry the bot never mentions parses as `enabled: false` — the
right answer for an unknown module and the wrong one here. A naive version of this change would
therefore have silently switched health **off** on every server that had not yet received a push:
every fresh install, every server whose bot is unreachable, every one in v2-compat, every
unregistered one. So the default is on, twice over and independently:

1. `TunnelClient`'s flag starts `true`, so a tunnel whose reconcile never ran — including every
   tunnel unit test — behaves exactly as it did before this existed; and
2. `HeimdallRuntime`'s **built-in config defaults** now carry `health: {enabled: true}`, which the
   `live push > disk cache > built-in defaults` overlay preserves for any document that does not
   mention health.

Only an explicit `health: {enabled: false}` from the dashboard turns it off. `/hd disable health`
reaches it too, because it is a real module rather than a special case — departure D67 applies to it
for free.

### D70 — v2's directory names are copied out of v2, and "nothing found" is no longer always silent

**v2:** `plugins/HeimdallWhitelist/` on Bukkit (`plugin.yml` `name:`) and
`plugins/heimdall-whitelist/` on Velocity (`@Plugin(id = …)`), because each platform derives a
plugin's data directory from a different declaration.
**v3:** both spelled out as constants in `MigrationBoot`, each one a verbatim copy of v2's own
declaration, each pinned by a test that cites where it came from.

**Found live, on a real Velocity network.** `V2_VELOCITY_DIRECTORY` was `heimdallwhitelist` — v2's
*display name*, hand-lowercased, on the reasoning that "Velocity ids are lower-case". Velocity does
lower-case its ids, but v2's id is `heimdall-whitelist`, hyphen included, and has been in every
release from v2.0.0 to v2.4.0. So on a case-sensitive filesystem the migration searched a directory
no v2 install has ever had, found nothing, and the proxy booted with no credentials and the perfectly
ordinary line *"no bootstrap.yml yet"*. Bukkit was right for a reason that does not generalise: its
constant came from `plugin.yml`'s `name:`, which is what Bukkit actually uses.

**The lesson is about the test, not the constant — and the precise diagnosis is worse than "a bad
fixture".** It is tempting to say the fixture agreed with the constant and so validated the mistake.
That is not what happened: **no test referenced either constant at all.** The migration tests
exercised `V2Migration` directly, passing search directories they had built and named themselves
(`root.resolve("HeimdallWhitelist")` — a string literal that happened to be right, and would have
stayed green whatever `MigrationBoot` said). The one end-to-end row that *did* use the production
path, `paper-migrate` in the connected smoke, ran on Bukkit only. So `V2_VELOCITY_DIRECTORY` had
**zero** coverage — not weak coverage, none — and shipped on nothing but its author's reasoning about
how Velocity derives directory names.

Two things follow, and both are now in place:

- **A name that is not ours to choose is checked against the thing that chose it.** `MigrationBootTest`
  pins each constant with the v2 declaration and the tag range it holds across quoted in the comment,
  and migrates end to end *through* `MigrationBoot.migrate` on the real `plugins/heimdall-whitelist/`
  layout — so the constant is used, not compared against a second copy of the same guess.
- **The smoke matrix has a `velocity-migrate` row.** Its absence was never a harness limitation: the
  migrate assertions wait on log lines and then read the host file system, needing no console, and
  the proxy branch already mounts its plugins directory read-write and stages files before boot. It
  simply did not exist. `migrate` is the one mode whose *inputs* differ per platform — different
  directory name and a different file format — so running it on one platform was always going to miss
  half of it. The matrix guard that used to force every non-`configured` row onto Bukkit now applies
  to `setup` alone, with the reason spelled out next to it.

**One consequence of the fix, for anyone supporting an upgrade.** With the right directory name, a
Velocity proxy that still has *both* jars in `plugins/` — the two declare different ids, so both
load — will now have its live v2 `config.json` renamed to `config.json.v2-backup` on the first v3
boot. That is the same non-destructive move Bukkit has always made (nothing is deleted, and rule
three of "never destructive" still holds), but it is newly *reachable* on the proxy, and a v2 plugin
still running beside v3 will not find its config on the boot after that. Remove the v2 jar.

**And the silence was the other half of the cost.** `V2Migration.run`'s not-found branch is
deliberately silent — a fresh install has no v2 config and does not need to be told so — but a
botched upgrade produces the *identical* observable: no bootstrap, no migration, a server asking to
be set up. That is why this survived deployment and then cost a debugging session. So the branch now
distinguishes the two cases: still silent when nothing beside the plugin looks like a v2 install, and
one INFO line when something does, naming every directory searched (and whether it exists), every
v2-looking directory found beside them, and what each of those holds. Matching is deliberately
loose — case-insensitive, separators stripped, so `HeimdallWhitelist`, `heimdall-whitelist` and
`heimdall_whitelist` all count — because the whole point is to catch a spelling the plugin did *not*
expect. A check that only recognised the expected spelling would be the original bug again.

The closing advice is branched, because the two near misses have opposite fixes: a candidate that was
*not* searched means the directory name is wrong ("move the config into one of the directories
searched"), while a candidate that *was* searched means the name is right and the file inside it is
wrong ("check the file name"), and giving the second operator the first instruction is telling them
to do what they have already done. The whole diagnostic is wrapped in a `catch (RuntimeException)`,
because it walks directories nobody asked it to walk and `V2Migration.run` promises never to throw —
a log line failing must not be what stops a server booting.

### D71 — the dashboard's three on-demand requests are tunnel-level, not module features

**v2:** `get_players`, `run_command` and `probe_player` were handled inline in each platform's entry
point, unconditionally, whenever the tunnel was up. v2 had no module system, so there was nothing
that could have switched them off.
**v3:** the same three are subscribed by `RemoteRequestWiring`, called once from
`HeimdallRuntime.start()`. **No module owns any of them and no toggle can turn one off.**

`update` in `UpdateWiring` is the same *category* — a property of having a tunnel rather than a
feature a guild opts into — but not the same placement, and the difference is worth not blurring:
`UpdateWiring.install` is called from the two platform bootstraps, because it needs a
platform-specific `UpdateInstaller`. Nothing here needs anything a `PlatformFacade` cannot supply, so
this is installed once, in core, rather than twice.

**Found live, on a real Velocity proxy running 3.0.0-rc.2.** v3 had every piece of the reply path —
`TunnelBus.reply`, the correlation map keyed on the echoed id, the dispatcher's subscription step —
and *nothing subscribed to the requests*. So all three frames fell through to the "no handler for
tunnel message" debug line and were never answered. The dashboard's Online Players panel 504ed after
ten seconds; from the outside, a missing subscription is indistinguishable from a server that is down.

The placement question is real and has a defensible wrong answer. `run_command` looks like it belongs
in `module-console`, and `get_players` looks like it could be a roster module. Both are rejected for
the same reason: a guild that switched off console *streaming* would find the dashboard's console
*command box* had silently stopped working, with the dashboard still offering it — a behaviour change
from v2 and from what the dashboard's own permission model (`useWebSocket`) says gates that button.
These are properties of **having a tunnel**, so they are wired where the tunnel is.

Consequences worth stating, because they are what a future reader will want to re-litigate:

- **No handler can answer "module disabled".** There is no module, so the case cannot arise. The only
  thing a capability gate would buy here is the ability to refuse a question v2 always answered.
- **They are subscribed before `start()`'s not-configured early return.** Subscriptions live on the
  client rather than on a socket, so they survive every reconnect and are already in place when
  `/hd setup` brings a tunnel up without a restart — departure D56's shape again.
- **All three run on `heimdall-io`, and both asynchronous continuations name that executor too.** A
  handler on the socket's reading thread stops the tunnel reading, and the bot's sweep then reaps a
  link that is working (departure D27). `run_command` matters most: its future completes on the
  *server's main thread* on Bukkit, and replying there would put a socket write on the tick loop.
- **`TunnelClient.hasSubscribers`** exists so a test can assert that the wiring step ran, not merely
  that the class it would have called is correct. That distinction is the entire bug above.
- **`probe_player` arms its own 10-second deadline** on `heimdall-sched`, cancelled if Trace answers
  first, with a compare-and-set so exactly one of the two can reply. It is the only one of the three
  whose answer comes from *another plugin*, and a `CompletableFuture` a third party never completes
  reproduces this whole departure's bug one layer further out — where it is much harder to attribute.
  Ten seconds because the bot's own probe route waits fifteen: a deadline at or above the caller's is
  not a deadline. Not `heimdall-ws`, whose javadoc reserves it for the tunnel's own sense of time.

**The Bukkit roster read is retried, not merely copied.** `Bukkit.getOnlinePlayers()` is a live
transforming view over `PlayerList`'s plain `ArrayList`, mutated on the main thread; copying it from
`heimdall-io` can throw `ConcurrentModificationException`, or `IndexOutOfBoundsException` where the
view's `size()` and `get()` disagree for an instant. Escaping, that lands in the handler and becomes
`{players: [], error: …}` — an empty Online Players panel on a busy server, which is the same symptom
this departure is about. Five attempts, then throw: a race is momentary by definition, so a second
attempt essentially always wins, and five consecutive failures is something other than a race. It
throws rather than answering "nobody is online", because a failure disguised as an ordinary state is
one nothing downstream can detect. `PlayerDirectory.onlinePlayers` now says so — the previous claim
that these reads were "a read of a concurrent map inside CraftBukkit and safe from any thread" was
simply false, and v2 having the same race is not a reason to keep a comment that lies.

The roster payload's per-platform third column — `ip` on the Bukkit family, `server` on a proxy — is
v2's, kept exactly, including the literal `"unknown"` both platforms fall back to. Core owns `uuid`
and `username` and writes them once; the platform contributes its own key through
`PlayerDirectory.describe`. The alternative — each platform assembling the whole row — is how v2's two
entry points drifted apart in the first place. A proxy deliberately does **not** report an address,
even though it knows one: v2's proxy roster had no such column and quietly starting to publish every
proxied player's IP is not a change a roster reply gets to make on the way past.

### D72 — a console command the server does not have is reported, not acknowledged

**v2:** `run_command` replied `{"output": "Command dispatched: <cmd>"}` whether or not the verb
existed, because it discarded the platform's own boolean.
**v3:** `ConsoleBridge.dispatchCommand` fails with `UnknownCommandException` (departure D59's
subject), and the handler carries that through as `{"output": "no such command: <cmd>"}`. The
successful case reports the bridge's own acknowledgement — `dispatched: <cmd>` — so the operator who
clicked the dashboard button and the operator who typed the command get the same account of the same
event.

The reply *shape* is v2's — `command_result`, one `output` key, which is the only key the dashboard
reads — so nothing downstream changes. What changes is that the sentence is true. An operator who
typed a verb this server does not have was previously told it ran.

Two smaller corrections ride along, both of the same family as the one D71 is about:

- **An empty `command` is answered.** v2 hit a bare `break` and replied nothing at all, so an empty
  console box burned the bot's whole request timeout on a mistake it could have been told about
  immediately.
- **A dispatch that throws is answered.** v2 had no such path, because it never read a result.

`probe_player` needed none of this: `Integrations.traceProbe` already answers with an error payload
for every reason it cannot help, which is v2's #797 / MC-12 fix generalised into the facade. The
handler adds only the two things that facade cannot see — a `uuid` that is not a uuid, and a future
that fails rather than completing. The error *strings* are v3's own wording rather than v2's, which
is deliberate: the shape (`{"error": "..."}`) is the contract, and the text is what an operator reads.

### D73 — a whitelist change is pushed, and the plugin answers it with a debounced pull

**v2:** the mirror was refreshed only by the pre-warm poll, on its interval. A player moved back to
*pending* on the dashboard stayed admitted for up to a full poll period — five minutes at the default
— and whoever had just revoked them watched them keep playing.
**v3:** the bot pushes a `whitelist_changed` **notification** (a real nanoid `id`, an empty payload,
no reply expected) to the v3 connections of the affected guild, and the whitelist module turns it into
one `syncNow()` within ~2s.

Four decisions inside that sentence:

- **A notification, not a diff.** The frame carries nothing. The bot is the source of truth and the
  plugin already has a conditional sync endpoint; a diff on the wire would be a second, weaker copy of
  it — and one that could disagree.
- **Debounced.** A bulk import fires one notification per row, and fifty full syncs back to back would
  land on the single `heimdall-sched` thread that also runs the pre-warm poll and the expiry sweep.
  `WhitelistChangeNudge` arms one one-shot per burst. The ETag makes a no-op sync a 304, so
  over-nudging is cheap — but cheap is not a reason to leave it unbounded.
- **Disarmed before the sync runs, not after**, so a change landing while a sync is in flight arms a
  fresh one rather than being absorbed by a run that started before it happened. Absorbing it would
  lose exactly the revocation the mechanism exists to deliver.
- **Silence when the module is off.** The subscription is made in `enable()` and unwound by the
  context on disable, so a guild with the whitelist module switched off receives the frame, finds
  nothing subscribed, and it is written off with a debug line. There is no mirror to refresh and no
  login decision being made; and because it is a notification, saying nothing costs the bot nothing.

The nudge owns its `ScheduledFuture` rather than registering it through `ModuleContext`, for the same
reason `HeimdallConsoleModule` owns its log tap: the context's tracking bag is unbounded and only
emptied on disable, so a handle per nudge would accumulate one entry every couple of seconds for as
long as the whitelist kept changing. `disable()` closing it is therefore load-bearing.

**The id is not a correlation.** The bot puts one on every frame because v2's `WebSocketClient`
dropped any frame lacking an `id` before dispatch. `TunnelDispatcher` consults the pending-request map
*before* the subscription registry, so a notification's id takes the correlation path first — it
misses silently (`PendingRequests.complete` returns `false` for an id nobody issued, and says nothing)
and falls through to the subscriber. Nothing auto-replies at any layer.

**How v2 reacts to this frame, since the bot may reach a v2 connection:** it is ignored. Both entry
points end their `switch` in a `default` that logs at debug and does nothing else —
`logger.debug("[WS] Unhandled message type: " + type)` on Velocity, wrapped in an explicit
`configProvider.getBoolean("logging.debug", false)` test, and the same line on Paper after first
offering the frame to the `HeimdallTunnel` SPI. `PaperLogger.debug` is itself gated on
`logging.debug`, so on a default install a `whitelist_changed` frame delivered to a v2 plugin produces
**no output at all** and no reply. The bot therefore does not have to filter to v3-only connections
for correctness; it may still choose to, to avoid the wasted frame.

### D74 — BungeeCord's API floor is a pinned release, chosen for what the code uses

**New in phase 2.** v2 had no BungeeCord build at all, so there is nothing to depart from — what
follows is the reasoning recorded so the next person does not re-litigate it.

`:platform-bungee` compiles against **`net.md-5:bungeecord-api:1.16-R0.4`**, which is neither the
latest nor an arbitrary old one. Three properties, in the order they decided it:

1. **It is the oldest `bungeecord-api` release on Maven Central.** The published line starts at
   `1.16-R0.1`; everything older exists only as a `-SNAPSHOT` on the sunset OSSRH host. Every other
   platform API in this build is a snapshot for want of an alternative (`spigot-api`, `paper-api`,
   and `velocity-brigadier` transitively) — this one need not be, and a release cannot be re-resolved
   into something different on a cold CI runner.
2. **It is Java 8 bytecode**, so `--release 8` can read it. That is still true of `1.21-R0.4` today;
   it is the floor's job to keep being true when it stops being.
3. **Every method this module calls is identical in `1.21-R0.4`.** `LoginEvent`, `AsyncEvent`'s
   intents, `ProxyServer`, `PluginManager`, `ProxiedPlayer`, `TaskScheduler`, `ProxyConfig` and
   `TextComponent.fromLegacyText` were compared with `javap` across both. The only differences in the
   types used here, over five years, are `ProxyServer.unsafe()` and `LoginEvent`'s single-component
   `setReason` — and neither is called.

Compiling against the oldest thing that has what we need is what makes the runtime floor a property
of **what the code uses** rather than of whatever happened to be current the day it was written. Two
consequences show up in the code and are commented there: `TextComponent.fromLegacyText` and
`LoginEvent.setCancelReason(BaseComponent...)` are both deprecated on modern BungeeCord in favour of
single-component forms that arrived in the 1.20 line, and both are used anyway.
Deprecated-but-present beats absent-on-half-the-fleet — the same trade `Log4jConsoleTap`'s
four-argument `super` makes (D45).

The boot-smoke matrix holds both ends to account: `bungee-2000` is the last era of BungeeCord
compiled at release 8 and runs on a **Java 8** JRE, and `bungee-2085` is current BungeeCord, which is
classfile 61 and moved its own floor to Java 17 after the API version pinned here.

### D75 — the proxy's login gate defers, because BungeeCord's intents have no timeout

**New in phase 2.**

Every platform's login gate has to make a bounded network call to the bot. Where they run it differs,
and BungeeCord's is the only one where getting it wrong hangs a player forever.

| Platform | Event | Where the check runs |
|---|---|---|
| Bukkit | `AsyncPlayerPreLoginEvent` | on the connection's own thread — blocking there is the point of the event |
| Velocity | `LoginEvent` | on an event-executor thread; blocking is permitted |
| BungeeCord | `LoginEvent` | **on the connection's netty event loop** — blocking stalls every connection sharing it |

So on BungeeCord the handler returns immediately and the decision runs on `heimdall-io`, bracketed by
`event.registerIntent(plugin)` and `event.completeIntent(plugin)`. Two properties of `AsyncEvent`
shape everything about how that is written:

- **`registerIntent` must happen before the handler returns.** It `checkState`s that the event has not
  fired, and the event fires as soon as the last handler returns with no intents outstanding.
  Registering from the worker is a race that loses on an idle proxy.
- **Nothing times out an intent.** `AsyncEvent` holds a latch, a callback and no clock whatsoever. An
  intent that is never completed does not fail the login, or delay it, or log: that player's
  connection sits in the login state until they give up, and no supervisor anywhere in BungeeCord will
  notice.

`completeIntent` is therefore reached on **every** path, exactly once, through an `AtomicBoolean`
rather than through care: the allow path, the deny path, a pipeline that threw an `Error` past its own
containment, and an executor that refused the task because the pools are shutting down. Exactly-once
matters as much as at-least-once — `completeIntent` `checkState`s that an intent is outstanding, so a
second call throws on the netty event loop for a connection that has already been let through.

The nine tests in `BungeeLoginListenerTest` drive the real `LoginEvent` and call `postCall()` where
BungeeCord's own `EventBus` calls it, so "the gate was released" is BungeeCord's latch reaching zero
rather than an assertion the suite invented.

`BungeeBootstrap.disable()` also unregisters the listeners explicitly, before stopping the runtime,
rather than leaving it to the proxy: BungeeCord unregisters a disabling plugin's listeners only on its
own shutdown path, and a login listener that outlived the pools would register an intent and then fail
to submit the work that completes it. The listener handles that case, but not being registered at all
is better than relying on it.

### D76 — the proxy's text boundary is a plain call, because BungeeCord is not Adventure

**New in phase 2.** The mirror image of D44, and worth recording precisely because it looks like the
same problem and is not.

Velocity's API is built on Adventure and Heimdall relocates its own copy, so the two `Component` types
collide and `VelocityText` must reflect. BungeeCord's text API is
`net.md_5.bungee.api.chat.BaseComponent`, which is not Adventure and matches no relocation pattern in
`:app`'s shadow configuration. There is no collision: Heimdall's `Component` and BungeeCord's
`BaseComponent` are two unrelated types, and converting between them is an ordinary method call.
`BungeeText` is nine lines to `VelocityText`'s three hundred.

The conversion is **legacy §-coded text** — `Msg.toLegacy` then `TextComponent.fromLegacyText` — which
is the same round trip the Bukkit binding uses for its kick screen and the Velocity one uses across its
reflective boundary, so a dashboard message renders identically on all three.

JSON was the alternative and was rejected: serialise with Adventure's Gson serializer, parse with
BungeeCord's `ComponentSerializer`. It preserves click and hover handlers, which nothing on any path
this bridge serves has (a kick screen, a command reply, a relayed message). Against that it would mean
shading `adventure-text-serializer-gson` for one platform, and pinning two JSON component *schemas*
against each other across the decade of protocol versions a single BungeeCord speaks. Legacy text is
the format both ends have always agreed on, which is the whole reason to use it.

Hex colours ride along in the `§x§R§R§G§G§B§B` form `Msg` already emits — vanilla's own
repeated-character encoding, which BungeeCord has parsed since 1.16 and which, as on 1.8.8, simply
never appears unless a dashboard template used a hex colour.

### D77 — the proxy's console tap is a JUL handler on the proxy's own logger

**New in phase 2.**

BungeeCord runs no log4j at any version, so the appender `Log4jConsoleTap` attaches would attach to
nothing. The platform-free half of that class — the ANSI strip, the bounded queue, the drain executor,
the re-entrancy guard, the dropped-consumer accounting and the attach-time self-test — moved into a
`ConsoleTap` base, and each backend supplies only what genuinely differs.

Two JUL-specific decisions, both of which fail silently if taken the other way:

- **The attach point is the proxy's own logger, never the JUL root.** `BungeeLogger` is constructed
  through `Logger`'s *protected* constructor, so it is not registered with the `LogManager` and has no
  parent, and it calls `setUseParentHandlers(false)` besides. A handler on `Logger.getLogger("")`
  attaches perfectly and captures nothing, which is indistinguishable from a quiet server. Every
  plugin's logger, by contrast, ends its constructor with `setParent(plugin.getProxy().getLogger())` —
  so one handler on the proxy logger sees the proxy's own lines and every plugin's. It is also why the
  self-test probe is logged on that logger rather than on a `com.heimdall.consoletap` one the way the
  log4j side does: a logger from `Logger.getLogger(name)` has the JUL root as its parent, so its
  records would never arrive and the self-test would fail on a working tap.
- **The level floor is the opposite comparison.** JUL's `intValue()` rises with severity and log4j's
  falls. Copying the log4j form across inverts the filter: every `FINE` line ships and nothing else
  does. Both ends are pinned in `JulConsoleTapTest`.

`WARNING` and `SEVERE` are mapped to `WARN` and `ERROR` on the way out. The console feed carries a
`level` string per line and the dashboard renders it, so a proxy sending `SEVERE` while every backend
behind it sends `ERROR` would be one feature displaying two vocabularies.

`ConsoleTap` also now states plainly what the re-entrancy guard cannot do. It is thread-local, and both
Paper's async loggers and BungeeCord's `LogDispatcher` deliver on a thread of their own, so a consumer
that logs is caught only in the synchronous case. The rule in `ConsoleBridge` — *a consumer must not
log* — is the real protection; the guard is a second line of defence for the one case it can see. The
previous wording implied more than the code delivers, which is the class of comment this codebase has
had to correct twice.

### D78 — there is no v2 migration on BungeeCord, and that is a constant rather than an omission

**New in phase 2.**

v2 shipped a Bukkit entry point and a Velocity entry point and nothing else, so no BungeeCord proxy in
the world has a `plugins/HeimdallWhitelist/` or `plugins/heimdall-whitelist/` of its own. The boot
still calls `MigrationBoot.migrate`, with `MigrationBoot.NO_V2_DIRECTORY` in place of a sibling
directory name.

Naming a directory anyway would be a guess presented as a fact in the one log line an operator reads
when an upgrade appears to have lost their configuration. Skipping the call entirely would cost two
things worth keeping:

- the plugin's **own** data directory is still searched, which is the same rule the other two platforms
  follow — *a config an operator has already dropped in by hand is the one they meant*;
- `V2Migration`'s near-miss diagnostic still runs. An operator who copies `plugins/HeimdallWhitelist/`
  across from a backend expecting it to be picked up is told where to put the file, instead of getting
  a silent unconfigured boot — which is the whole of D70.

Two tests pin the diagnostic **not** firing, and they are the ones worth having. The shaded jar is
called `heimdall-whitelist-<version>.jar` and sits in `plugins/`, right beside the searched directory —
and the near-miss test normalises hyphens and dots away, so its *name* matches "heimdall" +
"whitelist" exactly. Only the `isDirectory()` check stops every proxy in the fleet being told on every
boot that its own jar might be a misplaced v2 install, and nothing held that to account until now.

`connected.sh` refuses a `bungee` row in `migrate` mode outright, for the same reason: it would assert
against a fixture no operator has ever had, which is the mistake the `velocity-migrate` row exists to
have corrected, made in the opposite direction.

### D57 — a mirror's window and ceiling are fixed when it is opened

**New in 1d.**

Everything the whitelist module reads from its settings is read live, at the point of use, because a
settings change does not re-enable a module and a field captured in `enable()` is permanently stale
after the first dashboard edit. Two values are the exception: `cacheWindow` and `maxExtensionHours`
are baked into the `MirrorPolicy` when the store is opened, so a change to either takes effect the
next time the module is enabled — toggling it off and on is enough.

Reopening the store in place was rejected. Two `MirrorStore`s over one file, one of them with a
debounced write still pending, is a way to lose the file — and losing the whitelist mirror is
precisely the thing that turns a bot outage into an outage for every player.

The module logs a warning naming both settings when it sees one of them change, because a setting
that appears to save and silently does nothing is worse than one that says when it will apply.

### D58 — an offline target is refused on every platform

**v2:** the Bukkit path resolved an offline player through `getOfflinePlayerIfCached`; the proxy path
refused outright.
**v3:** refused on both.

`PlayerDirectory` is online-only, and that is a decision rather than an omission: "resolve this name
to a UUID" has a different answer on every platform — Bukkit has a cache of everyone who has ever
joined, a Velocity proxy has nothing at all — the wrong answer is silent, and the bot already knows
the mapping.

v3 applies the proxy's behaviour everywhere rather than being right on one platform and differently
right on the other, because the failure mode is bad: a fabricated or mis-resolved UUID files the
infraction under an identity that never matches the player's real one, history splits in two, and
every escalation tier is computed from half a record (issue #797 / MC-7). It is discovered when a
repeat offender receives a first-offence warning.

Offending a logged-out player is a real workflow, so this is a **stated gap, not a closed question**.
Closing it properly means asking the bot to resolve the name, since it holds the link records and its
answer is identical on every platform. That is a new endpoint, so 1e or 1f — not a `PlayerDirectory`
extension invented in a module.

### D59 — `/offend` always dispatches as the console

**v2:** `player.performCommand(...)` when a player issued it, console dispatch otherwise.
**v3:** always the console.

v2's split meant the punishment plugin re-checked *the moderator's* permissions, so the same offence
landed or did not depending on who reported it — while the bot recorded the infraction either way.
An infraction with no punishment attached to it, silently, for some staff and not others.

---

## Structure

These change no behaviour, but they are the reason the v3 code does not look like v2's.

### D20 — one request path instead of four

v2 had four copy-pasted ~90-line request methods that had drifted: three carried their own retry
loop, only two logged the attempt number, one accepted any 2xx while the others insisted on exactly
200, and one silently swallowed a failure to read the error body. v3 has one `RequestExecutor`.

### D21 — no telescoping constructors, and no positional value types

v2's `WhitelistResponse` had five telescoping constructors, four of which existed only to pass
defaults. v3 models use builders or named factories throughout. The sharper failure is the one that
still compiles: four nullable Strings in a row, with nothing to say which is the ETag and which is
the hash.

### D22 — response models carry `equals`/`hashCode`

Change detection is value comparison. Identity comparison answers "it changed" every time, which
turns a cheap poll into a permanent rewrite of whatever it feeds.

### D23 — `http` does not depend on `config`

The `BootstrapConfig` → `ApiSettings` adapter lives in `com.heimdall.core.wiring`. Phase 1b adds a
second source for the same settings (remote config over the tunnel), which an adapter bolted onto
`ApiSettings` would have had no room for.

### D34 — core owns a JSON value type, so Gson stays private

**New in v3.**

Gson is `implementation` in core, so it is not on a feature module's compile classpath. Phase 1b is
the first time core has to hand a module something JSON-shaped — tunnel payloads, and a module's own
remote-config settings — so `com.heimdall.core.json.Payload` is the one type both speak, and
`Envelope` lives beside it purely so it can reach `Payload`'s package-private Gson bridge and build
a frame without serialising and re-parsing a payload that was just constructed.

`Payload.hashCode` is computed rather than delegated: Gson's `JsonPrimitive.equals` compares numbers
by value while its `hashCode` branches on the concrete `Number` subclass, so a parsed `1` and a built
`1` are equal and hash differently. Payloads are compared for change detection (D22) and could end
up as map keys, which is exactly where that inconsistency loses entries.

### D35 — Adventure is core's one `api` dependency

**v2:** section-coded `String`s passed all the way down, re-interpreted per platform at each call
site.
**v3:** everything user-visible is a `Component`, rendered once at the edge by
`com.heimdall.core.text.Msg`.

`Component` is genuinely in core's public signatures — `Msg` returns one, a pipeline `Verdict.deny`
carries one — so `adventure-api` is declared `api` and core applies `java-library` for it. The legacy
serializer stays `implementation`: it is how `Msg.legacy` is built, not part of what it promises.
Every other module keeps a build file where `implementation` is the only option, which is the right
default. MiniMessage is deliberately absent until the dashboard templates that would produce it
exist.

### D36 — one `Registration` handle instead of paired add/remove methods

**New in v3.**

Every registry returns an idempotent `AutoCloseable` rather than exposing `removeX(handler)`.
Unregistering by identity does not work for lambdas — v2's answer was to keep handlers in fields
purely so they could be removed, or more often to never remove them — and a handle is what makes
ownership trackable, which is what D30 is built on.

### D37 — the WebSocket library sits behind a core-owned seam

**v2:** `WebSocketClient` was written directly against `java.net.http.WebSocket`.
**v3:** `TunnelSocket` / `TunnelSocketFactory` / `TunnelSocketListener`, with one small adapter class
naming nv-websocket-client.

The library was chosen for a constraint that has nothing to do with its API — it is the only mature
client with no logging facade, and legacy Spigot ships no slf4j — and that constraint could change.
The seam is also what makes the invariants testable: a real server cannot be made to black-hole a
connection, error and close simultaneously, or refuse the next four attempts and then accept.


### D48 — the entry points do not contain the wiring

**v2:** `HeimdallPaperPlugin` was 1,086 lines and `HeimdallVelocityPlugin` was 1,311, both of them
`JavaPlugin`/`@Plugin` subclasses doing the same assembly differently.
**v3:** each entry point is a ~40-line shell; a `BukkitBootstrap`/`VelocityBootstrap` owns the order
things are built in; and everything that is not platform-specific lives once in
`com.heimdall.core.wiring.HeimdallRuntime`.

Wiring is exactly the kind of work where duplication is invisible — both files compile, both boot,
and the divergence surfaces as a bug that reproduces on one platform. It also could not be looked at
without starting Minecraft, so it never was. None of the assembly needs a server: it needs a
`PlatformFacade`, which exists precisely so it does not.

The remaining per-platform bootstrap is short enough to compare side by side, which is the point.

### D49 — the platform seam is four small interfaces, not one wide one

**v2:** no seam at all; core and platform were the same classes.
**v3:** `PlatformFacade` answers seven questions, and four of the answers are focused interfaces —
`PlayerDirectory`, `SchedulerBridge`, `ConsoleBridge`, `Integrations`.

A module that only looks players up takes a `PlayerDirectory` and is testable with four lines of
fake. One that took a whole `PlatformFacade` would need a fake for the console, the scheduler and
three optional plugin integrations it never calls — which is how a test suite ends up asserting
against mocks instead of behaviour.

`InstanceRoleDetector` is the same idea inverted: the platform supplies two booleans and the
*policy* that turns them into a `ServerRole` lives in core, so it is identical everywhere and the
whole matrix is testable without a server.

### D50 — proxy detection reads the server's config files, not its API

**v2:** did not detect anything; role was not a concept.
**v3:** reads `settings.bungeecord` from `spigot.yml`, and Paper's velocity switch from `paper.yml`
or `config/paper-global.yml`, through core's own relocated SnakeYAML (`YamlProbe`).

Paper's switch has moved class three times since 1.16 — `PaperConfig.velocitySupport`, gone in 1.19,
`GlobalConfiguration` after that — while the YAML key it is loaded from has changed once, when the
file was renamed. An API-based check would need a reflective probe per Paper generation, each of
which is a place to be silently wrong.

Bukkit's own `YamlConfiguration` would have done the parsing, and is not used: it binds to the
server's bundled SnakeYAML, whose API changed incompatibly at 2.0, so the answer would depend on
which version the server happens to carry. Reading it ourselves also makes the whole thing testable
against a fixture directory — which is how the role matrix is tested at all.

---

## Deliberate non-departures

Places where v3 keeps behaviour that looks wrong, so nobody "fixes" it by accident.

### N1 — `extendOnEvent` sets rather than extends

It assigns `now + windowMs` (capped), so a **shorter** window shortens an entry's expiry — a
120-minute join extension landing after a 180-minute leave extension pulls the expiry back in. That
is v2 verbatim. The plausible improvement is `max(current, capped)`, deferred to 1d so that a
behaviour change and the first real caller do not land in the same commit.

**1d has the first real caller now — the whitelist module's join and quit windows — and the decision
is to leave it alone.** Two reasons, and the second is the one that settles it:

- the reachable case is narrow. It needs a quit to land before the join that preceded it, which the
  asynchronous dispatch (D52) makes possible but rare, and the cost when it happens is an entry that
  expires an hour early and is then re-verified against the bot on the next login. That is the system
  working, just with one extra request;
- `max(current, capped)` is not obviously the safer rule. It makes every window a floor, so a leave
  extension can only ever push an entry further out, and the thing standing between that and
  indefinite access is the #771 ceiling alone. Weakening one bound and leaning harder on the other is
  a change that wants its own reasoning and its own tests, not a line changed in passing while
  shipping the first consumer.

Left as a real option rather than closed: if a server is ever seen re-verifying far more often than
its windows suggest it should, this is the first thing to look at.

### N2 — the whitelist-sync ETag asymmetry between HTTP and WebSocket signing

HTTP signs the path **including** the query string (`req.originalUrl`); the WebSocket upgrade signs
it **excluding** one (`url.pathname`), even though the signature travels as a query parameter. This
is the bot's real behaviour and is named loudly in `HmacSigner`'s two methods rather than smoothed
over.

### N3 — YAML comments are lost on save

SnakeYAML discards them at parse time.

**The original justification was wrong and is corrected here rather than deleted**, because it is the
reasoning somebody will reach for again. It said saves come from the setup flow, which only ever
rewrites a file it wrote itself. `/hd setup` exists as of 1e and that is still not true: guild
discovery saves over the file unprompted the first time it answers, the v2 migration writes it from
a file somebody else authored, and every install that predates the setup command was hand-written.
Hand-written comments really are being lost, on real installs.

It is still the right trade, for a different reason: the alternative is carrying a
comment-preserving YAML editor to protect four keys and a cache. What the loss is not allowed to be
is *surprising*, which is why the one field the plugin writes on its own initiative is called
`guildIdCache` on disk (D54). The name is the only thing in that file that can explain itself, since
a comment saying so would not survive the next save either. Unknown keys are still preserved (D19);
only the comments around them are not.

### N4 — the client still sends a ping the bot ignores

The bot special-cases exactly `identify`, `pong`, `health` and `console_line`. There is no `ping`
case, so a **client-initiated** ping gets no reply and does not refresh liveness — it lands where
`trace.report` does. Client liveness derives entirely from answering the *bot's* pings, or from
sending `health`, which the sweep does count.

The heartbeat sends one anyway. It is harmless, and it is what the deployed v2 fleet does, so
removing it would be a wire change made in passing rather than a decision anyone took. Making a
client ping meaningful is a bot-side capability decision for a later phase; inventing it here would
produce a plugin that looks healthy in testing and gets reaped in production.

### N5 — a capability id and a module id are not the same string (RESOLVED bot-side)

`Capabilities.WHITELIST` is `whitelist@1`; a config document files that module's settings under the
unversioned id `whitelist`. If the bot compared the two with exact string equality then nothing would
match and the client would receive config for no modules at all — silently, because an empty push is
a perfectly valid push.

**The bot side has since decided, and this is now settled.** It narrows with `capabilityModuleId()`
— the base name — so `whitelist@1` does match the `whitelist` key, and versioned ids work as
designed. `stub-bot` implements the same rule, and `TunnelStubIntegrationTest` asserts the
resolution rather than the hazard.

Left in this file rather than deleted: the entry is why `Capabilities.moduleId()` and
`Capabilities.version()` exist, and the failure it describes is the one somebody will go looking for
the next time a module runs with no configuration.

### N6 — the declared capability set is a snapshot taken when the socket opened (RESOLVED in 1d)

**Superseded by departure D55, and left here because the reasoning below is still how somebody will
arrive at the question.**

The hazard was real and its fix came from the opposite direction to the one this entry expected. The
set is no longer the *enabled* modules but the *registered* ones, and registration happens before
`start()` and never changes — so there is nothing left for a mid-connection toggle to make stale, and
the asymmetry described below no longer exists in either half. What forced the change was not this
entry but a deadlock: an empty set is not a v3 handshake at all, so a fresh install never negotiated
v3 and never received the config that would have enabled anything.

The original entry follows.

`identify` is sent once per connection, so the capability set the bot has been told about is
whatever was enabled at that moment. A module toggled while the tunnel is up is not reflected until
the next reconnect.

The consequence is asymmetric, and both halves are tolerable today. A module switched **off** leaves
the bot pushing config nothing reads — harmless. A module switched **on** may receive no config
until the tunnel next reconnects, and runs on its built-in defaults or its disk cache until then.

Reconnecting to re-advertise would fix it and is deliberately not done: dropping a working tunnel to
update metadata would make every dashboard toggle a brief outage, which is a worse trade than a
delayed config push. Whether the protocol should instead gain a live capability update is a bot-side
decision for phase 1f — not invented here, because a client announcing capabilities in a way the bot
does not understand looks correct in testing and is ignored in production.

### N7 — a role sync diffs against *inherited* groups, and that is v2's behaviour

`GroupDiff` is fed what LuckPerms reports from `user.getInheritedGroups(...)`, which includes groups
held transitively through another group rather than directly. Two consequences follow, and both are
v2's, reproduced deliberately:

- a managed group the player inherits (rather than holds) is already in "current", so it is never
  *added* — correct, since granting it would change nothing;
- the same group, when the dashboard drops it from the target set, **is** listed for removal, and
  removing the direct node does nothing because the player still inherits it. The sync then logs a
  removal that did not take effect.

The alternative — diffing against directly-held nodes only — changes which groups get written on
every sync for every server that uses group inheritance, which is most of them. That is a behaviour
change with a real blast radius and no reported complaint behind it.

**Do not "fix" this in 1d without deciding it explicitly.** The decision needs the bot side in the
room: what the dashboard means by "this player has this group" is the actual question, and the
plugin's diff is downstream of the answer.

### N8 — v2's `cleanupUser` is not ported yet

v2's Velocity LuckPerms manager exposed `cleanupUser(uuid)`, which drops a user from LuckPerms'
in-memory cache so the next read comes from storage. Nothing called it on a schedule; it existed for
callers who needed fresh data.

`LuckPermsBridge` has no equivalent, because in 1c nothing read groups on a cadence — the login path
loads the user itself and the role-sync module did not exist yet.

**Decided in 1d: it is not needed, because there is no loop.** The role-sync module has no polling at
all. It reacts to a pushed snapshot and to a login answer, and in both cases the snapshot is the
*bot's*, carried on the event, rather than something read back out of LuckPerms. The only read of
LuckPerms' own state is the one `setPlayerGroups` does internally to compute the diff, and that loads
the user from storage when it is not cached — which is the behaviour `cleanupUser` exists to force.
Invalidating beforehand would buy nothing and cost a storage round trip on every sync.

The condition that would change the answer is worth writing down, because it is the shape of the
2.4.0 outage (D7) in a different place: **if the role-sync module ever grows a periodic reconcile
that reads current groups and compares them against a snapshot it is holding, it needs an
invalidation step**, and `LuckPermsBridge` needs a method for it. Until then, adding one would be
adding a cache-management API with no cache to manage.

Separately, 1d did close a *different* staleness hole in the same bridge: a resolved `LuckPerms`
handle is now dropped whenever a call using it fails, so a LuckPerms hot-reloaded under a running
server heals on the next call instead of leaving `isAvailable()` answering `true` about a shut-down
instance forever.

---

## Seams named but not built

Shapes that phase 1c decided and phase 1d implements. They are recorded here so 1d builds the agreed
thing rather than improvising one, and so a reviewer can object *now* rather than to the code.

### S1 — join and quit arrive as notifications, not as a third pipeline

**BUILT in 1d**, exactly as specified below. `com.heimdall.core.session.PlayerSessionEvents` is the
dispatcher; `BukkitSessionListener` and `VelocitySessionListener` push into it; `ModuleContext`
exposes `onPlayerJoin` / `onPlayerQuit` with the usual tracked registrations. The two details 1d
settled beyond the shape are in D52.

Platform adapters push join and quit into core through a `PlayerSessionEvents` dispatcher: the
adapter supplies a `PlayerHandle` and a timestamp, modules subscribe through `ModuleContext` with
the usual tracked registrations, and the handle is unwound when the module is disabled like every
other registration.

Two things it is deliberately **not**:

- **Not a third `Pipeline`.** A pipeline exists to arbitrate a decision — allow, deny, abstain,
  ordered by priority, first denial wins. Join and quit have no decision to arbitrate: the player is
  already in or already gone. Modelling them as one would invite an interceptor to "deny" a quit,
  and the pipeline's whole vocabulary would be wrong.
- **Not more `PlatformFacade` methods.** The facade answers questions core asks the platform.
  This is the platform telling core something happened, which is the opposite direction, and
  bolting it on would make every platform implement a listener registry as well as a set of
  accessors.

1c deliberately ships no join/quit listeners at all rather than dead ones: nothing consumes the
events until the whitelist mirror lands in 1d (its `extendOnEvent` window is the first real
consumer), and a listener with no consumer is a listener nobody will notice has stopped working.

### D51 — the identify_ack is read the way the bot actually writes it

**Earlier v3 client:** required the ack to echo the identify's id, and read `accepted` as a boolean.
**Now:** the id is not compared at all, and `accepted` is read as the list of capabilities the bot
will honour.

Both were assumptions about the bot, and both were wrong in the same direction — they would have
made every connection to a real v3 bot fail to negotiate v3, while the bot believed it had:

- the bot sends `id: nanoid()`; it does not correlate the ack with the identify. The echo check
  would have discarded every ack it will ever send, so the client would have timed out into
  v2-compat on every connection. Cross-socket delivery is already impossible without that check —
  `TunnelClient`'s callbacks carry a generation and stale ones are inert (D24) — so it was a second
  layer built on a false premise about the first;
- `accepted` is a `string[]`. `Payload.bool("accepted", false)` over a JSON array returns the
  fallback, so the client would have logged "the bot refused this plugin's protocol version" at
  SEVERE and dropped to v2-compat on every successful handshake.

There is no refusal frame in this protocol at all: a capability the bot does not support is simply
absent from `accepted`, and an empty list is a *successful* handshake with a bot that recognised
none of what this build declared. The boolean is still read, but only to detect an explicit refusal
from a bot answering the older shape.

Caught by transcribing the bot's `feat/minecraft-v3-protocol` branch into `stub-bot` rather than by
testing — which is exactly what a fixture that claims to be executable documentation is for. The
tests that pinned the old behaviour were pinning a client-side belief, not a contract.

