# Gap audit — 2026-07-11

A full pass over the gameplay codebase (everything outside the bit-exact-verified
worldgen core) collecting what is still missing or approximated versus real
vanilla 26.1.2. Notes only — nothing here was changed. Each item: where, what's
missing, what vanilla does, rough size (S/M/L). Written right after the four
HANDOFF items (daylight detector, difficulty system, villager food economy,
trial chambers) landed, so those are NOT re-listed except for their documented
leftovers.

## Cross-cutting

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
  task, not tracked state — gets a fresh timer on restore). Still session-scoped
  (HANDOFF "Persistence adapter tail"): trial chambers, scheduled ticks,
  warden anger (deliberate), item entities in flight, breeding's own
  30-second IN_LOVE window (too short-lived to be worth it), and the
  registries are overworld-only (position keys would collide across
  dimensions — pre-existing limitation).
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
  here, there's nothing to gate.
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
- **No attack-cooldown model** (Combat.java:82-86 admits it) — vanilla 1.9+
  attack charge scales damage 0.2x-1x and gates sweep; minecom always
  full-strength + sweep on grounded sword hits. (M)
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
  denial is inherent (isPushable rejects block entities).
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
- Missing passive/utility mobs: bee (pollination/hive/anger — M-L), cat (village
  spawning, morning gifts, creeper repel), skeleton_horse (trap exists? no —
  lightning trap missing), mule, trader_llama (wandering trader spawns alone;
  vanilla brings 2 leashed llamas), allay (item collection), sniffer, tadpole
  (frog lifecycle), snow golem/iron golem BUILDING by players (pumpkin
  placement patterns — golems only spawn via commands/tests). (S-M each)
- No taming anywhere (wolves/cats/parrots/horses), no horse riding/saddles, no
  leads, no name tags (despawn suppression), no mob item pickup except
  villagers (zombies canPickUpLoot rolled but unused). (M-L)
- VanillaMobs.java:656 — phantom bounded to size 0 (6 dmg); vanilla size scales
  with insomnia. Also no insomnia/phantom natural night spawner at all —
  phantoms only via test/summon. (M)
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
  VillagerFood), no gossip/reputation (hero of the village, raid discounts),
  no trade restocking at job site (verify VillagerTrades restock), no
  profession-specific requestedItems beyond the picks-up tag, no baby villager
  growth into profession claim delay nuances, no zombie-villager curing
  (golden apple + weakness), no villager→zombie-villager conversion on zombie
  kill (Normal 50%/Hard 100% — difficulty hook now exists!). (M; the conversion
  is S and high-value now)
- Raid.java:17 — bounded 3-wave raid started by command/bell proximity; no Bad
  Omen from patrol captains (no patrols), no wave scaling by difficulty (wave
  count: Easy 3/Normal 5/Hard 7 — difficulty now exists, S), no raid bar
  percentage from raider HP, no hero of the village, no ravager riders, no
  witch/evoker in later waves? (verify wave comps), no bell-ring glowing. (M)
- VNaturalSpawner — solid core; check: no cave/spawner mob costs (charge-based
  spawn potentials for soul sand valley etc.), no per-biome water ambient
  (fish schools), no persistent-after-nametag/tempt rules, wandering trader
  spawn uses flat 20-min timer (vanilla escalating 2.5%→7.5% odds per 24000t
  cycle with spawn attempts). (S-M)
- Combat.java — mob spawn-equipment **enchantments** never applied
  (populateDefaultEquipmentEnchantments: 0.25/0.5 x specialMultiplier chance,
  MOB_SPAWN_EQUIPMENT provider — difficulty threading exists now, needs an
  enchant-provider evaluator, M); worn-equipment **drop chances** on death
  (8.5% per filled slot, +looting) — mobs never drop their armor/weapons. (S)
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

- LootTables.java:17 — "No enchantment system exists" for loot evaluation:
  enchant_randomly/enchant_with_levels functions in tables (trial reward
  enchanted books, end city gear) come out unenchanted; set_potion works? (my
  trial tables roll POTION items — verify the potion component is applied,
  else they drop as water bottles). Fortune/silk-touch conditions work via
  tool passing. (M)
- Enchants.java — enchanting TABLE flow: is there an enchanting table UI with
  real xp/lapis costs and randomized offers? (grep suggests enchant command +
  anvil combining only — the table itself missing = L). Grindstone
  (disenchant+xp), smithing table (netherite + trims), stonecutter, loom,
  cartography: none. (M-L)
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
- Items: no elytra/firework flight, no ender pearl teleport (verify — thrown
  pearls?), no eye of ender flight (locatestronghold command instead), no maps,
  no bundles, no spyglass, no goat horns, no shields BANNER patterns, no
  totem. (each S-M)

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
- PlayTest port collision with concurrent sessions — MINECOM_TEST_PORT exists;
  consider defaulting to a random free port instead. (S)

## worldgen (documented deferrals only — core is verified elsewhere)

- VStructureManager.java:951-967 — ancient city: carving fully-open instead of
  80% probabilistic ceiling; city-center jigsaw growth capped; sculk patches +
  the spider spawner block + entity spawns deferred as "bounded next increment".
- VStructureManager.java:1101 — mineshaft spider spawner block deferred (needs
  spawner mob-type API — same shape as the new TrialChambers registry; could
  reuse that pattern for classic `minecraft:spawner` block entities generally:
  dungeons, fortress blaze spawners, stronghold silverfish). (M, high-value)
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

## stale comments to clean up when touched

- VanillaMobs.java witch javadoc (~:708 region) still says "no difficulty
  setting" in one spot if any remain — guardian + lightning were fixed
  2026-07-11; grep for "no difficulty" on next pass.
- Villagers.java:20 "pragmatic stand-in until villagers are placed from the
  template entity data" — village template entities still unplaced (villagers
  seed at bells); the trial-chambers template-NBT hook shows the way to do
  spawner-based placement properly.

## Top 10 by player impact

1. Structure chest loot filling (empty dungeon/chamber/city chests) — M
2. Classic `minecraft:spawner` block entities (dungeons, mineshafts, fortress,
   stronghold) via the TrialChambers registry pattern — M
3. Enchanting table (+ grindstone/smithing) — L
4. Persistence of containers/mobs across restarts — L
5. Random-tick engine (crop/grass/fire/copper/sapling) — L
6. Villager→zombie-villager conversion + curing loop (difficulty hooks ready) — S/M
7. Mob equipment enchantments + equipment drop chances — S/M
8. Taming (wolf/cat/horse) + leads/name tags — L
9. Splash/lingering/tipped arrows + missing 26.x effects — M
10. Raid difficulty scaling (wave counts by difficulty, Bad Omen via patrols) — M
