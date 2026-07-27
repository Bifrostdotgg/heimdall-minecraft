# stub-bot

A fake Heimdall bot: the HTTP API and the WebSocket tunnel the Minecraft plugin talks to, on one
port, behind real HMAC verification.

It is two things at once.

1. **A dependency the smoke matrix can start** — the plugin can be pointed at it without a Discord
   bot, a Mongo, or a network.
2. **The contract, executable** — everything documented below is implemented in
   `src/main/java/com/heimdall/stubbot/` and exercised by a test in `src/test/java/`. If this file
   and the code ever disagree, the tests fail. That is the point: a protocol description that nobody
   runs rots within one release.

It is **never shipped**. `:app` does not depend on it, and `:app:verifyShadowJar` fails the build if
a foreign class ever reaches the plugin jar.

---

## Running it

```bash
./gradlew :stub-bot:run                                    # demo fixtures, port 8080
./gradlew :stub-bot:run --args="--port=9000 --verbose=true"

./gradlew build                                            # also produces a launcher:
stub-bot/build/install/stub-bot/bin/stub-bot                # (stub-bot.bat on Windows)
```

From a test:

```java
try (StubBot bot = StubBot.start(StubBotConfig.withDemoFixtures().port(0))) {
    String baseUrl = bot.baseUrl();          // http://127.0.0.1:<ephemeral>
    bot.fixtures().put(PlayerFixture.of(uuid, "Steve", Outcome.ALLOW));
    bot.ws().sendRoleSync(guildId, uuid, "Steve", List.of("vip"), List.of("vip"), List.of("vip"), List.of());
}
```

### Configuration

Every setting has a working default, so the fixture is useful with no configuration at all.
Precedence: defaults → `STUB_BOT_*` environment variables → `--key=value` arguments.

| Setting | Default | Meaning |
| --- | --- | --- |
| `STUB_BOT_BIND` | `0.0.0.0` | Bind address for the public port. |
| `STUB_BOT_PORT` | `8080` | The one port serving both HTTP and the WebSocket upgrade. `0` = ephemeral. |
| `STUB_BOT_GUILD_ID` | `123456789012345678` | Must be 17–20 digits, or the WebSocket route will not match it. |
| `STUB_BOT_API_KEY` | `stub-bot-dev-key` | The HMAC shared secret (`INTERNAL_API_KEY` on the real bot). |
| `STUB_BOT_PLAYERS` | demo fixtures | Inline JSON array of player fixtures (schema below). |
| `STUB_BOT_PLAYERS_FILE` | — | Path to the same JSON. Easier in compose. |
| `STUB_BOT_DEFAULT_OUTCOME` | `deny` | Outcome served for a UUID with no fixture. |
| `STUB_BOT_PING_INTERVAL_MS` | `30000` | WebSocket ping sweep interval. |
| `STUB_BOT_LIVENESS_TIMEOUT_MS` | `90000` | Silence after which a connection is reaped. |
| `STUB_BOT_UNREGISTERED_SERVERS` | — | Comma-separated server ids to treat as absent from the registry. |
| `STUB_BOT_FOREIGN_SERVERS` | — | Comma-separated server ids registered to a *different* token — their upgrade is refused with 403. |
| `STUB_BOT_REGISTRY_UNREADABLE` | `false` | Simulates a registry outage; with an incumbent under another token the upgrade is refused with 503. |
| `STUB_BOT_CONFIG_VERSION` | `1` | Starting config version advertised to v3 clients. |
| `STUB_BOT_MODULES` | all but `console` | Inline JSON for the `config.push` `modules` object. |
| `STUB_BOT_OFFENSE_TYPES` | one "Cheating" type | Inline JSON array, same shape as the bot's `OffenseType` documents. |
| `STUB_BOT_PLUGIN_LATEST` | a v3.0.0 stub release | Inline JSON for `GET /plugin/latest`. |
| `STUB_BOT_VERBOSE` | `false` | Log every request and every WebSocket frame. |

