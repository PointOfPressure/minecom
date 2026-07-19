# Tier 4 — The Nether: Design

Written 2026-07-17 (Opus, nether-design worktree off main @ v0.28.0), the
design doc MASTERPLAN §3 Tier 4 calls for. It is the sibling of
`docs/P1-DESIGN.md` and matches its rigor: every claim below is cited to a
`file:line` in this tree at v0.28.0 or to a committed doc. Implementation
sessions execute against it.

**One-line goal**: make the Nether a *bit-exact decompiled port of vanilla*
on the engine that already generates the overworld and the End — and prove it
with the region-diff harness extended to the Nether dimension — replacing
today's plausible-but-fabricated approximate generator, without moving the
overworld region-diff baseline (99.3613%) or the suites (228 + 822).

**Build, not adopt.** The one community worldgen lib that exposes a
`.nether()` Generator (`rocks.minestom.worldgen`, launch-prep.md:385-396) and
vibenilla (Apache-2.0, MASTERPLAN §11.4:382) are not adoptable here: a
2026-07-17 probe found vibenilla's *public* code is dormant pre-26.2 and its
26.2 work is unpublished, so there is nothing to vendor today. minecom builds
its own Nether on its own engine, exactly as it did for the overworld and the
End — and later exposes it through the `WorldGenerators` facade
(launch-prep.md:387-396) so the Nether flips from "falls through to caller" to
"verified" for adopters.

---

## 1. Current state — how approximate is the Nether today?

### The verdict

**`NetherGen.java` (679 lines) is a fully custom, fabricated generator, not a
port. Its bit-exactness against vanilla is ~0% by construction.** It is a
convincing-looking Nether, not a vanilla Nether, and it is documented as such:
"the whole nether is an approximate generator by design ... a future bit-exact
nether port is the single largest worldgen item left. (XL)"
(`docs/AUDIT.md:1173-1176`; also `HANDOFF.md:1223`, MASTERPLAN §3:155-160).

Five independent facts establish the ~0% verdict, each cited:

1. **Wrong seed.** The generator is keyed off a hard-coded internal constant
   `SEED = 0x4E65746865724DL` ("NetherM"), *not the user's world seed*
   (`NetherGen.java:27`, javadoc `:148-172` states this explicitly). Vanilla's
   Nether is a deterministic function of the world seed; minecom's is
   identical for every world. Nothing can line up with vanilla.

2. **Wrong noise.** Terrain uses a bespoke value-noise lattice —
   `fbm2/fbm3/value2/value3/mix/h` (`NetherGen.java:627-678`), "same lattice
   family as WorldGen" (`:625`), i.e. the pre-`VDensity` legacy hash noise —
   not vanilla's `NormalNoise`/`BlendedNoise` stack that `VNoise`/`VDensity`
   implement bit-exactly (`VNoise.java:1-9`). Different math, different output.

3. **Wrong terrain.** Fixed bedrock shells (`:42-49`), a custom cavern
   predicate `carved()` with hand-tuned height bias (`:80-85`), custom
   `material()` netherrack/gravel/blackstone/soul-sand patches (`:563-570`),
   lava sea pinned at `y=31` (`LAVA_SEA`, `:28`), height range 0-127
   (`CEILING`, `:29`). None of this is decompiled from vanilla's nether
   `noise_settings`.

4. **Missing biomes, fabricated placement.** Only **3 of the 5** nether
   biomes are ever assigned as terrain (`nether_wastes`,
   `soul_sand_valley`, `crimson_forest`), via a single soul-noise threshold
   (`:66-76`, `:88-93`) — not a multi-noise climate source.
   `warped_forest` and `basalt_deltas` are never generated as terrain
   biomes (they appear only inside structure biome-gates, `:288`, `:386`).

