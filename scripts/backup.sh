#!/usr/bin/env bash
# Snapshot the SHIELD's state before changing anything: package lists (incl. which are
# disabled), every settings namespace, default home, preferred activities, app-ops, memory
# and a screenshot. restore.sh uses the snapshot named in backup/LATEST: the first one taken.
# Usage: scripts/backup.sh [--set-latest]
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
export ANDROID_SERIAL="${ANDROID_SERIAL:-192.168.0.222:5555}"

adb connect "$ANDROID_SERIAL" >/dev/null 2>&1 || true
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "SHIELD not reachable at $ANDROID_SERIAL"; exit 1; }

STAMP="$(date +%Y%m%d-%H%M%S)"
B="$ROOT/backup/$STAMP"
mkdir -p "$B/screens"

adb shell getprop > "$B/getprop.txt"
adb shell pm list packages -f | sort > "$B/packages_all.txt"
for f in -e -d -s -3 -u; do adb shell pm list packages "$f" -f | sort > "$B/packages$f.txt"; done
adb shell pm list packages -d | sort > "$B/packages_disabled.txt"
adb shell pm list users > "$B/users.txt"
for ns in system secure global; do adb shell settings list "$ns" > "$B/settings_$ns.txt"; done
adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN > "$B/home_activity.txt"
adb shell dumpsys package preferred-activities > "$B/preferred_activities.txt"
adb shell dumpsys deviceidle whitelist > "$B/deviceidle_whitelist.txt"
adb shell dumpsys appops > "$B/appops.txt" 2>/dev/null || true
adb shell dumpsys meminfo > "$B/meminfo.txt"
adb exec-out screencap -p > "$B/screens/home.png"

echo "Backup saved to $B"
# restore.sh returns the SHIELD to the backup named in LATEST. Only the first (pre-debloat)
# snapshot becomes LATEST, so a later backup can't quietly turn restore into a no-op.
if [ ! -f "$ROOT/backup/LATEST" ] || [ "${1:-}" = "--set-latest" ]; then
  echo "backup/$STAMP" > "$ROOT/backup/LATEST"
  echo "restore.sh will now restore to this snapshot."
else
  echo "restore.sh still uses $(cat "$ROOT/backup/LATEST") (pass --set-latest to switch)."
fi
