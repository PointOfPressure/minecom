#!/usr/bin/env python3
"""ONE command to run a P0 bench scenario (MASTERPLAN §4) against a chosen
server and emit one machine-readable JSON result.

    python3 scripts/bench/run_scenario.py chunkgen --server minecom
    python3 scripts/bench/run_scenario.py chunkgen --server vanilla
    python3 scripts/bench/run_scenario.py redstone --server minecom
    python3 scripts/bench/run_scenario.py spawn --server minecom      # currently fails loudly, see below

Scenarios are configs (scripts/bench/scenarios/*.toml), not script forks —
this file has one code path per scenario `mode` (live | chunkgen), not one
per scenario. See docs/BENCHMARKS.md for the full methodology.

Sanity gate (MASTERPLAN §4's "must fail loudly, not report 20 TPS on an
empty server" rule): a live scenario with bots > 0 requires
`minecom_players_online` to reach the target and hold — see
bench_common.wait_for_players. A run that can't verify its own population
does NOT report scenario numbers: it writes a result JSON with
status="failed" and a failure_reason, and exits nonzero. As of 2026-07-15
this means scenarios (a) spawn and (b) spread10k fail loudly EVERY run —
that is expected and correct until docs/HANDOFF.md's rust-mc-bot
connection-drop entry is resolved, not a bug in this script.

Live scenarios currently only support --server minecom: the vanilla/Paper
baseline path is wired for chunkgen (scenario e) only this session — a live
vanilla baseline needs either a console-driven players-online probe (no
/metrics on vanilla) or a BenchSetup-equivalent world-setup mechanism for
redstone/mobfarm, neither built yet (see MASTERPLAN §4 P0 checkbox notes).

Work dirs are disposable, outside the repo: ~/minecom-bench/<run-id>/.
Results land in scripts/bench/results/<run-id>.json (committed — this
project keeps its harness *and* its evidence in-tree, following
scripts/worldgen_region_diff.py's north-star-number precedent).
"""

import argparse
import re
import subprocess
import sys
import time
import tomllib
from datetime import datetime, timezone
from pathlib import Path

import bench_common as bc

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
    result = base_result(cfg, server, rid)
    bots_target = cfg.get("bots", 0)
    result.update(duration_seconds=cfg["duration_seconds"], bots_requested=bots_target)

    if server != "minecom":
        result.update(status="failed",
                       failure_reason=f"live scenarios only support --server minecom this session "
                                       f"(server={server} baseline not wired yet — see run_scenario.py docstring)")
        return result

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
            proc = subprocess.run(
                ["java", "-Xmx2048M", "-jar", str(bc.MINECOM_JAR), "--genregions", str(seed), str(radius)],
                cwd=workdir, capture_output=True, text=True)
            gen_log.write_text(proc.stdout + proc.stderr)
            if proc.returncode != 0 or "genregions done" not in proc.stdout:
                result.update(status="failed", failure_reason=f"pregen failed, see {gen_log}")
                return result

        srv = bc.launch_minecom(workdir, log_dir / "server.log", extra_env=extra_env,
                                 metrics_port=metrics_port, jfr_path=jfr_path)

        bots_connected = 0
        max_bots_before_sub20 = None
        if bots_target > 0:
            swarm = bc.BotSwarm("127.0.0.1:25565", log_dir)
            if cfg.get("ramp"):
                bots_connected, max_bots_before_sub20 = _run_ramp(cfg, swarm, metrics_port)
            else:
                swarm.add_batch(bots_target)
                bots_connected = bc.wait_for_players(
                    metrics_port, bots_target, cfg["join_hold_seconds"], cfg["join_timeout_seconds"])

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
