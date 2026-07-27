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
