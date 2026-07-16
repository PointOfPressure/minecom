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
  adapter tail". Still session-scoped: warden anger (deliberate), item
  entities in flight, breeding's own 30-second IN_LOVE window (too
  short-lived to be worth it), and the registries are overworld-only
  (position keys would collide across dimensions — pre-existing
  limitation).
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
  system in this codebase at all), sign-waxing (HoneycombItem also
  implements SignApplicator — no sign block-entity/text system exists to
  apply it to), particles/sounds (client-visual).
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
  or elytra boost). Still missing: armor stands (no armor-stand system),
  brush (no archaeology), candle placement, chest-onto-donkey (no
  chested-horse inventory). Honeycomb/waxing landed 2026-07-12 (Sonnet,
  `blocks/CopperWaxing.java` — see the random-tick engine entry above, now
  that oxidation exists for it to block). Sculk
  shriekers landed too (Vibrations.java): player-caused shrieks, Darkness,
  warning levels; the warden itself landed 2026-07-12 (Fable — see the
  mobs/ section entry). (each S once its base system exists)
- Redstone.java `containerSignal` — **expanded 2026-07-11 (Fable)**: added
  copper bulb (LIT=15), crafter (filled+locked slots), sculk sensors (last
  vibration frequency while active). Still missing: beehive honey level (no
  bee/hive system). Barrel/brewing/cake/end-portal-frame/chiseled-bookshelf/
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
- Fluids.java:26 — flow spreads evenly; vanilla weights toward nearest hole
  within 4 blocks. Also check: kelp/seagrass waterlogging, ~~bubble columns~~
  (done 2026-07-12, Fable — blocks/BubbleColumns.java, see entry below),
  bottomless lava in nether speed (fast_lava attribute). (M)
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
- Beds.java:41 — no nether/end bed explosion (vanilla BED_RULE attribute); no
  "monsters nearby" sleep denial; thunderstorm daytime sleeping. (S)
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
- No: chiseled bookshelves, decorated pots (break/insert), cauldrons (water/
  lava/powder snow storage, bottle fill), bells (raid pings, ring on hit),
  candles on cakes, item frames, armor stands, banners, signs (editing),
  skulk, spore blossoms, big dripleaf tilt, pointed dripstone falling/
  filling, scaffolding, ladders/vine climb speed (client-side anyway),
  respawn-anchor charge particles. (each S-M; cauldron and bells most
  player-visible) — ~~lodestone+compass~~ done (`Lodestone.java`, binding +
  splitting a single vs. stacked compass; `scenarioLodestone` playtest
  coverage). Lightning rod done 2026-07-11 (Fable):
  tracked-rod registry + 128-block strike redirect + 8gt pulse, weighted
  pressure plates analog + copper bulbs also landed in the same
  redstone-parity pass.
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
- Missing passive/utility mobs: bee (pollination/hive/anger — M-L), cat (village
  spawning, morning gifts, creeper repel — taming/feeding DONE, see above),
  skeleton_horse (trap exists? no —
  lightning trap missing), trader_llama (wandering trader spawns alone;
  vanilla brings 2 leashed llamas), allay (item collection), sniffer, tadpole
  (frog lifecycle), snow golem/iron golem BUILDING by players (pumpkin
  placement patterns — golems only spawn via commands/tests). (S-M each)
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
  exists for this subsystem (unlike piston/worldgen). Smithing table
  (netherite + trims), stonecutter, loom, cartography table: still none.
  (M-L)
- Potions.java — 13 effect cases handled on drink; missing: splash potion AoE
  scaling by distance, lingering clouds, tipped arrows, turtle master, slow
  falling (check), levitation via potion, bad omen bottle (ominous bottle item
  — trial chambers ominous path currently only reachable via /effect-style
  application in tests), luck, darkness, oozing/weaving/infested/wind-charged
  (26.x effects). Absorption/golden apples? Totem of undying? (M)
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
  command instead), no maps, no bundles, no spyglass, no goat horns, no
  shields BANNER patterns, no totem. (each S-M) ~~no ender pearl teleport~~
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
  worldgen item left. (XL)
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
  or port vanilla's scheduled distance updates.)

## 26.2 bump — deliberate simplifications (2026-07-13, Fable)

- **Sulfur cube is a roster stub, not a parity port.** `VanillaMobs.sulfurCube`
  (via `slimeLike`) gives the correct passive shell — no targeting goals, no
  touch damage, adult spawn size 2, cube-mob attribute rules, slime hop
  movement — but none of vanilla's data-driven behavior: the
  `SulfurCubeArchetype` system (bouncy/explosive/hot/sticky... modifiers),
  item swallowing (`SULFUR_CUBE_FOOD` tempt + BODY-slot pickup), bucketing,
  shearing, breeding/baby state, fuse priming, or split-into-exactly-2 on
  death (`maybeSplitSlime` stays slime/magma-only). Decompiles cached at
  `vanilla-src/net/minecraft/world/entity/monster/cubemob/` +
  `SulfurCubeArchetype*.java`. Part of the sulfur-caves Tier-parity
  follow-up. (L)
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
9. Splash/lingering/tipped arrows + missing 26.x effects — M
10. ~~Raid difficulty scaling (wave counts by difficulty, Bad Omen via
    patrols)~~ — DONE 2026-07-15 (v0.23.0, wave counts/composition; the
    patrol-captain Bad Omen trigger itself remains open, see Raid.java
    entry above) — M remaining (patrol captains)
