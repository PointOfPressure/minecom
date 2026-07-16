# P1 — Single-Thread Performance Era: Design

Written 2026-07-16 (Fable, orchestrating session fork), immediately after
v0.25.0 closed P0 with the full benchmark matrix. This is the design doc
MASTERPLAN §4 P1 calls for; implementation sessions execute against it.
Every number below is from committed evidence (`docs/BENCHMARKS.md`,
`scripts/bench/results/*.json`, `docs/HANDOFF.md` 2026-07-16 entries) or a
`file:line` reference into this tree at v0.25.0.

**P1's one-line goal**: close the chunk-pipeline gap and consolidate the
tick loop, so the matrix's already-good tick latency holds at multiples of
today's player counts — without moving a single number on the parity
suites or the region-diff baseline.

---

## 1. The evidence P1 starts from

| fact | number | source |
|---|---|---|
| chunk generation, minecom | **0.46 chunks/sec** | `chunkgen_minecom_20260715T181325Z.json` |
| chunk generation, vanilla 26.2 | 3.47 chunks/sec | `chunkgen_vanilla_20260715T181606Z.json` |
| chunk generation, Paper | 3.92 chunks/sec | `chunkgen_paper_20260715T181710Z.json` |
| ⇒ the gap | **7.5–8.5×** | BENCHMARKS.md §(e) |
| tick latency at 15 bots (spawn p99) | minecom 8.5 ms vs vanilla 20.8 / Paper 9.5 | BENCHMARKS.md matrix |
| generation runs **on the virtual-thread carrier pool** | jstack: `ForkJoinPool-1-worker-*` carrying `VNoise.Improved.noise` / `VDensity` lambdas | HANDOFF 2026-07-16 read-stall entry |
| consequence | 4 concurrent generations starve every connection read loop → mass "Timeout" kick | same entry (Recv-Q forensics) |
| tick-task debt (v0.25.0 count) | **53** `TaskSchedule.tick(1)` sites, **36** instance-wide `getEntities()` sweeps, **12** `getNearbyEntities` call sites | `grep -rn` this tree (MASTERPLAN §1.3's 38/85 was the plan-time count; it grew with Tier 1–2 parity) |
| suites / parity gate | 228 selftest + 822 playtest (deterministic), region-diff **99.3613%** full-state | v0.25.0, `scripts/worldgen_region_diff.py` |

The gap was independently confirmed three ways in one week: the chunkgen
scenario, cold-boot forceload stalls (~2 s/chunk observed), and the
read-stall kill chain. One bottleneck, three symptoms.

---

## 2. Chunk pipeline rebuild

### 2.1 Where the 7.5–8.5× actually lives — ranked hypotheses

The generator is a **bit-exact interpreter** over vanilla's JSON density
graphs (`VDensity.java:22` — `interface DF { double compute(int x, int y,
int z); }`, graph built by `parse()` from bundled worldgen JSON). Vanilla
compiles the same graphs into specialized classes with primitive per-chunk
scratch state. The interpreter is why worldgen was portable and provable in
weeks — and it is also, in three specific shapes, why it is ~8× slower:

**H1 — `Interpolated`'s per-block boxed corner cache**
(`VDensity.java:393-445`). Vanilla's `NoiseChunk.NoiseInterpolator` computes
cell-corner slices once per cell into primitive `double[]` (reusing the
shared face between adjacent cells) and does incremental lerp updates per
block. Minecom's port does, **per block** (16×16×384 = 98,304 positions per
chunk): 2–3 `ThreadLocal.get()`s (`CELL_MODE` :364, `corners` :396,
`epoch`), **eight `HashMap<Long, Double>` lookups** with boxed keys and
values (`corner()` :436-444), and the full trilerp. That is ~800k boxed
hash lookups per chunk *per interpolated node* where vanilla does ~7 fused
multiply-adds on locals. Also sustained `Long`/`Double` allocation churn →
G1 pressure (47 GC collections in one 150 s spawn run,
`spawn_minecom_20260716T153405Z.json`).

**H2 — full-graph per-block evaluations outside cell mode.** The aquifer
deliberately drops out of cell mode for its probes (`VAquifer.java:55`,
`:305` — `cellModeRaw(false)`), and `VanillaGen` calls
`finalDensity.compute(x,y,z)` per block for aquifer substance decisions
(`VanillaGen.java:325/344/358` — 4 sites). Each such call walks the entire
uninterpolated density graph through megamorphic `DF.compute` dispatch.
Vanilla's aquifer reads the *same* NoiseChunk scratch state the terrain
fill already computed.

