"""Shared plumbing for the P0 benchmark harness (MASTERPLAN §4).

Server launchers, the /metrics scraper, a coarse packet-bytes-out proxy, and
the bot swarm driver — imported by run_scenario.py, not run directly.

minecom has no console (see docs/HANDOFF.md's 2026-07-15 rust-mc-bot
escalation entry — that's about the bot swarm, not this) so it's driven via
its own /metrics endpoint (bench/Metrics.java); vanilla/Paper have a real
console, reused from scripts/vanilla_oracle.Server (its `query_gametime()`
is the cross-server TPS probe for servers with no /metrics of their own).
"""

import json
import os
import platform
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BENCH_DIR = Path(__file__).resolve().parent
MINECOM_JAR = REPO_ROOT / "target" / "minecom.jar"
RUST_BOT = BENCH_DIR / "rust-mc-bot" / "target" / "release" / "rust-mc-bot"
# PaperMC 26.2 build 60 (fill.papermc.io), downloaded 2026-07-15 for the P0
# baseline — self-contained jar, not part of this repo (like ~/mc-26.2).
PAPER_JAR = Path.home() / "paper-26.2" / "paper-26.2-60.jar"

sys.path.insert(0, str(REPO_ROOT / "scripts"))
import vanilla_oracle  # noqa: E402 (needs sys.path set first)


def git_commit():
    return subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
                           capture_output=True, text=True, check=True).stdout.strip()


def hardware_fingerprint():
    """MASTERPLAN §11.1: the laptop is the reproducible low-end datapoint, not
    the headline — every result JSON carries this so nobody mistakes one for
    the other. A Threadripper run (owner has one, not reachable from this
    sandbox) gets the same fingerprint shape when it happens."""
    cpu_model = None
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu_model = line.split(":", 1)[1].strip()
                break
    except FileNotFoundError:
        pass
    mem_kb = None
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal"):
                mem_kb = int(line.split()[1])
                break
    except FileNotFoundError:
        pass
    return {
        "cpu_model": cpu_model,
        "logical_cpus": os.cpu_count(),
        "mem_total_mb": (mem_kb // 1024) if mem_kb else None,
        "os": platform.platform(),
        "hostname": platform.node(),
        "java_version": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
        if subprocess.run(["java", "-version"], capture_output=True, text=True).returncode == 0 else None,
    }


def scrape_metrics(port, timeout=5):
    """GET /metrics (bench/Metrics.java), parse Prometheus text exposition
    into {metric_with_labels: float}. Minimal parser — no histogram bucket
    math needed since Metrics.java already emits pre-computed quantiles."""
    url = f"http://127.0.0.1:{port}/metrics"
    with urllib.request.urlopen(url, timeout=timeout) as r:
        text = r.read().decode()
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, value = line.rsplit(" ", 1)
        out[name] = float(value)
    return out


def reset_metrics(port, timeout=5):
    urllib.request.urlopen(f"http://127.0.0.1:{port}/metrics/reset", timeout=timeout).read()


def metrics_reachable(port, timeout=2):
    try:
        scrape_metrics(port, timeout=timeout)
        return True
    except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
        return False


def proc_io_bytes(pid):
    """Coarse packet-bytes-out proxy (MASTERPLAN §4 P0 "packet bytes out"):
    write-syscall byte count from /proc/<pid>/io. A real per-connection
    packet-level counter needs Netty-layer instrumentation, which is P1's
    per-system tick pipeline territory (MASTERPLAN §4 P1 item 1), not P0's
    coarse pass — this is the honest OS-level stand-in, and world saves
    (the other source of write() calls) only happen at shutdown, outside
    the measurement window."""
    try:
        for line in Path(f"/proc/{pid}/io").read_text().splitlines():
            if line.startswith("wchar:"):
                return int(line.split()[1])
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return None
    return None


class LogTailProcess:
    """A subprocess whose stdout+stderr is redirected to a log file, with a
    line-scanning wait_for() (same shape as vanilla_oracle.Server, kept
    separate because minecom has no stdin console to drive)."""

    def __init__(self, cmd, cwd, log_path, env=None):
        self.cmd = cmd
        self.cwd = Path(cwd)
        self.log_path = Path(log_path)
        self.env = env
        self.proc = None
        self._lines = []
        self._cond = threading.Condition()

    def start(self):
        self.cwd.mkdir(parents=True, exist_ok=True)
        self._log_fh = open(self.log_path, "w")
        self.proc = subprocess.Popen(self.cmd, cwd=self.cwd, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True, bufsize=1, env=self.env)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.proc.stdout:
            self._log_fh.write(line)
            self._log_fh.flush()
            with self._cond:
                self._lines.append(line)
                self._cond.notify_all()
        with self._cond:
            self._cond.notify_all()

    def wait_for(self, needle, timeout):
        deadline = time.time() + timeout
        with self._cond:
            scanned = 0
            while True:
                while scanned < len(self._lines):
                    line = self._lines[scanned]
                    scanned += 1
                    if needle in line:
                        return line
                if self.proc.poll() is not None:
                    raise RuntimeError(f"process exited before {needle!r} appeared — see {self.log_path}")
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise RuntimeError(f"timeout waiting for {needle!r} — see {self.log_path}")
                self._cond.wait(min(remaining, 1.0))

    def stop(self, timeout=60):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=10)
        self._log_fh.close()

    @property
    def pid(self):
        return self.proc.pid


