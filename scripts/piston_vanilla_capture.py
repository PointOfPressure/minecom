#!/usr/bin/env python3
"""Piston differential-test fixture capture (HANDOFF: reorder-collision entry).

Drives a REAL vanilla 26.1.2 dedicated server (~/versions/26.1.2/server-26.1.2.jar)
through a battery of slime/honey piston contraptions and records, for each case,
the exact final block layout after extension and after retraction. The output
JSON (src/main/resources/vanilla/piston_reorder_cases.json) is the committed
fixture that PlayTest.scenarioPistonDifferential replays through minecom's own
Pistons engine, comparing every cell.

Case families:
  - 12 hand-designed mutations of the known reorder-at-collision rig (the
    wrap-around slime/honey arrangement whose late branch line walks into an
    earlier-claimed cell) - these are the cases that exercise
    reorderListAtCollision itself;
  - randomized structured rows (parallel sticky lines + perpendicular bridges,
    the collision-prone shape) and uniform random fills;
  - over-push-limit / immovable cases that must leave the piston unextended.

All placements are RECORDED in the JSON (the fixture is self-contained; the
RNG seed is not replayed anywhere), so Java never mirrors Python RNG.

Run from the repo root:  python3 scripts/piston_vanilla_capture.py
Work dir: ~/minecom-vanilla-capture (disposable; NOT inside the repo).
The worldgen region-diff harness was never committed and had to be treated as
lost tooling - this script exists in-tree so the piston fixture never suffers
the same fate. Reproducing the fixture is one command.
"""

import json
import random
import shutil
import struct
import subprocess
import sys
import threading
import time
import zlib
from pathlib import Path

JAR = Path.home() / "versions/26.1.2/server-26.1.2.jar"
LIBS = Path.home() / "libraries"  # ~/versions + ~/libraries are a Mojang bundler unpack
WORK = Path.home() / "minecom-vanilla-capture"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/vanilla/piston_reorder_cases.json"
SEED = 20260713

# capture box, relative to the piston (facing east = +x)
BOX = dict(x0=-1, x1=15, y0=-4, y1=6, z0=-8, z1=8)
# random-structure generation zone (kept clear of the piston column and its trigger)
GEN = dict(x0=1, x1=9, y0=-2, y1=2, z0=-3, z1=3)

GRID_COLS, GRID_SPACING, BASE_X, BASE_Y, BASE_Z = 8, 32, 16, 100, 16

MOVABLE = ["slime_block", "honey_block", "stone"]


def rel(dx, dy, dz, key):
    return [dx, dy, dz, key]


def known_rig(slime="slime_block", honey="honey_block", mid="stone",
              zdir=1, use_y=False, extra=None, drop=None):
    """The hand-traced reorder rig: A/C slime rows, D honey line split by `mid`,
    E honey row; E1's branch adds D1 whose forward walk collides with `mid`."""
    def cell(dx, row, key):
        if use_y:
            return rel(dx, row * zdir, 0, key)
        return rel(dx, 0, row * zdir, key)
    blocks = []
    for x in (1, 2, 3):
        blocks.append(cell(x, 0, slime))   # A row
        blocks.append(cell(x, 1, slime))   # C row
        blocks.append(cell(x, 3, honey))   # E row
    blocks.append(cell(1, 2, honey))       # D1
    blocks.append(cell(2, 2, mid))         # M
    blocks.append(cell(3, 2, honey))       # D3
    if drop:
        blocks = [b for b in blocks if tuple(b[:3]) not in drop]
    if extra:
        blocks.extend(extra)
    return blocks


def hand_cases():
    cases = []
    cases.append(("rig-baseline", known_rig()))
    cases.append(("rig-mirror-z", known_rig(zdir=-1)))
    cases.append(("rig-materials-swapped",
                  known_rig(slime="honey_block", honey="slime_block")))
    cases.append(("rig-vertical", known_rig(use_y=True)))
    cases.append(("rig-mid-honey", known_rig(mid="honey_block")))
    cases.append(("rig-mid-slime", known_rig(mid="slime_block")))
    cases.append(("rig-no-d3", known_rig(drop={(3, 0, 2)})))
    cases.append(("rig-overlimit-a4",
                  known_rig(extra=[rel(4, 0, 0, "slime_block")])))
    cases.append(("rig-blocked-obsidian",
                  known_rig(extra=[rel(4, 0, 0, "obsidian")])))
    cases.append(("rig-overlimit-e4",
                  known_rig(extra=[rel(4, 0, 3, "honey_block")])))
    cases.append(("rig-e-gap", known_rig(drop={(2, 0, 3)})))
    two_layer = known_rig() + [[dx, dy + 1, dz, k] for dx, dy, dz, k in known_rig()]
    cases.append(("rig-overlimit-two-layer", two_layer))
    return cases


