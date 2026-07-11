# Gap audit — 2026-07-11

A full pass over the gameplay codebase (everything outside the bit-exact-verified
worldgen core) collecting what is still missing or approximated versus real
vanilla 26.1.2. Notes only — nothing here was changed. Each item: where, what's
missing, what vanilla does, rough size (S/M/L). Written right after the four
HANDOFF items (daylight detector, difficulty system, villager food economy,
trial chambers) landed, so those are NOT re-listed except for their documented
leftovers.

## Cross-cutting

- **Session-scoped block-entity/mob state.** Container inventories beyond
  chests/furnaces (dispensers/droppers `Redstone.DISPENSERS`, hoppers
  `Hoppers.HOPPERS`, brewing stands, jukebox discs, lectern books, composter is
  block-state so fine), villager inventories/food (`VillagerFood`), trial
  chamber registrations (`TrialChambers` — chambers reloaded from a saved Anvil
  world come back inert), mob entities themselves (nothing re-spawns mobs from a
  save; villagers re-seed via bell scan only) — all lost on restart.
  `Persist.java` saves only: time, raining, difficulty, chests, furnaces, crops,
  players. (L — needs a persistence design pass; per-chunk entity/BE save)
- **No random-tick engine.** Harvesting.java:18 admits it: crop growth is a
  scheduled-task approximation (Farming.java), and there's no generic random
  tick for: grass/mycelium spread, leaf decay timing (leaf decay exists but
  event-driven), fire spread, ice/snow melt by light, copper oxidation, sugar
  cane/cactus/bamboo growth (check Farming coverage), sapling growth, vine
  spread, budding amethyst. (L — a bounded random-tick scheduler would unlock
  many S items)
- **No attack-cooldown model** (Combat.java:82-86 admits it) — vanilla 1.9+
  attack charge scales damage 0.2x-1x and gates sweep; minecom always
  full-strength + sweep on grounded sword hits. (M)
- **No server-enforced mining-speed system** (VanillaMobs elder guardian notes)
  — Mining Fatigue/Haste apply as effects but don't change dig speed
  server-side; Aqua Affinity/Depth Strider abandoned for the same reason. (M/L)
- **Chunk inhabited time not persisted** (Difficulty.java) — regional difficulty
  ramp resets each restart; vanilla stores it per chunk. (S once per-chunk
  persistence exists)
- **Concurrent-session note:** another model is actively adding blocks
  (Cake.java, Candle.java appeared mid-audit); re-check this list against HEAD
  before starting work.

## blocks/

- Redstone.java — dispenser/dropper item behaviors cover only arrow,
  water/lava bucket, TNT, plain drop. Vanilla DispenserBehavior registry also
  does: bone meal, fire charge, flint&steel, shears (sheep/carve), armor
  equipping, spawn eggs, boats/minecarts placement, splash/lingering potions,
  projectiles (snowball, egg, trident, wind charge, firework), bucket pickup,
  shulker box placement, candles, honeycomb... (M — data-driven table + handlers)
- Redstone.java `containerSignal` — comparator reads chest, furnace,
  dispenser/dropper, hopper, composter, jukebox, lectern, respawn anchor. Missing:
  brewing stand, barrel (no barrel container at all?), beehive honey level, cake,
  end portal frame eye, chiseled bookshelf, decorated pot, crafter. (S each)
- Redstone.java — no crafter block (26.x auto-crafter) at all. (M)
- Redstone.java — sculk sensors/calibrated sculk: no vibration system anywhere.
  (L)
- Pistons.java:121 — pushes/pulls single blocks; no 12-block push chains? (verify)
  no slime/honey block chain semantics, no block-entity move denial nuances. (M/L)
- Hoppers.java — check minecart-with-hopper pull-from-above and
  hopper-into-minecart paths (Minecarts variants exist; the connection is
  suspect). (S/M)
- Fluids.java:26 — flow spreads evenly; vanilla weights toward nearest hole
  within 4 blocks. Also check: kelp/seagrass waterlogging, bubble columns,
  bottomless lava in nether speed (fast_lava attribute). (M)
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
- Boats.java — no fall-damage negation rules/bubble column sink? chest boats?
  (S/M)
- Campfires.java — signal smoke (hay bale below → taller smoke) is
  client-visual; soul campfire→piglin fear link missing (piglins don't fear
  anything). (S)
- Composter.java — verify per-item compost chances match
  data (looks data-driven already).
- Containers.java — chest inventories are plain 27-slot; **no double chests**,
  no loot-table filling for structure-placed chests (worldgen chest NBT
  `LootTable` field is dropped by VStructureGen; dungeons/mineshafts/trial
  chambers corridors give empty chests). (M — piggyback on the trial-chambers
  template-NBT hook: register pos→loot_table at placement, roll on first open)
- No: barrels, shulker boxes, ender chests, trapped chests (comparator+signal),
  chiseled bookshelves, decorated pots (break/insert), cauldrons (water/lava/
  powder snow storage, bottle fill), bells (raid pings, ring on hit), candles
  on cakes, item frames, armor stands, banners, signs (editing), skulk, spore
  blossoms, big dripleaf tilt, pointed dripstone falling/filling, lightning rod
  (see Lightning.java note), scaffolding, ladders/vine climb speed (client-side
  anyway), lodestone+compass, respawn-anchor charge particles. (each S-M;
  cauldron and bells most player-visible)
- TrialChambers.java (mine, for the record) — not modeled: ominous item
  spawner drips, per-mob ominous equipment loot tables, spawn-potential custom
  NBT (slime size etc.), vault client display-item cycling/connected-player
  packets, decorated pots in chambers, dispenser traps in chambers, heavy
  core/mace, breeze projectile deflection (breeze should destroy incoming
  arrows), trial explorer map in vault loot (map item is a dead item here). (M)

## mobs/

- Missing hostile mobs entirely (no factory/case): **cave_spider** (mineshaft
  spawners are also unimplemented — VStructureManager:1101 defers the spawner
  block), silverfish (infested blocks too), endermite (ender pearl 5% spawn),
  vex exists only as evoker summon (fine), warden + skulk shrieker pipeline,
  piglin_brute (bastion), zoglin (hoglin portal conversion), illusioner,
  giant/unused. 26.x additions to check against the jar's entity registry:
  creaking (+ creaking heart), copper golem?, happy ghast?, parched (exists!),
  nautilus (exists as water animal). (each S-M given the factory pattern)
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
  mid-fight exists; witch raid participation. Slime: split-on-death sizes
  (verify Slime handling — magma cube too). Piglin: no bartering (gold ingot
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
