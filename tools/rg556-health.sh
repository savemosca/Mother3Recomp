#!/usr/bin/env bash
# Collects the diagnostics of recent play sessions from a connected Android
# device and prints one line per session, so problems that do not crash the
# game (interpreted code, missed frames, stalls, audio underruns, engine
# warnings) are noticed after the fact.
#
# Needs adb and root on the device (GammaOS userdebug: su), because the files
# live in the app's private storage. Read-only on the device.
#
# Usage: tools/rg556-health.sh [--package <id>] [--out <dir>]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="com.ylports.mother3recomp.en.dev"
OUT="$ROOT/health"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package) PACKAGE="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v adb >/dev/null || { echo "adb is required" >&2; exit 3; }
[[ "$(adb get-state 2>/dev/null || true)" == device ]] ||
  { echo "No device: connect it and allow USB debugging." >&2; exit 3; }

FILES="/data/data/$PACKAGE/files"
SNAP="$OUT/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$SNAP"

# The archived sessions plus the current one (not archived until next PLAY).
adb exec-out "su -c 'cd $FILES && tar cf - sessions android-runtime.log recomp_coverage_*.json recomp_master_misses_*.toml.frag 2>/dev/null'" \
  > "$SNAP/files.tar" || true
mkdir -p "$SNAP/files"
tar xf "$SNAP/files.tar" -C "$SNAP/files" 2>/dev/null || true
rm -f "$SNAP/files.tar"
adb logcat -d -b crash > "$SNAP/logcat-crash.txt" 2>/dev/null || true

[[ -f "$SNAP/files/android-runtime.log" ]] ||
  { echo "Nothing collected from $FILES (app installed? su allowed?)" >&2; exit 4; }
mkdir -p "$SNAP/files/sessions/current"
for f in "$SNAP"/files/android-runtime.log "$SNAP"/files/recomp_*; do
  [[ -f "$f" ]] && mv "$f" "$SNAP/files/sessions/current/"
done

field() {  # field <key> <text>: value of key=value
  printf '%s\n' "$2" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -1
}

summarize() {  # summarize <session dir>
  local dir="$1" log="$1/android-runtime.log" end cov health warn
  [[ -f "$log" ]] || return 0
  if grep -q "session ended, exit code 0" "$log"; then end="quit"
  elif grep -q "session ended" "$log"; then end="exit $(sed -n 's/.*session ended, exit code //p' "$log" | tail -1)"
  elif grep -q "terminated while suspended" "$log"; then end="closed in background"
  elif grep -q "background — emulation suspended" "$log"; then end="in background"
  else end="CRASH or still running"
  fi
  cov="$({ grep -h "^self_heal_coverage=" "$log" || true; } | tail -1)"
  if [[ -n "$cov" ]]; then
    cov="$(field self_heal_coverage "$cov") misses=$(field dispatch_misses "$cov") interp=$(field interpreted_insns "$cov")"
  else
    cov="coverage n/a"
  fi
  health="$({ grep -h "session_health" "$log" || true; } | tail -1)"
  if [[ -n "$health" ]]; then
    health="frames=$(field frames "$health") late=$(field late_frames "$health") hitches=$(field hitches "$health") max_gap_ms=$(field max_gap_ms "$health") audio_dry_ms=$(field audio_dry_ms "$health") audio_dropped_ms=$(field audio_dropped_ms "$health")"
  else
    health="health n/a (pre-0008 build)"
  fi
  warn="$(grep -c -E "did not iret|SELF-HEAL bridge hit|abandoning|Aborting|abort|FATAL|failed" "$log" || true)"
  printf '%-16s %-22s %-40s %s warnings=%s\n' "$(basename "$dir")" "$end" "$cov" "$health" "$warn"
  # Each hitch with the log line that follows it, and the uncounted stall
  # before the app left the foreground (Android's transition, not the game).
  { grep -A1 -E "host_window: hitch|not counted$" "$log" || true; } |
    grep -v "^--$" | grep -v "device gyro input" | sed 's/^/    /'
}

echo "Collected into $SNAP"
echo
for dir in "$SNAP"/files/sessions/*/; do summarize "${dir%/}"; done
echo
crashes="$(grep -c "Fatal signal" "$SNAP/logcat-crash.txt" || true)"
echo "Android crash buffer: $crashes fatal signal(s) (details in logcat-crash.txt)"
{ grep "Fatal signal" "$SNAP/logcat-crash.txt" || true; } | awk '{print "  " $1 " " $2 " " $NF " " $(NF-1)}'
echo "Interpreted PCs, by session: grep -l NOT_STATIC $SNAP/files/sessions/*/android-runtime.log"
