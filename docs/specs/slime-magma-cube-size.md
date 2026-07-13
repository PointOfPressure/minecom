# Slime / Magma Cube: SIZE mechanics and split-on-death

Vanilla version: 26.1.2 (unobfuscated server jar). All source citations refer to
classes decompiled into `vanilla-src/net/minecraft/...` via
`vanilla-src/tools/vineflower.jar` (see CLAUDE.md item 6). Class hierarchy:
`Slime extends Mob implements Enemy`, `MagmaCube extends Slime`.

Primary sources:
- `net.minecraft.world.entity.monster.Slime` (cached)
- `net.minecraft.world.entity.monster.MagmaCube` (cached)
- `net.minecraft.world.entity.monster.Monster` (cached, `createMonsterAttributes()`)
- `net.minecraft.world.entity.EntityType` (cached, entity registration/`.sized()`/`.spawnDimensionsScale()`)
- `net.minecraft.world.entity.ai.attributes.DefaultAttributes` (cached)
- `net.minecraft.world.entity.ConversionType` / `ConversionParams` / `Mob.convertTo` (decompiled fresh this session
  from `net/minecraft/world/entity/ConversionType.class`, `ConversionType$1.class`, `ConversionType$2.class`,
  `ConversionParams.class`, `Mob.class` — `Mob.java` was already cached; `ConversionType.java`/`ConversionParams.java`
  were not previously cached and have now been written to `vanilla-src/net/minecraft/world/entity/` per CLAUDE.md
  item 6, with the two anonymous per-constant `convert()` bodies folded back into proper enum-constant overrides.)
- `net.minecraft.world.DifficultyInstance` (cached, `getSpecialMultiplier()`)
- `net.minecraft.world.attribute.EnvironmentAttributes` (cached, `SURFACE_SLIME_SPAWN_CHANCE`)
- Loot tables: `data/minecraft/loot_table/entities/slime.json`, `.../magma_cube.json` (data-driven JSON extracted
  from the server jar, not Java — no `vanilla-src` cache path applies to datapack JSON)
- `data/minecraft/tags/worldgen/biome/allows_surface_slime_spawns.json`,
  `data/minecraft/worldgen/biome/swamp.json` (datapack JSON)

---

## 1. `setSize()` — per-size attribute formulas

### 1.1 Slime — `Slime.setSize(int size, boolean updateHealth)` (Slime.java:93-106)

```
actualSize = clamp(size, MIN_SIZE=1, MAX_SIZE=127)          // Mth.clamp
entityData[ID_SIZE] = actualSize
reapplyPosition(); refreshDimensions()
MAX_HEALTH.baseValue      = actualSize * actualSize
MOVEMENT_SPEED.baseValue  = 0.2 + 0.1 * actualSize
ATTACK_DAMAGE.baseValue   = actualSize
if updateHealth: setHealth(getMaxHealth())
xpReward = actualSize
```

Base attribute template before `setSize` overrides: `Slime` uses
`DefaultAttributes` entry `EntityType.SLIME -> Monster.createMonsterAttributes().build()`
(`DefaultAttributes.java:162`), where `Monster.createMonsterAttributes()` = `Mob.createMobAttributes().add(Attributes.ATTACK_DAMAGE)`
(`Monster.java:116`) and `Mob.createMobAttributes()` = `LivingEntity.createLivingAttributes().add(Attributes.FOLLOW_RANGE, 16.0)`
(`Mob.java:170-171`). `setSize` then unconditionally overwrites MAX_HEALTH/MOVEMENT_SPEED/ATTACK_DAMAGE base values
regardless of whatever the attribute template supplied, so the template's own default numeric values for those three
attributes are irrelevant for Slime — only FOLLOW_RANGE(16.0) and whatever `LivingEntity.createLivingAttributes()`
supplies (armor=0, armor toughness=0, knockback resistance=0, etc., standard living-entity defaults) survive from the
template.

Dimensions/hitbox scale: `Slime.getDefaultDimensions(Pose pose)` (Slime.java:336-338):
```
getDefaultDimensions(pose) = EntityType.SLIME.baseDimensions(pose).scale(getSize())
```
Base (unscaled, size=1) entity dimensions from registration: `.sized(0.52F, 0.52F).eyeHeight(0.325F)`
(`EntityType.java:897-905`, field `SLIME`). So at size N: width = height = 0.52 * N, eye height = 0.325 * N (via the
uniform `EntityDimensions.scale(N)` which scales width, height, and eyeHeight identically — verify eyeHeight scaling
behavior in `EntityDimensions` if exact eye-height parity matters; treated here as `EntityDimensions.scale` applying
uniformly, standard behavior confirmed for size-based Slime/MagmaCube.style entities).

XP reward per size: `xpReward = actualSize` (i.e., size 1→1xp, 2→2xp, 4→4xp), set inside `setSize` (Slime.java:105).

### 1.2 Magma Cube — `MagmaCube.setSize(int size, boolean updateHealth)` (MagmaCube.java:37-41)