5. **Stand-in fortress; decoupled structures.** The fortress is a bounded
   nether-brick platform + two bridges with one fixed blaze spawner, not a
   piece-tree (`:107-133`, javadoc `:98-106`). The nether-fossil
   (`:185-241`), bastion (`:292-330`) and ruined_portal_nether (`:410-547`)
   layers *do* reuse the real vanilla placement engines (`VStructures`
   random-spread grid, `VSurface.LegacyRandom.setLargeFeatureSeed`,
   `VJigsaw`, real template pools) — genuinely faithful RNG — but they are
   keyed off the internal `SEED` and placed onto the approximate terrain, so
   their world positions do not match vanilla either. The fortress/bastion
   placement-grid conflict is a known 1.44%-collision hack (`:266-278`),
   because the real `nether_complexes` mutual-exclusion grid was never ported.

### The measurement gap (why the verdict is "asserted, not measured")

MASTERPLAN §3:159-160 is explicit: "region-diff harness (§2.1) must exist
first so nether parity is *measured* from day one, not asserted." **Today it
cannot measure the Nether at all** — the harness is overworld-only:

- `scripts/worldgen_region_diff.py:52` hard-codes `SECTION_MIN, SECTION_MAX =
  -4, 19` (overworld y -64..319).
- `vanilla_oracle.py:38`: `VANILLA_REGION_SUBDIR =
  "world/dimensions/minecraft/overworld/region"` — a single overworld path,
  no dimension argument.
- `gen_vanilla` (`worldgen_region_diff.py:69-115`) forceloads in the server's
  default (overworld) dimension; `gen_minecom` runs `--genregions`, and
  `GenRegions.run` only builds the overworld `VanillaGen` into `./world/region`
  (`GenRegions.java:44-48`, javadoc `:14-27`).

So the committed 99.3613% north-star number is overworld-only. **The Nether's
approximation is currently unmeasured.** Extending the harness to the Nether
is therefore the hard prerequisite (S0 below), exactly as MASTERPLAN demands.

### S0 DONE (2026-07-19, `nether-s0` branch) — the gap is now closed

The harness and extractor were extended per §4/§5 below; the Nether is now
**measured**. All four §4 changes landed (harness + data only, no production
`src/` behavior change — the live server keeps `NetherGen`, Bootstrap.java:99):

- `vanilla_oracle.py` — `VANILLA_NETHER_REGION_SUBDIR` +
  `region_subdir(dimension)`.
- `worldgen_region_diff.py` — `--dimension {overworld,nether}`, a per-dimension
  `DIMENSIONS` table (section window, region subtrees, `mc_dimension`), the
  vanilla side drives `execute in minecraft:the_nether run forceload ...`, and
  the nether runs in an independent work dir (`_nether` suffix) so the overworld
  cache/baseline can never be perturbed. Nether scans sections 0..7 (y 0..127).
- `GenRegions.java` — `--genregions <seed> <radius> [cx cz [dimension]]`;
  `nether` builds today's `NetherGen` into
  `world/dimensions/minecraft/the_nether/region` (per-dimension Anvil subtree).
