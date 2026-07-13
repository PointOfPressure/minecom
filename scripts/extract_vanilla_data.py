#!/usr/bin/env python3
"""Rebuild src/main/resources/vanilla/ from the official Mojang server jar.

Reconstructed 2026-07-13 (26.2-upgrade step 1, docs/MASTERPLAN.md section 6): past
sessions extracted the bundled tree by hand with no surviving script; this file is
the reproducible replacement. Originally validated 100% against the bundled
26.1.2 tree (JSON compared semantically, .nbt byte-identical) via --validate;
since the 2026-07-13 bump the bundled tree and default jar are 26.2.

Usage:
    python3 scripts/extract_vanilla_data.py [--jar JAR] [--out DIR]
    python3 scripts/extract_vanilla_data.py --validate      # diff against bundled tree

Requires: Python 3 stdlib only. Two files additionally need the jar's own data
generator (see DATAGEN below), which needs `java` and the Mojang bundler library
tree next to the jar (~/libraries beside ~/versions/<v>/server-<v>.jar — the
layout a bundler unpack produces). The generated report is cached under
~/.cache/minecom/ so repeat runs are pure zip reads (< 10 s total).

PROVENANCE — every bundled file and the jar path(s) it is derived from
======================================================================
Aggregates (one JSON object merging a whole jar directory; key = file path
relative to that directory, ".json" stripped; value = parsed file content):
  recipes.json            data/minecraft/recipe/*.json                    (all 1515)
  loot_blocks.json        data/minecraft/loot_table/blocks/**             (all 1085)
  loot_entities.json      data/minecraft/loot_table/entities/**           (all 108)
  loot_gameplay.json      data/minecraft/loot_table/gameplay/**           (all 26)
  loot_chests.json        data/minecraft/loot_table/chests/**             (all 56; keys
                          keep the "chests/" prefix, i.e. relative to loot_table/)
  loot_trial.json         data/minecraft/loot_table/chests/trial_chambers/**
                          + data/minecraft/loot_table/spawners/**         (keys
                          relative to loot_table/; overlaps loot_chests by design)
  trial_spawner.json      data/minecraft/trial_spawner/**                 (all 28)
  tags_block.json         data/minecraft/tags/block/**                    (all 248, raw:
                          "#..." tag refs NOT resolved — VanillaData resolves at runtime)
  tags_item.json          data/minecraft/tags/item/**                     (all 207, raw)
  tags_damage_type.json   data/minecraft/tags/damage_type/**              (all 33, raw)
  worldgen_density.json   data/minecraft/worldgen/density_function/**     (all 35)
  worldgen_noise.json     data/minecraft/worldgen/noise/**                (60 of 62 —
                          SUBSET: nether/temperature + nether/vegetation excluded;
                          minecom only generates overworld + end)
Aggregates keyed "minecraft:<name>" (namespace prefix ADDED):
  carvers.json            data/minecraft/worldgen/configured_carver/*.json — SUBSET:
                          only carvers referenced by overworld biomes (cave,
                          cave_extra_underground, canyon; excludes nether_cave),
                          with the debug-only config.debug_settings key stripped
  configured_features.json data/minecraft/worldgen/configured_feature/**  (all 221)
  placed_features.json    data/minecraft/worldgen/placed_feature/**       (all 258)
  structure_sets.json     data/minecraft/worldgen/structure_set/**        (all 20)
Biome projections (fields of data/minecraft/worldgen/biome/<b>.json, keyed
"minecraft:<b>"):
  biome_carvers.json      "carvers" field, all 65 biomes
  biome_climate.json      [temperature, temperature_modifier or "none",
                           has_precipitation, downfall], all 65 biomes
  biome_features.json     "features" field — SUBSET: the 54 overworld biomes only
  spawners.json           {"biomes": {biome: {category: [{type, weight,
                           min<-minCount, max<-maxCount}]}}, "spawn_costs": {...}};
                          empty categories, all-empty biomes (deep_dark, the_void)
                          and empty spawn_costs entries are omitted
Verbatim single-file copies:
  multi_noise_overworld.json   data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json
  noise_settings_overworld.json data/minecraft/worldgen/noise_settings/overworld.json
  noise_settings_end.json      data/minecraft/worldgen/noise_settings/end.json
                               (SUBSET: only the 2 dimensions minecom generates)
Directory trees (file-per-file verbatim):
  template_pool/**        data/minecraft/worldgen/template_pool/**        (all 186)
  processor_list/**       data/minecraft/worldgen/processor_list/**       (all 40)
  structure/**.nbt        data/minecraft/structure/**.nbt — SUBSET: all 1185 except
                          empty.nbt and fossil/** (17 files; overworld fossils are a
                          *feature*, not a jigsaw structure — minecom doesn't use them)
  structure_def/*.json    data/minecraft/worldgen/structure/<n>.json — SUBSET: the 5
                          jigsaw structures minecom generates (ancient_city,
                          ruined_portal, ruined_portal_mountain, trail_ruins,
                          trial_chambers); the other 29 are hand-ported Java
Resolved biome tags (structure_biomes/<n>.json = {"values": [...]} with every
"#minecraft:..." ref recursively expanded in place against
data/minecraft/tags/worldgen/biome/** — vanilla TagLoader resolution order,
duplicates keep the first occurrence. VStructureManager reads them pre-resolved.
ORDER CAVEAT: the bundled trees' value ORDER is an artifact of (at least) two
different past hand-extractions and is provably not reproducible by any single
traversal — bundled pillager_outpost moves a trailing direct value ("grove")
ahead of a "#is_mountain" expansion (breadth-first), while bundled mineshaft
keeps its trailing direct values after all tag expansions (depth-first/in-place);
no one rule does both. The order is semantically dead: VStructureManager.loadBiomes
collapses "values" into a java.util.HashSet. --validate therefore compares
structure_biomes/*.json values as SETS (plus a no-duplicates check); everything
else in the tree is compared by strict parsed equality):
  structure_biomes/<n>.json    data/minecraft/tags/worldgen/biome/has_structure/<n>.json
  ...except 4 renames/re-sources:
    ruined_portal.json             <- .../has_structure/ruined_portal_standard.json
    mineshaft_blocking.json        <- data/minecraft/tags/worldgen/biome/mineshaft_blocking.json
    ocean_monument_surrounding.json <- data/minecraft/tags/worldgen/biome/required_ocean_monument_surrounding.json
    stronghold_biased_to.json      <- data/minecraft/tags/worldgen/biome/stronghold_biased_to.json
  (has_structure tags for structures minecom hasn't ported — nether_fortress,
   nether_fossil, ocean_monument, stronghold — are not bundled)

DATAGEN-derived files (jar code, not jar data — the jar's
data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json is just
{"preset": "minecraft:overworld"}; the actual parameter table lives in
net.minecraft.world.level.biome.OverworldBiomeBuilder and is dumped by the jar's own
data generator: `java -cp <jar>:<libraries...> net.minecraft.data.Main --reports`):
  biome_parameters_overworld.json  reports/biome_parameters/minecraft/overworld.json,
      each row = 12 quantized min/max climate bounds (temperature, humidity,
      continentalness, erosion, depth, weirdness) + quantized offset + biome id;
      quantize(v) = (long)((float) v * 10000.0F), float32 semantics (Climate.quantizeCoord)
  features_per_step.json  Python port of net.minecraft.world.level.biome.FeatureSorter
      .buildFeaturesPerStep + net.minecraft.util.Graph.depthFirstSearch: biomes =
      distinct biomes of the overworld parameter list in first-occurrence order,
      chains = each biome's per-step placed-feature lists, nodes keyed
      (featureIndex = first-seen order, step), DFS over a (step, featureIndex)-sorted
      graph, post-order reversed, then split by step. Deterministic; matches vanilla's
      feature-index decoration salts exactly.

NOT COVERED (deliberately): piston_reorder_cases.json is not jar-derived — it is a
behavior capture recorded from a live vanilla server by scripts/piston_vanilla_capture.py.
"""

