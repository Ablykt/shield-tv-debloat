#!/usr/bin/env bash
# Builds Zen Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
SDK="$HOME/Library/Android/sdk"
BT="$SDK/build-tools/35.0.1"
JAR="$SDK/platforms/android-30/android.jar"
[ -f "$JAR" ] || JAR="$SDK/platforms/android-33/android.jar"
OUT=build
APP=app

mkdir -p "$OUT"
find "$OUT" -mindepth 1 -delete
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
  --manifest "$APP/AndroidManifest.xml" -A "$APP/assets" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 30 \
  -0 jpg ${DEBUG:+--debug-mode} "$OUT/res/res.zip"

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -classpath "$JAR" -d "$OUT/classes" \
  $(find "$APP/src" "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=keystore/zenhome.jks
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KS" -storepass zenhome -keypass zenhome -alias zen \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Zen Home" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:zenhome --key-pass pass:zenhome \
  --out "$OUT/ZenHome.apk" "$OUT/aligned.apk"
ls -la "$OUT/ZenHome.apk"