```
super.setSize(size, updateHealth)     // identical MAX_HEALTH/MOVEMENT_SPEED/ATTACK_DAMAGE/xpReward formulas as Slime
ARMOR.baseValue = size * 3
```

So MagmaCube's health/speed/base-attack-damage/xp formulas are byte-for-byte identical to Slime's (inherited, not
overridden). ARMOR is the one MagmaCube-specific size-dependent attribute: `armor = 3 * size` (size 1→3, size 2→6,
size 4→12).

Base attribute template: `DefaultAttributes.java:137` maps `EntityType.MAGMA_CUBE -> MagmaCube.createAttributes().build()`,
where `MagmaCube.createAttributes()` = `Monster.createMonsterAttributes().add(Attributes.MOVEMENT_SPEED, 0.2F)`
(MagmaCube.java:27-29) — this initial MOVEMENT_SPEED(0.2) is immediately overwritten by the first `setSize` call
during spawn/load (identical formula to Slime, `0.2 + 0.1*size`), so it is effectively dead code except as the
transient value between entity construction and the first `setSize` call.

Actual attack damage for MagmaCube: `MagmaCube.getAttackDamage()` (MagmaCube.java:90-93):
```
getAttackDamage() = super.getAttackDamage() + 2.0
                   = (float)getAttributeValue(ATTACK_DAMAGE) + 2.0
                   = size + 2.0
```
Note this is a *derived getter*, not a change to the ATTACK_DAMAGE attribute's base value itself — the attribute
value used elsewhere (e.g. for attribute-modifier stacking, tooltips) stays `size`, but the actual melee damage dealt
via `dealDamage()` (inherited from Slime, Slime.java:229-237, calling `getAttackDamage()`) is `size + 2`.

Base dimensions for MagmaCube: `.sized(0.52F, 0.52F).eyeHeight(0.325F)` (`EntityType.java:669-678`, field
`MAGMA_CUBE`) — identical base dims to Slime; scaled by `getSize()` via the same inherited `Slime.getDefaultDimensions`.

### 1.3 Constants table

| Constant | Value | Source |
|---|---|---|
| `Slime.MIN_SIZE` | 1 | Slime.java:56 |
| `Slime.MAX_SIZE` | 127 | Slime.java:57 |
| `Slime.MAX_NATURAL_SIZE` | 4 | Slime.java:58 (declared, not referenced elsewhere in decompiled tree; natural spawn formula independently caps at 4 — see §2) |
| Max health formula | `size^2` | Slime.java:98 |
| Movement speed formula | `0.2 + 0.1*size` | Slime.java:99 |
| Attack damage (base attribute) | `size` | Slime.java:100 |
| Attack damage (MagmaCube effective) | `size + 2` | MagmaCube.java:92 |
| Armor (MagmaCube only) | `3*size` | MagmaCube.java:40 |
| XP reward | `size` | Slime.java:105 |
| Base hitbox (size 1) | 0.52 x 0.52, eye 0.325 | EntityType.java (SLIME: 897-905, MAGMA_CUBE: 669-678) |
| Hitbox at size N | `0.52*N` cube, eye `0.325*N` | Slime.getDefaultDimensions, Slime.java:336-338 |
| `spawnDimensionsScale` | 4.0F (both types) | EntityType.java:902/674 — governs spawn-position hitbox padding checks during natural spawn placement, not the live model scale |
| Slime FOLLOW_RANGE | 16.0 (from `Mob.createMobAttributes`) | Mob.java:171 |

---

## 2. Valid size values, natural spawn roll, spawn-egg/summon default

### 2.1 Clamp range
`setSize` clamps to `[1, 127]` (Slime.java:94, `Mth.clamp(size, 1, 127)`). Any size ≥1 is legal; there is no
"size must be power of two" enforcement at the attribute layer — that constraint only comes from how natural
spawning and split-on-death choose sizes (both only ever produce 1, 2, 4, 8, ... via `1 << n` or `size/2`
respectively). Manually setting e.g. size=3 via NBT/`setSize` is legal and produces `size^2=9` max health etc.,
just never occurs naturally.

### 2.2 Natural spawn size roll — `Slime.finalizeSpawn` (Slime.java:311-325)

```
sizeScale = random.nextInt(3)                      // 0, 1, or 2, uniform
if sizeScale < 2 and random.nextFloat() < 0.5 * difficulty.getSpecialMultiplier():
    sizeScale += 1
size = 1 << sizeScale                               // 1, 2, or 4
setSize(size, true)
```
This applies to **any** Slime `finalizeSpawn` call regardless of spawn reason (natural, spawner, mob-spawn-egg via
`SpawnEggItem`, `/summon`, command-block spawn, etc.) — `finalizeSpawn` is the universal post-construction hook the
game calls for all spawn paths that go through `Mob.finalizeSpawn`/`EntityType.spawn`, not just chunk-based natural
spawning. MagmaCube does not override `finalizeSpawn`, so it inherits this exact roll unchanged (size ∈ {1,2,4},
same distribution).

