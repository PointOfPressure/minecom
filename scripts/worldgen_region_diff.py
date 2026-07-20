#!/usr/bin/env python3
"""Worldgen region-diff harness (MASTERPLAN §2 item 1 — the north-star number).

ONE command that answers "how bit-exact is minecom's overworld?":

    python3 scripts/worldgen_region_diff.py            # seed 20260708, 36x36 chunks
    python3 scripts/worldgen_region_diff.py --seed 123 --radius 4   # quick spot-check
    python3 scripts/worldgen_region_diff.py --dimension nether      # Nether diff
    python3 scripts/worldgen_region_diff.py --dimension nether_vibenilla  # adopted Nether

Dimension-parameterized (docs/TIER4-NETHER-DESIGN.md §4): --dimension overworld
(default) scans sections -4..19; --dimension nether drives the vanilla side with
`execute in minecraft:the_nether run forceload ...`, compares the per-dimension
region subtree world/dimensions/minecraft/the_nether/region on both sides, and
scans sections 0..7 (y 0..127). The two run as fully independent work dirs, so
the overworld baseline can never be regressed by nether work.

--dimension nether_vibenilla (the "ADOPT" measurement) reuses the `nether`
vanilla cache byte-for-byte (same seed/section-window/subtree) and only
regenerates the minecom side with the adopted vibenilla generator, so the
adopted number is measured against identical vanilla ground truth as the
NetherGen baseline.

It (1) generates the chunk square [center-radius, center+radius)^2 on a REAL
vanilla dedicated server (version = vanilla_oracle.MC_VERSION; driven over
stdin, forceload tile by tile,
polled via save-all flush until every chunk reaches minecraft:full), (2)
generates the same square with minecom's VanillaGen (`--genregions` mode of
target/minecom.jar, run in its own work dir), then (3) compares every block
of every section (-4..19) between the two Anvil trees and prints the match
percentage plus a per-block-type mismatch breakdown (`minecom<-vanilla`,
name-level; property-only mismatches show as `x<-x (props)`). Properties are
compared on the FULL block state: Minestom's AnvilLoader omits default-valued
properties, so palettes are completed from the vanilla data generator's
blocks.json (auto-generated once into the work dir) before comparing.

Baseline for the historical 99.38% number: seed 20260708, radius 18, center
(0,0) — 1,296 chunks / 127,401,984 blocks (the defaults). Output is teed to
test-logs/regiondiff_seed<seed>_r<radius>_<timestamp>.log.

Work dir: ~/minecom-region-diff/<seed>_r<radius>_<cx>x<cz>/ (disposable, NOT
inside the repo). The vanilla world is cached there — after a worldgen change,
re-running only regenerates the minecom side (~10 min on the dev laptop) and
re-compares. --fresh wipes both sides; --reuse-minecom skips the minecom
regen too (compare-only).

The vanilla side ticks briefly while forceloaded (gamerules pin down random
ticks / weather / mobs, but scheduled fluid ticks from generation do run), so
a handful of water/lava cells settle vs minecom's raw generated state; that
contamination is part of the baseline number and identical run-to-run.
"""

import argparse
import json
import shutil
import subprocess
import sys
import time
from collections import Counter
from pathlib import Path

from vanilla_oracle import (JAR, MC_VERSION, VANILLA_END_REGION_SUBDIR,
                            VANILLA_NETHER_REGION_SUBDIR,
                            VANILLA_REGION_SUBDIR, RegionReader, Server,
                            default_block_properties, prepare_workdir,
                            section_indices)

REPO = Path(__file__).resolve().parent.parent
TILE = 12                                  # chunks per forceload tile (144 < vanilla's 256 cap)

