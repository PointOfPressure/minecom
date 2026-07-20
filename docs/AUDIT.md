# Gap audit — 2026-07-11

A full pass over the gameplay codebase (everything outside the bit-exact-verified
worldgen core) collecting what is still missing or approximated versus real
vanilla 26.1.2. Notes only — nothing here was changed. Each item: where, what's
missing, what vanilla does, rough size (S/M/L). Written right after the four
HANDOFF items (daylight detector, difficulty system, villager food economy,
trial chambers) landed, so those are NOT re-listed except for their documented
leftovers.

## Cross-cutting

- **Unguarded `getBlock` near a loaded area's edge is a recurring bug class,
  not a one-off (2026-07-16, Fable).** The first instance of this
  (`Portals.tryLight`'s frame-detection walk, see below) was treated as an
  isolated fix; the MASTERPLAN §4 P0 bench harness's bot swarm — whose
  small wander is far more likely to reach a loaded area's edge than
  organic play — found three more while chasing real spawn/spread10k
  numbers: `Redstone.java`'s `strongPowerOf`/`wireInput`/`wireNeighbors`/
  `wireShape` (the `wireNeighbors` one is especially bad — it feeds
  `recomputeWireNetwork`'s BFS, so hitting it doesn't just throw once, it
  **permanently kills the shared redstone scheduler for the rest of the
  process**), `VNaturalSpawner.spawnCategoryForPosition`'s cluster-drift
  walk (mirrors real vanilla's `NaturalSpawner`, can wander ~20 blocks from
  its starting chunk), and `RandomTicks.growAmethyst`. All four fixed with
  the same `isChunkLoaded` guard pattern as the original. Given four
  independent occurrences now, any *new* code that walks outward from a
  known-loaded position (redstone-adjacent, spawn-adjacent, random-tick-
  adjacent, or otherwise) should default to guarding `getBlock` at the
  neighbor-computation site, not assume the surrounding area is loaded —
  see docs/HANDOFF.md's 2026-07-16 entry for the full debugging account.

- **`setInstance` without `.join()` is NOT a bug wherever the target chunk is
  already loaded — stop pattern-matching it (2026-07-13, Opus).** Most mob
  factories in `VanillaMobs` call `mob.setInstance(instance, pos)` without
  joining the returned `CompletableFuture<Void>`, and this shape has now been
  blamed for two separate flakes (zombie melee damage; dispensed animal spawn)
  and been **wrong both times** (real causes: sunburn, and an AI-stroll race in
  the test). Verified against the decompiled 26.1.2 Minestom: an entity is
  registered into `world.getEntities()` inside `setInstance`'s
  `loadOptionalChunk(...).thenAccept(...)`, and `InstanceContainer.loadOrRetrieve`
  returns `CompletableFuture.completedFuture(chunk)` for an already-loaded
  chunk — so the continuation runs **inline on the calling thread** and
  registration is fully synchronous. (`InstanceContainer.setBlock` also
  `join()`s a chunk load on a miss, so merely having placed a block nearby is
  enough to guarantee this.) The future is only genuinely async when spawning
  into an **unloaded** chunk. `silverfish()` is the one factory that joins, and
  says so in-source. Fix the pattern elsewhere only with evidence that a
  specific call site both spawns into an unloaded chunk and reads the entity
  back immediately — not on sight.

- **Session-scoped block-entity/mob state — largely CLOSED 2026-07-12
  (Fable).** The persistence design pass landed: docs/PERSISTENCE.md +
  `StateAdapter` SPI + `RegionStore` region shards (multi-core-ready
  sharding). Now persisted: chests (incl. double-chest shared views, full
  item NBT), furnaces (progress/XP), hoppers, crafters (+locked slots),
  brewing stands, dispensers/droppers, crops, all tracked redstone/sculk
  positions, mob entities (kind/pos/health/equipment + villager
  profession/food/inventory), chunk inhabited time, and — 2026-07-12,
  Sonnet — campfire/jukebox/lectern/decorated-pot/chiseled-bookshelf/
  shulker-box state (composter/bells/note-blocks needed no adapter: no
  separate persistent state beyond Anvil-saved block properties) plus
  per-mob extras (sheep color/sheared, baby state, breeding cooldown —
  the cooldown persisted as a relative "ticks remaining" delta since
  Breeding.java keys it by the entity's ephemeral runtime id, not
  something that survives a respawn — and slime/magma-cube size, restored
  via the explicit-size factory instead of Mobs.spawn's plain path which
  would otherwise roll a fresh random size). A restored baby's remaining
  grow-up time isn't modeled (the 20-minute timer is a one-shot scheduled
  task, not tracked state — gets a fresh timer on restore). ~~Trial
  chambers~~ and ~~fire's own scheduled-tick countdown~~ **done 2026-07-12
  (Sonnet)** — see their own entries below and in HANDOFF's "Persistence
  adapter tail". ~~Item frames/glow item frames and armor stands~~ **done
  2026-07-17 (Sonnet 5, Tier 3 batch 3)** — neither is an `EntityCreature`,
  so `RegionStore.collectMobs` never swept them; a restart silently
  discarded every placed frame (and whatever it held) or stand (and its
  flags/pose/equipment) with nobody having documented it as deliberate.
  Fixed with a sibling entity-sweep (`RegionStore.collectDecorations`/
  `restoreDecoration`, its own `"deco"` array per chunk — same shape as the
  existing mob sweep, not the `StateAdapter` SPI, since like mobs these are
  roaming/placed entities at a floating-point `Pos` rather than
  chunk-anchored block-entity data) rather than a new adapter kind.
  `ItemFrames.java`/`ArmorStands.java` gained small `spawnAt`/`isFrame`/
  primitive-`applyFlags` accessors so the restore path can reconstruct an
  entity exactly (position, direction, held item + rotation for frames;
  the five boolean flags, all six pose `Vec`s, and the six standard
  equipment slots for stands) without duplicating their placement logic.
  2 new PlayTest checks folded into the existing `scenarioPersistence`
  save/wipe/reload round trip. Still session-scoped: warden anger
  (deliberate), item entities in flight, breeding's own 30-second IN_LOVE
  window (too short-lived to be worth it), and the registries are
  overworld-only (position keys would collide across dimensions —
  pre-existing limitation).
- ~~No random-tick engine~~ **Core landed 2026-07-12 (Fable)** —
  `blocks/RandomTicks.java`: the ServerLevel.tickChunk dispatch (3 rolls per
  non-empty 16³ section per tick, chunks within 8 of players; vanilla's
  per-section ticking-block index is approximated by a palette-empty skip)
  with handlers for grass/mycelium spread + death, ice + snow-layer melt at
  block light > 11, sugar cane + cactus growth (incl. 26.x cactus flowers),
  farmland moisture, copper oxidation (full ChangeOverTimeBlock port:
  0.05688889 roll, Manhattan-4 scan, squared age ratio, 0.75 unaffected
  modifier, key-derived NEXT_BY_BLOCK chain), budding amethyst, and bamboo
  column growth (2026-07-12, Sonnet — BambooStalkBlock port: 1/3 roll gated
  on air+light>=9 above, 16-block cap with an unconditional stage flip at
  height 15, leaf-crown cascade onto the segment(s) below). Light is
  the project's behavioural model (VNaturalSpawner precedent). Still on old
  approximations or missing: snow accumulation stays in survival/Snow.java;
  leaf-decay random timing not implemented.