`difficulty.getSpecialMultiplier()` (`DifficultyInstance.java:35-41`):
```
effectiveDifficulty < 2.0        -> 0.0
effectiveDifficulty > 4.0        -> 1.0
else                              -> (effectiveDifficulty - 2.0) / 2.0
```
`effectiveDifficulty` is the continuous 0–6.75ish blended value computed from base Difficulty enum + world-time +
local difficulty (moon brightness) in `DifficultyInstance.calculateDifficulty` (not itself size-relevant, only
referenced for citation completeness). Net effect: on Peaceful this path is never reached (see spawn-rule gating
below, which already excludes Peaceful); on Easy/lower-end Normal, `specialMultiplier` trends toward 0 so the "grow
one size tier" bonus roll is unlikely; from Normal (effectiveDifficulty≈4-ish upward) through Hard it approaches 1.0,
i.e. up to a flat 50% chance to bump a size-1 or size-2 roll up one tier. **Overall distribution is difficulty- and
time-dependent, not a fixed table.** At `specialMultiplier=0`: P(1)=1/3, P(2)=1/3, P(4)=1/3. At
`specialMultiplier=1` (bonus chance capped at 50%): P(1)=1/3*0.5=1/6, P(2)=1/3*0.5 + 1/3*0.5=1/3, P(4)=1/3 + 1/3*0.5=1/2.

**Simplification candidate:** exact `effectiveDifficulty` computation (moon-phase/time blend) is out of scope for
this spec; a Minestom port could approximate `getSpecialMultiplier()` with a static per-Difficulty constant
(Peaceful=n/a, Easy≈0.0, Normal≈0.5, Hard≈1.0) rather than porting the full continuous local-difficulty timer, with
this simplification explicitly documented per CLAUDE.md item 4/AUDIT.md.

### 2.3 Spawn location/rule gating — `Slime.checkSlimeSpawnRules` (Slime.java:263-290)

```
if level.difficulty == PEACEFUL: return false
if spawnReason is a spawner reason (EntitySpawnReason.isSpawner):
    return checkMobSpawnRules(...)                       // standard mob spawn rule (light/space), no extra slime gating
if biome.is(BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS) and 50 < pos.y < 70:
    chance = environmentAttributes.getValue(SURFACE_SLIME_SPAWN_CHANCE, pos)   // Float, per-position environment attribute
    if random.nextFloat() < chance and maxLocalRawBrightness(pos) <= random.nextInt(8):
        return checkMobSpawnRules(...)
if level is not a WorldGenLevel: return false
chunkPos = containing(pos)
slimeChunk = WorldgenRandom.seedSlimeChunk(chunkPos.x, chunkPos.z, worldSeed, 987234911L).nextInt(10) == 0
if random.nextInt(10) == 0 and slimeChunk and pos.y < 40:
    return checkMobSpawnRules(...)
return false
```
Notes/constants:
- `BiomeTags.ALLOWS_SURFACE_SLIME_SPAWNS` = `{minecraft:swamp, minecraft:mangrove_swamp}`
  (`data/minecraft/tags/worldgen/biome/allows_surface_slime_spawns.json`).
- Y-band for the swamp/surface path: `50 < y < 70` (exclusive both ends), hardcoded in Slime.java:271.
- Light gate for surface path: `maxLocalRawBrightness(pos) <= random.nextInt(8)`, i.e. roughly biased toward darker
  spots but not a hard `<8` cutoff — it's `<= uniform(0..7)`.
