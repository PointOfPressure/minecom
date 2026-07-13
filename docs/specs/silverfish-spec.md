# Silverfish + Infested Blocks — Implementation Spec (Minecraft 26.1.2)

All facts below are cited to decompiled server classes under `vanilla-src/net/minecraft/...`
(vineflower-decompiled from `~/versions/26.1.2/server-26.1.2.jar`, unobfuscated).
Cached sources used:

- `net/minecraft/world/entity/monster/Silverfish.java` (was already cached)
- `net/minecraft/world/level/block/InfestedBlock.java` (was already cached)
- `net/minecraft/world/level/block/InfestedRotatedPillarBlock.java` (newly decompiled/cached)
- `net/minecraft/world/entity/EntityType.java` (newly decompiled/cached, `SILVERFISH` entry)
- `net/minecraft/world/level/block/Blocks.java` (newly decompiled/cached, infested block registrations + host block `strength()`)
- `net/minecraft/world/entity/monster/Monster.java`, `net/minecraft/world/entity/Mob.java`,
  `net/minecraft/world/entity/LivingEntity.java` (attribute defaults, already cached)
- `net/minecraft/world/entity/ai/goal/Goal.java`, `GoalSelector.java` (tick-delay semantics, already cached)
- `net/minecraft/world/entity/Mob.java#serverAiStep` (goal tick cadence, already cached)
- `net/minecraft/world/effect/InfestedMobEffect.java`, `MobEffects.java` (decompiled for the unrelated-but-adjacent
  "Infested" status effect; see §6, out of primary scope but flagged since it also spawns Silverfish)
- Data pack JSON extracted directly from the jar (loot tables, tags) — not decompiled Java, but authoritative:
  `data/minecraft/loot_table/blocks/infested_*.json`, `data/minecraft/loot_table/entities/silverfish.json`,
  `data/minecraft/tags/damage_type/always_triggers_silverfish.json`,
  `data/minecraft/tags/entity_type/immune_to_infested.json`,
  `data/minecraft/tags/enchantment/prevents_infested_spawns.json`

---

## 1. Silverfish entity

### 1.1 Attributes — `Silverfish.createAttributes()`

```
Monster.createMonsterAttributes()          // = Mob.createMobAttributes().add(ATTACK_DAMAGE)  [engine default value]
        .add(MAX_HEALTH, 8.0)
        .add(MOVEMENT_SPEED, 0.25)
        .add(ATTACK_DAMAGE, 1.0)
```

Resolved values:

| Attribute | Value | Source |
|---|---|---|
| Max health | **8.0** (4 hearts) | `Silverfish.createAttributes()` |
| Movement speed | **0.25** | `Silverfish.createAttributes()` |
| Attack damage | **1.0** (0.5 heart, melee) | `Silverfish.createAttributes()` |
| Follow range | **16.0** (engine default, not overridden) | `Mob.createMobAttributes()` → `.add(Attributes.FOLLOW_RANGE, 16.0)` |
| Knockback resistance, armor, armor toughness, etc. | engine attribute-registry defaults (not overridden by Silverfish) | `LivingEntity.createLivingAttributes()` |

**Candidate for documented simplification:** only MAX_HEALTH / MOVEMENT_SPEED / ATTACK_DAMAGE / FOLLOW_RANGE are
behaviorally load-bearing for a faithful port; the rest (scale, step height, gravity, safe-fall-distance, etc.)
can be left at Minestom/engine defaults with an AUDIT.md note, since Silverfish never touches them.

### 1.2 Dimensions — `EntityType.SILVERFISH` registration

```java
EntityType.Builder.of(Silverfish::new, MobCategory.MONSTER)
   .sized(0.4F, 0.3F)
   .eyeHeight(0.13F)
   .passengerAttachments(0.2375F)
   .clientTrackingRange(8)
   .notInPeaceful()
```

- Bounding box: **width 0.4, height 0.3** blocks.
- Eye height: **0.13**.
- `notInPeaceful()` — governs the vanilla *natural spawn cycle* mob-category filtering only; it does **not**
  gate the "triggered" spawns described in §3.2/§1.4/§6 below, which call `EntityType.SILVERFISH.create(level,
  EntitySpawnReason.TRIGGERED)` directly and bypass spawn-rule checks entirely (see §5 edge case).
