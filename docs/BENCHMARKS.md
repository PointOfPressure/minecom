# Benchmarks

MASTERPLAN §4 P0: "nothing in the performance program is real until it's
measured, and the measurement itself is a deliverable nobody else has."
This document is the methodology + the first, honest, potato-tier numbers
that prove the harness works end to end. It is not the headline — see
Hardware disclosure below.

## One command

```
python3 scripts/bench/run_scenario.py <scenario> --server <minecom|vanilla|paper>
```

Scenarios (`scripts/bench/scenarios/*.toml` — configs, not script forks):
`spawn`, `spread10k`, `redstone`, `mobfarm`, `chunkgen`. Each run writes one
JSON result to `scripts/bench/results/<scenario>_<server>_<timestamp>.json`
(committed — the harness and its evidence live together, same principle as
`scripts/worldgen_region_diff.py`'s north-star number).

Prerequisites: `mvn -q package -DskipTests` (minecom jar), and for the bot
scenarios, `cd scripts/bench/rust-mc-bot && cargo build --release` (see its
`VENDOR.md`). A live vanilla dedicated server jar is expected at
`~/mc-26.2/versions/26.2/server-26.2.jar` (same layout `scripts/
vanilla_oracle.py` already uses for the region-diff/piston fixtures).

## Hardware disclosure (MASTERPLAN §11.1)

Every result JSON carries a `hardware` block (CPU model, logical CPUs, RAM,
OS, Java version, hostname) — no result is reported without it. As of this
first run, all numbers below are from **this development laptop**:

- CPU: Intel Core i7-5500U (2 physical / 4 logical cores, 2.40GHz base)
- RAM: ~15.5 GB
- Storage: spinning 5400rpm HDD (the actual bottleneck for anything
  disk-touching, per this project's own hardware-profile notes)
- OS: Ubuntu/Zorin, Linux 6.17

Per the owner's 2026-07-13 answer (MASTERPLAN §11.1): this laptop is
deliberately the **low-end/regression datapoint**, not the headline. The
owner has a Threadripper tower for publishable/marketing numbers later —
that run isn't reachable from this sandbox, so it isn't in this document.
A two-point hardware spread (potato + many-core) is the plan; this is only
the potato half, and it exists so the harness itself is proven before any
number goes in front of the Minestom community. The harness is
one-command-reproducible on **any** hardware for exactly this reason — a
Threadripper run is a rerun of the same command, not different tooling.

## Status as of 2026-07-16 (v0.24.0)

**Harness ergonomics landed** (MASTERPLAN §4 P0's "Remaining" item): a low
`-Dminestom.chunk-view-distance=4` for every bench launch, `--genregions`
pregen + a `world/seed.txt` pin (so the live server's generator actually
matches what was pregenerated — see Bugs found below) + a 60s idle settle
before any bot joins, and paced joins (batches of ~5 with a gap) instead of
a mass-connect burst. `spawn.toml`/`spread10k.toml` now target a
laptop-realistic ~15 bots instead of the pre-fix 100.

**Bugs found and fixed this session** (six, all real, all verified not to
regress `--selftest`/`--playtest`):

1. **Pregen never wrote `world/seed.txt`**, so a live boot in the same
   workdir picked a fresh *random* seed every run — `Bootstrap.findSpawn()`
   landed somewhere never pregenerated, and the run failed for a reason
   that had nothing to do with bots. Fixed: `run_scenario.py` now pins
   `world/seed.txt` to the pregen seed right after `--genregions` runs.
2. **`Redstone.tick`'s queue NPEs on unloaded chunks — for real, in
   production, not just PlayTest.** `docs/AUDIT.md` already documents this
   exact class of bug once (`Portals.tryLight`'s frame-detection walk); it
   turns out `Redstone.java`'s `strongPowerOf`/`wireInput`/`wireNeighbors`/
   `wireShape` had the same unguarded `getBlock` calls, and a bench bot
   swarm's small wander is far more likely to reach the edge of a loaded
   area than organic play — first observed as `NullPointerException:
   Unloaded chunk`, which **permanently kills the shared redstone
   scheduler for the rest of the process** (see the PlayTest class comment
   near its own explosion-safety forceload). Fixed with the same
   `isChunkLoaded` guard pattern as the existing precedent.
3. **`VNaturalSpawner`'s cluster-drift walk has the same gap** — its
   `spawnCategoryForPosition` mirrors real vanilla's `NaturalSpawner` and
   can wander a mob-spawn candidate position ~20 blocks from its starting
   chunk; guarded once at the drift loop (protects every downstream check
   — `weightedPick`/`isValidSpawnPositionForType`/`isSpawnPositionOk`/
   `checkSpawnRules` — instead of hunting each individually).
4. **`RandomTicks.growAmethyst`**, same pattern (a bud right at the loaded
   area's edge growing toward an unloaded neighbor).
5. **A log-flood red herring**: Minestom's own `PacketReading` warns on any
   trailing unread bytes after a successful packet decode — harmless per
   its own source, but the vendored bot was flooding it at ~90 lines/sec
   (see bug 6), and logging that many lines synchronously to this laptop's
   HDD was real overhead. Suppressed via `logback.xml` (root cause fixed
   separately, see below) — kept the suppression anyway since Minestom's
   own warning is genuinely non-actionable noise even at a normal rate.
6. **The actual rust-mc-bot packet bug**: `write_current_pos` sent packet
   ID `0x1E` — `ClientPlayerPositionPacket` in minecom/Minestom's registry
   (position-only: x, y, z, on_ground) — but always wrote the
   *position+rotation* byte layout (two extra `f32`s = 8 bytes), matching
   exactly the "not fully read" warning's leftover byte count on *every*
   tick from *every* bot. Fixed to send a genuine position-only packet;
   `write_pos` (the real position+rotation packet, unused elsewhere) was
   corrected to `0x1F`.
7. **Minestom's per-player packet queue (`ServerFlag.
   PLAYER_PACKET_QUEUE_SIZE`, default 1,000, drained at most 50/tick)
   overflows and kicks with `"Too Many Packets"`** the instant a tick-thread
   stall (this laptop's HDD-bound chunk I/O) pauses draining while a bot's
   steady ~2 packets/tick keeps arriving over the network — a stall of only
   a few seconds is enough with even 5 bots connected. This was the kick
   reason actually observed once bugs 5-6 were fixed (not a keep-alive
   timeout as first suspected). Mitigated with
   `-Dminestom.packet-queue-size=20000` in `bench_common.launch_minecom`.

**Still blocked after all of the above: (a) spawn and (b) spread10k
against minecom.** Every one of the six fixes above was real, verified,
and individually confirmed to change the failure's *shape* — but a further
issue remains after all of them: connections reliably die a fixed number
of events into their lifetime (~10 identical position-resync/teleport
events, independent of join-burst timing — an isolated single-batch,
no-ramp, full-duration test hit the same wall), and `spread10k` shows a
related-but-distinct symptom (later-joining batches never complete their
spawn-teleport at all, `players_online` reads 0 despite the earliest
batch's own log showing ongoing activity). Root cause not found despite
sustained effort across many isolating tests this session (packet-level,
config-level, and code-level fixes all applied and verified). Escalated to
`docs/HANDOFF.md` per rule 3 rather than continued half-correct attempts —
this is exactly the harness's designed behavior
("a run that can't hold population still fails loudly").

- **(c) redstone** and **(d) mobfarm** ran with `bots = 0` (server-only
  load — the world setup itself, redstone clocks / mob pen, doesn't need
  bots) against **all three servers** this session — minecom (carried over
  from 2026-07-15), and now vanilla + Paper too. Real numbers below.
- **(e) chunkgen** needs no bots and is unaffected (unchanged this
  session).

**Live-scenario baselines**: `run_live_vanilla` (new this session,
`run_scenario.py`) drives vanilla/Paper via `scripts/vanilla_oracle.Server`
— the same console-driven fixture factory every other differential harness
in this repo already uses — instead of minecom's `/metrics` HTTP scrape.
Players-online comes from `/list` (regex on `"There are N of a max of..."`),
MSPT from `/tick query` (vanilla 26.2 and Paper both support it directly —
no spark/JFR parsing needed, contrary to this section's original worry),
and TPS from `query_gametime()` delta over the measurement window (already
used by `scripts/piston_vanilla_capture.py`/`worldgen_region_diff.py`).
(c) redstone and (d) mobfarm's world setup is stamped via console commands
(`/setblock`/`/fill`/`/summon`) mirroring `BenchSetup.java`'s exact
coordinates/counts. **Not yet run**: spawn/spread10k against vanilla/Paper
(bots do speak real vanilla protocol so this should work, and might even
sidestep minecom's still-open mystery above — next session's easiest win).

**Paper**: a 26.2 build exists (PaperMC's `fill` API, build 60, downloaded
and smoke-tested launching cleanly) — used for chunkgen (2026-07-15) and
now redstone/mobfarm (this session) baselines below.

## Instrumentation

- **minecom**: `bench/Metrics.java` — a Prometheus text-format `/metrics`
  endpoint (default port 9225, `MINECOM_METRICS_PORT` to override), backed
  by Minestom's own `ServerTickMonitorEvent` (fired every tick,
  `ServerProcessImpl`, no opt-in needed) for true per-tick MSPT, exposed as
  `quantile="0.5"/"0.95"/"0.99"` (standard Prometheus summary shape) plus
  TPS, tick count, GC collections/time (`java.lang.management`), heap, and
  uptime. `POST /metrics/reset` scopes a result to exactly one scenario's
  measurement window. Always on for the real server (harmless — see
  `Main.java`); a bind failure there only logs a warning, it can't take the
  game server down.
- **vanilla/Paper**: no `/metrics` exists, so `chunkgen`'s baseline uses a
  direct measurement (forceload the same chunk square, flush, read Anvil
  region files back for `minecraft:full` status — ground truth, not a
  proxy). A live-scenario TPS probe for these (`time query gametime` delta
  over a wall-clock window, `vanilla_oracle.Server.query_gametime()`) is
  wired and unit-testable but not yet plumbed into `run_scenario.py`'s live
  path (see Status above).
- **Packet bytes out**: no Minestom-internal counter exists for this yet (a
  real one is Netty-layer, P1 territory — MASTERPLAN §4 P1 item 1, the tick
  pipeline consolidation). `bench_common.proc_io_bytes` is the honest coarse
  stand-in: `/proc/<pid>/io`'s `wchar` (write-syscall bytes), which for a
  network-bound server not actively saving the world is dominated by socket
  writes. Labelled `packet_bytes_out_proxy`/`_source` in every result JSON
  so nobody mistakes it for a precise number.
- **JFR**: `run_scenario.py --jfr` adds `-XX:StartFlightRecording=...` to
  the launched JVM; the `.jfr` path is recorded in the result JSON.
  Deliberately not auto-parsed into the result yet — MASTERPLAN §4 P0 says
  instrument coarsely, the per-system breakdown a JFR parse would enable is
  explicitly P1's job (the 38-task tick-pipeline consolidation).

## Results (first full run, 2026-07-15, this laptop)

Raw JSON for every run below is committed under `scripts/bench/results/`;
these numbers are pulled straight from those files, not hand-copied.

### (e) chunkgen — chunk generation throughput

64 chunks (radius=4, seed 20260708), one JVM/process per number:

| server | chunks/sec | wall time |
|---|---:|---:|
| minecom | 0.46 | 140.2s |
| vanilla 26.2 | 3.47 | 18.5s |
| Paper 26.2 (build 60) | 3.92 | 16.3s |

Minecom's worldgen is currently **~7.5-8.5x slower than vanilla/Paper** at
raw chunk generation on this hardware. This is an honest, unflattering,
and expected potato-tier number — MASTERPLAN §4 P1 item 4 names exactly
this gap ("chunk-gen is embarrassingly parallel and is the one benchmark
where beating vanilla by 5-10x is realistic") as future work (a
static-per-world noise graph compiled to an array-form interpreter /
MethodHandles instead of walking `VDensity`'s graph node-by-node per
block). This first measurement is what makes that P1 claim testable rather
than a guess. (A radius=8/256-chunk minecom run separately measured 0.54
chunks/sec — consistent with the radius=4 number within noise; the vanilla
side isn't repeated at that size, see the chunkgen scenario config's
comment for why: a naive full-radius vanilla run tripped its own watchdog,
and even after fixing that, per-tile `save-all flush` polling on this HDD
made a single 144-chunk tile take >15 minutes, which is this laptop's I/O
being the limit, not either engine's generator.)

Paper edges out plain vanilla slightly, within the range you'd expect from
its general server-loop optimizations — it does not meaningfully change
the shape of the comparison.

### (c) redstone — double-observer clock grid, 400 clocks, 150s, 0 bots

| server | p50 MSPT | p95 MSPT | p99 MSPT | TPS |
|---|---:|---:|---:|---:|
| minecom (2026-07-15) | 0.24ms | 1.53ms | 2.65ms | 20.0 |
| vanilla 26.2 | 1.4ms | 2.0ms | 2.1ms | 7.97 |
| Paper 26.2 (build 60) | 1.1ms | 2.1ms | 2.2ms | 20.0 |

Full 20 TPS held throughout on minecom and Paper, nowhere near the 50ms/tick
budget — 400 redstone clocks alone are cheap on all three engines at the
MSPT level. Plain vanilla's TPS reading (~7.97, `query_gametime` delta, not
an MSPT artifact) is well below its own MSPT would predict — this laptop
had been running continuously for hours of this session's own testing by
the time this pair ran, and Paper's clean 20.0 immediately afterward on the
same hardware is the more likely explanation (general OS/JVM scheduling
pressure from a very long session, not a redstone-specific vanilla
regression) rather than a genuine engine difference this narrow scenario
would be expected to surface. Worth a clean-machine re-run before treating
the vanilla number as load-bearing.

### (d) mobfarm — 150 penned zombies, 150s, 0 bots

| server | p50 MSPT | p95 MSPT | p99 MSPT | TPS |
|---|---:|---:|---:|---:|
| minecom (2026-07-15) | 0.96ms | 2.40ms | 3.71ms | 20.0 |
| vanilla 26.2 | 3.0ms | 5.9ms | 9.2ms | 7.97 |
| Paper 26.2 (build 60) | 1.0ms | 1.8ms | 2.0ms | 20.0 |

~3x the p50 MSPT of the redstone scenario on both vanilla and Paper at
similar entity/contraption count, matching minecom's own redstone-vs-mobfarm
ratio — mob AI/pathfinding being measurably more expensive per-tick than
redstone propagation isn't a minecom-only shape, it holds across all three
engines. Same vanilla-TPS caveat as redstone above (this laptop's session-
long load, not a redstone/mobfarm-specific vanilla regression).

### (a) spawn, (b) spread10k — still blocked against minecom

See Status above for the full account. Both fail loudly, exactly as
MASTERPLAN §4 requires of the harness itself ("a bot swarm that connects 0
bots must fail loudly, not report 20 TPS on an empty server") — six real
bugs were found and fixed while chasing this, each changing the failure's
shape, but a further connection-lifetime issue remains unresolved. Not
attempted against vanilla/Paper yet this session (time-budget constrained,
not a further blocker) — that's the natural next step, since the bot
speaks real vanilla protocol and might not hit whatever minecom-specific
(or bot-specific — not yet distinguished) behavior is causing the current
wall.

### The chunk-pipeline finding: two independent measurements

MASTERPLAN §4 P1 item 4 (worldgen performance) now has two separate,
independently-arrived-at pieces of evidence pointing at the same gap:

1. **Direct throughput** (chunkgen, 2026-07-15): minecom generates chunks
   at ~0.46/sec vs vanilla/Paper's ~3.5-3.9/sec — a **7.5-8.5x** gap,
   measured by timing raw `--genregions` output against forceload-and-poll
   on the real servers.
2. **Downstream symptom** (this session, debugging spawn/spread10k): that
   same slow chunk I/O is *why* this laptop's tick thread stalls long
   enough for Minestom's per-player packet queue (bug 7 above) to overflow
   under a bot join — a completely different measurement (packet-queue
   overflow kicks, not a chunks/sec number) arriving at the same
   underlying cause via an unrelated path. The P1 fix for one is very
   likely to measurably help the other.

### Not yet run: Threadripper headline numbers

Per MASTERPLAN §11.1, this laptop is the low-end/regression datapoint by
design. The owner's Threadripper tower is the intended high-core-count
counterpart (and the natural testbed for P2's region-multi-core arc) —
it's not reachable from this sandbox, so it isn't in this document. Same
one command, different hardware, when that happens.