import argparse
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JAR = os.path.expanduser("~/mc-26.2/versions/26.2/server-26.2.jar")
DEFAULT_OUT = os.path.join(REPO_ROOT, "src", "main", "resources", "vanilla")
CACHE_ROOT = os.path.expanduser("~/.cache/minecom")

# Files in the bundled tree that are NOT produced from the jar (see docstring).
NOT_JAR_DERIVED = {"piston_reorder_cases.json"}

# structure/ exclusions: overworld fossils are a feature, not a jigsaw structure.
STRUCTURE_NBT_EXCLUDE_PREFIXES = ("fossil/",)
STRUCTURE_NBT_EXCLUDE_FILES = {"empty.nbt"}

# The 5 jigsaw structure definitions minecom generates from data (structure_def/).
STRUCTURE_DEF_NAMES = [
    "ancient_city", "ruined_portal", "ruined_portal_mountain", "trail_ruins",
    "trial_chambers",
]

# structure_biomes/<out-name>.json <- tag path under data/minecraft/tags/worldgen/biome/
STRUCTURE_BIOMES_SPECIAL = {
    "ruined_portal": "has_structure/ruined_portal_standard",
    "mineshaft_blocking": "mineshaft_blocking",
    "ocean_monument_surrounding": "required_ocean_monument_surrounding",
    "stronghold_biased_to": "stronghold_biased_to",
}
# has_structure tags bundled under their own name (everything except unported ones).
STRUCTURE_BIOMES_DIRECT = [
    "ancient_city", "bastion_remnant", "buried_treasure", "desert_pyramid",
    "end_city", "igloo", "jungle_temple", "mineshaft", "mineshaft_mesa",
    "ocean_ruin_cold", "ocean_ruin_warm", "pillager_outpost",
    "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain",
    "ruined_portal_nether", "ruined_portal_ocean", "ruined_portal_swamp",
    "shipwreck", "shipwreck_beached", "swamp_hut", "trail_ruins",
    "trial_chambers", "village_desert", "village_plains", "village_savanna",
    "village_snowy", "village_taiga", "woodland_mansion",
]