- `clientTrackingRange(8)` is a network sync-distance detail, not behavior — simplification candidate (use
  Minestom's default entity view distance).

### 1.3 Goals — `Silverfish.registerGoals()`

| Priority | Goal | Notes |
|---|---|---|
| 1 | `FloatGoal` | swim/float in liquid |
| 1 | `ClimbOnTopOfPowderSnowGoal` | |
| 3 | `SilverfishWakeUpFriendsGoal` | see §1.4 |
| 4 | `MeleeAttackGoal(speedModifier=1.0, followEvenIfNotSeen=false)` | |
| 5 | `SilverfishMergeWithStoneGoal` | see §2 |

Target selector:

| Priority | Goal |
|---|---|
| 1 | `HurtByTargetGoal().setAlertOthers()` — retaliate against attacker, and (engine-generic alerting) notify nearby same-type mobs |
| 2 | `NearestAttackableTargetGoal(Player.class, mustSee=true)` |

Lower goal-selector priority number = higher precedence (goal system evaluates in ascending priority order and
stops adding once a flag-conflicting higher-priority goal is already running — standard vanilla `GoalSelector`
semantics, not Silverfish-specific).

`getWalkTargetValue`: a candidate walk target position is scored **10.0** (an attractive score, drawing the
Silverfish toward it during idle wandering AI) if the block **below** it is `InfestedBlock.isCompatibleHostBlock`
(i.e. a plain, unconverted host block such as stone/cobblestone/stone-bricks/deepslate variants) — `Silverfish
.getWalkTargetValue`. This nudges Silverfish to loiter near infestable stone, priming them to use the merge goal.

Sound events (`Silverfish.getAmbientSound/getHurtSound/getDeathSound/playStepSound`):
`SILVERFISH_AMBIENT`, `SILVERFISH_HURT`, `SILVERFISH_DEATH`, `SILVERFISH_STEP` (step sound volume 0.15, pitch 1.0).
Body rotation is slaved to yaw every tick (`yBodyRot = yRot`) — Silverfish never turns its body independently
of its head, unlike most mobs — `Silverfish.tick()` / `setYBodyRot()`.

### 1.4 Hurt behavior — call for reinforcements

`Silverfish.hurtServer(ServerLevel, DamageSource, float)`:

```
if isInvulnerableTo(source): return false
if (source.getEntity() != null OR source.is(DamageTypeTags.ALWAYS_TRIGGERS_SILVERFISH))
      AND friendsGoal != null:
   friendsGoal.notifyHurt()
return super.hurtServer(...)     // normal damage application
```

- **Trigger condition**: damage from any source that has an owning entity (melee, projectile, explosion caused
  by an entity, etc.) **or** any damage type tagged `minecraft:always_triggers_silverfish`. That tag currently
  contains only **`minecraft:magic`** (the "magic"/instant-damage-potion damage type) —
  `data/minecraft/tags/damage_type/always_triggers_silverfish.json`. Damage with neither an entity source nor
  that tag (fall damage, drowning, starvation, non-entity fire/lava, cactus, etc.) does **not** call friends.
- `notifyHurt()` (`Silverfish.SilverfishWakeUpFriendsGoal.notifyHurt`):
  ```
  if lookForFriends == 0:
     lookForFriends = adjustedTickDelay(20)
  ```
  Only arms the countdown if it isn't already counting down (repeated hits while already counting don't reset
  or stack the timer).

**Tick timing**: `adjustedTickDelay(20)` = `requiresUpdateEveryTick() ? 20 : reducedTickDelay(20)`. This goal
does not override `requiresUpdateEveryTick()` (defaults to `false`), so it resolves to
`reducedTickDelay(20) = ceil(20/2) = 10` **goal-ticks** — `Goal.adjustedTickDelay/reducedTickDelay`. Per
`Mob.serverAiStep`, a non-every-tick goal's `tick()` is only invoked on ticks where
`(tickCount + entityId) % 2 == 0` (i.e. every other real tick, phase offset by entity ID to spread load across
entities) — `Mob.serverAiStep`. So **10 goal-ticks ≈ 20 real game ticks ≈ 1.0 second** of real time before the
scan fires. **This is the well-known "Silverfish call friends ~1 second after being hit" delay.**

