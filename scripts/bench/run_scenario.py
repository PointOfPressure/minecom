#!/usr/bin/env python3
"""ONE command to run a P0 bench scenario (MASTERPLAN §4) against a chosen
server and emit one machine-readable JSON result.

    python3 scripts/bench/run_scenario.py chunkgen --server minecom
    python3 scripts/bench/run_scenario.py chunkgen --server vanilla
    python3 scripts/bench/run_scenario.py redstone --server minecom
    python3 scripts/bench/run_scenario.py spawn --server minecom

Scenarios are configs (scripts/bench/scenarios/*.toml), not script forks —
this file has one code path per scenario `mode` (live | chunkgen), not one
per scenario. See docs/BENCHMARKS.md for the full methodology.

Sanity gate (MASTERPLAN §4's "must fail loudly, not report 20 TPS on an
empty server" rule): a live scenario with bots > 0 requires
`minecom_players_online` to reach the target and hold — see
bench_common.wait_for_players. A run that can't verify its own population
does NOT report scenario numbers: it writes a result JSON with
status="failed" and a failure_reason, and exits nonzero. (This gate
refused to emit fake numbers through the entire 2026-07-15/16 bot-bug
investigation — all five scenarios produce real numbers as of 2026-07-16;
see docs/BENCHMARKS.md.)

Live scenarios support --server vanilla/paper for all five scenarios
(spawn, spread10k, redstone, mobfarm, chunkgen) as of v0.25.0 —
run_live_vanilla drives vanilla/Paper via scripts/vanilla_oracle.Server: a
console `list` for players-online, `/tick query` for MSPT, and console
`/forceload`+`/setblock`+`/summon`+`/spreadplayers` for the world-setup
BenchSetup.java does directly against the Minestom API on the minecom side.
See docs/BENCHMARKS.md for the full baseline matrix and methodology notes
(including the deliberate divergences: mobfarm's flat platform instead of
real terrain height, spread10k's /spreadplayers minimum-separation vs
minecom's independent-uniform placement).

Work dirs are disposable, outside the repo: ~/minecom-bench/<run-id>/.
Results land in scripts/bench/results/<run-id>.json (committed — this
project keeps its harness *and* its evidence in-tree, following
scripts/worldgen_region_diff.py's north-star-number precedent).
"""

import argparse
import math
import random
import re
import shutil
import subprocess
import sys
import time
import tomllib
from datetime import datetime, timezone
from pathlib import Path

import bench_common as bc
import vanilla_oracle

SCENARIOS_DIR = Path(__file__).resolve().parent / "scenarios"
RESULTS_DIR = Path(__file__).resolve().parent / "results"
WORK_ROOT = Path.home() / "minecom-bench"


def load_scenario(name):
    path = SCENARIOS_DIR / f"{name}.toml"
    if not path.exists():
        raise SystemExit(f"no such scenario: {name} (looked for {path})")
    with open(path, "rb") as f:
        return tomllib.load(f)


def run_id(scenario, server):
    return f"{scenario}_{server}_{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"


def base_result(cfg, server, rid):
    return {
        "run_id": rid,
        "scenario": cfg["name"],
        "description": cfg.get("description"),
        "server": server,
        "commit": bc.git_commit(),
        "hardware": bc.hardware_fingerprint(),
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }


# ---------------------------------------------------------------- chunkgen