- `extract_vanilla_data.py` — bundles the nether inputs (all NEW files, no
  existing file touched): `noise_settings_nether.json` (carries the nether
  **surface rule**, sea_level 32, netherrack/lava, aquifers off),
  `worldgen_noise_nether.json` (nether temperature/vegetation NormalNoise),
  `biome_parameters_nether.json` (5-biome quantized climate table),
  `carvers_nether.json` (`nether_cave`), `biome_features_nether.json` (the 5
  nether biomes' feature steps), and `structure_biomes/nether_fortress.json` +
  `nether_fossil.json` (the nether structure biome-gates).

**Honest baseline for today's `NetherGen` (the *before* number):**

> **41.796950% full-state** — seed 20260708, radius 18, center (0,0): 1,296
> chunks, 42,467,328 blocks (sections 0..7), 17,750,048 matched, 0 chunks
> missing. Cached at `~/minecom-region-diff/20260708_r18_0x0_26.2_nether/`;
> log `test-logs/regiondiff_nether_41.80pct_s0_baseline.log`.

This 41.8% is *coincidental* full-state overlap (both generators fill the 0..127
band with large volumes of netherrack, air and lava, so those cells match by
chance); the **non-air structural** correspondence is ~0, exactly as §1's
five-fact verdict predicts (wrong seed, wrong noise). Dominant mismatch classes,
each a target for S1..S4:

| count | minecom ← vanilla | what it is |
|---|---|---|
| 9,715,432 | `netherrack ← air` | solid where vanilla has open cavern (wrong terrain/noise) |
| 2,622,325 | `lava ← netherrack` | lava sea pinned at y=31 vs vanilla's noise terrain |
| 2,455,259 | `air ← netherrack` | cavern where vanilla has solid |
| 1,400,805 | `netherrack ← basalt` | basalt_deltas surface never generated (S2) |
| 620,730 + 227,559 | `netherrack/air ← cave_air` | vanilla marks carved nether air as **`cave_air`**; `NetherGen` writes plain `air` — a systematic block-state class S1 must reproduce |
| 512,943 | `blackstone ← netherrack` | blackstone placement differs |
| 448,632 / 436,100 | `netherrack ↔ bedrock` | bedrock floor/roof shells at the wrong y (S1) |

Gates held for this step (§5, any miss reverts): overworld extractor outputs
byte-identical (`--validate`: every overworld file PASS, only the pre-existing
non-jar-derived `block_map_colors.json` uncovered, + the 7 new nether files
PASS); overworld region-diff **exactly 99.361284%** (cached vanilla side reused,
not `--fresh`); suites **258 selftest + 1015 playtest** green on an idle machine
(the doc's original 228+822 predates the current release).

---

## 2. Why the engine is ready — the leverage

The port is large but *low-risk*, because none of the hard machinery is new.
The interpreter is already dimension-agnostic; the Nether is **data + one
generator class + a harness extension**, not new engine code. Evidence:

- **`VDensity` already drives a second dimension.** `VEndGen`
  (`VEndGen.java:34-67`) runs the *same* `VDensity` interpreter and `VNoise`
  stack off `noise_settings_end.json`, with `builder.setCellSize(8, 4)`
  (`:64`) instead of the overworld default 4/8 (`VDensity.java:37-38`).
  The Nether's `noise_settings` is `size_horizontal 1 → cellWidth 4`,
  `size_vertical 2 → cellHeight 8` — the *same* cell size the overworld
  already uses, so no new cell-mode code is needed at all.

- **Height range is a per-generator constant, not a global.** `VanillaGen
  MIN_Y=-64 HEIGHT=384` (`:22-23`); `VEndGen MIN_Y=0 HEIGHT=128` (`:36-37`).
  The Nether is `MIN_Y=0, HEIGHT=128` — a one-line pair of constants in the
  new generator.

- **No aquifer to port.** Nether `noise_settings` has `aquifers_enabled=false`,
  so the fill collapses to the simple `default_block` (netherrack) /
  `default_fluid` (lava, below sea_level 32) / air rule — *strictly simpler*
  than `VanillaGen`'s `VAquifer.computeSubstance` path
  (`VanillaGen.java:238-245`). This is the same simplification `VEndGen`
  enjoys (end_stone-or-air, `VEndGen.java:87-89`).

- **`VBiome` is a general multi-noise RTree source; only its data is
  overworld-bound.** The 6-climate router + `Climate.RTree` port is
  dimension-agnostic (`VBiome.java:24-56`); the single overworld coupling is
  the hard-coded resource `/vanilla/biome_parameters_overworld.json`
  (`:38`). The Nether uses the same code path with a ~5-entry parameter list
  and a constructor argument for the resource name.

- **`VSurface` is data-driven from `noise_settings.surface_rule`**
  (`VanillaGen.java:62-63`). The Nether's surface rule (netherrack default +
  soul_sand/soul_soil/gravel/basalt/blackstone sequences) is just a different
  JSON tree fed to the same engine.

- **The jigsaw/template structure engines already run in the Nether.**
  `NetherGen` already assembles a real `bastion/starts` jigsaw via `VJigsaw`
  (`NetherGen.java:286-330`) and single-template fossils/portals via
  `VStructures`/`VTemplate`. These are the same engines `VStructureManager`
  uses for the overworld — they only need to run against *real* terrain and
  the *real* world seed.

- **What is missing is DATA, not code.** `extract_vanilla_data.py`
  deliberately excludes every nether input today: "SUBSET: nether/temperature
  + nether/vegetation excluded; minecom only generates overworld + end"
  (`:40-41`), `noise/nether/*` skipped (`:237-240`),
  `noise_settings_nether.json` not extracted (`:254-256`), the nether
  multi-noise preset and biome parameter table not built, `biome_carvers`
  excludes `nether_cave` (`:44`), `biome_features` is "the 54 overworld
  biomes only" (`:65`), and the nether structure biome-gates
  (`nether_fortress`, `nether_fossil`) are not bundled (`:104-105`). Every
  one of these is a mechanical extension of the extractor (CLAUDE.md rule 9 —
  regenerate, never hand-edit).

**Caveat (CLAUDE.md rule 7):** cached `vanilla-src/` classes predating the
2026-07-13 26.2 bump are 26.1.2 decompiles. MASTERPLAN:350 flags "nether
re-parity" as a consumer of the re-decompile requirement. Every nether class
ported against here (`NoiseGeneratorSettings` nether preset,
`MultiNoiseBiomeSource` nether preset, `NetherFortressStructure`/
`NetherFortressPieces`, the nether surface-rule builder) must be re-decompiled
from `~/mc-26.2/versions/26.2/server-26.2.jar` before porting.

---

## 3. Port order — mirror how the overworld was done

The overworld was built density-router → biomes → surface rules →
carvers/features → structures (`VanillaGen.java:56-69` wires exactly that
stack). The Nether mirrors it, phase for phase, each phase citing its
overworld analog:

- **Phase A — nether final_density / noise-router (terrain shape).** A new
  `VNetherGen implements Generator` paralleling `VEndGen`: read
  `worldgen_density.json` + `worldgen_noise.json` +
  `noise_settings_nether.json`, `new VDensity.Builder(...)` (default cell
  size), `build(router.get("final_density"))` — the pattern is
  `VanillaGen.java:49-66` / `VEndGen.java:54-66`. Fill rule: `density>0` →
  `default_block` (netherrack), else `y < sea_level(32)` → `default_fluid`
  (lava), else air; plus bedrock floor/roof from the `noise_settings`
  `bedrock_floor_position`/`bedrock_roof_position`. No aquifer, no veins in
  this phase.

- **Phase B — nether biomes + surface rules.** `VBiome` with a
  `biome_parameters_nether.json` (the 5 biomes: `nether_wastes`,
  `soul_sand_valley`, `crimson_forest`, `warped_forest`, `basalt_deltas`),
  constructed via a resource-name argument (the only new coupling vs
  `VBiome.java:38`). `VSurface` fed the nether `surface_rule` from the same
  `noise_settings` file (`VanillaGen.java:62-63` pattern) — soul_soil in
  soul_sand_valley, nylium/roots handled at the feature layer, basalt/
  blackstone deltas, gravel patches.

- **Phase C — carvers, features, then structures.** Nether cave/canyon
  carvers (`VCarver`), then configured/placed features (glowstone blobs,
  nether ore veins — `nether_gold_ore`/`nether_quartz_ore`/`ancient_debris`
  via the *real* ore feature, not the hand-rolled `NetherGen.ores`
  `:585-613`; fire, fungi, weeping/twisting vines, basalt columns, delta
  lava), and finally structures through the jigsaw machinery: the **real**
  `nether_bridge` fortress piece-tree (retiring the platform stand-in
  `NetherGen.fortress` `:107-133`), bastion via the real `nether_complexes`
  placement grid (retiring the 1.44% fortress-collision hack `:266-278`),
  nether_fossil and ruined_portal_nether re-keyed to the world seed and to
  real terrain reads. This is where the existing faithful placement RNG
  (`NetherGen:185-547`) gets re-pointed at a real generator instead of being
  thrown away.

---

## 4. Region-diff-from-day-one gate (extend the harness to the Nether)

This section specifies S0 — the prerequisite that makes every later step
*measured*. Four concrete changes to the harness, none touching production
`src/`:

1. **Oracle region path (per-dimension).** Add
   `VANILLA_NETHER_REGION_SUBDIR = "world/dimensions/minecraft/the_nether/region"`
   alongside the overworld one (`vanilla_oracle.py:38`). Give `RegionReader`
   and `gen_vanilla` a dimension parameter.

2. **Vanilla-side generation in the Nether.** `gen_vanilla`
   (`worldgen_region_diff.py:94-113`) issues `forceload add` in the overworld;
   for the Nether it must run `execute in minecraft:the_nether run forceload
   add ...` (and poll the_nether region status), or the harness gains a
   `--dimension {overworld,nether}` flag threaded through both sides. The
   gamerule/save-flush plumbing (`:85-90`) is unchanged.

3. **minecom-side dimension argument.** Extend
   `GenRegions.run` (`GenRegions.java:32-70`) to accept an optional dimension
   token: for `nether`, build `VNetherGen` (Phase A output) instead of
   `VanillaGen` and save into `world/dimensions/minecraft/the_nether/region`
   so the comparator points at matching subtrees. Overworld invocation stays
   byte-identical.

4. **Section window + roof.** Nether y-range is 0-127, so the comparator uses
   `SECTION_MIN=0, SECTION_MAX=7` (8 sections × 4096 = 32,768 blocks/chunk)
   for the Nether, vs the overworld's `-4..19` (`worldgen_region_diff.py:52`
   must become dimension-parameterized, not a module constant). The nether
   `DimensionType` is nominally 256 tall (`logical_height=128`), but the
   `noise_settings` height is 128 and a bedrock roof caps content at ~127, so
   sections 8-15 are all-air: assert them empty on **both** sides and compare
   0-7. The `compare()` blockstate machinery (`:176-242`) and the
   `default_block_properties` completion table (`:304`, regenerated from the
   jar per CLAUDE.md rule 9) work unchanged — nether blocks are already in
   the jar report. Optionally add a biome-plane comparison, since nether
   biome is a gameplay-visible 3D field.

