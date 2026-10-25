#!/usr/bin/env bash
# Re-apply the whole setup (e.g. after restore.sh or a factory reset):
# debloat phase 1 -> install Zen Home -> make it home -> disable the stock launcher (phase 2).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$DIR")"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
export ANDROID_SERIAL="${ANDROID_SERIAL:-192.168.0.222:5555}"
APK="$ROOT/launcher/build/ZenHome.apk"

[ -f "$APK" ] || "$ROOT/launcher/build.sh"
"$DIR/debloat.sh" phase1
adb install -r "$APK"
adb shell cmd package set-home-activity --user 0 com.zen.home/.HomeActivity </dev/null
"$DIR/debloat.sh" phase2
adb shell cmd package set-home-activity --user 0 com.zen.home/.HomeActivity </dev/null
adb shell input keyevent KEYCODE_HOME </dev/null
echo "Home now resolves to: $(adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN </dev/null | tail -1)"
