#!/usr/bin/env python3
"""Parity scorecard generator (MASTERPLAN §2 item 6).

Machine-generates docs/SCORECARD.md — the per-subsystem coverage/divergence
sheet — from the suites' own artifacts, so the sheet can never drift from
reality by hand-editing:

  - the newest full --playtest log in test-logs/ (677 checks, grouped by the
    `[scenario-tag]` each PASS/FAIL line carries since 2026-07-13),
  - the newest --selftest log (the server-less data-engine battery),
  - the newest worldgen region-diff log (scripts/worldgen_region_diff.py),
  - the committed differential fixtures (piston_reorder_cases.json),
  - docs/AUDIT.md (documented simplifications/gaps, counted per section).

Run from the repo root after a green full run:

    python3 scripts/parity_scorecard.py            # writes docs/SCORECARD.md
    python3 scripts/parity_scorecard.py --playtest-log test-logs/foo.log

A log qualifies as "full" only if its report footer says `0 failed` and no
section filter was used (the generator refuses partial runs — the scorecard
is a claim about the whole suite).
"""

import argparse
import json
import re
import sys
import time
from collections import Counter, OrderedDict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LOGS = REPO / "test-logs"

FOOTER = re.compile(r"^(\d+) passed, (\d+) failed$", re.M)


def newest(pattern_test):
    candidates = [p for p in sorted(LOGS.glob("*.log"),
                                    key=lambda p: p.stat().st_mtime, reverse=True)
                  if pattern_test(p.read_text(errors="replace"))]
    return candidates[0] if candidates else None


def is_full_green_playtest(text):
    m = FOOTER.search(text)
    return bool(m and m.group(2) == "0" and "[playtest] join" in text
                and int(m.group(1)) > 500)


def is_green_selftest(text):
    m = FOOTER.search(text)
    return bool(m and m.group(2) == "0" and text.startswith("indexed:"))


def is_final_regiondiff(text):
    return "=== FINAL RESULT ===" in text and "bit-exact match rate:" in text


def parse_checks(text):
    """PASS/FAIL lines -> (Counter by tag, total passed, total failed).
    Pre-2026-07-13 logs have no [tag]; those checks land in 'untagged'."""
    tagged = re.compile(r"^(PASS|FAIL) (?:\[([^\]]+)\] )?(.*)$")
    by_tag = OrderedDict()
    passed = failed = 0
    for line in text.splitlines():
        m = tagged.match(line)
        if not m:
            continue
        tag = m.group(2) or "untagged"
        ok = m.group(1) == "PASS"
        p, f = by_tag.get(tag, (0, 0))
        by_tag[tag] = (p + ok, f + (not ok))
        passed += ok
        failed += not ok
    return by_tag, passed, failed


def parse_regiondiff(text):
    def grab(rx, cast=str):
        m = re.search(rx, text)
        return cast(m.group(1)) if m else None
    return dict(
        rate=grab(r"bit-exact match rate: ([\d.]+)%", float),
        chunks=grab(r"chunks compared: (\d+)", int),
        blocks=grab(r"total blocks: (\d+)", int),
        seed=grab(r"region diff: seed (-?\d+)", int),
        top=re.findall(r"^(\d+)  (minecraft:\S+<-minecraft:\S+(?: \(props\))?)$",
                       text, re.M)[:10],
    )


