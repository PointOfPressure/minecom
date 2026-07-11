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

### Lightning-rod redirection (tracked-position registry) — Sonnet/Opus

`Lightning.java` claims this was "logged in docs/HANDOFF.md" but no entry
existed until now. Strikes should first redirect to a lightning rod within 128
blocks (`ServerLevel.findLightningRod`, a POI search). Needs a lightweight
tracked-position registry for placed rods (the same pattern as
`Redstone.trackDaylightDetector` / `TrialChambers`), then a nearest-rod check in
`Lightning.strikeAt` before the entity-redirect. See docs/AUDIT.md for the full
gap list this came from.

## Done

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