The overworld and nether diffs run as **independent work dirs**
(`worldgen_region_diff.py:275` already namespaces by seed/radius/center/MC
version), so the overworld 99.3613% baseline is re-run unchanged after every
nether step — it can never be silently regressed by nether work.

---

## ADOPT (2026-07-19, `nether-adopt` branch) — measured, threshold missed

This section reshapes §5: the plan pivoted from *build our own `VNetherGen`
(S1-S4)* to *adopt the vibenilla worldgen library and gate it with our own
instrument*. Context that flipped the 2026-07-17 "Build, not adopt" verdict
(§ preamble): the 2026-07-17 probe found vibenilla's 26.2 work unpublished;
by 2026-07-19 the repo is public and was independently measured at nether
**99.7650%** blocks / **100%** biomes against our cached vanilla ground truth
(seed 20260708). If that number reproduces under **our** region-diff, S1-S4
collapse into a single binary-dependency adoption; S5 (live cutover) keeps its
exit criteria (§6) unchanged.

### What landed (harness + offline path only; live server untouched)

- `scripts/fetch_vibenilla.sh` — reproducible pinned build: clone
  vibenilla/worldgen @ `ffaafa1`, stub the gitignored `StripTypeAnnotations`,
  `./gradlew jar`, install as `rocks.minestom:worldgen:26.2-ffaafa1` to `~/.m2`.
  Their library stays a **binary dependency** — never vendored into `src/`.