# Per-dimension harness config (docs/TIER4-NETHER-DESIGN.md §4). The overworld
# row reproduces the historical setup exactly — minecom writes world/region and
# the comparator scans sections -4..19 (y -64..319). The nether writes into the
# per-dimension subtree on BOTH sides and scans sections 0..7 (y 0..127): the
# nether noise_settings height is 128 and a bedrock roof caps content near y=127,
# so sections 8..15 are all-air and not compared.
# `vanilla_tag` selects which work dir holds the (expensive-to-build, cached)
# vanilla side: it defaults to the dimension's own name but can point at another
# dimension's cache. nether_vibenilla reuses the `nether` vanilla ground truth
# unchanged (same seed/section-window/subtree) and only regenerates the minecom
# side with the adopted generator — so the two nether numbers are measured
# against byte-identical vanilla, and --fresh on nether_vibenilla never touches
# the nether vanilla cache (docs/TIER4-NETHER-DESIGN.md "ADOPT").
DIMENSIONS = {
    "overworld": dict(vanilla_subdir=VANILLA_REGION_SUBDIR,
                      minecom_subdir="world/region",
                      mc_dimension="minecraft:overworld",
                      section_min=-4, section_max=19,
                      vanilla_tag="overworld"),
    "nether": dict(vanilla_subdir=VANILLA_NETHER_REGION_SUBDIR,
                   minecom_subdir=VANILLA_NETHER_REGION_SUBDIR,
                   mc_dimension="minecraft:the_nether",
                   section_min=0, section_max=7,
                   vanilla_tag="nether"),
    "nether_vibenilla": dict(vanilla_subdir=VANILLA_NETHER_REGION_SUBDIR,
                             minecom_subdir=VANILLA_NETHER_REGION_SUBDIR,
                             mc_dimension="minecraft:the_nether",
                             section_min=0, section_max=7,
                             vanilla_tag="nether"),
    # The End: same per-dimension subtree pattern as the Nether. End terrain
    # (noise_settings_end.json height 128, main island near y40..70, obsidian
    # spikes to ~y103) all lives in y 0..127 -> sections 0..7. The vanilla side
    # is captured with `execute in minecraft:the_end run forceload`.
    "end": dict(vanilla_subdir=VANILLA_END_REGION_SUBDIR,
                minecom_subdir=VANILLA_END_REGION_SUBDIR,
                mc_dimension="minecraft:the_end",
                section_min=0, section_max=7,
                vanilla_tag="end"),
}


class Tee:
    def __init__(self, path):
        self.f = open(path, "w")

    def __call__(self, msg):
        print(msg, flush=True)
        self.f.write(msg + "\n")
        self.f.flush()


# ---------------------------------------------------------------- generation

def gen_vanilla(work, seed, cx0, cx1, cz0, cz1, port, dim, log):
    # max-tick-time=-1: save-all flush of a freshly generated tile can block the
    # main thread >60s on an HDD, and the watchdog would crash the server.
    # sync-chunk-writes=false: no per-chunk fsync (crash-safety only, and the
    # world is disposable + resumable). fresh=False: vanilla gen is
    # deterministic per seed, so an interrupted run resumes where it stopped —
    # already-full chunks satisfy the tile poll instantly.
    prepare_workdir(work,
        "online-mode=false\ndifficulty=peaceful\n"
        f"level-seed={seed}\ngenerate-structures=true\n"
        f"server-port={port}\nview-distance=4\nsimulation-distance=4\n"
        "max-tick-time=-1\nsync-chunk-writes=false\n"
        "motd=region diff capture\n", fresh=False)
    srv = Server(work, xmx="2048M")
    log(f"vanilla: starting dedicated server (seed {seed}, dim {dim['mc_dimension']})...")
    srv.start()
    for rule in ("doMobSpawning", "doWeatherCycle", "doDaylightCycle",
                 "doFireTick", "doTraderSpawning", "doMobGriefing"):
        srv.cmd(f"gamerule {rule} false")
    srv.cmd("gamerule randomTickSpeed 0")
    srv.cmd("gamerule spawnChunkRadius 0")
    srv.barrier("setup")

    # forceload runs in the target dimension: bare in the overworld (byte-
    # identical to the historical run), `execute in <dim> run ...` otherwise.
    pre = ("" if dim["mc_dimension"] == "minecraft:overworld"
           else f"execute in {dim['mc_dimension']} run ")
    region_dir = work / dim["vanilla_subdir"]
    start = time.time()
    for tx in range(cx0, cx1, TILE):
        for tz in range(cz0, cz1, TILE):
            ex, ez = min(tx + TILE, cx1) - 1, min(tz + TILE, cz1) - 1
            tile = [(cx, cz) for cx in range(tx, ex + 1) for cz in range(tz, ez + 1)]
            srv.cmd(f"{pre}forceload add {tx * 16} {tz * 16} {ex * 16 + 15} {ez * 16 + 15}")
            deadline = time.time() + 900
            while True:
                srv.save_flush()
                reader = RegionReader(region_dir)
                full = sum(1 for c in tile
                           if (reader.status(*c) or "").endswith("full"))
                if full == len(tile):
                    break
                if time.time() > deadline:
                    raise RuntimeError(
                        f"tile ({tx},{tz}): only {full}/{len(tile)} chunks full after 900s")
                time.sleep(8)
            srv.cmd(f"{pre}forceload remove all")
            log(f"vanilla: tile ({tx},{tz})..({ex},{ez}) full "
                f"({len(tile)} chunks, {time.time() - start:.0f}s elapsed)")
    srv.stop()
    log(f"vanilla: done in {time.time() - start:.0f}s")


