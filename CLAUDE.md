# Standing instructions for agents working in this repo

1. **Before writing any code, follow `docs/CONVENTIONS.md`.** It codifies
   the canonical patterns; the deviations it lists in §11 are unification
   targets, not precedents to copy.
2. **`vanilla-src/` is decompiled Mojang reference ONLY.** Never commit it,
   never copy code verbatim from it into `src/` — port behavior, cite the
   source class in the class Javadoc. `world/`, `world_backup_customgen/`,
   `logs/`, `test-logs/`, `target/` also stay uncommitted.
3. **Escalation log: `docs/HANDOFF.md`** — if a task needs a stronger model,
   log it there (newest first) instead of attempting it half-correctly.
   Mark entries done, don't delete them. Known-gaps ledger: `docs/AUDIT.md`.
   Business/licensing/roadmap decisions: `~/minecom-private/STRATEGY.md`
   (kept outside the public repo).
4. **Verification is the product.** Every behavior change ships with
   `check(desc, cond)` coverage in `SelfTest` (`--selftest`) and/or
   `PlayTest` (`--playtest`, port via `MINECOM_TEST_PORT`). No JUnit.
   Deliberate simplifications are stated in source + AUDIT.md, never
   silently faked. Test logs go to `test-logs/`, not `/tmp`.
5. **Other models may be editing this tree concurrently.** A transient
   compile error can be another agent mid-save — wait and retry before
   "fixing" a half-written file, and never run `mvn compile` while a
   verify run is executing (shared `target/classes`).
6. **Every change is auto-backed-up before it lands — don't hand-roll your
   own.** `.claude/settings.json` runs `scripts/backup-changed-file.sh` as a
   PreToolUse hook on every Edit/Write/NotebookEdit, snapshotting the file's
   **pre-change** contents (the restore point) to
   `~/minecom-backups/YYYY-MM-DD/HHMMSS__<flattened-path>`. Backups live
   OUTSIDE the repo so they never touch git or a build; only the affected
   files are copied, never the whole project. `target/`, `vanilla-src/`,
   `world*/`, `logs/`, `test-logs/` are skipped. The hook never blocks a tool
   call (it always exits 0), so a failed backup will not stop your work —
   but do not disable it, and do not add ad-hoc `cp file file.bak` clutter to
   the tree, that is what this exists to replace. Restore is a plain `cp`
   back from the dated directory.
7. **Decompiling new reference classes** (26.x is unobfuscated): extract
   the `.class` files from `~/mc-26.2/versions/26.2/server-26.2.jar` into a
   temp dir, run `java -jar vanilla-src/tools/vineflower.jar <classdir>
   <outdir>`, and cache the resulting `.java` under the matching
   `vanilla-src/net/...` path so the next session finds it. Check
   `vanilla-src/` first — don't re-decompile what's already cached, BUT
   cached files predating the 2026-07-13 26.2 bump are 26.1.2 decompiles:
   re-decompile any class you port worldgen behavior against.
8. **A release tag requires one fully green playtest run on an idle
   machine** (check `uptime` first; two consecutive greens after any
   flake-class fix). "Environmental flakiness" is a hypothesis you test
   on an idle machine, never a verdict you ship on. A check that fails
   intermittently is a bug — in the test or the product (verify against
   decompiled reference before deciding which; v0.21.1's guardian-laser
   fix was a real parity bug hiding behind "flaky test"). Structural fix
   patterns: conserved quantities / state gates (commit 8a5488f), never
   wider tolerances.
9. **Bundled vanilla data (`src/main/resources/vanilla/`) is regenerated,
   never hand-edited.** `scripts/extract_vanilla_data.py --validate`
   rebuilds all 1,486 jar-derived files from the server jar and proves the
   result (per-file provenance in its docstring). If a data file looks
   wrong, fix the extractor and regenerate; a version bump re-runs it
   against the new jar.