**Scan volume and iteration order** (`SilverfishWakeUpFriendsGoal.tick()`, runs once when `lookForFriends`
counts down to ≤ 0, from the Silverfish's own `blockPosition()` as origin):

```
for yOff in expanding-from-0 order over [-5, 5]:      // 0,1,-1,2,-2,...,5,-5  (11 values)
  for xOff in expanding-from-0 order over [-10, 10]:  // 0,1,-1,2,-2,...,10,-10 (21 values)
    for zOff in expanding-from-0 order over [-10, 10]: // 0,1,-1,2,-2,...,10,-10 (21 values)
      pos = silverfishPos + (xOff, yOff, zOff)
      if level.getBlockState(pos).getBlock() instanceof InfestedBlock:
         if MOB_GRIEFING gamerule:
            level.destroyBlock(pos, true, silverfish)
         else:
            level.setBlock(pos, infestedBlock.hostStateByInfested(state), flags=3)
         if random.nextBoolean():   // 50% chance per infested block found
            return                  // stop scanning entirely
```

- **Search box**: 21×11×21 = 4851 candidate cells (X:±10, Y:±5, Z:±10), centered on the *hurt* Silverfish, not
  on the location it was hit. Note the asymmetric radius: **vertical range is ±5, horizontal is ±10** —
  a common point of error when porting ("radius" is not uniform).
- **Iteration order is per-axis independently expanding outward from offset 0**, y outermost, then x, then z
  innermost (`0,1,-1,2,-2,...`). It is *not* a Euclidean-distance sort — cells are visited in row-major
  (y,x,z) nested-loop order, each axis individually oscillating outward. Faithfully porting the *exact* order
  only matters if you want bit-identical behavior with `random.nextBoolean()` early-exit; for gameplay purposes
  a simpler "collect all infested blocks in box, shuffle, iterate" is a reasonable **documented simplification**
  since the 50%-stop makes exact order mostly imperceptible to players, but note it changes *which* blocks get
  woken in dense infestations.
- **Effect per found infested block, when `mobGriefing` is true**: `level.destroyBlock(pos, true, silverfish)`.
  Tracing `Level.destroyBlock(pos, dropResources=true, breaker, updateLimit)` →
  `Block.dropResources(state, level, pos, blockEntity, breaker, ItemStack.EMPTY)` →
  `state.spawnAfterBreak(serverLevel, pos, ItemStack.EMPTY, true)`. Since the "tool" used is `ItemStack.EMPTY`,
  the block's loot table's `match_tool` silk-touch condition (see §3.2) always **fails** ⇒ **no item drops**,
  but `InfestedBlock.spawnAfterBreak` still fires with an empty (non-silk-touch) tool ⇒ **a brand-new Silverfish
  is spawned** at that position (subject to the `BLOCK_DROPS` gamerule check inside `spawnAfterBreak`, which is
  virtually always true) — `InfestedBlock.spawnAfterBreak` / `Block.dropResources(4-arg overload)`. **This is a
  load-bearing, non-obvious detail: "waking friends" doesn't reveal pre-existing hidden Silverfish — it
  destroys the infested block and spawns a fresh Silverfish via the same code path as a player mining it.**
- **Effect when `mobGriefing` is false**: no block destruction, no new entity — the goal just silently reverts
  the block to its host state in place (`InfestedBlock.hostStateByInfested`, update flags `3` = notify clients +
  neighbors) — effectively a "fizzle" so the mechanic can't grief blocks with griefing disabled, but the
  infested block is still consumed/lost (converted to host) without ever spawning a bug.
- Loop stops scanning further cells the first time a found infested block's `random.nextBoolean()` roll is
  `true` (~50%); otherwise continues scanning remaining cells in order. Because of the early-return, at most
  the scan can process anywhere from 1 up to all matching cells in the volume in a single goal-tick — it is not
  bounded to "wake exactly 1 friend"; it's a geometric-ish distribution (P(exactly k blocks affected before stop)
  = 0.5^k for k found, roughly), constrained by however many `InfestedBlock`s actually exist in the box.