def launch_minecom(workdir, log_path, extra_env=None, metrics_port=9225, game_port=25565, xmx="2048M", jfr_path=None):
    """target/minecom.jar, offline mode by default (no online-mode flag needed).
    Bench scenario setup is entirely env-gated (bench/BenchSetup.java) so a
    plain launch with no MINECOM_BENCH_* vars behaves like production."""
    if not MINECOM_JAR.exists():
        raise RuntimeError(f"{MINECOM_JAR} missing — run mvn -q package -DskipTests first")
    env = os.environ.copy()
    env["MINECOM_METRICS_PORT"] = str(metrics_port)
    if extra_env:
        env.update(extra_env)
    jvm_flags = [f"-Xmx{xmx}"]
    if jfr_path:
        jvm_flags.append(f"-XX:StartFlightRecording=filename={jfr_path},settings=profile")
    proc = LogTailProcess(["java", *jvm_flags, "-jar", str(MINECOM_JAR)], workdir, log_path, env=env)
    proc.start()
    proc.wait_for("Minecom started on", timeout=120)
    # the server log line lands before Metrics.register finishes binding —
    # poll the endpoint itself rather than trusting a fixed sleep
    deadline = time.time() + 30
    while not metrics_reachable(metrics_port):
        if time.time() > deadline:
            raise RuntimeError(f"minecom started but /metrics never came up on :{metrics_port}")
        time.sleep(0.5)
    return proc


class BotSwarm:
    """rust-mc-bot batches against one server (scripts/bench/rust-mc-bot,
    see its VENDOR.md). Each batch is a separate OS process; name_offset
    (vendored addition) keeps their Bot_N usernames from colliding so
    batches add up instead of kicking each other."""

    def __init__(self, addr, log_dir):
        if not RUST_BOT.exists():
            raise RuntimeError(f"{RUST_BOT} missing — cargo build --release in scripts/bench/rust-mc-bot first")
        self.addr = addr
        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.procs = []
        self._next_offset = 0

    def add_batch(self, count, threads=1):
        offset = self._next_offset
        self._next_offset += count
        log_path = self.log_dir / f"bots_offset{offset}.log"
        with open(log_path, "w") as f:
            proc = subprocess.Popen([str(RUST_BOT), self.addr, str(count), str(threads), str(offset)],
                                     stdout=f, stderr=subprocess.STDOUT)
        self.procs.append(proc)
        return offset

    def stop(self):
        for p in self.procs:
            if p.poll() is None:
                p.terminate()
        deadline = time.time() + 10
        for p in self.procs:
            remaining = max(0.1, deadline - time.time())
            try:
                p.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                p.kill()


def wait_for_players(metrics_port, target, hold_seconds, timeout):
    """The harness's own sanity gate (MASTERPLAN §4 rule: 'a bot swarm that
    connects 0 bots must fail loudly, not report 20 TPS on an empty
    server'). Requires players_online to reach `target` AND hold there for
    `hold_seconds` before the scenario is trusted — raises RuntimeError
    (caller treats as a hard failure, never a silent partial result) if it
    doesn't. See docs/HANDOFF.md's 2026-07-15 entry: this is currently
    EXPECTED to fail for any bot-driven scenario until that's fixed."""
    deadline = time.time() + timeout
    held_since = None
    last = -1
    while time.time() < deadline:
        try:
            m = scrape_metrics(metrics_port, timeout=3)
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
            time.sleep(1)
            continue
        online = int(m.get("minecom_players_online", -1))
        last = online
        if online >= target:
            held_since = held_since or time.time()
            if time.time() - held_since >= hold_seconds:
                return online
        else:
            held_since = None
        time.sleep(1)
    raise RuntimeError(
        f"bot swarm sanity check failed: players_online never reached {target} and held for "
        f"{hold_seconds}s within {timeout}s (last observed: {last}). Not reporting scenario "
        f"numbers from an unverified server population — see docs/HANDOFF.md's rust-mc-bot "
        f"escalation entry (2026-07-15) if this is the known keep-alive drop.")


def write_result(path, result):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"wrote {path}")