def run_chunkgen(cfg, server, rid):
    result = base_result(cfg, server, rid)
    seed, radius = cfg["seed"], cfg["radius"]
    total_chunks = (radius * 2) ** 2
    result.update(seed=seed, radius=radius, total_chunks=total_chunks)
    workdir = WORK_ROOT / rid
    workdir.mkdir(parents=True, exist_ok=True)

    if server == "minecom":
        log_path = workdir / "genregions.log"
        start = time.time()
        proc = subprocess.run(
            ["java", "-Xmx2048M", "-jar", str(bc.MINECOM_JAR), "--genregions", str(seed), str(radius)],
            cwd=workdir, capture_output=True, text=True)
        wall_elapsed = time.time() - start
        log_path.write_text(proc.stdout + proc.stderr)
        m = re.search(r"genregions done: (\d+) chunks, seed -?\d+, center \(-?\d+,-?\d+\), radius \d+, (\d+)ms",
                      proc.stdout + proc.stderr)
        if proc.returncode != 0 or not m:
            result.update(status="failed", failure_reason=f"--genregions did not complete cleanly, see {log_path}")
            return result
        chunks_done, elapsed_ms = int(m.group(1)), int(m.group(2))
        if chunks_done != total_chunks:
            result.update(status="failed",
                           failure_reason=f"expected {total_chunks} chunks, genregions reported {chunks_done}")
            return result
        result.update(status="ok", elapsed_ms=elapsed_ms, wall_elapsed_ms=int(wall_elapsed * 1000),
                       chunks_per_sec=chunks_done / (elapsed_ms / 1000))
        return result

    if server in ("vanilla", "paper"):
        import vanilla_oracle
        # max-tick-time=-1 + sync-chunk-writes=false + tiled forceload/poll/
        # remove: the exact pattern scripts/worldgen_region_diff.py already
        # proved at 1,296+ chunks. A naive "fire every forceload, one
        # save_flush at the end" (this file's first attempt) trips vanilla's
        # watchdog — 256 fresh chunks blocking the main tick thread on this
        # HDD is well past its 60s default hang threshold, and the server
        # self-crashes with a watchdog "Server Watchdog/ERROR" report.
        properties = (
            f"level-seed={seed}\nonline-mode=false\nspawn-protection=0\nview-distance=32\n"
            "max-tick-time=-1\nsync-chunk-writes=false\n"
        )
        work = vanilla_oracle.prepare_workdir(workdir / server, properties, fresh=True)
        launch_cmd = None
        if server == "paper":
            if not bc.PAPER_JAR.exists():
                result.update(status="failed", failure_reason=f"{bc.PAPER_JAR} missing")
                return result
            launch_cmd = ["java", "-Xmx2048M", "-jar", str(bc.PAPER_JAR), "--nogui"]
        srv = vanilla_oracle.Server(work, xmx="2048M", launch_cmd=launch_cmd)
        srv.start()
        try:
            for rule in ("doMobSpawning", "doWeatherCycle", "doDaylightCycle",
                         "doFireTick", "doTraderSpawning", "doMobGriefing"):
                srv.cmd(f"gamerule {rule} false")
            srv.cmd("gamerule randomTickSpeed 0")
            srv.barrier("setup")

            region_dir = work / vanilla_oracle.VANILLA_REGION_SUBDIR
            reader = vanilla_oracle.RegionReader(region_dir)
            tile_size = 12  # chunks/tile, < vanilla's 256-chunk forceload cap
            start = time.time()
            done = 0
            for tx in range(-radius, radius, tile_size):
                for tz in range(-radius, radius, tile_size):
                    ex, ez = min(tx + tile_size, radius) - 1, min(tz + tile_size, radius) - 1
                    tile = [(cx, cz) for cx in range(tx, ex + 1) for cz in range(tz, ez + 1)]
                    srv.cmd(f"forceload add {tx * 16} {tz * 16} {ex * 16 + 15} {ez * 16 + 15}")
                    deadline = time.time() + 900
                    while True:
                        srv.save_flush()
                        full = sum(1 for c in tile if (reader.status(*c) or "") == "minecraft:full")
                        if full == len(tile):
                            break
                        if time.time() > deadline:
                            result.update(status="failed",
                                           failure_reason=f"tile ({tx},{tz}): only {full}/{len(tile)} "
                                                           f"chunks full after 900s")
                            return result
                        time.sleep(8)  # matches worldgen_region_diff.py's proven interval —
                        # a tighter poll just means more full-world save_flush overhead on this HDD
                    srv.cmd("forceload remove all")
                    done += len(tile)
            elapsed = time.time() - start
        finally:
            srv.stop()
        if done != total_chunks:
            result.update(status="failed",
                           failure_reason=f"expected {total_chunks} chunks at minecraft:full, found {done}")
            return result
        result.update(status="ok", elapsed_ms=int(elapsed * 1000), chunks_per_sec=done / elapsed)
        return result

    result.update(status="failed", failure_reason=f"chunkgen not implemented for server={server}")
    return result