class Jar:
    def __init__(self, path):
        self.zip = zipfile.ZipFile(path)
        self.names = [n for n in self.zip.namelist() if not n.endswith("/")]

    def bytes(self, path):
        return self.zip.read(path)

    def json(self, path):
        return json.loads(self.zip.read(path).decode("utf-8"))

    def under(self, prefix, suffix=""):
        """Sorted file paths under prefix (never descends into data/minecraft/datapacks/
        because those live under a different prefix — the bundle uses BASE data only)."""
        return sorted(n for n in self.names if n.startswith(prefix) and n.endswith(suffix))


# ------------------------------------------------------------------ builders
# Each builder returns {relpath-under-vanilla/: ("json", obj) | ("raw", bytes)}.

def aggregate(jar, jar_dir, key_prefix=""):
    """Merge every .json under jar_dir into one object, key = relative path
    minus .json, optionally prefixed (e.g. 'minecraft:')."""
    out = {}
    for n in jar.under(jar_dir, ".json"):
        out[key_prefix + n[len(jar_dir):-len(".json")]] = jar.json(n)
    return out


def build_aggregates(jar):
    lt = "data/minecraft/loot_table/"
    loot_trial = {}
    for n in jar.under(lt + "chests/trial_chambers/", ".json") + jar.under(lt + "spawners/", ".json"):
        loot_trial[n[len(lt):-len(".json")]] = jar.json(n)
    return {
        "recipes.json": ("json", aggregate(jar, "data/minecraft/recipe/")),
        "loot_blocks.json": ("json", aggregate(jar, lt + "blocks/")),
        "loot_entities.json": ("json", aggregate(jar, lt + "entities/")),
        "loot_gameplay.json": ("json", aggregate(jar, lt + "gameplay/")),
        # keys keep the "chests/" prefix (relative to loot_table/), matching
        # LootTables' "chests/village/village_weaponsmith" lookups
        "loot_chests.json": ("json", {"chests/" + k: v for k, v in
                                      aggregate(jar, lt + "chests/").items()}),
        "loot_trial.json": ("json", loot_trial),
        "trial_spawner.json": ("json", aggregate(jar, "data/minecraft/trial_spawner/")),
        "tags_block.json": ("json", aggregate(jar, "data/minecraft/tags/block/")),
        "tags_item.json": ("json", aggregate(jar, "data/minecraft/tags/item/")),
        "tags_damage_type.json": ("json", aggregate(jar, "data/minecraft/tags/damage_type/")),
        "worldgen_density.json": ("json", aggregate(jar, "data/minecraft/worldgen/density_function/")),
        # SUBSET: nether/* noises excluded (minecom generates overworld + end only)
        "worldgen_noise.json": ("json", {k: v for k, v in
                                         aggregate(jar, "data/minecraft/worldgen/noise/").items()
                                         if not k.startswith("nether/")}),
        "configured_features.json": ("json", aggregate(jar, "data/minecraft/worldgen/configured_feature/", "minecraft:")),
        "placed_features.json": ("json", aggregate(jar, "data/minecraft/worldgen/placed_feature/", "minecraft:")),
        "structure_sets.json": ("json", aggregate(jar, "data/minecraft/worldgen/structure_set/", "minecraft:")),
    }


