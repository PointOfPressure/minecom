#!/usr/bin/env python3
"""Shared vanilla-as-oracle plumbing (MASTERPLAN §2 item 2 — the fixture factory).

Every differential harness follows the same recipe: drive the REAL vanilla
26.2 dedicated server (~/mc-26.2, a Mojang bundler unpack with its own
libraries/ root, launched via -cp ... net.minecraft.server.Main), let it produce
ground truth, then read that truth back out of its Anvil region files with a
zero-dependency NBT reader. This module is that recipe, factored out of
scripts/piston_vanilla_capture.py (the first committed harness) so the next
differential test is an import away:

    from vanilla_oracle import Server, RegionReader, prepare_workdir

Consumers in-tree:
  - scripts/piston_vanilla_capture.py  (piston reorder fixture)
  - scripts/worldgen_region_diff.py    (the 99.x% worldgen north star)

Vanilla region files live at <workdir>/world/dimensions/minecraft/overworld/
region (26.x layout); minecom/Minestom writes <workdir>/world/region.
"""

import json
import struct
import subprocess
import threading
import time
import shutil
import zlib
from pathlib import Path

# 26.2 bundler unpack keeps its own libraries/ root — do NOT merge into
# ~/libraries (that tree belongs to the 26.1.2 unpack at ~/versions).
MC_VERSION = "26.2"
JAR = Path.home() / "mc-26.2/versions/26.2/server-26.2.jar"
LIBS = Path.home() / "mc-26.2/libraries"

VANILLA_REGION_SUBDIR = "world/dimensions/minecraft/overworld/region"


def prepare_workdir(work, properties, fresh=True):
    """Disposable server dir (NOT inside the repo) with eula + server.properties.
    `properties` is the full server.properties text."""
    work = Path(work)
    if fresh and work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True, exist_ok=True)
    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text(properties)
    return work


def _classpath(jar=JAR, libs=LIBS):
    return ":".join([str(j) for j in sorted(Path(libs).rglob("*.jar"))] + [str(jar)])


def default_block_properties(cache_dir):
    """{block-name-without-minecraft: -> default-state properties dict}, from the
    vanilla data generator's blocks.json (java net.minecraft.data.Main --reports,
    cached in cache_dir). This is the authority for property values a sparse
    writer (Minestom's AnvilLoader omits default-valued properties) left out."""
    cache_dir = Path(cache_dir)
    path = cache_dir / "reports/blocks.json"
    if not path.exists():
        cache_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["java", "-cp", _classpath(), "net.minecraft.data.Main",
             "--reports", "--output", str(cache_dir)],
            cwd=cache_dir, check=True,
            stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    defaults = {}
    for name, block in json.loads(path.read_text()).items():
        for state in block.get("states", []):
            if state.get("default"):
                defaults[name.removeprefix("minecraft:")] = state.get("properties", {})
    return defaults


# ---------------------------------------------------------------- server driving

