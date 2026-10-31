#!/usr/bin/env bash
# Build WidgetHostTest.apk - no Gradle, same toolchain as build.sh.
set -euo pipefail
SRC="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/home/z/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
ECJ="${ECJ_JAR:-/home/z/ecj.jar}"
OUT="$SRC/out"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/compiled-res" "$OUT/dex"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res"
mapfile -t FLATS < <(find "$OUT/compiled-res" -name '*.flat' | sort)
"$BT/aapt2" link -I "$PLAT" --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --java "$OUT/gen" -o "$OUT/base.apk" "${FLATS[@]}"
mapfile -t SOURCES < <(find "$SRC/src" "$OUT/gen" -name '*.java' | sort)
java -jar "$ECJ" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$PLAT" -d "$OUT/classes" "${SOURCES[@]}"
(cd "$OUT/classes" && zip -q -r "$OUT/app.jar" .)
"$BT/d8" --release --lib "$PLAT" --min-api 23 --output "$OUT/dex" "$OUT/app.jar"
(cd "$OUT/dex" && zip -q -j "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
KS="$SRC/testkey.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias palgate -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass palgatetest -keypass palgatetest \
    -dname "CN=PalGate Car" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:palgatetest --key-pass pass:palgatetest \
  --out "$SRC/WidgetHostTest.apk" "$OUT/aligned.apk"
echo "== done: $SRC/WidgetHostTest.apk =="
