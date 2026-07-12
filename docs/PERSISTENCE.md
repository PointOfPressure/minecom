# Persistence design

Status: DESIGN ACCEPTED, implementation in progress (2026-07-12).
Owner: Fable. Prereq reading: docs/COMMUNITY-INTEL.md (multi-core intel),
docs/AUDIT.md "Session-scoped block-entity/mob state".

## Problem

Blocks round-trip through Minestom's AnvilLoader today (saved on shutdown,
`Main.java`), and `Persist.java` saves a single `world/minecom_state.json`
with world extras (time/weather/difficulty), chests, ender chests, furnaces,
crops and player snapshots. Everything else is session-scoped static maps —
30+ registries across `blocks/` and `redstone/` (hoppers, crafters + locked
slots, brewing stands, campfires, composters, jukeboxes, lecterns, decorated
pots, chiseled bookshelves, trial-chamber spawner/vault state, tracked
redstone positions: daylight detectors, lightning rods, sculk sensors,
shriekers, ...), all live mobs (villager professions/inventories included),
scheduled redstone ticks, chunk inhabited time, and warden warning levels.
A restart silently wipes all of it. This is the biggest architectural gap in
the project (AUDIT.md priority list #4, upgraded: it now blocks several
otherwise-done features from being honest).

## Constraints

1. **Multi-core region ownership is coming** (COMMUNITY-INTEL.md): the
   accepted future is Folia/MCHPRS-style region-partitioned ticking, where
   each region thread owns its state and cross-thread locking is the known
   failure mode. Persistence must already be sharded so a region can save
   and load its own state without touching another region's data.