def random_cases(rng, structured, uniform):
    cases = []
    for i in range(structured):
        blocks, seen = [], set()
        rows = rng.sample(range(GEN["z0"], GEN["z1"] + 1), rng.randint(2, 4))
        length = rng.randint(3, 5)
        for z in rows:
            mat = rng.choice(["slime_block", "honey_block"])
            for x in range(1, 1 + length):
                if rng.random() < 0.85 and (x, 0, z) not in seen:
                    blocks.append(rel(x, 0, z, mat))
                    seen.add((x, 0, z))
        # perpendicular bridges between rows (the wrap-around ingredient)
        for _ in range(rng.randint(2, 5)):
            x = rng.randint(1, length)
            z = rng.randint(GEN["z0"], GEN["z1"])
            if (x, 0, z) not in seen:
                blocks.append(rel(x, 0, z, rng.choice(MOVABLE)))
                seen.add((x, 0, z))
        cases.append((f"rows-{i}", blocks))
    for i in range(uniform):
        blocks = []
        density = rng.uniform(0.25, 0.6)
        for x in range(1, rng.randint(4, 7)):
            for z in range(-2, 3):
                for y in range(0, rng.randint(1, 3)):
                    if rng.random() < density:
                        key = rng.choices(
                            MOVABLE + ["obsidian"], weights=[40, 40, 15, 5])[0]
                        blocks.append(rel(x, y, z, key))
        cases.append((f"random-{i}", blocks))
    return cases


