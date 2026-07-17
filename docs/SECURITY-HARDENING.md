# Security Hardening — threat model, audit, and mitigation spec

Status: **design doc, not yet implemented.** This is the read-only security
analysis that MASTERPLAN §6 ("Protocol hardening is our job") and the §10
launch sequence step 6 ("Security sweep + protocol hardening") require before
launch. It is written as an implementation spec: a future coding session
executes the mitigations in [§4](#4-mitigation-spec-prioritized); nothing here
has been coded yet.

Audited against:

- minecom `main` as of 2026-07-17 (this branch: `security-hardening`).
- Minestom **`2026.07.12-26.2`** (the version pinned in `pom.xml`). All
  Minestom line numbers below are from a local decompile of that exact jar;
  re-verify them on any version bump (see [§5](#5-re-checking-on-a-minestom-bump)).

Companion document: `docs/SECURITY.md` (the public disclosure policy). Any
sensitive attack-sequence detail derived during this analysis is kept in an
**uncommitted** local note outside the repo (flagged for private upstream
disclosure), never in this file — see [§5](#5-re-checking-on-a-minestom-bump).

---

## 1. Threat model

### 1.1 Assets to protect

1. **Server availability** — the tick loop, the accept loop, the
   virtual-thread pool, heap, file descriptors, and the HDD-bound chunk I/O
   path (this laptop's real bottleneck; see the P0 work in `docs/HANDOFF.md`).
2. **World and player data integrity** — the Anvil regions and persistence
   state (`Persist`), which a malicious client must not corrupt or force
   unbounded growth of.
3. **Other players' sessions** — one client must not be able to crash, lag,
   or hijack another's session.

### 1.2 Attacker model

- **Unauthenticated remote (primary).** Anyone who can open a TCP connection
  to the listen port. Minecom runs **offline mode** (no Mojang auth — see
  [§3.7](#37-offline-mode--no-authentication)), so "unauthenticated" and
  "authenticated" collapse into the same actor once a proxy is not in front:
  reaching the PLAY state requires only speaking the protocol, not proving an
  identity. This is the actor the hardening is built around.
- **Malicious authenticated player (secondary).** A client that has reached
  PLAY and abuses in-game packets/commands (chat spam, huge sign/book text,
  window-click floods, expensive commands).
- **Not in this model:** a compromised host, a malicious hosting provider, or
  an attacker on the JVM. Those are out of scope (`docs/SECURITY.md`).

### 1.3 Inbound attack surface

Every byte a client sends crosses, in order:

1. **The accept loop** —
   `net.minestom.server.network.socket.Server` (~L70-103): one
   `PlayerSocketConnection` + two virtual threads per accepted socket.
2. **The frame reader** —
   `net.minestom.server.network.packet.PacketReading.readPacket`
   (L86-125) → `readFramedPacket` (L127-160): length-prefix framing,
   optional zlib decompression, packet-id dispatch.
3. **The per-player packet queue** — `Player.addPacketToQueue` /
   `interpretPacketQueue`, bounded by `ServerFlag.PLAYER_PACKET_QUEUE_SIZE`
   and drained `PLAYER_PACKET_PER_TICK`/tick.
4. **minecom's event handlers** — everything wired in `Bootstrap.boot`
   (`Bootstrap.java:75-286`) and `Main.registerConnectionFlow`
   (`Main.java:83-118`), plus the ~30 command handlers
   (`Bootstrap.java:259-279`).

### 1.4 Vectors

| # | Vector | Where it bites | §4 priority |
|---|---|---|---|
| V1 | Connection flood / idle-connection hold (pre-auth) | accept loop, buffer pool, fds, vthreads | **P0** |
| V2 | Slow-loris / partial-frame stall | frame reader read timeout | **P0** |
| V3 | Packet-queue overflow false-kick under tick stall | per-player queue vs HDD I/O | **P1** |
| V4 | Oversized / malformed packets | frame reader, deserializers | P2 (mostly upstream) |
| V5 | Decompression bomb | `readFramedPacket` | P3 (upstream-covered) |
| V6 | Unbounded / expensive commands in offline mode | command handlers | **P1** |
| V7 | Application-content floods (chat, signs, books, clicks) | event handlers | P2 |
| V8 | Persistence-growth abuse | `Persist`, Anvil writer | P2 |

---

## 2. What Minestom already handles (do not rebuild)

Confirmed by reading `2026.07.12-26.2`. These are real, and the mitigation
spec **relies** on them rather than duplicating them.

| Control | Mechanism | Default | Verdict |
|---|---|---|---|
| Frame size cap (post-auth) | `PacketReading.readPacket` L102-106; `maxPacketSize` L181-185 | `MAX_PACKET_SIZE` = **2 097 151** (~2 MiB) | Rejects negative and oversized frames with `DataFormatException`. |
| Frame size cap (pre-auth) | same, HANDSHAKE/LOGIN branch | `MAX_PACKET_SIZE_PRE_AUTH` = **8 192** | Tight pre-auth cap — good; keeps a pre-login attacker to ~8 KiB frames. |
| Decompression bound | `readFramedPacket` L131-158 | declared length must be `0 ≤ n ≤ maxPacketSize`; output written into an exactly-`dataLength` slice; `written != dataLength` throws (L146) | **Decompression bombs are handled.** No unbounded inflate; see [§3.5](#35-decompression-bomb-v5). |
| Per-player inbound flood | `Player.addPacketToQueue` / `interpretPacketQueue` | `PLAYER_PACKET_QUEUE_SIZE` = **1000**, `PLAYER_PACKET_PER_TICK` = **50** | Overflow → `"Too Many Packets"` kick. Caps sustained per-player packet rate — but see [§3.3](#33-packet-queue-overflow-false-kick-v3). |
| Dead-connection reaping (post-login) | keep-alive | `KEEP_ALIVE_DELAY` = **10 000 ms**, `KEEP_ALIVE_KICK` = **15 000 ms** | Kicks players who stop answering keep-alives. Note: **post-login only** — does not cover pre-auth idle ([§3.1](#31-connection-flood--idle-hold-v1)). |
| Socket buffers | `Server.configureSocket` L106-113 | send **262 143**, recv **32 767** | Bounded kernel buffers per socket. |
| Chunk-update rate | per-player chunk-update limiter | `PLAYER_CHUNK_UPDATE_LIMITER_HISTORY_SIZE` = **5** | Rate-limits client-driven chunk churn. |
| Entity interaction range | `ENFORCE_INTERACTION_LIMIT` | **true** | Rejects out-of-range interaction packets by default. |
| Transfer packets | `ACCEPT_TRANSFERS` | **false** | Won't honour cross-server transfer packets by default. |

---

## 3. Read-only audit — vector by vector

### 3.1 Connection flood / idle-hold (V1)

**Minestom side.** The accept loop
(`net.minestom.server.network.socket.Server` ~L70-103) accepts **every**
socket unconditionally and immediately starts a reader and a writer virtual
thread plus a `PlayerSocketConnection` (L88-95). There is **no per-IP
connection cap, no global connection cap, and no accept-rate limit** anywhere
in this loop. `configureSocket` (L106-113) sets `SO_TIMEOUT` =
`SOCKET_TIMEOUT` (15 000 ms) on the channel's socket.

**minecom side.** `Main.java:71` binds `server.start("0.0.0.0", 25565)` — all
interfaces, no proxy, no flags. `registerConnectionFlow`
(`Main.java:83-118`) sets no connection cap; `AsyncPlayerConfigurationEvent`
(`Main.java:84-90`) accepts any login. The `ServerListPingEvent` advertises
`max = 20` (`Main.java:116`) but **nothing enforces 20** — it is cosmetic.
A grep of `src/main/java` for `maxplayer`/`MojangAuth`/`connection.*limit`
returns only gameplay-level limiters, none at the connection layer.

**Exposure.** minecom is **exposed to a vector Minestom does not cover.**
Connection count, buffer-pool pressure, thread count, and file descriptors
are all unbounded from an unauthenticated attacker. The two sub-cases:

- *Flood:* rapid connect/disconnect churns connection objects and the buffer
  pool.
- *Idle-hold:* accept, then stay silent. There is a subtle, important gap
  here around whether `SO_TIMEOUT` actually reaps a silent connection on the
  NIO blocking read path — detailed defensively below and, with the
  repro-shaped specifics, in the uncommitted upstream-disclosure note.

### 3.2 Slow-loris / partial-frame stall (V2)

**Minestom side.** `PacketReading.readPacket` L107-113: when a frame's
declared length exceeds the bytes available, the reader rewinds and waits for
more, failing the connection only once `requiredCapacity > buffer.capacity()`.
Because `packetLength` is capped at `maxPacketSize` (2 MiB post-auth, 8 KiB
pre-auth), a client can declare a large frame and then **trickle** its bytes;
the read side keeps waiting. The only backstop is the read timeout.

**The read-timeout question.** `SO_TIMEOUT` is set on a `SocketChannel`'s
socket, but the channel is read in blocking mode on a virtual thread.
`Socket#setSoTimeout` is documented not to govern reads performed through the
`SocketChannel` itself. **If** the shipped build's blocking channel read does
not honour `SO_TIMEOUT`, a trickling or silent pre-auth connection has no idle
deadline. This must be verified on minecom's exact pinned build before launch
(see [§5](#5-re-checking-on-a-minestom-bump)); the confirmation procedure and
its consequences live in the uncommitted note, not here.

**Exposure.** Potentially exposed, pending the timeout verification. Even in
the worst case per-connection memory is bounded (2 MiB post-auth, 8 KiB
pre-auth), so this is a **connection-count** exhaustion axis, not a
per-connection one — which is why the V1 connection cap is the shared primary
mitigation.

### 3.3 Packet-queue overflow false-kick (V3)

**Minestom side.** `PLAYER_PACKET_QUEUE_SIZE` = 1000, drained ≤ 50/tick. On
overflow the player is kicked `"Too Many Packets"`.

**minecom side.** The P0 benchmark work already hit this
(`docs/HANDOFF.md` item 7): on this HDD-bound laptop, a tick-thread stall from
chunk I/O pauses the drain while a bot's steady ~2 packets/tick keep arriving,
overflowing the 1000-slot queue in a few seconds with only a handful of
clients — a **self-inflicted DoS** under legitimate load. The fix applied was
`-Dminestom.packet-queue-size=20000` — **but that flag lives only in the bench
harness** (`scripts/bench/bench_common.py:47,213-216`). The production
entrypoint (`Main.java:71`) sets no JVM flags, so **production ships the
default 1000.** The value the P0 work proved necessary never reaches players.

**Exposure.** Exposed to a self-DoS / low-effort-DoS: the queue that is meant
to be the anti-flood control is, at the production default on slow storage,
itself the failure point. Raising it blindly, though, trades a false-kick for
a real unbounded-queue memory risk — the mitigation has two halves ([§4.3](#43-p1--v3-packet-queue-decouple-drain-from-tick-stalls)).

### 3.4 Oversized / malformed packets (V4)

**Minestom side.** Length caps ([§2](#2-what-minestom-already-handles-do-not-rebuild)) reject
oversized frames before deserialization. Malformed *content* (bad enums,
truncated fields) throws inside `readPayload` (`PacketReading.java:162-179`),
wrapped as a `RuntimeException`, failing that read and disconnecting the
client — contained per-connection. A trailing-bytes case only logs a warning
(L169-172; minecom already suppresses that noise via `logback.xml` per
`docs/HANDOFF.md` item 5).

**minecom side.** minecom registers its own deserialization only in a few
spots that read client-influenced structured data — notably `VTemplate.java:209`
(`BinaryTagIO.unlimitedReader()` on GZIP), which is **structure-template NBT
from bundled resources, not a client packet**, so not remotely reachable. No
minecom code parses raw client packet bytes ahead of Minestom's caps.

**Exposure.** Largely **covered by Minestom**; minecom adds little raw-packet
surface. Residual risk is any minecom event handler that trusts a
client-supplied count/length without bounding it (audit target in
[§4.5](#45-p2--v4v7-content-validation--handler-bounds)).

### 3.5 Decompression bomb (V5)

**Covered by Minestom.** `readFramedPacket` (L131-158): the declared
uncompressed length must satisfy `0 ≤ dataLength ≤ maxPacketSize`, the output
is decompressed into a slice of *exactly* `dataLength`, and a mismatch between
bytes written and `dataLength` throws (L146-148). A "declare 8 KiB compressed
→ inflate to 500 MiB" bomb is impossible: the declared length is capped at
2 MiB and the inflate cannot exceed the slice. **No minecom action needed**
beyond not weakening the pinned build's compression path and not calling
`BinaryTagIO.unlimitedReader()` on any client-supplied stream (it currently
does so only on bundled resources — keep it that way).

### 3.6 Expensive commands in offline mode (V6)

**minecom side.** `Bootstrap.java:259-279` registers ~30 commands. None are
permission-gated (a grep of `Commands.java` for `permission`/
`getPermissionLevel` finds nothing). `/fill` is bounded to `MAX_BLOCKS`
= 32 768 per call (`Commands.java:341,357`) but is **not rate-limited across
calls**; `/summon` has no visible entity-count cap. In an authenticated,
op-gated server these are admin tools; in **offline mode with no gating,
every connected client can run all of them**.

**Exposure.** Exposed as a post-auth **amplification** surface, gated only by
"reach PLAY". Lower priority than the pre-auth vectors, but it must be closed
for any public offline deployment.

### 3.7 Offline mode / no authentication (cross-cutting)

`Main.java` never calls `MojangAuth.init()`, so there is no player
authentication and no transport encryption: usernames are spoofable, identity
is meaningless, and there is no per-account rate limiting to hang throttles
on. This is a reasonable default for a **LAN / behind-a-proxy** deployment and
for the benchmark harness, but it makes "unauthenticated attacker" and
"player" the same actor on a public bind. The supported production shapes
([§4.1](#41-p0--v1-connection-admission-control)) are: (a) behind a trusted
proxy (Velocity/ProxyProtocol) that does the auth, or (b) `MojangAuth.init()`
enabled. This is documented as an operator choice in `docs/SECURITY.md`, and
the hardening below assumes one of those two shapes for any public server.

---

## 4. Mitigation spec (prioritized)

Each item is written for a future coding session. Ordering is by
risk × ease. **P0 first.** All of this is new code under `src/` plus config;
none of it changes gameplay, so it slots cleanly into the launch sequence.

### 4.1 P0 — V1: connection admission control

**Goal:** bound concurrent connections and connection churn per source, at
the earliest possible point, before a socket becomes a full player pipeline.

**Design.**

1. **Prefer a trusted proxy for public deployments.** The lowest-code, most
   robust answer is to *not* expose minecom's port directly: bind minecom to
   loopback / a private interface and put Velocity (or any ProxyProtocol
   front) in front, letting the proxy do connection limiting, auth, and
   encryption. Enable Minestom's `PROXY_PROTOCOL` / `PROXY_PROTOCOL_REQUIRED`
   (currently both **false**) so minecom only accepts connections carrying the
   proxy header, and set `AUTH_PREVENT_PROXY_CONNECTIONS` appropriately. Ship
   this as the **documented default production topology.**
2. **In-process admission guard (for direct-bind / no-proxy operators).**
   Add a small connection-accounting layer that runs before a connection is
   allowed to progress:
   - a **global cap** on concurrent connections (default ≈ `max-players ×
     small-factor`, e.g. 3×, configurable);
   - a **per-IP concurrent-connection cap** (default small, e.g. 3–5);
   - a **per-IP new-connection rate limit** (token bucket, e.g. N/10 s).
   Minestom's accept loop exposes no hook, so implement this as either
   (a) a `ConnectionState`-aware early listener that disconnects
   over-limit connections during HANDSHAKE, or (b) a thin accept-side wrapper
   if a future Minestom version adds a pre-connection hook (raise this
   upstream — see [§4.6](#46-the-upstream-contribution)). Account by remote
   `InetAddress`; release on disconnect. Exempt loopback and a configurable
   trusted-CIDR allowlist so the bench harness and a fronting proxy are never
   throttled.
3. **Enforce the advertised player cap.** Reject logins in
   `AsyncPlayerConfigurationEvent` (`Main.java:84`) once
   `getOnlinePlayers().size() >= maxPlayers`, so the `max = 20` in the ping
   (`Main.java:116`) becomes real. Make `maxPlayers` a config value shared by
   the ping and the gate.

**Config:** new keys (env vars or a small config file, matching how the bench
harness passes `-D` flags): `minecom.max-players`,
`minecom.max-connections-per-ip`, `minecom.connection-rate-per-ip`,
`minecom.trusted-cidrs`, `minecom.require-proxy-protocol`.

**Verification:** a `--selftest`/`--playtest` scenario that opens N loopback
connections past each cap and asserts the over-limit ones are refused while a
trusted-CIDR / loopback client is never refused (per CLAUDE.md rule 4;
loopback must stay exempt or the harness itself breaks).

### 4.2 P0 — V2: pre-auth / login-completion deadline

**Goal:** guarantee that a connection which does not progress to PLAY within a
bounded time is disconnected, independent of whether `SO_TIMEOUT` fires on the
NIO read path.

**Design.**

1. On connection (earliest observable point, HANDSHAKE), schedule a
   **login-completion deadline** task (default ≈ 30 s, configurable). If the
   connection has not reached the PLAY state when it fires, disconnect it and
   release its V1 accounting. Cancel the task on successful PLAY transition.
   This does **not** depend on `SO_TIMEOUT` and closes the idle/slow-loris
   hold regardless of the channel-read-timeout question in [§3.2](#32-slow-loris--partial-frame-stall-v2).
2. Keep `MAX_PACKET_SIZE_PRE_AUTH` at its 8 192 default (already tight); do
   **not** raise it.
3. Verify the `SO_TIMEOUT`-on-blocking-channel-read behaviour on the pinned
   build (procedure in the uncommitted note). If it turns out to be
   ineffective, that is the finding to carry into the upstream contribution
   ([§4.6](#46-the-upstream-contribution)); the in-process deadline above is
   the local mitigation either way.

**Verification:** a scenario that opens a loopback connection, completes the
TCP handshake, sends nothing, and asserts disconnection within the deadline
(and that a normal login is never affected).

### 4.3 P1 — V3: packet-queue — decouple drain from tick stalls

**Goal:** stop the production self-DoS without simply hiding it behind a huge
buffer.

**Design.**

1. **Immediate:** set a deliberate production `packet-queue-size` and
   `packet-per-tick` in the real launch path — not only in the bench harness.
   Add the JVM flags (or `ServerFlag` equivalents) to a production launch
   script / documented run command so `Main` no longer inherits the bare
   1000 default. Pick the value from the P0 data, not the bench's 20 000
   blindly: 20 000 was chosen for a 5400 rpm HDD's worst-case stall and is a
   memory ceiling, so document the trade-off and let operators lower it on
   fast storage.
2. **Real fix:** the root cause is that chunk I/O stalls the tick thread while
   packets keep arriving. Attack it at the source, tracked against
   MASTERPLAN §5.0 / P1: move HDD-bound chunk reads off the tick thread (or
   bound their per-tick time budget) so the queue drains steadily. A larger
   queue is slack; the decoupling is the fix. Prefer the structural fix
   (CLAUDE.md rule 8: "conserved quantities / state gates, never wider
   tolerances") over living on a bigger buffer forever.
3. Confirm the queue overflow still protects against a genuine flood at the
   chosen production size (an actual >50-packets/tick attacker should still be
   kicked) — i.e. don't set the queue so large that V3's protection value is
   gone.

**Verification:** re-run the P0 spawn/ramp scenarios at the production queue
value on an idle machine and confirm no `"Too Many Packets"` kick under
legitimate load, while a synthetic >50/tick flood is still kicked.

### 4.4 P1 — V6: gate expensive commands for public deployments

**Goal:** stop unauthenticated clients (offline mode) from running admin/
worldedit-class commands and amplifying load.

**Design.**

1. Introduce a **permission / op model** or a single `minecom.public-mode`
   flag. In public mode, the operator commands (`/fill`, `/setblock`,
   `/summon`, `/give`, `/gamemode`, `/tp`, `/time`, `/weather`, `/enchant`,
   `/effect`, `/difficulty`, `/xp`, `/clear`, `/drain`, `/end`, …) are
   registered only for op'd players (or disabled). Gameplay commands that are
   meant to be public (`/spawn`, `/killme`) stay open. Do this at registration
   in `Bootstrap.java:259-279` (conditional registration) or via a Minestom
   command condition.
2. **Rate-limit** the few commands that are expensive even for an op:
   `/fill` and `/summon` get a per-player cooldown / budget (reuse the
   established per-player throttle pattern already used for vibrations,
   `Vibrations.java:41`). Keep the existing `MAX_BLOCKS` per-call cap
   (`Commands.java:341`).

**Verification:** scenario asserting a non-op player in public mode cannot run
`/fill`, and that repeated `/fill` from an op is cooldown-limited.

### 4.5 P2 — V4/V7: content validation & handler bounds

**Goal:** ensure minecom's own handlers bound any client-supplied
count/length that Minestom's frame cap doesn't semantically constrain.

**Design.**

1. **Audit pass** over minecom event handlers that read client-controlled
   sizes/counts and add explicit bounds where missing: sign/hanging-sign text
   (`Signs.java`), book/lectern text (`Lectern.java`), banner patterns
   (`Banners.java`), decorated-pot / item-frame / armor-stand NBT,
   window-click stack counts, and any custom-payload/plugin-message handler.
   A 2 MiB frame is a lot of sign text; cap content to vanilla limits at the
   handler, not just at the frame size.
2. **Rate-limit** chat and other client-initiated broadcasts per player so a
   PLAY-state client can't fan out cheap messages to every online player
   (`Main.java:97-99` broadcasts to all online players on join — audit the
   same pattern anywhere a client action triggers an all-players send).
3. Prefer minecom's bundled-data readers to stay on **bounded** NBT readers;
   never point `BinaryTagIO.unlimitedReader()` at a client stream (currently
   used only on bundled resources at `VTemplate.java:209` — keep it so).

**Verification:** per-handler checks asserting over-limit content is rejected/
truncated to vanilla limits.

### 4.6 The upstream contribution

If [§3.2](#32-slow-loris--partial-frame-stall-v2)'s timeout check confirms
`SO_TIMEOUT` is ineffective on Minestom's blocking channel reads, the fix is
also the pre-launch upstream contribution MASTERPLAN §7.5 wants: propose to
Minestom either (a) an explicit login-completion deadline enforced
independently of `SO_TIMEOUT`, and/or (b) an accept-side hook so servers can
do per-IP connection accounting without shadowing the accept loop. Route it
through **private disclosure first** (`docs/SECURITY.md` → Minestom
maintainers), not a public PR, until coordinated — see
[§5](#5-re-checking-on-a-minestom-bump).

---

## 5. Re-checking on a Minestom bump

The launch-prep intel (`docs/intel/launch-prep.md` §5) records a
**confirmed-live, then-unpatched Minestom DoS** (mjbommar, 2026-06-19): a
"trivial" DoS Mojang silently fixed in vanilla 26.2, reproduced against
current Minestom. Every finding here is version-specific, so on **every**
Minestom bump (`pom.xml` `minestom.version`) re-run this checklist:

1. **Re-derive the ServerFlag defaults** from the new jar (the numbers in
   [§2](#2-what-minestom-already-handles-do-not-rebuild) are from
   `2026.07.12-26.2`): `MAX_PACKET_SIZE`, `MAX_PACKET_SIZE_PRE_AUTH`,
   `PLAYER_PACKET_QUEUE_SIZE`, `PLAYER_PACKET_PER_TICK`, `KEEP_ALIVE_KICK`,
   `SOCKET_TIMEOUT`, `PROXY_PROTOCOL`, `ENFORCE_INTERACTION_LIMIT`. A silent
   default change is itself a security event.
2. **Re-read the two hot paths** —
   `net.minestom.server.network.socket.Server` (accept loop, `configureSocket`)
   and `net.minestom.server.network.packet.PacketReading`
   (`readPacket` / `readFramedPacket`) — line numbers **will** drift; confirm
   the length caps, the decompression bound, and the accept-loop's continued
   absence (or new presence) of a connection hook.
3. **Re-run the pre-auth idle/slow-loris confirmation** against a **local
   loopback** minecom on the new build (procedure and repro-shaped detail in
   the uncommitted note, never committed): does a silent post-TCP-handshake
   connection get reaped, and does the [§4.2](#42-p0--v2-pre-authlogin-completion-deadline)
   deadline still fire? This is the direct re-check of the launch-prep DoS
   class against "whatever Minestom version minecom ships on."
4. **Watch the #3179 registry/packet rework** (MASTERPLAN §6): it reworks the
   packet surface and will move or rename much of the above. Treat the bump
   that lands #3179 as a **full re-audit**, not a checklist pass.

**Disclosure hygiene.** This document contains **defenses and specs only**.
Any specific attack sequence, timing, or confirmation harness derived while
hardening lives in an **uncommitted** local note outside the repo
(`~/minecom-security-notes.txt`), flagged for private upstream disclosure per
`docs/SECURITY.md`. Never commit exploit reproduction to this repository.
