# Minecom

Vanilla Minecraft rebuilt from scratch on [Minestom](https://github.com/Minestom/Minestom) — no Mojang server code, multithreaded from the ground up, driven by the **real Mojang 26.1.2 data files** (1515 recipes, 1085 block + 108 entity loot tables, full tag graph, extracted from the official server jar and bundled in `src/main/resources/vanilla/`).

## What's implemented (v0.3 "Adventure Update")

**New in v0.3** — armor with the vanilla damage-reduction formula (armor/toughness values and the `bypasses_armor` rule read from official components and damage-type tags); item durability (`MAX_DAMAGE`/`DAMAGE` components, break sound); attack damage from `ATTRIBUTE_MODIFIERS` (no hardcoded stat tables); XP orbs + vanilla level curve (mobs, ores, smelting via furnace XP bank, death penalty); flowing water & lava (sources, falling columns, infinite-water pools, obsidian/cobble/stone interactions) with buckets; farming (hoes→farmland, wheat/beetroot/carrot/potato growth, bone meal) and sapling→tree growth; beds (respawn point + night skip); rain cycles (`/weather`); and disk persistence for chest/furnace contents, crops, world time/weather, and player inventory/health/hunger/XP/position (`world/minecom_state.json`).

Loot tables now also evaluate `block_state_property` against real block state — young wheat drops only seeds, ripe wheat drops wheat, per Mojang's own table.


**World** — continental terrain with oceans, plains and mountain ranges (up to ~y190); biomes (plains, forest, birch forest, taiga, snowy plains, desert, beach, ocean, stony peaks) sent to the client for correct grass/snow rendering; 3D-noise caves with lava below y=-54; all ore families with correct stone/deepslate variants and depth ranges (coal→diamond, emerald in peaks); oak/birch/spruce trees, cacti, flowers, sugar cane; deepslate + bedrock gradients; day/night cycle; Anvil persistence.

**Survival** — vanilla hunger/exhaustion/saturation model with natural regen and starvation; eating driven by items' real FOOD components (stews return bowls); fall damage; void damage; death drops + respawn.

**Blocks** — loot-table drops with vanilla tool gating (`requiresTool` + `mineable/*` + `needs_*_tool` tags: stone needs a pickaxe, diamond ore needs iron+); sand/gravel physics with falling-block entities; doors/trapdoors/fence gates toggle.

**Crafting** — the player 2x2 grid and the crafting-table 3x3 match the full vanilla shaped/shapeless recipe set (tags resolved, mirrored patterns, shift-click crafts repeatedly, closing returns ingredients).

**Furnace** — real smelting recipes with cook times, vanilla fuel burn durations, lit block states, progress-bar window properties. **Chest** — 27-slot per-position inventories (in-memory).

**Mobs** — zombies/spiders (melee AI), skeletons (real arrows), creepers (fuse + explosion damage), cows/pigs/sheep/chickens; natural spawning (passives on daylight grass, hostiles at night or deep underground, caps + far-despawn); loot-table drops; vanilla weapon damage values and knockback.

**Commands** — `/gamemode`, `/tp`, `/give`, `/time set day|noon|night|midnight`, `/weather clear|rain`, `/summon <mob>`, `/drain [radius] [all]`, `/spawn`, `/kill`.

**Redstone (v0.4)** — wire networks with exact 15-block decay and vanilla line/dot pointing rules, strong vs weak power, torches (1rt inversion), repeaters (1-4rt delay + locking), comparators (compare/subtract **and container reading**), lamps, levers, buttons (stone 20t / wood 30t), pressure plates, iron doors, TNT, dispensers (shoot arrows, place fluids, prime TNT) and droppers with 9-slot GUIs — and pistons with the 12-block push limit, sticky retraction, immovable-block rules, **and quasi-connectivity**: pistons/dispensers/droppers accept power at the block above and only re-check on a block update, so BUD switches work like vanilla. Block updates from wire propagate two deep, as in vanilla.

**Explosions** — the vanilla 1352-ray algorithm with per-block blast resistance: TNT (power 4, 100% drops, chain-primes other TNT) and creepers (power 3, 1/3 drops) now actually crater terrain.

**v0.5** — observers (pulse on watched-block changes, chainable), hoppers (vacuum, container transfer, furnace input/fuel routing, redstone-disable, comparator-readable), redstone torch burnout (8 flips/60 ticks), enchanting (table with bookshelf-scaled offers + lapis/level costs, `/enchant`; silk touch, fortune, looting, sharpness, protection, knockback, unbreaking, efficiency — silk/fortune/looting evaluate through Mojang's real loot-table conditions), animal breeding (love mode, calves, growth), falling-attack crits (1.5x), and leaf decay with cascade when trees are logged.

**v0.6-0.7** — potions & brewing stands (the vanilla recipe graph: nether wart, ingredients, redstone/glowstone/fermented-eye modifiers; effects hook combat: strength/weakness/resistance, regen/poison tick, fire resistance vs the new lava damage), shields (block frontal hits), piston entity-pushing, anvils (durability + enchantment merging with level costs), and fishing (bobber, bite timing, catches straight from Mojang's fishing/junk/treasure loot tables).

**v0.8 — The Nether.** A second dimension with its own generator (netherrack caverns, the lava sea at y=31, soul sand valleys, glowstone ceilings, quartz/gold/ancient debris), real obsidian portals (flint and steel on any valid 2-21 frame, 4-second travel, vanilla 8:1 coordinate scaling, automatic return portals) and nether mobs: neutral zombified piglins that mob you when provoked, magma cubes, and blazes — completing the brewing progression chain.

**v0.10 — Natural spawning (the vanilla `NaturalSpawner`, in parallel).** The crude roll-based spawner is replaced by a faithful port of Mojang's `NaturalSpawner`: real biome-weighted spawn lists (extracted from all 63 overworld biomes), the `max × spawnableChunks ÷ 289` mob-cap formula per `MobCategory`, the 3-group pack loop, per-type `SpawnPlacements` + spawn-rule predicates (monsters need the dark; animals need grass + light), the 24-block player-distance rule, and 128/32-block despawn. Because spawning draws from the level's non-seeded random this matches the *algorithm and probabilities* (unit-tested: caps, category map, weighted selection), not bit-identical positions. **This is the multithreading thesis in action:** the per-chunk spawn-decision phase is embarrassingly parallel, so it runs across a thread pool — measured 1.66× on 4 cores at 5-player / 1249-chunk scale (the win grows with players and with the real generator's per-chunk biome-climate cost, where vanilla is stuck on one thread). The mob roster grew from 8 to **all 50 natural-spawn types** — hostile variants (husk/drowned/stray/bogged/zombie_villager/slime), the enderman (neutral until you stare at it, then it blinks around and comes for you), the witch (keeps its distance and lobs harming potions), ghast fireball volleys, ~20 passive animals, 9 aquatic mobs (`IN_WATER` placement), and nether piglins/hoglins/striders (`IN_LAVA`) — and both the overworld and the Nether run the same faithful spawner. Every mob a biome can list now actually spawns.

**v0.11 — The End (terrain).** A third dimension whose generator reuses the vanilla density-function engine driven by `noise_settings_end.json`: the End-island simplex terrain (`consumeCount(17292)` → `SimplexNoise`, `getHeightValue`) is **verified bit-exact against Mojang's `EndIslandDensityFunction`**, and the density graph runs on the End's 8×4×8 noise cells (the interpolator's cell size is now parameterized — the overworld stays byte-for-byte identical). The result is the correct shape of The End: a solid central island, scattered small outer islands, and the void between, with the faithful `TheEndBiomeSource` (the_end centre → highlands/midlands/barrens/small-islands). Endermen spawn there (the spawner treats the sky-less dimension as always dark), the ten obsidian spikes ring the island (world-seeded sizes/heights, iron-caged crystals on top), and **an ender dragon circles the centre** — healed by the crystals until you destroy them, then killable, and on death it forms the exit portal (bedrock + dragon egg) and drops the reward XP. `/end` drops you onto an obsidian arrival platform. Refinements still to come: the waypoint-graph flight and breath attacks, the end-portal stronghold, and chorus/gateway features.

## Honest gaps (not yet implemented)

Villagers themselves (the village *buildings* generate — see below — but no villager entities, trading, or professions yet), village terrain-following on slopes (the expansion hack) and beardifier foundations, fortresses, the End stronghold/chorus/gateways, boats/minecarts, anvil renaming, fluid/redstone simulation inside the Nether (overworld-only engines for now), sub-tick update ordering. Fluid flow spreads evenly rather than seeking the nearest hole. Chunks beyond the Anvil save regenerate deterministically from seed.

**v0.12 — Villages.** All five village types (plains, desert, savanna, snowy, taiga) generate from Mojang's real jigsaw template pools, reusing the bit-exact jigsaw assembly with the new **`WORLD_SURFACE_WG` heightmap projection** so the town centre lands on the terrain surface — bells, dirt-path streets, biome-correct houses (acacia in savanna, sandstone in desert…), wells, farms and lamps, laid out by the same RNG-exact placer that builds the ancient city. Wandering **villagers** are spawned at each town-centre bell as chunks load, and right-clicking one opens the real **merchant trade GUI** (farmer offers — wheat/potatoes for emeralds, emeralds for bread/apples — selecting fills the result slot when you hold the inputs, and taking it consumes them). Multiple professions, price/reputation scaling, slope-following (the expansion hack), and beardifier foundations are the next layers.

**v0.13 — Minecarts & the Nether fortress.** A minecart placed on a rail rides the track: right-click to board, powered rails accelerate it (up to a cap) and brake it when unpowered, plain rails coast with friction, curved rails turn it, and it snaps to the rail centreline. And nether-brick **fortresses** now generate (railed bridges + a platform) so **blazes** and wither skeletons spawn on the brick — restoring the brewing progression (the vanilla spawner, unlike the old roll-based one, correctly keeps blazes fortress-only). And **boats** float: use a boat item on water to place one, right-click to board, and it bobs at the surface with water friction (all nine wood types). Minecart slopes/collision, boat paddle-steering nuance, and the full branching fortress layout are the next layers.

## Requirements & running

- Java 25+, client Minecraft **26.1.2** (bump `minestom.version` in `pom.xml` when Minestom ships 26.2)

```sh
mvn package
java -jar target/minecom.jar            # server on :25565
java -jar target/minecom.jar --selftest # data-engine test battery (34 checks)
java -jar target/minecom.jar --playtest # headless gameplay verification (88 checks)
```

`--playtest` boots the full server wiring on a flat world, joins a fake player, and
exercises breaking/drops/tool gating, both crafting grids, furnaces, eating, fall
damage, armor, mob combat and drops, skeleton archery, fluid spread/decay, buckets,
farming, doors, beds, death/respawn — through the same event pipeline real clients use.

Offline mode (LAN). Add `MojangAuth.init()` in `Main` before `server.start()` for online auth.