Player fixture schema (`STUB_BOT_PLAYERS`):

```json
[
  { "uuid": "1111…", "username": "Steve", "outcome": "allow",
    "targetGroups": ["vip"], "managedGroups": ["vip", "member"] },
  { "uuid": "2222…", "username": "Alex",  "outcome": "deny" },
  { "uuid": "3333…", "outcome": "pending_auth",     "authCode": "135790" },
  { "uuid": "4444…", "outcome": "revoked",          "revocationReason": " for griefing" },
  { "uuid": "5555…", "outcome": "pending_approval", "queuePosition": 3 },
  { "uuid": "7777…", "outcome": "pending_approval", "schedule": "on Friday at 18:00 UTC" },
  { "uuid": "6666…", "outcome": "existing_link",    "authCode": "246800" },
  { "uuid": "7777…", "outcome": "allow", "roleSyncEnabled": false },
  { "uuid": "8888…", "outcome": "allow", "linkedDiscordId": "999…", "linkedDiscordUsername": "steve" }
]
```

The whitelist is **derived** from the outcomes rather than tracked separately — `allow` and
`existing_link` are whitelisted, the rest are not. One place to change a player's state means
`connection-attempt` and `whitelist/sync` cannot drift apart, which is exactly the disagreement the
plugin's pre-warm cache would otherwise hide until a bot restart.

---

## Authentication

HMAC-SHA256. The shared secret never crosses the wire.

```
canonical = ${timestamp} \n ${METHOD} \n ${path} \n ${sha256hex(body)}
signature = hex(hmac_sha256(secret, canonical))
```

- `timestamp` is **seconds** since the epoch, as a decimal string.
- The method is upper-cased.
- The body is hashed **even when empty** — a bodyless request signs over
  `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- Requests more than **±5 minutes** from the server's clock are rejected as replays.
- Signatures are compared in constant time.

### `path` means different things on the two transports

This asymmetry is the single most likely thing to get wrong, and it is invisible until a real
connection is refused.

| Transport | Where the signature travels | What `path` covers |
| --- | --- | --- |
| HTTP | `X-Signature` + `X-Timestamp` headers | Path **including** the query string (Express `req.originalUrl`). |
| WebSocket upgrade | `signature` + `timestamp` **query parameters** | Path **excluding** the query string (`url.pathname`). |

So the WebSocket handshake signs `/ws/minecraft/123456789012345678` even though the URL it is sent
on is `/ws/minecraft/123456789012345678?serverId=…&signature=…&timestamp=…`.

### Golden vectors

With secret `test-secret-key` and timestamp `1700000000`:

| Case | Method | Path signed | Body | Signature |
| --- | --- | --- | --- | --- |
| HTTP POST | `POST` | `/api/guilds/123456789012345678/minecraft/connection-attempt` | `{"username":"steve","uuid":"11111111-2222-3333-4444-555555555555"}` | `9a5b013496d6e835a067272b2d7e7856f0e071c415dd3086ae5725ec117b7654` |
| HTTP GET + query | `GET` | `/api/guilds/123456789012345678/minecraft/whitelist/sync?since=42` | *(empty)* | `cbaf8b0e3665a386221189e597f54cbd0fdf90779d159c0657849b52d7f752b6` |
| HTTP GET | `GET` | `/api/guilds/123456789012345678/minecraft/offense-types` | *(empty)* | `c34e64c928c404380b8511078abc15167db357ccf2d441ec41d98247e204e915` |
| WS upgrade | `GET` | `/ws/minecraft/123456789012345678` | *(empty)* | `40738ba535de6dc0fc828033c57f2672eeedbd0a5e08adc4535729756605c71d` |

These were computed independently in PowerShell and in Node, and the bot's own `verifyRequest` was
shown to accept the canonical form. They are asserted in `HmacTest`.

---

## HTTP endpoints

All guild routes are mounted under `/api/guilds/{guildId}/minecraft/`.

Successful responses use the envelope `{"success": true, "data": {…}}`; errors use
`{"success": false, "error": {"code": …, "message": …}}`.

> **One exception, reproduced on purpose.** A failed HMAC on a guild route answers **401 with a bare
> `{"error":"Unauthorized"}`** — *not* the envelope. That is what the real bot's middleware does. A
> client that assumes `error.code` exists on every failure breaks against it, and a fixture that
> tidied this up would hide that. `POST /api/minecraft/identify` authenticates inline and *does* use
> the envelope.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/minecraft/identify` | Resolve an API key → `{guildId}`. Not guild-scoped; authenticates inline. |
| `POST` | `…/connection-attempt` | The login gate. Called on every join. |
| `GET` | `…/whitelist/sync` | Full whitelist for the pre-warm cache. ETag + `304`. |
| `POST` | `…/request-link-code` | `/linkdiscord` in game → a 6-digit code. |
| `GET` | `…/offense-types` | Offense types with escalation tiers. `data` is an **array**. |
| `POST` | `…/offend` | Record an offense, resolve the escalation tier. |
| `GET` | `…/plugin/latest` | Latest release metadata for the self-updater. |

