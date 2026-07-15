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

## Status as of 2026-07-15 (v0.24.0)

**Blocked: bot-driven scenarios.** `scripts/bench/rust-mc-bot` (vendored
stress-test bot) connects and completes the login→play handshake, but every
connection is silently dropped by the server ~25-30s later — see
`docs/HANDOFF.md`'s 2026-07-15 escalation entry for the full investigation
(one real bug found and fixed en route: a stale Play-state packet-ID table;
a second, deeper issue — suspected compressed-packet framing or an mio
re-arm edge case in the vendored bot — remains open). Consequence:

- **(a) spawn** and **(b) spread10k** cannot currently produce trustworthy
  numbers. Running them does exactly what MASTERPLAN §4 requires of the
  harness itself: `run_scenario.py`'s sanity gate (`bench_common.
  wait_for_players`) requires `players_online` to reach the bot target and
  *hold*, and fails loudly — a `status: "failed"` result JSON with a clear
  `failure_reason`, nonzero exit — rather than reporting numbers from a
  half-populated or emptied server. That failing-loudly behavior is itself
  verified working, which is the harness-level check MASTERPLAN §4 asks for
  ("a bot swarm that connects 0 bots must fail loudly").
- **(c) redstone** and **(d) mobfarm** ran with `bots = 0` (server-only
  load — the world setup itself, redstone clocks / mob pen, doesn't need
  bots; only the spec's "modest player presence" variant does). Real
  numbers below.
- **(e) chunkgen** needs no bots and is unaffected.

**Live-scenario baselines**: `--server vanilla`/`paper` are wired for
`chunkgen` only this session (uses the same console-driven technique as
`scripts/vanilla_oracle.py`'s existing region-diff/piston harnesses). A live
vanilla/Paper baseline (spawn/spread/redstone/mobfarm) needs either a
console-based `players_online` probe (vanilla/Paper have no `/metrics`) or,
for redstone/mobfarm, a `BenchSetup.java`-equivalent world-setup mechanism —
neither built yet. Tracked as P0 follow-up, not silently skipped.

**Paper**: a 26.2 build exists (PaperMC's `fill` API, build 60, downloaded
and smoke-tested launching cleanly) — used for the chunkgen baseline below.

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

minecom only (see Status above for why vanilla/Paper aren't in this row yet):

| p50 MSPT | p95 MSPT | p99 MSPT | TPS held | ticks observed |
|---:|---:|---:|---:|---:|
| 0.24ms | 1.53ms | 2.65ms | 20.0 | 3000/3000 expected |

Full 20 TPS held throughout with MSPT nowhere near the 50ms/tick budget —
400 redstone clocks alone are cheap on this engine. A useful low-end
sanity number for later comparison once P1 lands.

### (d) mobfarm — 150 penned zombies, 150s, 0 bots

| p50 MSPT | p95 MSPT | p99 MSPT | TPS held | ticks observed |
|---:|---:|---:|---:|---:|
| 0.96ms | 2.40ms | 3.71ms | 20.0 | 3005/3000 expected |

~4x the p50 MSPT of the redstone scenario at similar entity/contraption
count — mob AI/pathfinding is measurably more expensive per-tick than
redstone propagation here, matching intuition (and now measured instead of
assumed). Still comfortably inside the 20 TPS budget on this laptop with
zero players connected.

### (a) spawn, (b) spread10k — BLOCKED, and correctly failing loudly

Both ran and both failed loudly, exactly as MASTERPLAN §4 requires of the
harness itself ("a bot swarm that connects 0 bots must fail loudly, not
report 20 TPS on an empty server"):

- **spawn** (ramp mode, target 100 bots): the first 20-bot batch joined and
  held its 10s stability check, but by the time the ramp tried to verify
  the *next* batch (cumulative 40), the first batch had already silently
  dropped (docs/HANDOFF.md's rust-mc-bot escalation entry) — the harness
  correctly refused to report ramp/TPS numbers and exited nonzero with a
  clear `failure_reason`, no orphaned server or bot processes left behind.
- **spread10k**: the full 10,000-chunk (100x100) pregen isn't attempted
  this session — at this laptop's ~0.5 chunks/sec it would take on the
  order of hours before a single bot even attempts to connect, which
  wouldn't survive the known bug anyway. The pregen→spread-teleport
  pipeline itself was smoke-tested at a much smaller scale (radius=4) to
  confirm it doesn't have its *own* bug hiding behind the bot blocker —
  it doesn't: pregen completed, the server came up with the spread
  listener active, bots joined and held briefly, then hit the same known
  drop. That smoke result wasn't kept (not a real answer to the scenario's
  question), but the finding — "the pipeline is sound, only the bot swarm
  is broken" — is.

Re-run both once `docs/HANDOFF.md`'s rust-mc-bot connection-drop entry is
resolved; no other change to this harness should be needed.

### Not yet run: Threadripper headline numbers

Per MASTERPLAN §11.1, this laptop is the low-end/regression datapoint by
design. The owner's Threadripper tower is the intended high-core-count
counterpart (and the natural testbed for P2's region-multi-core arc) —
it's not reachable from this sandbox, so it isn't in this document. Same
one command, different hardware, when that happens.
