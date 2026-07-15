# Vendored: rust-mc-bot

Upstream: https://github.com/Eoghanmc22/rust-mc-bot
Commit vendored: `6a190c3a16607fc05b9e00c738ea6f968a7fd965` ("port to 1.21.8", 2026-04-21)

Built by the Minestom project itself for end-to-end stress testing (existing
bot implementations crashed before the server did), so it's a minimal
protocol client: connect, join, wander, occasionally chat. No online-mode
support by design — matches minecom running in offline mode.

## Changes made

1. **`src/main.rs`**: `PROTOCOL_VERSION` bumped from `772` (1.21.8) to `776`,
   matching `net.minestom.server.MinecraftConstants.PROTOCOL_VERSION` for
   minecom's pinned Minestom `2026.07.12-26.2`.
2. **`src/main.rs`**: added an optional 4th CLI arg, `name_offset` (see CLI
   reference) — lets the P0 bench harness launch several bot batches against
   one server without `Bot_0..` name collisions kicking the earlier batch.
   Not in upstream.
3. **`src/packet_processors.rs`**: the Play-state packet ID table was stale
   (built against protocol 772, not 776) — **every one of its 5 entries
   except `cookie_request` had drifted**. Re-derived from
   `net.minestom.server.network.packet.PacketVanilla`'s `SERVER_PLAY`
   registry (Minestom 26.2 sources, `~/.m2/repository/net/minestom/minestom/
   2026.07.12-26.2/minestom-2026.07.12-26.2-sources.jar`) by counting each
   packet class's position in the registry's declaration order (that
   position *is* its wire ID): `keep_alive` 0x26→0x2C, `join_game`
   0x2B→0x31, `kick`/Disconnect 0x1C→0x20, `teleport` 0x41→0x48, `transfer`
   0x7A→0x81. Login and Configuration state tables were checked the same way
   and are all still correct — only Play had drifted. This fix is real and
   necessary but **not sufficient** — see Known issue below.

## Known issue (UNRESOLVED, escalated — see HANDOFF.md 2026-07-15 entry)

The original "proof it works" note below (single short observation,
2026-07-15 morning) was misleading: it only proved the login→play
*handshake* completes. A longer soak test (single bot, `players_online`
polled from minecom's own `/metrics` every 10s) shows the connection is
**silently dropped by the server around t+25-30s** — every time, both before
and after the packet-ID fix above:

```
t+14s players_online=3   (3-bot batch)
t+24s players_online=3
t+34s players_online=0   <- gone
```

25-30s lines up exactly with Minestom's `KEEP_ALIVE_DELAY` (10s) +
`KEEP_ALIVE_KICK` (15s) — i.e. this reads as the bot never successfully
answering a server keep-alive, timing out, and getting kicked. But the bot
process itself never notices: no "Peer closed socket", no decompression
error, no `bot.kicked` print of any kind (`src/net.rs`) — it just goes
quiet. Debug-instrumenting `packet_processors::lookup_packet`'s Play-state
fallback (temporarily) showed only **5 total packets** ever reach dispatch
over a 40s connection, none repeating, none at the (now-correct) keep-alive
ID — i.e. the connection appears to go idle almost immediately after join,
well before the kick, rather than streaming normally and then getting cut
off. `net.rs::process_packet`'s read/decompress/dispatch loop reads
structurally correct (drains via `read_socket` until `WouldBlock`, which is
the right pattern for mio's edge-triggered epoll) — the stall wasn't
isolated by inspection alone.

**Not yet ruled out**: a framing bug specific to compressed Play-state
packets (`net.rs` lines ~102-153, the `bot.compression_threshold > 0`
branch) that leaves the buffer waiting for bytes that never complete a
packet under some condition this project's real chunk/entity traffic hits
but the earlier short smoke test didn't; or an mio re-arm edge case. Needs
either sustained instrumentation (print `next`/reader/writer index every
loop iteration, not just unhandled IDs) or a from-scratch trace against a
known-good client (e.g. a real Minecraft client packet capture) to
localize. Two real fix attempts were made in this session (protocol
version, then the full Play-ID re-derivation above) without resolving it —
escalating per CLAUDE.md rule 3 rather than guessing further.

**Impact**: any scenario needing a live bot swarm (a, b, and the
modest-player-presence variant of c/d) cannot currently produce trustworthy
numbers — a run will either hang waiting for `players_online` to reach the
target or (if the harness's join-verification window is long enough) appear
to join and then silently lose players mid-run. `scripts/bench/
run_scenario.py` treats this as a hard failure (players_online must hold at
the target, not just briefly touch it) rather than reporting numbers from a
half-populated server — see its docstring.

## Proof the login→play handshake itself works (2026-07-15, superseded above)

```
cargo build --release
java -jar target/minecom.jar &            # offline mode by default, confirmed in log
./target/release/rust-mc-bot 127.0.0.1:25565 5
```

Bot output — all 5 completed login→configuration→play:

```
cpus: 4
spawn bot "Bot_3" 0/1
bot "Bot_3" joined
spawn bot "Bot_4" 0/1
bot "Bot_4" joined
spawn bot "Bot_2" 0/1
bot "Bot_2" joined
spawn bot "Bot_0" 0/2
bot "Bot_0" joined
spawn bot "Bot_1" 1/2
bot "Bot_1" joined
```

This only proves the initial handshake — NOT that the connection survives
past the first keep-alive interval. See Known issue above.

## CLI reference

```
rust-mc-bot <ip:port | unix:///path> <count> [threads] [name_offset]
```

- `count` — number of bot connections to open.
- `threads` — worker thread count (defaults to `num_cpus::get()`); each
  thread runs its own mio event loop and gets a share of `count`.
- `name_offset` — vendored addition (see Changes made): bots are named
  `Bot_<name_offset..name_offset+count>` instead of always starting at 0, so
  a second invocation against the same server doesn't collide names/UUIDs
  with a still-connected first batch.
- Join pacing is a compile-time constant, not a flag:
  `AVG_JOINS_PER_TICK = 5.0` (join storm limited to ~100/s at 20 "ticks"/s in
  the bot's own scheduler) — deliberately un-configurable upstream to avoid
  a login-storm being mistaken for the *server's* limit. Left as-is.
- `SHOULD_MOVE = true` (compile-time) — bots take a local random walk near
  their spawn point and occasionally send a chat message
  (`src/main.rs` `MESSAGES`). There is no "teleport to region" or scripted
  path support — a bot only ever wanders near wherever it spawned.

**Consequence for scenario (b) (N bots spread over the pregenerated
10k-chunk world)**: rust-mc-bot itself can't be told to spread bots across a
world — the scatter has to happen server-side (e.g. a bench-only join
listener that teleports each joining bot to a random point inside the
pregenerated square when a spread env var/flag is set). Left for the
scenario-config task, not something to solve inside the vendored bot.