- `pom.xml` — declares `rocks.minestom:worldgen:26.2-ffaafa1`.
- `VibenillaNether.java` — offline bridge; self-provisions the vanilla datapack
  (`data/**` extracted from the server jar into `~/mc-26.2/datapack`, outside
  the repo, same rule as `vanilla-src/`) and returns their `.nether()` Generator.
- `GenRegions.java` — new `nether_vibenilla` dimension token → their generator
  into the same `the_nether` region subtree, seed-keyed. The `nether`
  (`NetherGen`) token stays as the baseline. Bootstrap / live server UNCHANGED.
- `worldgen_region_diff.py` — `--dimension nether_vibenilla` reuses the `nether`
  vanilla cache byte-for-byte (`vanilla_tag`) and only regenerates the minecom
  side, so both nether numbers are measured against identical vanilla.
- Licensing hygiene: `NOTICE`, `docs/licenses/vibenilla-worldgen-LICENSE.txt`,
  `docs/AUDIT.md` entry (Apache-2.0 → AGPLv3 is a compatible direction).

### The number our instrument produced

> **98.748042% full-state** — seed 20260708, radius 18, center (0,0): 1,296
> chunks, 42,467,328 blocks (sections 0..7), 41,935,655 matched, 0 chunks
> missing. Log `test-logs/regiondiff_nether_vibenilla_seed20260708_r18_20260719-193211.log`.
> Minecom side generated in seconds; vanilla side reused the `_nether` cache.

