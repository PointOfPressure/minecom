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

## Open

### Minestom 26.2 upgrade (from pinned 2026.07.01-26.1.2) — Opus/Fable, needs a scoping decision first

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

### Rare silk-touch-ambush-contamination flake — unassigned

Surfaced once (1/29 reruns) chasing the entry above, in the SAME scenario:
"silk touch never springs the ambush" occasionally sees a stray silverfish
even though silk-touch mining never calls `spawnInfestation` at all (an
early return in `InfestedBlocks.register`'s listener, before the spawn
call). Likely a residual silverfish from the *prior* sub-test surviving
`clearEntitiesExceptPlayer()` somehow, but not confirmed — the two fixes
above already closed the two most likely causes without fully eliminating
it. Rare enough (down from ~1/20 combined to ~1/29 for this one specific
check) that further live instrumentation is left for next time it's caught.

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

### Persistence adapter tail — Sonnet

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

Still open: trial chambers persistence (a bigger, separate concern —
already documented as deliberately session-scoped in AUDIT.md, needs
persisting per-chamber config assignments and wave/vault state, not a
quick addition — scope it as its own task before starting).

### Redstone parity — remaining summit after the 2026-07-11 pass — mixed
### (summit COMPLETE 2026-07-12: items 1-3 done; 4 is a design decision, 5 is cleanup)

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

### Piston reorder-collision differential test — Opus (IN PROGRESS 2026-07-12 ~05:20, overnight Fable queue session)

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

### Unification-pass mechanical cleanups — strongest model available (BLOCKED until first pass done)

**Mislabeled below as "Sonnet" — corrected 2026-07-12 (Sonnet).**
docs/STRATEGY.md §6 step 3 explicitly says this pass runs on "strongest
model available," not Sonnet — that's a project-sequencing decision (it's
step 3 of the launch roadmap, after "finish first pass" and "suite
hardening," both still open per this file), not a difficulty judgment
about the individual renames below. Don't pick this up as a quick Sonnet
task even once it's unblocked.

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


### Daylight detector — Opus

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

### Difficulty system (Peaceful/Easy/Normal/Hard) — Opus

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

### Villager breeding food-threshold (personal food inventory + pickup AI) — Opus/Fable

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