### `POST …/connection-attempt`

Request (the fields the v2 plugin sends):

```json
{ "username": "steve", "uuid": "…", "ip": "1.2.3.4", "serverIp": "mc.example.com",
  "serverId": "survival", "currentlyWhitelisted": false, "currentGroups": ["default"],
  "isBedrock": false, "bedrockGamertag": null, "bedrockXuid": null }
```

Missing `username` or `uuid` → `400 MISSING_FIELDS`. A guild with no config → `404 NOT_CONFIGURED`.

Otherwise the answer is **always `200` with `success: true`** — the decision lives in `data`, in one
of six shapes. A plugin that only handles the first two mis-handles the rest in production.

| Outcome | `whitelisted` | Extra keys in `data` |
| --- | --- | --- |
| `allow` | `true` | `message`, `roleSync` |
| `deny` | `false` | `message` |
| `pending_auth` | `false` | `message`, `pendingAuth: true`, `authCode` |
| `revoked` | `false` | `message`, `revoked: true` |
| `pending_approval` | `false` | `message`, `pendingApproval: true`, **`queuePosition` only sometimes** — see below |
| `existing_link` | **`true`** | `message`, `existingPlayerLink: true`, `authCode` |

`existing_link` is the one most easily got wrong: the player **is admitted** and simultaneously
offered a link code.

**`pending_approval` has two branches, and `queuePosition` is conditional.**

| Fixture | Response |
| --- | --- |
| `queuePosition` set | Staff approval required. `queuePosition` is present, and the message carries `{position}`. |
| `queuePosition` absent (set `schedule` instead) | Auto-whitelist is on but **scheduled**. The bot computes no position and spreads nothing into the response, so **the key is absent — not null**. The message tells the player when to come back (`{schedule}`). |

A plugin that reads `queuePosition` unconditionally breaks on the second branch and nowhere else,
which is why `ScheduledSam` is in the default demo set.

`roleSync` itself has three shapes, all of which the plugin must handle:

| Value | Meaning |
| --- | --- |
| `null` | Nothing to apply — a row with no target-group snapshot yet. |
| `{"enabled": false}` | The bot drives LuckPerms over RCON. The plugin must keep out. |
| `{"enabled": true, "targetGroups": […], "managedGroups": […]}` | Apply this snapshot. |

Explicit `null`s are serialised as `null`, not dropped — an absent key and a null one are not the
same thing to a parser.

Message templates substitute `{player}` / `{username}`, plus `{code}`, `{position}` and `{reason}`
for the outcomes that carry them, exactly as the bot does.

### `GET …/whitelist/sync`