def gen_minecom(work, jar, seed, radius, ccx, ccz, dimension, log):
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    # run from a copy so a concurrent `mvn package` can't rewrite the jar
    # under a live JVM (other agents may be building in the same tree)
    jar = shutil.copy2(jar, work / "minecom.jar")
    log(f"minecom: java -jar {Path(jar).name} --genregions {seed} {radius} {ccx} {ccz} {dimension} ...")
    start = time.time()
    with open(work / "genregions.log", "w") as out:
        rc = subprocess.run(
            ["java", "-Xmx3G", "-jar", str(jar),
             "--genregions", str(seed), str(radius), str(ccx), str(ccz), dimension],
            cwd=work, stdout=out, stderr=subprocess.STDOUT).returncode
    if rc != 0:
        tail = (work / "genregions.log").read_text().splitlines()[-20:]
        raise RuntimeError("minecom --genregions failed:\n" + "\n".join(tail))
    log(f"minecom: done in {time.time() - start:.0f}s")


# ---------------------------------------------------------------- comparison

def canonize(interned, canon_names, defaults, palette):
    """Palette -> canon ids via a run-global intern table. Properties are
    compared on the FULL state: Minestom's AnvilLoader omits default-valued
    properties, so every entry is completed from the vanilla blocks report
    before interning (a real axis=x vs omitted-default-y still mismatches)."""
    ids = []
    for name, props in palette:
        full = defaults.get(name)
        if full:
            full = {**full, **props} if props else full
        else:
            full = props
        key = (name, tuple(sorted(full.items())) if full else ())
        cid = interned.get(key)
        if cid is None:
            cid = len(canon_names)
            interned[key] = cid
            canon_names.append("minecraft:" + name)
        ids.append(cid)
    return ids


def section_canon(interned, canon_names, defaults, sec, air_id):
    """One section as (uniform_id, None) or (None, list-of-4096-canon-ids)."""
    if sec is None:
        return air_id, None
    palette, data = sec
    ids = canonize(interned, canon_names, defaults, palette)
    indices = section_indices(palette, data)
    if indices is None:
        return ids[0], None
    if len(ids) == 1:
        return ids[0], None
    return None, [ids[i] for i in indices]


