#!/usr/bin/env python3
"""Regenerate src/main/resources/vanilla/block_map_colors.json.

Source of truth: vanilla-src/net/minecraft/world/level/block/Blocks.java
(decompiled from ~/mc-26.2/versions/26.2/server-26.2.jar per CLAUDE.md rule 7 —
re-decompile before regenerating if the jar has moved on). Mojang's data
generator "reports" don't include block map colors (it's a client-rendering
property, not part of the blockstate report), so this walks the actual
registration call `public static final Block NAME = register(BlockItemIds.X,
[factory,] Properties.of()....mapColor(MapColor.COLOR)...)` and records
NAME.lowercase() -> the MapColor enum constant name, for every block that
sets one explicitly. Blocks with no `mapColor(...)` call inherit vanilla's
default (MapColor.NONE, id 0) and are simply absent from the output — callers
should treat a missing key as NONE.

The Java field name (e.g. `GRASS_BLOCK`) lowercases to the actual block
registry id (`grass_block`) for every vanilla block — this is the same
identifier Minestom uses in `block.key().value()`, matching the pattern
already used throughout this codebase (e.g. blocks/Conduits.java).

Run with --validate to also assert every emitted MapColor name exists in the
current MapColor.java decompile (catches drift on a version bump).
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BLOCKS_SRC = REPO / "vanilla-src/net/minecraft/world/level/block/Blocks.java"
MAPCOLOR_SRC = REPO / "vanilla-src/net/minecraft/world/level/material/MapColor.java"
DYECOLOR_SRC = REPO / "vanilla-src/net/minecraft/world/item/DyeColor.java"
OUT = REPO / "src/main/resources/vanilla/block_map_colors.json"

REGISTER_RE = re.compile(r"public static final Block (\w+) = register\(")
MAPCOLOR_CALL_RE = re.compile(r"mapColor\(MapColor\.(\w+)\)")
LOG_PROPERTIES_RE = re.compile(r"logProperties\(\s*MapColor\.(\w+)")
DYECOLOR_ENUM_RE = re.compile(
    r'^\s*(\w+)\((\d+), "(\w+)", -?\d+, MapColor\.(\w+), MapColor\.(\w+),', re.M
)

# `ColorCollection.registerBlocks` families: Blocks.java registers 16 blocks per
# family (one per DyeColor) via a loop keyed off `color.getMapColor()` or
# `color.getTerracottaColor()` rather than a literal `mapColor(MapColor.X)` call
# per block — see e.g. Blocks.java's WOOL/CONCRETE/DYED_TERRACOTTA fields. The
# resulting block id is always `{dyeColorName}_{suffix}` (stable vanilla naming
# since 1.13, no ambiguity to resolve from source). "field" selects which of
# DyeColor's two per-color MapColor slots (map vs. terracotta) that family uses
# — confirmed per-family against Blocks.java: WOOL/CARPET/STAINED_GLASS/
# STAINED_GLASS_PANE/CONCRETE/CONCRETE_POWDER/GLAZED_TERRACOTTA/BANNER/
# WALL_BANNER/CANDLE use `color.getMapColor()`; DYED_TERRACOTTA (block id
# suffix "terracotta") uses `color.getTerracottaColor()`. Stained-glass panes
# and banners set no `mapColor` at all in Properties (glass panes are
# transparent, banners aren't full blocks) so they're excluded here to match
# real vanilla's NONE default.
DYE_FAMILIES = [
    ("wool", "map"),
    ("carpet", "map"),
    ("stained_glass", "map"),
    ("concrete", "map"),
    ("concrete_powder", "map"),
    ("glazed_terracotta", "map"),
    ("candle", "map"),
    ("terracotta", "terracotta"),  # DYED_TERRACOTTA -> "{color}_terracotta"
]


def extract_balanced(text: str, open_paren_idx: int) -> str:
    """Return the substring from open_paren_idx's '(' to its matching ')'."""
    depth = 0
    for i in range(open_paren_idx, len(text)):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren_idx : i + 1]
    raise ValueError("unbalanced parens starting at %d" % open_paren_idx)


def parse_dye_colors() -> list:
    """[(colorName, mapColorConst, terracottaColorConst), ...] from DyeColor.java."""
    text = DYECOLOR_SRC.read_text()
    out = []
    for m in DYECOLOR_ENUM_RE.finditer(text):
        _const, _id, name, map_color, terracotta_color = m.groups()
        out.append((name, map_color, terracotta_color))
    return out


def build() -> dict:
    text = BLOCKS_SRC.read_text()
    result = {}
    for m in REGISTER_RE.finditer(text):
        name = m.group(1)
        open_idx = m.end() - 1  # the '(' of register(
        call = extract_balanced(text, open_idx)
        color_m = MAPCOLOR_CALL_RE.search(call)
        if color_m:
            result[name.lower()] = color_m.group(1)
        else:
            log_m = LOG_PROPERTIES_RE.search(call)
            if log_m:
                # logProperties(topColor, sideColor, soundType) — the map only
                # ever samples one MapColor per block, so use the top color
                # (matches real vanilla: e.g. every oak log tile on the map is
                # the same wood-brown regardless of the log's placed axis).
                result[name.lower()] = log_m.group(1)

    dye_colors = parse_dye_colors()
    if len(dye_colors) != 16:
        raise ValueError(f"expected 16 DyeColor entries, got {len(dye_colors)}")
    for suffix, field in DYE_FAMILIES:
        for name, map_color, terracotta_color in dye_colors:
            block_id = f"{name}_{suffix}"
            result[block_id] = terracotta_color if field == "terracotta" else map_color

    return dict(sorted(result.items()))


def known_mapcolor_names() -> set:
    text = MAPCOLOR_SRC.read_text()
    return set(re.findall(r"public static final MapColor (\w+) = new MapColor", text))


def main():
    validate = "--validate" in sys.argv
    result = build()
    if not result:
        print("ERROR: extracted zero entries — Blocks.java parsing broke", file=sys.stderr)
        sys.exit(1)
    if validate:
        known = known_mapcolor_names()
        bad = {v for v in result.values() if v not in known}
        if bad:
            print(f"ERROR: unknown MapColor names referenced: {bad}", file=sys.stderr)
            sys.exit(1)
        print(f"validate OK: {len(result)} blocks, all MapColor names resolve "
              f"({len(known)} known constants)")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")
    print(f"wrote {OUT} ({len(result)} block -> MapColor entries)")


if __name__ == "__main__":
    main()
