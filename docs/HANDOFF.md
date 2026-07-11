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

### Piston reorder-collision differential test — Opus

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

### Flaky villager-breeding playtest scenarios — Sonnet

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

### Unification-pass mechanical cleanups — Sonnet (BLOCKED until first pass done)

Queued per docs/STRATEGY.md §6 step 3 and docs/CONVENTIONS.md §11 — do these
as ONE dedicated pass, not opportunistically: (1) rename camelCase
static-final collections in `redstone/Redstone.java` + `blocks/Fluids.java`
to UPPER_SNAKE; (2) converge `start(Instance)` (~11 files), `Recipes.index()`
and the one `load()` onto `register(...)`; (3) make `Bootstrap.java` use
imported simple names consistently (42 FQN call sites); (4) unify the
mixed plain/concurrent map pairs (e.g. `Hoppers.COOLDOWN`); (5) split-plan
for the §11.6 god classes (PlayTest 5.3k lines first). Every rename must
compile + full selftest/playtest green before commit.

### Creaking + Creaking Heart block entity — Opus

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

### Happy Ghast + multi-passenger riding — Opus

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

### Silverfish + infested blocks — Opus

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

### Slime/magma cube split-on-death (+ mob size scaling) — Opus

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

### Bubble columns (soul sand/magma push mechanic) — Opus

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


### Lightning-rod redirection (tracked-position registry) — Sonnet/Opus

`Lightning.java` claims this was "logged in docs/HANDOFF.md" but no entry
existed until now. Strikes should first redirect to a lightning rod within 128
blocks (`ServerLevel.findLightningRod`, a POI search). Needs a lightweight
tracked-position registry for placed rods (the same pattern as
`Redstone.trackDaylightDetector` / `TrialChambers`), then a nearest-rod check in
`Lightning.strikeAt` before the entity-redirect. See docs/AUDIT.md for the full
gap list this came from.

## Done

### Piston slime/honey block chains (structure resolver) — Opus/Fable

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

### Trial Chambers functional mechanics (spawner waves, vault, Breeze) — Opus/Fable

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