def compare(vanilla_region, minecom_region, chunks, top, defaults,
            section_min, section_max, log):
    blocks_per_chunk = (section_max - section_min + 1) * 4096
    interned, canon_names = {}, []
    air_id = canonize(interned, canon_names, defaults, [("air", {})])[0]
    vr = RegionReader(vanilla_region)
    mr = RegionReader(minecom_region)
    matched = 0
    compared_chunks = 0
    bad_chunks = 0
    classes = Counter()
    start = time.time()

    def clas(mid, vid):
        m, v = canon_names[mid], canon_names[vid]
        return f"{m}<-{v}" if m != v else f"{m}<-{v} (props)"

    for n, (cx, cz) in enumerate(chunks, 1):
        vstatus = vr.status(cx, cz) or ""
        msecs = mr.sections(cx, cz)
        if not vstatus.endswith("full") or not msecs:
            bad_chunks += 1
            log(f"WARN chunk ({cx},{cz}): vanilla status={vstatus!r}, "
                f"minecom sections={len(msecs)} — skipped")
            continue
        vsecs = vr.sections(cx, cz)
        compared_chunks += 1
        for sy in range(section_min, section_max + 1):
            vu, vlist = section_canon(interned, canon_names, defaults, vsecs.get(sy), air_id)
            mu, mlist = section_canon(interned, canon_names, defaults, msecs.get(sy), air_id)
            if vlist is None and mlist is None:
                if mu == vu:
                    matched += 4096
                else:
                    classes[clas(mu, vu)] += 4096
            elif vlist is None:
                eq = sum(1 for a in mlist if a == vu)
                matched += eq
                if eq < 4096:
                    classes.update(clas(a, vu) for a in mlist if a != vu)
            elif mlist is None:
                eq = sum(1 for b in vlist if b == mu)
                matched += eq
                if eq < 4096:
                    classes.update(clas(mu, b) for b in vlist if b != mu)
            elif mlist == vlist:
                matched += 4096
            else:
                mism = [(a, b) for a, b in zip(mlist, vlist) if a != b]
                matched += 4096 - len(mism)
                classes.update(clas(a, b) for a, b in mism)
        vr.forget(cx, cz)
        mr.forget(cx, cz)
        if n % 100 == 0:
            total = compared_chunks * blocks_per_chunk
            log(f"progress: {n} chunks compared, {int((time.time() - start) * 1000)}ms "
                f"elapsed, running match rate {100 * matched / total:.4f}%")

    total = compared_chunks * blocks_per_chunk
    log("=== FINAL RESULT ===")
    log(f"chunks compared: {compared_chunks}, chunks missing/not-full: {bad_chunks}")
    log(f"total blocks: {total}")
    log(f"matched: {matched}")
    rate = 100 * matched / total if total else 0.0
    log(f"bit-exact match rate: {rate:.6f}%")
    log(f"--- top {top} mismatch classes (minecom<-vanilla) ---")
    for cls, count in classes.most_common(top):
        log(f"{count}  {cls}")
    return rate, bad_chunks


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--seed", type=int, default=20260708)
    ap.add_argument("--radius", type=int, default=18,
                    help="chunks in each direction from center; 18 -> 36x36 = 1296 chunks")
    ap.add_argument("--center", type=int, nargs=2, default=[0, 0], metavar=("CX", "CZ"))
    ap.add_argument("--dimension", choices=sorted(DIMENSIONS), default="overworld",
                    help="which dimension to diff (docs/TIER4-NETHER-DESIGN.md §4)")
    ap.add_argument("--port", type=int, default=25598)
    ap.add_argument("--top", type=int, default=40, help="mismatch classes to print")
    ap.add_argument("--fresh", action="store_true", help="wipe cached vanilla + minecom worlds")
    ap.add_argument("--reuse-minecom", action="store_true",
                    help="skip minecom regen, compare existing output")
    ap.add_argument("--jar", type=Path, default=REPO / "target/minecom.jar")
    ap.add_argument("--work", type=Path, default=Path.home() / "minecom-region-diff")
    args = ap.parse_args()

    dim = DIMENSIONS[args.dimension]
    ccx, ccz = args.center
    cx0, cx1 = ccx - args.radius, ccx + args.radius
    cz0, cz1 = ccz - args.radius, ccz + args.radius
    chunks = [(cx, cz) for cx in range(cx0, cx1) for cz in range(cz0, cz1)]

    if not JAR.exists():
        sys.exit(f"missing vanilla server jar {JAR}")
    if not args.jar.exists():
        sys.exit(f"missing {args.jar} — build it first: mvn -q -DskipTests package")

    # Per-MC-version work dirs: the vanilla world cache is only valid for the
    # server version that generated it (the 26.1.2 cache lives in the
    # unsuffixed pre-26.2 dirs and is deliberately kept).
    # overworld dir name is unchanged (reuses the hours-to-build cached vanilla
    # side); the nether diff is a fully independent work dir via the suffix.
    def workdir(tag):
        suffix = "" if tag == "overworld" else f"_{tag}"
        return args.work / f"{args.seed}_r{args.radius}_{ccx}x{ccz}_{MC_VERSION}{suffix}"

    # The minecom side lives in this dimension's own dir; the vanilla side comes
    # from `vanilla_tag`'s dir (nether_vibenilla borrows the `nether` cache). For
    # the two nether dimensions vanilla_tag == "nether", so the vanilla cache is
    # shared and byte-identical between them.
    work = workdir(args.dimension)
    vanilla_dir = workdir(dim["vanilla_tag"]) / "vanilla"
    minecom_dir = work / "minecom"
    if args.fresh and work.exists():
        # --fresh only wipes THIS dimension's dir; when the vanilla cache is
        # borrowed (nether_vibenilla), the lender's vanilla side is never
        # touched (it lives under a different work dir).
        shutil.rmtree(work)
    work.mkdir(parents=True, exist_ok=True)
    vanilla_dir.parent.mkdir(parents=True, exist_ok=True)

    ts = time.strftime("%Y%m%d-%H%M%S")
    log_path = REPO / f"test-logs/regiondiff_{args.dimension}_seed{args.seed}_r{args.radius}_{ts}.log"
    log_path.parent.mkdir(exist_ok=True)
    log = Tee(log_path)
    log(f"region diff: dimension {args.dimension}, seed {args.seed}, "
        f"chunks ({cx0},{cz0})..({cx1 - 1},{cz1 - 1}) = {len(chunks)} chunks, work dir {work}")

    marker = vanilla_dir / ".complete"
    if marker.exists():
        log("vanilla: cached world found, skipping generation (--fresh to regen)")
    else:
        gen_vanilla(vanilla_dir, args.seed, cx0, cx1, cz0, cz1, args.port, dim, log)
        marker.write_text(json.dumps(dict(
            seed=args.seed, radius=args.radius, center=[ccx, ccz],
            dimension=args.dimension)) + "\n")

    if args.reuse_minecom and (minecom_dir / dim["minecom_subdir"]).exists():
        log("minecom: --reuse-minecom, comparing existing output")
    else:
        gen_minecom(minecom_dir, args.jar, args.seed, args.radius, ccx, ccz,
                    args.dimension, log)

    log("loading vanilla blocks report (default-state property table)...")
    # per-version like the world caches: the report enumerates every block's
    # default-state properties, and 26.2 added blocks (sulfur family)
    defaults = default_block_properties(args.work / f"blocks_report_{MC_VERSION}")
    rate, bad = compare(vanilla_dir / dim["vanilla_subdir"],
                        minecom_dir / dim["minecom_subdir"], chunks, args.top,
                        defaults, dim["section_min"], dim["section_max"], log)
    log(f"log: {log_path}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
