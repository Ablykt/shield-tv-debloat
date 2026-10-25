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
# Without a backup (e.g. it was taken on another computer) it re-enables every package in
# debloat-list.txt instead.
#
# Optional: ANDROID_SERIAL=ip:5555, BACKUP=backup/<stamp>.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
. "$DIR/common.sh"
BACKUP="${BACKUP:-}"
[ -n "$BACKUP" ] || [ ! -f "$ROOT/backup/LATEST" ] || BACKUP="$ROOT/$(cat "$ROOT/backup/LATEST")"
STOCK_HOME="com.google.android.tvlauncher/.MainActivity"

DRY="$DRY_RUN"; UNINSTALL_ZEN=0; ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --uninstall-zen) UNINSTALL_ZEN=1 ;;
    --package) ONLY="$2"; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option: $1"; exit 2 ;;
  esac
  shift
done

run() { if [ "$DRY" = 1 ]; then echo "  [dry-run] adb shell $*"; else adb shell "$@" </dev/null; fi; }

shield_connect
if [ -n "$BACKUP" ] && [ -f "$BACKUP/packages_disabled.txt" ]; then
  echo "Using backup: $BACKUP"
  originally_disabled="$(sed 's/^package://' "$BACKUP/packages_disabled.txt" | tr -d '\r')"
else
  [ -z "$BACKUP" ] || echo "Backup not found: $BACKUP"
  echo "No backup: re-enabling every package in debloat-list.txt instead."
  originally_disabled=""
  BACKUP=""
fi

if [ -n "$ONLY" ]; then
  run pm enable "$ONLY"
  exit 0
fi

# Packages disabled now that were NOT disabled in the backup -> re-enable.
# Without a backup, only packages from debloat-list.txt are touched.
now_disabled="$(adb shell pm list packages -d </dev/null | sed 's/^package://' | tr -d '\r')"
ours="$(grep -vE '^\s*(#|$)' "$DIR/debloat-list.txt" | sed 's/#.*//' | xargs -n1)"
count=0
for pkg in $now_disabled; do
  if [ -z "$BACKUP" ] && ! grep -qx "$pkg" <<<"$ours"; then continue; fi
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
  if [ "$DRY" = 1 ]; then echo "  [dry-run] adb uninstall com.zen.home"; else adb uninstall com.zen.home || true; fi
fi

if [ "$DRY" = 0 ]; then
  run input keyevent KEYCODE_HOME
  echo
  echo "Home now resolves to: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null | tail -1 | tr -d '\r')"
  [ -n "$BACKUP" ] || exit 0
  diff <(sed 's/^package://' "$BACKUP/packages_disabled.txt" | tr -d '\r' | sort) \
       <(adb shell pm list packages -d </dev/null | sed 's/^package://' | tr -d '\r' | sort) \
    && echo "Disabled-package list matches the backup exactly." \
    || echo "Note: differences from backup listed above."
fi