# ------------------------------------------------------------------- live

def run_live(cfg, server, rid, jfr=False):
    if server in ("vanilla", "paper"):
        return run_live_vanilla(cfg, server, rid)

    result = base_result(cfg, server, rid)
    bots_target = cfg.get("bots", 0)
    result.update(duration_seconds=cfg["duration_seconds"], bots_requested=bots_target)

    workdir = WORK_ROOT / rid
    log_dir = workdir / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    jfr_path = str(workdir / f"{rid}.jfr") if jfr else None
    metrics_port = 9225

    srv = None
    swarm = None
    try:
        extra_env = {"MINECOM_BENCH_SCENARIO": cfg["bench_scenario_env"]}
        extra_env.update(cfg.get("bench_extra_env", {}))

        if cfg.get("world") == "pregen":
            seed, radius = cfg.get("pregen_seed", 20260708), cfg["pregen_radius_chunks"]
            gen_log = log_dir / "pregen.log"
            # pregen worlds are pure functions of (jar-era worldgen, seed,
            # radius) — cache them; at ~0.46 chunks/sec on this laptop a
            # radius-14 world costs ~30 min once, unacceptable per run
            cache = WORK_ROOT / "pregen-cache" / f"seed{seed}_r{radius}"
            if not (cache / "world" / "seed.txt").exists():
                cache.mkdir(parents=True, exist_ok=True)
                proc = subprocess.run(
                    ["java", "-Xmx2048M", "-jar", str(bc.MINECOM_JAR), "--genregions", str(seed), str(radius)],
                    cwd=cache, capture_output=True, text=True)
                gen_log.write_text(proc.stdout + proc.stderr)
                if proc.returncode != 0 or "genregions done" not in proc.stdout:
                    shutil.rmtree(cache, ignore_errors=True)
                    result.update(status="failed", failure_reason=f"pregen failed, see {gen_log}")
                    return result
                # GenRegions takes its seed as a bare CLI arg — it never touches
                # world/seed.txt, so a subsequent normal boot's Bootstrap.worldSeed()
                # would pick a fresh RANDOM seed (and a random Bootstrap.findSpawn()
                # spawn point unrelated to what was just pregenerated) unless this
                # pins it to match. Written into the cache so every reuse gets it.
                (cache / "world" / "seed.txt").write_text(str(seed))
            else:
                gen_log.write_text(f"pregen cache hit: {cache}\n")
            shutil.copytree(cache / "world", workdir / "world", dirs_exist_ok=True)

        srv = bc.launch_minecom(workdir, log_dir / "server.log", extra_env=extra_env,
                                 metrics_port=metrics_port, jfr_path=jfr_path)

        # warm-world step (MASTERPLAN §4 P0 harness ergonomics, docs/HANDOFF.md
        # 2026-07-15 "Remaining"): a freshly-booted instance still has lazy
        # background work (lighting/JIT/first-GC) settling out even once its
        # world is pregenerated on disk — an idle minute here keeps that
        # transient off the join/ramp measurement instead of mixing "server
        # still warming up" into "bots can't join".
        settle = cfg.get("warm_settle_seconds", 0)
        if settle:
            time.sleep(settle)

        bots_connected = 0
        max_bots_before_sub20 = None
        if bots_target > 0:
            swarm = bc.BotSwarm("127.0.0.1:25565", log_dir)
            if cfg.get("ramp"):
                bots_connected, max_bots_before_sub20 = _run_ramp(cfg, swarm, metrics_port)
            else:
                # join pacing: batches of bot_join_batch (default: all at once)
                # with a gap between, same rationale as the ramp path's own
                # batching — a mass-connect burst is the cold-join stall this
                # laptop's chunk pipeline can't keep up with.
                batch_size = cfg.get("bot_join_batch") or bots_target
                gap = cfg.get("bot_join_batch_gap_seconds", 0)
                bots_connected = bc.join_paced(
                    swarm, bots_target, batch_size, gap, metrics_port,
                    cfg["join_hold_seconds"], cfg["join_timeout_seconds"])

        # clean measurement window: reset AFTER the population is verified stable
        bc.reset_metrics(metrics_port)
        time.sleep(cfg["duration_seconds"])

        final_metrics = bc.scrape_metrics(metrics_port)
        online_now = int(final_metrics.get("minecom_players_online", -1))
        if bots_target > 0 and online_now < bots_target:
            result.update(
                status="failed",
                failure_reason=f"players_online dropped to {online_now} during the run (target {bots_target}) "
                                f"— see docs/HANDOFF.md's rust-mc-bot escalation entry if this is the known "
                                f"keep-alive drop")
            return result

        tick_count = int(final_metrics.get("minecom_tick_total", 0))
        if tick_count == 0:
            result.update(status="failed",
                           failure_reason="0 ticks observed in the measurement window — server never ticked")
            return result

        io_bytes = bc.proc_io_bytes(srv.pid)
        result.update(
            status="ok",
            bots_connected=bots_connected,
            max_bots_before_sub20_tps=max_bots_before_sub20,
            tick_count=tick_count,
            mspt_p50=final_metrics.get('minecom_tick_mspt{quantile="0.5"}'),
            mspt_p95=final_metrics.get('minecom_tick_mspt{quantile="0.95"}'),
            mspt_p99=final_metrics.get('minecom_tick_mspt{quantile="0.99"}'),
            tps=final_metrics.get("minecom_tps"),
            mspt_source="minestom ServerTickMonitorEvent (true per-tick)",
            gc_collections_total=sum(v for k, v in final_metrics.items()
                                      if k.startswith("minecom_gc_collections_total")),
            gc_time_ms_total=sum(v for k, v in final_metrics.items()
                                  if k.startswith("minecom_gc_time_ms_total")),
            heap_used_bytes=final_metrics.get("minecom_heap_used_bytes"),
            packet_bytes_out_proxy=io_bytes,
            packet_bytes_out_source="proc/pid/io wchar delta proxy (coarse — see bench_common.proc_io_bytes)",
            jfr_path=jfr_path,
        )
        return result
    except RuntimeError as e:
        # bc.wait_for_players and friends raise RuntimeError for every "fail
        # loudly" condition (MASTERPLAN §4) — turn that into a written,
        # inspectable result instead of a bare traceback with no artifact.
        result.update(status="failed", failure_reason=str(e))
        return result
    finally:
        if swarm:
            swarm.stop()
        if srv:
            srv.stop()