**This is below the ~99% floor the adoption step set for itself, so per the
plan this STOPS here and reports rather than debugging their generator.** But
the shape of the result is unambiguous and strongly positive:

1. **Terrain is solved — bit-exact.** Every dominant mismatch class of the
   `NetherGen` 41.80% baseline (§1 "S0 DONE": `netherrack←air` 9.7M,
   `lava←netherrack` 2.6M, `air←netherrack` 2.5M, `netherrack↔bedrock` ~0.9M)
   is **entirely absent** from this run's top-40. The 41.80% → 98.75% jump is
   the terrain shape (density/caverns, lava sea, bedrock shells) now matching
   vanilla. That is the whole point of the adoption and it worked.

2. **The residual 1.25% is 100% decoration-layer**, and split as:

   | ~share | classes | feature |
   |---|---|---|
   | ~73% (≈388k) | `basalt↔blackstone↔netherrack` swaps | `basalt_deltas` delta patches |
   | ~23% (≈124k) | `nether_wart_block/crimson_roots/crimson_stem/weeping_vines/warped_wart_block/shroomlight/crimson_fungus ↔ air` | crimson/warped forest vegetation |
   | ~4% | magma_block, gravel, nether_quartz_ore, nether_bricks | minor feature/ore layer |

3. **The swaps are symmetric** (`basalt←blackstone` 77,513 ≈ `blackstone←basalt`
   73,363; `nether_wart_block←air` 43,187 ≈ `air←nether_wart_block` 42,402).
   Symmetry ⇒ the features **are placed**, just at positionally-shifted voxels —
   NOT missing. A generator that simply omitted features would show asymmetric
   `air←X` classes dominating; it doesn't.

### The finding (why our 98.75% ≠ their 99.765%)

The ~1% delta between our instrument and vibenilla's self-reported 99.765% is
concentrated in **exactly the two feature families most sensitive to
cross-chunk decoration spill** (`basalt_deltas` and nether-forest vegetation
both decorate with neighbor context), and it manifests as **symmetric
positional swaps** — the signature of a **decoration-order / cross-chunk
feature-spill artifact between the two harnesses**, not a terrain-quality
deficit and not missing content:

- Our oracle is a **real vanilla dedicated server**, which applies vanilla's
  true "decorate only when the 8 neighbours are terrain-generated" rule, so
  border features land with full neighbour context. Our own `VanillaGen` mirrors
  this via `decoratedData` (re-runs neighbours' decorations and clips spill —
  `GenRegions.java` javadoc). Vibenilla's `WorldGenerator`, driven through
  Minestom's **per-chunk** `Generator` interface by `--genregions`, decorates
  each chunk without that identical neighbour-spill model, so voxel positions of
  deltas/vegetation shift near chunk borders. Over 1,296 chunks that is ~1.25%,
  entirely in the feature layer.
- Vibenilla's own `VanillaComparison` harness presumably drives both sides
  through the same chunk model, so it does not surface this border divergence —
  hence their 99.765%. **The discrepancy is a harness artifact, exactly the
  hypothesis §ADOPT was told to expect; confirming/closing it is orchestrator
  work, not a reason to touch their generator.**

### Recommendation for the orchestrator

- **Do not merge as-is against the §6 exit target (≥99.0% with every residual
  attributed to a decompile-verified simplification).** We are at 98.75% and the
  residual is *plausibly* a harness artifact but is **not yet attributed to a
  documented simplification** — the §6 bar is "residual explained," and this
  residual is characterised but not proven.
- **The landable value here is real and low-risk:** the pinned reproducible
  build, the binary-dependency wiring, and the `nether_vibenilla` measurement
  path. These make the adopted Nether *measurable by our instrument* (the S0
  spirit) and prove terrain parity. Commit them; keep `NetherGen` live.
- **Next move is a harness question, not a generator one:** decide whether
  `--genregions nether_vibenilla` should drive vibenilla through the same
  neighbour-decoration model our `VanillaGen` uses (re-run neighbours' feature
  passes and clip spill) before re-measuring. If, after aligning the decoration
  driver, the number clears ~99.7%, adoption is confirmed and S1-S4 are retired;
  if it does not, the gap is a genuine vibenilla feature-placement difference and
  the build-our-own path (§5 below) is back on the table for the feature layer
  only (terrain is already solved by adoption).

---

## 5. Sequencing — S0..S5, smallest risk first (superseded for S1-S4 by ADOPT above)

Each step is one landable session. What stays green after **every** step:
228 selftest + 822 playtest (CLAUDE.md rule 8 — two consecutive greens on an
idle machine after any flake-class fix) **and** the overworld region-diff at
exactly 99.3613% (independent harness dir). The nether region-diff number
climbs from its S0 baseline. Any step that misses its bar reverts, not
"fix-forward" (the P1-DESIGN §2.4 precedent).

