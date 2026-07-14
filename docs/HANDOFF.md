# Handoff notes for other models

This file exists because Minecom is worked on by whichever Claude model
fits the difficulty of the task (Sonnet 5 for routine work, Opus for
harder problems, Fable for the hardest) — see the README's Development
approach section. When a task looks like it needs a stronger model than
whichever one is currently working, it gets logged here instead of
attempted half-correctly, so the next session (on the right model) has
full context to pick it up.

Format: one entry per task, newest first. Mark it done (don't delete it —
keep the trail) once picked up and finished, so this stays a useful log
of what got escalated and why.

---

## Enchanting engine landed (2026-07-14, Sonnet 5) — MASTERPLAN §3 Tier 1 item 1, v0.18.0

Table + anvil + grindstone, decompile-verified against 26.2 (EnchantmentHelper,
EnchantmentMenu, AnvilMenu, GrindstoneMenu, EnchantingTableBlock, Player's
enchantment-seed fields — all cached fresh under vanilla-src/, superseding any
stale pre-26.2-bump copies per CLAUDE.md rule 7), built on the now-bundled
data-driven enchantment JSONs rather than hardcoded tables. Full detail in
AUDIT.md's Enchants.java entry (search "DONE 2026-07-14"); summary:

- **Data**: extract_vanilla_data.py extended for enchantment.json,
  enchantment_provider.json, tags_enchantment.json, and — since
  `data/minecraft/items/*.json` doesn't exist in 26.2 (item component
  defaults live in `Items.java` code) — item_enchantability.json /
  item_repairable.json pulled from the jar's own "Default Components"
  datagen report (`reports/minecraft/components/item/*.json`; the datagen
  caching in find_report was generalized to find_reports_root, caching the
  whole reports/ tree once instead of one file). 1491 bundled files now,
  --validate clean.
- **Table**: real per-player persisted enchantment seed (Persist.java —
  `enchantSeed`), bit-exact getEnchantmentCost port (continuing RNG stream
  across all 3 slots, NOT reseeded per slot — a 30-cap that was in the old
  code doesn't exist in vanilla, removed), bookshelf air-gap check
  (EnchantingTableBlock.isValidBookShelf's transmitter-tile requirement, not
  just shelf-tile identity), seed-deterministic weighted offer selection
  (exclusive_set-aware, book one-less-offer trim), and the buttonId+1
  lapis/xp quirk — clicking a slot deducts 1/2/3 levels, NOT the displayed
  8/16/24-ish requirement (a genuinely easy detail to get backwards; verify
  against EnchantmentMenu.clickMenuButton directly if touching this again).
- **Anvil**: added raw-material repair (item_repairable.json) alongside the
  pre-existing same-item combine, switched enchant-merge fees from flat +1
  to the real per-enchantment anvil_cost, added rename via Minestom's real
  `PlayerAnvilInputEvent`/`AnvilListener` (net/minestom/server/listener/ —
  this exists despite not being under event/inventory/ with the other
  inventory events; a first pass looking only in that package would wrongly
  conclude Minestom has no rename support, so LOOK IN listener/ TOO before
  writing off a Minestom capability gap).
- **Grindstone**: entirely new — disenchant keeps curses
  (tags/enchantment/curse.json), non-curse enchants refund xp at the
  table's min_cost (NOT anvil_cost — a different formula from the anvil's
  fee, easy to conflate), repair-merge uses a 5% durability bonus (anvil is
  12%).
- **Verification**: 18 new SelfTest checks (data loading, fixed-seed offer
  determinism across 300 seeded rolls, cost-formula spot checks, anvil/
  grindstone pure-function checks) — selftest now 228/228. 3 new PlayTest
  scenarios driving the real block+event flow (table, grindstone, anvil
  rename) + the pre-existing anvil scenario's cost assertions corrected to
  match the REAL "price<=0 -> finalPrice 0 even with a REPAIR_COST tax"
  rule (the old test's "two pristine unenchanted pickaxes cost 2" premise
  was itself wrong — real vanilla charges 0 for a true no-op combine; fixed
  by damaging the test pickaxes so a genuine durability-improvement price
  exists for the tax to stack on). Full playtest run: 689/689 (one run
  during this session showed 688/689 — see the flake note below, unrelated
  and pre-existing).
- **Bug found + fixed en route**: Anvils.java and the new Grindstone.java
  were originally wired to recompute their preview (slot 2) on
  `InventoryClickEvent`, mirroring the pre-existing (pre-this-session)
  Anvils.java pattern — but real vanilla's `slotsChanged` fires on ANY
  container item change (`SimpleContainer.setChanged()`), not specifically
  on a click. This under-recomputes for programmatic/non-click item
  placement and is why the very first grindstone PlayTest attempt failed
  (setItemStack(0, ...) never triggered a preview). Fixed by switching both
  to `InventoryItemChangeEvent` (which the enchanting table code already
  used correctly from the start) — more accurate to vanilla AND fixes the
  latent gap. If any other block-UI subsystem in this codebase still uses
  InventoryClickEvent to gate its own recompute, it likely has the same
  latent bug — worth an audit pass.
- **Deliberate simplifications** (AUDIT.md has the full list): anvil block
  damage-on-use not modeled; rename-cost comparison uses only the stored
  custom name; table candidate ordering is alphabetical rather than the
  real in_enchanting_table tag's declared file order (internally
  deterministic, not bit-identical to a live server — no differential
  oracle exists for this subsystem to make that distinction observable).
- **Not touched, still open**: smithing table, stonecutter, loom,
  cartography table (AUDIT top-10 item 3 partially closed, not fully);
  LootTables.java's enchant_randomly/enchant_with_levels loot functions are
  now directly wireable against Enchants.allDefs() but not yet wired;
  Combat.java's mob-spawn-equipment enchant provider similarly unblocked
  (enchantment_provider.json bundled) but not yet wired.