def _run_ramp(cfg, swarm, metrics_port):
    step, interval = cfg["ramp_step"], cfg["ramp_interval_seconds"]
    floor, cap = cfg["ramp_tps_floor"], cfg["bots"]
    connected = 0
    max_before_sub20 = None
    while connected < cap:
        batch = min(step, cap - connected)
        swarm.add_batch(batch)
        target = connected + batch
        # a wait_for_players failure here IS the fail-loud path — propagate,
        # don't swallow it as "ramp stopped early"
        bc.wait_for_players(metrics_port, target, cfg["join_hold_seconds"], cfg["join_timeout_seconds"])
        connected = target
        time.sleep(interval)
        tps = bc.scrape_metrics(metrics_port).get("minecom_tps", 20.0)
        if tps < floor:
            max_before_sub20 = connected - batch
            break
    return connected, max_before_sub20


# ---------------------------------------------------------- live (vanilla/paper)

def _vanilla_players_online(srv, timeout=15):
    """Console players-online probe (vanilla/Paper have no /metrics) — same
    scan-from-a-marker technique as vanilla_oracle.Server.query_gametime."""
    with srv.cond:
        start_scanned = len(srv.lines)
    srv.cmd("list")
    deadline = time.time() + timeout
    with srv.cond:
        scanned = start_scanned
        while True:
            while scanned < len(srv.lines):
                line = srv.lines[scanned]
                scanned += 1
                m = re.search(r"There are (\d+) of a max of \d+ players online", line)
                if m:
                    return int(m.group(1))
            if srv.proc.poll() is not None:
                raise RuntimeError("server exited waiting for players-online probe")
            remaining = deadline - time.time()
            if remaining <= 0:
                raise RuntimeError("timeout waiting for /list response")
            srv.cond.wait(min(remaining, 1.0))


