#!/usr/bin/env bash
# Pre-change backup hook (see CLAUDE.md rule 7).
#
# Fires as a Claude Code PreToolUse hook on Edit/Write/NotebookEdit and snapshots
# the file AS IT IS BEFORE the edit lands — the restore point, not the result.
# Snapshots go OUTSIDE the repo (~/minecom-backups/) so they never pollute the
# working tree, git status, or a build.
#
# Layout: ~/minecom-backups/YYYY-MM-DD/HHMMSS__src_main_java_..._Foo.java
# A file edited N times in a day leaves N snapshots, oldest-first by timestamp.
#
# Contract: NEVER block the tool call. Every failure path exits 0.

set -uo pipefail

BACKUP_ROOT="${MINECOM_BACKUP_ROOT:-$HOME/minecom-backups}"
PROJECT_ROOT="${MINECOM_PROJECT_ROOT:-$HOME/minecom}"

# Hook payload arrives as JSON on stdin.
payload="$(cat 2>/dev/null || true)"
file="$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null || true)"

# Nothing to back up: no path, or the file doesn't exist yet (a brand-new file
# has no prior contents to preserve — the edit itself is the first version).
[ -n "$file" ] || exit 0
[ -f "$file" ] || exit 0

# Only back up files inside the project.
case "$file" in
    "$PROJECT_ROOT"/*) ;;
    *) exit 0 ;;
esac

rel="${file#"$PROJECT_ROOT"/}"

# Never snapshot build output, decompiled Mojang reference, or the world saves —
# they are huge, regenerable, and (vanilla-src/) must not be copied around.
case "$rel" in
    target/*|vanilla-src/*|world/*|world_backup_customgen/*|logs/*|test-logs/*) exit 0 ;;
esac

day="$(date +%Y-%m-%d)"
stamp="$(date +%H%M%S)"
flat="${rel//\//_}"
dest_dir="$BACKUP_ROOT/$day"

mkdir -p "$dest_dir" 2>/dev/null || exit 0
cp -p "$file" "$dest_dir/${stamp}__${flat}" 2>/dev/null || exit 0

exit 0
