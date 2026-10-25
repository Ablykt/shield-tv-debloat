#!/usr/bin/env bash
# Undo the SHIELD debloat using the backup taken before any change.
#
#   scripts/restore.sh                  full restore: re-enable every package that was enabled at
#                                       backup time, make the stock Google TV launcher home again.
#                                       Zen Home stays installed (just not the default).
#   scripts/restore.sh --uninstall-zen  same, and also uninstall Zen Home.
#   scripts/restore.sh --package PKG    re-enable a single package only.
#   scripts/restore.sh --dry-run        show what would change, change nothing.
#
# Optional: ANDROID_SERIAL=ip:5555 (default 192.168.0.222:5555), BACKUP=backup/<stamp>.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
export ANDROID_SERIAL="${ANDROID_SERIAL:-192.168.0.222:5555}"
BACKUP="${BACKUP:-$ROOT/$(cat "$ROOT/backup/LATEST")}"
STOCK_HOME="com.google.android.tvlauncher/.MainActivity"

DRY=0; UNINSTALL_ZEN=0; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --uninstall-zen) UNINSTALL_ZEN=1 ;;
    --package) ONLY="$2"; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
  shift
done

run() { if [ $DRY = 1 ]; then echo "  [dry-run] adb shell $*"; else adb shell "$@" </dev/null; fi; }

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
[ "$(adb get-state 2>/dev/null)" = device ] || {
  echo "SHIELD not reachable at $ANDROID_SERIAL."
  echo "Turn on Settings > Device Preferences > Developer options > Network debugging, then retry."
  exit 1
}
[ -f "$BACKUP/packages_disabled.txt" ] || { echo "Backup not found: $BACKUP"; exit 1; }
echo "Using backup: $BACKUP"

if [ -n "$ONLY" ]; then
  run pm enable "$ONLY"
  exit 0
fi

# Packages disabled now that were NOT disabled in the backup -> re-enable.
originally_disabled="$(sed 's/^package://' "$BACKUP/packages_disabled.txt" | tr -d '\r')"
now_disabled="$(adb shell pm list packages -d </dev/null | sed 's/^package://' | tr -d '\r')"
count=0
for pkg in $now_disabled; do
  if ! grep -qx "$pkg" <<<"$originally_disabled"; then
    echo "re-enable: $pkg"
    run pm enable "$pkg"
    count=$((count + 1))
  fi
done
echo "$count package(s) re-enabled."

echo "home -> $STOCK_HOME"
run cmd package set-home-activity --user 0 "$STOCK_HOME"

if [ $UNINSTALL_ZEN = 1 ]; then
  echo "uninstall Zen Home"
  if [ $DRY = 1 ]; then echo "  [dry-run] adb uninstall com.zen.home"; else adb uninstall com.zen.home || true; fi
fi

if [ $DRY = 0 ]; then
  run input keyevent KEYCODE_HOME
  echo
  echo "Home now resolves to: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null | tail -1 | tr -d '\r')"
  diff <(sed 's/^package://' "$BACKUP/packages_disabled.txt" | tr -d '\r' | sort) \
       <(adb shell pm list packages -d </dev/null | sed 's/^package://' | tr -d '\r' | sort) \
    && echo "Disabled-package list matches the backup exactly." \
    || echo "Note: differences from backup listed above."
fi
