#!/usr/bin/env bash
# Disable (never uninstall) the packages in debloat-list.txt.
# Usage: scripts/debloat.sh [phase1|phase2|all]
#   phase1 = everything except the stock launcher/recommendations
#   phase2 = only the [phase2] lines (run after Zen Home is the default launcher)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
export ANDROID_SERIAL="${ANDROID_SERIAL:-192.168.0.222:5555}"
PHASE="${1:-phase1}"
LOG="$ROOT/backup/debloat-$(date +%Y%m%d-%H%M%S).log"

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "SHIELD not reachable at $ANDROID_SERIAL"; exit 1; }

grep -vE '^\s*(#|$)' "$DIR/debloat-list.txt" | while read -r line; do
  pkg="${line%%#*}"; pkg="$(echo "$pkg" | xargs)"
  case "$PHASE" in
    phase1) [[ "$line" == *"[phase2]"* ]] && continue ;;
    phase2) [[ "$line" != *"[phase2]"* ]] && continue ;;
  esac
  if ! adb shell pm path "$pkg" </dev/null >/dev/null 2>&1; then
    echo "skip (not installed): $pkg" | tee -a "$LOG"; continue
  fi
  out="$(adb shell pm disable-user --user 0 "$pkg" </dev/null 2>&1 || true)"
  echo "$pkg -> $out" | tee -a "$LOG"
done
echo "Log: $LOG"