**H3 — interpreter dispatch itself.** Every graph node is a lambda or small
class behind the `DF` interface; corner evaluations walk dozens of
megamorphic call sites (`VDensity` parse cases :276-278 and the spline/
cache node zoo). The JIT cannot inline through a data-built graph the way
it inlines vanilla's generated code. This is real but likely third —
attack it *after* H1/H2, and only as far as the profile says.

Feature placement, structures, and Anvil IO are presumed minor by
comparison — **step 0 exists to check that presumption before any code
moves.**

### 2.2 Step 0 — measure before touching anything

```
nice -n 15 java -XX:StartFlightRecording=filename=test-logs/genregions_p1_baseline.jfr,settings=profile \
    -Xmx2048M -jar target/minecom.jar --genregions 20260708 6
jfr print --events jdk.ExecutionSample test-logs/genregions_p1_baseline.jfr | \
    grep "at dev.pointofpressure" | sort | uniq -c | sort -rn | head -40
jfr print --events jdk.ObjectAllocationSample test-logs/genregions_p1_baseline.jfr | \
    grep -B2 "objectClass" | sort | uniq -c | sort -rn | head -20
```

Acceptance for the hypotheses: H1 predicts `HashMap.get`/`Long.valueOf`/
`ThreadLocal.get` dominating samples under `Interpolated.compute`; H2
predicts a visible second tower under `VAquifer`. If the profile
contradicts the ranking, the profile wins and this doc gets amended before
implementation proceeds.

### 2.3 Target architecture

**(a) Per-chunk generation context replaces ThreadLocals+HashMaps.**
Introduce a `GenContext` object created once per chunk generation, passed
down the fill path (or held in a scoped value), owning: primitive
`double[]` corner slices per interpolated node (vanilla's exact
slice-reuse shape), the cell-mode flag, and the epoch. This deletes every
`ThreadLocal.get()` and every boxed map from the hot path while keeping
node semantics — and therefore bit-exactness — identical. Region-diff must
read **exactly 99.3613%** after this lands; the change is a pure
representation swap.

**(b) Aquifer reads the shared context** (H2): substance probes consume the
already-computed cell state instead of re-walking the graph, mirroring
vanilla's NoiseChunk sharing. Same bit-exactness bar.

**(c) Generation moves OFF the carrier pool (MASTERPLAN §5.0 — mandatory).**
A dedicated `Executors.newFixedThreadPool(max(1, cores - 1))` generation
executor, wrapped so Minestom's `Generator` work is submitted to it rather
than executed on the calling virtual thread. Bounded by construction:
generation can saturate its own pool and the network read loops never
notice — the read-stall class dies architecturally, not probabilistically.
The bench harness's carrier-parallelism workaround
(`-Djdk.virtualThreadScheduler.parallelism=8`, `bench_common.py`) becomes
unnecessary and is removed the same day, so the fix is proven by the same
scenario that found the bug (spawn at 50 bots on a cold frontier — the
2026-07-16 collapse case).

**(d) Only then, H3 as far as the profile justifies**: specialize the 3–5
hottest node types (e.g. fuse `add(mul(...))` chains, precompute constant
subtrees at parse time). No graph-to-bytecode compiler unless the numbers
demand it — that is a complexity cliff and the exit criteria (§6) likely
don't need it.

### 2.4 Parity guardrails (non-negotiable, every step)

`python3 scripts/worldgen_region_diff.py` after **each** landed change —
the number must not move below 99.3613% (it may move *up* only with an
explained cause). Suites stay 228 + 822 green per CLAUDE.md rule 8. Any
step that can't hold both gets reverted, not "fixed forward," per the
flake SLO's spirit.

---

## 3. Tick-pipeline consolidation (+ the unification pass, folded in)

**Shape**: one `ServerTickEvent`-driven pipeline (`TickPipeline.java`) with
explicit phases replacing 53 independent `tick(1)` tasks; one entity
sweep per tick building typed indexes (players, hostiles, items,
projectiles, per-chunk buckets) that the 36 `getEntities()` sweeps and 12
`getNearbyEntities` sites consume instead of re-scanning. BubbleColumns'
O(cells×entities) scan (MASTERPLAN §1.2) becomes a lookup against the
per-chunk bucket.

**Phase order is a parity constraint, not a style choice.** Vanilla's tick
order (decompile `MinecraftServer.tickServer`/`ServerLevel.tick` for the
authoritative sequence) fixes which systems observe pre- vs post-move
state. Derive the phase list from that decompile and document each phase's
vanilla anchor in the pipeline's javadoc: (world border) → weather/time →
scheduled block ticks → random ticks → block events → fluids → redstone/
neighbor updates → entity ticking (AI, movement, combat) → block entities
(furnaces, spawners, hoppers) → mob spawning → chunk send/persistence
bookkeeping. Redstone's own internal queue (`Redstone.tick`) stays its own
subsystem — it slots in as one phase, unchanged internally.