def build_copies(jar):
    out = {
        "multi_noise_overworld.json": ("raw", jar.bytes(
            "data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json")),
        # SUBSET: only the two dimensions minecom generates (not nether/amplified/...)
        "noise_settings_overworld.json": ("raw", jar.bytes("data/minecraft/worldgen/noise_settings/overworld.json")),
        "noise_settings_end.json": ("raw", jar.bytes("data/minecraft/worldgen/noise_settings/end.json")),
    }
    for n in jar.under("data/minecraft/worldgen/template_pool/", ".json"):
        out["template_pool/" + n[len("data/minecraft/worldgen/template_pool/"):]] = ("raw", jar.bytes(n))
    for n in jar.under("data/minecraft/worldgen/processor_list/", ".json"):
        out["processor_list/" + n[len("data/minecraft/worldgen/processor_list/"):]] = ("raw", jar.bytes(n))
    for name in STRUCTURE_DEF_NAMES:
        out["structure_def/%s.json" % name] = ("raw", jar.bytes("data/minecraft/worldgen/structure/%s.json" % name))
    for n in jar.under("data/minecraft/structure/", ".nbt"):
        rel = n[len("data/minecraft/structure/"):]
        if rel in STRUCTURE_NBT_EXCLUDE_FILES or rel.startswith(STRUCTURE_NBT_EXCLUDE_PREFIXES):
            continue
        out["structure/" + rel] = ("raw", jar.bytes(n))
    return out


def build_structure_biomes(jar):
    """Biome tags with '#' refs recursively pre-resolved (first-occurrence order)."""
    tag_dir = "data/minecraft/tags/worldgen/biome/"

    def resolve(tag_path, seen):
        """In-place (vanilla TagLoader) expansion; see ORDER CAVEAT in docstring."""
        values = []
        for v in jar.json(tag_dir + tag_path + ".json")["values"]:
            if isinstance(v, dict):
                v = v["id"]
            if v.startswith("#"):
                ref = v[1:].split(":", 1)[1]
                if ref not in seen:
                    seen.add(ref)
                    values += resolve(ref, seen)
            else:
                values.append(v)
        # dedup, keep first occurrence
        out, have = [], set()
        for v in values:
            if v not in have:
                have.add(v)
                out.append(v)
        return out

    out = {}
    for name in STRUCTURE_BIOMES_DIRECT:
        out["structure_biomes/%s.json" % name] = ("json", {"values": resolve("has_structure/" + name, set())})
    for name, src in STRUCTURE_BIOMES_SPECIAL.items():
        out["structure_biomes/%s.json" % name] = ("json", {"values": resolve(src, set())})
    return out


# ------------------------------------------------------------------ datagen