```json
{ "success": true, "data": {
    "hash": "bc9e075e…", "count": 2, "generatedAt": "2026-07-27T13:38:18.916Z",
    "players": [ { "uuid": "…", "username": "Steve" } ] } }
```

- `ETag: "<hash>"` on every response.
- `hash` = **SHA-1 over the sorted UUIDs, each followed by `\n`**. Sorting is what makes it
  order-independent, so it moves only when membership actually changes.
- `If-None-Match` matching the current hash → **`304`, no body**. The bot strips quotes before
  comparing, so `"abc"` and `abc` both match.

Golden ETags (asserted in `WhitelistEtagTest`, computed independently in PowerShell and Node):

| Whitelist | ETag |
| --- | --- |
| *(empty)* | `da39a3ee5e6b4b0d3255bfef95601890afd80709` |
| `11111111-2222-3333-4444-555555555555` | `774864e2e86bc1d01827984372a1e8453759cee8` |
| the three UUIDs in `WhitelistEtagTest`, in any order | `bc9e075e23532f87033c1ec6cf3355122829cbe3` |

### `POST …/request-link-code`

`{"username", "uuid"}` → `{"alreadyLinked": false, "code": "135790"}`, or when the fixture carries a
`linkedDiscordId`, `{"alreadyLinked": true, "message", "discordId", "discordUsername",
"discordDisplayName"}`.

The real bot mints a random code; the stub derives a stable one from the UUID (or uses the fixture's
`authCode`) so a test can assert the exact value.

### `POST …/offend`

`{"targetUuid", "targetUsername", "offenseSlug", "issuedByUuid"?, "issuedByUsername"?, "notes"?}`.

Unknown slug → `404 UNKNOWN_OFFENSE`. Otherwise the escalation maths is the bot's own: the running
non-pardoned count for that *offense type* (not slug — slugs sharing a type share a counter) is
incremented, then **the lowest tier whose `points` is at least the new total wins**, pinning to the
highest tier once the player runs off the end. `{reason}` is resolved first so a command template can
embed it.

```json
{ "success": true, "data": {
    "infraction": { … }, "command": "tempban Cheater 1d Cheating — tier 2 (3 points)",
    "action": "tempban", "duration": 1440, "totalPoints": 3, "tierApplied": 2,
    "tierDescription": "tempban (1d)", "offenseType": "Cheating" } }
```

Durations format as `1h`, `1d`, `7d`, `1h30m`. `bot.resetInfractions()` clears the counters.

---

### `POST /api/minecraft/claim`

**Public — no signature.** It has to be: a server claiming a setup code has no token to sign with
yet, which is the entire point of the endpoint. It is registered ahead of the HMAC gate on the real
bot too.

Request: `{code, platform?, mcVersion?, role?}`.

- `code` is upper-cased and then stripped of every non-alphanumeric, so `abcd-2345` and `ABCD 2345`
  both reach `ABCD2345` — an operator reads the code off a screen and types the dash they see.
- `role` is validated against `gatekeeper` / `enforcer` / `standalone` and **silently becomes null**
  when it is anything else. Not a 400. A client sending a typo gets a successful claim and a server
  with no role.

Success (200): `{success: true, data: {guildId, tokenId, token, serverId, serverName}}`. `token` is
the plaintext API key, returned exactly once.

| Status | code | When |
| --- | --- | --- |
| 400 | `MISSING_PARAMS` | `code` absent or not a string. |
| 401 | `INVALID_CODE` | Unknown, expired or already used. Codes are single-use. |
| 429 | `TOO_MANY_ATTEMPTS` | Ten failures from one client. Checked **before** the body is read, so a throttled caller gets 429 whatever it sends. |

Issue a code in a test with `bot.http().issueClaimCode("ABCD2345", "Survival")`. A successful claim
also **registers** the new serverId, which is what the WebSocket upgrade then looks up.

### `POST …/servers/{serverId}/config/import`