**Unification rides along** (MASTERPLAN §5.1): converting a subsystem's
`buildTask(...)` registration into a pipeline phase *is* the moment its
naming/lifecycle/map idioms get converged (CONVENTIONS §11 list) and its
Bootstrap wiring gets normalized. One pass over every subsystem, not two.
PlayTest.java's by-section split (7,3xx lines → per-section files along the
scenario registry) lands first inside this era, since consolidation churns
scenario wiring anyway.

**Verification leverage**: the suites are strong on behavior but blind to
*ordering* between subsystems. Before consolidating, add a small
`scenarioTickOrder` (observer blocks + fluid + hopper + mob interaction in
one contraption whose outcome is order-sensitive — the piston-fixture
differential method applies) so the pipeline migration has an
order-sensitive canary beyond "everything still passes."

---

## 4. Perf-regression gate (§2.7 — lands with P1's first change)

The bench JSONs already carry `commit`, `hardware`, `tps`, `mspt_p50/95/99`,
`chunks_per_sec` (`run_scenario.py:base_result`). The gate:

1. `scripts/bench/compare_results.py <baseline.json> <candidate.json>`:
   fails (exit 1) on chunks_per_sec regression >10% or p99 regression >20%
   on the same scenario+hardware fingerprint; prints a one-line delta table.
2. CI job (nightly, after the playtest job): runs `chunkgen` (radius 4 —
   the cheap scenario) + `redstone` (bots=0) on the runner, compares
   against the latest committed result for that hardware fingerprint,
   fails loudly on regression. Runner hardware ≠ laptop hardware — the gate
   compares *runner-to-runner* history only; committed laptop numbers are
   never the CI baseline.
3. Full matrix re-runs stay manual (laptop or, later, the Threadripper) at
   each P1 milestone; results committed like any other evidence.

---

## 5. Sequencing (smallest risk first; suites + region-diff green after every step)

| step | change | proves |
|---|---|---|
| S0 | JFR baseline profile (§2.2), committed to test-logs/ + summarized in this doc | hypotheses vs reality |
| S1 | perf gate: `compare_results.py` + CI job (§4) | regressions can't land silently from S2 on |
| S2 | `GenContext` + primitive corner slices (H1) | region-diff bit-identical; chunkgen number moves first time |
| S3 | aquifer shares context (H2) | same bars |
| S4 | dedicated generation executor (§2.3c) + remove the carrier-parallelism workaround | 50-bot cold-frontier spawn run passes; read-stall dead |
| S5 | targeted node specialization (H3) — only as far as S0's profile justifies | chunkgen approaches target |
| S6 | PlayTest by-section split + `scenarioTickOrder` canary | consolidation prerequisites |
| S7 | TickPipeline: migrate subsystems in dependency order, unification per subsystem as each moves (§3) | 53→1 tasks, 36+12 scans→indexed lookups |
| S8 | full matrix re-run, BENCHMARKS.md updated, laptop MSPT/max-bots re-measured | P1 exit numbers |

Each step is one landable session (S7 is several, one subsystem group per
session). Any step that misses its bar reverts.

## 6. Exit criteria — P1 is done when, on this laptop:

1. **chunkgen ≥ 3.5 chunks/sec** (≥ vanilla parity, a ≥7.6× improvement);
   stretch goal 7 chunks/sec (2× vanilla) — decide whether to chase it
   only after S5's profile, not on ambition.
2. **Spawn scenario holds 20 TPS at 50 bots** including a cold chunk
   frontier (the exact 2026-07-16 collapse case), with zero read-stall
   kicks and the carrier workaround removed.
3. **No live-scenario p99 regresses** vs the v0.25.0 matrix; spawn p99
   stays ≤ 8.5 ms at 15 bots.
4. **Region-diff ≥ 99.3613%**, suites 228 + 822 green, zero new AUDIT
   simplifications introduced by perf work.
5. The perf gate is in CI and has caught (or provably would catch) a
   deliberate 15% chunkgen regression (test it once by reverting S2 in a
   branch).

## 7. Non-goals

- **No multicore / region ownership** — that is P2, and it builds on the
  tick pipeline this era creates. The generation executor here is a
  bounded worker pool, not a region-threading design.
- **No Minestom fork.** Everything above sits in minecom's own layer; if a
  Minestom-internal wall appears (e.g. chunk send path), it gets a
  HANDOFF entry and a MASTERPLAN §6 decision, not an ad-hoc fork.
- **No API/behavior breaks**: parity numbers are the contract; every perf
  change is invisible to the suites by definition of done.
- **No new benchmark scenarios mid-era** (matrix comparability): scenario
  changes happen at era boundaries, versioned in BENCHMARKS.md.