def find_report(jar_path, reports_dir):
    """Locate (or generate + cache) reports/biome_parameters/minecraft/overworld.json."""
    rel = os.path.join("reports", "biome_parameters", "minecraft", "overworld.json")
    if reports_dir:
        p = os.path.join(reports_dir, "biome_parameters", "minecraft", "overworld.json")
        if not os.path.isfile(p):
            sys.exit("--reports-dir given but %s not found" % p)
        return p
    version = json.loads(zipfile.ZipFile(jar_path).read("version.json"))["id"]
    cache = os.path.join(CACHE_ROOT, "vanilla-reports-" + version)
    cached = os.path.join(cache, rel)
    if os.path.isfile(cached):
        return cached
    # bundler-unpack layout: <root>/versions/<v>/server-<v>.jar + <root>/libraries/
    libs = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(jar_path)))), "libraries")
    if not os.path.isdir(libs):
        sys.exit("Cannot generate biome_parameters report: no libraries/ dir at %s\n"
                 "(need the Mojang bundler unpack layout, or pass --reports-dir)" % libs)
    jars = [os.path.join(dp, f) for dp, _, fs in os.walk(libs) for f in fs if f.endswith(".jar")]
    cp = os.pathsep.join([os.path.abspath(jar_path)] + sorted(jars))
    os.makedirs(cache, exist_ok=True)
    print("running vanilla datagen (--reports) once, caching to %s ..." % cache)
    with tempfile.TemporaryDirectory(prefix="minecom-datagen-") as wd:
        subprocess.run(["java", "-cp", cp, "net.minecraft.data.Main", "--reports",
                        "--output", os.path.join(wd, "out")],
                       cwd=wd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        src = os.path.join(wd, "out", rel)
        os.makedirs(os.path.dirname(cached), exist_ok=True)
        shutil.copyfile(src, cached)
    return cached


def f32(x):
    return struct.unpack("f", struct.pack("f", x))[0]


def quantize(v):
    """Climate.quantizeCoord: (long)((float) value * 10000.0F), truncating."""
    return int(f32(f32(v) * f32(10000.0)))


def build_biome_parameters(report):
    rows = []
    for e in report["biomes"]:
        p = e["parameters"]
        row = []
        for k in ("temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"):
            v = p[k]
            lo, hi = (v[0], v[1]) if isinstance(v, list) else (v, v)
            row += [quantize(lo), quantize(hi)]
        row.append(quantize(p["offset"]))
        row.append(e["biome"])
        rows.append(row)
    return rows


def overworld_biome_order(report):
    """Distinct biomes of the overworld parameter list, first-occurrence order
    (= MultiNoiseBiomeSource.possibleBiomes() iteration order)."""
    order, seen = [], set()
    for e in report["biomes"]:
        if e["biome"] not in seen:
            seen.add(e["biome"])
            order.append(e["biome"])
    return order


def build_features_per_step(jar, biome_order):
    """Port of FeatureSorter.buildFeaturesPerStep (see docstring)."""
    feature_index = {}   # placed feature id -> first-seen index
    graph = {}           # node (featureIndex, step) -> successor set
    max_steps = 0
    for b in biome_order:
        steps = jar.json("data/minecraft/worldgen/biome/" + b.split(":", 1)[1] + ".json")["features"]
        max_steps = max(max_steps, len(steps))
        chain = []
        for step, feats in enumerate(steps):
            if isinstance(feats, str):
                feats = [feats]
            for f in feats:
                if f not in feature_index:
                    feature_index[f] = len(feature_index)
                chain.append((feature_index[f], step))
        for j, node in enumerate(chain):
            succ = graph.setdefault(node, set())
            if j < len(chain) - 1:
                succ.add(chain[j + 1])

    key = lambda n: (n[1], n[0])  # Comparator: step, then featureIndex
    order, visited, in_progress = [], set(), set()

    def dfs(n):  # net.minecraft.util.Graph.depthFirstSearch (True = cycle)
        if n in visited:
            return False
        if n in in_progress:
            return True
        in_progress.add(n)
        for m in sorted(graph.get(n, ()), key=key):
            if dfs(m):
                return True
        in_progress.discard(n)
        visited.add(n)
        order.append(n)
        return False

    sys.setrecursionlimit(100000)
    for n in sorted(graph, key=key):
        if n not in visited and dfs(n):
            raise RuntimeError("feature order cycle (FeatureSorter would throw)")
    order.reverse()
    by_index = {v: k for k, v in feature_index.items()}
    return [[by_index[i] for (i, st) in order if st == step] for step in range(max_steps)]


def build_biome_projections(jar, biome_order):
    biome_files = jar.under("data/minecraft/worldgen/biome/", ".json")
    carvers, climate, features = {}, {}, {}
    spawners, spawn_costs = {}, {}
    overworld = set(biome_order)
    for n in biome_files:
        bid = "minecraft:" + n[len("data/minecraft/worldgen/biome/"):-len(".json")]
        b = jar.json(n)
        c = b.get("carvers", [])
        carvers[bid] = [c] if isinstance(c, str) else c
        climate[bid] = [b["temperature"], b.get("temperature_modifier", "none"),
                        b["has_precipitation"], b["downfall"]]
        if bid in overworld:
            features[bid] = b["features"]
        cats = {}
        for cat, entries in b.get("spawners", {}).items():
            if entries:  # empty categories omitted
                cats[cat] = [{"type": e["type"], "weight": e["weight"],
                              "min": e["minCount"], "max": e["maxCount"]} for e in entries]
        if cats:  # all-empty biomes (deep_dark, the_void) omitted
            spawners[bid] = cats
        if b.get("spawn_costs"):
            spawn_costs[bid] = b["spawn_costs"]
    # carvers.json subset: only carvers referenced by overworld biomes
    used = []
    for bid in biome_order:
        for c in carvers.get(bid, []):
            if c not in used:
                used.append(c)
    carver_defs = {}
    for c in used:
        d = jar.json("data/minecraft/worldgen/configured_carver/" + c.split(":", 1)[1] + ".json")
        d.get("config", {}).pop("debug_settings", None)  # debug-only, stripped in bundle
        carver_defs[c] = d
    return {
        "biome_carvers.json": ("json", carvers),
        "biome_climate.json": ("json", climate),
        "biome_features.json": ("json", features),
        "spawners.json": ("json", {"biomes": spawners, "spawn_costs": spawn_costs}),
        "carvers.json": ("json", carver_defs),
    }


def build_all(jar_path, reports_dir=None):
    jar = Jar(jar_path)
    report = json.load(open(find_report(jar_path, reports_dir)))
    biome_order = overworld_biome_order(report)
    out = {}
    out.update(build_aggregates(jar))
    out.update(build_copies(jar))
    out.update(build_structure_biomes(jar))
    out.update(build_biome_projections(jar, biome_order))
    out["biome_parameters_overworld.json"] = ("json", build_biome_parameters(report))
    out["features_per_step.json"] = ("json", build_features_per_step(jar, biome_order))
    return out


# ------------------------------------------------------------------ modes

def write_tree(built, out_dir):
    for rel, (kind, data) in sorted(built.items()):
        p = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if kind == "raw":
            with open(p, "wb") as f:
                f.write(data)
        else:
            with open(p, "w", encoding="utf-8") as f:
                json.dump(data, f, separators=(",", ":"), ensure_ascii=False)
                f.write("\n")
    print("wrote %d files to %s" % (len(built), out_dir))


def group_of(rel):
    return rel.split("/", 1)[0] + "/" if "/" in rel else rel


def validate(built, bundled_dir):
    """Compare the in-memory rebuild against the bundled tree. JSON files are
    compared parsed (semantic), .nbt byte-identical. Returns exit code."""
    bundled = set()
    for dp, _, fs in os.walk(bundled_dir):
        for f in fs:
            bundled.add(os.path.relpath(os.path.join(dp, f), bundled_dir).replace(os.sep, "/"))

    results = {}   # group -> [pass, fail]
    failures = []

    def record(rel, ok, why=""):
        g = group_of(rel)
        results.setdefault(g, [0, 0])[0 if ok else 1] += 1
        if not ok:
            failures.append((rel, why))

    for rel, (kind, data) in sorted(built.items()):
        p = os.path.join(bundled_dir, rel)
        if rel not in bundled:
            record(rel, False, "missing from bundled tree")
            continue
        if rel.endswith(".nbt"):
            record(rel, open(p, "rb").read() == data, "nbt bytes differ")
        else:
            want = json.load(open(p, encoding="utf-8"))
            got = data if kind == "json" else json.loads(data.decode("utf-8"))
            if rel.startswith("structure_biomes/"):
                # order-insensitive: consumer is a HashSet (see ORDER CAVEAT)
                ok = (set(got["values"]) == set(want["values"])
                      and len(got["values"]) == len(set(got["values"])))
                record(rel, ok, "biome tag membership differs")
            else:
                record(rel, got == want, "JSON differs semantically")

    uncovered = sorted(bundled - set(built) - NOT_JAR_DERIVED)
    print("== extract_vanilla_data --validate ==")
    for g in sorted(results):
        ok, bad = results[g]
        print("  %-34s %4d/%-4d %s" % (g, ok, ok + bad, "PASS" if bad == 0 else "FAIL"))
    for rel, why in failures[:40]:
        print("  FAIL %s: %s" % (rel, why))
    if len(failures) > 40:
        print("  ... and %d more failures" % (len(failures) - 40))
    for rel in uncovered:
        print("  FAIL (uncovered bundled file): %s" % rel)
    skipped = sorted(bundled & NOT_JAR_DERIVED)
    for rel in skipped:
        print("  SKIP %s (not jar-derived; from scripts/piston_vanilla_capture.py)" % rel)
    total = sum(a + b for a, b in results.values())
    nfail = len(failures) + len(uncovered)
    print("total: %d built, %d pass, %d fail, %d skipped-by-design"
          % (total, total - len(failures), nfail, len(skipped)))
    return 1 if nfail else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--jar", default=DEFAULT_JAR, help="vanilla server jar (inner bundler jar)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output dir (default: bundled tree)")
    ap.add_argument("--reports-dir", default=None,
                    help="existing datagen reports/ dir (skips running the data generator)")
    ap.add_argument("--validate", action="store_true",
                    help="rebuild in memory and diff against --out instead of writing")
    args = ap.parse_args()
    built = build_all(args.jar, args.reports_dir)
    if args.validate:
        sys.exit(validate(built, args.out))
    write_tree(built, args.out)


if __name__ == "__main__":
    main()