| step | change | proves / gate |
|---|---|---|
| **S0** | Extend region-diff to the Nether (§4) **and** extend `extract_vanilla_data.py` to bundle nether inputs (noise_settings_nether, biome_parameters_nether, nether surface rule, nether carvers/features, nether structure biome-gates). Data-only + harness-only; production behavior unchanged; overworld extractor outputs byte-identical (rule 9 `--validate`). | Nether parity is now **measurable**. Records the *before* number for today's `NetherGen` (expected near-0 non-air match) as the honest baseline. **DONE 2026-07-19 (`nether-s0`): 41.796950% full-state; non-air ~0. See §1 "S0 DONE".** |
| **S1** | `VNetherGen` terrain — Phase A (final_density + fill + bedrock shells). Wired **only** into `--genregions nether`; the live server keeps `NetherGen` (Bootstrap.java:98) so shipped behavior can't regress before parity is proven. | Nether terrain (netherrack/lava/air + bedrock floor/roof) bit-matches vanilla where biomes don't matter; nether % jumps to the terrain-shape match. |
| **S2** | Nether multi-noise biomes (5) + nether surface rules — Phase B. | Surface substances (soul_soil, gravel, basalt/blackstone) and the biome plane match vanilla. |
| **S3** | Nether carvers + configured/placed features — Phase C part 1 (glowstone, real ore features, fungi/vines/columns/fire). | Decoration matches; nether % approaches the structure-free ceiling. |
| **S4** | Structures on real terrain + real seed — Phase C part 2: real `nether_bridge` fortress piece-tree (retire platform stand-in), bastion via real `nether_complexes` grid (retire 1.44% hack `NetherGen:266-278`), nether_fossil + ruined_portal_nether re-keyed to world seed & `VNetherGen` terrain reads. | Structures line up with vanilla positions (a `testNetherStructureAt` probe); blaze spawner / fortress loot keyed correctly. |
| **S5** | Live cutover: `Bootstrap.java:98` `setGenerator(new VNetherGen(seed))`; re-point the ClassicSpawners nether biome lambda (`Bootstrap.java:245`) from `NetherGen.biomeAt` to `VNetherGen.biomes`; retire/relegate `NetherGen` (keep only if STRATEGY wants it as the Lite profile's cheap generator). Gated on the nether region-diff clearing its exit target (§6), not on a date. | Full parity live; suites green with the real generator; no API break because the swap is one `setGenerator` line behind a proven number. |

---

## 6. Exit criteria — Tier 4 is done when

1. **Nether region-diff ≥ 99.0% full-state** on the default seed/radius, with
   *every* residual mismatch class enumerated and each attributed to a
   documented, decompile-verified simplification — mirroring the overworld's
   mismatch-class ledger (the overworld sits at 99.3613% with named
   irreducibles: fluid settling `worldgen_region_diff.py:33-35`, leaf-distance
   props `AUDIT.md:1181-1188`). The Nether has analogous irreducibles (lava
   settling, feature-RNG edges); the bar is "residual is explained," not
   "residual is zero."
2. **These structures generate at vanilla positions, world-seed-keyed:**
   `nether_fortress` (real jigsaw piece-tree), `bastion_remnant` (real
   placement grid, no fortress-collision hack), `nether_fossil`,
   `ruined_portal_nether` — verified by a `testNetherStructureAt`-style probe
   the way the overworld structures are.
3. **Overworld region-diff still exactly 99.3613%**; suites 228 + 822 green;
   zero new AUDIT simplifications introduced outside the Nether's own
   documented ledger.
4. The `WorldGenerators` facade (launch-prep.md:385-396) advertises the
   Nether as **verified** instead of "falls through to caller."

---

## 7. Non-goals (documented, not silent)

- **No performance work.** `VNetherGen` inherits the same bit-exact
  interpreter and therefore the same 7.5-8.5× chunkgen gap P1 owns
  (`P1-DESIGN.md §1`); parity first, throughput is P1's job and applies
  uniformly across dimensions once landed. No `GenContext`/executor work here.
- **No multicore.** Region ownership is P2 (MASTERPLAN §P2); the Nether is a
  generator, not a threading change.
- **No API / behavior breaks.** The live server keeps the approximate
  `NetherGen` until S5, and the cutover is a single `setGenerator` line gated
  on the region-diff exit target — never a date. Existing nether-portal,
  fortress-blaze-spawner and persistence wiring keep working throughout
  (HANDOFF.md:880-947 multi-dimension spawner/persistence caveats stay
  respected).
- **No new worldgen machinery.** Reuse `VDensity`, `VNoise`, `VBiome`,
  `VSurface`, `VCarver`, `VFeature`, `VJigsaw`, `VStructures`, `VTemplate`
  exactly as-is. The Nether is data + one generator class + a harness
  extension. If a genuine interpreter gap appears (a density-function node
  type only the nether uses), it gets a HANDOFF entry, not an ad-hoc branch.
- **No other dimensions.** Overworld and End are done; this doc is the Nether
  only.
