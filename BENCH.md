# P1 branch `p1-tick-index` — bench signature note

This branch lands MASTERPLAN §4 P1 items **1 (one tick pipeline)** and
**2 (spatial entity index)**. No benchmarks were run here by design — the A/B
bench happens centrally on an idle machine. This note tells the bench operator
exactly where to expect movement and what new instrumentation to read.

## What changed (both increments are behavior-preserving)

1. **`TickPipeline`** — the ~23 independent global `MinecraftServer` scheduler
   `repeat(tick(1))` tasks (redstone, hoppers, furnaces, beacons, brewing,
   conduits, fire spread, random ticks, natural/phantom/classic spawners, …)
   now run from **one** ordered per-tick dispatch instead of N separate
   scheduler tasks. Same scheduler-manager tick point, so nothing moves
   relative to Minestom's own instance/entity ticking; only the order among
   these systems is now fixed (vanilla-anchored) and deterministic.

2. **`EntityIndex`** — a per-chunk spatial facade over Minestom's
   `EntityTracker`. ~15 "entities near X" sites that previously did an
   instance-wide `getEntities()` O(all-entities) scan now do an O(local)
   chunk lookup (radius via `near()`, single-cell via `inChunk()`, cell-set
   via `inChunksOf()`), plus one O(1) `getEntityById` swap in TrialChambers.

## Scenarios that should show the win

- **Tick MSPT under bot load** (spawn scenario at 15 and 50 bots; the
  redstone and mobfarm bench cells). Increment 1's direct effect is fewer
  scheduler entries/wakeups per tick — expect a small but real p50/p95 MSPT
  reduction, largest where many of the consolidated systems are active at once
  (redstone contraptions + hoppers + furnaces ticking together). The primary
  deliverable here is **observability + deterministic order**, not a large
  MSPT delta.

- **Entity-heavy scenarios** (the real §P1-2 win): anything with a high live
  entity count where the converted near-X sites fire each tick —
  - mob farms / high mob density → `ClassicSpawners` spawn-cap count, `Beds`
    nearby-monster check, `Goals` alert-others, explosions in crowds;
  - item-entity-heavy redstone → `Hoppers` vacuum, weighted/normal pressure
    plates, detector rails, tripwire (all were full-instance scans);
  These go from O(entities-in-instance) to O(entities-in-local-chunks). Expect
  the gap to widen with entity count: run the mobfarm / crowded-redstone bench
  cells at increasing populations and compare MSPT slope vs `main`.

- Expected to be **flat** (no regression, no win): chunkgen (no entities),
  low-population idle scenarios.

## New timer output the bench can read

`TickPipeline` records per-system `System.nanoTime()` every tick:

- `TickPipeline.lastNanos(String systemName)` — last-tick ns for one system
  (returns -1 if unknown). System names: `redstone`, `hoppers`, `furnaces`,
  `beacons`, `conduits`, `beehives`, `campfires`, `jukebox`, `brewing`,
  `creakingHearts`, `trialChambers`, `leashing`, `minecarts`, `boats`,
  `breath`, `villagerConversion`, `archaeologyBrush`, `archaeologyResets`,
  `fireSpread`, `randomTicks`, `phantomSpawning`, `classicSpawners`,
  `naturalSpawner0`/`naturalSpawner1`/… (one per instance).
- `TickPipeline.lastTotalNanos()` — total ns across all pipeline systems on
  the last tick (a single per-tick number to trend).
- `TickPipeline.timingsReport()` — formatted per-system table in run order.
- `/tickprofile` command (op-gated) — dumps the table in-game / to a fake
  player, for spot checks during a live bench run.

The bench can sample `lastTotalNanos()` alongside the existing MSPT capture to
attribute tick time to the consolidated gameplay systems specifically (the
rest of MSPT is Minestom entity/chunk/packet work, which this branch does not
touch).

`EntityIndex` has no per-call counters; its win shows up as reduced MSPT in the
entity-heavy cells above, not as a separate metric. If per-site attribution is
wanted later, wrap `near`/`inChunk` in the same nanotime idiom.