def _wait_for_players_vanilla(srv, target, hold_seconds, timeout):
    """Same hold-and-verify sanity gate as bench_common.wait_for_players
    (MASTERPLAN §4's "must fail loudly" rule), console-probed instead of
    /metrics-scraped."""
    deadline = time.time() + timeout
    held_since = None
    last = -1
    while time.time() < deadline:
        last = _vanilla_players_online(srv)
        if last >= target:
            held_since = held_since or time.time()
            if time.time() - held_since >= hold_seconds:
                return last
        else:
            held_since = None
        time.sleep(1)
    raise RuntimeError(
        f"bot swarm sanity check failed: players_online never reached {target} and held for "
        f"{hold_seconds}s within {timeout}s (last observed: {last})")


def _join_paced_vanilla(swarm, target, batch_size, gap_seconds, srv, hold_seconds, timeout):
    connected = 0
    while connected < target:
        batch = min(batch_size, target - connected)
        swarm.add_batch(batch)
        connected += batch
        if connected < target:
            time.sleep(gap_seconds)
    return _wait_for_players_vanilla(srv, target, hold_seconds, timeout)


def _tick_percentiles_vanilla(srv, timeout=15):
    """`/tick query` (vanilla 26.2 and Paper both support it) — the console-
    native equivalent of minecom's ServerTickMonitorEvent-backed /metrics MSPT
    quantiles, so no spark/JFR parsing rabbit hole is needed (MASTERPLAN §4 P0
    baseline note). Returns None if the command isn't recognized (older/odd
    builds) rather than failing the whole scenario over a bonus metric."""
    with srv.cond:
        start_scanned = len(srv.lines)
    srv.cmd("tick query")
    deadline = time.time() + timeout
    avg = p50 = p95 = p99 = None
    with srv.cond:
        scanned = start_scanned
        while time.time() < deadline:
            while scanned < len(srv.lines):
                line = srv.lines[scanned]
                scanned += 1
                m = re.search(r"Average time per tick: ([\d.]+)ms", line)
                if m:
                    avg = float(m.group(1))
                m = re.search(r"Percentiles: P50: ([\d.]+)ms P95: ([\d.]+)ms P99: ([\d.]+)ms", line)
                if m:
                    p50, p95, p99 = (float(x) for x in m.groups())
                    return {"avg": avg, "p50": p50, "p95": p95, "p99": p99}
                if "Unknown or incomplete command" in line:
                    return None
            srv.cond.wait(min(deadline - time.time(), 1.0))
    return None


def _forceload_square_vanilla(srv, radius_chunks):
    """Console equivalent of BenchSetup.forceloadSquare — see that method's
    class comment for why a bench swarm needs an explicit force-loaded margin
    that real organic player traffic gets for free. `/forceload add` takes a
    block-coordinate rectangle, inclusive on both corners.

    Tiled, NOT one-shot (2026-07-16, first spawn-vs-paper attempt): vanilla's
    own `net.minecraft.server.commands.ForceLoadCommand.changeForceLoad` calls
    `Level.getChunk` -> `ServerChunkCache.syncLoad` for EVERY chunk in the
    requested rectangle SYNCHRONOUSLY on the main tick thread before the
    command returns — a radius-6 (13x13=169 chunk) one-shot square blocks long
    enough (worse under any machine contention) to trip Paper's watchdog
    (spigot.yml `timeout-time`, independent of vanilla's own `max-tick-time`
    property — that's why the same one-shot call survived against plain
    vanilla but killed+restarted the Paper run: vanilla's watchdog IS disabled
    by `max-tick-time=-1` in server.properties, Paper's separate one isn't).
    Same tiled forceload+barrier pattern `run_chunkgen`'s vanilla/paper path
    already proved at 1,296+ chunks, just smaller tiles (this is a warm-margin
    step for bots, not a throughput measurement, so there's no reason to push
    tile size — small tiles keep every single command's synchronous window
    short regardless of what's sharing the CPU) and no minecraft:full polling
    (unlike chunkgen, nothing here is measuring generation throughput)."""
    tile = 3  # chunks/tile — keeps each `forceload add` call's synchronous
    # blocking window short even on a heavily-loaded machine (this whole
    # script is meant to be launched as `nice -n 15 python3 run_scenario.py
    # ...` when the owner is using the laptop interactively — niceness is
    # inherited by every java/rust-mc-bot child process this script spawns,
    # see docs/BENCHMARKS.md's methodology note — but low CPU priority only
    # helps get SCHEDULED sooner; the work inside one command is still
    # synchronous either way, so small tiles matter regardless).
    for tx in range(-radius_chunks, radius_chunks + 1, tile):
        ex = min(tx + tile - 1, radius_chunks)
        for tz in range(-radius_chunks, radius_chunks + 1, tile):
            ez = min(tz + tile - 1, radius_chunks)
            srv.cmd(f"forceload add {tx * 16} {tz * 16} {ex * 16 + 15} {ez * 16 + 15}")
            srv.barrier(f"forceload-tile-{tx}_{tz}")