- `SURFACE_SLIME_SPAWN_CHANCE` (`EnvironmentAttributes.java:144-146`): `EnvironmentAttribute<Float>`, **default value
  0.0F**, unit-float range. **UNVERIFIED / gap:** neither `data/minecraft/worldgen/biome/swamp.json` nor
  `mangrove_swamp.json`'s `"attributes"` block sets a `gameplay/surface_slime_spawn_chance` override in this jar's
  bundled datapack (checked exhaustively — only `audio/background_music`, `gameplay/increased_fire_burnout`,
  `visual/sky_color`, `visual/water_fog_color`, `visual/water_fog_end_distance` are set). No other JSON
  (`environment_attribute_modifier`-style folder does not exist in this jar's `data/minecraft/`) or decompiled Java
  in the cached tree sets this attribute to a nonzero value for swamp biomes either. This means either (a) the
  effective chance really is 0 by default and swamp "surface slime" spawning in this build relies solely on the
  slime-chunk path below (unlikely — historically swamps have a distinct moon-phase-gated surface spawn), or (b) the
  override lives in a bootstrap/datagen Java class not present in the server jar's decompiled tree (e.g. a
  moon-phase-driven `EnvironmentAttributeLayer$TimeBased` registered programmatically rather than via static JSON;
  `EnvironmentAttributeLayer$TimeBased` exists as a class but its instantiation site was not found within this
  session's search budget). **Flagged as unverified — do not assume 0.0; test in-game or decompile
  `EnvironmentAttributeLayer`/whatever registers biome attribute overrides before shipping swamp slime spawning.**
  Recommended interim simplification: hardcode a legacy-style chance (vanilla pre-1.18 used `1/100` per chunk-column
  swamp check gated additionally by moon phase — NOT the same formula as this version) or pick a conservative
  constant (e.g. `0.01`) and document it as a documented simplification in AUDIT.md pending verification.
- Slime-chunk path: `WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, worldSeed, 987234911L)` — magic seed constant
  `987234911L` is the vanilla "slime chunk" salt (Slime.java:283). `slimeChunk = seededRandom.nextInt(10) == 0`
  (10% of chunks are slime chunks, deterministic per-world-seed-and-chunk). Additional gate: `random.nextInt(10)==0`
  (10% per spawn attempt) AND `pos.y < 40`. Combined: within a slime chunk, at y<40, spawn attempts succeed 10% of
  the time (this 10% is on top of, and independent from, standard mob-cap/spacing rules in `checkMobSpawnRules`).
- `checkMobSpawnRules` (inherited standard `Mob`/`Monster` check — not part of Slime, generic light/space validity)
  gates all three paths identically once the slime-specific probability/position checks pass.

MagmaCube spawn rule — `MagmaCube.checkMagmaCubeSpawnRules` (MagmaCube.java:31-35):
```
return level.difficulty != PEACEFUL
```
Trivial — no size, chunk, biome, or light gating at the mob-type level (biome placement/`spawn_costs`/`spawners`
lists in worldgen JSON, e.g. Nether biomes, control *where* magma cubes are candidates; this method only vetoes
Peaceful).

### 2.4 Spawn-egg / `/summon` default size

Neither spawn-egg placement nor `/summon` invoke `finalizeSpawn` with any size-forcing override beyond the same
`Slime.finalizeSpawn` roll described in §2.2 (SpawnEggItem and the summon command both route through the standard
entity-creation + `finalizeSpawn` pipeline with `EntitySpawnReason.SPAWN_ITEM_USE` / `EntitySpawnReason.COMMAND`
respectively, both of which are **not** `EntitySpawnReason.isSpawner(...)`, so they fall into the same
`finalizeSpawn` roll as any other non-spawner spawn — no default-to-size-1 override was found). This means:
- `/summon minecraft:slime` (no NBT) still resolves through `finalizeSpawn`'s `1<<random.nextInt(3)` roll with the
  difficulty-bonus, exactly as if it spawned naturally — **not** a guaranteed size 1.
- If NBT explicitly sets `Size` (see §3 save format), `readAdditionalSaveData` (Slime.java:119-124) calls
  `setSize(Size+1, false)` which happens *after* the default entity-data value (`entityData.define(ID_SIZE, 1)`,
  Slime.java:89) but the relative ordering vs. `finalizeSpawn` depends on the generic entity-load pipeline
  (`finalizeSpawn` is only invoked for spawns without saved NBT / "SpawnGroupData" fresh spawns — entities loaded
  from NBT, including `/summon ... {Size:N}`, skip `finalizeSpawn` and use the NBT `Size` tag directly). So:
  - `/summon minecraft:slime` (bare) → random size per §2.2.
  - `/summon minecraft:slime ~ ~ ~ {Size:0}` → explicit size 1 (`Size` tag is `size-1`, see §3).
- **Raw entity-data default** (before any `finalizeSpawn`/NBT applies, i.e. what you'd get from a bare
  `entityType.create()` with zero further processing, as used mid-flight by `Mob.convertTo` for split children) is
  size **1** (`entityData.define(ID_SIZE, 1)`, Slime.java:89) — this is the value split-on-death children start at
  before their `setSize(halfSize, true)` call overwrites it (see §3).

---

## 3. Split-on-death — `Slime.remove(RemovalReason reason)` (Slime.java:194-214)

```
size = getSize()
if !level.isClientSide() and size > 1 and isDeadOrDying():
    width = getDimensions(getPose()).width()          // == 0.52 * size (or MagmaCube's own via inherited dims)
    xzSlimeSpawnOffset = width / 2.0
    halfSize = size / 2                                // integer division
    count = 2 + random.nextInt(3)                       // 2, 3, or 4 — uniform
    team = getTeam()
    for i in 0 until count:
        xd = (i % 2 - 0.5) * xzSlimeSpawnOffset
        zd = (i / 2 - 0.5) * xzSlimeSpawnOffset          // integer division i/2
        convertTo(getType(), ConversionParams(SPLIT_ON_DEATH, keepEquipment=false, preserveCanPickUpLoot=false, team),
                  EntitySpawnReason.TRIGGERED,
                  child -> {
                      child.setSize(halfSize, true)
                      child.snapTo(x+xd, y+0.5, z+zd, random.nextFloat()*360.0, 0.0)
                  })
super.remove(reason)      // (Mob's normal removal/cleanup runs after spawning children)
```

Key facts:
- **Does size-1 split?** No — guard is `size > 1`; a size-1 (tiny) slime/magma cube produces **zero** children on
  death. This is the base case that terminates the split recursion (size 4 → two size-2s → each size-2 → 2-4 size-1s
  → size-1s die with no further split).
- **Child count formula:** `2 + random.nextInt(3)` → uniform over {2, 3, 4}, independent random roll per parent
  death (not fixed at exactly `size`-dependent count — same distribution regardless of parent size, as long as
  parent size > 1).
- **Child size:** `halfSize = parentSize / 2` (integer division). For parentSize=4 → children size 2; parentSize=2
  → children size 1 (terminal, they will not split further); if a non-power-of-two size were manually set (e.g. 3),
  `halfSize = 1` (3/2 integer division), so an odd size still degrades toward 1 rather than being invalid.
- **Placement offsets:** `xzSlimeSpawnOffset = parentWidth / 2` where `parentWidth = 0.52 * parentSize` (Slime) —
  note this uses the **parent's** hitbox width (pre-split), not the child's. Offsets use `i % 2` and `i / 2` (both
  integer ops) each mapped through `(n - 0.5) * offset`, producing a small 2x2-ish quadrant spread scaled by
  `-0.5*offset` and `+0.5*offset` for i∈{0,1,2,3}: i=0→(x:-0.5*off, z:-0.5*off), i=1→(x:+0.5*off, z:-0.5*off),
  i=2→(x:-0.5*off, z:0 [i/2=1→0.5*off... recompute: i=2: i%2=0→-0.5*off x; i/2=1→ (1-0.5)*off=+0.5*off z], i=3:
  i%2=1→+0.5*off x, i/2=1→+0.5*off z. (When count=4 this gives the classic 2x2 quadrant grid; when count=2 or 3 only
  the first 2-3 quadrant positions are used, i.e. i=0,1[,2] — no shuffling, deterministic order.)
- **Y placement:** `y + 0.5` (half a block above parent's feet-Y), not on-ground snapped.
- **Yaw:** `random.nextFloat() * 360.0` — fully random per child, independent of parent facing. Pitch fixed at 0.
- **Velocity:** Not explicitly set by `Slime.remove`/the split lambda — `ConversionType.SPLIT_ON_DEATH`'s `convert()`
  override (`ConversionType$2` in decompiled bytecode) does **not** copy `from.getDeltaMovement()` (unlike
  `SINGLE`/`ConversionType$1`, which does `to.setDeltaMovement(from.getDeltaMovement())`). Net effect: split children
  spawn with zero/default velocity (whatever `EntityType.create` initializes, effectively 0,0,0), not inheriting the
  parent's fall/knockback momentum.
- **Do children inherit name/persistence?** Both `SINGLE` and `SPLIT_ON_DEATH` funnel through
  `ConversionType.convertCommon(from, to, params)` (`ConversionType.java:30-86`) which unconditionally copies:
  absorption amount, all active `MobEffectInstance`s, baby flag+age (if `AgeableMob`, N/A for Slime), the
  `ANGRY_AT` brain memory if present, `leftHanded`, `noAi`, **`persistenceRequired`** (`if from.isPersistenceRequired():
  to.setPersistenceRequired()` — one-directional, only propagates *true*), `customNameVisible`, on-fire shared flag,
  `invulnerable`, `noGravity`, `portalCooldown`, `silent`, all entity tags, and the `CUSTOM_NAME` and `CUSTOM_DATA`
  data components (`ConversionType.java:17`, `COMPONENTS_TO_COPY`). So **yes**, a custom-named or
  persistence-required parent slime's children inherit both the custom name and the persistence-required flag.
  `preserveCanPickUpLoot` is passed as `false` for SPLIT_ON_DEATH, so `canPickUpLoot` is explicitly **not** copied
  (children get their default, false, canPickUpLoot regardless of parent). `keepEquipment=false` too, but Slime/MagmaCube
  carry no equipment slots functionally, so this is moot. Team membership is copied via `params.team()` (scoreboard
  team add), independent of the two boolean flags.
- **Leash handling:** `ConversionType$2.convert()` explicitly drops any leash the dying parent held
  (`from.dropLeash()`) before calling `convertCommon` — a leashed parent's children are never leashed.
- **Passenger/vehicle handling:** Unlike `ConversionType.SINGLE` (which transfers riders/vehicle), `SPLIT_ON_DEATH`
  only stops the parent's own first passenger from riding (does not re-mount it on a child) — split children never
  inherit riders or the parent's vehicle.
- **Spawn timing/order:** `Mob.convertTo` (Mob.java:1176-1199) creates the child via `entityType.create(level,
  EntitySpawnReason.TRIGGERED)` (raw construction, **not** `finalizeSpawn` — so no independent random size re-roll
  happens; the child's synced size defaults to 1 from `defineSynchedData` until the `afterConversion` lambda's
  explicit `setSize(halfSize, true)` runs), then `params.type().convert(...)` (leash/passenger cleanup +
  `convertCommon`), then the `afterConversion` lambda (`setSize` + `snapTo`), then `serverLevel.addFreshEntity(newMob)`.
  Because `SPLIT_ON_DEATH.shouldDiscardAfterConversion()` is `false` (`ConversionType.java:15`,
  `SPLIT_ON_DEATH("SPLIT_ON_DEATH", 1, false)`), `Mob.convertTo` does **not** call `this.discard()` on the parent —
  the parent's actual removal is handled by the normal death pipeline (`super.remove(reason)` at the end of
  `Slime.remove`), which is already in flight (this `remove()` override runs inside the removal call chain).
- **NBT `Size` tag:** saved as `getSize() - 1` (`addAdditionalSaveData`, Slime.java:113-117, key `"Size"`), loaded as
  `setSize(input.getIntOr("Size", 0) + 1, false)` (Slime.java:120-121, `readAdditionalSaveData`) — i.e. NBT `Size:0`
  == in-game size 1, matching the `/summon` example in §2.4. `updateHealth=false` on load (health is restored
  separately from the saved `Health` tag by the generic entity load path, not re-maxed).

---

## 4. Attack behavior differences by size

### 4.1 `isDealsDamage()` — the core "can this slime/magma cube deal touch damage" gate

- Slime (Slime.java:243-245): `!isTiny() && isEffectiveAi()`. `isTiny()` = `getSize() <= 1` (Slime.java:126-128).
  So **size-1 ("tiny") slimes deal zero touch/attack damage** — confirmed by the guard being checked in both
  `playerTouch` (Slime.java:223-227) and `push` (against `IronGolem`, Slime.java:216-221) and the attack-goal's
  `slime.isDealsDamage()` (passed into `SlimeMoveControl.setDirection` to decide aggressive jump-timing, §4.2).
- MagmaCube (MagmaCube.java:85-88) **overrides** this: `isDealsDamage() = isEffectiveAi()` — **no** `isTiny()` check.
  So **size-1 magma cubes DO deal damage** (this is the documented vanilla asymmetry between tiny slimes, which are
  harmless, and tiny/"small" magma cubes, which still attack). `getAttackDamage()` for a size-1 magma cube is
  `1 + 2 = 3`.

### 4.2 Attack cadence / jump-based melee

Slimes/magma cubes don't have a discrete "attack action" separate from movement — damage is dealt via
`playerTouch`/`push` collision checks (each called whenever the entity's hitbox actually overlaps a valid target,
effectively every tick of contact, further gated by `dealDamage`'s `isWithinMeleeAttackRange(target) &&
hasLineOfSight(target)` checks, Slime.java:229-237) combined with `SlimeAttackGoal`
(Slime.java:340-391) driving pursuit:
- `SlimeAttackGoal.start()`: `growTiredTimer = reducedTickDelay(300)` — **300 ticks (15s)** of chase persistence
  before giving up (decremented every `canContinueToUse()` check, i.e. every goal-selector tick while active).
- `SlimeMoveControl.tick()` (Slime.java:445-498) governs jump cadence: `jumpDelay` starts at `slime.getJumpDelay()`
  = `random.nextInt(20) + 10` ticks (**10–29 ticks**, uniform) for Slime (Slime.java:165-167); **when
  `isAggressive` (i.e., has a target and `isDealsDamage()` returned true when `setDirection` was last called)**,
  `jumpDelay /= 3` (integer division) — so an aggressive size≥2 slime jumps roughly 3x as often (≈3–9 ticks between
  jumps) as a wandering one.
- MagmaCube overrides `getJumpDelay()` to be **4x** Slime's base roll (`super.getJumpDelay() * 4`,
  MagmaCube.java:56-59) → base **40–116 ticks** between jumps (then still divided by 3 when aggressive → ≈13–38
  ticks), i.e. magma cubes jump much less often than slimes baseline, but the `/3` aggressive-speedup ratio is the
  same relative factor.
- Damage-per-touch is a flat hit whenever in melee range + line of sight while `isDealsDamage()` — there is no
  separate "attack tick timer"; effectively every server tick that the touch conditions hold, a hurt event fires
  and (through normal invulnerability-tick mechanics on the *victim*, standard 10-tick/0.5s hurt-immunity window —
  not slime-specific, from generic `LivingEntity` invulnerable-time handling) actual damage application is naturally
  rate-limited to roughly once per hurt-immunity window on the victim's side, not the slime's.

### 4.3 `isTiny()` usage summary (Slime.java, used by both Slime and MagmaCube since MagmaCube doesn't override the
size-1 predicate itself, only the damage gate):
- `isDealsDamage` gate (Slime only, §4.1)
- Hurt/death/squish/jump **sound variants** switch to the "_SMALL" sound event when `isTiny()` (§5)
- `getSoundPitch()` (private, Slime.java:327-330): `pitchAdjuster = isTiny() ? 1.4 : 0.8`, then
  `((rand.nextFloat()-rand.nextFloat())*0.2 + 1.0) * pitchAdjuster` — tiny slimes' jump sound pitch is scaled ~1.75x
  higher than non-tiny (1.4 vs 0.8 base multiplier).

---

## 5. Other size-dependent behavior

- **Squish animation:** `targetSquish`/`squish`/`oSquish` floats, updated every tick
  (`squish += (targetSquish - squish) * 0.5`, Slime.java:136-137, exponential-decay lerp, not size-dependent
  itself, but the *visual* squish amount is naturally proportional to the scaled model since squish factors are
  applied on top of the size-scaled hitbox/model). Landing triggers `targetSquish = -0.5` and jumping-off triggers
  `targetSquish = 1.0` (Slime.java:139-155). `decreaseSquish()`: Slime multiplies `targetSquish *= 0.6` per tick
  (Slime.java:161-163); **MagmaCube overrides to `*= 0.9`** (MagmaCube.java:61-64) — magma cubes' squish decays
  much slower (retains squish longer, more pronounced "jelly" look), independent of size.
- **Squish/landing particle count:** on landing (Slime.java:139-151), particle count = `size(width) * 16` where
  `size = getDimensions(pose).width() * 2.0` — i.e. `particleCount ≈ (2 * width) * 16 = 32 * 0.52 * slimeSize =
  16.64 * slimeSize` (loop bound is a float compared each iteration, effectively `floor`). Bigger slimes spawn
  proportionally more landing particles. Particle type: `ParticleTypes.ITEM_SLIME` for Slime
  (`getParticleType()`, Slime.java:130-132), overridden to `ParticleTypes.FLAME` for MagmaCube (MagmaCube.java:47-50).
- **Sound volume:** `getSoundVolume() = 0.4 * getSize()` (Slime.java:292-294) — used for jump sound, squish sound,
  applies to both Slime and MagmaCube (not overridden by MagmaCube). Bigger = louder, linearly with size.
- **Jump power / height:**
  - Slime: `jumpFromGround()` (Slime.java:305-309) sets Y-velocity to `getJumpPower()` (generic
    `Attributes.JUMP_STRENGTH`-derived base, not slime-specific — out of scope here) with **no size term** — jump
    *height* on solid ground is size-independent for Slime.
  - MagmaCube: `jumpFromGround()` (MagmaCube.java:66-72) adds a size-proportional boost:
    `Y-vel = getJumpPower() + size * 0.1` — bigger magma cubes jump measurably higher on solid ground.
  - MagmaCube also overrides `jumpInLiquid(TagKey<Fluid> type)` (MagmaCube.java:74-83): when swimming/jumping in
    **lava** specifically, `Y-vel = 0.22 + size * 0.05` (flat override, ignores normal jump power entirely); for any
    other fluid (e.g. water) falls back to `super.jumpInLiquid(type)` (generic Mob/Slime behavior, not slime-size
    specific). Slime has no `jumpInLiquid` override, so slimes use the generic liquid-jump behavior in water/lava
    alike.
  - `doPlayJumpSound()` (Slime.java:301-303): `getSize() > 0` — always true given `MIN_SIZE=1`, effectively
    dead/always-true code (kept for citation completeness, not size-differentiating in practice since size can't be
    ≤0 post-clamp).
- **Sounds table (size-gated via `isTiny()`, both classes override the 4 sound getters):**

  | Sound | Slime (tiny) | Slime (≥2) | MagmaCube (tiny) | MagmaCube (≥2) |
  |---|---|---|---|---|
  | hurt | `SLIME_HURT_SMALL` | `SLIME_HURT` | `MAGMA_CUBE_HURT_SMALL` | `MAGMA_CUBE_HURT` |
  | death | `SLIME_DEATH_SMALL` | `SLIME_DEATH` | `MAGMA_CUBE_DEATH_SMALL` | `MAGMA_CUBE_DEATH` |
  | squish | `SLIME_SQUISH_SMALL` | `SLIME_SQUISH` | `MAGMA_CUBE_SQUISH_SMALL` | `MAGMA_CUBE_SQUISH` |
  | jump | `SLIME_JUMP_SMALL` | `SLIME_JUMP` | `MAGMA_CUBE_JUMP` (no size split — MagmaCube's `getJumpSound()` override, MagmaCube.java:110-113, always returns the same event regardless of size) |

- **Knockback:** No slime/magma-cube-specific knockback-resistance or knockback-dealt scaling was found in either
  class beyond the standard `Attributes.ATTACK_DAMAGE`-driven `dealDamage()` → `hurtServer` → generic knockback
  application pipeline (`EnchantmentHelper.doPostAttackEffects`, standard). No `Attributes.KNOCKBACK_RESISTANCE`
  override present in either `setSize` implementation — both rely on whatever `LivingEntity.createLivingAttributes()`
  supplies as the template default (not size-scaled).
- **Loot tables (size-gated, datapack JSON not Java):**
  - `data/minecraft/loot_table/entities/slime.json`: single pool with an `entity_properties` condition
    `type_specific.type = "minecraft:slime", type_specific.size = 1` gating the **entire pool** — i.e. `minecraft:slime_ball`
    (and the frog-predation exceptions) **only drops from size-1 (tiny) slimes**; size ≥2 slimes drop nothing from
    this table. Count: `uniform(0,2)` slimeballs (rounded via `set_count`), plus looting-enchant bonus
    `uniform(0,1)` per looting level (`enchanted_count_increase`), with a special-cased alternate entry
    (flat count 1, no looting bonus) when the damage source is a `minecraft:frog` entity — frog predation kills
    always yield exactly 1 slimeball.
  - `data/minecraft/loot_table/entities/magma_cube.json`: single pool, entries individually gated per-item (not
    pool-level). The `minecraft:magma_cream` entry requires `entity_properties` predicate
    `type_specific.type = "minecraft:slime"` (MagmaCube reuses the Slime type-specific predicate key),
    `type_specific.size = {min: 2}` — i.e. **magma cream drops only from size ≥2 magma cubes; size-1 ("tiny") magma
    cubes drop no magma cream** (mirrors, but is the opposite direction of, slime's "only size 1 drops" rule — worth
    flagging explicitly since it's easy to invert by mistake when porting). Count: `uniform(-2,1)` (i.e. drop count
    can floor at 0 → no drop even when the condition passes) plus looting bonus `uniform(0,1)`/level, again with a
    frog-predation flat-1 exception (any frog kill, non-warm/cold/temperate-specific note: the flat-1 magma-cream
    entry requires *not* being killed by a frog — actual frog-variant-specific entries drop froglight items instead,
    see below) — re-read carefully: the frog-exclusion (`inverted` of "killed by any frog") gates magma cream; three
    separate frog-**variant**-specific entries (warm/cold/temperate) each drop exactly 1 of the matching froglight
    item (`pearlescent_froglight`/`verdant_froglight`/`ochre_froglight`) with **no size gating at all** — any-size
    magma cube killed by a warm/cold/temperate frog drops its froglight, size-independent.
- **`getPassengerAttachmentPoint`** (Slime.java:239-241): `y = dimensions.height() - 0.015625 * getSize() * scale`
  — rider seat height scales down slightly (by `~0.0156*size` blocks) relative to the top of the (already
  size-scaled) hitbox; minor visual/riding detail, cited for completeness, low priority to port exactly.
- **`getMaxHeadXRot()`** (Slime.java:296-299): fixed `0` — slimes/magma cubes never pitch their head; not
  size-dependent, but relevant context for any head-rotation/look-goal porting.
- **Light-level immunity (MagmaCube only):** `getLightLevelDependentMagicValue() = 1.0` (always max, prevents
  light-based despawn/burn checks that key off this value) and `isOnFire() = false` (always reports not on fire for
  rendering/particle purposes even if actually ablaze) — both size-independent, MagmaCube-only overrides
  (MagmaCube.java:43-45, 52-54), included here since they interact with fire-immunity (`EntityType.MAGMA_CUBE`
  registered `.fireImmune()`, EntityType.java:671) which itself is size-independent but easy to conflate with the
  attack/size mechanics being ported alongside.

---

## 6. Simplification candidates (flag for AUDIT.md if adopted)

1. `DifficultyInstance.getSpecialMultiplier()` / `effectiveDifficulty` continuous moon-phase+time blend (§2.2) —
   candidate for a static per-`Difficulty`-enum constant instead of full local-difficulty simulation.
2. `SURFACE_SLIME_SPAWN_CHANCE` swamp override source is **unverified** (§2.3) — must be resolved (either found in a
   not-yet-decompiled bootstrap class, or confirmed genuinely data-driven-but-absent-in-this-jar) before
   implementing swamp-specific slime spawning; do not silently default to 0 without documenting it.
2b. If left unresolved, a documented fallback (e.g. flat 1% surface-spawn chance in swamp biomes, y 50-70, light
   gated) is a reasonable stopgap, explicitly logged as a simplification.
3. Slime-chunk determinism (`WorldgenRandom.seedSlimeChunk`, magic salt `987234911L`) requires porting Mojang's
   exact `WorldgenRandom`/legacy-random seeding algorithm bit-for-bit if slime-chunk parity with vanilla seeds is
   desired — otherwise document that Minestom slime chunks will not match vanilla world seeds' slime chunk maps,
   which is likely an acceptable, explicitly documented simplification for most non-seed-critical use cases.
4. Landing-particle count/positions (§5) are cosmetic-only; approximate count formula is fine to port loosely
   (or skip / stub) since it has no gameplay effect — reasonable simplification if performance-constrained.
5. `getPassengerAttachmentPoint`'s exact `0.015625*size` mount-height fudge factor (§5) is a minor visual constant;
   safe to approximate or omit under documented simplification if riding slimes isn't a supported feature.

---

## 7. Cross-check gaps / unresolved items (explicitly flagged)

- **§2.3**: Source of nonzero `SURFACE_SLIME_SPAWN_CHANCE` for swamp/mangrove-swamp biomes not located in the cached
  decompile tree within this session's search scope. Needs follow-up decompilation of biome-attribute bootstrap
  registration (likely a class instantiating `EnvironmentAttributeLayer$TimeBased`, not found by class name in the
  cached tree or jar's data folder).
- `EntityDimensions.scale(float)`'s exact eye-height scaling semantics were asserted by convention (uniform scale of
  width/height/eyeHeight together) rather than independently re-derived from `EntityDimensions.java` in this pass —
  low risk, standard Mojang behavior, but not independently re-verified byte-for-byte this session.
- `ConversionType`/`ConversionParams` have now been cached into `vanilla-src/net/minecraft/world/entity/` per
  CLAUDE.md item 6 (this session); `Mob.java` was already present and unchanged. No further action needed for these.