class Server:
    """Dedicated-server driver. A pump thread drains stdout continuously —
    command feedback for hundreds of setblocks would otherwise fill the pipe
    buffer and deadlock the server mid-batch."""

    def __init__(self, workdir, xmx="1024M", jar=JAR, libs=LIBS):
        self.workdir = Path(workdir)
        self.xmx = xmx
        self.jar = Path(jar)
        self.libs = Path(libs)
        self.proc = None
        self.lines = []
        self.scanned = 0
        self.cond = threading.Condition()

    def start(self):
        self.proc = subprocess.Popen(
            ["java", f"-Xmx{self.xmx}", "-cp", _classpath(self.jar, self.libs),
             "net.minecraft.server.Main", "--nogui"],
            cwd=self.workdir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._pump, daemon=True).start()
        self.wait_for("Done (", timeout=300)

    def _pump(self):
        for line in self.proc.stdout:
            with self.cond:
                self.lines.append(line)
                self.cond.notify_all()
        with self.cond:
            self.cond.notify_all()  # EOF

    def wait_for(self, needle, timeout):
        deadline = time.time() + timeout
        with self.cond:
            while True:
                while self.scanned < len(self.lines):
                    line = self.lines[self.scanned]
                    self.scanned += 1
                    if needle in line:
                        return line
                if self.proc.poll() is not None:
                    raise RuntimeError("server exited:\n" + "".join(self.lines[-40:]))
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise RuntimeError(f"timeout waiting for {needle!r}")
                self.cond.wait(min(remaining, 1.0))

    def cmd(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def barrier(self, tag):
        self.cmd(f"say SYNC-{tag}")
        self.wait_for(f"SYNC-{tag}", timeout=120)

    def save_flush(self):
        """Synchronous save-all flush; returns once the save is on disk."""
        self.cmd("save-all flush")
        self.wait_for("Saved the game", timeout=300)

    def stop(self):
        self.cmd("save-all flush")
        self.barrier("presave")
        self.cmd("stop")
        self.proc.wait(timeout=120)


# ---------------------------------------------------------------- anvil reading

def nbt_parse(data, pos):
    tag = data[pos]
    pos += 1
    if tag == 0:
        return None, None, pos
    nlen = struct.unpack_from(">H", data, pos)[0]
    name = data[pos + 2:pos + 2 + nlen].decode()
    payload, pos = nbt_payload(data, pos + 2 + nlen, tag)
    return name, payload, pos


def nbt_payload(data, pos, tag):
    if tag == 1:
        return struct.unpack_from(">b", data, pos)[0], pos + 1  # signed: section Y is -4..19
    if tag == 2:
        return struct.unpack_from(">h", data, pos)[0], pos + 2
    if tag == 3:
        return struct.unpack_from(">i", data, pos)[0], pos + 4
    if tag == 4:
        return struct.unpack_from(">q", data, pos)[0], pos + 8
    if tag == 5:
        return struct.unpack_from(">f", data, pos)[0], pos + 4
    if tag == 6:
        return struct.unpack_from(">d", data, pos)[0], pos + 8
    if tag == 7:
        n = struct.unpack_from(">i", data, pos)[0]
        return None, pos + 4 + n
    if tag == 8:
        n = struct.unpack_from(">H", data, pos)[0]
        return data[pos + 2:pos + 2 + n].decode(), pos + 2 + n
    if tag == 9:
        etype = data[pos]
        n = struct.unpack_from(">i", data, pos + 1)[0]
        pos += 5
        items = []
        for _ in range(n):
            v, pos = nbt_payload(data, pos, etype)
            items.append(v)
        return items, pos
    if tag == 10:
        d = {}
        while True:
            name, v, pos = nbt_parse(data, pos)
            if name is None:
                return d, pos
            d[name] = v
    if tag == 11:
        n = struct.unpack_from(">i", data, pos)[0]
        return None, pos + 4 + n * 4
    if tag == 12:
        n = struct.unpack_from(">i", data, pos)[0]
        vals = struct.unpack_from(f">{n}q", data, pos + 4)
        return list(vals), pos + 4 + n * 8
    raise ValueError(f"tag {tag}")


class RegionReader:
    """Reads chunk NBT / block states from an Anvil region directory (1.18+
    format). Point it at a snapshot on disk; it caches per chunk, so create a
    fresh reader after the files change (e.g. after another save-all flush)."""

    def __init__(self, region_dir):
        self.region_dir = Path(region_dir)
        self._roots = {}
        self._sections = {}
        self._files = {}

    def forget(self, cx, cz):
        """Drop a chunk's parsed NBT (bulk sweeps call this after consuming a
        chunk so a 1000+-chunk pass doesn't hold every root in memory)."""
        self._roots.pop((cx, cz), None)
        self._sections.pop((cx, cz), None)

    def root(self, cx, cz):
        """Raw chunk NBT root, or None if the chunk was never written."""
        key = (cx, cz)
        if key not in self._roots:
            self._roots[key] = self._load_root(cx, cz)
        return self._roots[key]

    def _file(self, rx, rz):
        key = (rx, rz)
        if key not in self._files:
            rf = self.region_dir / f"r.{rx}.{rz}.mca"
            self._files[key] = rf.read_bytes() if rf.exists() else None
        return self._files[key]

    def _load_root(self, cx, cz):
        data = self._file(cx >> 5, cz >> 5)
        if data is None:
            return None
        idx = 4 * ((cx & 31) + (cz & 31) * 32)
        if idx + 4 > len(data):
            return None
        loc = struct.unpack_from(">I", data, idx)[0]
        offset = (loc >> 8) * 4096
        if offset == 0:
            return None
        length, comp = struct.unpack_from(">IB", data, offset)
        raw = data[offset + 5:offset + 4 + length]
        nbt = zlib.decompress(raw) if comp == 2 else raw
        _, root, _ = nbt_parse(nbt, 0)
        return root

    def status(self, cx, cz):
        """Vanilla generation status, e.g. 'minecraft:full' (None if absent —
        Minestom-written chunks carry a status but proto-vanilla layers matter)."""
        root = self.root(cx, cz)
        return None if root is None else root.get("Status")

    def sections(self, cx, cz):
        """{sectionY: (palette, data)} with palette entries normalized to
        (name-without-minecraft:, properties-dict). {} if the chunk is absent."""
        key = (cx, cz)
        if key not in self._sections:
            root = self.root(cx, cz)
            sections = {}
            for sec in (root or {}).get("sections", []):
                bs = sec.get("block_states")
                if bs is None:
                    continue
                palette = [(e["Name"].removeprefix("minecraft:"),
                            e.get("Properties", {})) for e in bs["palette"]]
                sections[sec["Y"]] = (palette, bs.get("data"))
            self._sections[key] = sections
        return self._sections[key]

    def block(self, x, y, z):
        sections = self.sections(x >> 4, z >> 4)
        sec = sections.get(y >> 4)
        if sec is None:
            return ("air", {})
        palette, data = sec
        if data is None or len(palette) == 1:
            return palette[0]
        bits = max(4, (len(palette) - 1).bit_length())
        per_long = 64 // bits
        index = (y & 15) * 256 + (z & 15) * 16 + (x & 15)
        word = data[index // per_long] & 0xFFFFFFFFFFFFFFFF
        pid = (word >> (bits * (index % per_long))) & ((1 << bits) - 1)
        return palette[pid]


def section_indices(palette, data):
    """Decode a section's packed data longs into 4096 palette indices, or None
    for a uniform section (single palette entry / no data)."""
    if data is None or len(palette) == 1:
        return None
    bits = max(4, (len(palette) - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for word in data:
        word &= 0xFFFFFFFFFFFFFFFF
        for _ in range(per_long):
            out.append(word & mask)
            word >>= bits
    del out[4096:]
    return out