Signed, and the **only** server-config route a guild-scoped Minecraft token may call — every other
route under `servers/` is dashboard-only (see below).

Request `{modules}`. **Write-once:** a second import against a server that already has a document is
a **200 with `imported: false`** and the stored modules unchanged, not a conflict. A plugin migrating
a v2 config file therefore cannot clobber what an operator has since edited in the dashboard, and
does not need to ask first.

Success (200): `{serverId, imported, version, modules}`.

| Status | code | When |
| --- | --- | --- |
| 403 | `FORBIDDEN` | `"This API key does not own that server."` — the serverId is registered to a different token. |

There is deliberately **no 404**: an unregistered serverId is allowed through, because the document
it writes is inert until a registry row points at it. Refusing would only force a plugin to claim
before it could import.

## Upgrade refusals

Most refusals are a silent socket close — a bad path, a missing or invalid signature — unchanged
from v2 so a v2 plugin sees exactly what it always did. Two are **real HTTP responses**, because a
plugin has to be able to tell them apart:

| Status | When | Retry? |
| --- | --- | --- |
| 403 Forbidden | The serverId has a registry row naming a **different** token. | **No.** Permanent until somebody changes the configuration. |
| 503 Service Unavailable | The registry could not be read **and** a live connection for that id is held by a different token. | **Yes**, with backoff. The bot refuses to guess during an outage rather than let two servers share an id. |

**A same-token reconnect always passes**, by both routes: with a readable registry the row matches,
and with an unreadable one the incumbent check does not fire. That is the case that actually happens
in production — a server restarting — and it must never be refused. With an unreadable registry the
connection is then treated as **unregistered**, so it gets `configVersion: 0` and no push.

Reproduce with `STUB_BOT_FOREIGN_SERVERS` and `STUB_BOT_REGISTRY_UNREADABLE`.

## WebSocket tunnel

`GET /ws/minecraft/{guildId}?serverId=…&signature=…&timestamp=…` — same port as the HTTP API.

The guild id must be **17–20 digits** or the route does not match at all. An absent `serverId`
defaults to `default`. A failed upgrade is refused at the handshake.

Reconnecting with a `serverId` that is already connected **replaces** the old connection (the stale
socket is closed with `1000 Replaced by new connection`).

### Envelope

Every frame, in both directions:

```json
{ "id": "<correlation id>", "type": "<message type>", "payload": { } }
```

**Correlation is by echoed id.** A request carries a fresh id; the reply must carry the same one.
Anything arriving with an unrecognised id is treated as unsolicited and handed to the registered
`WsMessageListener` rather than silently dropped.

### Message table

Bot → plugin:

| Type | Payload | Correlated |
| --- | --- | --- |
| `ping` | `{}` | Reply `pong` with the same id. **This is the only keepalive that exists.** |
| `role_sync` | `{uuid, username, targetGroups, managedGroups, groupsAdded, groupsRemoved}` | No — broadcast to every server in the guild. |
| `get_players` | `{}` | Yes → `player_list`. |
| `run_command` | `{command}` | Yes → `command_result`. |
| `probe_player` | `{uuid, username}` | Yes → `probe_result`. |
| `update` | `{}` | Yes → `update_result`. |
| `identify_ack` | `{protocolVersion, accepted, configVersion}` | v3 only; **fresh id — it does NOT echo the `identify` id**. |
| `pong` | `{}` | v3 only; echoes the client `ping` id. Gives **no** liveness credit. |
| `config.push` | `{version, modules}` | v3 only; fresh id. |

Plugin → bot:

| Type | Payload | Handling |
| --- | --- | --- |
| `identify` | metadata, plus v3 `protocolVersion` + `capabilities` | Recorded. **Not a liveness signal** — the bot's handler only stores metadata. See below. |
| `pong` | `{}` | Refreshes liveness. |
| `health` | `{tps?, mspt?, onlinePlayers?, maxPlayers?, usedMemMb?, maxMemMb?}` | Stored; **also refreshes liveness**. |
| `console_line` | `{lines: [{ts, level, msg}]}` | Collected per server (bounded to 2000). |
| `config.ack` | `{version}` | v3 only; recorded. Not a liveness signal. |
| `trace.report` / `trace.annotation` | trace payloads | **Real bot-side sinks**, not test noise: the bot's `onClientMessage` listener persists them to Mongo via `traceIngest.ts`. The stub has no database, so it forwards them to its `WsMessageListener` like any other unsolicited message. |
| `ping` | `{}` | v3 only: answered with `pong` echoing the id. A v2 connection gets **silence**. Never a liveness signal. |
| `player_join` / `player_leave` / anything else | — | Forwarded to the `WsMessageListener`. |
| `player_list` / `command_result` / `probe_result` / `update_result` | — | Correlated by id. |

> **A client `ping` is answered only for a v3 connection, and never credits liveness.** Both halves
> matter. A v2 connection has no declared capabilities, so its ping falls through to id-correlation
> and then to the listener — silence, byte-identical to what a deployed v2 plugin has always seen. A
> v3 connection gets a `pong` echoing its id, so it can time its own round trip and spot a half-open
> socket before the 90-second sweep would.
>
> `lastSeen` is **not** refreshed by it. Liveness stays bot-attested: a client ping is
> self-certification, and a plugin whose modules had all died but whose keepalive thread still ran
> would otherwise renew its own staleness deadline forever — never tripping the sweep, never firing
> the offline alert. The bot's own ping → client `pong` covers socket liveness, and *that* path does
> refresh it.

### Liveness

The bot pings **immediately on connect** — that is how the plugin learns the link is live before its
own heartbeat tick. Then a sweep every `STUB_BOT_PING_INTERVAL_MS` (30 s) pings every open
connection and closes any that has been silent for `STUB_BOT_LIVENESS_TIMEOUT_MS` (90 s) with
`1001 Heartbeat timeout`.

Liveness is refreshed by `pong` and `health` **only** — not by `identify`, not by `config.ack`, and
not by a client-initiated `ping` (which is answered, but earns nothing).

Both `pong` **and** `health` count as liveness, so a plugin whose heartbeat carries health without an
explicit pong stays connected.

### `identify`, and the v3 capability handshake

A **v2 client** sends metadata only:

```json
{ "id": "…", "type": "identify", "payload": {
    "serverId": "survival", "serverName": "Survival", "plugins": [],
    "pluginVersion": "2.4.0", "platform": "bukkit", "mcVersion": "1.8.8",
    "serverSoftware": "Paper", "startedAt": 1753000000000 } }
```

…and gets **nothing back**. Silence is the v2 contract; acking would be a protocol change no deployed
plugin asked for.

A **v3 client** additionally declares `protocolVersion` and `capabilities` (either an array
`["whitelist@1","rolesync@1"]` or a flag map `{"whitelist": true, "console": false}`).

The handshake is triggered by a **non-empty `capabilities` array alone** — `protocolVersion` on its
own does not. A client that declares one without the other is a v2 client as far as the wire is
concerned.

1. **`identify_ack`**, with a **fresh id**:
   `{"protocolVersion": 3, "accepted": ["whitelist@1"], "configVersion": 1}`.
2. **`config.push`** (fresh id): `{"version": 1, "modules": { … }}`, narrowed per connection.
3. Whatever **`config.ack`** `{"version": 1}` the client sends back, recorded on the connection.

#### Three things a client gets wrong without reading this

**`accepted` is a list, not a boolean.** There is no refusal frame in this protocol at all. A
capability the bot does not support is simply absent from the list, and an **empty list is a
successful handshake** with a bot that recognised none of what the client declared. A client that
reads it as a boolean sees `false`, reports that the bot refused its protocol version, and downgrades
itself to v2-compat — while the bot believes it negotiated v3 and starts pushing configuration into
a client that has stopped listening for it.