def build_cases():
    rng = random.Random(SEED)
    named = hand_cases() + random_cases(rng, structured=16, uniform=12)
    cases = []
    for idx, (name, blocks) in enumerate(named):
        ox = BASE_X + (idx % GRID_COLS) * GRID_SPACING
        oz = BASE_Z + (idx // GRID_COLS) * GRID_SPACING
        cases.append(dict(id=idx, name=name, origin=[ox, BASE_Y, oz], blocks=blocks))
    return cases


# ---------------------------------------------------------------- server driving

class Server:
    """Dedicated-server driver. A pump thread drains stdout continuously —
    command feedback for hundreds of setblocks would otherwise fill the pipe
    buffer and deadlock the server mid-batch."""

    def __init__(self):
        self.proc = None
        self.lines = []
        self.scanned = 0
        self.cond = threading.Condition()

    def start(self):
        classpath = ":".join([str(j) for j in sorted(LIBS.rglob("*.jar"))] + [str(JAR)])
        self.proc = subprocess.Popen(
            ["java", "-Xmx1024M", "-cp", classpath, "net.minecraft.server.Main", "--nogui"],
            cwd=WORK, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
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
        return data[pos], pos + 1
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
    """Reads block states from an Anvil region directory (1.18+ format)."""

    def __init__(self, region_dir):
        self.region_dir = Path(region_dir)
        self.chunks = {}

    def chunk(self, cx, cz):
        key = (cx, cz)
        if key not in self.chunks:
            self.chunks[key] = self._load(cx, cz)
        return self.chunks[key]

    def _load(self, cx, cz):
        rf = self.region_dir / f"r.{cx >> 5}.{cz >> 5}.mca"
        data = rf.read_bytes()
        idx = 4 * ((cx & 31) + (cz & 31) * 32)
        loc = struct.unpack_from(">I", data, idx)[0]
        offset, sectors = (loc >> 8) * 4096, loc & 0xFF
        if offset == 0:
            return {}
        length, comp = struct.unpack_from(">IB", data, offset)
        raw = data[offset + 5:offset + 4 + length]
        nbt = zlib.decompress(raw) if comp == 2 else raw
        _, root, _ = nbt_parse(nbt, 0)
        sections = {}
        for sec in root.get("sections", []):
            bs = sec.get("block_states")
            if bs is None:
                continue
            palette = [(e["Name"].removeprefix("minecraft:"),
                        e.get("Properties", {})) for e in bs["palette"]]
            sections[sec["Y"]] = (palette, bs.get("data"))
        return sections

    def block(self, x, y, z):
        sections = self.chunk(x >> 4, z >> 4)
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


def snapshot(reader, case):
    ox, oy, oz = case["origin"]
    cells = []
    for dx in range(BOX["x0"], BOX["x1"] + 1):
        for dy in range(BOX["y0"], BOX["y1"] + 1):
            for dz in range(BOX["z0"], BOX["z1"] + 1):
                key, props = reader.block(ox + dx, oy + dy, oz + dz)
                if key == "air":
                    continue
                cell = [dx, dy, dz, key]
                if key in ("sticky_piston", "piston"):
                    cell.append({"extended": props.get("extended", "false")})
                cells.append(cell)
    return cells


# ---------------------------------------------------------------- main flow

def main():
    if not JAR.exists():
        sys.exit(f"missing {JAR}")
    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir()
    (WORK / "eula.txt").write_text("eula=true\n")
    (WORK / "server.properties").write_text(
        "level-type=minecraft\\:flat\n"
        "online-mode=false\ndifficulty=peaceful\ngenerate-structures=false\n"
        "server-port=25597\nview-distance=4\nsimulation-distance=4\n"
        "motd=piston capture\n")

    cases = build_cases()
    min_cx = (BASE_X - 16) // 16
    max_cx = (BASE_X + (GRID_COLS - 1) * GRID_SPACING + 24) // 16
    rows = (len(cases) + GRID_COLS - 1) // GRID_COLS
    min_cz = (BASE_Z - 16) // 16
    max_cz = (BASE_Z + (rows - 1) * GRID_SPACING + 24) // 16

    print(f"{len(cases)} cases; forceload chunk box "
          f"({min_cx},{min_cz})..({max_cx},{max_cz})")

    srv = Server()
    srv.start()
    for rule in ("doMobSpawning", "doWeatherCycle", "doDaylightCycle",
                 "doFireTick", "doTraderSpawning"):
        srv.cmd(f"gamerule {rule} false")
    srv.cmd("gamerule randomTickSpeed 0")
    srv.cmd(f"forceload add {min_cx * 16} {min_cz * 16} {max_cx * 16} {max_cz * 16}")
    srv.barrier("setup")

    for case in cases:
        ox, oy, oz = case["origin"]
        srv.cmd(f"setblock {ox} {oy} {oz} minecraft:sticky_piston[facing=east]")
        for dx, dy, dz, key in case["blocks"]:
            srv.cmd(f"setblock {ox + dx} {oy + dy} {oz + dz} minecraft:{key}")
    srv.barrier("built")
    time.sleep(2)

    for case in cases:  # trigger: direct power from below, no QC in play
        ox, oy, oz = case["origin"]
        srv.cmd(f"setblock {ox} {oy - 1} {oz} minecraft:redstone_block")
    srv.barrier("triggered")
    time.sleep(4)  # extension is ~3gt; leave wide margin
    srv.stop()

    reader = RegionReader(WORK / "world/dimensions/minecraft/overworld/region")  # 26.x layout
    for case in cases:
        case["extended"] = snapshot(reader, case)
    print("extended snapshots captured")

    srv = Server()
    srv.start()
    for case in cases:
        ox, oy, oz = case["origin"]
        srv.cmd(f"setblock {ox} {oy - 1} {oz} minecraft:air")
    srv.barrier("untriggered")
    time.sleep(4)
    srv.stop()

    reader = RegionReader(WORK / "world/dimensions/minecraft/overworld/region")  # 26.x layout
    for case in cases:
        case["retracted"] = snapshot(reader, case)
    print("retracted snapshots captured")

    OUT.write_text(json.dumps(
        dict(source="vanilla 26.1.2 dedicated server, scripts/piston_vanilla_capture.py",
             seed=SEED, box=BOX, cases=cases), indent=0))
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {len(cases)} cases)")


if __name__ == "__main__":
    main()
