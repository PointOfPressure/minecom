# Minecom

**Vanilla Minecraft, rebuilt from scratch on [Minestom](https://github.com/Minestom/Minestom).**
No Mojang server code. No copy-pasted client jar. Every mechanic is
re-derived from the official data files and, where it matters, verified
block-for-block against a real vanilla dedicated server.

> **Status: active, ongoing development — not yet a finished product.**
> See [License](#license) before using or forking anything here.

---

## What this project actually is

Most "vanilla on Minestom" efforts either stop at terrain that merely
*looks* right, or plateau once the easy 80% is done. Minecom's goal is
different: **match real vanilla Minecraft as closely as the underlying
algorithms allow** — the same world generation, the same structure
layouts, the same mob behavior, the same redstone timing, driven by the
*actual* Mojang data files (recipes, loot tables, tags, biome parameters,
NBT structure templates) extracted from the official server jar, not
guessed at.

Where a mechanic is genuinely too expensive or out of scope to fully
replicate right now, that's stated plainly in the source and tracked
honestly rather than silently faked. The project draws a hard legal line:
decompiled Mojang source is used only as a *reference* to understand
behavior — see [License](#license) for the full boundary.

## How correctness is verified

This isn't "it looks about right in the client." Three layers of
verification run continuously as the project grows:

- **A real region-diff against real vanilla.** A genuine vanilla 26.1.2
  dedicated server generates a large region of terrain for a fixed seed;
  Minecom generates the same region; every block is diffed. This is the
  project's north star number and it's currently **99.38%+ bit-exact**
  across a 1,296-chunk (36×36) sample — every mismatch class in the
  remaining gap is individually identified, root-caused, and tracked
  (some are deliberately deferred architectural work, most are down to a
  handful of blocks per hundred thousand).
- **`--selftest`** — a deterministic battery of data-engine checks
  (currently 180+) covering world generation, structures, loot tables,
  recipes, and biome logic without needing a live server.
- **`--playtest`** — headless gameplay verification (currently 220+
  checks) that boots the real server wiring, joins a fake player, and
  drives actual gameplay through the same event pipeline a real client
  uses: combat, mob AI, structures, minecarts, boats, redstone, potions,
  enchanting, the Nether, the End, raids, and more.

Every change lands only after both suites pass clean and, for anything
touching world generation, after a fresh region-diff measurement.

## Current scope

**World generation** — the real overworld density-function/noise-router
engine (bit-exact against Mojang's own graph), aquifers, ore veins,
biome-parameter climate model, surface rules, cave carving, and — the
thing most reimplementations skip — **Beardifier**: the terrain-carving
system that lets structures physically reshape the ground around them
(the mechanic behind, e.g., an Ancient City's excavated caverns) rather
than just stamping blocks on top of unmodified terrain.

**Structures — all 20 vanilla structure sets**, each placed via a faithful
port of the real jigsaw/piece-tree assembly and (where applicable) real
extracted NBT templates: villages (all 5 biome variants), the ancient
city, ocean monuments, woodland mansions, strongholds, trial chambers,
end cities, nether fortresses, ruined portals, pillager outposts, trail
ruins, mineshafts, igloos, swamp huts, shipwrecks, ocean ruins, desert
pyramids, jungle temples, buried treasure, and nether fossils.

**Dimensions** — overworld, Nether (its own noise settings, obsidian
portals with real coordinate scaling, nether-exclusive mobs), and the End
(bit-exact island terrain, obsidian spikes, an actual ender dragon fight
that heals off crystals and forms the exit portal on death, chorus
plants, end gateways).

**Mobs & AI** — a real goal-based AI brain (not a state-machine
approximation) driving the full natural-spawner (biome-weighted spawn
lists, mob caps, the pack-spawn loop, spawn-placement predicates — and
now real block-light-aware spawn suppression, so a placed torch actually
keeps monsters away), ~50 natural-spawn mob types, raids, villager
trading, breeding, and combat AI that matches vanilla's actual goal
priorities and timings — down to details like pillagers visibly charging
a crossbow with the real state machine rather than shooting on a
skeleton's bow rhythm, and lightning-charged creepers dropping their
victim's head.

**Redstone** — wire networks with real 15-block decay and vanilla's
line/dot pointing rules, quasi-connectivity, pistons (12-block push limit,
sticky retraction), repeaters, comparators (including container reading),
hoppers, dispensers/droppers, and observers.

**Survival systems** — the real hunger/exhaustion model, brewing (the
full potion recipe graph), enchanting (bookshelf-scaled table offers,
real loot-table-driven silk touch/fortune/looting), fishing (straight
from Mojang's own loot tables), farming, breeding, fall/fire/drown/void
damage, weather cycles with lightning, and full crafting (shaped/shapeless,
tag-resolved, from the real recipe set — 1,515 recipes bundled verbatim).

## Development approach

Minecom is being built **continuously**, with work tiered to a Claude
model matched to the difficulty of the problem: **Sonnet 5** handles the
bulk of routine implementation and porting work, **Opus** takes on the
harder architectural and debugging problems, and **Fable** is reserved
for the genuinely hardest cases — the kind of subtle, deeply-nested
correctness bugs that need the most capable reasoning available. Every
change is verified against the test suites and, for worldgen, against
real vanilla output before it lands — nothing ships on vibes alone, the
tests have to actually pass.

## License

**This repository is public for visibility, not for reuse.** Every file
here is `Copyright (c) 2026 PointOfPressure`, all rights reserved — see
[`LICENSE`](./LICENSE) for the full notice and the reasoning behind it.
Short version: the project is moving too fast right now for a fork to be
worth much, and once it stabilizes the intent is to release it under a
proper permissive open-source license. Until then: look, learn, open
issues — but don't redistribute or reuse the code without asking first.

## Requirements & running

- Java 25+
- Client Minecraft **26.1.2**

```sh
mvn package
java -jar target/minecom.jar            # server on :25565
java -jar target/minecom.jar --selftest # deterministic data-engine test battery
java -jar target/minecom.jar --playtest # headless gameplay verification
```

`--playtest` boots the full server wiring on a real world, joins a fake
player, and drives actual gameplay through the same event pipeline real
clients use — mining, crafting, combat, structures, redstone, potions,
minecarts, the Nether, the End, raids, and more.

Offline mode (LAN) by default; add `MojangAuth.init()` in `Main` before
`server.start()` for online-mode auth.