def parse_audit():
    """docs/AUDIT.md '## section' -> number of top-level bullets (each is one
    documented simplification/gap)."""
    counts = OrderedDict()
    section = None
    for line in (REPO / "docs/AUDIT.md").read_text().splitlines():
        if line.startswith("## "):
            section = line[3:].strip()
            counts[section] = 0
        elif section and line.startswith("- "):
            counts[section] += 1
    return counts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--playtest-log", type=Path)
    ap.add_argument("--selftest-log", type=Path)
    ap.add_argument("--regiondiff-log", type=Path)
    ap.add_argument("--out", type=Path, default=REPO / "docs/SCORECARD.md")
    args = ap.parse_args()

    pt_log = args.playtest_log or newest(is_full_green_playtest)
    st_log = args.selftest_log or newest(is_green_selftest)
    # region diff: the biggest run wins over the newest, so a quick --radius 3
    # spot-check never displaces the full baseline in the scorecard
    rd_log = args.regiondiff_log
    if not rd_log:
        finals = [p for p in LOGS.glob("*.log")
                  if is_final_regiondiff(p.read_text(errors="replace"))]
        rd_log = max(finals, key=lambda p: (
            parse_regiondiff(p.read_text(errors="replace"))["chunks"] or 0,
            p.stat().st_mtime), default=None)
    if not pt_log or not st_log:
        sys.exit("no qualifying full-green playtest/selftest log in test-logs/ "
                 "(run the suites first, or pass --playtest-log/--selftest-log)")

    pt_tags, pt_pass, pt_fail = parse_checks(pt_log.read_text(errors="replace"))
    st_tags, st_pass, st_fail = parse_checks(st_log.read_text(errors="replace"))
    rd = parse_regiondiff(rd_log.read_text(errors="replace")) if rd_log else None
    audit = parse_audit()
    piston = json.loads(
        (REPO / "src/main/resources/vanilla/piston_reorder_cases.json").read_text())

    out = []
    w = out.append
    w("# Parity scorecard")
    w("")
    w(f"*Generated {time.strftime('%Y-%m-%d %H:%M')} by "
      f"`python3 scripts/parity_scorecard.py` — do not hand-edit. "
      f"Sources: `{pt_log.name}`, `{st_log.name}`"
      + (f", `{rd_log.name}`" if rd_log else "") + ".*")
    w("")
    w("## Headline")
    w("")
    w(f"- **PlayTest (headless full server, fake player): {pt_pass} passed, "
      f"{pt_fail} failed** across {len(pt_tags)} scenario groups")
    w(f"- **SelfTest (server-less data engine): {st_pass} passed, {st_fail} failed**")
    if rd:
        w(f"- **Worldgen region diff: {rd['rate']:.4f}% bit-exact** vs a real "
          f"vanilla 26.2 server — {rd['chunks']} chunks / {rd['blocks']:,} "
          f"blocks, seed {rd['seed']} (`scripts/worldgen_region_diff.py`, "
          f"full-state comparison incl. block properties)")
    w(f"- **Differential fixtures: {len(piston['cases'])} piston "
      f"extend/retract cases** captured from a real vanilla server, replayed "
      f"cell-by-cell every run (`scripts/piston_vanilla_capture.py`)")
    w(f"- Documented simplifications/gaps (docs/AUDIT.md): "
      f"{sum(audit.values())} across {len(audit)} areas")
    w("")
    w("## PlayTest coverage by scenario group")
    w("")
    w("| scenario group | checks | failed |")
    w("|---|---:|---:|")
    for tag, (p, f) in sorted(pt_tags.items(), key=lambda kv: -sum(kv[1])):
        w(f"| {tag} | {p + f} | {f} |")
    w("")
    w("## Known divergence (top region-diff mismatch classes, minecom<-vanilla)")
    w("")
    if rd and rd["top"]:
        w("| blocks | class |")
        w("|---:|---|")
        for count, cls in rd["top"]:
            w(f"| {int(count):,} | `{cls}` |")
    else:
        w("*(no region-diff log found)*")
    w("")
    w("## Documented simplifications by area (docs/AUDIT.md)")
    w("")
    w("| area | entries |")
    w("|---|---:|")
    for section, n in audit.items():
        w(f"| {section} | {n} |")
    w("")

    args.out.write_text("\n".join(out) + "\n")
    print(f"wrote {args.out} ({pt_pass}+{st_pass} checks, "
          f"{len(pt_tags)} playtest groups"
          + (f", region diff {rd['rate']:.4f}%" if rd else "") + ")")


if __name__ == "__main__":
    main()