2. **Don't fork the block store.** Minestom's AnvilLoader keeps owning
   block/biome data. Verified against minestom-2026.07.01: AnvilLoader
   *loads* block entities from vanilla worlds (`loadBlockEntities` → block
   NBT) but its save path writes sections only — no block-entity save, no
   entities/*.mca support in either direction. So vanilla-Anvil fidelity on
   the write side does not exist upstream; minecom needs its own store for
   everything that isn't a block state.
3. **Static-holder registries are the codebase idiom** (CONVENTIONS.md) and
   the hot paths depend on them. The design must not force a "everything
   becomes an object graph" refactor across 30 subsystems in one pass.
4. **Verification is the product**: save→wipe→load→assert must be drivable
   from PlayTest inside one JVM, no server restart.

## Decision

A minecom-owned **region-sharded sidecar store** next to the Anvil data,
with a small adapter SPI that each stateful subsystem implements. Vanilla
`entities/*.mca` + block-entity NBT export stays a listed non-goal for now
(AUDIT.md): the sidecar is authoritative for minecom state; the Anvil
region files stay authoritative for blocks. (A vanilla-world *import* pass
can later map loaded block-entity NBT into the registries via the same
adapters — AnvilLoader already parses it for us on load.)

### Layout

```
world/
  region/                      # Minestom AnvilLoader (blocks/biomes) — untouched
  minecom_state.json           # world-level: time, weather, difficulty,
                               # ender chests, player snapshots (v0 format kept)
  minecom/
    r.<rx>.<rz>.json.gz        # per-region shard: all chunk-anchored state
                               # for the 32x32 chunks of region (rx, rz)
```

Region shards are versioned (`"v": 1`) and gzipped (JSON is the project's
existing persistence idiom; shards keep any one file small; a binary format
is a premature optimization the versioned envelope lets us adopt later
without migration pain). The world-level monolith keeps its v0 name/format —
splitting players into per-uuid files adds nothing architectural today and
is deferred until player state grows; the multi-core-critical part is the
shard layer, which is fully as designed.

### Region shard schema

```json
{ "v": 1,
  "chunks": {
    "<cx>,<cz>": {
      "inhabited": 12345,
      "be":   { "<x>,<y>,<z>": { "kind": "hopper", "data": {...} }, ... },
      "mobs": [ { "kind": "villager", "pos": [x,y,z,yaw,pitch],
                  "health": 20.0, "data": {...} }, ... ],
      "ticks": [ { "pos": "<x,y,z>", "in": 12, "action": "shrieker_reset" } ]
    }
  }
}
```

- `be` = block-entity-shaped state, keyed by block position, one entry per
  subsystem claim. `kind` routes to the adapter that owns it.
- `mobs` = entity snapshots. Mobs rebuild through `Mobs.spawn(kind, ...)` +
  adapter-applied `data` (profession, trades, food inventory, size, tags,
  equipment, breeding cooldowns). Brains/goals rebuild from scratch exactly
  like vanilla rebuilds AI on chunk load; in-flight AI state is not saved
  (vanilla saves only select memories — noted per-mob in AUDIT.md).
- `ticks` = pending scheduled actions anchored to a position, saved as
  relative delays. Only actions that are (a) position-anchored and (b)
  meaningful across a restart are saved (shrieker reset, observer pulse
  decay, pending piston finish is NOT — pistons complete instantly here).

### Adapter SPI

```java
public interface StateAdapter {
    String kind();                                   // "hopper", "crafter", ...
    // save: emit every entry this subsystem owns inside the given chunk
    void collect(Instance in, int cx, int cz, BiConsumer<Point, JsonObject> out);
    // load: restore one entry (registry put + any block-entity re-arm)
    void restore(Instance in, Point pos, JsonObject data);
}
// mobs are the same shape with Entity-level collect/restore
```

Subsystems keep their static maps and hot paths untouched; each contributes
one adapter (typically 20-40 lines) registered in `Bootstrap`. `Persist`
becomes the coordinator: walk loaded chunks → group by region → write
shards. Load side: `InstanceChunkLoadEvent` restores that chunk's entries
lazily (plus an eager pass at boot for spawn chunks), so state returns
exactly when its chunk does — same lifecycle vanilla has.

### Why this survives multi-core

The unit of save/load is the region shard, and the unit of runtime
ownership in the planned threading model is also the region. When region
threads land, each thread serializes its own shard from its own data with
zero cross-thread reads; the coordinator only gathers finished files. The
adapter SPI does not change — only who calls it. This is the property the
MCHPRS precedent demands and what a single global JSON (status quo) would
have made impossible without a stop-the-world save.

### Save triggers

Shutdown (existing hook), every 5 minutes (existing cadence), and — new —
chunk unload once view-distance chunk unloading actually happens (today the
playtest/dev instances keep chunks loaded; the hook is wired but is a no-op
in practice). Saves are atomic per file (write `.tmp`, move over).

### Migration

`minecom_state.json` keeps carrying world-level keys. Its legacy
chests/furnaces/crops sections still load (once, at boot) but are no longer
written — the next save re-anchors that state into region shards, so old
files upgrade themselves. The legacy load fallback deletes after one
release.

## Implementation status (2026-07-12, Fable)

DONE in the first pass:
- Core: `StateAdapter` SPI + `RegionStore` (gzipped versioned shards, atomic
  writes, stale-shard deletion), `Persist` as coordinator with legacy-section
  load fallback; adapters self-register from each subsystem's register().
- Adapters: chest (double-chest shared views via ref + finishRestore),
  furnace (progress + XP bank), crop positions, hopper, crafter (+locked
  slots), brewing stand (progress + fuel), dispenser/dropper inventories,
  redstone tracked positions (plates, detector rails, daylight detectors,
  tripwire hooks, lightning rods), sculk sensor/shrieker positions.
- Mob snapshots (built into RegionStore): kind + pos + health + equipment +
  villager profession/food level/8-slot inventory; restore via Mobs.spawn.
  Warden anger deliberately not saved (session-scoped, AUDIT).
- Chunk inhabited time (closes the Difficulty AUDIT note).
- Item serialization upgraded to full NBT (toItemNBT/fromItemNBT SNBT) —
  potions, enchants, names now survive; m/a/d kept as legacy fallback.
- Tests: SelfTest item/pos round-trips; PlayTest scenarioPersistence
  (save → wipe → reload → data AND behavior assertions).

REMAINING (Sonnet-sized now the SPI exists — see HANDOFF):
- Small block entities: campfire, composter, jukebox, lectern, decorated
  pot, chiseled bookshelf, bells, note blocks, shulker-box contents.
- Trial chambers (defs + runtime state) — larger, has its own session-scoped
  caveat in AUDIT.
- Position-anchored scheduled ticks (shrieker reset et al).
- Per-mob extras: sheep color/sheared, slime size (once sizes exist),
  breeding cooldowns, baby state.

## Non-goals (this pass)

- Vanilla `entities/*.mca` and block-entity NBT *export* (import comes free
  via AnvilLoader's load path + adapters; export is a compatibility feature
  for later, tracked in AUDIT.md).
- Item entities / projectiles in flight (vanilla saves them; low value,
  short-lived — AUDIT note).
- Cross-instance state beyond the three dimensions already booted.