def _stamp_redstone_vanilla(srv, count):
    """Console port of BenchSetup.stampRedstoneClocks — same grid shape/
    coordinates (RIG_Y=100, spacing=3) so vanilla/Paper/minecom stamp the
    identical rig for a fair comparison. Vanilla's /setblock triggers the same
    neighbor-update-on-placement any survival build does, so the double-
    observer pairs self-start with no extra "kick" needed."""
    RIG_Y = 100
    per_row = math.ceil(math.sqrt(count))
    spacing = 3
    half = per_row * spacing // 2
    min_c, max_c = (-half - 8) >> 4, (half + 8) >> 4
    srv.cmd(f"forceload add {min_c * 16} {min_c * 16} {(max_c + 1) * 16 - 1} {(max_c + 1) * 16 - 1}")
    srv.barrier("redstone-forceload")
    placed = 0
    for row in range(per_row):
        if placed >= count:
            break
        for col in range(per_row):
            if placed >= count:
                break
            x, z = -half + row * spacing, -half + col * spacing
            srv.cmd(f"setblock {x} {RIG_Y} {z} minecraft:observer[facing=east]")
            srv.cmd(f"setblock {x + 1} {RIG_Y} {z} minecraft:observer[facing=west]")
            placed += 1
    srv.barrier("redstone-stamped")


def _stamp_mobfarm_vanilla(srv, count, kind):
    """Console port of BenchSetup.stampMobFarm — one deliberate divergence:
    Java's version places the pen at real terrain surface height via
    VanillaGen.topBlock, which a console has no cheap equivalent for (no
    height-query command), so this builds its own flat floor on a fixed-Y
    platform instead of relying on natural terrain. Documented divergence,
    not a silent one (CLAUDE.md rule 4) — see docs/BENCHMARKS.md."""
    cx, cz, y = 32, 0, 200
    size = math.ceil(math.sqrt(count)) + 4
    half = size // 2
    x0, x1, z0, z1 = cx - half, cx + half, cz - half, cz + half
    min_c, max_c = (min(x0, z0)) >> 4, (max(x1, z1)) >> 4
    srv.cmd(f"forceload add {min_c * 16} {min_c * 16} {(max_c + 1) * 16 - 1} {(max_c + 1) * 16 - 1}")
    srv.barrier("mobfarm-forceload")
    srv.cmd(f"fill {x0} {y - 1} {z0} {x1} {y - 1} {z1} minecraft:cobblestone")
    for yy in range(y, y + 4):
        srv.cmd(f"fill {x0} {yy} {z0} {x1} {yy} {z0} minecraft:cobblestone")
        srv.cmd(f"fill {x0} {yy} {z1} {x1} {yy} {z1} minecraft:cobblestone")
        srv.cmd(f"fill {x0} {yy} {z0} {x0} {yy} {z1} minecraft:cobblestone")
        srv.cmd(f"fill {x1} {yy} {z0} {x1} {yy} {z1} minecraft:cobblestone")
    srv.cmd(f"fill {x0} {y + 4} {z0} {x1} {y + 4} {z1} minecraft:cobblestone")
    srv.barrier("mobfarm-walled")
    rng = random.Random(20260708)
    for _ in range(count):
        x = rng.randint(x0 + 1, x1 - 1)
        z = rng.randint(z0 + 1, z1 - 1)
        srv.cmd(f"summon minecraft:{kind} {x} {y} {z}")
    srv.barrier("mobfarm-summoned")