- Difficulty gating: **none** — this mechanic runs identically on Easy/Normal/Hard (and even, per code, would run
  on Peaceful if a Silverfish existed there — see §5 edge case on `notInPeaceful()` not applying to
  triggered/goal-driven spawns).

---

## 2. Merge-with-stone goal — `Silverfish.SilverfishMergeWithStoneGoal`

Extends `RandomStrollGoal(silverfish, speedModifier=1.0, interval=10)`, flags `Goal.Flag.MOVE`.

### `canUse()`

```
if mob.getTarget() != null: return false          // won't hide while it has a combat target
if !mob.getNavigation().isDone(): return false     // won't hide mid-path
random = mob.getRandom()
if MOB_GRIEFING gamerule AND random.nextInt(reducedTickDelay(10)) == 0:
    // reducedTickDelay(10) = ceil(10/2) = 5  → 1-in-5 chance per eligible canUse() evaluation
    selectedDirection = Direction.getRandom(random)   // one of the 6 axis directions, uniform
    pos = floor(mob.x, mob.y + 0.5, mob.z) offset by selectedDirection
    blockState = level.getBlockState(pos)
    if InfestedBlock.isCompatibleHostBlock(blockState):
        doMerge = true
        return true
doMerge = false
return super.canUse()   // fall back to normal RandomStrollGoal random-wander logic
```

- **Roll rate**: `reducedTickDelay(10) = 5` (this goal *does* implicitly run through the same non-every-tick
  cadence as other goals — see §1.4 — so `canUse()` itself is only re-evaluated roughly every other game tick per
  the `GoalSelector`/`Mob.serverAiStep` cadence when not already running). Each such evaluation has a **1-in-5
  (20%) chance** to consider merging, gated additionally by `mobGriefing`.
- Candidate block sampled: **only the single block adjacent to the Silverfish's current position** in one
  uniformly random cardinal/vertical direction (`Direction.getRandom`, 6 choices, sampled from
  `BlockPos.containing(x, y+0.5, z)` — note the **+0.5 Y offset**, i.e. it samples from the block at the
  Silverfish's *body center* height, not its feet).
- If that block is not a compatible host, this evaluation falls through to ordinary `RandomStrollGoal` wandering
  behavior for this tick (does not retry other directions/positions).
- **Compatible host blocks** (`InfestedBlock.isCompatibleHostBlock`, keyed off the static
  `BLOCK_BY_HOST_BLOCK` identity map populated by every `InfestedBlock`/`InfestedRotatedPillarBlock` constructor
  — see §3.1 for the full host↔infested table): Stone, Cobblestone, Stone Bricks, Mossy Stone Bricks, Cracked
  Stone Bricks, Chiseled Stone Bricks, Deepslate.

### `canContinueToUse()`

```
return doMerge ? false : super.canContinueToUse()
```
If merging, the goal is a one-shot action (never "continues" — all the work happens synchronously in `start()`
below); otherwise falls back to `RandomStrollGoal`'s normal continuation logic for wandering.

### `start()`

```
if !doMerge:
    super.start()   // begin normal random-stroll movement
else:
    pos = same block computed in canUse() (re-fetched from selectedDirection, not cached)
    blockState = level.getBlockState(pos)
    if InfestedBlock.isCompatibleHostBlock(blockState):   // re-validated; block may have changed since canUse()
        level.setBlock(pos, InfestedBlock.infestedStateByHost(blockState), updateFlags=3)
        mob.spawnAnim()   // client-facing "poof" spawn particle/animation
        mob.discard()     // the Silverfish entity is removed (not killed — no death event/drops/XP)
```

- **No random roll at merge time** beyond the 1-in-5 gate already applied in `canUse()` — once triggered, the
  merge is guaranteed to occur (subject to the re-validation check, which can fail if the block changed in the
  interim, in which case the goal simply does nothing this activation — the Silverfish is *not* removed and
  nothing is placed).
- **Block-state property copy**: `InfestedBlock.infestedStateByHost` copies every matching `Property<?>` from the
  host state onto the new infested block's default state (`InfestedBlock.getNewStateWithProperties` /
  `copyProperty`), so e.g. a rotated Deepslate pillar's `axis` property is preserved when it becomes
  `infested_deepslate`. Results are cached in an identity map (`HOST_TO_INFESTED_STATES`) for reuse.
