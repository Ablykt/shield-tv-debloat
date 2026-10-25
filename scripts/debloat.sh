#!/usr/bin/env bash
# Disable (never uninstall) the packages in debloat-list.txt.
# Usage: scripts/debloat.sh [phase1|phase2|all]
#   phase1 = everything except the stock launcher/recommendations
#   phase2 = only the [phase2] lines (run after Zen Home is the default launcher)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/common.sh"
PHASE="${1:-phase1}"
case "$PHASE" in phase1|phase2|all) ;; *) echo "usage: $0 [phase1|phase2|all]"; exit 2 ;; esac
mkdir -p "$ROOT/backup"
LOG="$ROOT/backup/debloat-$(date +%Y%m%d-%H%M%S).log"

shield_connect

grep -vE '^\s*(#|$)' "$DIR/debloat-list.txt" | while read -r line; do
  pkg="${line%%#*}"; pkg="$(echo "$pkg" | xargs)"
  case "$PHASE" in
    phase1) [[ "$line" == *"[phase2]"* ]] && continue ;;
    phase2) [[ "$line" != *"[phase2]"* ]] && continue ;;
  esac
  if kept "$pkg"; then echo "keep (you asked): $pkg"; continue; fi
  if ! adb shell pm path "$pkg" </dev/null >/dev/null 2>&1; then
    echo "skip (not installed): $pkg" | tee -a "$LOG"; continue
  fi
  if [ "$DRY_RUN" = 1 ]; then echo "  [dry-run] disable $pkg"; continue; fi
  out="$(adb shell pm disable-user --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r' || true)"
  echo "$pkg -> $out" | tee -a "$LOG"
done
[ "$DRY_RUN" = 1 ] || echo "Log: $LOG"
