# Bubble Columns — Implementation Spec (vanilla 26.1.2)

Sources (all decompiled from `~/versions/26.1.2/server-26.1.2.jar`, cached under
`vanilla-src/net/...`):

- `net.minecraft.world.level.block.BubbleColumnBlock`
- `net.minecraft.world.entity.Entity` (`onAboveBubbleColumn`, `onInsideBubbleColumn`,
  `checkInsideBlocks`, `applyEffectsFromBlocks`)
- `net.minecraft.world.level.block.state.BlockBehaviour` /
  `BlockBehaviour.BlockStateBase` (entityInside dispatch)
- `net.minecraft.world.level.material.FlowingFluid`, `WaterFluid` (tick delay)
- `net.minecraft.world.entity.vehicle.boat.AbstractBoat`, `Boat`
- Data: `data/minecraft/tags/block/enables_bubble_column_drag_down.json`,
  `enables_bubble_column_push_up.json`,
  `data/minecraft/tags/fluid/bubble_column_can_occupy.json`
  (extracted from the same server jar)

---

## 1. Block: `BubbleColumnBlock`

### 1.1 State

- Single boolean property `DRAG_DOWN` (`BlockStateProperties.DRAG`).
  Default state: `DRAG_DOWN = true`.
  `DRAG_DOWN = true` → column drags entities **down** (magma block below).
  `DRAG_DOWN = false` → column pushes entities **up** (soul sand below).
- `getFluidState()` always returns `Fluids.WATER.getSource(false)` — a bubble
  column tile behaves as source water for fluid-adjacency/flow purposes even
  though its own block class is `BubbleColumnBlock`, not `LiquidBlock`.
- `getShape()` returns `Shapes.empty()` (no collision — entities pass through
  freely) and `getRenderShape()` is `INVISIBLE` (renders as plain water
  client-side; the "column" is a fluid-state + particle/sound effect layered
  on ordinary water, not a distinct visible block).
- Registered tags consumed (data-driven, not hardcoded in the class):
  - `BlockTags.ENABLES_BUBBLE_COLUMN_PUSH_UP` = `{minecraft:soul_sand}`
  - `BlockTags.ENABLES_BUBBLE_COLUMN_DRAG_DOWN` = `{minecraft:magma_block}`
  - `FluidTags.BUBBLE_COLUMN_CAN_OCCUPY` = `{minecraft:water}` (source water only,
    see §1.3)

### 1.2 Constants