def run_live_vanilla(cfg, server, rid):
    """Vanilla/Paper baseline for the live scenarios (MASTERPLAN §4 P0). Reuses
    scripts/vanilla_oracle.Server (the console-driven fixture factory every
    other differential harness in this repo already uses) instead of
    bench_common's minecom-specific /metrics + LogTailProcess plumbing — a
    real console changes what's cheap: players-online and MSPT both come from
    console commands (_vanilla_players_online, _tick_percentiles_vanilla)
    instead of an HTTP scrape, and (c)/(d)'s world setup is stamped via
    /setblock/summon instead of BenchSetup.java's direct Minestom API calls."""
    result = base_result(cfg, server, rid)
    bots_target = cfg.get("bots", 0)
    result.update(duration_seconds=cfg["duration_seconds"], bots_requested=bots_target)

    workdir = WORK_ROOT / rid
    log_dir = workdir / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)

    seed = cfg.get("pregen_seed", 20260708)
    properties = (
        f"level-seed={seed}\nonline-mode=false\nspawn-protection=0\n"
        # allow-flight: the bots hover above terrain instead of simulating
        # gravity/collision — without this vanilla/Paper fly-kick them after
        # 80 ticks (2026-07-16 discriminator run: "Flying is not enabled")
        f"allow-flight=true\n"
        f"view-distance={bc.BENCH_CHUNK_VIEW_DISTANCE}\nmax-tick-time=-1\nsync-chunk-writes=false\n"
    )
    work = vanilla_oracle.prepare_workdir(workdir / server, properties, fresh=True)
    launch_cmd = None
    if server == "paper":
        if not bc.PAPER_JAR.exists():
            result.update(status="failed", failure_reason=f"{bc.PAPER_JAR} missing")
            return result
        # Paper's watchdog is spigot.yml timeout-time, INDEPENDENT of
        # server.properties max-tick-time — without this it thread-dumps at
        # 10s and kills the server at 60s during HDD-bound world setup
        # (first spawn-vs-paper attempt died exactly this way)
        (work / "spigot.yml").write_text(
            "settings:\n  timeout-time: -1\n  restart-on-crash: false\n")
        launch_cmd = ["java", "-Xmx2048M", "-jar", str(bc.PAPER_JAR), "--nogui"]

    srv = vanilla_oracle.Server(work, xmx="2048M", launch_cmd=launch_cmd)
    swarm = None
    try:
        srv.start()
        for rule in ("doMobSpawning", "doWeatherCycle", "doDaylightCycle",
                     "doFireTick", "doTraderSpawning", "doMobGriefing"):
            srv.cmd(f"gamerule {rule} false")
        srv.barrier("setup")

        scenario = cfg["name"]
        spread_radius_blocks = None
        if scenario == "spawn":
            # MINECOM_BENCH_FORCELOAD_RADIUS (6), NOT pregen_radius_chunks (14) —
            # the latter is minecom-only margin against its own worldgen-on-
            # carrier-pool read-stall (docs/HANDOFF.md 2026-07-16); vanilla/Paper
            # don't share that bug, and forceloading 14's 29x29=841 chunks live
            # at vanilla's own ~3.5 chunks/sec (chunkgen baseline) would blow the
            # warm_settle_seconds window for no baseline-fidelity reason.
            radius = int(cfg["bench_extra_env"]["MINECOM_BENCH_FORCELOAD_RADIUS"])
            _forceload_square_vanilla(srv, radius)
            srv.barrier("spawn-forceload")
        elif scenario == "redstone":
            _stamp_redstone_vanilla(srv, int(cfg["bench_extra_env"]["MINECOM_BENCH_REDSTONE_COUNT"]))
        elif scenario == "mobfarm":
            _stamp_mobfarm_vanilla(srv, int(cfg["bench_extra_env"]["MINECOM_BENCH_MOB_COUNT"]),
                                    cfg["bench_extra_env"].get("MINECOM_BENCH_MOB_KIND", "zombie"))
        elif scenario == "spread10k":
            radius = int(cfg["bench_extra_env"]["MINECOM_BENCH_SPREAD_RADIUS"])
            _forceload_square_vanilla(srv, radius)
            spread_radius_blocks = radius * 16
            srv.barrier("spread-forceload")

        settle = cfg.get("warm_settle_seconds", 0)
        if settle:
            time.sleep(settle)

        bots_connected = 0
        if bots_target > 0:
            swarm = bc.BotSwarm("127.0.0.1:25565", log_dir)
            batch_size = cfg.get("bot_join_batch") or bots_target
            gap = cfg.get("bot_join_batch_gap_seconds", 0)
            bots_connected = _join_paced_vanilla(
                swarm, bots_target, batch_size, gap, srv,
                cfg["join_hold_seconds"], cfg["join_timeout_seconds"])

            if scenario == "spread10k":
                # Console equivalent of BenchSetup.registerSpread's per-player
                # PlayerSpawnEvent teleport: /spreadplayers scatters the whole
                # swarm across the forceloaded square AND resolves each
                # column's surface height itself (motion-blocking heightmap),
                # so no separate topBlock query is needed. Documented
                # divergence, not silent (CLAUDE.md rule 4, see
                # docs/BENCHMARKS.md): spreadplayers enforces a minimum
                # separation between players, where minecom's registerSpread
                # picks each column independently and uniformly (columns CAN
                # land close together there but never here).
                srv.cmd(f"spreadplayers 0 0 8 {spread_radius_blocks} false @a")
                srv.barrier("spread-teleported")
                # rust-mc-bot's process_teleport re-homes bot.anchor_{x,y,z}
                # on every position-sync packet, not just the first ("later
                # syncs (scenario spread-teleports) re-home the anchor too" —
                # scripts/bench/rust-mc-bot/src/states/play.rs) — this settle
                # lets every bot receive and process that sync before the
                # measurement window starts, so the window doesn't open
                # mid-flight from spawn to the new column.
                time.sleep(5)

        gt_before = srv.query_gametime()
        t0 = time.time()
        time.sleep(cfg["duration_seconds"])
        gt_after = srv.query_gametime()
        elapsed = time.time() - t0
        tps = min(20.0, (gt_after - gt_before) / elapsed)

        online_now = _vanilla_players_online(srv)
        if bots_target > 0 and online_now < bots_target:
            result.update(status="failed",
                           failure_reason=f"players_online dropped to {online_now} during the run "
                                           f"(target {bots_target})")
            return result

        tick = _tick_percentiles_vanilla(srv)
        result.update(
            status="ok",
            bots_connected=bots_connected,
            tps=tps,
            tps_source="query_gametime delta / wall-clock elapsed, capped at 20 (no /metrics on this server)",
            mspt_p50=tick["p50"] if tick else None,
            mspt_p95=tick["p95"] if tick else None,
            mspt_p99=tick["p99"] if tick else None,
            mspt_avg=tick["avg"] if tick else None,
            mspt_source="/tick query console command" if tick else "unavailable (/tick query not recognized)",
        )
        return result
    except RuntimeError as e:
        result.update(status="failed", failure_reason=str(e))
        return result
    finally:
        if swarm:
            swarm.stop()
        if srv.proc is not None and srv.proc.poll() is None:
            srv.stop()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario")
    ap.add_argument("--server", choices=["minecom", "vanilla", "paper"], default="minecom")
    ap.add_argument("--jfr", action="store_true", help="capture a JFR recording alongside the run (live scenarios)")
    args = ap.parse_args()

    cfg = load_scenario(args.scenario)
    rid = run_id(cfg["name"], args.server)
    result_path = RESULTS_DIR / f"{rid}.json"

    if cfg["mode"] == "chunkgen":
        result = run_chunkgen(cfg, args.server, rid)
    elif cfg["mode"] == "live":
        result = run_live(cfg, args.server, rid, jfr=args.jfr)
    else:
        raise SystemExit(f"unknown scenario mode: {cfg['mode']}")

    bc.write_result(result_path, result)
    if result.get("status") != "ok":
        print(f"SCENARIO FAILED: {result.get('failure_reason')}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