**The ack's id is fresh, not the identify's.** The bot does not correlate the two. A client that
requires an echo discards every ack it will ever receive, times out, and falls back to v2-compat on
every single connection.

**An unregistered server gets an ack and no config.** `configVersion` is `0` and no `config.push`
follows, because the bot never reads the config store for a serverId it has no registry row for. The
client runs on its own defaults — which is exactly v2 behaviour, and is why this is a supported state
rather than an error. Reproduce it with `STUB_BOT_UNREGISTERED_SERVERS`.

#### Which capabilities are accepted

Exact major match against a fixed table — `whitelist`, `rolesync`, `console`, `health`, `modules`,
`config`, all at major 1:

| Declared | Accepted | Why |
| --- | --- | --- |
| `whitelist@1` | `whitelist@1` | Exact match. |
| `whitelist` | `whitelist` | No `@N` reads as major 1 — and it is echoed back **verbatim**, not normalised to `whitelist@1`. |
| `whitelist@beta` | `whitelist@beta` | A non-numeric major also reads as 1. Surprising, and it is what the bot does. |
| `whitelist@2` | — | Known module, wrong major. Dropped. |
| `teleport@1` | — | Unknown module. Dropped. |

Duplicates collapse to their first occurrence and declaration order is preserved. Rejections are
logged bot-side and are **otherwise silent**: omission from `accepted` is the entire signal.

#### What gets pushed

`modules` is narrowed to the **base module id** of each accepted capability, so `whitelist@1` lets the
`whitelist` key through. Pushing config for a module the client cannot run is how a silently-ignored
setting is born.

`modules@1` and `config@1` are accepted and acknowledged like any other capability but contribute
**nothing** to the push: no config document has keys by those names, so narrowing to them yields an
empty map.

An empty `accepted` list narrows to **nothing**, not to everything — "the client declared nothing I
understand" and "the client is a v2 client" are different states, and only the second gets no push
at all.

#### WIRE CONTRACT: never dedupe a `config.push` by version

**A client MUST apply every `config.push` it receives and MUST NOT skip one whose `version` it has
already seen.** The version identifies the stored *document*, which is guild+server scoped — but the
`modules` map is narrowed per **connection**, against that connection's capabilities.

Two connections from the same server (different jar builds, a module compiled out by an update) can
therefore legitimately receive different maps at the same version, and a re-push at an unchanged
version can legitimately carry a different map than the previous one did. Versions are only ever
compared *within* one connection, for staleness reporting via `config.ack` — never to skip an apply.

`bot.ws().pushConfig(guildId, serverId)` bumps the version and re-pushes: the hot-toggle path.

---

## Test hooks

On `StubBot`:

- `fixtures()` — mutate players and the whitelist while running.
- `resetInfractions()` — clear the `/offend` escalation counters.
- `baseUrl()`, `port()`.

On `StubBot.ws()`:

- `awaitConnection(guildId, serverId, timeoutMs)` / `awaitIdentify(…)`
- `connected(guildId)` / `connected(guildId, serverId)` → `ConnectedServer` (identify payload,
  capabilities, last health, console lines, acked config version)
- `sendRoleSync(…)`, `broadcast(guildId, type, payload)` → number of sockets reached
- `getPlayers(…)`, `runCommand(…)`, `probePlayer(…)`, `triggerUpdate(…)` → `CompletableFuture`
- `pushConfig(guildId, serverId)`, `configVersion()`
- `setMessageListener(…)` — every envelope the stub did not handle itself

---

## Why one port

The real bot answers HTTP and the WebSocket upgrade on the **same** port; the plugin derives its
WebSocket URL from `api.baseUrl` by swapping the scheme. The JDK's `HttpServer` cannot hand a socket
over for an upgrade and Java-WebSocket cannot serve ordinary HTTP, so `PortMultiplexer` sits in
front: it reads each connection's request head, routes on the `Upgrade` header, and relays bytes.