| Constant | Value | Source |
|---|---|---|
| `CHECK_PERIOD` | 5 | `BubbleColumnBlock.CHECK_PERIOD` (declared but only used indirectly — the literal `5` is what's actually passed to `scheduleTick`, see §1.5) |
| Water fluid tick delay | 5 | `WaterFluid.getTickDelay(LevelReader)` → `return 5;` |
| Minimum fluid amount to be "occupiable" | 8 | `BubbleColumnBlock.canOccupy` — `occupyFluid.getAmount() >= 8` (i.e. only a full/source water block, since source water reports amount 8) |

### 1.3 Placement / column growth: `updateColumn`

Static method, called both from block `tick()` and from the fluid tick
scheduled by `updateShape` (see §1.5). Two overloads; the 4-arg one reads the
block's own current state first.

```
updateColumn(bubbleColumnBlock, level, occupyAt, belowState):
    updateColumn(bubbleColumnBlock, level, occupyAt, level.getBlockState(occupyAt), belowState)

updateColumn(bubbleColumnBlock, level, occupyAt, occupyState, belowState):
    if not canOccupy(bubbleColumnBlock, occupyState): return
    columnState = getColumnState(bubbleColumnBlock, belowState, occupyState)
    level.setBlock(occupyAt, columnState, flags=2)      # flag 2 = send to clients, no neighbor-shape update
    pos = occupyAt.above()
    while canOccupy(bubbleColumnBlock, level.getBlockState(pos)):
        if not level.setBlock(pos, columnState, flags=2):
            return                                       # setBlock returned false -> stop (should not normally happen)
        pos = pos.above()
    # loop naturally stops at the first non-occupiable block (air, solid, non-source water, etc.)
```

`canOccupy(bubbleColumnBlock, occupyState)`:
```
if occupyState.is(bubbleColumnBlock): return true
fluid = occupyState.getFluidState()
return fluid.is(FluidTags.BUBBLE_COLUMN_CAN_OCCUPY)     # water tag
   and occupyState.getBlock() instanceof LiquidBlock     # must be a plain liquid block (not e.g. kelp-flooded etc. — actually any LiquidBlock waterlogged-carrier still counts if fluid state matches)
   and fluid.isSource()                                  # FLOWING water does NOT qualify — source only
   and fluid.getAmount() >= 8
```
→ **Only source water propagates a bubble column.** Flowing/partial water
blocks stop the column from extending further, and existing bubble-column
blocks always themselves count as occupiable (so the column can keep
"climbing" through its own prior blocks when re-evaluated).

`getColumnState(bubbleColumnBlock, belowState, occupyState)` decides what to
write into each cell as the column grows, based on the block **directly
below** the cell being written (this is why growth is driven by the block
below the current position rather than the source itself; it lets an
existing drag/push direction propagate upward one story at a time):
```
if belowState.is(bubbleColumnBlock):                         return belowState              # inherit direction from block below
elif belowState.is(ENABLES_BUBBLE_COLUMN_PUSH_UP):  # soul sand
     return bubbleColumnBlock.defaultBlockState().with(DRAG_DOWN=false)
elif belowState.is(ENABLES_BUBBLE_COLUMN_DRAG_DOWN):  # magma block
     return bubbleColumnBlock.defaultBlockState().with(DRAG_DOWN=true)
else:
     # below is neither bubble-column, soul sand, nor magma: this cell should
     # NOT be (or remain) a bubble column
     return occupyState.is(bubbleColumnBlock) ? Blocks.WATER.defaultState() : occupyState
```

**Net effect / algorithm in plain terms:**
1. Soul sand or magma block placed underwater (or water placed directly above
   soul sand/magma) schedules a block tick (see §1.5) which converts the
   water cell immediately above into a bubble-column block with the
   direction determined by the block below (soul sand → push-up/`DRAG_DOWN
   =false`; magma → drag-down/`DRAG_DOWN=true`).
2. `updateColumn` then walks straight up (`Direction.UP`), converting every
   further source-water cell into the same-direction bubble column, stopping
   at the first cell that is not occupiable (air, non-source water, solid
   block, etc.) — this reproduces the "one continuous column from the
   soul-sand/magma up to the water surface" behavior in a single call, i.e.
   growth is not literally "one block per tick" — the whole visible column
   above a freshly-placed source updates in one `updateColumn` invocation.
   New growth after any change is re-triggered by the scheduled ticks below,
   so the *visible* appearance of the column can still look like it grows in
   stages when a player places water blocks one at a time — that staged
   growth is a consequence of players adding one water block at a time
   (each addition schedules its own tick), not an intrinsic per-tick block
   cap in the algorithm itself.

### 1.4 Decay path (column removal)

There is no explicit "decay" method — removal is driven entirely by
`updateColumn` being re-run:
- If the soul-sand/magma block is removed/replaced, `updateShape` fires for
  the water/bubble-column cell above (direction `UP`, see §1.5) and
  reschedules a block tick. On that tick, `getColumnState` finds `belowState`
  is no longer bubble-column/soul-sand/magma, so the column cell reverts:
  `occupyState.is(bubbleColumnBlock) ? Blocks.WATER.defaultBlockState() :
  occupyState` — i.e. a bubble-column cell decays to plain source water.
  Because `updateColumn` still walks upward while `canOccupy` holds, the
  reversion propagates up the whole column in one tick, converting every
  bubble-column cell above back to plain water (the loop's `columnState` is
  computed once at the bottom using the *now-non-magma/soul-sand* below
  block, so every cell in the column gets overwritten with `Blocks.WATER`).
- If the water source itself is removed/replaced by something that fails
  `canOccupy` (e.g. drained, replaced with a solid block, becomes flowing
  water), the loop in `updateColumn` simply stops at that point when
  re-triggered; cells above an interrupted column are **not** automatically
  cleaned up by that single call — but removing/changing a block also fires
  `updateShape` on its neighbors, which reschedules ticks for the cells
  immediately above/below the change, cascading the correction outward.
- `canSurvive`: a bubble-column block can only survive if the block **below**
  is itself a bubble column, soul sand, or magma block
  (`BlockTags.ENABLES_BUBBLE_COLUMN_PUSH_UP` / `..._DRAG_DOWN`). If
  `canSurvive` fails after a shape update, vanilla's generic
  `updateShape`/neighbor-check machinery (not shown in this class — inherited
  `Block` behavior) will eventually remove/replace the block; the bubble
  column code itself doesn't call `canSurvive` directly except via
  `updateShape`'s early condition (see §1.5) which force-schedules a tick
  when survival fails, that tick then runs `updateColumn`, which (because
  `belowState` no longer qualifies) rewrites the cell back to plain water.
- Bucket removal: `BubbleColumnBlock implements BucketPickup`.
  `pickupBlock(user, level, pos, state)` sets the block to air with flags
  `11` and returns `new ItemStack(Items.WATER_BUCKET)` — picking up a bubble
  column cell with a bucket yields a water bucket and leaves air (does not
  itself trigger the decay/propagation logic above; the column above will be
  fixed up by its own scheduled ticks once its `belowState` no longer
  matches).

### 1.5 Neighbor-update / scheduling triggers: `updateShape`

Called by the generic block-update pipeline whenever a neighbor of a bubble
column cell changes.
```
updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random):
    ticks.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))   # always: 5-tick fluid tick, unconditional
    if not state.canSurvive(level, pos)
       or directionToNeighbour == DOWN
       or (directionToNeighbour == UP and not neighbourState.is(this) and canOccupy(this, neighbourState)):
        ticks.scheduleTick(pos, this, 5)                                      # extra 5-tick BLOCK tick
    return super.updateShape(...)
```
- **Every** neighbor update on a bubble-column cell schedules a plain fluid
  tick 5 ticks later (this is the normal water self-tick, inherited fluid
  behavior — flows/spreads/etc., not bubble-specific).
- An **additional** self (block) tick 5 ticks later is scheduled when any of:
  - the cell can no longer survive (block below no longer qualifies) — this
    is the trigger that starts the decay-to-water conversion described in
    §1.4, or
  - the neighbor that changed is the block **below** this cell (any change
    below always re-checks/re-derives direction), or
  - the neighbor that changed is the block **above** this cell, that neighbor
    is not itself this bubble-column block, and that neighbor now qualifies
    as `canOccupy` (i.e. new source water appeared directly above — this is
    the trigger that lets a column grow upward as the player raises the
    water level, or as adjacent water flows in and becomes a source block).
- **Placement flow, concretely:** placing water directly above soul
  sand/magma, or placing soul sand/magma directly under existing source
  water, both fire `updateShape` on the water cell (from the `UP`/`DOWN`
  direction respectively), scheduling the 5-tick block tick that converts it
  via `updateColumn` (§1.3).

### 1.6 Block tick

```
tick(state, level, pos, random):
    updateColumn(this, level, pos, state, level.getBlockState(pos.below()))
```
Runs 5 ticks after being scheduled (per §1.5) — every direct state-derivation
and column-growth/decay step in this feature runs on a **5-game-tick (0.25s
at 20 TPS)** delay from the triggering neighbor change, not instantaneously.

### 1.7 Client-visual effects (candidates for simplification)

`animateTick(state, level, pos, random)` — purely client-side particles/
sounds, safe to simplify or approximate on a Minestom server (server doesn't
need to replicate exact particle spawn timing, only send equivalent packets
if desired):
- `DRAG_DOWN = true`: spawns `ParticleTypes.CURRENT_DOWN` every call at
  `(x+0.5, y+0.8, z)`, zero velocity. 1-in-200 chance
  (`random.nextInt(200)==0`) per call to play
  `SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT` at volume
  `0.2 + rand()*0.2`, pitch `0.9 + rand()*0.15`.
- `DRAG_DOWN = false`: spawns **two** `ParticleTypes.BUBBLE_COLUMN_UP`
  particles — one at `(x+0.5, y, z+0.5)` with velocity `(0, 0.04, 0)`, one at
  a random point within the block with the same velocity. Same 1-in-200
  chance to play `SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT` with identical
  volume/pitch formula.
- These particle/sound calls originate from the client-tick-driven
  `animateTick` (called per-block per-random-tick-ish client animation pass,
  not the server's scheduled `tick`/`CHECK_PERIOD` mechanism) — **not**
  required for correct server-authoritative physics; recommended
  simplification: server need only apply the entity velocity effects
  (§2) and, optionally, send generic bubble particles for visual parity.

---

## 2. Entity interaction

### 2.1 Dispatch chain (who calls what, and when)

Per entity tick, movement triggers `Entity.applyEffectsFromBlocks()` →
`checkInsideBlocks(from, to, ...)` (`Entity.java`), which sweeps every block
the entity's bounding box intersected between its previous and new position
(`BlockGetter.forEachBlockIntersectedBetween`). For each intersected
non-air block:
```
isPrecise = movedFar || deflatedBoundingBoxAtTarget.intersects(blockIntersection)
state.entityInside(level, blockIntersection, entity, effectCollector, isPrecise)
```
`movedFar` is true when `from.distanceToSqr(to) > 0.9999900000002526²`
(i.e. moved ~1 block or more this step) — in that case every intersected
block is treated as "precise" regardless of final-position overlap, so fast
movers still get exact bubble-column handling instead of being skipped.

`BlockBehaviour.BlockStateBase.entityInside(...)` simply forwards to
`getBlock().entityInside(state, level, pos, entity, effectApplier, isPrecise)`,
which for `BubbleColumnBlock` is:
```
entityInside(state, level, pos, entity, effectApplier, isPrecise):
    if not isPrecise: return                      # imprecise sweep-through: no effect applied this call
    stateAbove = level.getBlockState(pos.above())
    nothingAbove = stateAbove.getCollisionShape(level, pos).isEmpty()
                   and stateAbove.getFluidState().isEmpty()
    if nothingAbove:
        entity.onAboveBubbleColumn(state.DRAG_DOWN, pos)   # entity is at/near the TOP of the column, open air above
    else:
        entity.onInsideBubbleColumn(state.DRAG_DOWN)        # entity is inside the column, more column/blocks above
```
So exactly one of the two entity callbacks fires per intersected
bubble-column block per tick, chosen by whether the block directly above the
column cell the entity is standing in has both empty collision shape AND
empty fluid state (i.e. is open air, not more water/column and not a solid
block) — this is what "at the surface" means mechanically, not the entity's
own Y relative to any global column top.

Because a swept AABB can intersect several bubble-column cells in one tick
(fast vertical movement), an entity could in principle receive multiple
calls in one tick — each call independently clamps velocity as below, so
the **net** effect of overlapping calls in the same tick is bounded by the
per-call clamp values, not additive beyond them (see §2.2 clamp semantics —
`Math.max`/`Math.min` against the *current* delta each call, so repeated
same-directions calls in one tick don't stack past the same ceiling/floor a
single call would produce, though the underlying value can still ratchet
toward it slightly on each intermediate call).

### 2.2 `Entity.onInsideBubbleColumn(dragDown)` — inside the column

```
handleOnInsideBubbleColumn(entity, dragDown):
    movement = entity.getDeltaMovement()
    if dragDown: yd = max(-0.3, movement.y - 0.03)
    else:        yd = min(0.7,  movement.y + 0.06)
    entity.setDeltaMovement(movement.x, yd, movement.z)
    entity.resetFallDistance()          # fallDistance = 0.0, regardless of direction
```
- Drag-down: Y velocity decreases by `0.03`/tick, floored at `-0.3` blocks/tick.
- Push-up: Y velocity increases by `0.06`/tick, capped at `0.7` blocks/tick.
- Always resets fall distance (prevents fall damage while inside a column,
  in both directions).

### 2.3 `Entity.onAboveBubbleColumn(dragDown, pos)` — at the open-air surface

```
handleOnAboveBubbleColumn(entity, dragDown, pos):
    movement = entity.getDeltaMovement()
    if dragDown: yd = max(-0.9, movement.y - 0.03)
    else:        yd = min(1.8,  movement.y + 0.1)
    entity.setDeltaMovement(movement.x, yd, movement.z)
    sendBubbleColumnParticles(entity.level, pos)
```
- Drag-down: same per-tick decrement (`0.03`) as inside, but a much larger
  floor (`-0.9` vs `-0.3`) — lets entities accelerate downward faster once
  they're being pulled under from the surface.
- Push-up: larger increment (`0.1` vs `0.06`) and much larger ceiling (`1.8`
  vs `0.7`) — this is the "launch out of the water" mechanic (soul sand
  columns can shoot the player well above the surface).
- **Does not** call `resetFallDistance()` here — only the "inside" variant
  does. (An entity launched out via a push-up column at the surface can
  therefore still take fall damage from the ensuing fall, whereas being
  dragged/pushed while still submerged resets fall distance every tick.)
- Fires `sendBubbleColumnParticles`: server-side, only when `level instanceof
  ServerLevel` — spawns, twice per call: one `ParticleTypes.SPLASH` and one
  `ParticleTypes.BUBBLE` at `(pos.x + rand[0,1), pos.y+1, pos.z + rand[0,1))`,
  count `1`, speed `1.0` (SPLASH) / delta `(0, 0.01, 0)` speed `0.2` (BUBBLE).
  **Simplification candidate** — purely cosmetic, safe to approximate/omit
  particle parity as long as the velocity clamp behavior above is exact.

### 2.4 Applicability to non-living entities (items, projectiles, boats)

- `checkInsideBlocks`/`entityInside` dispatch is on the base `Entity` class
  and is not gated to `LivingEntity` — **items, arrows/projectiles, boats,
  and all other entity types** go through the same `entityInside` →
  `onInsideBubbleColumn`/`onAboveBubbleColumn` path and receive the same
  velocity clamps by default (Entity is the declaring/overridable class for
  both methods — see `Entity.java` lines ~2803 and ~2835). `AbstractBoat`
  overrides `onAboveBubbleColumn` (not `onInsideBubbleColumn`) to add
  boat-specific visual/eject behavior on top of (not instead of) calling
  into the base velocity-clamp logic — **verify**: `AbstractBoat` does NOT
  call `super.onAboveBubbleColumn(...)` in the decompiled body shown (only
  sets `isAboveBubbleColumn`/`bubbleColumnDirectionIsDown`/`bubbleTime` and
  plays a splash sound) — so for boats, the base Y-velocity clamp does
  **not** run on `onAboveBubbleColumn`; boat vertical motion instead comes
  entirely from `tickBubbleColumn()` below. Boats still receive the
  ordinary `onInsideBubbleColumn` base-class behavior unmodified (no boat
  override exists for it), so a boat that's fully submerged mid-column still
  gets the §2.2 Y-clamp exactly as any other entity.

---

## 3. `AbstractBoat` bubble-column mechanic (sink/launch/wobble)

Source: `net.minecraft.world.entity.vehicle.boat.AbstractBoat`. `Boat`
(`net.minecraft.world.entity.vehicle.boat.Boat`) adds no bubble-specific
overrides itself.

### 3.1 Constants / fields

| Name | Value | Notes |
|---|---|---|
| `BUBBLE_TIME` | 60 (ticks = 3s) | Set into synced data `DATA_ID_BUBBLE_TIME` when a boat first enters "above bubble column" state |
| `DATA_ID_BUBBLE_TIME` | synced `int` entity-data field | Replicated to clients for wobble animation |
| `isAboveBubbleColumn` | boolean | server-local, not synced |
| `bubbleColumnDirectionIsDown` | boolean | server-local, mirrors `DRAG_DOWN` from the last `onAboveBubbleColumn` call |
| `bubbleMultiplier` | float, clamped `[0,1]` | client-only, eases the wobble in/out |
| `bubbleAngle` / `bubbleAngleO` | float | client-only, current/previous-tick wobble angle for interpolated rendering |

### 3.2 `onAboveBubbleColumn(dragDown, pos)` override

```
onAboveBubbleColumn(dragDown, pos):
    if level is ServerLevel:
        isAboveBubbleColumn = true
        bubbleColumnDirectionIsDown = dragDown
        if getBubbleTime() == 0:
            setBubbleTime(60)
    if not isUnderWater() and random.nextInt(100) == 0:
        play swim-splash sound at 1.0 volume, pitch 0.8 + 0.4*rand()
```
Note: does **not** invoke the base `Entity.onAboveBubbleColumn` clamp logic
or `sendBubbleColumnParticles` — those are entirely bypassed for boats;
boat vertical motion is driven solely by `tickBubbleColumn()` (below), which
runs unconditionally every server tick as part of `AbstractBoat.tick()`.

### 3.3 `tickBubbleColumn()` — called every tick from `AbstractBoat.tick()`

Client branch (visual-only, wobble):
```
if clientBubbleTime > 0: bubbleMultiplier += 0.05
else:                    bubbleMultiplier -= 0.1
bubbleMultiplier = clamp(bubbleMultiplier, 0, 1)
bubbleAngleO = bubbleAngle
bubbleAngle = 10.0 * sin(0.5 * tickCount) * bubbleMultiplier     # degrees, ±10° max amplitude
```

Server branch (authoritative sink/launch):
```
if not isAboveBubbleColumn: setBubbleTime(0)
bubbleTime = getBubbleTime()
if bubbleTime > 0:
    setBubbleTime(bubbleTime - 1)
    diff = 60 - bubbleTime - 1
    if diff > 0 and bubbleTime == 0:            # i.e. this is the tick where the counter would hit 0
        setBubbleTime(0)
        if bubbleColumnDirectionIsDown:
            deltaMovement.y += -0.7
            ejectPassengers()
        else:
            deltaMovement.y = hasPlayerPassenger ? 2.7 : 0.6
    isAboveBubbleColumn = false
```
**Effective behavior**: a boat sitting above a bubble column gets its
`bubbleTime` counter (re)armed to 60 every tick it's detected above the
column (via `onAboveBubbleColumn`, called from movement-based `entityInside`
same as any entity, but consumed only for the counter/flag here). The
counter only ever counts down to exactly 0 (a single terminal event) — the
moment it reaches 0 *while the boat has been continuously above the column
this whole 60-tick window* (`isAboveBubbleColumn` true every tick, else the
counter is force-reset to 0 and the whole 60-tick timer restarts from
scratch next time the boat re-enters), the boat:
  - Drag-down (magma) column: instantaneous `-0.7` Y-velocity impulse, and
    **ejects all passengers** — this is the vanilla "boat capsizes over a
    magma-driven downdraft" mechanic.
  - Push-up (soul sand) column: Y-velocity is **hard-set** (not additive) to
    `2.7` if any passenger is a `Player`, else `0.6` — a stronger launch when
    a player is riding.
- So the sink/launch impulse is a delayed, one-shot event **3 seconds
  (60 ticks) after continuously being detected above the bubble column**, not
  an immediate per-tick effect like the base entity clamp. If the boat drifts
  off the column before the 60 ticks elapse, the timer resets to 0 and must
  restart from 60 the next time it's re-detected above a column.
- Passengers (including players) riding the boat do **not** independently
  receive the base-entity bubble clamp while seated, because the boat itself
  is the entity whose bounding box intersects the block — passengers are
  positioned relative to the boat and aren't separately swept by
  `checkInsideBlocks` for blocks the boat is standing in (ordinary vanilla
  passenger-position semantics, not bubble-specific — noted for completeness,
  **not independently re-verified in this pass**, flag as **unverified**).

### 3.4 Items / projectiles

Per §2.4, any `Entity` subtype (dropped items, arrows/tridents/other
projectiles, minecarts, etc.) that has no override for
`onInsideBubbleColumn`/`onAboveBubbleColumn` receives the plain
`Entity`-class velocity clamps unmodified — items and projectiles **are**
affected by bubble columns using exactly the §2.2/§2.3 formulas (no
special-casing found for `ItemEntity` or projectile classes in the classes
inspected for this spec; **not exhaustively verified** — only `Entity` and
`AbstractBoat` were inspected for overrides in this pass, since those are
the only two classes referencing bubble columns anywhere in the decompiled
tree searched).

---

## 4. Magma block damage — not entangled

Magma block contact/burn damage to entities standing on it (`MagmaBlock`
class) is a wholly separate mechanic from the bubble-column growth/velocity
logic — the only relationship is that `BlockTags.ENABLES_BUBBLE_COLUMN_DRAG_DOWN`
happens to be satisfied by `minecraft:magma_block`, which is a data-tag
membership check only. Per task scope, `MagmaBlock`'s damage-on-contact
behavior was **not decompiled/inspected** for this spec — skip.

---

## 5. Simplification candidates summary

1. **`animateTick` particles/sounds** (§1.7) — purely client-visual; server
   need not reproduce spawn-chance/position precisely. Recommended: send a
   generic ambient bubble particle effect at a fixed low rate instead of
   replicating the exact `random.nextInt(200)` gate and dual-particle
   positions.
2. **`sendBubbleColumnParticles`** (§2.3) — cosmetic SPLASH/BUBBLE particles
   on above-column ticks; safe to omit or approximate.
3. **Sweep-based `isPrecise` gating** (§2.1) — vanilla's exact-AABB /
   moved-far heuristic for deciding whether a swept-through block counts as
   "precise" is movement-engine-specific plumbing. A Minestom port doing a
   simpler "check blocks under/around the entity's current AABB every tick"
   (rather than continuous swept-shape intersection) is a reasonable
   simplification as long as it doesn't systematically miss fast-moving
   entities passing through a column in a single tick — document this
   explicitly if simplified, since it changes which tick an entity's
   velocity gets clamped on for very fast movement.
4. **Boat wobble angle/multiplier** (§3.3 client branch) — pure client
   rendering interpolation; server doesn't need it at all beyond exposing the
   `bubbleTime` synced field if visual parity with vanilla clients is
   desired.
5. **Decay propagation nuance** (§1.4, "cells above an interrupted column are
   not automatically cleaned up by a single call") — faithfully reproducing
   the exact multi-tick cascade of `updateShape`-triggered rescheduling is
   low-value complexity; a simplified "re-scan whole column top-to-bottom
   whenever the base block changes" achieves the same visible end state and
   is an acceptable, documentable simplification as long as the *final*
   state after settling matches vanilla (same set of cells revert to plain
   water, same set keep their `DRAG_DOWN` value).

## 6. Unverified / flagged items

- §3.3 passenger note: whether seated passengers ever independently get
  swept by `checkInsideBlocks` was **not independently re-verified** in this
  pass (relying on general vanilla passenger-positioning knowledge, not a
  read of the passenger/riding code in this session).
- §3.4: no exhaustive search was done across all `Entity` subclasses for
  bubble-column overrides beyond `Entity` and `AbstractBoat` — only those two
  classes reference bubble columns in the portion of the decompiled tree
  produced for this task. If parity for a specific entity type (e.g.
  `ItemEntity`, `AbstractArrow`) is required, decompile and check that class
  directly for an override before assuming the base-`Entity` formula applies
  unmodified.
- The double `this.applyEffectsFromBlocks();` call (two consecutive
  identical statements) in `AbstractBoat.tick()` (source lines ~243-244) is
  reproduced faithfully from the decompiled source and looks unusual; it was
  not further investigated for whether it's intentional (e.g. covering two
  different movement-sub-steps within `move()`) or a Mojang-side redundancy.
  Flag as **unverified whether reproducing it twice matters** for a port —
  the effect is idempotent per call within the same tick position, so a
  single call is very likely sufficient for a functionally-equivalent port.