- **When can this goal run**: priority 5 (lowest precedence among Silverfish's goals), and only reached if no
  higher-priority goal (float, climb powder snow, wake-friends, melee attack) is currently occupying the `MOVE`
  flag. In particular a Silverfish actively fighting or already counting down `lookForFriends` won't merge.
- Difficulty gating: **none** directly, but `mobGriefing` gamerule is required for the merge roll to even be
  attempted (`canUse()`).

---

## 3. `InfestedBlock`

### 3.1 Host ↔ infested variant table

From `Blocks.java` registrations (constructor `InfestedBlock(hostBlock, properties)` populates the static
`BLOCK_BY_HOST_BLOCK` identity map used by both `isCompatibleHostBlock` and the merge goal):

| Host block | Host `strength()` (hardness, resistance) | Infested block | Infested class | Infested `destroyTime` (hardness/2) | Infested explosion resistance |
|---|---|---|---|---|---|
| `STONE` | 1.5, 6.0 | `INFESTED_STONE` | `InfestedBlock` | **0.75** | **0.75** (constant, not host-derived) |
| `COBBLESTONE` | 2.0, 6.0 | `INFESTED_COBBLESTONE` | `InfestedBlock` | **1.0** | **0.75** |
| `STONE_BRICKS` | 1.5, 6.0 | `INFESTED_STONE_BRICKS` | `InfestedBlock` | **0.75** | **0.75** |
| `MOSSY_STONE_BRICKS` | 1.5, 6.0 | `INFESTED_MOSSY_STONE_BRICKS` | `InfestedBlock` | **0.75** | **0.75** |
| `CRACKED_STONE_BRICKS` | 1.5, 6.0 | `INFESTED_CRACKED_STONE_BRICKS` | `InfestedBlock` | **0.75** | **0.75** |
| `CHISELED_STONE_BRICKS` | 1.5, 6.0 | `INFESTED_CHISELED_STONE_BRICKS` | `InfestedBlock` | **0.75** | **0.75** |
| `DEEPSLATE` | 3.0, 6.0 | `INFESTED_DEEPSLATE` | `InfestedRotatedPillarBlock` (extends `InfestedBlock`, adds `axis` property + rotation/placement support) | **1.5** | **0.75** |

Source: `Blocks.java` (`INFESTED_STONE`, `INFESTED_COBBLESTONE`, `INFESTED_STONE_BRICKS`,
`INFESTED_MOSSY_STONE_BRICKS`, `INFESTED_CRACKED_STONE_BRICKS`, `INFESTED_CHISELED_STONE_BRICKS`,
`INFESTED_DEEPSLATE` registrations) + `InfestedBlock` constructor (`destroyTime(hostBlock.defaultDestroyTime()
/ 2.0F).explosionResistance(0.75F)`) + host block `strength(hardness, resistance)` calls in `Blocks.java`.

- Every infested block's mining speed is **exactly half the host's hardness** regardless of host, and every
  infested block has a **flat 0.75 explosion resistance**, overriding whatever the host's own resistance was
  (host resistance of 6.0 is discarded — infested blocks are much easier to blow up than their host, e.g. more
  fragile than TNT-resistant stone).
- `InfestedRotatedPillarBlock` (deepslate only) additionally copies the `RotatedPillarBlock.AXIS` property on
  placement/rotation, defaulting to `Axis.Y`.
- No infested variants exist for granite/diorite/andesite/their polished forms, deepslate bricks/tiles, sandstone,
  or any other stone-like block — the table above is exhaustive for 26.1.2.

### 3.2 Breaking an infested block

`InfestedBlock.spawnAfterBreak(state, level, pos, tool, dropExperience)`:

```
super.spawnAfterBreak(...)   // normal Block XP-drop logic (see below)
if BLOCK_DROPS gamerule AND !tool.hasTag(EnchantmentTags.PREVENTS_INFESTED_SPAWNS):
    spawnInfestation(level, pos)
```

`spawnInfestation`:
```
silverfish = EntityType.SILVERFISH.create(level, EntitySpawnReason.TRIGGERED)
if silverfish != null:
    silverfish.snapTo(pos.x+0.5, pos.y, pos.z+0.5, yaw=0, pitch=0)
    level.addFreshEntity(silverfish)
    silverfish.spawnAnim()
```

- **Spawn count**: exactly **1** Silverfish per block break (or per `destroyBlock` call — see §1.4 reinforcement
  path, which reuses this same code).
- **Spawn position**: block center horizontally (`+0.5, +0.5`), **at the block's Y floor** (not +0.5), facing
  yaw 0 (north), pitch 0.
- **Gating**:
  - `BLOCK_DROPS` gamerule must be true (default true). If false, breaking any block (infested or not) drops
    nothing *and* suppresses the Silverfish spawn too, since it's gated by the same flag.
  - **Silk Touch exception**: any tool whose enchantments carry the `minecraft:prevents_infested_spawns` tag
    prevents the spawn. That tag currently contains only **`minecraft:silk_touch`** —
    `data/minecraft/tags/enchantment/prevents_infested_spawns.json`. So mining with Silk Touch **never** spawns
    a Silverfish.
  - No difficulty check, no distance-to-player check, no light check — breaking (or `destroyBlock`-ing) an
    infested block spawns a Silverfish unconditionally as long as the two gates above pass, **even on Peaceful**
    (see §5).
- **Drops**: per `data/minecraft/loot_table/blocks/infested_*.json`, the loot table has a **single pool gated by
  a `match_tool` condition requiring Silk Touch (level ≥ 1)**. Its one entry drops **the host block item**
  (e.g. `infested_stone` → drops `minecraft:stone`), never the infested block itself. **Without Silk Touch, the
  pool's condition fails and the table has no fallback pool ⇒ zero item drops.** So:
  - Silk Touch: drop 1 host-block item, **no** Silverfish spawn (mutually exclusive with the spawn gate above —
    Silk Touch always both prevents the spawn *and* is required for any drop at all).
  - Non-Silk-Touch tool (or bare hand): **no item drop**, Silverfish spawns.
  - This means normal mining of an infested block is a pure loss unless you specifically use Silk Touch, in
    which case you get the host block but never trigger the ambush — a well-known vanilla design intent (Silk
    Touch is the "safe" way to relocate infested blocks, e.g. into an item frame display, without releasing the
    bug).
- **XP**: `super.spawnAfterBreak` (base `Block.spawnAfterBreak`) handles the generic "drop XP orbs" path used by
  ore-like blocks; `InfestedBlock` does not override or provide any XP value of its own, and — unlike ores —
  `dropExperience` is not set to award XP for these blocks in their block properties, so **breaking an infested
  block does not award experience**. (Not independently re-verified against `Block.spawnAfterBreak`'s exact XP
  computation in this pass — flagged as **unverified**: the claim rests on infested blocks having no configured
  XP range, consistent with them not being XP-bearing ore blocks, but the base-class XP mechanism itself was not
  traced in this session.)
- **Explosion / piston breaks**: `InfestedBlock` does not override `onExplosionHit`/`wasExploded`/piston-related
  hooks, so it uses vanilla generic `Block` behavior — explosions destroying an infested block go through the
  same `Block.dropResources`/`spawnAfterBreak` path with `ItemStack.EMPTY` as the tool (no Silk Touch possible
  from an explosion) ⇒ **explosions breaking infested blocks also spawn Silverfish**, with no item drop. Pistons
  pushing an infested block simply relocate the whole block state (silverfish inside is not "released") since
  piston movement doesn't call `spawnAfterBreak` at all. **Not independently traced in this session — flagged as
  a reasonable inference from `InfestedBlock` having no overrides, not a directly-cited fact.**

### 3.3 No "spawnedBySilverfish" flag

Searched `InfestedBlock.java` and `Silverfish.java` in full: there is **no persistent NBT/data flag** analogous
to "spawned by X" markers (compare e.g. traded-villager or bred-animal flags elsewhere in the codebase). The
only state carried is the block-state identity itself (host vs. infested block type + copied properties like
`axis`) and the identity-map caches in `InfestedBlock` (`BLOCK_BY_HOST_BLOCK`, `HOST_TO_INFESTED_STATES`,
`INFESTED_TO_HOST_STATES`), which are static, per-JVM lookup tables (not per-world persisted state) — pure
memoization of the codec/property-copy logic, safe to reconstruct identically in a Minestom port as a static
init-time table rather than a cache-on-demand map (**documented simplification candidate**: pre-populate the
7-entry table eagerly instead of porting the lazy `computeIfAbsent` memoization, since the entry set is fixed
and small).

---

## 4. Difficulty interactions

- **Natural spawning**: `Silverfish.checkSilverfishSpawnRules` requires `checkAnyLightMonsterSpawnRules` (which
  itself requires `level.getDifficulty() != Difficulty.PEACEFUL`, plus generic light/spawn-rule checks) —
  `Monster.checkAnyLightMonsterSpawnRules`. **Exception for spawner-driven spawns**: if
  `EntitySpawnReason.isSpawner(spawnReason)` is true, the function returns `true` immediately, skipping even the
  peaceful-difficulty check — i.e. a monster spawner (or trial spawner) can spawn a Silverfish even on Peaceful.
  For non-spawner natural spawns, an additional rule applies: the spawn is rejected if any player is within 5.0
  blocks (`level.getNearestPlayer(..., 5.0, true)` must be null) — this exists so Silverfish don't pop into
  existence right next to a player during ambient world spawning.
- **All other spawn paths in this spec** (§1.4 reinforcement destroy, §2 merge — inverse direction (block→mob is
  not a spawn, it's the reverse: entity discarded, block placed), §3.2 block-break spawn, §6 Infested-effect
  spawn) use `EntityType.SILVERFISH.create(level, EntitySpawnReason.TRIGGERED)` directly, which does **not**
  invoke `checkSilverfishSpawnRules` at all — see §5.
- **Merge goal and wake-friends goal** are both gated by the `mobGriefing` gamerule (not a difficulty setting),
  as noted in §1.4/§2. No `Difficulty` (Peaceful/Easy/Normal/Hard) branch exists anywhere in `Silverfish.java` or
  `InfestedBlock.java` except the natural-spawn check above.
- Hard-mode does not change Silverfish attack damage, spawn rate multipliers, or the reinforcement mechanic in
  this code (no `Difficulty`-keyed branching found in either class).

## 5. Edge case: triggered spawns bypass Peaceful-difficulty gating

Because §1.4 (reinforcement), §3.2 (block break), and §6 (Infested status effect) all call
`EntityType.SILVERFISH.create(level, EntitySpawnReason.TRIGGERED)` + `level.addFreshEntity(...)` directly rather
than going through the natural-spawn-rule pipeline that checks `checkSilverfishSpawnRules`/`notInPeaceful()`,
**a Silverfish can be spawned this way even when the world difficulty is Peaceful**, e.g. breaking an infested
stone block on Peaceful still spawns a hostile Silverfish. This is a genuine, verifiable-from-code vanilla
quirk (not a bug in this analysis) and should be preserved faithfully in a port unless deliberately simplified
(if simplified, document it in AUDIT.md — suppressing hostile spawns entirely on Peaceful is themost likely
"expected" behavior for players, so silently diverging from vanilla here is worth flagging either way).

## 6. Adjacent mechanic (out of primary scope): the "Infested" status effect — `InfestedMobEffect`

Not a block/entity mechanic but shares the Silverfish-spawn code path and is worth flagging since it's easy to
conflate with infested blocks. `MobEffects.INFESTED` = `new InfestedMobEffect(HARMFUL, color=0x8C9C0C,
chanceToSpawn=0.1F, spawnedCount = randomBetweenInclusive(1, 2))` — `MobEffects.java`.

`InfestedMobEffect.onMobHurt(level, mob, amplifier, source, damage)`:
```
if mob.random.nextFloat() <= 0.1:                       // 10% chance per hurt event, NOT per tick
    count = randomBetweenInclusive(random, 1, 2)         // 1 or 2, uniform
    repeat count times: spawn a Silverfish at (mob.x, mob.y + mob.bbHeight/2, mob.z)
```
Spawned Silverfish get a randomized yaw, zero pitch, and an initial velocity derived from the *victim* mob's
look direction (scaled 0.3 horizontally / 0.45 vertically, randomly yawed ±90° around that look direction) —
a "burst outward from the victim" launch, plus a hurt-sound cue (`SILVERFISH_HURT`) instead of the usual
spawn-poof animation. This effect is amplifier-independent (the `amplifier` parameter is accepted but unused in
the spawn-chance/count math) — **flagged as unverified whether higher amplifiers change anything elsewhere**
(e.g. duration/particle only) since only `onMobHurt` was inspected. This effect is not applied by anything in
the classes read this session (likely sourced from Trial Chamber ominous-vault mobs, e.g. Bogged/certain
raid mobs) — its trigger source was **not traced** and is out of scope for this spec; documented here only so a
future "why did a Silverfish spawn with no infested block nearby" investigation isn't mistaken for a bug.

---

## 7. Summary of constants (quick reference)

| Constant | Value | Source |
|---|---|---|
| Silverfish max health | 8.0 | `Silverfish.createAttributes` |
| Silverfish movement speed | 0.25 | `Silverfish.createAttributes` |
| Silverfish attack damage | 1.0 | `Silverfish.createAttributes` |
| Silverfish follow range | 16.0 (engine default) | `Mob.createMobAttributes` |
| Silverfish hitbox | 0.4 × 0.3 | `EntityType.SILVERFISH` |
| Silverfish eye height | 0.13 | `EntityType.SILVERFISH` |
| Reinforcement delay | `adjustedTickDelay(20)` → 10 goal-ticks ≈ 20 game ticks ≈ 1.0 s | `SilverfishWakeUpFriendsGoal.notifyHurt/adjustedTickDelay` |
| Reinforcement scan box | X:±10, Y:±5, Z:±10 (21×11×21) | `SilverfishWakeUpFriendsGoal.tick` |
| Reinforcement per-find stop chance | 50% (`random.nextBoolean()`) | same |
| Always-triggers-silverfish damage tag | `{minecraft:magic}` | `tags/damage_type/always_triggers_silverfish.json` |
| Merge-goal roll gate | `reducedTickDelay(10)` = 5 → 1-in-5 (20%) per eligible check | `SilverfishMergeWithStoneGoal.canUse` |
| Merge-goal direction sample | 1 of 6, uniform, from body-center (+0.5 Y) | same |
| Infested destroy time | host hardness ÷ 2 | `InfestedBlock` ctor |
| Infested explosion resistance | 0.75 (constant, all variants) | `InfestedBlock` ctor |
| Infested block break: spawn count | exactly 1 Silverfish | `InfestedBlock.spawnInfestation` |
| Infested block break: spawn-prevention tag | `{minecraft:silk_touch}` | `tags/enchantment/prevents_infested_spawns.json` |
| Infested loot: silk touch required for drop | yes, drops host item only | `loot_table/blocks/infested_*.json` |
| Infested-status-effect spawn chance | 10% per hurt event | `MobEffects.INFESTED` |
| Infested-status-effect spawn count | 1–2 (uniform) | `MobEffects.INFESTED` |

## 8. Unverified / not traced this session

- Exact XP award (or lack thereof) from `Block.spawnAfterBreak`'s base implementation for infested blocks (§3.2).
- Piston and explosion interaction with `InfestedBlock` was inferred from the absence of overrides, not directly
  traced through `PistonBaseBlock`/explosion code (§3.2).
- The `HurtByTargetGoal().setAlertOthers()` exact alert radius/mechanism (generic engine goal, not Silverfish-
  specific — not read this session; only its presence in `registerGoals()` was confirmed).
- Where `MobEffects.INFESTED` is actually applied in-game (§6) — flagged as adjacent context only, not verified.
- `RandomStrollGoal`'s own `canUse()`/movement-selection internals (the `super.canUse()`/`super.start()` fallback
  paths used by `SilverfishMergeWithStoneGoal` when not merging) were not read; only the constructor signature
  (`speedModifier=1.0, interval=10`) is cited.