The alternative — two ports — would force a second config key that only ever exists for tests, and a
config shape production never exercises is exactly what works in CI and fails on a customer's server.

## Deliberate deviations from the real bot

Everything here is a conscious simplification, not an oversight.

| Deviation | Why |
| --- | --- |
| 401 on a guild route returns a bare `{"error":"Unauthorized"}` | **Not** a deviation — this reproduces the real bot's inconsistency. Listed so nobody "fixes" it. |
| One guild, one API key | Guild-scoped token resolution, the `minecraft` scope, and per-guild key fallback are bot-side concerns; the plugin cannot tell the difference. |
| Auth codes are derived from the UUID, not random | So a test can assert the exact code without scraping a previous response. |
| Infractions live in memory and are never pardoned | The plugin only reads the resolved tier and command. |
| No Bedrock/Floodgate identity resolution | The stub matches on UUID; prefix inference and same-name/different-edition guarding are bot-side matching rules with no wire-visible effect on the plugin. |
| No `player_join` / `player_leave` / alerting side effects | Those drive Discord and the dashboard, not the plugin. |
| A silently rejected upgrade fails the handshake; the real bot destroys the socket | Both look like a failed connection to the client. Java-WebSocket has no socket-destroy hook. The two refusals that carry a *status* (403, 503) are written on the raw socket before the library sees it, exactly as the bot does — see "Upgrade refusals". |
| No token identity: one API key owns everything | The 403 and 503 paths are reproduced with explicit fixtures (`STUB_BOT_FOREIGN_SERVERS`, `STUB_BOT_REGISTRY_UNREADABLE`) rather than by modelling a token registry. A plugin cannot tell the difference — it sees the status line either way. |
| `trace.report` / `trace.annotation` are forwarded, not persisted | They are real bot-side sinks backed by Mongo. The stub has no database; the wire behaviour (accepted, no reply) is identical. |
| The timestamp parse is **stricter** than the bot's | The bot uses JavaScript `Number()`, which accepts hex, a leading `+`, exponents and whitespace; `Double.parseDouble` also accepts `"5d"`. None of that is a timestamp a client sends. The divergence is one-directional and fail-closed: the stub can refuse a request the bot would accept, never the reverse. |

## Deferred, deliberately

**Client-initiated keepalive is answered but earns nothing, and that is the final shape.** It was
open in the v2 protocol and the bot has since settled it: a v3 connection gets a `pong` echoing its
ping's id, so a plugin can measure its own round trip and detect a half-open socket — but the reply
does not refresh liveness, because a client ping is self-certification. A plugin whose modules had
all died but whose keepalive thread still ran would otherwise renew its own staleness deadline
forever, never tripping the sweep and never firing the offline alert.

**Rate-limit windows are not modelled.** The claim endpoint refuses after ten failures like the
bot's, but the failures never expire — the bot forgets them after ten minutes. A test that wants a
clean slate restarts the stub, which is cheaper than making a fixture keep wall-clock time.

## Dashboard-only routes, not fixtured

These exist on the bot and are **deliberately absent here**. They are called by the dashboard with
the global key, never by a plugin, so a fixture for them would be documentation of something no
client under test can reach:

| Method | Path |
| --- | --- |
| GET | `/api/guilds/{g}/minecraft/servers` |
| POST | `/api/guilds/{g}/minecraft/servers/claim-code` |
| DELETE | `/api/guilds/{g}/minecraft/servers/claim-code/{code}` |
| DELETE | `/api/guilds/{g}/minecraft/servers/{serverId}` |
| GET | `/api/guilds/{g}/minecraft/servers/{serverId}/config` |
| PUT | `/api/guilds/{g}/minecraft/servers/{serverId}/config` |

The one exception is `POST …/servers/{serverId}/config/import`, which a plugin *does* call and which
is fixtured above.
