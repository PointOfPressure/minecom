#!/usr/bin/env bash
# Temp-gated runner: waits until the CPU package is below COOL_START °C before
# launching the given command, to stop sustained back-to-back worldgen/test runs
# from thermal-killing the session (Dell Inspiron 7548 cooling can't hold
# repeated full-load java runs — package hits 90°C+, crit 105°C).
# Usage: scripts/cool-run.sh [COOL_START°C] -- <command...>
set -u
COOL_START=72
if [[ "${1:-}" =~ ^[0-9]+$ ]]; then COOL_START="$1"; shift; fi
[ "${1:-}" = "--" ] && shift

pkg_temp() {
  local t
  t=$(cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -rn | head -1)
  echo $(( ${t:-0} / 1000 ))
}

T=$(pkg_temp)
echo "[cool-run] package ${T}°C (gate: <${COOL_START}°C)"
while [ "$(pkg_temp)" -ge "$COOL_START" ]; do
  sleep 20
done
echo "[cool-run] cooled to $(pkg_temp)°C at $(date +%H:%M:%S) — starting: $*"
exec "$@"