- ~~Crop growth~~ **Done 2026-07-12 (Sonnet)** — `RandomTicks.growCrop`/
  `cropGrowthSpeed`, decompile-verified against `CropBlock.randomTick`/
  `getGrowthSpeed`: light gate (raw brightness >= 9), the 3x3
  farmland-moisture-weighted growth-speed scan below the crop (center cell
  full weight, ring cells /4; unmoistened farmland contributes 1.0,
  moistened 3.0), halved for same-type neighbors on both axes (west/east
  AND north/south) or a lone diagonal same-type neighbor, then a
  `nextInt((int)(25/growthSpeed)+1)==0` roll. Covers wheat/carrots/potatoes/
  beetroots (the types `Farming.SEEDS` plants); `Farming.maxAge` widened
  from private to package-private for reuse (the `skyExposed` precedent).
  Replaces `Farming.growthTick`'s old flat 100-tick/20%-roll sweep, deleted
  along with its scheduler registration and the now-unused `Farming.instance`
  field. `Farming.CROPS` itself is untouched — still gates bonemeal
  eligibility and drives persistence — and the new handler is a fidelity
  improvement there too: it now applies to any wheat/carrots/potatoes/
  beetroots block, not just `CROPS`-tracked ones, matching real vanilla
  (growth was never actually restricted to player-planted crops). The risk
  HANDOFF flagged here ("must update the farming/villager playtest
  scenarios") turned out overstated: neither `scenarioFarming` nor
  `scenarioVillagerFood`'s farmer-harvest check depends on growth timing —
  both pre-place mature crops directly and drive age via bonemeal. New
  coverage folded into `scenarioRandomTicks` (light gate blocks growth
  entirely, unmoistened-farmland speed-2.0 branch, moistened-farmland
  speed-4.0 branch, per-crop maxAge cap using beetroots' 3 vs. wheat's 7),
  5/5 clean reruns.
- ~~Fire spread~~ **Done 2026-07-12 (Sonnet)** — `blocks/FireSpread.java`,
  deliberately NOT a `RandomTicks.java` consumer: real `FireBlock` doesn't
  use a random tick at all, it self-reschedules a genuine scheduled tick
  every 30+rand(10) ticks per block, so this is its own tracked-position +
  shared-periodic-scheduler subsystem (Campfires/Jukebox's shape, not
  Redstone's tracked-position-registry one). Ported verbatim: age
  progression, checkBurnOut on the 6 cardinal/vertical neighbors
  (consume-and-relight or remove, TNT priming included), the 3x3x6
  spread-attempt volume weighted by igniteOdds/(age+30)+difficulty, rain
  extinguishing gated on sky exposure, netherrack/magma "infiniburn"
  exemption. The 207-entry `setFlammable` odds table was machine-diffed
  against the decompile for an exact match, not hand-transcribed on trust.
  Wired into the two existing fire-placement sites (fire charge hits,
  dispenser flint-and-steel) plus a genuinely separate gap found while
  scoping this: the player-direct flint-and-steel case only handled TNT
  priming before, general fire-lighting was dispenser-only. Not modeled:
  `EnvironmentAttributes.INCREASED_FIRE_BURNOUT` (no environment-attribute
  exposure for it yet — treated as always off), `isFaceSturdy`'s exact
  per-shape solidity (approximated as `Block.isSolid()`), nether/end
  infiniburn tags (only the overworld's netherrack/magma_block pair).
  `doFireTick`-style gating doesn't exist in real vanilla's fire-spread
  path at all (no `mobGriefing`-class check found in `FireBlock.tick`) —
  the "no gamerule store" note elsewhere in this file doesn't even apply
  here, there's nothing to gate. ~~No persistence for the tracked-position
  countdown~~ **done 2026-07-12 (Sonnet)** — a `StateAdapter` (Campfires'
  exact shape) now saves/restores `POSITIONS`/`COUNTDOWN`, so a restart
  doesn't silently stop a mid-countdown fire's spreading/aging/burning-out.
- ~~Sapling growth~~ **Done 2026-07-12 (Sonnet)** — `RandomTicks.growSapling`/
  `Farming.advanceTree`, decompile-verified against `SaplingBlock.randomTick`/
  `advanceTree`: light gate (raw brightness above the sapling >= 9), a 1/7
  roll, and a real two-stage climb the old one-shot scheduled-delay
  approximation didn't have at all — stage 0 just cycles to stage 1 on a
  successful roll, only a SECOND successful roll against a now-stage-1
  sapling actually grows the tree. `Farming.boneMeal`'s sapling branch
  called `growTree` straight through (instant tree, no stage check) —
  real vanilla's `performBonemeal` also just calls `advanceTree`, so bone
  meal needed the exact same fix, not a separate one. Covers all 8 real
  sapling types; `Farming.growTree`'s tree-shape logic is unchanged, only
  the trigger/pacing around it moved off the scheduled delay.
- ~~Grass bonemeal~~ **Done 2026-07-12 (Sonnet)** — `Farming.boneMealGrass`:
  GrassBlock.performBonemeal's 128-attempt scatter walk (quantized into 8
  groups of 16 with a progressively longer random walk, staying on top of
  grass_block, landing on air scatters a short_grass ~7/8 of the time via
  the real bundled GRASS_BONEMEAL placed-feature data). Mycelium turned out
  to not be bonemealable at all in real vanilla (decompile-verified —
  MyceliumBlock has no BonemealableBlock implementation, only a
  client-visual particle tick; HANDOFF's "grass/mycelium" framing was
  imprecise). Not modeled: re-rolling an existing short_grass into
  tall_grass (a rare 1/10 sub-case), and the other 1/8 "biome-specific
  decoration feature" branch (flowers/mushrooms/trees — the real per-biome
  feature list is bundled in biome_features.json, but placing an
  arbitrarily complex feature needs this project's worldgen-time Canvas
  system, which isn't bridged to live gameplay — a bigger, separate task).
- ~~Vine spread~~ **Done 2026-07-12 (Sonnet)** — VineBlock.randomTick, the
  growth half (RandomTicks.spreadVine): 1/4 roll, then a uniform pick among
  6 directions; horizontal + not-yet-connected tries to extend outward
  (corner-wrap via an existing CW/CCW face, else hang a fresh face off the
  CW/CCW neighbor, else a rare 5% upward poke), otherwise tries to grow a
  new vine above or below by copying a random subset of this block's own
  horizontal faces (VineBlock.copyRandomFaces), gated by the same 9x3x9/
  5-vine density cap (canSpread) as real vanilla. Not modeled: the
  neighbor-update-driven detach/survival check (canSurvive/updateShape —
  this codebase has no generic block-support-removal system to hook into),
  world min/max Y bounds (no established height-bounds accessor elsewhere),
  MultifaceBlock.canAttachTo's exact per-face voxel shape (approximated as
  `block.isSolid()`, same coarse-solidity pattern used elsewhere in this
  file), SPREAD_VINES gamerule (assumed always true — no gamerule store in
  this project).
- ~~Copper WAXING (honeycomb interaction, axe scraping)~~ **Done 2026-07-12
  (Sonnet)** — `blocks/CopperWaxing.java`: honeycomb prefixes "waxed_" onto
  any unwaxed copper-family block (RandomTicks.isWeatheringCopper gate,
  consumes 1 outside creative), which blocks the oxidation handler above for
  good; an axe on a waxed block strips the wax back off, or on an unwaxed
  weathered block scrapes it back one stage (RandomTicks.previousOxidation,
  the key-derived mirror of the oxidation handler's own next-stage lookup)
  — both cost 1 durability, no item consumed. Not modeled: axe log-stripping
  (AxeItem.STRIPPABLES — a separate, still-missing gap, no stripped-log
  system in this codebase at all), particles/sounds (client-visual).
  ~~sign-waxing (HoneycombItem also implements SignApplicator — no sign
  block-entity/text system exists to apply it to)~~ **Done 2026-07-17
  (Sonnet 5, Tier 3 batch 2, `blocks/Signs.java`)** — see the dedicated
  signs/banners AUDIT entry.
- ~~No attack-cooldown model~~ **DONE 2026-07-15 (Sonnet 5)** — Combat.java's
  player melee branch, decompile-verified against `Player.getAttackStrengthScale`/
  `getCurrentItemAttackStrengthDelay`/`baseDamageScaleFactor`/`canCriticalAttack`/
  `isSweepAttack` (26.2): a per-player "world age at last swing" tracks the real
  charge ticker (equivalent to it without a live per-tick counter), weapon
  attack_speed (`Items.attackSpeed`, new) sets the real recharge delay
  (20/attack_speed ticks), damage is quadratically scaled by charge
  (0.2 + scale²×0.8) before enchant flat bonuses are added (those aren't
  charge-scaled, matching the decompile's exact ordering), crit requires
  full charge AND not sprinting (previously ungated), sweep requires full
  charge/non-crit/non-sprint (previously ungated, sweep formula itself
  already correct), and a sprinting full-charge hit adds a real second,
  separate knockback impulse (`Player.attack`'s `knockbackAttack`, decompile-
  verified as a genuinely separate sequential `knockback()` call, not a
  combined-strength one). Combat.resetAttackCharge exposed for tests that
  need a deterministic full-charge hit.
- **No server-enforced mining-speed system** (VanillaMobs elder guardian notes)
  — Mining Fatigue/Haste apply as effects but don't change dig speed
  server-side; Aqua Affinity/Depth Strider abandoned for the same reason. (M/L)
- ~~Chunk inhabited time not persisted~~ **Done 2026-07-12 (Fable)** — rides
  the region shards (RegionStore), restored via Difficulty.setInhabitedTicks.
- **Concurrent-session note:** another model is actively adding blocks
  (Cake.java, Candle.java appeared mid-audit); re-check this list against HEAD
  before starting work.

## blocks/

- Redstone.java dispenser behaviors — **expanded 2026-07-11 (Fable)**: now
  covers arrows (tipped/spectral), snowball, egg, wind charge, fire charge
  (small fireball ignites on impact via Combat), spawn eggs (via Mobs.spawn),
  minecarts incl. chest/hopper/furnace/TNT variants (rails only), bone meal
  (Farming.boneMeal), flint&steel (fire/campfire/candle relight/TNT prime; no
  tool durability inside containers), powder snow bucket, shulker box
  placement, plus the pre-existing water/lava/empty buckets, TNT, boats.
  Second expansion same day: splash/lingering potions (full thrown-potion
  system, `survival/ThrownPotions.java` — splash distance scaling is
  center-to-center rather than vanilla's AABB-to-AABB), XP bottles (3-11
  orbs on landing), glass-bottle water fill, shears via `Shearing.shear`
  (no tool durability in containers), armor equipping onto living entities
  with empty slots, firework rockets (cosmetic flight only — no explosion
  or elytra boost). Still missing:
  dispenser-fires-a-brush (a brush dispensed at a suspicious block — no dispenser
  case for it; ~~brushing itself~~ **done 2026-07-17, Sonnet 5, Tier 3 batch 4** — see
  the dedicated Archaeology entry below), candle placement, chest-onto-donkey (no
  chested-horse inventory). Armor stands landed 2026-07-16 (Opus, Tier 3
  batch 1 — `blocks/ArmorStands.java`: place/consume, cursor-height equip +
  swap + bare-hand take, Invisible/Small/NoBasePlate/Marker/ShowArms + pose
  from item NBT, two-hit player break dropping the item + gear; simplified
  to the player-break path only, no incremental-health/fire/explosion break
  branches, disabled-slots always 0, poses meta-only). Honeycomb/waxing landed 2026-07-12 (Sonnet,
  `blocks/CopperWaxing.java` — see the random-tick engine entry above, now
  that oxidation exists for it to block). Sculk
  shriekers landed too (Vibrations.java): player-caused shrieks, Darkness,
  warning levels; the warden itself landed 2026-07-12 (Fable — see the
  mobs/ section entry). (each S once its base system exists)
- Beacons landed 2026-07-16 (Opus, Tier 3 batch 1 — `blocks/Beacons.java`,
  ported from BeaconBlockEntity/BeaconMenu): updateBase pyramid levels 0-4
  over BEACON_BASE_BLOCKS, beam/effect sky-access gate (lightBlocked>=15
  breaks it, glass passes), validateEffects level-gating, menu payment-item
  consumption, and the gameTime%80 effect application (level*10+10 range,
  (9+level*2)*20 duration, amp II at level 4 when secondary==primary).
  Simplified: whole beam/level recompute on the 80-tick cadence (not the
  10-blocks/tick incremental beamSections walk), no beam colour sections or
  CONSTRUCT_BEACON advancement, the set-beacon packet maps PotionType->effect
  by key.
- Conduits landed 2026-07-16 (Opus, Tier 3 batch 1 — `blocks/Conduits.java`,
  ported from ConduitBlockEntity): updateShape 3x3x3 water gate + radius-2
  prismarine-family frame count, activation (>=16) and hunting (>=42),
  size/7*16 power radius, gameTime%40 Conduit Power to in-water/rain players
  in range, and the 4-magic-damage pulse to a random hostile mob in water
  within 8 blocks. Simplified: no nautilus/attack particles, destroy target
  re-selected each cadence (not persisted across ticks), "in water or rain"
  treats rain as raining + sky access, hostile set is an explicit monster
  enumeration (the vanilla Enemy interface).
- Redstone.java `containerSignal` — **expanded 2026-07-11 (Fable)**: added
  copper bulb (LIT=15), crafter (filled+locked slots), sculk sensors (last
  vibration frequency while active); **beehive/bee-nest honey_level added
  2026-07-16 (Sonnet 5, Tier 3 batch 2)** alongside `blocks/Beehives.java` — the
  raw honey_level (0-5), unscaled (BeehiveBlock.getAnalogOutputSignal). Barrel/brewing/cake/end-portal-frame/chiseled-bookshelf/
  decorated-pot reads were already present.
- ~~Redstone.java — no crafter block~~ **Done 2026-07-11 (Fable)**:
  `redstone/Crafters.java` — trigger edge + 4gt delay, 6gt CRAFTING, eject
  into containers or as item, slot locking via empty-cursor click, comparator
  = filled+locked. Not modeled: recipe remainder items (bucket returns),
  fail/success level events, locked-slot client visuals (container property
  packets), smallest-stack insert balancing. ~~Crafter persistence
  (session-scoped like dispensers)~~ — done, rides the persistence core
  landed 2026-07-12 (Fable): crafters + locked slots are in the region
  shards now, same as chests/hoppers/furnaces.
- ~~Redstone.java — sculk sensors: no vibration system~~ **Done 2026-07-11
  (Fable)**: `redstone/Vibrations.java` — full frequency table, 1 block/gt
  travel, distance power, sensor 8/30gt + calibrated 16/10gt with back-face
  frequency filter, 10gt cooldown, comparator = last frequency, wool
  occlusion as a straight-line sample (vanilla probes a curved occlusion set),
  step sweep every 5gt (sneaking players silent). Emission taps: block
  place/break, note blocks, TNT prime, explosions, lightning, projectile
  land, and — done 2026-07-12 (Sonnet), decompile-verified against
  `ContainerOpenersCounter.incrementOpeners`/`decrementOpeners` (the exact
  same 0-&gt;1/1-&gt;0 transition already driving this session's new chest/
  barrel lid-animation work) — `container_open`/`container_close` (chest/
  trapped_chest/ender_chest/barrel/hopper/furnace family/shulker box/
  brewing stand/dispenser+dropper/crafter; close only wired where this
  project already tracks a close — chest/trapped_chest/barrel/ender_chest —
  the other six only have open-side tracking today, a known asymmetry, not
  a gap that needed new close-tracking infrastructure just for this),
  `block_open`/`block_close` (doors/trapdoors/fence gates, unconditional on
  every toggle, no opener-count gating), `eat`, `drink`, and one `equip`
  site (dispenser-equips-a-mob — the only equip call site found with
  confidence; player-direct right-click-to-wear-armor wasn't wired, no
  clearly-matching call site found without guessing). The shrieker→warning→
  warden chain is fully modeled as of 2026-07-12 (Fable): faithful
  WardenSpawnTracker port in Vibrations.java (can_summon-gated warnings,
  players within 16 pooled to max+1 and copied back, 200gt increase
  cooldown, no warning with a warden within 48, amortized -1-per-12000gt
  quiet decay, darkness + reply sound/summon when the 90gt shriek ends)
  plus the warden itself (mobs/ai/WardenMob.java). Still not modeled:
  amethyst resonance, player-direct equip, swim/splash/flap emissions, the
  spawn_wardens gamerule (no gamerule system). "Waterlogged
  silencing" was on this list but turned out to be a non-issue — checked
  2026-07-12 (Sonnet): `SculkSensorBlock`'s WATERLOGGED references are all
  about suppressing the click *sound*, not detection; a waterlogged sensor
  activates/powers/reads identically to a dry one in real vanilla. Note
  the behavior change: non-summon shriekers
  (can_summon=false, the player-placed default) no longer apply Darkness —
  that matches vanilla (tryRespond requires an actual warning).
- ~~Pistons.java — no slime/honey chains~~ **Done 2026-07-11 (Fable)** — full
  structure resolver port; see docs/HANDOFF.md Done entry. Block-entity move
  denial is inherent (isPushable rejects block entities). The
  reorder-at-collision path gained real coverage 2026-07-13 (Fable): a
  hand-traced collision rig with an execution-witness check
  (Redstone.pistonReorderFires — layouts alone can't prove the path ran,
  because apply() snapshots states before moving, making final layouts
  provably invariant to toPush order in BOTH implementations; vanilla's
  moveBlocks snapshots too, so the list order only governs its
  moving-piston block-entity/update ordering, which the instant-apply
  simplification doesn't model), plus a 40-case differential fixture
  captured from a real vanilla 26.1.2 dedicated server
  (scripts/piston_vanilla_capture.py →
  resources/vanilla/piston_reorder_cases.json, replayed cell-by-cell by
  scenarioPistonDifferential). What the differential falsifies: collision
  handling's effect on resolve outcomes (membership closure, re-branch
  bounds, push-limit failures, blocked-vs-moved) and any other
  extend/retract divergence in the resolver port.
- Hoppers.java — check minecart-with-hopper pull-from-above and
  hopper-into-minecart paths (Minecarts variants exist; the connection is
  suspect). (S/M)
- ~~Fluids.java:26 — flow spreads evenly; vanilla weights toward nearest hole
  within 4 blocks.~~ **Done 2026-07-17 (Sonnet 5)** — decompile-verified against
  freshly-decompiled `FlowingFluid`/`WaterFluid`/`LavaFluid` (26.2, no cached
  copy predated the 26.2 bump; re-decompiled and cached to vanilla-src per rule
  7 even though fluids aren't worldgen, since this is now a differential-
  testable parity claim). Sideways spread now runs a BFS (`Fluids.slopeDistance`)
  from each open/same-fluid neighbor looking for the nearest "hole" (a cell
  whose own below-neighbor isn't solid — `Fluids.isHole`, this project's coarse-
  solidity approximation of `isWaterHole`) within `getSlopeFindDistance` blocks
  (water 4, lava 2 — overworld values only, `fast_lava`/nether still open, see
  below); only the direction(s) tied for the shortest path actually receive
  fluid, matching `FlowingFluid.getSpread` exactly. A dead end in every
  direction (no hole within range) falls back to the old spread-evenly
  behavior, which is real vanilla's own fallback too — the existing "vanilla
  diamond of 113" PlayTest check (a fully open, hole-free area) is unaffected
  and still the regression guard for that case. New `scenarioFluidSlopeWeighting`
  (3 checks): a source between two floor gaps at different distances spreads
  only toward the nearer one after a single deterministic tick, leaving the
  farther gap's side and both perpendicular directions untouched. Not modeled:
  vanilla's broader `canHoldAnyFluid` (any non-motion-blocking block, e.g. tall
  grass, can accept fluid) — this project's target eligibility stays air/
  same-fluid-only, a pre-existing simplification unrelated to this fix; kelp/
  seagrass waterlogging; bottomless lava in nether speed (`fast_lava`
  attribute, `LavaFluid.getSlopeFindDistance`/`getDropOff`/`getTickDelay` all
  branch on it — still a separate, undone gap, `isFastLava` was decompiled but
  not wired since this project has no dimension-attribute lookup for it yet).
- **Bubble columns (blocks/BubbleColumns.java, 2026-07-12, Fable)** —
  decompile-verified BubbleColumnBlock + Entity.onInside/AboveBubbleColumn +
  AbstractBoat: soul-sand push / magma drag columns re-derived from the
  below-neighbor on 5gt scheduled checks (CHECK_PERIOD), whole contiguous
  source-water runs converted (or reverted) at once, vanilla entity velocity
  ramps (inside ±0.03/0.06 toward -0.3/0.7; open surface cell ±0.03/0.1
  toward -0.9/1.8 — ×20 for Minestom's blocks/sec), fall-distance reset via
  Survival's in-fluid check, boats through the AbstractBoat 60gt armed timer
  (drag: skip buoyancy + eject + sink impulse; push: float until launch —
  the launch adds a 1.2-block teleport hop because Boats' surface easing
  re-zeroes velocity the next tick). Simplifications: columns are
  event-driven and session-scoped (worldgen soul sand under oceans doesn't
  self-start; Redstone-registry pattern), column cells aren't water sources
  for the flow engine (vanilla getFluidState is), clamps apply once per
  entity per tick from the highest intersected cell (not per swept block),
  flowing-water occupancy rejected same as vanilla (source only),
  whirlpool/upward particles + ambient sounds skipped (client visuals),
  launch velocities are impulses rather than sustained vanilla airborne
  arcs.
- Explosions.java — check block-drop probability (1/power) and whether
  explosions damage via `is_explosion` scaling now that difficulty exists
  (Combat handles players; item-frame/armor-stand absent anyway). (S)
- Furnaces.java — blast furnace/smoker/campfire recipe-book gating: blast
  furnace at 2x speed? (verify constants); fuel list completeness; recipe XP
  granularity. (S)
- Brewing.java — potion paths cover base tree (wart→awkward→...); check
  gunpowder→splash, dragon breath→lingering, redstone/glowstone
  duration/amplifier variants, blaze-powder fuel (20 charges). (M)
- Anvils.java — check rename cost, prior-work-penalty doubling, too-expensive
  cap 40, unit repair with raw material. (S/M)
- ~~Beds.java:41 — no nether/end bed explosion (vanilla BED_RULE attribute); no
  "monsters nearby" sleep denial; thunderstorm daytime sleeping. (S)~~ This
  note was stale — nether/end explosion and thunderstorm daytime sleeping were
  already done by the time of this pass (see `Beds.java`'s own class doc).
  **"Monsters nearby" done 2026-07-17 (Sonnet 5, Tier 3 batch 5)** —
  decompile-verified against `ServerPlayer.startSleepInBed` (26.2, freshly
  decompiled): a non-creative player can't sleep while a real vanilla
  `Monster` is within an AABB of ±8 blocks horizontal / ±5 vertical around
  the bed's bottom-center. `Monster.isPreventingPlayerRest` has exactly ONE
  override anywhere in the 26.2 entity tree (confirmed by scanning every
  class file's bytecode in the server jar, not just the ones this project
  implements): `ZombifiedPiglin`, gated on anger at the specific player —
  left out of `Beds.MONSTER_TYPES` entirely rather than reimplemented as
  "always false", since this project has no persistent per-mob anger/aggro
  timer for it (same gap `VanillaMobs`' wolf-anger note documents), so it
  can never actually be angry here. `MONSTER_TYPES` itself is real vanilla's
  `Monster` subclass hierarchy — also confirmed by walking each class
  file's superclass chain in the jar bytecode, not assumed from names —
  intersected with the mobs this project spawns; several plausible-looking
  "monsters" are real vanilla `Mob` subclasses, NOT `Monster` subclasses,
  and correctly do NOT block sleep: ghast, hoglin, slime, magma cube/sulfur
  cube, phantom, shulker. 5 new PlayTest checks in `scenarioBedMonsters`.
- Boats.java — no fall-damage negation rules; ~~bubble column sink?~~ (done
  2026-07-12 — buoyancy defers to BubbleColumns over drag columns); chest
  boats? (S/M)
- Campfires.java — signal smoke (hay bale below → taller smoke) is
  client-visual; soul campfire→piglin fear link missing (piglins don't fear
  anything). (S)
- Composter.java — verify per-item compost chances match
  data (looks data-driven already).
- ~~Containers.java: no double chests, no structure loot-table filling~~
  **corrected/done 2026-07-12 (Sonnet)** — this note was stale: double chests
  (left/right sharing one 54-slot Inventory, `openMergeable`), barrels,
  ender chests (per-player, `EnderChest.java`), trapped chests
  (comparator+signal via `TRAPPED_CHEST_POS`), and shulker boxes were all
  already implemented, just never reflected here. What was genuinely missing
  and is now done: structure-placed chest/barrel/dispenser LOOT CONTENTS —
  `Containers.registerLoot`/`rollPendingLoot` (a pos→loot-table-id pending
  registry, persisted, rolled on first open — not at generation time,
  matching real vanilla's LootTable NBT resolution timing) plus a new
  bundled `loot_chests.json` (56 tables extracted from the real server jar's
  `data/minecraft/loot_table/chests/`) and `LootTables.chest(idPath)`. Wired
  at every structure placement site that carries a chest/barrel/dispenser:
  villages, trial chambers, pillager outposts, ancient city, bastion
  (all via `VStructureGen.placeTemplate`'s NBT `LootTable` field, the same
  mechanism real vanilla uses — chest block-entity NBT was decompile-verified
  directly against a bundled village-house template), ruined portals,
  igloos, shipwrecks, ocean ruins (all via `VStructureManager`'s own
  per-structure NBT-template loops), jungle temple (chest + trap dispenser),
  desert pyramid, buried treasure (all 3 hand-placed, hardcoded to their one
  correct loot table id, no NBT to read), and stronghold
  corridor/crossing/library (`VStrongholdGen.createChest`, extended with a
  loot-table parameter). Two structures use a data-marker indirection
  instead of the chest's own NBT (structure_block "metadata" sitting one
  block above/beside the target) rather than a direct LootTable field:
  igloo's laboratory chest and shipwreck's 3 chest flavors (supply/map/
  treasure) — handled with the marker read directly rather than a generic
  data-marker system (not built here). Also fixed the chest/barrel/
  ender-chest **open animation**: `player.openInventory` only manages the
  inventory window, it never touches the block's own visual state — real
  vanilla drives the lid animation via a `BlockActionPacket` (chest/trapped_
  chest/ender_chest, actionId 1 = viewer count) or an "open" blockstate
  toggle (barrel, a genuinely different real-vanilla mechanism), neither of
  which existed anywhere in this project before (`Containers.chestAnimation`/
  `sendChestAction`/`barrelToggle`). Still not covered: mineshaft chest
  MINECART entities (a separate, larger no-worldgen-entity-spawning gap,
  unrelated to loot content), woodland mansion, ocean monument (no chest in
  real vanilla), nether fortress, swamp hut (no chest in real vanilla).
- ~~Portals.java: creative crosses at the survival 4-second pace~~ **fixed
  2026-07-12 (Sonnet)** — found live: creative/spectator players now cross
  instantly (`ServerPlayer.getDimensionChangingDelay`/`handleInsidePortal`
  decompile-verified — real vanilla only makes survival wait). Fixing this
  surfaced a second, genuine bug the instant crossing made trivial to
  trigger: with no portal cooldown at all, a player landing inside (or
  right next to) another nearby portal on arrival would instantly cross
  back, forever — a real vanilla-parity gap for survival too (just far
  less likely to ever be noticed there, since the ~4-second wait usually
  gives you time to step off the block first). Added a simplified
  `Entity.portalCooldown` analog (`Entity.java`/`setAsInsidePortal`
  decompile-verified): 300 ticks, refreshed to full every tick still
  touching a portal while on cooldown (so it only actually decrements once
  you've stepped off), set on every successful crossing regardless of game
  mode. Not modeled: the full `PortalProcessor`/`TeleportTransition`
  machinery (per-axis relative-position preservation through the portal,
  multi-entity/vehicle handling) — this project's simpler position-scaling
  `travel()` is unchanged, only the wait/cooldown timing around it is new.
  Also found and fixed a latent, pre-existing crash surfaced by the
  Containers.java portal-vs-fire fix above (`Portals.tryLight` is now
  called on every flint-and-steel click, not just from this file's own
  listener): its frame-detection scan can walk up to ~24 blocks from the
  click position and was calling `getBlock` unguarded, which throws
  (`NullPointerException: Unloaded chunk`) if that walk reaches an
  ungenerated chunk — a real risk during normal play near the edge of
  explored terrain, not just a test artifact (caught via a genuinely flaky
  `--playtest "fire spread"` run, root-caused from the stack trace, not
  guessed). `safeAir`/`obsidian` now check `isChunkLoaded` first and treat
  unloaded space as air/non-obsidian instead of throwing — correct, not
  just safe, since real vanilla wouldn't find a portal frame in
  ungenerated space either.
- No: skulk, spore blossoms, big dripleaf tilt, pointed dripstone falling/
  filling, ladders/vine climb speed (client-side anyway),
  respawn-anchor charge particles. (each S-M) — chiseled bookshelves,
  decorated pots, cauldrons, bells, candles on cakes, item frames, armor
  stands, and scaffolding are all done, see their own entries elsewhere in
  this file (this bullet is old and understates current coverage). ~~banners,
  signs (editing)~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 2)** — see the
  dedicated signs/banners entry below (this batch's item 3). ~~lodestone+compass~~ done (`Lodestone.java`, binding +
  splitting a single vs. stacked compass; `scenarioLodestone` playtest
  coverage). Lightning rod done 2026-07-11 (Fable):
  tracked-rod registry + 128-block strike redirect + 8gt pulse, weighted
  pressure plates analog + copper bulbs also landed in the same
  redstone-parity pass.
- ~~Flower pots~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 3,
  `blocks/FlowerPots.java`, new file)** — decompile-verified against
  `FlowerPotBlock.useItemOn`/`useWithoutItem` (26.2, freshly decompiled, no
  cached copy existed before). No block-entity state at all: planting swaps
  the whole block straight to its `potted_<content>` variant (the real
  37-entry `POTTED_BY_CONTENT` map, reproduced from `Blocks.java`'s
  registration list) and un-planting swaps it straight back, so persistence
  rides the world's own block storage for free — no `StateAdapter` needed.
  The one genuinely non-obvious part: every pottable plant item (saplings,
  flowers, fungi, roots, azaleas, eyeblossoms) is ITSELF a real placeable
  block, so Minestom's `BlockPlacementListener` (its own dispatcher, not a
  vanilla-src class) never reaches `PlayerUseItemOnBlockEvent` for them —
  a block-material item skips straight from the block-interact event to
  normal block PLACEMENT once interact doesn't consume the click. Real
  vanilla's own dispatch is item-first-with-block-fallback; Minestom's is
  interact-first-with-item-fallback (`PlayerBlockInteractEvent` always
  fires first and can call `setBlockingItemUse(true)` to suppress
  everything after it) — so both planting AND un-planting are driven off
  that single guaranteed event, reading the held item directly off
  `player.getItemInHand(hand)`, reproducing vanilla's full outcome table
  (empty pot + valid plant -> plants; empty pot + anything else -> no-op;
  filled pot + ANY valid plant (matching or not) -> pure no-op, real
  vanilla's `CONSUME`, does NOT extract; filled pot + anything else
  including empty hand -> extracts). Not modeled:
  `POTTED_OPEN_EYEBLOSSOM`/`POTTED_CLOSED_EYEBLOSSOM`'s random-tick
  day/night auto-toggle (a biome `EnvironmentAttributes.EYEBLOSSOM_OPEN`
  cosmetic swap, unrelated to planting/unplanting itself). 8 new PlayTest
  checks (`scenarioFlowerPot`), covering the full outcome table including
  the block-material dispatch-shortcut case (a potted sapling still
  extracts correctly rather than being swallowed by normal block
  placement).
- ~~Archaeology (brush + suspicious_sand/suspicious_gravel)~~ **Done
  2026-07-17 (Sonnet 5, Tier 3 batch 4, `blocks/Archaeology.java`, new
  file)** — decompile-verified against `BrushItem`/`BrushableBlock`/
  `BrushableBlockEntity` (26.2, freshly decompiled, no cached copy existed
  before). The engine gap: real vanilla's `onUseTick` is a per-tick callback
  on the held item while a use-animation is active; Minestom exposes no such
  hook (same gap `Crossbow.java` already documents for its own charge
  timing), so the 10-brush-strokes-to-reveal cadence is driven by a global
  per-tick poll over every in-progress brush session's
  `player.isUsingItem()`/`getCurrentItemUseTime()` — the real
  `timeElapsed%10==5` gate (`timeElapsed = getUseDuration-ticksRemaining+1`)
  re-derived against Minestom's up-counting timer as
  `getCurrentItemUseTime()%10==4`. A second, more specific engine wrinkle: a
  BLOCK-targeted right-click (aiming at a suspicious block) routes through
  Minestom's `BlockPlacementListener`/`PlayerUseItemOnBlockEvent`, which —
  unlike the AIR-click path `UseItemListener` handles — never starts the
  engine's use-timer on its own; `Archaeology.useOnBlock` starts it manually
  (`Player#refreshItemUse`/`refreshActiveHand`, the exact two calls
  `UseItemListener` itself makes internally) to mirror vanilla's own
  `BrushItem.useOn` calling `player.startUsingItem(hand)` explicitly. Ported:
  the real completion-state thresholds (0/1-2/3-5/6-9 strokes -> `dusted`
  blockstate 0-3), the 10gt per-stroke cooldown, the 40gt-idle-then-4gt decay
  (`checkReset`, brushCount -= 2 per step), loot resolved once on the FIRST
  successful stroke and cached (not re-rolled), the exact vanilla quirk that
  `hurtAndBreak(1)` on the brush only fires on the stroke that COMPLETES the
  reveal (`brush()`'s own return value gates it, decompile-verified — not
  once per stroke), and the real drop-in-the-hit-direction position + a
  10-30-count `split` (practically always the whole roll, since every real
  archaeology table's pool yields exactly one stack). New bundled data:
  `loot_archaeology.json` (`scripts/extract_vanilla_data.py` extended — all 6
  `data/minecraft/loot_table/archaeology/*.json` tables, `--validate`
  clean) + `LootTables.archaeology(idPath)`. Falling (BrushableBlock
  implements Fallable) rides the existing gravity-block system
  (`blocks/BlockRules.java` — suspicious_sand/suspicious_gravel added to its
  whitelist) rather than vanilla's own unconditional per-2-tick check, same
  event-driven-on-neighbor-change simplification every other gravity block
  in this project already has. Persisted via a new `StateAdapter` (brush
  count/timers as restart-relative deltas, the `Breeding.cooldownTicksRemaining`
  technique; pending/resolved loot). Not modeled: the continuous
  look-direction raycast vanilla re-evaluates every tick to cancel brushing
  the instant a player looks away (this project captures position+face once
  at click time and only re-validates "is this still a suspicious block"
  each poll — a player can look elsewhere mid-brush without interrupting it,
  a real behavioral difference, not a hidden one); dust/falling-block
  particles (client-visual). **Worldgen does NOT place suspicious_sand/
  suspicious_gravel anywhere yet** (desert pyramids, desert wells, ocean
  ruins, trail ruins all should carry them in real vanilla) — deliberately
  out of scope per this batch's "no worldgen changes" instruction; this file
  is the reusable subsystem (`registerLoot`, the same pending-pos-&gt;table
  shape `Containers.registerLoot` already uses for structure chests),
  exercised in tests by registering loot directly rather than through a
  structure placement. 10 new PlayTest checks (`scenarioArchaeology` — full
  brush-out with real loot dropped and picked up, exact-1-durability cost, a
  no-loot-registered block still turns to sand/gravel but drops nothing, and
  abandoned progress decaying back to 0) + 3 SelfTest checks on the bundled
  loot data itself.
- TrialChambers.java (mine, for the record) — not modeled: ominous item
  spawner drips, per-mob ominous equipment loot tables, spawn-potential custom
  NBT (slime size etc.), vault client display-item cycling/connected-player
  packets, decorated pots in chambers, dispenser traps in chambers, heavy
  core/mace, breeze projectile deflection (breeze should destroy incoming
  arrows), trial explorer map in vault loot (map item is a dead item here). (M)
  ~~Persistence~~ **done 2026-07-12 (Sonnet)** — was session-scoped (a
  restart came back inert); SPAWNER_DEFS/VAULT_DEFS need no persistence of
  their own (this project never saves raw chunk/block data — every chunk
  regenerates deterministically from the seed, re-deriving them naturally
  the same way Containers' structure-loot registry does), but SpawnerData/
  VaultData plus the block's own trial_spawner_state/vault_state/ominous
  properties do, since regeneration would otherwise reset those to the
  template default. World-age-relative fields (nextMobSpawnsAt, etc.) are
  stored as deltas and re-anchored at restore, the same technique
  Breeding.cooldownTicksRemaining already uses. currentMobs (live entity
  ids) is intentionally dropped — the state machine already tolerates an
  empty one correctly, matching this project's "in-flight state, acceptable
  loss" precedent elsewhere. rewardedPlayers and itemsToEject ARE fully
  persisted (real vanilla's one-unlock-per-player guarantee must survive a
  restart, and an already-unlocked reward is genuinely earned, not
  in-flight). 2 new scenarioTrialChamber checks (spawner cooldown state and
  vault unlock-tracking both survive a save/wipe/reload), 3/3 clean reruns.

## mobs/

- Missing hostile mobs entirely (no factory/case): **cave_spider** (mineshaft
  spawners are also unimplemented — VStructureManager:1101 defers the spawner
  block), ~~silverfish (infested blocks too)~~ (done 2026-07-12, Fable — see
  the Silverfish entry below), endermite (ender pearl 5% spawn),
  vex exists only as evoker summon (fine), ~~warden + skulk shrieker
  pipeline~~ (done 2026-07-12, Fable — see the Warden entry below),
  piglin_brute (bastion), zoglin (hoglin portal conversion), illusioner,
  giant/unused. 26.x additions to check against the jar's entity registry:
  creaking (+ creaking heart), copper golem?, happy ghast?, parched (exists!),
  nautilus (exists as water animal). (each S-M given the factory pattern)
- **Happy Ghast (mobs/ai/HappyGhastMob.java, 2026-07-12, Fable)** — the
  rideable flying mount, ported from decompiled HappyGhast.java over
  Minestom's native passenger API: harness equip/strip (any *_harness item
  onto BODY, sneak+shears removes), up to 4 passengers, first-passenger
  steering (strafe + pitch-projected forward with the backward -0.5x rule,
  jump +0.5 up, 3.9xFLYING_SPEED scaling, 0.91 flying friction, 8%/tick yaw
  ease, half-pitch), player-on-top still-platform (10gt timeout + 60gt
  grace), ghast-drift idle, continuous heal (600gt / 20gt in rain), 20 HP.
  Movement is velocity-only while ridden (the HANDOFF-flagged Navigator+
  passenger breakage never engages). Not modeled: per-seat passenger
  attachment offsets (riders share the client's default point), baby
  ghastling (brain AI, 0.2375 scale, dried-ghast rehydration pipeline),
  snowball tempt goal, leash quad-offsets/elasticity, home-restriction
  radius, natural spawning, cloud detection for fast heal.
- **Creaking + Creaking Heart (mobs/ai/CreakingMob.java +
  blocks/CreakingHearts.java, 2026-07-12, Fable)** — full port of
  Creaking/CreakingAi + CreakingHeartBlock/BlockEntity/State: heart state
  machine (uprooted/dormant/awake on a 20+rand(5) ticker, matching-axis
  pale-oak log requirement), one linked protector (5×(±16,±8)
  not-on-leaves spawn, unlink at day/34-blocks/player-stuck),
  freeze-under-gaze (cone + LOS approximation of isLookingAtMe's
  0.5-tolerance three-height check; carved-pumpkin disguise honored
  post-activation), damage redirect to the heart's 100gt hurt call with
  interpolated sounds and 2-3 resin clumps (BFS depth 2/64 over pale-oak
  logs, multiface accumulation, waterlogged on source water), 45gt
  teardown, distance-scaled comparator, natural-heart 20-24 XP.
  Simplifications: teardown/trail particles and eye-flicker not sent
  (client visuals), CREAKING_ACTIVE modeled as the standard 13000-23000
  night window (vanilla reads the day timeline), hearts are session-scoped
  like TrialChambers (placement/test tracking only — no worldgen pale
  gardens yet, no Anvil reload re-link), body-rotation control not ported.
- **Slime/magma cube sizes + split-on-death (VanillaMobs.slimeLike +
  Combat.death + LootTables size predicate, 2026-07-12, Fable)** —
  decompile-verified Slime/MagmaCube setSize: HP size², speed 0.2+0.1·size,
  attack size (magma folds getAttackDamage's +2 into the attribute — same
  melee result, but attribute readers see size+2 where vanilla shows size),
  magma armor 3·size, 0.52·size cube hitbox + SlimeMeta, XP = size,
  finalizeSpawn roll (1<<rand(3), bump chance 0.5×regional special
  multiplier — the real DifficultyInstance formula, no approximation),
  Slime.remove split (2+rand(3) children of size/2 on the parent-width
  quadrant grid, +0.5y, random yaw; size 1 terminal), vanilla jump cadence
  (10+rand(20), ×4 magma, ÷3 aggressive), tiny-slime-harmless vs
  tiny-magma-bites, and the loot tables' type_specific.size predicate wired
  through Combat.death (slimeballs only from size-1 slimes, magma cream only
  from size-2+). Simplifications: touch damage on a 20gt per-mob cadence
  (approximates the victim hurt-immunity window this project doesn't model);
  hop velocity is the pre-existing fixed approximation (no size-scaled magma
  jump height, no SlimeMoveControl yaw easing); no squish/landing particles
  or _SMALL sound variants (client visuals); split children don't inherit
  custom name/persistence flags (no name tags in this project); no
  slime-chunk seeding or swamp surface-spawn chance (natural slime spawning
  itself is the spawner's existing biome roll); frog-predation loot entries
  inert (damage_source_properties source_entity predicates evaluate false —
  no kill attribution; this pass also FIXED the pre-existing hardcoded-true
  that made magma cubes drop froglights instead of magma cream and slimes
  always drop a flat frog-predation slimeball).
- **Silverfish + infested blocks (VanillaMobs.silverfish +
  blocks/InfestedBlocks.java, 2026-07-12, Fable)** — decompile-verified
  Silverfish/InfestedBlock/InfestedRotatedPillarBlock: 8 HP / 0.25 speed /
  1 attack factory with wake-up-friends (entity-attributed damage arms a
  one-shot ~20gt countdown, then the X/Z ±10, Y ±5 per-axis-outward scan
  destroys infested blocks — no drops, empty tool fails the silk gate —
  releasing one fresh silverfish each, 50% stop per find) and
  merge-with-stone (idle 1-in-10 per-tick roll ≈ vanilla's 1-in-5 per
  every-other-tick evaluation; one random direction from body center;
  host converts to its infested variant, deepslate keeps its axis, mob
  discarded). Breaking any of the 7 infested variants without silk touch
  spawns one silverfish at the block center; the bundled infested_* loot
  tables already carry the silk-gated host-item drop. Simplifications:
  no mobGriefing gamerule exists project-wide, so vanilla's griefing-off
  fizzle branch (revert block, no spawn) isn't modeled; explosions don't
  release silverfish (Explosions.java has no per-block spawnAfterBreak
  hook); the always_triggers_silverfish magic-damage tag isn't wired
  (potion "magic" damage doesn't arm the wake goal — entity-attributed
  damage only); no FloatGoal/powder-snow-climb counterparts; the
  getWalkTargetValue 10.0 loiter-near-infestable-stone bias not ported;
  infested destroy-time halving + flat 0.75 blast resistance not modeled
  (block hardness comes from Minestom's registry); no natural spawning
  (vanilla only spawns them from spawners/infested blocks; stronghold
  portal-room spawners are themselves still inert — see worldgen);
  spawn-poof particles skipped (client visual).
- **Warden (mobs/ai/WardenMob.java, 2026-07-12, Fable)** — full port of
  Warden/WardenAi/AngerManagement/AngerLevel + the behavior/warden package
  as one explicit state machine over VBrain navigation; summoned by the
  shrieker chain (Vibrations.tryRespond → WardenMob.trySummon, the
  SpawnUtil 20×(±5,±6) ON_TOP_OF_COLLIDER walk-down) or /summon-equivalent
  Mobs.spawn("warden"). Deliberate simplifications: digging/emerging
  invulnerability is total (vanilla exempts BYPASSES_INVULNERABILITY
  damage types); no sonic-boom particle trail or digging block particles
  (client visuals — no particle idiom in the codebase yet); anger is
  session-scoped (vanilla persists suspect UUIDs in NBT — ties into
  docs/PERSISTENCE.md); navigation is the shared VPathfinder rather than
  vanilla's warden-specific XZ-weighted PathFinder subclass; touch-anger
  approximates doPush with a 1.5-block nearby sweep; the sniff/emerge
  ambient-sound and heartbeat cadences are client-driven via WardenMeta
  anger sync only. Sonic boom damage rides DamageType.SONIC_BOOM (armor
  bypass follows the bundled damage-type tags).
- **Taming/mounts (mobs/Taming.java, mobs/Riding.java, mobs/Steering.java,
  mobs/Leashing.java, mobs/NameTags.java, 2026-07-15, Sonnet 5)** —
  MASTERPLAN §3 Tier 2's L item, decompile-verified against TamableAnimal/
  Wolf/Cat/AbstractHorse/Horse/AbstractChestedHorse/Donkey/Mule/SkeletonHorse/
  ZombieHorse/Pig/Strider/ItemBasedSteering/Leashable/LeashFenceKnotEntity/
  NameTagItem (26.2, all freshly re-decompiled for this pass — the cached
  copies predated the 26.2 bump). Landed in three commits:
  - **Wolves + cats**: bone/fish taming is a flat 1-in-3 roll per feed (no
    growing "trust" meter — that's a Bedrock-ism, not real Java behavior),
    tamed wolves jump 8→40 max health, sit/collar-dye/feed via Minestom's
    native `WolfMeta`/`CatMeta`/`TameableAnimalMeta` (owner UUID, sitting,
    tamed all synced natively — no custom tags needed), `mobs.ai.Goals`
    gained `SitWhenOrdered` + `FollowOwner` (with the real
    TELEPORT_WHEN_DISTANCE_IS_SQ=144 owner-teleport), and wolf owner-defense
    (assist the owner's hit, retaliate against a hit the owner takes) is
    wired as two extra `EntityDamageEvent`/`EntityAttackEvent` listeners in
    Taming.java registered *after* Combat.java specifically so they see its
    cancellation decisions, rather than edits inside Combat's dense pipeline.
    Cats have no combat AI at all (matches vanilla — no HurtByTarget,
    no owner-defense goals in real Cat.registerGoals).
  - **Horse family**: taming-by-riding (`Goals.RunAroundLikeCrazy`, the real
    temper/maxTemper(100) roll every ~50 ticks, +5 temper per failed buck),
    saddling, donkey/mule chests (a plain `CHEST_3_ROW` cargo hold — Minestom
    has no horse-menu InventoryType and this project has no custom-slot-click
    menu framework to build vanilla's real 3x5+2 layout), feeding
    (wheat/sugar/apple/carrot +3 temper, golden carrot +5, golden apple +10,
    hay heals 20 with no temper — `AbstractHorse.handleEating` exactly), and
    player-steered riding (WASD with the real sideways-halved/backward-
    quartered factors, full-power jump on a tap rather than vanilla's
    charge-by-holding — no client charge-bar infrastructure exists here, a
    stated simplification not a silent fake). Horse x donkey breeding
    (golden carrot/apple love mode) produces a mule with
    `AbstractHorse.createOffspringAttribute`-exact inherited health/jump/
    speed, implemented self-contained in Riding.java rather than folded into
    Breeding.java's generic same-species pairing (a 3rd-species cross needs
    attribute inheritance Breeding.java has no concept of). Skeleton horses
    are never player-tameable by riding (matches real
    SkeletonHorse.mobInteract's unconditional PASS while untamed — they're
    normally already-tame via the lightning trap, itself still unmodeled,
    see below).
  - **Pig/strider saddles**: no taming needed to saddle either. Riding is
    forward-only, steered purely by where the rider looks (real vanilla
    `getRiddenInput` is a flat `(0,0,1)` — no strafe, no reverse, no jump),
    with carrot-on-a-stick / warped-fungus-on-a-stick boosting
    (`ItemBasedSteering.boost`: 140-980gt window, `1+1.15·sin(t/total·π)`
    factor, no restacking mid-boost, 1 durability per use).
  - **Leads**: attach/detach, fence-post `LeashFenceKnotEntity` re-homing
    (spawned once per block, reused on repeat clicks), and the real
    LEASH_TOO_FAR_DIST=12 (pull)/MAXIMUM_ALLOWED_LEASHED_DIST=16 (snap,
    drops a lead) distances as a plain velocity write each tick rather than
    vanilla's full spring/wrench/angular-momentum rope physics. **Engine
    gap, not a project simplification**: Minestom's animal entity metadata
    has no "leash holder" field at all (grep-confirmed against the
    decompiled Minestom 26.2 sources — only `LeashKnotMeta.IS_LEASH_HOLDER`,
    a marker on the knot itself), so no client ever renders the tether line
    to a leashed mob regardless of what this project does server-side.
  - **Name tags**: the rename step needed zero new code (Anvils.java's
    generic `DataComponents.CUSTOM_NAME` rename already covers any item,
    name tags included); applying one sets the custom name, makes it
    visible, and marks the mob persistent — this project's first
    "`Mob.setPersistenceRequired()`" equivalent, wired into
    `VNaturalSpawner.despawnTick` alongside the new tamed-mob persistence
    check (both must run *before* the peaceful-instant-despawn branch, since
    untamed cats have no natural-spawn TYPE_CATEGORY entry at all and would
    otherwise wrongly fall into the Cat.MONSTER default).
  - Test coverage: 3 new PlayTest scenarios (taming, riding, leashing/name-
    tags/steering), each covering the probabilistic taming rolls via the
    established "retry generously, ~30 attempts for a 1-in-3 roll" sampling
    convention (scenarioEquipmentDropChance's 8.5% roll is the precedent).
  - Not modeled (all stated above or here, not silently faked): wolf/cat
    body armor, wolf/cat variant textures and sounds, persistent-anger
    duration (a provoked wild wolf holds its grudge forever, no 20-39s
    timer), wolf/cat/donkey-solo breeding (only horse x donkey → mule is
    wired), horse rearing/eating animation state, foals following their bred
    mother, strider cold-shaking animation, skeleton-horse lightning trap
    (AUDIT: still open below), parrot taming (still open below), mob item
    pickup (still open below).
- ~~Missing passive/utility mob: bee (pollination/hive/anger)~~ **Done 2026-07-16
  (Sonnet 5, Tier 3 batch 2)** — `mobs/Bees.java` (decompile-verified against `Bee`,
  freshly re-decompiled for the 26.2 bump) + `blocks/Beehives.java` (against
  `BeehiveBlockEntity`/`BeehiveBlock`): pollination (BlockTags.BEE_ATTRACTIVE within
  Manhattan radius 5, 400+ hover ticks -> nectar, real 20%/tick early-exit roll),
  hive delivery (MIN_OCCUPATION_TICKS_NECTAR/NECTARLESS gate, honey_level +1 or +2
  (1% roll) capped at 5), anger + sting-once-then-die (real vanilla death formula:
  `timeSinceSting%5==0 && random.nextInt(clamp(1200-timeSinceSting,1,1200))==0` —
  deterministically fires at exactly tick 1200), campfire/soul-campfire sedation
  (CampfireBlock.isSmokeyPos ported: up to 5 blocks straight down, stops early at a
  smoke-blocking solid), shears/glass-bottle harvest at honey_level 5 (3 honeycomb
  — HARVEST_BEEHIVE's fixed loot count, hardcoded since it's a gameplay loot table
  this project's extractor doesn't bundle — or a honey_bottle), max-3 occupant
  storage. New bundled data: `block_map_colors.json` (`scripts/extract_map_colors.py`,
  465 direct + 147 DyeColor-family block->MapColor entries, `--validate` checked
  against the live MapColor.java decompile) — built for the maps item in this same
  batch but the extractor parses `Blocks.java` generically, no bee-specific
  dependency. Simplifications: a released bee is a fresh entity (no full NBT
  round-trip of the one that entered), front-blocked-release retry/eviction and the
  shared "one flower memory per hive" nicety are skipped, no day/night hive-return
  gate exists in the 26.2 decompile at all (`EnvironmentAttributes.BEES_STAY_IN_HIVE`
  defaults false with no biome override wired in this project — ported faithfully,
  not the commonly-assumed "bees fly home at night" behavior), hive/flower discovery
  is a periodic block-tag scan (no POI-manager range query in this project), no
  crop-growing (BeeGrowCropGoal).
- Missing passive/utility mobs: cat (village
  spawning, morning gifts, creeper repel — taming/feeding DONE, see above),
  skeleton_horse (trap exists? no —
  lightning trap missing), trader_llama (wandering trader spawns alone;
  vanilla brings 2 leashed llamas), ~~allay (item collection)~~ **done
  2026-07-17 (Sonnet 5, Tier 3 batch 3, `mobs/Allays.java`, new file)**,
  sniffer, tadpole
  (frog lifecycle), snow golem/iron golem BUILDING by players (pumpkin
  placement patterns — golems only spawn via commands/tests). (S-M each)
- **Allay (mobs/Allays.java, 2026-07-17, Sonnet 5)** — decompile-verified
  against `Allay.java`/`AllayAi.java` (26.2, already cached in vanilla-src
  from an earlier session but never ported into gameplay code before this
  pass), condensed from vanilla's Brain/Sensor/Behavior tree into one
  per-tick state machine (`Bees.java`'s "static holder + per-entity State
  map" shape, since this project has no generic Brain framework). Give/take
  (`mobInteract`): an empty-handed allay offered an item holds it and
  remembers the giver as its liked player; approached empty-handed with the
  item held, it gives the item back, drops any carried extras, and forgets
  its liked player. Item collection (`wantsToPickUp`/`InventoryCarrier.
  pickUpItem`): with an item already in hand, it collects matching dropped
  items (same material AND matching `PotionContents` —
  `allayConsidersItemEqual`) within reach into a single carry slot, then
  flies them to a deposit target — a liked note block if one is still
  liked (real 1024-block/600-tick-cooldown gate,
  `shouldDepositItemsAtLikedNoteblock`) else its liked player (real
  64-block/survival-or-creative gate, `getLikedPlayer`) — throwing them
  once close enough (`GoAndGiveItemsToTarget`). Note-block hearing rides
  the existing `Vibrations.emit` tap system as a new hook alongside the
  warden one (`Vibrations.emit`'s note_block_play case ->
  `Allays.hearNoteblock`, the real 16-block `VibrationUser` listener
  radius), not a new vibration subsystem. Dancing: idling near an actively
  playing jukebox (`Jukebox.isPlaying`, real 10-block `JUKEBOX_PLAY`
  notification radius, periodic 20-tick scan when idle) starts a dance
  (Minestom's native `AllayMeta.dancing`); duplication: offering an
  amethyst shard (`ItemTags.DUPLICATES_ALLAYS`, bundled in
  `tags_item.json`) to a dancing allay with the real 6000-tick cooldown
  ready spawns a twin at the same position, both landing on the shared
  cooldown (`duplicateAllay`). Wired into `Mobs.spawn("allay", ...)` and
  `Bootstrap.java`. 14 new PlayTest checks (`scenarioAllay`), 14/14 clean.
  Simplifications (stated, not silently faked): flight is direct
  velocity-to-target steering, the same idiom Bees/HappyGhast/ghast/
  phantom already use (no A*-ish `AirRandomPos` sampling — this project's
  ground `VPathfinder` doesn't cover 3D flight); item-pickup cooldown is a
  short fixed 2-tick same-tick-reentry guard rather than vanilla's exact
  `ITEM_PICKUP_COOLDOWN_TICKS` memory value (that value lives in
  `GoToWantedItem`/`Mob` base-class internals not decompiled for this
  pass — the PURPOSE, not re-grabbing what was just set down, is
  preserved); deposit throws happen the instant the allay is within range
  rather than vanilla's real 20-tick `GIVE_ITEM_TIMEOUT_DURATION` travel
  window; no heart/amethyst-chime particles (this project has no particle
  idiom yet, same note as Warden's sonic boom); no
  `CriteriaTriggers.ALLAY_DROP_ITEM_ON_BLOCK` advancement (no advancement
  system in this project). State (liked player/noteblock, carried extras,
  dancing) is session-scoped like Bees' own hive/flower memory — a restart
  resets an allay's relationships but not its identity or held mainhand
  item (that rides the generic mob-equipment snapshot in
  `RegionStore.collectMobs`, unchanged by this pass).
- ~~No taming anywhere (wolves/cats/parrots/horses), no horse riding/saddles, no
  leads, no name tags (despawn suppression)~~ — DONE 2026-07-15 for
  wolves/cats/horses/leads/name tags, see above; parrot taming still open (S).
  No mob item pickup except
  villagers (zombies canPickUpLoot rolled but unused). (M-L)
- ~~VanillaMobs.java:656 — phantom bounded to size 0 (6 dmg); vanilla size
  scales with insomnia. Also no insomnia/phantom natural night spawner at
  all — phantoms only via test/summon~~ **DONE 2026-07-15 (Sonnet 5,
  `mobs/PhantomSpawning.java`, new file)** — decompile-verified against
  `PhantomSpawner`/`Phantom` (26.2): a per-world 60-119s countdown checks
  every non-spectator player at/above sea level with a clear sky view,
  rolls the real regional-difficulty gate (`DifficultyInstance.isHarderThan`,
  already ported as `Difficulty.effectiveAt`), then the real insomnia roll
  against a per-player "ticks since rest" counter (this project has no
  general player-stats system, so it's tracked ad hoc, reset by
  `Beds.interact` on a successful sleep) — mathematically impossible before
  72000 ticks awake. On a pass, 1 to (difficulty ordinal + 1) phantoms spawn
  20-34 blocks up. **The size-scales-with-insomnia part of this note was
  stale**: this decompile shows `Phantom.finalizeSpawn` unconditionally
  resets size to 0 on every natural spawn — real vanilla no longer scales
  phantom size at all (an older-version behavior), so the existing
  size-0-only (6 damage) mob stats already matched real vanilla and needed
  no change. Not modeled: Nether/End phantom spawning (this spawner only
  runs on the overworld — `.start(overworld)`, matching WeatherCycle/Snow/
  Lightning's existing precedent) and the `SPAWN_PHANTOMS` gamerule (no
  gamerule store in this project, same established precedent elsewhere).
- VanillaMobs.java:868 — iron golem: no village-population auto-spawn (vanilla
  panic-based golem summoning); no flower offering; no crack stages/repair with
  iron ingot. (M)
- Creeper: no cat/ocelot fear (no cats), charged-creeper head drops exist.
  Skeleton: no strafing movement (BowAttack fires standing), no sun-burn hat
  check (helmet blocks burn? verify sunburn checks equipment), no baby
  variants. Zombie: door breaking flag rolled (my difficulty pass) but no
  door-break AI; no chicken-jockey (5% baby ride). Drowned: no trident THROW
  (holds it, melee only?) — verify; no swim-up-at-night AI (admitted bounded).
  Enderman: no block pick-up/place, no water damage, no teleport-on-projectile
  dodge (blink-on-hurt exists). Witch: verify drinking heal/fire-res potions
  mid-fight exists; witch raid participation. ~~Slime: split-on-death sizes
  (verify Slime handling — magma cube too)~~ (done 2026-07-12, Fable — see
  the Slime sizes entry below). Piglin: no bartering (gold ingot
  toss), no gold-armor aggro rules, no portal zombification (piglins_zombify
  attribute), no hoglin hunting. Ghast: fireball deflection by hitting it back
  (verify projectileHit handles ghast fireball reflect). (S-M each)
- Villagers: no sleep/schedules (canBreed sleep-half skipped — noted in
  VillagerFood), no trade restocking at job site (verify VillagerTrades
  restock), no profession-specific requestedItems beyond the picks-up tag, no
  baby villager growth into profession claim delay nuances. ~~no
  zombie-villager curing (golden apple + weakness), no villager→zombie-
  villager conversion on zombie kill~~ **DONE 2026-07-15 (Sonnet 5,
  `mobs/VillagerConversion.java`)** — decompile-verified against Zombie.
  killedEntity/convertVillagerToZombieVillager and ZombieVillager (26.2, both
  re-decompiled fresh): Normal 50%/Hard 100% conversion on any zombie-family
  kill (Husk/Drowned/ZombieVillager included, not just the base type —
  killedEntity is inherited, none of them override it), profession carried
  through both directions, real 3600-6000t cure timer (weakness + golden
  apple, NOT the enchanted one) with the iron-bars/bed speedup roll. Also
  ports the narrow slice of gossip/reputation this needs: a cure grants the
  curing player a real trade discount (floor(reputation*0.05) off the cost
  side, matching Villager.updateSpecialPrices) — the full gossip ledger
  (trade/hurt/killed events, decay, transfer, hero-of-the-village) is still
  not modeled, only the zombie-cure path, since a single cure already
  saturates both contributing gossip types' real caps (125 reputation is the
  steady state, not an approximation of it). Session-scoped: conversion
  timers and cure reputation aren't persisted (restart resets them), same
  precedent as breeding's IN_LOVE window/warden anger. **Real bug found and
  fixed en route (Combat.java): mob-vs-mob combat was entirely dead code** —
  the melee-damage branch required `target instanceof Player`, so no mob
  could ever actually damage another mob (a zombie could never kill a
  villager, an iron golem's own branch was the only exception). Real vanilla
  `Mob.doHurtTarget` is target-type-agnostic; the gate is now just
  `e.getEntity() instanceof EntityCreature`. This was a pre-existing gap the
  villager-conversion feature surfaced, not something conversion itself
  introduced — full playtest re-run clean (788/788, then 799/800 with one
  unrelated pre-existing flake — see HANDOFF) after the fix.
- ~~Raid.java:17 — bounded 3-wave raid started by command/bell proximity; no
  wave scaling by difficulty (wave count: Easy 3/Normal 5/Hard 7)~~ **DONE
  2026-07-15 (Sonnet 5)** — decompile-verified against
  `net.minecraft.world.entity.raid.Raid` (26.2): wave count now real
  `getNumGroups` (Easy 3/Normal 5/Hard 7), each wave's composition read from
  the real per-type `spawnsPerWaveBeforeBonus` tables (Vindicator/Evoker/
  Pillager/Witch/Ravager, indexed by wave number) plus the real random
  per-wave bonus-spawn roll (`getPotentialBonusSpawns`), and `Raid.start`
  now takes a Bad Omen level — above 1 adds one real bonus wave past the
  last normal one (`shouldSpawnBonusGroup`/`hasBonusWave`), though nothing
  in this codebase can currently pass anything above the default 1 (no
  patrol/Bad Omen potion chain exists yet, so that path is real and
  reachable but presently dormant — same underlying gap as "no Bad Omen
  from patrol captains" below). Still not modeled: raid bar percentage
  specifically from raider HP (this project's bar already tracks a coarser
  alive-count fraction), hero of the village, ravager riders, bell-ring
  glowing, and the patrol-captain Bad Omen trigger itself. (remaining: M)
- VNaturalSpawner — solid core; check: no cave/spawner mob costs (charge-based
  spawn potentials for soul sand valley etc.), no per-biome water ambient
  (fish schools), no persistent-after-nametag/tempt rules, wandering trader
  spawn uses flat 20-min timer (vanilla escalating 2.5%→7.5% odds per 24000t
  cycle with spawn attempts). (S-M)
- Combat.java — mob spawn-equipment **enchantments** never applied
  (populateDefaultEquipmentEnchantments: 0.25/0.5 x specialMultiplier chance,
  MOB_SPAWN_EQUIPMENT provider — difficulty threading exists now, needs an
  enchant-provider evaluator, M; enchantment_provider.json is bundled as of
  v0.18.0 — data/minecraft/enchantment_provider/*.json, incl. raid/** — but
  the EnchantmentsByCost/EnchantmentsByCostWithDifficulty/SingleEnchantment
  provider TYPES from vanilla-src/net/minecraft/world/item/enchantment/
  providers/ still need porting, decompiled but unused). ~~worn-equipment
  drop chances on death — mobs never drop their armor/weapons~~ **this note
  was stale — actually landed long before this audit entry was last touched
  (`dropEquipment`, 8.5% base + looting bonus, since commit 807f5ab); the
  only real gap was killedByPlayer being too narrow (only the LITERAL final
  hit, not real vanilla's 100-tick memory window / tamed-wolf-owner credit)
  — DONE 2026-07-15 (Sonnet 5): Combat.java now tracks a per-mob
  `LAST_HURT_BY_PLAYER` credit (decompile-verified against
  LivingEntity.resolvePlayerResponsibleForDamage) so a mob hit by a player
  (or that player's tamed wolf) still drops gear if something else finishes
  it off within 100 ticks. Still open: mob spawn-equipment enchantments
  above, and the "preserve" guaranteed-drop path (skeleton pumpkin heads
  etc. — no factory in this codebase sets it, isPreserved() always false).
  (S)
- Breeding.java — only same-species pair+item; no baby growth acceleration by
  feeding, no love-mode particles timing nuances, no per-animal breeding items
  beyond spec sets (mostly right), horses/llamas can't breed (no taming). (S)
- EnderDragonFight.java:17 — bounded fight: verify crystal healing beams,
  perching phase, breath attacks, dragon egg spawn + teleport-on-click,
  respawn-the-dragon crystal ritual. (M/L)
- path/VPathfinder — no door-opening path nodes (villagers can't use doors),
  no water-aware pathing for aquatic mobs (guardians ground-path, admitted).
  (M)

## survival/ + data/

- LootTables.java:17 — UPDATE 2026-07-14: "no enchantment system exists" is
  now stale — Enchants.java has one (EnchantmentDef/allDefs/weightedPick,
  v0.18.0), so enchant_randomly/enchant_with_levels loot-table functions
  (trial reward enchanted books, end city gear) are directly wireable
  against it now (candidates = allDefs() filtered by supportsItem()/
  inEnchantingTable()-equivalent per the function's own options, weighted
  pick same as the table); still unimplemented, just unblocked. set_potion
  works? (my trial tables roll POTION items — verify the potion component is
  applied, else they drop as water bottles). Fortune/silk-touch conditions
  work via tool passing. (M)
- Enchants.java — DONE 2026-07-14 (v0.18.0, MASTERPLAN §3 Tier 1 item 1):
  the enchanting economy engine, ported from decompiled EnchantmentHelper/
  EnchantmentMenu/AnvilMenu/GrindstoneMenu (26.2, vanilla-src/), built on the
  now-bundled data-driven enchantment JSONs (src/main/resources/vanilla/
  enchantment.json, item_enchantability.json, item_repairable.json,
  tags_enchantment.json — extract_vanilla_data.py extended, item component
  defaults pulled from the datagen "Default Components" report since
  data/minecraft/items/*.json doesn't exist in 26.2). Table: real per-player
  persisted enchantment seed (Persist.java), bit-exact bookshelf-power cost
  formula + hide-slot rule, seed-deterministic weighted offer selection
  (exclusive_set-aware), the buttonId+1 lapis/xp quirk (NOT the displayed
  cost), reroll-on-take. Anvil: raw-material repair (item_repairable.json)
  ALONGSIDE the existing same-item combine, real per-enchantment anvil_cost
  fees (not flat +1), rename via Minestom's real (if undocumented outside
  listener/AnvilListener.java) PlayerAnvilInputEvent, rename-only 39-cap
  exception, prior-work-penalty tax. Grindstone (new): disenchant keeps
  curses via tags/enchantment/curse.json, non-curse enchant xp refund at
  table min_cost (not anvil_cost), 5% durability-merge bonus (vs anvil's
  12%). 18 SelfTest checks (fixed-seed offer/cost determinism) + 3 new
  PlayTest scenarios (real block+event flow) + the existing anvil scenario
  corrected to match the real "price<=0 -> cost 0 even with tax" rule.
  Known simplifications (stated per rule 4, not silently faked): anvil block
  damage-on-use (12% chip chance) not modeled; rename-cost comparison uses
  only the stored custom name, not vanilla's resolved-default-name edge
  case; table candidate ordering is alphabetical (enchantment.json's jar
  listing order) rather than the real in_enchanting_table tag's declared
  file order — internally deterministic (same seed -> same result) but not
  bit-identical to a live vanilla server, since no differential oracle
  exists for this subsystem (unlike piston/worldgen). ~~Smithing table
  (netherite + trims), stonecutter, loom, cartography table: still none.~~
  **Stonecutter/loom/cartography table done 2026-07-17 (Sonnet 5, Tier 3
  batch 6)** — `blocks/Stonecutter.java`, `blocks/Loom.java`,
  `blocks/CartographyTable.java` (all new files), decompile-verified against
  freshly-decompiled `StonecutterMenu`/`LoomMenu`/`CartographyTableMenu`
  (26.2). Stonecutter registers all 319 bundled `stonecutting` recipes with
  Minestom's `RecipeManager`/`DeclareRecipesPacket` at startup (real
  vanilla's button UI is built client-side from its own recipe registry —
  without declaring them the client renders zero buttons regardless of
  server-side matching), matches by button-click index into
  `Recipes.stonecuttingFor`'s alphabetical-by-id-sorted list (documented
  approximation of true vanilla registration order — same class of gap as
  the enchanting-table candidate ordering above — but self-consistent with
  the client since it learns the order FROM this declaration). Loom sources
  its pattern-button lists straight from Minestom's own built-in
  `BannerPatternTags`/registry (real Mojang data, no server declaration
  needed there), carries a selection across a pattern-slot change by VALUE
  not index (LoomMenu.slotsChanged's `previousSelectablePatterns.get(...)`
  dance, ported faithfully), and confirms real vanilla's pattern-item
  reusability (LoomMenu's result-slot `onTake` only decrements the
  banner/dye slots, never the stencil). Cartography table's lock/zoom
  mutation is deliberately deferred to actual-take time, never
  menu-preview recompute (mirrors `MapItem.onCraftedPostProcess`'s real
  timing — an earlier draft of this batch mutated on preview and would
  have locked a map the instant a glass pane merely touched the slot,
  caught before landing); its one accepted gap versus real vanilla is that
  locking reuses the source map's own id instead of minting a fresh
  snapshot id (this project's map identity is `hash(centerX,centerZ,scale)`,
  not an insertion counter — see `Maps.tryLock`'s doc). Smithing table
  (netherite upgrade + the 18 armor-trim recipes) is the one station still
  unbuilt — recipe data for both is already bundled
  (`minecraft:smithing_transform`/`minecraft:smithing_trim` in
  `recipes.json`) and Minestom's `RecipeDisplay.Smithing` +
  `DataComponents.TRIM`/`ArmorTrim` exist, so the remaining work is UI
  wiring analogous to this batch, not data plumbing. (M-L)
- Potions.java — 13 effect cases handled on drink. ~~splash potion AoE scaling
  by distance, lingering clouds~~ — this note was stale: both are done, see
  `survival/ThrownPotions.java`'s own class doc (splash strength scales
  1-dist/4 within a 4-block reach; lingering clouds shrink and reapply on a
  cooldown). ~~tipped arrows~~ **Done 2026-07-17 (Sonnet 5)** — decompile-
  verified against freshly-decompiled `Arrow`/`SpectralArrow`/`AbstractArrow`/
  `ArrowItem`/`TippedArrowItem`/`SpectralArrowItem`/`BowItem`/
  `ProjectileWeaponItem` (26.2, no cached copies existed before): previously
  `Bow.consumeArrow`/`Crossbow.hasArrow`/`consumeArrow` only ever recognized
  plain `Material.ARROW`, so a tipped or spectral arrow couldn't even be
  nocked, and `Combat.projectileHit`'s arrow branch was gated to
  `EntityType.ARROW` only, so a dispenser-fired spectral arrow (a distinct
  entity type) dealt no damage and applied no effect at all — worse than "no
  effect on hit," a real pre-existing gap this pass surfaced rather than
  introduced. Now: `Bow.isArrowFamily` accepts all three materials as valid
  ammo (bow and crossbow both — the crossbow's real arrow is consumed at
  `load()` time but fired at `shoot()` time, so its material/potion identity
  rides the crossbow `ItemStack`'s own tags in between); a hit applies the
  carried potion (`Bow.POTION` tag, read off the fired item's
  `DataComponents.POTION_CONTENTS`) at the real bundled `tipped_arrow` item's
  `potion_duration_scale` of 0.125 (1/8 duration — reusing `Potions.apply`'s
  existing scale parameter, the same one splash/lingering already use), or a
  flat 200-tick Glowing for a spectral arrow (`SpectralArrow.
  doPostHurtEffects`, unconditional, no distance/scale factor). Dispenser-
  fired tipped arrows (`redstone/Redstone.java`, already spawning the correct
  entity type before this pass) now carry the same potion tag. Loot supply
  was already real and working (stray/bogged/parched drop `tipped_arrow` with
  `set_potion`, trial chamber loot tables carry them too — `LootTables.
  applyItemFunctions`'s `set_potion` case, unrelated pre-existing code,
  verified still correct) — the missing piece was purely the fire/hit side.
  8 new PlayTest checks (`scenarioTippedSpectralArrows`), using a manually-
  dispatched `ProjectileCollideWithEntityEvent` rather than real flight
  timing to test `Combat.projectileHit`'s effect application in isolation
  (real projectile flight/collision is this project's own documented flake
  class — trident riptide, crossbow piercing, etc. — irrelevant to what this
  change actually touches). ~~Not modeled: the `crafting_imbue` special
  recipe (8 arrows + a lingering potion -> 8 tipped arrows)~~ **done
  2026-07-18 (Sonnet 5)** — decompile-verified against freshly-decompiled
  `ImbueRecipe` (26.2, `vanilla-src/net/minecraft/world/item/crafting/
  ImbueRecipe.java`): a fixed 3x3 shape (source ingredient in the exact
  center cell, material filling the other 8, no sliding/mirroring like a
  normal shaped recipe — real vanilla's `matches` requires the input to BE
  a 3x3, so it never matches the player's 2x2 grid), `assemble` copying the
  center slot's own `POTION_CONTENTS` onto the result. `Recipes.java` gained
  a dedicated `Imbue` record + `matchImbue` (the one recipe kind whose
  output isn't a static `ItemStack`, so it couldn't fold into the generic
  shaped/shapeless matcher), wired into `matchCrafting` ahead of the shaped
  table when the grid is a full 3x3. 3 new PlayTest checks
  (`scenarioTippedSpectralArrows`): yields 8 tipped arrows carrying the
  center potion's effect, rejects a non-material item in a surrounding
  cell, never matches a 2x2 grid. A creative-mode or Infinity-enchanted shot
  still always fires a plain arrow
  regardless of what's nocked (real vanilla reads the type without consuming
  it in both cases — this project's pre-existing simplification, unchanged
  by this pass). Still missing: turtle master, slow falling (check),
  levitation via potion, bad omen bottle (ominous bottle item — trial
  chambers ominous path currently only reachable via /effect-style
  application in tests), luck, darkness, oozing/weaving/infested/wind-charged
  (26.x effects). Absorption/golden apples still open — the ABSORPTION
  potion effect mechanic itself now exists (mobs/Totems.java grants it
  generically via player.addEffect), just not golden apples granting it on
  eat. ~~Totem of undying?~~ done, see the Items entry's spyglass/totem
  note. (M)
- Experience.java — check: xp from trading/breeding/fishing (vanilla grants),
  level curve beyond 30ish, enchanting costs deduction. (S)
- Survival.java — exhaustion sources incomplete (sprint-jump 0.2, swim 0.01/m,
  regen 6.0 exists, attack 0.1 exists, damage-taken exhaustion missing?);
  saturation-fast-regen (foodLevel 20 + saturation → 1hp/10t, vanilla has it —
  minecom only has the 80-tick path + my peaceful path; the saturation path at
  parity with FoodData.tick is MISSING: vanilla heals every 10 ticks spending
  saturation when food==20). (S, decompile already in vanilla-src/food/)
- WeatherCycle.java — flat 1%-per-100t rain start, 3-8 min duration; vanilla:
  clear 12k-180k ticks, rain 12k-24k, thunder separate 3.6k-15.6k within rain.
  Persisted "raining" but not remaining durations. (S)
- Lightning.java:75-80 — no lightning-rod redirection (needs a tracked-position
  registry — the file says "logged in docs/HANDOFF.md" but no such HANDOFF
  entry exists; this audit is now that log); no fire starting from strikes; no
  skeleton-horse trap; no mobs-set-on-fire. (S/M)
- Bow/Crossbow/Trident — solid; check infinity arrows, crossbow firework
  loading, offhand arrow priority. Trident.java:30/98 admits riptide bounds.
  (S)
- Fishing.java — no open-water check? no luck-of-the-sea/lure timing shifts
  (check), no treasure requiring open water. (S)
- Items: ~~no elytra/firework flight~~ **DONE 2026-07-15 (Sonnet 5,
  `survival/Elytra.java`, new file)** — decompile-verified against
  `LivingEntity.canGlide`/`updateFallFlying`/`checkFallDistanceAccumulation`
  and `FireworkRocketEntity`/`FireworkRocketItem` (26.2, freshly decompiled):
  Minestom's raw `ClientEntityActionPacket` handler sets `flyingWithElytra`
  unconditionally with none of vanilla's real gating, so this project's own
  listener re-validates it both at deploy time and every tick after
  (airborne, not riding, no Levitation, an unbroken chestplate-slot item
  with the real `minecraft:glider` component); durability wears 1 every 20
  ticks of gliding; using a firework rocket while gliding applies the real
  per-tick boost-toward-look-direction impulse for the rocket's real
  lifetime (10×flightDuration + two small random rolls); fall distance caps
  the same way real vanilla's does (`checkFallDistanceAccumulation`, capped
  to <=1 whenever not in a fast vertical drop — translated into this
  project's peak-height fall tracking as capping the tracked peak to at
  most 1 block above the current position). Not modeled: exploding-firework
  damage when a star-carrying rocket detonates while attached (this project
  doesn't track the "attached but no longer boosting" tail state real
  vanilla keeps until the rocket's own timer runs out — a plain
  flight-duration-only rocket, the actual point of boosting, never carries
  stars anyway), the client-side glide flight path itself (pitch-to-speed
  conversion/lift/drag — this project targets real vanilla clients, which
  already run that physics locally and just report position, so only the
  parts a client can't authoritatively decide were modeled server-side).
  no eye of ender flight (locatestronghold
  command instead), no
  shields BANNER patterns (see banners entry below). (each S-M)
  ~~no spyglass~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 5,
  `survival/Spyglass.java`, new file)** — decompile-verified against
  `SpyglassItem` (26.2, freshly decompiled): real vanilla's spyglass is
  almost entirely client-visual (zoom FOV, scope overlay, held-to-eye
  pose), and Minestom's own raw-packet handler already special-cases
  `Material.SPYGLASS` for the animation/1200-tick duration the same way it
  special-cases `Material.GOAT_HORN` — so the only product-owned behavior
  is two sounds, `item.spyglass.use` on raise and `item.spyglass.stop_using`
  on release (early or at full duration), and (a real, easy-to-miss
  vanilla detail confirmed by decompiling `Entity.playSound`/`Player.
  playSound`/`ServerLevel.playSeededSound`) neither is heard by the acting
  player themselves — real vanilla's `Player.playSound` broadcasts to every
  OTHER nearby player, excluding the source. 5 new PlayTest checks
  (`scenarioSpyglass`, using a second connected player to observe the
  exclusion).
  ~~no totem~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 5,
  `mobs/Totems.java`, new file)** — decompile-verified against
  `LivingEntity.checkTotemDeathProtection` and `DeathProtection.
  TOTEM_OF_UNDYING` (26.2, freshly decompiled): main hand then off hand
  checked for `DataComponents.DEATH_PROTECTION`, consumed on a lethal hit
  (skipped for `bypasses_invulnerability` damage, e.g. out-of-world/kill),
  health set to 1.0, the item's own `death_effects` list applied generically
  (not hardcoded — real vanilla's default is clear-all-effects +
  Regeneration II/900t + Absorption II/100t + Fire Resistance I/800t), and
  entity status 35 broadcast (drives the totem particle burst + sound
  client-side, matching `broadcastEntityEvent(this, 35)` — nothing else
  needs to play a sound server-side). Registered right after `Combat.
  register` so it sees `EntityDamageEvent`'s amount AFTER Combat's armor/
  resistance/enchantment reduction, matching real vanilla's order. 9 new
  PlayTest checks (`scenarioTotem`: main-hand save, off-hand save, no-totem
  death, out-of-world bypass, exact effect amplifier/duration values).
  Leaves "Absorption from golden apples" open (Potions.java's own entry) —
  this closes the Absorption *potion effect* mechanic generally (usable by
  anything that grants it, including the totem), not golden apples
  specifically granting it on eat, which is still unimplemented.
  ~~no bundles~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 4,
  `survival/Bundles.java`, new file)** — decompile-verified against
  `BundleItem`/`BundleContents`/`BundleContents.Mutable` (26.2, freshly
  decompiled). Vanilla's `overrideStackedOnOther`/`overrideOtherStackedOnMe`
  hooks let a bundle intercept the generic inventory-click pipeline
  regardless of which menu it's in; this project has no per-item
  click-override hook, so a global `InventoryPreClickEvent` listener checks
  whether the cursor or the clicked slot holds a bundle and, if so, cancels
  the default click and runs the insert/remove logic itself (the same
  "cancel + hand-roll" shape `blocks/Crafting.java` already uses for its
  result slot). Capacity rides `DataComponents.BUNDLE_CONTENTS` — a native
  Minestom component (a plain `List<ItemStack>`) — so the client already
  renders the fullness bar and contents tooltip with zero extra wiring, and
  persistence is free too (`Persist.java`'s item round-trip already walks
  every component via `ItemStack#toItemNBT`). Weight math (1/maxStackSize
  per item, 1/16+nested-weight for a bundle-in-a-bundle, shulker boxes
  categorically rejected) is re-derived as exact-integer "weight units out
  of 64" instead of vanilla's `Fraction` type, since every real stack size
  (1/16/64) divides 64 evenly — an exact re-encoding, not an approximation.
  Not modeled: the sub-item click-to-select-before-removing UI (needs the
  client to report which icon inside the bundle's own tooltip grid was
  clicked, a precision Minestom's click protocol doesn't expose — the
  un-selected fallback, index 0/most-recently-added, is used unconditionally,
  which is itself real vanilla behavior, not an approximation); holding
  right-click to continuously spill contents (vanilla's `onUseTick`, the
  same per-tick-callback engine gap `Archaeology.java`'s own entry below
  documents) collapses to a single tap-to-pop-one-entry. 13 SelfTest checks
  (pure insert/remove/weight math) + 12 PlayTest checks (`scenarioBundle` —
  the real click-event integration in both directions, shulker rejection,
  the bare-right-click drop).
  ~~no goat horns~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 4,
  `survival/GoatHorns.java`, new file)** — decompile-verified against
  `InstrumentItem`/`Instruments` (26.2, freshly decompiled). Turned out
  mostly already built: Minestom's own raw-packet handler (`UseItemListener`)
  special-cases `Material.GOAT_HORN` directly and resolves the held item's
  `DataComponents.INSTRUMENT` against its own built-in
  `MinecraftServer.getInstrumentRegistry()` — pre-populated with the real 8
  tunes (ponder/sing/seek/feel/admire/call/yearn/dream, uniform 7.0s
  duration/256-block range, matching `Instruments.bootstrap` exactly) — and
  fires the use-animation with the correct duration automatically, no
  per-item duration wiring needed the way `Crossbow.java`'s variable
  quick-charge duration does. What the engine does NOT do: play the sound
  (real vanilla plays it immediately on click, inline inside `use()`, before
  the use-animation even starts — not on finish) and apply a cooldown; both
  land on `PlayerUseItemEvent`, the earliest point matching vanilla's own
  timing. Cooldown is per-player keyed by material only (not by tune): real
  vanilla's `ItemCooldowns` defaults a stack's cooldown group to its base
  `Item` identity, and all 8 tunes share the single `minecraft:goat_horn`
  item, so blowing any tune locks out every tune for the same duration. Not
  modeled (no acquisition pipeline exists to need it yet): goat ramming a
  wall for a rare random-tune drop (a separate mobs/ gap — `withTune` is
  exposed as the entry point a future ramming implementation would call, the
  same shape `InstrumentItem.create` is in vanilla), the
  `CriteriaTriggers`/advancement hook, the `GameEvent.INSTRUMENT_PLAY` sculk
  vibration emission (no matching tap in `Vibrations.emit`'s table for this
  event kind). 5 PlayTest checks (`scenarioGoatHorn` — first blow succeeds,
  immediate re-blow blocked, a different tune still shares the cooldown,
  cooldown expiry, and an unresolvable instrument id is inert not a crash).
  ~~no maps~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 2)** —
  `survival/Maps.java` (decompile-verified against `EmptyMapItem`/`MapItem`/
  `MapItemSavedData`, 26.2) + `data/MapColors.java` (the ported `MapColor`
  fixed table, id/RGB/brightness, selftest-verified) + `block_map_colors.json`
  (`scripts/extract_map_colors.py`, parses the decompiled `Blocks.java`
  registration calls directly — 612 block -> MapColor entries, `--validate`
  checked against the live `MapColor.java` decompile). Empty map -> filled map
  on use (centred + snapped to the scale's map-area grid, matching
  `MapItemSavedData.createFresh`'s exact formula); identity (id/scale/centre/
  tracking flags) rides on the item's own tags rather than a separate saved-
  data file this project doesn't have, with the map id itself derived from
  `hash(centerX, centerZ, scale)` instead of vanilla's insertion-order counter
  (documented consequence: two maps created over the identical area+scale
  share one color buffer, a deliberate simplification, not vanilla's own
  behavior); color sampling (base color x brightness — water-depth or height-
  delta shading, the real per-pixel formula) on a periodic full-radius pass
  rather than vanilla's budgeted 1/16-columns-per-tick sweep (a bandwidth
  optimization with no behavioral difference to the eventual color); the
  holder's own player-marker decoration with real heading
  (`calculateRotation`'s non-Nether branch); zoom crafting
  (`MapExtendingRecipe`: filled map + 8 paper -> scale+1, capped at 4).
  Not modeled: fog-of-war beyond the held-scan radius, banner/item-frame/
  other-players' decorations, biome-preview (explorer) maps, the Nether's
  spinning-icon branch, at-scale>0 per-pixel averaging (samples one corner
  block instead of the full scale x scale footprint — exact at scale 0), and
  persistence of the color buffer across a restart (session-scoped; the
  item's own tags carry enough identity to rebuild it live on the next hold-
  tick, matching this project's other "cheap enough not to persist" gaps).
- ~~Signs (editing) + banners~~ **Done 2026-07-17 (Sonnet 5, Tier 3 batch 2)**
  — `blocks/Signs.java` (decompile-verified against `SignBlockEntity`/
  `SignBlock`/`SignText`/`StandingSignBlock`/`WallSignBlock`/`DyeItem`/
  `GlowInkSacItem`/`InkSacItem`/`HoneycombItem`, all freshly decompiled) +
  `blocks/Banners.java` (against `BannerBlockEntity`, plus the two
  crafting-grid special recipes `BannerDuplicateRecipe`/
  `ShieldDecorationRecipe`, recipes.json's already-bundled
  `*_banner_duplicate`/`shield_decoration`). Signs: front+back text
  independently tracked (4 lines/color/glow each), front-vs-back routed by
  the real angle formula (`isFacingFrontText` — the sign's own yaw vs. the
  ANGLE FROM THE SIGN TO THE PLAYER, not the player's own facing, a real
  vanilla quirk ported faithfully), placing a sign auto-opens the real
  client editor protocol (`OpenSignEditorPacket` -> client's
  `ClientUpdateSignPacket` -> Minestom's own `PlayerEditSignEvent`, both
  already built into Minestom and just wired up), `SignApplicator` items
  (dye sets color, glow ink sac/ink sac toggle glow, honeycomb waxes) only
  apply to a face that already has a message (`canApplyToSign`'s real gate
  — honeycomb is the one exception, always allowed), waxing locks out every
  further edit/dye/glow change, full persistence (front/back/color/glow/
  waxed) round-trips a save/wipe/reload. Banners: patterns captured from
  the placed item's `DataComponents.BANNER_PATTERNS` at place time (this
  project has no generic block-entity/item-component bridge, same pattern
  as DecoratedPot's held item), the two crafting-special recipes wired
  ahead of the generic shaped/shapeless matcher in `Crafting.java`
  (duplication needs the asymmetric consumption path — the source banner
  survives, only the one recipe in this project that doesn't uniformly
  consume every filled grid slot).
  Simplifications: the client-rendered block NBT this project attaches for
  sign text (`Signs.syncNbt`) is a best-effort vanilla-shaped compound not
  independently verified against a live client in this headless-playtest
  environment (the state machine itself — text/color/glow/wax routing,
  persistence — is what PlayTest verifies); no click-command execution on
  sign text (no rich-text click-event authoring path exists for players to
  create one); no hanging signs (a distinct chain-suspended block family);
  no banner custom name (`Nameable`); **the Loom UI/mechanic itself — the
  only way real vanilla lets a player ORIGINATE a pattern layer (choosing a
  pattern+dye combination with a live preview) — is not modeled at all**;
  the two crafting-special recipes above are real and correct once a
  patterned banner exists by some other means (loot, a command, or a
  future loom pass), matching this batch's own "if cheap" framing for the
  banner-on-shield ask.
  ~~no ender pearl teleport~~
  **DONE 2026-07-15 (Sonnet 5, `survival/EnderPearls.java`)** — decompile-
  verified against EnderpearlItem/ThrownEnderpearl (26.2, freshly decompiled,
  no cached copy existed before): 1.5-shoot-unit throw, teleport-on-land
  keeping the thrower's own look direction, 5 armor/knockback-bypassing
  damage (the bundled `bypasses_armor`/`no_knockback` tags already cover
  `minecraft:ender_pearl`, no special-casing needed), fall-distance-tracking
  reset on teleport (a new `Survival.resetFallTracking` — otherwise a pearl
  thrown from height into a low landing spot would wrongly charge fall
  damage too on the next ground contact), and the real 5% endermite spawn
  roll (outside Peaceful). No water-specific gate exists in the decompile at
  all (contrary to a common player myth) — fluids simply don't block flight.
  Not modeled: the zero-damage on-hit-entity "hurt" call (animation/
  invulnerability-timer only, no gameplay effect), the 32 landing particles
  (client visual), stasis-chamber chunk-ticket behavior (this project's
  chunk loading has no equivalent to vanilla's per-pearl force-load ticket
  system), and cross-dimension throws (collapses to a same-instance check).

## top-level / infra

- Commands.java — useful missing: /effect, /locate (structures generally),
  /setblock, /fill, /xp, /clear, /kill (targets), /teleport to player,
  /gamerule (naturalRegeneration/keepInventory/mobGriefing are all hardcoded
  behaviors right now), /seed, /whitelist. (S each)
- Persist.java — players: verify effects/xp/enderchest are in the "players"
  snapshot; add villager data, mob positions (see cross-cutting). Dimension of
  the player on save (nether/end logout probably respawns overworld). (M)
- Main.java — day/night driver only for overworld; nether/end share time? (S)
- Bootstrap.java — flat/playtest worlds skip Villagers.register: fine, but
  VillagerFood.start is therefore playtest-only-manual; production non-flat
  gets it via Villagers.register. OK — just noting the asymmetry.
- ~~PlayTest port collision with concurrent sessions~~ **done 2026-07-12
  (Sonnet)** — default changed from a fixed 25599 to 0 (OS-assigned
  ephemeral port); nothing else in the harness reads the bound port back
  (confirmed by grep — only the one bind call site existed), so this is
  safe. `MINECOM_TEST_PORT` still overrides for a fixed port. Verified
  two genuinely concurrent `--playtest` runs with no env var set at all
  no longer collide.

## worldgen (documented deferrals only — core is verified elsewhere)

- VStructureManager.java:951-967 — ancient city: carving fully-open instead of
  80% probabilistic ceiling; city-center jigsaw growth capped; sculk patches +
  the spider spawner block + entity spawns deferred as "bounded next increment"
  (ancient city's spawner specifically — NOT the same as the mineshaft one
  below, which is now done; ancient city was explicitly out of scope for that
  work, see MASTERPLAN §3 Tier 1 item 2).
- ~~VStructureManager.java:1101 — mineshaft spider spawner block deferred~~
  **done 2026-07-14 (Sonnet)** — `ClassicSpawners.java` (decompile-verified
  against 26.2's `BaseSpawner`/`SpawnerBlockEntity`/`SpawnData`/`SpawnerBlock`)
  now backs classic `minecraft:spawner` block entities generally: mineshaft
  spiderCorridor cave_spider (`VStructureManager.msMaybePlaceSpiderSpawner`),
  stronghold portal-room silverfish (`VStrongholdGen.ppPortalRoom`), and
  nether fortress blaze (`NetherGen.fortress`, one fixed position — the
  platform is already a documented stand-in, not a real piece-tree, so there
  was no real per-piece spawner slot to port). Full detail in HANDOFF.md
  (search "classic spawner", 2026-07-14). ~~Still open: dungeons~~ **done
  2026-07-14 (Sonnet, v0.20.0)** — `VFeature.placeMonsterRoom` (decompile-
  verified against 26.2's `MonsterRoomFeature`) now handles the
  `minecraft:monster_room`/`minecraft:monster_room_deep` configured features
  that were already bundled in placed_features.json but silently skipped
  (unhandled feature type, not missing data): validity gate, cobble/mossy-
  cobble wall carve, 0-2 loot chests against `chests/simple_dungeon` via
  `StructurePiece.reorient`, and the center spawner (skeleton/zombie/zombie/
  spider) via the now-ready `ClassicSpawners.registerSpawnerOverworld` — the
  predicted one-liner. Full detail in HANDOFF.md (search "Dungeons landed",
  2026-07-14). Region-diff moved up 99.3554% -> 99.361284%.
- VStructureManager.java:1962 — jungle temple: "hidden lever-and-piston vault"
  — dispenser traps with arrows/tripwire exist? (the note says wiring
  simplified; verify with a generated temple).
- VStructureManager.java:2884+ — desert well/ocean ruins terrain-adaptation
  simplifications (documented, low impact).
- NetherGen.java — the whole nether is an approximate generator by design
  (documented at :128-136): fortresses are bounded platforms, bastions have
  "fixed stand-ins" — a future bit-exact nether port is the single largest
  worldgen item left. (XL) **ADOPT in progress (2026-07-19):** rather than the
  planned S1-S5 build-it-ourselves port, minecom adopts the vibenilla worldgen
  library (github.com/vibenilla/worldgen, Apache-2.0, pinned @ ffaafa1) as a
  BINARY dependency (rocks.minestom:worldgen:26.2-ffaafa1, never vendored into
  src/; see NOTICE + docs/licenses/vibenilla-worldgen-LICENSE.txt). Wired ONLY
  into the offline region-diff harness (GenRegions "nether_vibenilla" token +
  VibenillaNether.java) so the adopted Nether is measured by our own instrument
  against our cached vanilla ground truth before any live cutover. The live
  server keeps NetherGen (Bootstrap unchanged) until the S5-analog cutover,
  gated on the region-diff exit target — not this step. Apache-2.0 -> AGPLv3 is
  a compatible direction. See docs/TIER4-NETHER-DESIGN.md "ADOPT".
- VProcessors.java:110,165 — capped processor + axis-aligned linear-pos
  predicate approximated (TODO markers in place, harmless until a modeled
  structure needs them).
- EndGateways/VChorus/VEndSpikes — bounded End features by design (documented).
- Tree features write leaves with `distance=7` instead of computing the real
  log-distance (1-6 near trunks) the way vanilla does at generation. Invisible
  to the old name-level region diff; the committed harness
  (scripts/worldgen_region_diff.py, 2026-07-13) compares full block states and
  reports it as the `x_leaves<-x_leaves (props)` mismatch class. Gameplay
  impact latent (no leaf-decay system yet), but any future decay port would
  treat worldgen leaves as trunkless. (S — compute distances in the leaf pass,
  or port vanilla's scheduled distance updates.) **SUPERSEDED 2026-07-19:**
  `VTree.updateLeaves` (a faithful port of `TreeFeature.updateLeaves`) now DOES
  compute the gradient; the residual `(props)` mismatches are downstream of a
  foliage-SHAPE bug, not the distance pass — see the root-cause entry below.

## Overworld worldgen residual root-cause — 99.361284% cut line (2026-07-19, Opus 4.8)

Deep diagnosis of the r18 residual (seed 20260708, 814k / 127.4M mismatched;
log test-logs/regiondiff_overworld_seed20260708_r18_20260719-200830.log). The
prompt's six "families" were treated as independent bugs; block-level probing
proved **69% of the residual (~560k) shares ONE architectural root**, and the
remaining candidate (ore) is not the carver/air cascade it looked like. NO
code was changed — every fix here either regresses the ratchet or needs a
larger design pass. Landed nothing rather than ship a speculative regression.

- **ROOT #1 — the independent-overlay decoration model is not order/state-faithful
  across chunks (VanillaGen.decoratedData:124-150).** For a target chunk it
  re-decorates each of the 9 neighbourhood chunks on its OWN fresh `OverlayCanvas`
  and merges only the writes landing in the target. Neighbour overlays never see
  each other's writes, and vanilla's cross-chunk feature ORDER (a feature in
  chunk A writing into B, then B's later features reading A's write on the shared
  world) is lost. Proven on a single spruce at trunk (-209,132..136,-126), chunk
  boundary x=-208/-209: vanilla's east canopy leaves spill from chunk (-14,-8)
  into (-13,-8); minecom exports those leaves from (-14)'s overlay, but (-13)'s
  OWN overlay runs its snow/top-layer feature against *undecorated* terrain,
  places snow on the bare ground, and because the merge processes self (dx=0)
  AFTER the -x neighbour, **snow overwrites the neighbour's spilled leaf**
  (`snow`/`snow_layer` where vanilla has `spruce_leaves`). Vanilla runs vegetal
  (trees) before top-layer (snow) on the shared world, so the leaf survives.
  This one root drives:
    - trees canopy `leaves/logs<->air` (family #3, ~165k),
    - ground cover `leaf_litter/short_grass/snow/glow_lichen<->air` (family #4, ~80k),
    - `x_leaves (props)` (family #2-leaf, ~54k) — the leaf-distance +1 is NOT an
      `updateLeaves` bug (verified: the BFS is a faithful `TreeFeature.updateLeaves`
      port, hashCode == BlockPos.hashCode). It is fed a wrong foliage set (snow
      where leaves should be) so the bucket-BFS visits in a different HashSet
      order and double-pops leaves to distance+1. Fix the shape, the distances
      resolve. leaf_litter `segment_amount`/`facing` (props) is the same root
      (positional/order divergence), not a state-calc bug.
    - **sculk / deep-dark (family #1, ~262k)** is the SAME infrastructure: it is
      deliberately gated OFF (`VFeature.SCULK_ENABLED`, -Dminecom.sculk) because
      SculkPatchFeature is a stochastic MULTI-CHUNK post-structure feature and
      the independent-overlay model diverges it (prior author measured net -0.06%
      on the ancient_city box). The gated code is a faithful `VSculk` port; it
      needs the persistent cross-chunk feature buffer that ROOT #1's fix provides.
  Fixing ROOT #1 = an order-faithful shared 3x3 (or wider) decoration pass that
  decorates the neighbourhood on ONE canvas in vanilla's chunk+step order, then
  extracts the centre. High-value (unlocks ~560k toward 99.9%) but HIGH-RISK
  under the ratchet: it changes cells the current approximation happens to match,
  so it must be built + measured carefully, not landed blind. Escalated in
  HANDOFF.md (2026-07-19). **LANDED 2026-07-19 (Fable): shared-canvas pass in
  scan order, r3 A/B picked z-major (99.194% vs x-major 99.176% vs old model
  98.951%); full r18 ratchet-positive 99.361284% -> 99.381792%. Gain is far
  below the 560k ceiling: the 3x3 window is the next binding constraint —
  sculk-ON measured net-NEGATIVE at r3 even on the shared canvas (98.997% vs
  99.194% off), so SCULK_ENABLED stays off and neighbourhood-radius widening
  (vanilla features write >1 chunk away) is the named follow-up, with the
  order property (-Dminecom.decoOrder) kept for re-A/B at the wider radius.**
  **RADIUS WIDENING MEASURED net-NEGATIVE 2026-07-20 (Opus 4.8): parameterized
  the window via -Dminecom.decoRadius=N (default 1, byte-identical to this
  baseline); r3 matrix (radius x sculk x order) shows monotonic r1 > r2 —
  radius 2 sculk-off 99.191567% (−0.0025), radius 2 sculk-ON 98.978594% (the
  wider ring does NOT flip sculk positive, it worsens it), xz still loses to
  z-major at r2. The 3x3 window is a genuine sweet spot: a fixed-window
  re-assembly has divergent BORDER chunks (decorated without their own full
  neighbourhood); widening just adds a new ring of divergent borders whose
  spurious writes now reach the target. SCULK_ENABLED stays off, ratchet stays
  99.381792%, no config change landed (knob only). The true fix is a
  persistent world-scan-order decoration buffer (decorate each chunk once in
  global order), not a wider fixed window. See HANDOFF.md 2026-07-20.**
  **PERSISTENT BUFFER BUILT + MEASURED net-neutral 2026-07-20 (Opus 4.8): the
  named true fix (each chunk decorated ONCE in global scan order on a persistent
  cross-chunk canvas, sliding-frontier extraction) was implemented harness-only
  (`VanillaGen.generateBufferedRegion`, `-Dminecom.decoBuffer=true`) and TIES the
  window ratchet — r18 99.381500% vs 99.381792% (−379 blocks/127.4M). Root cause
  of the tie: the window's per-target 3x3 z-major scan is ORDER-EQUIVALENT to the
  buffer's global z-major scan on each chunk's immediate 8-neighbour ring, so the
  shared-canvas landing had ALREADY captured vanilla's first-ring feature order —
  the ROOT #1 spruce/snow case is fixed by both. **The sculk hypothesis above is
  FALSIFIED:** the buffer does NOT unlock `SCULK_ENABLED` (−0.22pp at both margin
  M=1 and M=2) — sculk's divergence is intrinsic to the stochastic `VSculk`
  port/its charge spread, not the cross-chunk decoration infrastructure. The
  overworld residual's next lever is single-feature parity (sculk-spread RNG replay,
  ore placement-origin drift), NOT the decoration model. Full matrix + verdict in
  HANDOFF.md 2026-07-20.**

- **Ore / stone-patch drift (families #5/#6, ~110k) is a placement-ORIGIN RNG
  drift, NOT the carver/air cascade and NOT the scatter.** Verified against
  re-decompiled 26.2 `OreFeature` (rule 7): `place()` wrapper draw order (dir
  nextFloat, y0/y1 nextInt(3)), `doPlace` scatter (nextDouble per sphere, overlap
  cull, ellipsoid fill), `canPlaceOre`/`shouldSkipAirCheck` discard RNG, and the
  `count`/`in_square`/`height_range` (uniform+trapezoid) providers ALL match
  minecom exactly. Air-adjacency was ruled out empirically: of ~5000 coal
  `coal_ore<->stone` mismatches sampled, air-adjacency differs in 0-2. The blobs
  are shape-preserving TRANSLATES (one coal blob matched exactly, a sibling was
  shifted a few blocks in x+z), i.e. the per-count-iteration origin diverges from
  an upstream RNG-state difference (feature-sort index / a sibling feature's draw
  count). Root not yet isolated; needs a Python replay of the placement RNG for
  one chunk vs minecom-instrumented origins. Bounded but unconfirmed — could be a
  clean win or a feature-sort ordering problem. (M-L)

- Reaching 99.9% needs ~84% of the residual (687k) gone; the six families sum to
  ~674k at *perfect* fix, so 99.9% is only reachable if ROOT #1 (trees+ground+
  leaf+sculk, ~560k) largely lands PLUS ore PLUS some sub-top-40 long tail
  (~140k). It is not reachable by picking off independent families — the headline
  jump is gated on the decoration-model rewrite.

## 26.2 bump — deliberate simplifications (2026-07-13, Fable)

- **Sulfur cube is a roster stub, not a parity port.** `VanillaMobs.sulfurCube`
  (via `slimeLike`) gives the correct passive shell — no targeting goals, no
  touch damage, adult spawn size 2, cube-mob attribute rules, slime hop
  movement — but none of vanilla's data-driven behavior: the
  `SulfurCubeArchetype` system (bouncy/explosive/hot/sticky... modifiers),
  item swallowing (`SULFUR_CUBE_FOOD` tempt + BODY-slot pickup), bucketing,
  shearing, or breeding/baby-growth state. ~~split-into-exactly-2 on death
  (`maybeSplitSlime` stays slime/magma-only)~~ **done 2026-07-17 (Sonnet 5)**
  — `maybeSplitSlime` now also covers `SULFUR_CUBE`, decompile-verified
  against `SulfurCube.getSplitCount`/`AbstractCubeMob.remove` (26.2): always
  exactly 2 children (not slime/magma's `2+rand(3)`), and since sulfur cube's
  size ladder is only `{1,2}` (`MIN_SIZE`/`MAX_SIZE`), that first split is also
  always the terminal one — no fuse/archetype system exists yet to ever make
  `getSplitCount` return 0 (the `isPrimed()` branch), so the unconditional-2
  case is the only reachable one. 4 new PlayTest checks
  (`scenarioSulfurCubeSplit`). ~~Still open: the full `SulfurCubeArchetype`
  system~~ **slice (a) done 2026-07-18 (Sonnet 5)**: new file `mobs/
  SulfurCubes.java`, decompile-verified against `SulfurCubeArchetype`/
  `SulfurCubeArchetypes` (26.2, `vanilla-src/net/minecraft/world/entity/
  SulfurCubeArchetype*.java`) and `SulfurCube.collectEquipmentChanges`
  (`vanilla-src/.../monster/cubemob/SulfurCube.java`). All 12 real archetypes
  (regular/bouncy/slow_bouncy/slow_flat/fast_flat/light/fast_sliding/
  slow_sliding/high_resistance/sticky/explosive/hot) ported verbatim —
  `SulfurCubes.equipBody(mob, item)` matches the item against the real
  `sulfur_cube_archetype/*` item tags (already bundled in `tags_item.json`,
  no data-extractor changes needed) and applies every matching archetype's
  real `AttributeModifier`s onto the 5 real Minestom attributes vanilla's
  own archetype data drives (`KNOCKBACK_RESISTANCE`,
  `EXPLOSION_KNOCKBACK_RESISTANCE`, `BOUNCINESS`, `FRICTION_MODIFIER`,
  `AIR_DRAG_MODIFIER` — all 5 already exist in Minestom, no new attributes
  needed), removing the previous archetype's modifiers first (matches
  vanilla's re-swallow behavior: the newest item's archetypes replace the
  old ones, they don't stack). Buoyant/explosion/contact-damage/
  knockback-scale data is stored per-mob for the next slice to consume.
  State is session-scoped, same simplification `Allays.java`'s own State map
  already documents — real vanilla doesn't persist any of this either
  (`SulfurCube.addAdditionalSaveData` only saves pickup_timer/from_bucket/
  fuse), it's re-derived from the BODY item on equip, and this project has
  no generic "entity reloaded" hook yet to re-run that derivation after a
  world restart. 10 new PlayTest checks (`scenarioSulfurCubeArchetype`) —
  caught one real bug in the test itself while landing it: comparing a
  `float` archetype constant (`0.9f`) widened to `double` against the
  double literal `0.9` is a float-precision mismatch (`0.9f` widens to
  ~0.8999999762), not a product bug — fixed by computing the expected value
  via the same `(double) 0.9f` widening the production code does. Still
  open (this pass is data model + assignment + attribute stats only, Tier
  follow-up): the actual swallow/give/bucket/shear PLAYER interactions
  (nothing calls `equipBody` from gameplay yet — still nothing right-clicks
  a sulfur cube), and the physics/gameplay CONSUMPTION of the stored
  buoyant/explosion/contactDamage/knockback state (floating, fuse-priming,
  touch damage, knockback-scale hit reaction) plus per-archetype sound
  data (deliberately not ported — nothing consumes it yet). Breeding/
  baby-growth state also still open. Decompiles cached at
  `vanilla-src/net/minecraft/world/entity/monster/cubemob/` +
  `SulfurCubeArchetype*.java` + (this pass, newly decompiled)
  `vanilla-src/net/minecraft/world/item/component/SulfurCubeContent.java` +
  `vanilla-src/net/minecraft/core/dispenser/
  SulfurCubeBlockDispenseItemBehavior.java` (bucket-content tooltip data and
  the dispenser-feeds-a-cube behavior, both needed by the next slice). Part
  of the sulfur-caves Tier-parity follow-up. (L, in progress — slice (a) of
  4 landed, (b) physics/(c) interactions/(d) breeding still open. A slice-(b)
  fuse-priming attempt was made and REVERTED 2026-07-18 after an escalating,
  not-yet-understood flake — see HANDOFF.md's "Sulfur cube explosion
  fuse-priming" entry before re-attempting; don't just re-tune the test.)
- **Sulfur-caves biome decoration no-ops.** The 26.2 data regen brought in
  the biome, its features and 10 spring structure templates; its surface
  rules and `sulfur_cave_gradient` noise are in the loaded data, but the
  decoration features (sulfur spike/spring/pool) use the new
  `sequence`/`weighted_random_selector` configured-feature types, which
  VFeature silently skips (its unknown-type default). Biome SELECTION is
  fine: sulfur_caves is in biome_parameters_overworld.json (the datagen
  table VBiome reads — it's absent from the raw multi_noise_overworld.json
  preset file, like all datagen-only rows), so minecom places the biome
  where vanilla does; only its decoration diverges, and the 26.2 region-diff
  baseline captures exactly that. Same follow-up as above. (L)

## Structure-blind decoration canvas (2026-07-20, Fable)

The decoration canvas (OverlayCanvas) reads UNDECORATED terrain without
structures, so decoration features' replaceability checks can't see structure
blocks — kelp/seagrass can overwrite monument prismarine, tree writes can
overwrite mansion chests. Vanilla decorates on the real chunk data (structures
included), so its features protect those cells. Exposed (not introduced) by
the shared-canvas pass: measured at seed-20260710 monument -9 prismarine
family and seed-1 mansion -4 chests (SelfTest pins re-pinned to current
output 2026-07-20 with citing comments; the +38 mansion dark-oak delta in the
same re-pin is CORRECT cross-chunk canopy spill capture). The r18 ratchet
region contains no mansion, so this divergence is invisible to the headline
number. Fix shape: feed structure placements into the decoration canvas's
read path (chunkAt overlay), then re-measure r18 under the ratchet before
landing. (M) **FIXED 2026-07-20 (structure-aware canvas): decoration reads
go through structureData (cachedData + placed structure pieces); r18
ratchet-positive 99.381792 -> 99.393668. Mansion chests recovered to 33;
the monument pin jumped to 13671 because structure-blind ocean-floor disks
had been overwriting the ~58x58 foundation all along — every earlier pin
was measuring an eaten monument.**

## stale comments to clean up when touched

- VanillaMobs.java witch javadoc (~:708 region) still says "no difficulty
  setting" in one spot if any remain — guardian + lightning were fixed
  2026-07-11; grep for "no difficulty" on next pass.
- Villagers.java:20 "pragmatic stand-in until villagers are placed from the
  template entity data" — village template entities still unplaced (villagers
  seed at bells); the trial-chambers template-NBT hook shows the way to do
  spawner-based placement properly.

## Top 10 by player impact

1. Structure chest loot filling (empty chamber/city chests; dungeon chests
   DONE 2026-07-14 v0.20.0, see below) — M remaining
2. ~~Classic `minecraft:spawner` block entities~~ — mineshaft/stronghold/
   fortress DONE 2026-07-14 (v0.19.0, `ClassicSpawners.java`); dungeons DONE
   2026-07-14 (v0.20.0, `VFeature.placeMonsterRoom` — see worldgen section
   above) — all four integration points now complete
3. ~~Enchanting table (+ grindstone/smithing)~~ — table + grindstone DONE
   2026-07-14 (v0.18.0); smithing table (netherite + trims) still open — S
4. Persistence of containers/mobs across restarts — L
5. Random-tick engine (crop/grass/fire/copper/sapling) — L
6. ~~Villager→zombie-villager conversion + curing loop~~ — DONE 2026-07-15 (v0.22.0,
   `mobs/VillagerConversion.java`, see the mobs/ section above)
7. ~~Mob equipment drop chances~~ — DONE 2026-07-15 (v0.22.0, killedByPlayer memory
   window, see Combat.java entry above); mob spawn-equipment enchantments still open — M
8. ~~Taming (wolf/cat/horse) + leads/name tags~~ — DONE 2026-07-15 (v0.21.0, see the
   Taming/mounts entry above) — S remaining (wolf/cat/horse breeding, wolf armor)
9. ~~Splash/lingering/tipped arrows~~ — all DONE (splash/lingering were already
   real, just undocumented here; tipped/spectral arrows DONE 2026-07-17,
   Sonnet 5, see the Potions.java entry above) — missing 26.x effects (turtle
   master, luck, darkness, oozing/weaving/infested/wind-charged) remain — M
10. ~~Raid difficulty scaling (wave counts by difficulty, Bad Omen via
    patrols)~~ — DONE 2026-07-15 (v0.23.0, wave counts/composition; the
    patrol-captain Bad Omen trigger itself remains open, see Raid.java
    entry above) — M remaining (patrol captains)
