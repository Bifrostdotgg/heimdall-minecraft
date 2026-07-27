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

TODO(1d): a headless-client join on the `paper-1.21.8` row — connect, send a message, assert it is
suppressed and that the client stays connected. That single test settles risk 2 and turns the chat
interceptor from a design into a behaviour.

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
the boot-smoke matrix exercises the attach on all six supported servers on every run.

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

---

## Deliberate non-departures

Places where v3 keeps behaviour that looks wrong, so nobody "fixes" it by accident.

### N1 — `extendOnEvent` sets rather than extends

It assigns `now + windowMs` (capped), so a **shorter** window shortens an entry's expiry — a
120-minute join extension landing after a 180-minute leave extension pulls the expiry back in. That
is v2 verbatim. The plausible improvement is `max(current, capped)`, deferred to 1d so that a
behaviour change and the first real caller do not land in the same commit.

### N2 — the whitelist-sync ETag asymmetry between HTTP and WebSocket signing

HTTP signs the path **including** the query string (`req.originalUrl`); the WebSocket upgrade signs
it **excluding** one (`url.pathname`), even though the signature travels as a query parameter. This
is the bot's real behaviour and is named loudly in `HmacSigner`'s two methods rather than smoothed
over.

### N3 — YAML comments are lost on save

SnakeYAML discards them at parse time. Saves come from the setup flow, which writes a file that
either did not exist or that it wrote itself, so this trades a rare cosmetic loss against carrying a
comment-preserving YAML editor.

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

### N6 — the declared capability set is a snapshot taken when the socket opened

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

`LuckPermsBridge` has no equivalent, because in 1c nothing reads groups on a cadence — the login
path loads the user itself and the role-sync module does not exist yet. **1d prerequisite:** decide
whether the role-sync module needs it before it starts polling. A module that reads cached groups in
a loop and never invalidates is a module that acts on a stale answer indefinitely, which is the
shape of the 2.4.0 outage (D7) in a different place.

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