**Escalation — pre-existing flake found, NOT caused by this work (out of
scope, logging per rule 3 rather than chasing it)**: `[trident] riptide on
dry land does nothing` (PlayTest, Trident.java — a file this session never
touched) failed once in a full-suite run and once more in isolation, but
passed clean across 5 other isolated + 1 other full-suite run in the same
session — genuinely intermittent, not a scenario-order artifact (it failed
running in complete isolation too). Trident.java's own gate logic
(`riptideStrength>0f && !wet -> return`) reads correct by inspection; the
likely culprit is a timing race between the comment's own claim ("Force
weather off explicitly — the background WeatherCycle keeps running for the
whole suite") and `WeatherCycle.setRaining(world, false)` actually taking
effect before the very next tick's `isInWater(player) ||
WeatherCycle.isRaining(...)` check, OR a residual-block-state race on the
water/air swap at the same reused coordinates the wet sub-test just used.
Two-attempt budget spent reading the code; needs either a stronger model or
just someone willing to add a few debug ticks and re-run repeatedly to
narrow the race. Not armed with a DIAG-style diagnostic (that pattern is
reserved for the untouched silk one per standing instructions) — a fresh
session should decide whether to add one or root-cause directly.

---

## State of the project — full inventory (2026-07-13, Fable)

One place to see everything done and everything not. Compiled from a full
read of every doc (STRATEGY/AUDIT/CONVENTIONS/PERSISTENCE/COMMUNITY-INTEL/
this file), the complete 127-file source survey, and the suite state on this
date. Deeper strategy lives in docs/MASTERPLAN.md (written the same day).

### What this is

A vanilla-parity Minecraft 26.1.2 server built from scratch on Minestom
(pinned 2026.07.01-26.1.2), written agent-orchestrated and
verification-first: every mechanic is ported from the decompiled reference
(never copied), claims a named vanilla source class, and ships with checks.
~49,500 lines of Java across 127 files, 1,477 bundled real-vanilla data
files (11 MB — recipes, loot tables, worldgen graphs, 1,185 structure
templates), a 35 MB single-jar output, 102 commits since 2026-07-09.

### Verification state (the product's spine)

- **--playtest: 677/677 green** (headless full server + fake player driving
  real gameplay through the event pipeline; section filter via
  `--playtest <substring>`; port via MINECOM_TEST_PORT, default ephemeral).
- **--selftest: 210/210 green** (server-less data-engine battery).
- **Worldgen region-diff: 99.2805% bit-exact** (full block state incl.
  properties) vs a real vanilla server over 1,296 chunks / 127.4M blocks —
  seed 20260708, chunks (-18,-18)..(17,17). One command, committed
  2026-07-13: `python3 scripts/worldgen_region_diff.py` (vanilla side is
  cached in ~/minecom-region-diff after the first run, so a re-run after a
  worldgen change is ~20 min on this laptop: minecom regen via the jar's
  `--genregions` mode + a ~30 s compare; `--radius 3` for a 2-minute
  spot-check, `--reuse-minecom` for compare-only, needs
  `mvn -q -DskipTests package` first). The old ad-hoc harness's 99.384%
  compared block NAMES only; the committed harness fills Minestom's
  omitted default properties from vanilla's own blocks.json and compares
  full states — the 0.104% delta is exactly the property-level divergence
  it exposed (leaves `distance` written as 7 instead of computed, see
  AUDIT worldgen section). Name-level mismatch classes reproduce the old
  log almost 1:1 (sculk patches, tree-foliage placement, ore-vs-stone
  swaps, snow, short_grass — each identified and tracked).
- **Differential fixtures**: 40-case piston extend/retract layouts captured
  from a REAL vanilla dedicated server, replayed cell-by-cell every run.
  The server-driving + Anvil-reading plumbing is factored into
  `scripts/vanilla_oracle.py` (2026-07-13) — the next differential harness
  (hoppers, fluids, fire, random-tick distributions, pathfinding) starts at
  `from vanilla_oracle import Server, RegionReader, prepare_workdir`.
- Flake discipline: every known flake in this file is either root-caused and
  fixed, or armed with failure-only diagnostics (DIAG silk) so the next
  firing explains itself. No known-red checks anywhere. The rule is now
  written down as the flake SLO (CONVENTIONS §10, 2026-07-13) and both
  harnesses print it in their footer when a run has any FAIL. PlayTest waits
  are tick-counted (not wall-clock) as of the same date — the suite's
  deadlines stretch with server load instead of flaking (MASTERPLAN §2.4).

### Done — by subsystem (compressed; AUDIT.md has per-item simplifications)

- **Worldgen (the crown jewel, ~15k lines)**: full vanilla density-function
  interpreter (VDensity) + exact noise stack (Xoroshiro, ImprovedNoise) +
  aquifers, carvers, surface rules, ore veins, sculk, biome source, feature
  decoration (trees et al.), Beardifier; structures via real jigsaw assembly
  from bundled NBT templates — villages, trial chambers, ancient cities,
  strongholds (full StrongholdPieces maze), ocean monuments (room-graph),
  woodland mansions (MansionGrid), pillager outposts, bastions, igloos,
  shipwrecks, ruined portals, ocean ruins, desert pyramids, jungle temples,
  buried treasure, nether fossils; concentric-ring + grid placement both
  bit-exact. End dimension on the same engine (spikes, chorus, gateways).
  Nether is deliberately approximate (the one XL worldgen gap).
- **Blocks/containers (~30 subsystems)**: chests (double/trapped/ender),
  barrels, shulkers, furnaces×3, brewing, anvils, cauldrons, composters,
  campfires, jukeboxes, lecterns, decorated pots, chiseled bookshelves,
  cake/candles, item frames, scaffolding, note blocks (618 lines of
  instrument rules), beds, respawn anchors, lodestones, structure loot
  (56 real chest tables, rolled on first open like vanilla), lid
  animations, harvesting, pumpkin carving, copper waxing, bubble columns,
  fire spread (verbatim FireBlock, 207-entry odds table machine-diffed),
  fluids (cellular sim — NOT vanilla-exact spread weighting), portals
  (with cooldown), explosions (1352-ray vanilla algorithm), TNT, snow,
  random-tick engine (grass/ice/crops/saplings/copper/bamboo/vines/
  farmland/amethyst on the real 3-per-section dispatch).
- **Redstone**: wire networks (15-decay, strong/weak), pistons with the
  full slime/honey structure resolver (differentially verified), observers,
  comparators (container reads incl. crafter/bulb/sensors), repeaters,
  hoppers, droppers/dispensers (~25 dispenser behaviors), crafters, rails
  (powered/activator/detector propagation), daylight detectors (real
  sun-angle timeline math), lightning rods, target blocks, weighted plates,
  copper bulbs, tripwires, sculk sensors + calibrated + the full vibration
  engine (frequency table, occlusion, taps for open/close/eat/drink/equip),
  shriekers with the faithful WardenSpawnTracker chain. Batched-per-tick
  update order (vanilla's depth-first recursion NOT modeled — the known
  design decision waiting on multi-core, HANDOFF item 4).
- **Mobs**: ~45 species live. From-scratch vanilla brain system (VBrain +
  Goals + VPathfinder A* ports) beside legacy Minestom-AI wiring (§11.1
  seam); natural spawner is a faithful NaturalSpawner port with mob caps +
  despawn + difficulty gating; full difficulty system (regional formula,
  equipment tables, reinforcements); villagers (trades, breeding with the
  real food economy, farmer harvest AI, profession blocks); warden (full
  state machine + anger + sonic boom); creaking + hearts; breeze; happy
  ghast (rideable, mount-order-deterministic); slimes/magma (sizes,
  split-on-death); silverfish + infested blocks; iron/snow golems; raids
  (bounded 3-wave); ender dragon (bounded fight); piglin bartering; sheep
  shearing; breeding + baby states.
- **Survival/data**: hunger/exhaustion/regen (vanilla FoodData constants),
  air/drowning, fall damage, XP, fishing, bows/crossbows/tridents (loyalty,
  channeling), thrown/splash/lingering potions, 13 drinkable effects,
  weather + lightning (rod redirect, witch conversion), real crafting
  (2x2 + table vs full Mojang recipe set), real loot-table evaluator
  (fortune/silk conditions; enchant functions inert — no enchanting
  system), anvil combining, /commands (~20).
- **Persistence**: region-sharded sidecar store (RegionStore + StateAdapter
  SPI, gzip-versioned shards, multi-core-ready by design) covering ~20
  subsystems: containers, redstone positions, mobs (+villager
  profession/inventory, sheep, babies, breeding cooldowns, slime sizes),
  crops, fire countdowns, trial-chamber config+progress, inhabited time,
  world extras. Anvil handles blocks. Overworld-only keys (known limit).
- **Infra**: Bootstrap (shared prod/test wiring), fake-player test
  framework, backup PreToolUse hook, CONVENTIONS enforced at 100% javadoc
  coverage, three-tier model routing (this file), decompile cache
  discipline (rule 7).

### NOT done — the honest gap list

**Player-visible gameplay (AUDIT.md Top-10 remnants):**
1. Enchanting table + grindstone + smithing/trims + stonecutter/loom/
   cartography (L — biggest missing player system; loot enchant functions
   inert for the same reason).
2. Taming (wolves/cats/horses/parrots), horse riding/saddles, leads, name
   tags (L).
3. Classic `minecraft:spawner` block entities — dungeons, mineshaft cave
   spiders, fortress blazes, stronghold portal room (M, pattern exists via
   TrialChambers).
4. Elytra + firework flight, ender pearls, eyes of ender, maps, bundles,
   shields-with-banners, totems, spyglass (S-M each).
5. Villager zombie-conversion/curing, gossip/reputation, sleep schedules;
   raid difficulty scaling + Bad Omen patrols (S-M each).
6. Missing mobs: bee (M-L), cat, allay, sniffer, frog/tadpole, armadillo
   extras, skeleton-horse trap, phantom night spawner, endermite-from-pearl.
7. Mob equipment enchantments + equipment drop chances; attack-cooldown
   (1.9 charge) model; server-enforced mining speed (haste/fatigue).
8. Bedrock-pattern golem building, armor stands, banners, sign editing,
   archaeology/brush, beacons (unlisted anywhere yet — no beacon system),
   conduits (same).
9. Nether: whole dimension is an approximate generator (XL — the single
   largest parity gap in the project); piglin gold-armor/zombification
   rules; hoglins.
10. 26.x oddities: sulfur cube (26.2+ only), dried ghast/ghastling, some
    26.x potion effects (oozing/weaving/infested/wind-charged).

**Architecture/process (the strategic queue, STRATEGY §6):**
- **Multi-core is entirely unstarted.** Everything runs on Minestom's
  default single instance thread + scheduler. The region-ownership design
  (Folia/MCHPRS-style islands, COMMUNITY-INTEL) exists only as intel notes
  + the persistence sharding built to match it. This is the project's
  entire durable performance thesis and its hardest correctness problem.
- **Verification-suite hardening (§6 step 2) is undefined** — named as a
  co-prerequisite for unification, no scope exists. MASTERPLAN proposes one;
  needs owner sign-off.
- **Unification pass blocked** on the above (CONVENTIONS §11 is the work
  order: two AI systems, naming/lifecycle drift, god-class splits —
  PlayTest.java is now 7,348 lines).
- **Minestom 26.2 bump**: scoped (5 call sites); the extraction script now
  exists and validates 1,476/1,476 against the bundled tree
  (scripts/extract_vanilla_data.py, 2026-07-13) — remaining gates:
  sulfur-cube gap, passenger-positioning reconciliation, re-running the
  extractor against the 26.2 jar. Owner go/no-go.
- **Update-order semantics** (vanilla depth-first vs our batching) waits on
  the multi-core redstone design — deliberate, do not do twice.
- **Security sweep** (STRATEGY §5 exploit catalog) not started; no
  SECURITY.md.
- **README rewrite in a human voice** + launch-conduct plan (STRATEGY §4)
  not done; LICENSE is still the placeholder, CLA infrastructure not set up.
- **CI landed 2026-07-13** (build + selftest per push, full playtest
  nightly — MASTERPLAN §2.5); no benchmark harness yet (the performance
  story currently has zero numbers behind it beyond worldgen throughput
  incidentals).
- Gamerule system absent (mobGriefing/keepInventory/etc. hardcoded);
  multi-dimension persistence keys; entities/*.mca export; item entities
  in flight.

### Where this sits on the agreed roadmap (STRATEGY §6)

Step 1 "finish first pass" — genuinely close for the overworld gameplay
surface (the list above is the remainder); nether + enchanting are the two
big outliers. Step 2 (CONVENTIONS lock ✔ / suite hardening ✗-undefined) is
the actual current gate. Steps 3-10 (unification → security → human review
→ launch → Lite profile → add-ons → version cadence) all pend behind it.
Multi-core sits outside the numbered sequence entirely — MASTERPLAN
proposes where it belongs.

---

## Open

### ~~Minestom 26.2 version bump~~ — DONE 2026-07-13 (Fable, owner-approved)

Landed as scoped (see the scoping entry below for the full delta analysis).
What actually happened, delta vs. the scoping's predictions:

- **pom 2026.07.01-26.1.2 → 2026.07.12-26.2; the 5 predicted call sites were
  exactly right** (Bootstrap sneak listener → `PlayerInputEvent.
  hasPressedShiftKey()`; PlayTest's two dispatch sites → `Player.refreshInput`
  via new pressShiftKey/releaseShiftKey helpers — refreshInput also syncs the
  sneak flag and fires the event with old-state semantics, mirroring the real
  packet path; `SlimeMeta` relocation; VEndGen `fakeUnit` returns `BlockVec`).
  One addition the scoping missed: the SlimeMeta `instanceof` migrated to
  `metadata.cube.AbstractCubeMeta`, because in 26.1.2 `MagmaCubeMeta extends
  SlimeMeta` (one instanceof covered both) but in 26.2 they are siblings.
- **Bundled data regenerated from the 26.2 jar**: 1,486 files,
  `--validate` 1,486/1,486 PASS. The extractor's DEFAULT_JAR now points at
  `~/mc-26.2/`; CLAUDE.md rules 7+8 updated (decompile source + file count).
- **The real migration cost was loaders vs. 26.2's serializer, which omits
  optional-with-default codec fields.** Every fix decompile-verified against
  the 26.2 jar: `interval_select` density function (NEW in 26.2, replaces the
  weird_scaled_sampler subtrees in spaghetti caves/entrances — VDensity case
  ported from `DensityFunctions$IntervalSelect`), scalar `biome_is` in
  surface rules, SpringConfiguration defaults (4/1/true), Geode*Settings
  defaults (~14 fields), PlaceOnGroundDecorator defaults (128/2/1), loot
  entity-predicate flattening (`type_specific` → `minecraft:type_specific/
  cube_mob` dispatch keys, `minecraft:vehicle`). Feature-type renames:
  `dripstone_cluster` → `speleothem_cluster` (not handled before, still not).
- **Roster reconciliation (suites demanded it)**: `cave_spider` is natural-
  spawning in 26.2 (sulfur_caves) — added to BUILDABLE, builder already
  existed. `sulfur_cube` landed as a deliberate passive STUB via `slimeLike`
  (no archetypes/swallowing/bucketing/shearing/breeding/fuse/split) — see
  AUDIT "26.2 bump" section; full parity is the sulfur Tier follow-up.
- **Leaves distance=7 divergence FIXED** (the AUDIT worldgen item): ported
  `TreeFeature.updateLeaves` into VTree — bucketed BFS from logs
  (#prevents_nearby_leaf_decay = distance 0), decorator writes now recorded
  like vanilla's decorationSetter and pre-marked visited, vanilla HashSet pop
  order reproduced (Pos.hashCode == BlockPos.hashCode + incremental-insert
  table shape). `updateShapeAtEdge` is not modeled (canvas has no shape
  updates).
- **Oracle scripts re-pointed**: `vanilla_oracle.JAR/LIBS` → `~/mc-26.2/`
  (own libraries root, never merged into `~/libraries`), `MC_VERSION`
  constant added; region-diff work dirs are now per-version
  (`<seed>_r<r>_<cx>x<cz>_26.2`), the 26.1.2 vanilla cache dirs kept.
- **Suites green on the final tree: SelfTest 210/210, PlayTest 678/678**
  (three PlayTest failures root-caused along the way: the mending scenario
  was double-firing PickupExperienceEvent on top of 26.2's new auto-pickup;
  the creeper explosion check exposed a REAL product ordering bug —
  remove-before-explode, vanilla explodes first — now matched to the
  decompile with an explicit self-exclusion; trident riptide + snow golem
  failed only under the vanilla-gen load overlap and pass unloaded —
  physics-measurement checks are the one class tick-counted waits can't
  fully immunize, noted for the next flake audit).
- **26.2 region-diff re-baseline (CANONICAL): 99.355432%** — seed 20260708,
  r18, 1,296 chunks / 127,401,984 blocks, full-state, vanilla side a real
  26.2 dedicated server (log: test-logs/regiondiff_seed20260708_r18_
  20260713-201246.log; work dir ~/minecom-region-diff/20260708_r18_0x0_26.2,
  26.1.2 cache kept in the unsuffixed dir). UP from 26.1.2's 99.2805%
  despite 26.2's worldgen changes: the updateLeaves port cut the leaves
  (props) classes ~72k blocks (spruce 103,339->40,074, oak 20,827->11,974);
  the remaining top classes are composition-identical to the old baseline
  (sculk gated off by design, tree/ore placement divergence pre-existing).
  No new 26.2-specific divergence classes appeared in the sampled region
  (no sulfur_caves surfaced in this area). Remaining leaves (props) blocks
  are plausibly secondary to tree-placement mismatches (different BFS
  neighborhoods), not a distance-algorithm bug — verify before chasing.

### ~~MASTERPLAN §2 verification hardening (items 1-4, 6)~~ — DONE 2026-07-13 (Fable)

Owner-approved batch, all landed and pushed:

1. **Region-diff harness committed** (§2.1) — `scripts/worldgen_region_diff.py`
   + the jar's `--genregions` mode. One command, canonical baseline
   **99.2805% full-state** (see Verification state above for the 99.384%
   name-only reconciliation — the delta is real property divergence the old
   harness could not see, chiefly leaves `distance`, now in AUDIT).
2. **Fixture factory** (§2.2) — `scripts/vanilla_oracle.py`, shared by the
   piston capture + region diff; proven by regenerating the piston fixture
   byte-identical. Found a latent unsigned-byte NBT bug (section Y -4..-1)
   in the previously-embedded reader. Next differential fixtures (hoppers,
   fluids, fire, random-tick distributions, pathfinding) are imports now.
3. **Flake SLO** (§2.3) — CONVENTIONS §10 + failure-only footer in both
   harness reports (CI's `" 0 failed"` grep unaffected).
4. **Determinism pass** (§2.4) — systemic fix instead of per-site rewrites:
   `tick()`/`waitFor()` now count actual server ticks (scheduler-driven
   counter), so all ~247 wait sites became load-immune at once; the two raw
   wall-clock sites (trial-spawner fight window, creeper swell timing)
   converted to tick counting. Wall-clock remains only as a 20x stall guard.
5. **Parity scorecard generator** (§2.6) — `scripts/parity_scorecard.py`
   writes docs/SCORECARD.md from the newest full-green suite logs (PlayTest
   report lines now carry a `[scenario-tag]` for grouping), the biggest
   region-diff log, AUDIT section counts, and fixture counts.

Not attempted (per owner instruction): pom 26.2 bump, unification pass,
scenarioDispenserBehaviors, DIAG silk. §2.5 (CI) had already landed.

**At-a-glance triage (2026-07-13, Opus)** — the Opus queue is worked:
the 26.2 upgrade is now SCOPED (below — the answer is "the API migration
is ~5 lines; the data re-extraction is the real project"), the
dispensed-animal flake is ROOT-CAUSED AND FIXED, and the silk-touch flake
is re-rated (its headline "~1/29" was one observation, not a rate; it is
now bounded at <1/500 and its two named leads are both disproven).

- **Opus:** ~~Minestom 26.2 upgrade~~ scoped, awaiting a go/no-go on the
  data re-extraction (see below). ~~Dispensed-animal flake~~ done.
  Silk-touch flake: still open but demoted — no longer worth chasing
  blind, see its entry for the exact conditions to catch it under.
- **Fable:** ~~Piston reorder-collision differential test~~ DONE 2026-07-13
  (40-case fixture from real vanilla, all bit-matching; reorder path fires
  10× under an execution witness — see the entry below, including why
  layouts alone could never have verified this).
- **Fable, but blocked:** Unification-pass mechanical cleanups — a
  project-owner sequencing decision (STRATEGY.md §6), and the highest-risk
  item here on the merits too (splitting a 5.3k-line test file without
  breaking 600+ scenarios). Not ready to start yet regardless.

### Minestom 26.2 upgrade (from pinned 2026.07.01-26.1.2) — Opus

**Triaged 2026-07-12 (Sonnet), Opus not Fable:** big blast radius (148
breaking changes cascading into a likely Minecraft-version bump, touching
every "decompile-verified" claim in this project), but the work itself is
investigative/migration in shape — audit the changelog against this
project's actual API surface, decide what's additive-only vs. real rework,
scope the re-decompile — not a novel algorithm or design problem. Careful,
thorough triage is what this needs, not Fable-tier problem-solving.

Minestom released `2026.07.12-26.2` today (github.com/Minestom/Minestom/
releases/tag/2026.07.12-26.2). This is a real version bump, not a patch:
it targets Minecraft 26.2 (data revision rv3), and ships 148 documented
binary-compatibility breaks, including `Entity#getPassengers()` changing
from `Set` to an ordered `List` (touches every mob-riding/boat/happy-ghast
call site that iterates passengers), `PlayerStartSneakingEvent`/
`PlayerStopSneakingEvent` removed entirely in favor of `PlayerInputEvent`
(this project's sneak-detection call sites would need auditing —
`Farming`'s bonemeal-vs-till gate on `isSneaking()` reads the live flag
directly so may be unaffected, but any EVENT-based sneak listener isn't),
`JoinGamePacket`/`RespawnPacket` restructuring, and entity metadata changes
for horses/slimes. Also new: `EntityType.SULFUR_CUBE`/`SulfurCubeMeta`
(a new vanilla mob type, not yet in this project's roster), a generated
`RegistryKey` constants system (`BlockKeys`/`MaterialKeys`/
`EntityTypeKeys` — existing `Block.STONE`-style constants stay unchanged),
and block-predicate support for enchantment/attribute/container/damage
data components (could simplify some of `LootTables.java`'s
`matchTool`/condition handling if picked up).

Not attempted this session — bumping the Minestom version almost certainly
means bumping the target Minecraft version too, which cascades well beyond
a dependency bump: this project's vanilla-parity data (bundled recipes/
loot tables/worldgen structures, `vanilla-src/`'s decompiled reference
sources, every "decompile-verified" claim in AUDIT.md/HANDOFF.md) is all
keyed to 26.1.2 specifically. Re-pinning needs a deliberate scoping pass
(what actually changes data-wise between 26.1.2 and 26.2, whether
`vanilla-src/` needs re-decompiling wholesale or just the touched classes,
whether the 148 breaks are additive-only for this project's actual API
surface or require real rework) before anyone starts changing `pom.xml`.
Flagging here rather than guessing at scope.

---

**SCOPING PASS DONE 2026-07-13 (Opus).** `pom.xml` deliberately NOT touched:
the scope splits cleanly into a trivial half and an expensive half, and the
expensive half is a project-owner call, not a coding one.

**Headline: the "148 breaking changes" number is almost entirely noise for
this codebase.** The release notes themselves caveat it — that count is a raw
ABI-checker dump, and *"a single record or return type change can produce
several entries"*. It is really ~28 distinct changes, inflated by per-accessor
entries (`TeamsPacket` alone = 22 lines, `Range$*` = 24, `JoinGamePacket`/
`RespawnPacket` = 22, `PacketRegistry$*` = 13). Walking all 148 entries against
this project's actual imports: **145 don't touch us. Three do.**

**The whole API migration is 5 call sites in 4 files:**

| File:line | Change |
|---|---|
| `Bootstrap.java:165` | `PlayerStartSneakingEvent` (removed) → `PlayerInputEvent#hasPressedShiftKey()` — the global "sneak dismounts any vehicle" listener |
| `PlayTest.java:4034`, `:4083` | same, in the boat scenarios' test dispatch |
| `VanillaMobs.java:1549` | `metadata.other.SlimeMeta` → relocated to `metadata.cube` |
| `VEndGen.java:212-217` | `GenerationUnit` return types `Point` → `BlockVec` (see below — the only HARD compile error) |

Each of the four "known-suspect" items from the triage above resolved
differently than assumed, all verified by grep + `javap` against the pinned jar:

- **`getPassengers()` `Set`→`List` is FREE.** All 8 call sites use
  `Collection`-generic methods (`contains`/`size`/`isEmpty`/for-each/`stream`/
  `List.copyOf`) and the project declares **zero** `Set<Entity>`. It compiles
  untouched. It also silently *fixes* a latent bug: `HappyGhastMob.
  controllingPassenger()` (line ~145) is documented as "first passenger" but
  iterates a `Set` — "first" is currently nondeterministic; an ordered `List`
  makes it actually correct. *(Fixed ahead of the bump 2026-07-13, Fable:
  `HappyGhastMob` now tracks mount order in its own list, so the 26.2 change
  becomes a no-op here rather than a behavior change.)*
- **`PlayerStartSneakingEvent`: the `isSneaking()` guess was right.** The 7
  live-flag reads (`Vibrations`, `HappyGhastMob`, `Containers`, `Boats`,
  PlayTest) are **not** in the ABI report and need zero changes. Only the 3
  EVENT-based sites break. (The bonemeal-vs-till gate actually lives in
  `Containers.java:249`, not `Farming`.)
- **`JoinGamePacket`/`RespawnPacket` restructuring: ZERO sites.** This project
  constructs exactly two packets — `TradeListPacket` (`VillagerTrades.java:212`)
  and `BlockActionPacket` (`Containers.java:82`) — and neither appears anywhere
  in the 148. That kills ~50 entries outright.
- **Horse/slime metadata: 1 site, not the horses.** `HorseMeta` is used
  nowhere (all 10 horse entries are no-ops). `SlimeMeta` is one
  fully-qualified `instanceof` — a one-line fix.

**The one real compile error nobody flagged** is not in the suspect list at
all: `VEndGen.java:212-217`'s `fakeUnit()` returns an anonymous
`new GenerationUnit()` declaring `public Point size()` / `absoluteStart()` /
`absoluteEnd()`. Confirmed by `javap` that 26.1.2's interface returns `Point`
today; 26.2 narrows those to `BlockVec`. Java forbids widening a return type
in an override, so this is a **hard compile failure**, not a warning. (By
contrast `WorldGen.java:28-30` is safe — `BlockVec implements Point`, so
*consuming* a covariant return stays source-compatible. Only the anonymous
*implementor* breaks.)

**The expensive half — and the actual reason not to just do this.** The
project does NOT get its vanilla data from Minestom's registries:
`data/VanillaData.java` loads **bundled JSON/NBT from
`src/main/resources/vanilla/` — 1,476 files** (recipes, loot tables, tags,
worldgen noise/carvers/biome params, 1,185 `.nbt` structure templates),
extracted from the real 26.1.2 server jar. A Minecraft-version bump means
**re-extracting all of it from a 26.2 server jar**, and:

- **There is no extraction script.** Searched the tree: none. `CLAUDE.md`
  rule 6 documents only the *decompile* path, never how those 1,476 files
  were produced. Whoever bumps this reconstructs that procedure from scratch.
  **This is the single largest undocumented risk in the upgrade** and is a far
  bigger job than the 5-line API migration.
  - **DONE 2026-07-13 (Fable): the script now exists** —
    `scripts/extract_vanilla_data.py` (Python 3, stdlib only) rebuilds the
    whole tree from the jar and `--validate` proves it: **1,476/1,476 PASS,
    0 FAIL** against the bundled 26.1.2 tree (JSON parsed-equal, .nbt
    byte-identical; ~0.7 s warm, ~13 s cold). Per-file provenance is in the
    script docstring. Notes: (1) `piston_reorder_cases.json` is the one
    non-jar-derived file (from `scripts/piston_vanilla_capture.py`), skipped
    by design; (2) `biome_parameters_overworld.json` and
    `features_per_step.json` are jar *code*, not jar data — the script runs
    the jar's own datagen (`net.minecraft.data.Main --reports`, needs
    `~/libraries/` from the bundler unpack, cached in `~/.cache/minecom/`)
    and ports `FeatureSorter.buildFeaturesPerStep` exactly; (3)
    `structure_biomes/*.json` value *order* in the bundled tree is provably
    inconsistent hand-extraction noise (consumer is a HashSet), so those are
    validated as sets — everything else is strict. For the 26.2 bump: run
    `--jar ~/versions/26.2/server-26.2.jar`, but note 26.2's registry-dir
    renames (if any) must be re-checked against the paths in the docstring.
