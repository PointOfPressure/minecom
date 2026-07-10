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

## Done

(none yet)