- **`~/versions/` has only `26.1.2/`.** A 26.2 server jar exists (Mojang's
  manifest lists 26.2, released 2026-06-16) but is not downloaded.
  - **DATA SCOPING DONE 2026-07-13 (Fable, read-only — pom untouched).** The
    26.2 server bundler is downloaded and unpacked at `~/mc-26.2/`
    (own `libraries/` root, deliberately NOT merged into `~/libraries` — the
    oracle scripts classpath-glob that dir). The extractor runs clean against
    it: `scripts/extract_vanilla_data.py --jar
    ~/mc-26.2/versions/26.2/server-26.2.jar` builds 1,486 files (no
    registry-dir renames). Semantic diff vs the bundled 26.1.2 tree
    (test-logs/extract_262_vs_bundled_2612.log + scratch analysis):
    - **New content = the sulfur family**: `minecraft:sulfur_caves` biome,
      10 `structure/spring/sulfur_spring_*.nbt` templates, 5 new
      configured/4 placed features (sulfur spike/spring/pool),
      `sulfur_cave_gradient` noise, ~70 new sulfur/cinnabar recipes (+ the
      sulfur cube mob on the Minestom side).
    - **Worldgen changes to EXISTING output** — the 99.28% baseline will not
      transfer: `sloped_cheese` + cave `entrances`/`spaghetti_2d` density
      functions, overworld `surface_rule` + `noise_router`, ~55 configured
      features (trees among them), kelp/flower placed features. Expect a
      re-baseline run of `scripts/worldgen_region_diff.py` (vanilla side
      regenerates from the 26.2 jar) immediately after the bump.
    - **Serialization noise, not behavior**: 415 of 598 "changed" recipes
      only dropped the `category` field; loot tables dropped default
      `bonus_rolls: 0.0` (1,071 of 1,099 loot_blocks diffs are this shape) —
      the loaders must tolerate both, which they already do (fields are
      read with defaults).
    - 121 structure NBTs differ beyond DataVersion (trial-chambers decor
      beds, igloo top, high_rampart processor list); all other 1,064
      templates are byte-identical modulo DataVersion.
    - Gate per owner instruction: do NOT commit the bump until the
      extraction re-run against the 26.2 jar is confirmed passing and the
      owner signs off.

**`vanilla-src/` does NOT need a wholesale re-decompile.** It is a *cache*,
not a build input — 1,116 `.java` files, never compiled, never committed, used
only as porting reference. Rule 6 already prescribes lazy on-demand
decompilation. The right move is to re-point rule 6 at
`~/versions/26.2/server-26.2.jar` and re-decompile only classes actually
touched. The cost of a stale cached class is a wrong parity claim, not a
broken build. The real caveat: the many "decompile-verified against 26.1.2"
claims across AUDIT.md / HANDOFF.md / `docs/specs/` don't *break*, they become
*unverified* — worth staged re-verification, not a blocking one.

**Two things the bump CREATES rather than fixes** (parity scope, not migration
scope), and the reason this is a go/no-go rather than a chore:

1. **A brand-new mob gap.** 26.2 adds `EntityType.SULFUR_CUBE` + a
   data-driven sulfur-cube archetype registry. The project has zero
   sulfur-cube code, so targeting 26.2 means the roster is instantly missing
   a vanilla mob — plausibly more work than the entire API migration.
2. **Passenger positioning may start fighting us.** Minestom PR #3222 moves
   vehicle passengers onto vanilla attachment points *"including the special
   positioning used by boats, rafts, camels, animals, and happy ghasts"*, and
   removes `EntityUtils#getPassengerHeightOffset`. This project hand-rolls
   happy-ghast rider mechanics (`HappyGhastMob.tickRidden`/`travelFlying`) and
   boat seating (`Boats.java`), which may now double-apply. This is the one
   piece that needs eyes on real behavior, not a grep. Lower-risk behavioral
   watch items in the same class: shift-click transfer fix, `Entity#teleport`
   head-yaw sync, and changed **area-effect-cloud metadata defaults**
   (`ThrownPotions.java:117`/`:159` — compiles fine, defaults moved).

**Recommendation:** the pom bump + 5-line migration is a day's work and could
land whenever. Do NOT start it as a dependency bump, because it isn't one —
it silently commits the project to re-extracting 1,476 data files with no
existing script, plus a new mob, plus a passenger-positioning reconciliation.
Sequence it deliberately: (1) write and commit the data-extraction script
against the CURRENT 26.1.2 jar first, where the expected output is already
known-good and any bug is immediately visible as a diff against the bundled
files — that de-risks the whole upgrade and is worth doing regardless; then
(2) point it at 26.2 and take the bump as a unit.

Incidental finding, filed rather than fixed (it is in Fable's currently-
uncommitted `Boats.java`): `Boats.java:103` iterates the live passenger view
while calling `removePassenger` inside the loop — its two siblings
(`BubbleColumns.java:216`, `HappyGhastMob.java:215`) both correctly defend with
`List.copyOf`. Pre-existing CME risk, worth folding into whoever touches it.
*(Fixed 2026-07-13, Fable, alongside landing the bubble-columns work.)*

### ~~Structure loot, container open animation, creative portal crossing~~ — DONE 2026-07-12 (Sonnet)

Found live, not from HANDOFF/AUDIT scanning: the user was actually playing on
a running server and hit three real gaps in quick succession. Full detail
in AUDIT.md's Containers.java and Portals.java entries; summary:

1. **Structure chests were all empty.** `VStructureGen`/`VStructureManager`
   placed every structure's chest/barrel/dispenser BLOCK but never read the
   NBT `LootTable` field (or, for igloo/shipwreck, the structure_block
   "metadata" marker vanilla uses instead) — an established, DOCUMENTED
   simplification, but clearly not one the user wanted live. Built the
   missing piece: a bundled `loot_chests.json` (56 tables, extracted
   straight from the real server jar, not hand-transcribed), `LootTables.
   chest(idPath)`, and `Containers.registerLoot`/`rollPendingLoot` (a
   persisted pos→table pending-roll registry, resolved on first open —
   matching real vanilla's own resolution timing, not generation time).
   Wired at every chest/barrel/dispenser placement site across both
   structure-placement systems (villages through nether fossils — see
   AUDIT.md for the exact list and the couple of structures still
   uncovered: mineshaft cart loot, woodland mansion).
2. **No chest lid animation.** `player.openInventory()` only manages the
   inventory window client-side; it never told the client the physical
   block should animate. Real vanilla drives that separately — a
   `BlockActionPacket` (chest/trapped_chest/ender_chest) or an "open"
   blockstate toggle (barrel — a genuinely different mechanism). Neither
   existed anywhere in this project. Added both, plus the open/close
   sounds that ride along with them.
3. **Nether portals ignored game mode.** Every player waited the full
   survival ~4-second (80gt) standing time, when real vanilla crosses
   creative/spectator players instantly. Fixing that immediately exposed a
   second, previously-invisible bug: with no portal cooldown at all, a
   player landing inside (or right next to) another nearby portal on
   arrival would instantly cross back — forever. Added a decompile-verified
   simplified `Entity.portalCooldown` analog (300gt, refreshed while still
   touching a portal, so it only actually counts down once you step off).
   That same fix (`Portals.tryLight` now runs on every flint-and-steel
   click, not just from Portals' own listener) also surfaced a real,
   pre-existing crash: `tryLight`'s frame-search scan can walk ~24 blocks
   from the click and was calling `getBlock` unguarded, throwing on an
   unloaded chunk — caught via a flaky `--playtest "fire spread"` run, not
   guessed. Fixed at the source (`safeAir`/`obsidian` check `isChunkLoaded`
   first), so both call sites benefit, and normal play near the edge of
   explored terrain doesn't risk it either.

Also, while re-verifying the iron golem scenario in isolation during this
pass: found (not fixed as part of the above, just noticed and cleaned up
since it kept muddying `--playtest "iron golem"` runs) that
`scenarioIronGolem` didn't pin its own world time, so a spawned zombie could
combust in daylight (`VanillaMobs.sunburn`, decompile-verified real
mechanic) before the golem's own hit ever landed, making the scenario fail
when run standalone via the section filter (though not in the full ordered
suite, where an earlier scenario happened to leave time in a safe state).
Pinned night at the top, restored day at the end, matching every other
scenario's own time-management convention. The launch-velocity check still
has a rare (~1/5 in isolation) residual poll-timing flake — tightened from
worse but not fully closed; needs live instrumentation next time it's
caught, noted in-source rather than guessed at further.

All new/changed coverage: `scenarioStructureLoot` (chest/barrel/dispenser
loot rolls once and only once, barrel lid blockstate toggles), extended
`scenarioPortal` (creative instant-crossing exercised through the real
tick() scheduler, not the debugTravel test bypass). Full suite: 617-619/619
passed across multiple runs, the only 2 failures being Fable's
already-known in-progress bubble-column work (uncommitted, unrelated).

### ~~Rare (~1/20) silverfish ambush-spawn flake~~ — DONE 2026-07-12 (Sonnet)

Found while fixing the merge-with-stone test bug below.
`scenarioSilverfish`'s "mining infested stone without silk touch springs a
silverfish ambush" check failed once in ~20 runs. Instrumented directly
(temporary prints in `InfestedBlocks.spawnInfestation` + the failing check,
removed after): two real, separate causes, both fixed.

1. **Real bug, not a race**: `VanillaMobs.silverfish` (like most mob
   factories) calls `mob.setInstance(instance, pos)` without joining the
   returned `CompletableFuture<Void>` — confirmed by instrumentation that
   the check still occasionally failed even with the entire real-time
   `waitFor` window removed (an *immediate* post-break check), which rules
   out the usual sleep-vs-real-tick-skew class of flake this project
   otherwise sees; the entity genuinely isn't always registered in
   `world.getEntities()` the instant `setInstance` is called. Fixed by
   joining specifically in `silverfish()` (it backs `InfestedBlocks.
   spawnInfestation`, an ambush a player expects to see instantly) — not a
   sweep of every mob factory, which fire-and-forget by design and haven't
   shown this symptom.
2. **Genuine test-coverage gap, same class as the merge-with-stone bug
   right below**: the "mining ambush" and "wake-up-friends releases a
   fresh silverfish" checks both used to `waitFor(...)` before checking,
   giving the freshly-spawned silverfish real time to roll its own
   `SilverfishMergeWithStone` goal and vanish into the flat test world's
   solid floor before the check ever ran. Fixed by checking immediately
   (ambush spawn is synchronous, no wait needed at all) and by driving
   `InfestedBlocks.wakeFriends` directly instead of racing its natural
   20gt countdown (kept the natural-countdown path for the *separate*
   "~1s delay" check, which needs to exercise the real timer).

29 clean playtest reruns of the section after the fix (`--playtest
silverfish`, test-logs/playtest_silverfish_fix_verify.log has one). One
adjacent, still-open, much rarer flake below.

### Rare silk-touch-ambush-contamination flake — Opus (STILL OPEN, but re-rated and now self-diagnosing)

**Worked 2026-07-13 (Opus): could not reproduce, and the headline rate was
wrong.** Rather than leave it un-actionable, the check is now armed to explain
itself the next time it fires in anyone's run. Summary of what changed:

**The "~1/29" was never a rate — it was one observation.** It surfaced once
during the 29 reruns done to verify the *previous* silverfish fix. Post-fix run
totals now stand at **1 failure in ~227 runs**: 29 (the sighting) + 40 (Sonnet,
2026-07-12) + 158 fresh instrumented runs this pass (90 at 6-way parallelism,
then 68 more), all clean. A true 1/29 rate has only a ~0.5% chance of producing
0 failures in the 198 runs since. **Realistic rate is <1/200**, likely much
lower — so "run it a few more times and watch" is not a viable strategy, and
that is precisely why chasing it further by brute force was abandoned.

**Both named leads are disproven, so don't re-run them:**
1. **NOT an unjoined `setInstance` future.** `silverfish()` already joins, and
   independently: registration is synchronous whenever the target chunk is
   loaded (see the AUDIT.md cross-cutting entry — verified against decompiled
   Minestom, `loadOrRetrieve` returns an already-completed future). The test
   world's chunks are pre-loaded.
2. **NOT `clearEntitiesExceptPlayer` skipping an entity mid-iteration.**
   `Instance.getEntities()` is backed by `ConcurrentHashMap.newKeySet()`, whose
   iterator is weakly consistent — it neither throws nor skips live elements
   when entries are removed during traversal. Streaming it while calling
   `Entity::remove` is safe.

**What's left, and the one live hypothesis worth testing.** `spawnInfestation`
has exactly two callers: the block-break listener (silk-gated by an early
return, so it cannot fire here) and `InfestedBlocks.wakeFriends`. So a stray
silverfish in the silk window means **something released one from an infested
block**. The interesting candidate: the bare-hand sub-test's ambush silverfish
lives for `tick(5)` before being cleared, long enough to roll its
`SilverfishMergeWithStone` goal (1-in-10/tick × 1-in-6 directions ≈ 8% over
those 5 ticks) and merge **downward into the flat test world's own stone
floor** — which converts a floor block to `INFESTED_STONE` and leaves it there,
inside `wakeFriends`' ±10 X/Z, ±5 Y scan radius, for the rest of the scenario.
That is a real, un-cleaned-up piece of state the scenario creates and never
removes. What is *not* yet explained is what would then fire `wakeFriends`
during the silk window (it needs a silverfish to take damage, and nothing
should be alive to take it) — which is exactly what the instrumentation now
captures.

**The check now self-diagnoses (`scenarioSilverfish`, in-source).** On failure
only, it dumps: (a) any silverfish already alive at window start (survivor), (b)
every silverfish *spawned during* the window via an `EntitySpawnEvent` listener
(fresh spawn), (c) the stray's UUID cross-referenced against that list, and (d)
the floor/above blocks across x=50..56 (catching the merged-into-floor infested
block). Those four facts split every remaining hypothesis. **Next time this
fires — in a full-suite run, on anyone's machine — the log will contain the
answer.** Don't chase it blind before then; grep test-logs for `DIAG silk`.

**Triaged 2026-07-12 (Sonnet):** needs live instrumentation + careful
diagnostic reasoning once caught, not novel design work — Opus-sized.
Only escalate to Fable if instrumentation turns up something genuinely
structural (a real concurrency bug in the entity/instance system itself)
rather than another local timing/ordering issue like the two already
fixed in this same scenario.

Surfaced once (1/29 reruns) chasing the entry above, in the SAME scenario:
"silk touch never springs the ambush" occasionally sees a stray silverfish
even though silk-touch mining never calls `spawnInfestation` at all (an
early return in `InfestedBlocks.register`'s listener, before the spawn
call). Likely a residual silverfish from the *prior* sub-test surviving
`clearEntitiesExceptPlayer()` somehow, but not confirmed — the two fixes
above already closed the two most likely causes without fully eliminating
it. Rare enough (down from ~1/20 combined to ~1/29 for this one specific
check) that further live instrumentation is left for next time it's caught.
Ran 40 fresh reruns 2026-07-12 (Sonnet) trying to catch it again (in case
the trial chambers persistence work below disturbed anything nearby) — 0
failures, consistent with a still-real but genuinely rare event, not
caught this pass either.

### ~~Rare (~1/15-1/25?) dispensed-animal-spawn flake~~ — DONE 2026-07-13 (Opus)

**Root-caused by direct measurement, and the strong lead below turned out to
be WRONG — the fix is in the test, not in `animal()`.** `.join()` was never
added to the shared mob-spawn path; it would have been a no-op. Details first,
because the refutation is the useful part:

**The lead is disproven, twice over.** (a) *By decompile:* an entity only
enters `world.getEntities()` inside `setInstance`'s
`loadOptionalChunk(...).thenAccept(...)`, and `InstanceContainer.loadOrRetrieve`
returns `CompletableFuture.completedFuture(chunk)` when the chunk is already
loaded — so `thenAccept` runs **inline on the calling thread** and registration
is synchronous. The dispenser's own `rs()` already force-loads that chunk
(`InstanceContainer.setBlock` does `loadChunk(...).join()` on a miss), so the
future is *already complete* by the time the pig spawns. (b) *By measurement:*
instrumenting `EntitySpawnEvent` shows `tracked=true` in `world.getEntities()`
at the instant of every single spawn. The pig is never missing.

**The actual cause: the check was racing the pig's own AI.** `VanillaMobs.
animal()` wires a `WaterAvoidingRandomStroll` goal, so a dispensed pig starts
walking within a tick or two. The check polled the pig's *current* position for
an exact `blockZ == 210` (and `blockX` within ±1), but `waitFor` only polls
every 200ms (`tick(4)`). Measured drift across 12 runs: the pig left that box
after **161ms**, 443ms, and 2255ms in 3 of them — and 161ms is *inside a single
poll gap*. If the pig starts strolling before the first poll that observes it,
it leaves the box, keeps wandering (measured to z=211.25, x=53.25) and never
returns — so the entire 3s window fails. That ~1-in-12 chance of an early
enough stroll is exactly the observed ~1/20 flake rate.

**Fixed in `scenarioDispenserBehaviors`** by latching where the pig was
**spawned** (an `EntitySpawnEvent` listener + `waitFor` on the latch) instead of
polling where it currently *is*. That is what "places a pig in front" actually
asserts, it cannot decay, and it is strictly *stronger* than the old check —
it now pins the exact block (`blockX == 51 && blockY == Y+1 && blockZ == z`)
rather than accepting anything within a ±1 box.

**Statistics.** Baseline before the fix: **0 failures in 40** isolated runs, and
**0 in 30** more under 6-way parallel CPU load — i.e. the flake would not
reproduce on demand at all, which is itself why the old "1/5 in isolation" read
was misleading (it was one failure in five runs, not a measured rate; a true
1/15 rate has only a ~0.8% chance of surviving 70 clean runs). The fix is
therefore justified by the *mechanism* being measured directly, not by a
before/after count — the before/after count is exactly what this flake's rarity
made untrustworthy. After the fix: see the verification runs below.

**Historical triage + the (wrong) lead, kept for the trail:**

**Triaged 2026-07-12 (Sonnet):** already has a strong, specific lead (see
below) — this is confirm-and-fix work (get real before/after statistics
or a live instrumented catch before touching a path shared by ~20 mob
types), not a from-scratch investigation. Opus-sized, not Fable-sized.

Found 2026-07-12 (Sonnet) during a full-suite verification pass unrelated
to this scenario: `scenarioDispenserBehaviors`'s "dispensed spawn egg
places a pig in front" occasionally fails to find the pig in
`world.getEntities()` within the 3s `waitFor` window. Reproduced in
isolation (1/5 on a small rerun, consistent with a real, if rare, rate).

**Likely lead, not confirmed:** `Mobs.spawn("pig", ...)` routes through
`VanillaMobs.animal(kind, instance, pos)` — a single shared factory for
~20 passive mob types (cow/pig/sheep/chicken/rabbit/goat/horse/donkey/
llama/turtle/panda/polar_bear/armadillo/camel/fox/frog/wolf/parrot/
zombie_horse/ocelot) — which calls `mob.setInstance(instance, pos)`
without joining the returned `CompletableFuture<Void>`, the exact same
shape bug confirmed and fixed for `VanillaMobs.silverfish()` earlier this
session. Deliberately did NOT add `.join()` here without stronger
evidence: unlike silverfish (a narrow, rare-ambush-only spawn path),
`animal()` is a broad, shared, moderately-hot path across 20 common mob
types — the same caution HANDOFF's zombie-melee-damage entry already
applied (before that flake turned out to have a totally different, unrelated
root cause — sunburn, not this pattern — so pattern-matching alone isn't
enough confirmation). Needs either a dedicated instrumented repro or actual
before/after statistics before touching a path this widely shared.

**That caution was right, and is now 2-for-2.** Both times this
`setInstance`-without-`.join()` pattern has been pattern-matched onto a flake
(zombie melee damage, then this one), it has been the wrong culprit — sunburn
there, an AI-stroll race here. The pattern is real but *benign wherever the
target chunk is already loaded*, which is every call site that spawns into
terrain someone has already touched. Do not "fix" it on sight in the remaining
~20-type `animal()` path or anywhere else without evidence that a *specific*
call site spawns into an unloaded chunk AND reads the entity back immediately.
The one place it was genuinely fixed (`silverfish()`) is documented in-source
as such.

### ~~Rare (~1/30) unarmored zombie melee damage flake~~ — DONE 2026-07-12 (Sonnet)

Found 2026-07-12 (Sonnet) while fixing the flake pass below: the trident
scenario's "melee hit deals ~8 damage" check occasionally measures 1.0
instead. Reproduced and instrumented directly — the zombie has no equipped
armor (already stripped defensively, since a DIFFERENT known bug lets
`maybeEquipArmor` roll gear onto it), isn't a baby, isn't a "leader" zombie
(max health unboosted so no attribute-scaling path applies), and its armor
attribute reads a flat 2.0 every time (mathematically nowhere near enough to
explain an 8->1 reduction on its own). Armor-stripping was applied as a
defensive measure regardless (it guards a real, different failure mode) but
does not explain this specific case — root cause still unknown at the time.

A `.join()`/`setInstance`-race lead was tried and left unconfirmed (30
baseline reruns came back clean, not enough signal either way — see below,
kept for the record).

**Actually root-caused and fixed 2026-07-12 (Sonnet), later the same
session:** recognized the *exact* symptom — "took 1.0" — from fixing
`scenarioIronGolem`'s identical-looking flake earlier this session:
`VanillaMobs.sunburn` deals exactly 1 fire-tick damage/second to an undead
mob standing in daylight, and `scenarioTrident` never pinned its own world
time (unlike most other scenarios). The zombie combusts in whatever
ambient daytime state the suite happens to be in when this scenario runs,
and the FIRE damage — not the trident hit — is what the check measured.
Same fix as the golem scenario: pin `world.setTime(14000)` (night) at the
top, restore day at the end. 30/30 clean reruns on the melee-damage check
specifically (down from a real ~1/30 failure rate) — high confidence this
was the actual cause, not a coincidental improvement. The earlier
`.join()`/tick(1) mitigation (below) was left in place too — harmless,
and still a real bug in `VanillaMobs.zombie()` even if not THIS flake's
cause.

**Unconfirmed lead, kept for the record (2026-07-12, Sonnet, same session
as the silverfish ambush-spawn fix above):** this scenario spawns the
zombie and attacks it with *zero* tick delay in between — the single
fastest spawn-then-hit path in the whole suite — and `VanillaMobs.zombie()`
has the exact same shape bug just confirmed and fixed for `silverfish()`:
`mob.setInstance(instance, pos)` called without joining the returned
`CompletableFuture<Void>`. Tried a test-side `tick(1)` between spawn and
attack (giving the future a real tick to settle) as a repro/fix check, but
couldn't get a clean before/after comparison — 30 baseline (unpatched)
reruns came back clean too. Deliberately did NOT speculatively add
`.join()` to `VanillaMobs.zombie()` itself the way `silverfish()` got
fixed — zombie is the most commonly-spawned mob in the game, so that
change still needs its own confirming evidence, not just a plausible
analogy; this lead is independent of the sunburn fix above and may still
be a real (much rarer, or already-mitigated-by-the-tick(1)) issue.

Also found, NOT fixed (2026-07-12, Sonnet): during the 30-rerun
verification pass, run 22 hit a completely different, unrelated failure
in the SAME scenario — "loyalty-enchanted throw connects with the target"
and the trident-return check both failed together (a genuine ~1/30-ish
rate, one occurrence in 30 runs). Not investigated further — logged here
rather than guessed at, needs live instrumentation on next catch.

### ~~Random-tick consumers tail~~ — ALL DONE 2026-07-12 (Sonnet)

The engine landed 2026-07-12 (Fable, `blocks/RandomTicks.java`) with eight
handlers (see AUDIT). ~~Bamboo growth~~ **done 2026-07-12 (Sonnet)** —
BambooStalkBlock port (1/3 roll, air+light>=9 gate, 16-block cap with an
unconditional stage-flip at height 15, leaf-crown cascade), playtest
coverage added to `scenarioRandomTicks`. ~~Vine spread~~ **done 2026-07-12
(Sonnet)** — VineBlock.randomTick's growth half (`RandomTicks.spreadVine`):
corner-wrapping horizontal extension, upward/downward face-copying growth,
the 9x3x9/5-vine density cap; reused `Placement`'s existing clockwise/
counterclockwise/opposite/offset helpers (same package) rather than
duplicating direction math. Neighbor-update-driven detach isn't ported (no
generic block-support-removal system in this codebase to hook into) —
AUDIT.md. ~~Grass bonemeal~~ **done 2026-07-12 (Sonnet)** — not actually a
random-tick consumer (it's a direct `Farming.boneMealGrass` player/dispenser
interaction, same dispatch point as crop/sapling bonemeal), and "mycelium"
turned out to be the wrong framing entirely — decompile-verified real
vanilla MyceliumBlock isn't bonemealable at all. Ported GrassBlock.
performBonemeal's real 128-attempt scatter walk against the real bundled
GRASS_BONEMEAL feature data (not approximated); two secondary sub-branches
simplified out and documented (AUDIT.md) — short-grass-to-tall-grass
re-rolls, and the 1/8 biome-specific-decoration branch (would need bridging
this project's worldgen-time Canvas system to live gameplay, a separate,
bigger task). New playtest coverage folded into `scenarioFarming`.

~~Fire spread~~ **done 2026-07-12 (Sonnet)** — the risk analysis this entry
asked for turned up less risk than feared: real vanilla's fire spread has
no `mobGriefing`-style gate at all (decompile-verified — confirmed absent
from `FireBlock.tick`), so "griefing semantics" reduces to the same "no
gamerule store, assume default-on" simplification already used for
SPREAD_VINES/GRASS_BONEMEAL, and "block burn odds" is just a big-but-flat
data table (207 entries), machine-diffed against the decompile for an
exact match before shipping. The one genuine complication: fire doesn't
fit `RandomTicks.java`'s chunk-sampled engine at all — real `FireBlock`
self-reschedules its own SCHEDULED tick every 30+rand(10) ticks per block,
so this is a new, separate tracked-position + shared-scheduler subsystem
(`blocks/FireSpread.java`), the same shape Campfires/Jukebox already use
here (not Redstone's power-source tracked-position idiom). Ported
verbatim: age progression (0-15, biased to stay put), checkBurnOut on the
6 cardinal/vertical neighbors (consume-and-maybe-relight or just remove,
priming TNT if that's what burned), the 3x3x6 spread-attempt volume
weighted by igniteOdds/(age+30)+difficulty, rain extinguishing (gated on
sky exposure via `RandomTicks.skyExposed`, widened to package-private for
reuse) unless the block below is netherrack/magma ("infiniburn"). Also
wired the two existing fire-placement call sites (Combat.java's fire
charge, Redstone.java's dispenser flint-and-steel) to register with the
new tracker, and — a genuinely separate but directly adjacent gap found
while scoping this — added the missing PLAYER-direct flint-and-steel case
(`PlayerUseItemOnBlockEvent` only handled TNT priming before; general
fire-lighting on a clicked face was dispenser-only). Not modeled:
`EnvironmentAttributes.INCREASED_FIRE_BURNOUT` (this project's
environment-attribute system doesn't expose it yet — treated as always
off), `isFaceSturdy`'s exact per-shape solidity (approximated as
`Block.isSolid()`, matching this file's existing coarse-solidity pattern),
and the nether/end infiniburn tags (only the overworld's netherrack/
magma_block pair is modeled). New `scenarioFireSpread` playtest coverage
(4 checks: player-lit ignition, direct-neighbor burnout, unsupported
self-extinguish, wider-volume spread), 5/5 clean across reruns.

~~Crop growth~~ **done 2026-07-12 (Sonnet)** — the same re-assessment
pattern as fire spread above: the "must update the farming/villager
playtest scenarios" risk this entry warned about turned out unfounded —
neither `scenarioFarming` nor `scenarioVillagerFood`'s farmer-harvest check
depends on growth timing at all, both pre-place mature crops directly and
drive age via bonemeal, so nothing needed touching there. `RandomTicks.
growCrop`/`cropGrowthSpeed` ports `CropBlock.randomTick`/`getGrowthSpeed`
exactly (decompile-verified): light gate (raw brightness >= 9), the 3x3
farmland-moisture-weighted growth-speed scan below the crop (center full
weight, ring cells /4; unmoistened farmland=1.0, moistened=3.0), halved for
same-type neighbors on both axes or a lone diagonal same-type neighbor,
then the `nextInt((int)(25/growthSpeed)+1)==0` roll — covers wheat/
carrots/potatoes/beetroots. Replaces `Farming.growthTick`'s old flat
100-tick/20%-roll sweep (deleted, along with its scheduler registration and
the now-dead `Farming.instance` field); `Farming.CROPS` itself is
untouched (still gates bonemeal + persistence), and the new handler is a
fidelity improvement there too — it now applies to any crop block, not
just `CROPS`-tracked ones, matching real vanilla. New coverage folded into
`scenarioRandomTicks` (light gate, both growth-speed branches, the
per-crop maxAge cap), 5/5 clean reruns.

~~Sapling growth~~ **done 2026-07-12 (Sonnet)** — the small (S) follow-up
this entry deferred. `RandomTicks.growSapling`/`Farming.advanceTree` ports
`SaplingBlock.randomTick`/`advanceTree` exactly (decompile-verified): light
gate (raw brightness above the sapling >= 9), a 1/7 roll, and — the part
the old scheduled-delay approximation missed entirely — a real two-stage
climb, not a straight jump to a tree. A stage-0 sapling that rolls
successfully just cycles to stage 1; only a SECOND successful roll against
a stage-1 sapling actually grows the tree. `Farming.boneMeal`'s sapling
branch was calling `growTree` directly (instant tree, ignoring the stage
entirely) — real vanilla's `performBonemeal` also just calls
`advanceTree`, so bone meal needed the same two-application fix, found and
fixed as the same change (not a separate gap). Covers all 8 real sapling
types (oak/spruce/birch/jungle/acacia/dark_oak/cherry/pale_oak); the tree
SHAPE logic itself (`Farming.growTree`) is unchanged, only the
trigger/pacing around it. New coverage folded into `scenarioRandomTicks`
(stage-1 climb, tree growth, light gate) and `scenarioFarming` (the
bonemeal two-application behavior), 5/5 clean reruns.

### ~~Persistence adapter tail~~ — ALL DONE 2026-07-12 (Sonnet)

The persistence core landed 2026-07-12 (Fable): `StateAdapter` SPI +
`RegionStore` region shards + 9 adapters + mob snapshots + inhabited time
(docs/PERSISTENCE.md has the full status). What remains is mechanical
now the SPI exists — one small adapter each, copying the existing
patterns (Containers/Furnaces are the reference implementations).

~~Small block entities~~ **done 2026-07-12 (Sonnet)**: campfire (4 cooking
slots + progress/time), jukebox (disc + playback progress), lectern (book +
page), decorated pot (single item stack), chiseled bookshelf (6 slots +
last-touched slot), shulker box (27-slot inventory, same shape as chests) —
all copying Hoppers/Furnaces' exact `StateAdapter` shape. Composter, bells,
and note blocks turned out to need NO adapter at all: composter's fill
level and note blocks' pitch already live in block state (Anvil-persisted),
and bells have no real persistent state beyond a test-only ring counter —
confirmed by reading each file before assuming a gap existed. Along the
way, found and fixed a real (not test-only) bug the new adapters' longer
setup sequence exposed: `Redstone.activated`/`blockPowered` called
`instance.getBlock` on unchecked neighbor positions, NPE-crashing a
scheduled tick when a redstone-adjacent block sits one block from an
unloaded chunk boundary — every OTHER position-scanning loop in
`Redstone.java` already guards with `instance.isChunkLoaded(...)` first
(7+ existing call sites), these two just didn't; fixed by adding the same
guard. Reproduced 100% (not flaky) before the fix, 100% clean across 3
reruns after. scenarioPersistence extended with 6 new save/wipe/reload
checks (test-logs/playtest_persist_fixed_*.log). Also found (unrelated,
while re-running the full suite) a genuine test-coverage bug in
`scenarioSilverfish`'s merge-with-stone check, same class as the
villager-bed-count bug from the earlier determinism pass: the merge rolls
uniformly among 6 directions, and the flat test world's own floor is a
valid (always-solid) target directly below the silverfish, but the
verification loop only ever scanned the 4 explicitly-built side walls —
confirmed via direct instrumentation (mergePos landed on the untested floor
tile in the failing runs), not a race. Fixed by adding the floor position
to the check; 8/8 clean afterward.

~~Per-mob extras~~ **done 2026-07-12 (Sonnet)**: sheep color/sheared
(`SheepMeta`), baby state (`AgeableMobMeta` — any baby-capable mob, not
just animals), breeding cooldown (`Breeding.cooldownTicksRemaining`/
`setCooldownTicks`, two new public accessors), and — noticed while in here,
HANDOFF's own note said "once sizes exist" and they now do — slime/magma
cube size, restored via the same explicit-size factory `Combat.death`'s
split-on-death already uses (`Mobs.spawn`'s plain path rolls a fresh random
size, so a saved size bypasses it) in RegionStore.collectMobs/restoreMob.
The cooldown is the trickiest of these: `Breeding.java`'s internal
`COOLDOWN` map is keyed by the entity's ephemeral runtime id, which is
reassigned on every respawn — so it's persisted as a relative "ticks
remaining" delta (computed against Breeding's own tick counter at collect
time, re-armed against the same counter at restore time) rather than the
raw absolute value, so it survives the id change and doesn't care that
Breeding's tick counter itself resets on restart. IN_LOVE (30s) is
deliberately NOT persisted — too short-lived to be worth it, same
"acceptable loss" precedent as in-flight item entities. Also not modeled: a
baby's remaining grow-up time (the 20-minute timer is a one-shot scheduled
task, not tracked state, so a restored baby gets a fresh 24000-tick timer)
— noted in AUDIT.md. 4 new scenarioPersistence checks, 17/17 clean across
reruns.

~~Position-anchored scheduled ticks~~ **done 2026-07-12 (Sonnet), the
FireSpread slice of it** — scoped and found smaller than feared: the only
system in this project shaped like "a position tracks its own
self-rescheduling countdown, not polled reactively" is `FireSpread.java`
(built earlier this session — Redstone's daylight-detector/lightning-rod
trackers are a different idiom, power sources polled by the redstone
sweep, not counting down on their own). Added a `StateAdapter` copying
Campfires' exact shape (`POSITIONS`/`COUNTDOWN` collected/restored keyed
by position), so a restart no longer silently stops spreading/aging/
burning-out for every fire that was mid-countdown — previously
indistinguishable from the block just sitting inert, since the block
itself was already persisted as ordinary chunk data regardless. 2 new
scenarioPersistence checks (wipe drops the tracking, reload re-arms it),
19/19 clean reruns.

~~Trial chambers persistence~~ **done 2026-07-12 (Sonnet)** — turned out
to need both the runtime progress AND the per-position config persisted
(not just progress, as first assumed): production uses a real
`AnvilLoader`, so an already-visited chunk restores from its saved block
data on restart rather than regenerating, meaning the structure-placement
hook that derives the config never fires again for it. See
`TrialChambers.java`'s adapter Javadocs and AUDIT.md for the full writeup.
This closes out the persistence adapter tail entirely — nothing left open
in this entry.

### ~~Redstone parity — remaining summit after the 2026-07-11 pass~~ — ALL SONNET-TIER WORK DONE 2026-07-12
### (items 1-3 done 2026-07-11/12; 4 is a design decision, not a task — deliberately not attempted; 5's 3 sub-items all resolved 2026-07-12: vibration-tap gaps done, the other two confirmed non-issues)

The redstone-parity pass (see Done entries + AUDIT.md updates of this date)
landed: piston slime/honey chains, copper bulbs, weighted plates, lightning
rods, crafter, sculk sensors + calibrated + vibration engine, powered/
activator rail line propagation, target-block emission wiring (pre-existing),
and the dispenser behavior table (projectiles, spawn eggs, minecarts, bone
meal, flint&steel, buckets, shulkers). What "fully complete" still needs:

1. ~~Thrown potions~~ **Done 2026-07-11 (Fable, same session):**
   `survival/ThrownPotions.java` — splash (4-block reach, 1 - dist/4 scaling,
   sub-20gt drops) and lingering clouds (3.0 radius, -0.5 per use +
   radius/duration per tick, 10gt arm, 600gt life, 1/4 duration, 20gt
   per-entity cooldown) for player throws AND dispensers, through a new
   scaled `Potions.apply` overload. Approximation noted in AUDIT: impact
   distance is center-to-center, not vanilla's AABB-to-AABB.
2. ~~Warden mob~~ **Done 2026-07-12 (Fable) — the summit is complete.**
   `mobs/ai/WardenMob.java`: full port of Warden/WardenAi/AngerManagement/
   AngerLevel + behavior/warden (all decompiles cached, incl. fresh
   SculkShriekerBlockEntity + SpawnUtil) as an explicit state machine
   (EMERGING 134gt invulnerable / DIGGING 100gt after 1200gt calm / ROARING
   84gt / SNIFFING 84gt / SONIC_BOOM 60gt with the 34gt strike, 10 dmg,
   2.5/0.5 knockback, 40gt cooldown + 200gt fresh-target grace / melee 18gt
   cadence 30 dmg) over VBrain navigation; per-entity anger (35 default, 10
   first-projectile, +100 on hurt, +20 roar, 1/s decay, cap 150, angry-then-
   players-then-anger suspect order), vibration listening (radius 16, 40gt
   cooldown, wool occlusion, projectile-owner resolution), disturbance
   investigation, 120gt darkness pulses, warden-steps-dampened. Shrieker
   side reworked to the faithful WardenSpawnTracker semantics in
   `Vibrations.java`: can_summon-gated warnings, 16-block player pooling
   (max+1 copied to all), 200gt increase cooldown, warden-within-48
   suppression, -1/12000gt quiet decay, respond at the 90gt shriek end
   (darkness within 40 + reply sound by level, warden summon at 4 via the
   SpawnUtil placement walk). Behavior change: default (can_summon=false)
   shriekers no longer apply Darkness — matches vanilla tryRespond.
   Playtest scenario at z=235 drives the whole chain (warn→summon→emerge→
   anger→roar→sonic boom→dig despawn); selftest covers the suspect-order
   comparator + thresholds. Simplifications in AUDIT.md (particles, total
   dig/emerge invulnerability, session-scoped anger, shared pathfinder).
3. ~~Dispenser exotics~~ **Mostly done 2026-07-11 (Fable, same session):**
   XP bottle (orbs 3-11 on land), glass-bottle water fill, shears (shared
   `Shearing.shear`), armor equipping onto empty-slotted living entities,
   firework (cosmetic flight), splash/lingering potions (via #1). Still
   blocked on missing base systems: armor stands, brush/archaeology,
   candles, chest-onto-donkey (no chested-horse inventory). AUDIT.md
   updated. ~~Honeycomb/waxing~~ **done 2026-07-12 (Sonnet)** —
   `blocks/CopperWaxing.java`: honeycomb waxes any unwaxed copper-family
   block (blocking the oxidation handler for good), an axe strips wax back
   off or scrapes an unwaxed weathered block back one stage. Axe log-
   stripping (a separate AxeItem mechanic, no stripped-log system exists)
   and sign-waxing (no sign system exists) are out of scope — AUDIT.md.
4. **Update-order semantics** (DESIGN DECISION, not a task yet) — minecom
   batches dirty positions per tick instead of vanilla's depth-first
   neighbor-update recursion, so update-order-dependent contraptions
   (locational dupers, order-sensitive comparator chains) can behave
   differently. Real vanilla 26.x itself ships the deterministic
   "experimental redstone" Orientation system (ExperimentalRedstoneUtils) —
   porting THAT is the right target, but do NOT attempt it before the
   multi-core redstone design lands (COMMUNITY-INTEL.md: region-threaded
   redstone islands), or the update-order work gets done twice.
5. **Cleanup grab-bag, re-scoped 2026-07-12 (Sonnet) — sized per item,
   ~~crafter persistence~~ dropped (already done, see the Persistence
   adapter tail entry: crafters + locked slots landed in the persistence
   core itself):**
   - Locked-slot client visuals (crafter's locked-slot container property
     packets) — pure client-visual state sync, no gameplay effect; matches
     dozens of already-accepted sound/particle-class simplifications
     elsewhere in this project. Low value, skip unless picked up as part
     of a broader crafter-polish pass. (S, cosmetic)
   - Waterlogged sensor silencing — decompile-checked this session:
     `SculkSensorBlock`'s WATERLOGGED references are ALL about suppressing
     the click *sound* (`if (!waterlogged) playSound(...)`); a waterlogged
     sensor still activates/powers/reads on comparator identically to a
     dry one in real vanilla. Also pure client-audio, not a real gameplay
     gap. (S, cosmetic)
   - ~~Vibration-tap gaps~~ **done 2026-07-12 (Sonnet)** — decompiled
     `ContainerOpenersCounter` directly to confirm the exact trigger point
     (`incrementOpeners`/`decrementOpeners` fire `GameEvent.CONTAINER_OPEN`/
     `CLOSE` on the same 0-&gt;1/1-&gt;0 transition already being used for
     this session's new chest/barrel lid-animation work — same call sites,
     just add the emit). The frequency table already had every event name
     needed (`container_open`/`_close`, `block_open`/`_close`, `eat`,
     `drink`, `equip`) — nothing missing there, purely a wiring gap. Wired:
     chest/trapped_chest/ender_chest/barrel/hopper/furnace family/shulker
     box/brewing stand/dispenser+dropper/crafter (`container_open`,
     `container_close` only where this project already tracks a close —
     chest/trapped_chest/barrel/ender_chest; furnace/hopper/shulker/
     brewing/dispenser/crafter only have open-side tracking today, so only
     open is wired for those, noted as a known asymmetry rather than
     building new close-tracking for six more block types just for this),
     doors/trapdoors/fence gates (`block_open`/`_close`, unconditional on
     every toggle — no opener-count gating like containers), eating
     (`eat`), potion drinking (`drink`), and dispenser-equips-a-mob
     (`equip` — the one dispenser-exotic call site that clearly matches;
     did NOT guess at a player-direct right-click-to-wear-armor call site
     since none was found with confidence, left as a further increment).
     New `scenarioVibrationTaps` (5 checks: chest open, chest close, door
     open, eat, drink, all heard by a sensor 4 blocks away), 5/5 clean
     reruns. Also found and fixed, while re-verifying the fire-spread
     section this touched only tangentially: a genuine ~1/15 statistical
     flake in `scenarioFireSpread`'s wider-spread check (400 forced-tick
     iterations wasn't always enough for a rarer-than-expected roll — only
     one candidate position per tick ever has a flammable neighbor in that
     test's layout); bumped to 2000, 8/8 clean after.

### ~~Piston reorder-collision differential test~~ — DONE 2026-07-13 (Fable)

**Landed, with one honest finding that reframes what "verifying the reorder"
can even mean.** Three pieces:

1. **The collision rig reaches the path — proven, not assumed.** The
   uncommitted `scenarioPistonReorderCollision` rig was hand-traced through the
   ported resolver (A/C slime rows + split honey D line + honey E row; E1's
   branch adds D1, whose forward walk hits M at index 6 →
   `reorderListAtCollision(1, 6)`), and now carries an execution-witness check
   via a new `Pistons.REORDER_FIRES` counter (`Redstone.pistonReorderFires()`).
2. **The finding: final layouts are provably invariant to `toPush` order in
   BOTH implementations.** `Pistons.apply()` snapshots every pushed state
   before moving, and vanilla's `moveBlocks` does the same (states collected
   up front, destination-dedup via map) — so a wrong reorder can NEVER show up
   as a lost/duplicated block in a static layout, only as wrong
   moving-piston block-entity/update ORDERING (observer/BUD timing), which the
   documented instant-apply simplification doesn't model at all. That is why
   the witness counter exists: without it, no layout assertion can even prove
   the path executed. What IS layout-observable (and was genuinely unverified):
   the collision path's effect on resolve outcomes — membership closure, the
   `j <= collisionIndex + blocksAdded` re-branch bound, push-limit failures,
   blocked-vs-moved decisions.
3. **The differential test (option b, as recommended).** New committed harness
   `scripts/piston_vanilla_capture.py` boots the REAL vanilla 26.1.2 dedicated
   server (the `~/versions` + `~/libraries` bundler unpack; note 26.x moved
   overworld storage to `world/dimensions/minecraft/overworld/region`), builds
   40 cases on a forceloaded grid — 12 hand-designed reorder-rig mutations
   (mirrored, material-swapped, vertical, mid-block variants, over-limit,
   obsidian-blocked), 16 structured row/bridge randoms, 12 uniform randoms —
   triggers them, and snapshots post-extend AND post-retract layouts out of the
   region files (minimal zero-dep NBT/Anvil parser included) into
   `src/main/resources/vanilla/piston_reorder_cases.json` (62 KB, committed,
   regenerable with one command — the lesson from the worldgen harness never
   being committed). `scenarioPistonDifferential` replays every case through
   the real Pistons engine and compares every cell of every capture box.
   **Result: 40/40 cases bit-match vanilla on both extend and retract, with
   `reorderListAtCollision` firing 10× across the fixture** (witnessed by a
   final coverage check, so the fixture can't silently regress to
   never-reaching the path). 36 cases extended, 4 correctly stayed blocked.

Original entry follows for the trail:

### Piston reorder-collision differential test — Fable (IN PROGRESS 2026-07-12 ~05:20, overnight Fable queue session)

**Triaged 2026-07-12 (Sonnet): confirmed as Fable, not Opus** (the header
above said Opus, but the body already noted Fable is the one actively
working it — fixing the mismatch, not reassigning). Fable ported the
`PistonStructureResolver` graph-traversal algorithm this work verifies
from scratch; they have the deepest context on its exact collision-path
semantics, and a differential test against real vanilla for this is
exactly the kind of "confirm a hard algorithm port is bit-exact" work
worth reserving Fable for, not routing to Opus.

The 2026-07-11 slime/honey structure-resolver port (see the Done entry below)
ships `reorderListAtCollision` as a verbatim port, but none of the three new
playtest rigs actually exercises it — the collision path (a branch line's
FORWARD walk reaching a cell an earlier line already claimed) needs an exotic
wrap-around slime/honey arrangement that resisted quick construction, because
branching never runs along the push axis and forward walks usually claim
their own row first. Two ways to close the gap: (a) build a known
collision contraption from the technical-MC community in the playtest and
assert exact final positions, or (b) better, a differential test: run the
same randomized slime/honey structures through real vanilla (region diff,
same harness pattern as worldgen) and compare final block layouts. Until
then the reorder path is faithful-by-construction but unverified.

### ~~Flaky villager-breeding playtest scenarios~~ — DONE 2026-07-12 (Sonnet)

Observed 2026-07-11 across two consecutive full playtest runs of the same
jar: run 1 failed 5 villager checks (breeding pair, baby offspring, bread
pickup "a=0 pts", 12-food-points breed, farmer food-sharing), run 2 failed
only the first two — different subsets, so it's timing flakiness, not a
functional regression (pistons untouched by these; both runs 479-482
passed). The villager scenarios depend on real-time AI sweeps (40-tick
farmer harvest, pickup scan loops from `mobs/VillagerFood.java`) racing
fixed `waitFor` windows, which lose on this slow-HDD box under load.
Fix by making the scenarios deterministic rather than raising timeouts:
drive the relevant sweeps directly (call the tick hooks from the test the
way the trial-chamber scenario does) or gate on the underlying state
(inventory contents) instead of downstream behavior. Logs:
test-logs/playtest_piston_chains.log (5 fails),
test-logs/playtest_piston_rerun.log (2 fails).
Also observed once (playtest_redstone_batch2.log): "the enderman later
places the carried block back down" — same class of AI-timing flake; fold it
into the same determinism pass. Second enderman flake + one trident-loyalty
flake ("free slots at throw time=45") in playtest_summit.log (2026-07-12).
More of the same class, 2026-07-12 warden runs: playtest_warden.log failed
only the two breeding checks; playtest_warden2.log (identical jar + three
zero-behavior source tweaks) failed breeding ×2, farmer food-sharing, and —
new — "fire aspect II ignites the target" (its sibling burning-damage check
PASSED in the same run, so the ignite worked; the 1000ms isOnFire
observation window just lost on this box — widen or gate on state when
doing the determinism pass). And once in playtest_persist.log: "zombie hits
for 3 unarmored (got 6.5)" — two prior runs measured exactly 3.0; 6.5 is a
sword-equipped zombie (the 1% maybeEquipZombieWeapon roll). Fix in the same
pass by stripping the test zombie's held item before measuring.

While in here: add a **section filter to the harness** (`--playtest redstone`
runs only matching scenario names). The suite is now 500+ checks with
real-time waits (~7 min per full run on this box); focused iteration needs
sub-minute cycles. Trivial change in PlayTest's scenario runner.

**Done 2026-07-12 (Sonnet).** `--playtest <section>` landed first (Main.java
passes argv[1] through to a new `PlayTest.sectionFilter`, checked before the
pre-existing `MINECOM_TEST_ONLY` env var so old muscle-memory still works) —
cut iteration on the affected scenarios from ~7 min to seconds. Fixed by
determinism, not wider timeouts, per the prescription: villager food economy
(`scenarioVillagerFood`) now drives `VillagerFood.pickupSweep`/`farmerSweep`
directly inside the poll loop instead of racing the real 10-/40-tick
schedulers, and re-teleports the villager/farmer/thrown-item back to a fixed
spawn point on every poll — root cause was AI wander during the wait, not
just scheduler timing (confirmed via instrumentation: a villager reproducibly
12+ blocks from its own dropped bread). Enderman block-interaction
(`endermanBlockInteraction`, extracted to a public method) is now driven in a
tight loop for both the 1/20 pickup and 1/2000 placement rolls instead of a
real-time `waitFor` — the placement roll's old 480s budget was still only
~4.8x the geometric expectation, a measured ~0.8% false-negative floor no
timeout width fixes. Fire aspect's ignite window widened 1000ms->3000ms to
match its sibling burning-damage check. The zombie-unarmored-damage flake
was a held-item contamination bug (`maybeEquipZombieWeapon`'s 1% roll), fixed
by stripping the test zombie's main hand before measuring — applied the same
defensive strip to armor slots in the trident melee check, though that
uncovered a second, separate, rarer (~1/30) "took 1.0 instead of ~8" flake
with an unarmored, non-baby, non-leader zombie that armor-stripping does NOT
explain; logged below as a new Open item rather than guessed at further.
Trident loyalty-return was investigated with the same "drive it directly"
technique first (teleporting the returning trident onto the player) but that
made it CONSISTENTLY fail (0/10) — root cause traced to `Combat.java`'s
same-shooter collision no-op guard misfiring on the teleport-triggered
collision event; reverted to the original plain `waitFor` (natural flight
already completes in ~250ms against a 5000ms budget, so it was never really
the flake) — not every scenario in this class wants the same fix. The
villager-breeding sub-test itself turned out to be a genuine, 10/10-
reproducible test-setup bug, not flakiness: `Villagers.hasSpareBed` requires
`beds > villagerCount`, and the test only placed 1 bed for 2 villagers;
fixed by placing 3. All affected sections run clean 10x in a row with the
new filter; full selftest (210/0) and playtest green after the fix
(test-logs/playtest_determinism_pass.log, test-logs/selftest_determinism_pass.log).

### Unification-pass mechanical cleanups — Fable (BLOCKED until first pass done)

**Mislabeled below as "Sonnet" — corrected 2026-07-12 (Sonnet). Explicit
Opus-vs-Fable triage 2026-07-12 (Sonnet): kept as Fable, not downgraded
to Opus.** docs/STRATEGY.md §6 step 3 says "strongest model available" —
in this project's own three-tier vocabulary (see this file's own intro:
"Sonnet 5 for routine work, Opus for harder problems, Fable for the
hardest"), that's Fable specifically, a project-owner sequencing decision
already on record, not something to second-guess. It also holds up on
the merits, not just deference: renaming individual identifiers is
mechanical, but splitting a 5.3k-line PlayTest.java without breaking any
of 600+ scenarios needs holding the whole file's dependency shape in
mind at once and executing a wide, high-blast-radius refactor without a
single subtle regression — closer to the kind of broad-context work
Fable already did this session (the persistence core, the piston
structure resolver, trial chambers, the warden state machine) than a
mechanical Opus cleanup. Still don't pick this up as a quick task even
once unblocked — it's step 3 of a roadmap, do it as one dedicated pass.

Queued per docs/STRATEGY.md §6 step 3 and docs/CONVENTIONS.md §11 — do these
as ONE dedicated pass, not opportunistically: (1) rename camelCase
static-final collections in `redstone/Redstone.java` + `blocks/Fluids.java`
to UPPER_SNAKE; (2) converge `start(Instance)` (~11 files), `Recipes.index()`
and the one `load()` onto `register(...)`; (3) make `Bootstrap.java` use
imported simple names consistently (42 FQN call sites); (4) unify the
mixed plain/concurrent map pairs (e.g. `Hoppers.COOLDOWN`); (5) split-plan
for the §11.6 god classes (PlayTest 5.3k lines first). Every rename must
compile + full selftest/playtest green before commit.

**Also noticed 2026-07-12 (Sonnet) while checking this blocking condition:**
STRATEGY.md §6 step 2's "verification-suite hardening" (a co-prerequisite
alongside "finish first pass") isn't defined anywhere else in the docs —
no concrete scope, checklist, or size estimate exists for it yet. Whoever
picks this pass up needs to scope that step first (or confirm with the
project owner what it means) rather than assuming it's already satisfied
by this session's flake fixes, which were incidental bug fixes, not a
deliberate hardening pass.

### ~~Creaking + Creaking Heart block entity~~ — DONE 2026-07-12 (Fable)

Implemented exactly along the extracted spec below: `blocks/CreakingHearts.java`
(state machine + protector lifecycle + hurt-call emitter + resin BFS +
comparator + natural XP) and `mobs/ai/CreakingMob.java` (freeze-under-gaze,
damage redirect via an EntityDamageEvent interceptor, 45gt teardown,
heartless /summon variant stays mortal). Playtest scenario at z=250 runs the
whole chain; AUDIT.md lists the simplifications (particles, night-window
CREAKING_ACTIVE, session-scoped hearts). Original scoping entry kept below.

### Creaking + Creaking Heart block entity — Opus (original scoping)

`VanillaMobs.java` has no creaking factory. Decompiled `Creaking.java` and
confirmed this isn't a normal mob-stats-and-AI addition (unlike the 6
hostile mobs closed 2026-07-11 — cave_spider, endermite, illusioner,
piglin_brute, zoglin, giant): a Creaking's entire identity is that it's
near-invulnerable EXCEPT via a paired `CreakingHeartBlockEntity` (which
doesn't exist in this codebase — no `CreakingHeart` block at all), and it
FREEZES (can't move/be knocked back, `canMove()` gated on
`checkCanMove()`) whenever a player is looking directly at it
(`isLookingAtMe`), matching the "Weeping Angel" pattern — the opposite of
every other mob's AI in this project. `hurtServer` redirects most damage
to `creakingHeartBlockEntity.creakingHurt()` on the heart instead of
directly hurting the creaking itself; killing it for real means breaking
the heart (or landing damage that bypasses invulnerability). `tickDeath`
has its own 45-tick "tearing down" particle sequence
(`tearDown()`/`CREAKING_ORANGE`/`CREAKING_GRAY`) distinct from a normal
death. Shipping the mob without the heart mechanic would be a different
creature wearing the same texture, not a documented simplification like
this project's usual "skip the client-visual half" pattern — the heart
IS the mechanic. Needs its own block-entity design pass (state machine:
dormant/awake/uprooted, decompile `CreakingHeartBlock`/
`CreakingHeartBlockEntity`/`CreakingHeartState`) before the mob itself is
worth attempting. vanilla-src/ has `Creaking.java` cached; heart classes
still need decompiling.

**Decompiles cached + spec extracted 2026-07-11 (Fable)** —
`CreakingHeartBlock.java` (195 l), `CreakingHeartBlockEntity.java` (358 l),
`CreakingHeartState.java` now in vanilla-src/. The concrete state machine,
so scoping needs no further investigation:

- Block: properties AXIS (x/y/z), STATE (uprooted/dormant/awake), NATURAL.
  `hasRequiredLogs` = pale-oak logs on BOTH sides along AXIS with matching
  axis, else → UPROOTED. Non-uprooted + logs → AWAKE when the
  CREAKING_ACTIVE environment attribute is on (night — same day-timeline
  attribute system DaylightDetectors already implements), else DORMANT.
  Comparator output: `15 - floor(clamp(dist_to_creaking,0..32)/32 * 15)`,
  0 if uprooted/no creaking. Breaking or exploding the heart kills the
  linked creaking with death effects; NATURAL=true hearts pop 20-24 XP.
- Block entity tick (every 20+rand(5) ticks): re-derive STATE; if AWAKE,
  monsters-spawning on, and a player is within 32 → spawn ONE Creaking
  protector (5 attempts, ±16 xz / ±8 y, on-solid-not-leaves) and link it
  (store UUID; survives reload with a 30-tick grace resolve — minecom's
  session-scoped equivalent: just hold the entity ref, note the Anvil
  limitation in AUDIT.md like TrialChambers does). Unlink/kill when day,
  distance > 34, or player-stuck check.
- `creakingHurt()` (called from the mob's damage redirect): 100-tick
  "hurt call" — sound every 10 ticks interpolating position from creaking
  toward heart, particle trails for the first 50; if AWAKE also place 2-3
  resin clumps: BFS depth 2 / max 64 over pale-oak logs, extend a
  multiface `resin_clump` on a random free adjacent face (waterlogged on
  source water).
- Existing minecom precedent for every piece: TrialChambers (state machine
  + block-entity ticker + session-scoped config), DaylightDetectors
  (day-timeline attribute), Redstone tracked-position registries.
  The genuinely new bit is multiface resin_clump placement (face-property
  accumulation) and the Creaking mob itself (`Creaking.java` cached,
  freeze-when-looked-at + damage redirect to heart + tearDown).

### ~~Happy Ghast + multi-passenger riding~~ — DONE 2026-07-12 (Fable)

`mobs/ai/HappyGhastMob.java` rides Minestom's native passenger API exactly
as the scope update below predicted: harness gating, 4-passenger cap,
first-rider steering from Player.inputs() (the ClientInputPacket sync),
velocity-only movement while ridden (sidesteps the Navigator gotcha),
still-platform, continuous heal. Playtest scenario at z=255 covers
equip/mount/fly/dismount. AUDIT.md lists what's not modeled (per-seat
offsets, baby ghastling, leash elasticity). Original scoping kept below.

### Happy Ghast + multi-passenger riding — Opus (original scoping)

No `happyGhast()` factory. Decompiled `HappyGhast.java`: unlike every
other passive mob in this codebase, its whole point is being a rideable
flying mount — `MAX_PASSANGERS = 4`, a harness equipment item gates
riding/steering, `Ghast.GhastMoveControl`-based flight with a
leash-holder-driven direction system, baby scale 0.2375, and a
still-timeout mechanic pausing movement when idle. Confirmed via grep
this session: **no entity-riding-entity passenger system exists anywhere
in this codebase at all** — `Boats.java`/`Minecarts.java` are separate,
vehicle-specific placement/physics code, not a general passenger
framework a new flying mount could plug into. Building a real multi-
passenger flying-mount system (harness item, up to 4 riders, leash-driven
steering) is cross-cutting infrastructure work, not a mob-factory
addition. vanilla-src/ has `HappyGhast.java` cached (633 lines).

**Scope update 2026-07-11 (Fable):** Minestom itself ships a native
protocol-level passenger API — `Entity.addPassenger()` / `getPassengers()` /
`getVehicle()` — so the packet/mounting half of this task already exists
upstream; what minecom needs to build is only the gameplay layer (harness
item gating, 4-seat arrangement, steering input from the riding player,
GhastMoveControl flight). Community-reported gotchas from the Minestom
Discord help channel: the Navigator breaks while an entity has a passenger,
and passenger movement interpolation misbehaves for far-away mounts —
design around both.

### ~~Silverfish + infested blocks~~ — DONE 2026-07-12 (Fable, overnight queue session)

`VanillaMobs.silverfish` + `blocks/InfestedBlocks.java`: the real mechanic set —
7-pair host↔infested table (deepslate keeps its axis), break-without-silk-touch
ambush spawn (silk touch takes the host item through the existing loot path and
suppresses the spawn — the bundled infested_* tables already had the match_tool
gate), wake-up-friends (~20gt one-shot countdown after entity damage, then the
X/Z ±10 / Y ±5 per-axis-outward destroy scan with 50% stop per find, each
destroyed block releasing a fresh silverfish), and merge-with-stone (idle roll,
one random direction from body center, mob discarded). Playtest scenario at
z=260 covers all four behaviors; selftest checks the mapping table + scan
order. Simplifications in AUDIT.md (no mobGriefing gamerule to honor, no
explosion-release hook, magic-damage wake trigger not wired, hardness/blast
resistance from Minestom registry). Original scoping kept below.

### Silverfish + infested blocks — Opus (original scoping)

Deferred in the same "missing hostile mobs" pass that closed cave_spider/
endermite/illusioner/piglin_brute/zoglin/giant (2026-07-11) — silverfish
was the one NOT attempted, because real vanilla's version needs actual
infested-block mechanics (silverfish merge into stone/cobblestone/brick
variants on spawn, and hitting an infested block "wakes" a hidden
silverfish out of it), which is block-state/world-interaction work, not
just AI+stats like the other six. AUDIT.md's own framing already flagged
this as bigger than the rest of that batch; decompile `Silverfish.java`
(cached in vanilla-src/, read but not implemented) plus whatever block
class encodes "infested_*" variants before scoping the real size.

### ~~Slime/magma cube split-on-death (+ mob size scaling)~~ — DONE 2026-07-12 (Fable, overnight queue session)

Exactly the shape this entry prescribed: the size system first
(`VanillaMobs.slimeLike` — setSize formulas HP size²/speed 0.2+0.1·size/
attack size (+2 magma)/armor 3·size, scaled hitbox + SlimeMeta, the
finalizeSpawn 1<<rand(3) roll with the 0.5×specialMultiplier bump through
both factories and every spawn path), then split-on-death nearly for free
(`maybeSplitSlime` from Combat.death: 2+rand(3) half-size children,
quadrant offsets, size-1 terminal). Loot wired through the real
type_specific.size predicates (new LootTables entityDrops overload +
Combat.death size tag), XP = size. Playtest scenario at z=265 runs the
4→2→1→nothing chain + the tiny-slime/tiny-magma damage asymmetry; selftest
covers the loot gating. Simplifications in AUDIT.md. Original scoping below.

### Slime/magma cube split-on-death (+ mob size scaling) — Opus (original scoping)

AUDIT.md asked to "verify Slime handling — magma cube too"; investigated
2026-07-11 and it's a real gap, but bigger than a missing death hook.
Decompiled `Slime.java`: `remove()` spawns `2 + random.nextInt(3)`
half-size children when a slime with `size > 1` dies — but this
project's `slime()`/`magmaCube()` factories (`VanillaMobs.java`) have NO
size parameter at all; they're hardcoded to a single fixed small size
(4 HP / 16 HP respectively), and neither the natural spawner nor
`/summon` can ever produce a larger one. Implementing split-on-death
alone would be dead code — it can only ever trigger on a size that
never exists. Needs a real size system first: a size parameter threaded
through both factories scaling HP/attack/hitbox (decompile
`Slime.createAttributes`/`getDefaultDimensions` for the exact per-size
formula), natural-spawn size-roll logic (currently always implicitly
size 1), and only then the split-on-death hook itself (which becomes
almost free once size exists — real vanilla's `remove()` override is
~15 lines). Scope the size system as its own task; split-on-death rides
along with it, not separately.

### ~~Bubble columns (soul sand/magma push mechanic)~~ — DONE 2026-07-12 (Fable, overnight queue session)

`blocks/BubbleColumns.java` implements both halves this entry demanded:
(1) the propagation/maintenance system — columns re-derived from the
below-neighbor on the vanilla 5gt CHECK_PERIOD, whole source-water runs
converted/reverted at once, event-driven + a public notifyChanged hook
(worldgen self-start not modeled — AUDIT); (2) per-tick entity effects with
the exact Entity.onInside/AboveBubbleColumn ramps, plus the AbstractBoat 60gt
timer (Boats.buoyancy now floats on push columns and defers to the sink on
drag ones). Playtest scenario at z=270 drives grow → item launch → boat pop →
magma flip → boat sink → revert-to-water. Simplifications in AUDIT.md.
Original scoping kept below.

### Bubble columns (soul sand/magma push mechanic) — Opus (original scoping)

AUDIT.md's "Fluids.java" note only covers flow/waterlogging; bubble
columns are a separate gap found while checking Boats.java's "bubble
column sink?" item (2026-07-11). Decompiled `BubbleColumnBlock.java`:
this is not a simple "check block, apply velocity" mechanic — bubble
columns are a **self-propagating pseudo-fluid block** that generates and
maintains itself: `updateColumn()` grows a column upward one block at a
time above a soul-sand (drag-down) or magma-block (push-up) source
underneath flowing/source water, re-checked via scheduled ticks
(`CHECK_PERIOD = 5`) and neighbor updates, with its own `DRAG_DOWN`
block-state property. Confirmed via grep: **no bubble-column generation
logic exists anywhere in this codebase** — `bubble_column` is referenced
only as an equivalent-to-water wetness check (`Trident.java`,
`Breath.java`), never actually created by placing soul sand/magma under
water, and no entity (player, mob, or boat) is ever pushed by one. A
real implementation needs: (1) the propagation/maintenance system
(similar in shape to `Fluids.java`'s own flow engine, but a distinct
block type with its own up/down growth rule), and (2) the actual push
force applied per-tick to entities standing in or above a column
(`entity.onInsideBubbleColumn`/`onAboveBubbleColumn` in real vanilla).
Boats "sinking" in one is just the entity-push half applied to a boat —
not worth doing in isolation before the block-propagation half exists,
since a bubble column a player manually built via `/setblock` wouldn't
behave like a real one long-term (won't regenerate after
water-flow disruption) without it.



## Done

### ~~Lightning-rod redirection (tracked-position registry)~~ — DONE 2026-07-11 (Fable)

`Lightning.java` claims this was "logged in docs/HANDOFF.md" but no entry
existed until now. Strikes should first redirect to a lightning rod within 128
blocks (`ServerLevel.findLightningRod`, a POI search). Needs a lightweight
tracked-position registry for placed rods (the same pattern as
`Redstone.trackDaylightDetector` / `TrialChambers`), then a nearest-rod check in
`Lightning.strikeAt` before the entity-redirect. See docs/AUDIT.md for the full
gap list this came from.

**Done 2026-07-11 (Fable), as part of the redstone-parity pass.** Exactly the
prescribed shape: `lightningRods` tracked-position registry in Redstone.java
(placement event + `trackLightningRod` for tests/world-load),
`nearestLightningRod` 128-block search tried FIRST in `Lightning.strikeAt`
(before the entity redirect, matching ServerLevel.findLightningTargetAround
order), and `lightningRodStruck`: POWERED 15 for 8gt, strong power out the
attachment face (LightningRodBlock.getDirectSignal), weak 15 everywhere.
Playtest green (redirect + pulse decay). No copper-oxidation scrub (no
oxidation system — AUDIT).


### ~~Piston slime/honey block chains (structure resolver)~~ — DONE 2026-07-11 (Fable)

AUDIT.md flagged "no slime/honey block chain semantics" as unverified;
confirmed missing 2026-07-11. The existing 12-block push-length limit
IS already correct (verified — `Pistons.java`'s `extend()` already caps
at 12), so that half of the audit's uncertainty is resolved. What's
actually missing is real: pistons currently only push/pull blocks in a
single straight line along the push axis. Decompiled
`PistonStructureResolver.java` (196 lines) — real vanilla's slime/honey
mechanic is a genuine graph-traversal algorithm, not a simple extension
of the existing linear scan: a sticky block being pushed also drags every
directly-adjacent block along all 4 perpendicular axes
(`addBranchingBlocks`, recursive), a sticky block also pulls whatever's
stacked BEHIND it opposite the push direction before the branching check
runs (the `while (isSticky(nextState))` backward line-walk inside
`addBlockLine`), and if the branching search's forward projection
collides with blocks already queued from a different branch, the whole
pending list has to be reordered in place (`reorderListAtCollision`) to
preserve correct push ordering. Honey and slime stick to each other and
themselves but honey does NOT stick to slime in the pull direction
(`canStickToEachOther`'s asymmetric check) — an easy edge case to get
backwards. This is exactly the kind of thing the standing rule says not
to guess at: a naive/simplified version risks silent bugs (block
duplication/loss, infinite loops on cyclic structures, wrong push
ordering) that a shallow test wouldn't catch. Treat as a from-scratch
reimplementation of `Pistons.java`'s structure-detection, ported
faithfully from the decompiled resolver above, with deliberately
adversarial test cases (L-shaped honey/slime chains, colliding branches,
honey-slime boundary) — not a quick patch to the existing `extend()`
loop.

**Done 2026-07-11 (Fable).** `PistonStructureResolver` was not actually cached
in vanilla-src/ (only read in the earlier session) — decompiled fresh from
server-26.1.2.jar with Vineflower (now cached: all 7 piston-package classes)
and ported line-by-line into a private `Resolver` class inside
`redstone/Pistons.java`: back-pull walk behind sticky line starts, recursive
perpendicular branching (never along the push axis), `reorderListAtCollision`
verbatim, honey-slime mutual non-stick (symmetric, both orders — the HANDOFF
"asymmetric" note was slightly off), 12-block cap counted across the whole
structure, and `PistonBaseBlock.isPushable` ported alongside (obsidian family,
world-Y bounds, glazed terracotta PUSH_ONLY, DESTROY-on-push for
no-collision blocks, fluids destroyed without drops). Retraction now runs the
resolver too (vanilla triggerEvent gate: only NORMAL-pushable targets or
piston bases get pulled), so sticky pulls drag whole chains instead of the
old single block. Instant movement kept (no moving-piston block entity —
usual client-visual simplification); QC approximation unchanged. Three new
adversarial playtest scenarios (slime T-branch push + full pull-back,
honey-slime boundary + glazed-terracotta-above-slime contraption trick,
13-vs-12 block branched column limit) — all green, 479 playtest checks
passed. The reorder-collision path is faithful-by-construction but not yet
exercised by any rig — logged as its own Open task above. Log:
test-logs/playtest_piston_chains.log.


### ~~Daylight detector~~ — DONE 2026-07-11 (Fable)

Scoped as "low complexity" by an earlier research pass in this session,
but decompiling `DaylightDetectorBlock.updateSignalStrength` exactly
turned out to be more involved than that estimate: real vanilla's non-
inverted-mode signal isn't just sky brightness — it's `round(skyBrightness
* cos(sunAngle))` where `sunAngle` is a PERSISTENT PER-BLOCK-ENTITY value
smoothed toward the current sun position via exponential decay each tick
(`sunAngle += (target - sunAngle) * 0.2F`, evaluated every 20 ticks via
`DaylightDetectorBlockEntity`'s ticker) — not a pure function of world
time. It also reads `level.environmentAttributes().getValue(SUN_ANGLE,
pos)`, a per-dimension-customizable attribute system in 26.x whose exact
default overworld formula wasn't verified. Implementing this faithfully
needs: (1) confirming the real default SUN_ANGLE formula for the
overworld (decompile `EnvironmentAttributes`/whatever supplies the
default), (2) a new per-position persistent-state map (this project has
no precedent for that outside of what's already built for redstone
timing), and (3) the correct exponential-smoothing tick loop. Inverted
mode (right-click toggle, signal = `15 - skyBrightness`, no smoothing) is
simple and could be split out as a same-session quick win if wanted, but
the non-inverted mode (the common case — this is what makes "turns on
redstone at night" clocks work) is the one that needs the real
investigation above. Do not re-scope this as "small" without redoing the
sun-angle-formula check first.

**Done 2026-07-11 (Fable).** The sun-angle re-check disproved the scary half
of the estimate: `DaylightDetectorBlockEntity` holds NO persistent state in
26.1.2 (decompiled — it exists only to get a 20-tick ticker), and the "0.2
smoothing" is a one-shot local pull toward noon inside
`updateSignalStrength`, not an exponential-decay loop. The real SUN_ANGLE
source is the data-driven day timeline (`data/minecraft/timeline/day.json`):
360°/day anchored 0° at noon, eased by cubic-bezier(0.362, 0.241, 0.638,
0.759) — verified numerically equivalent to the classic vanilla sun-angle
curve. Effective sky brightness is `skyLight - skyDarken` with `skyDarken =
15 - SKY_LIGHT_LEVEL` from the same timeline (night multiplier 4/15 ⇒ the
classic −11) plus rain/thunder alpha-blends toward 4.0 (5/16, 135/256).
Implemented in `redstone/DaylightDetectors.java` + Redstone integration
(20-tick sweep, power emission, wire connection, right-click invert);
playtest covers noon 15 / afternoon cos-scaled / midnight 0 / inverted 11 /
rain 12.

### ~~Difficulty system (Peaceful/Easy/Normal/Hard)~~ — DONE 2026-07-11 (Fable)

Currently the whole project implicitly assumes one fixed difficulty
(closest to Normal) everywhere a difficulty-dependent constant is needed
— e.g. `VanillaMobs.java` (~line 276)'s baby-zombie weapon chance is
hardcoded flat at 1% with a comment noting real vanilla is 5% on Hard.
This is one visible symptom of a much bigger gap: real vanilla's
difficulty setting cascades into mob spawn caps/rates, combat damage
scaling, hunger drain rate, zombie reinforcement/siege AI, hostile
aggression ranges, and multiple loot/equipment rolls — not just this one
constant. A real fix means designing a world-level (or per-player, for
Bedrock-style personal difficulty — check if vanilla Java even supports
that or if it's world-only) difficulty setting and threading it through
every system that reads a difficulty-scaled constant. That's a genuine
cross-cutting subsystem design problem (where does the setting live, how
do systems read it without a giant parameter-threading refactor, what's
the actual constant table per system), not a local patch — hence Opus,
not Sonnet. Patching just the one flagged constant without the real
setting would be cosmetic script-kiddie work, not a fix.

**Done 2026-07-11 (Fable).** World-level setting (vanilla Java has no
per-player difficulty — confirmed) in `Difficulty.java` (root package):
static holder + `/difficulty` command + minecom_state.json persistence, plus
the full `DifficultyInstance.calculateDifficulty` regional formula
(decompile-verified: global 0→0.25 ramp over dayTime 72k..1512k, chunk
inhabited time 0→1 over 3.6M ticks ×0.75/×1.0, moon bonus clamped to the
global ramp, Easy halves local; special multiplier (eff−2)/2). Inhabited
time tracked in-memory per chunk (not persisted — see AUDIT.md). Threaded
into: player damage scaling (0 / x/2+1 / ×1 / ×1.5 via `Combat.damaged`,
`when_caused_by_living_non_player` + explosions), FoodData parity (Peaceful
no-drain + regen, starvation floors 10/1/none), hostile spawn gate + instant
Peaceful despawn (`VNaturalSpawner`), raid gate, zombie weapon 1%/5%, exact
`Mob.populateDefaultEquipmentSlots` (0.15×specialMultiplier, 6 armor tiers
incl. copper, 25%/10% stop chance), zombie handleAttributes (leader
zombies, kb-resistance/follow-range jitter), Hard-only zombie
reinforcements (7-40 block placement, ±0.05 caller/callee charge), cave
spider poison 7s/15s, guardian laser +2 on Hard, lightning witch-conversion
gate. Selftest has exact formula vectors; playtest drives all four settings
behaviorally. Not done (noted in AUDIT.md): mob equipment enchant provider,
equipment drop chances, raid wave counts by difficulty,
villager→zombie-villager conversion rolls.

### ~~Villager breeding food-threshold (personal food inventory + pickup AI)~~ — DONE 2026-07-11 (Fable)

`Villagers.java` (~line 90-103) only enforces the bed-capacity half of
real vanilla's breeding-willingness check; the food half is skipped
because villagers have no personal inventory. Confirmed via decompiling
`net.minecraft.world.entity.npc.villager.Villager`: real vanilla's
`canBreed()` is `foodLevel + countFoodPointsInInventory() >= 12 &&
!isSleeping() && age == 0`. Checked whether "food from trades already
executed" could substitute (a cheaper approximation) — no: confirmed via
decompile that `AbstractVillager.notifyTrade()` only increments trade-use
counts, awards XP, and plays a sound; it does NOT add the traded item to
the villager's inventory, so trade history is a dead end for this. The
real food source is (a) the Farmer profession's own crop-harvesting brain
task (walk to a claimed farmland POI, harvest, carry produce) and (b)
passive pickup — `wantsToPickUp()` checks `#minecraft:villager_picks_up`
(bread/wheat/carrots/potatoes/beetroot), so players tossing food near a
villager get auto-collected. This project currently has ZERO villager
personal-inventory storage (only a `PROFESSION` tag) and no item-pickup
logic anywhere in `mobs/` at all (confirmed via grep). Even the cheapest
faithful approximation (skip full farmer-harvest AI, implement only
passive pickup + a food-level/inventory tag + the eat-until-full logic)
requires inventing an entity-level inventory representation and a
pickup-scan tick loop that don't exist today — genuinely new subsystem
work. If the full farmer-harvest-AI version is wanted (not just the
cheaper pickup-only approximation), that's Fable territory; the
pickup-only version is more Opus-sized.

**Done 2026-07-11 (Fable), full version including the farmer half.**
`mobs/VillagerFood.java`: 8-slot personal inventory (AbstractVillager's
SimpleContainer(8), session-scoped like villagers themselves), passive
pickup sweep of `#minecraft:villager_picks_up` items (resolved through the
bundled tag data, nested `#villager_plantable_seeds` included), FOOD_POINTS
bread=4 / potato=carrot=beetroot=1, and the real gate wired into
`Villagers.breedTick`: `foodLevel + countFoodPointsInInventory() >= 12` per
parent, with `eatAndDigestFood()` (eat-until-12 then −12) on success.
Farmer profession: harvests one mature crop per 40-tick sweep within 8
blocks (real loot-table drops, replants from held seeds), and throws food
toward a villager under 12 points when carrying ≥24 (the
hasExcessFood/wantsMoreFood sharing behavior). Not modeled (AUDIT.md): the
sleeping half of canBreed (no villager sleep system), profession
requestedItems beyond the tag, the full walk-to-claimed-farmland-POI brain
choreography.

### ~~Trial Chambers functional mechanics (spawner waves, vault, Breeze)~~ — DONE 2026-07-11 (Fable)

The Trial Chambers structure generates correctly (jigsaw-assembled, real
NBT templates) but every special block in it — `trial_spawner`, `vault`,
`decorated_pot`, `dispenser` — is placed as an inert block with zero
functional logic. No trial-spawner wave-spawning/room-lock/player-
detection state machine, no Breeze mob (entity doesn't exist in the
codebase at all), no vault key-check/unlock/reward-loot interaction, no
ominous-trial upgrade path. This is effectively a self-contained
"Trial Chambers minigame" feature, not a small gap — estimate 500-1500+
LOC across a new spawner block-entity with its own tick/state logic, a
new mob (Breeze, with its own wind-charge ranged AI), and a vault
interact handler with key-item checks. Needs its own decompile pass
across `TrialSpawner.java`, `TrialSpawnerState.java`, `VaultBlockEntity.java`,
and `Breeze.java` plus AI. Scope it as a dedicated task, not a quick add.

**Done 2026-07-11 (Fable).** `blocks/TrialChambers.java`: the full
trial-spawner state machine (inactive → waiting_for_players →
active → waiting_for_reward_ejection → ejecting_reward → cooldown),
decompile-verified against TrialSpawnerState/TrialSpawnerStateData — 14-block
line-of-sight player detection throttled to 20 ticks, per-config wave math
(total 6 +2/extra player, simultaneous 2 +1/extra, 40-tick spacing;
defaults from TrialSpawnerConfig.Builder), 47-block mob untracking, 40-tick
shutter pause, one weighted loot roll ejected per detected player every 30
ticks, 36000-tick cooldown, and the ominous path (Bad Omen → Trial Omen
conversion, block flips ominous, wave restart, harder config + ominous key
table). All 28 vanilla trial_spawner config JSONs and the 18 trial-chamber
loot tables are bundled (`trial_spawner.json`, `loot_trial.json`); per-block
config ids are captured from template block-entity NBT at placement via a
VStructureGen hook (so chambers are session-scoped — an Anvil-reloaded world
comes back inert, see AUDIT.md). Vault: activation 4.0/deactivation 4.5
ranges, key-item check (trial_key/ominous per template config), one unlock
per player ever, 20-tick item ejection cadence. Breeze
(`VanillaMobs.breeze`): 30 HP / 0.63 speed / follow 24, ballistic hops
around the target, wind-charge projectiles whose radius-3 burst deals 1
direct damage, launches entities upward, and flips wooden
buttons/doors/trapdoors/fence gates (BreezeWindCharge.explode). Playtest
runs a full trial end-to-end plus vault single-unlock and wind-burst
checks. Not modeled (AUDIT.md): ominous item-spawner drips, per-mob ominous
equipment tables, spawn-potential custom NBT, vault display-item packets,
breeze projectile deflection.
