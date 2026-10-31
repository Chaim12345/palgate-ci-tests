#!/usr/bin/env bash
# Build PalGateCar.apk — Android Auto app, no Gradle.
# Toolchain: SDK platform-34 + build-tools 34.0.0 + ecj (JRE-only sandbox).
# Libraries: androidx.car.app:app:1.4.0 closure from aars/ (merged into cp/).
set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-/home/z/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
ECJ="${ECJ_JAR:-/home/z/ecj.jar}"
OUT="$SRC/out"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/compiled-res" "$OUT/dex"

if [ "${TEST_BUILD:-0}" = "1" ]; then
  echo "ci-test-build" > "$SRC/assets/test_build.marker"
  trap 'rm -f "$SRC/assets/test_build.marker"' EXIT
  echo "== TEST_BUILD=1: actuation-disabled marker injected =="
fi

echo "== 1/6 aapt2 compile resources =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res"

echo "== 2/6 aapt2 link (base APK + R.java) =="
mapfile -t FLATS < <(find "$OUT/compiled-res" -name '*.flat' | sort)
"$BT/aapt2" link -I "$PLAT" \
  --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 4 --version-name 1.3 \
  -A "$SRC/assets" \
  --java "$OUT/gen" \
  -o "$OUT/base.apk" \
  "${FLATS[@]}"

echo "== 3/6 ecj compile (app + libs classpath) =="
mapfile -t SOURCES < <(find "$SRC/src" "$OUT/gen" -name '*.java' | sort)
java -jar "$ECJ" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$PLAT" -classpath "$SRC/cp" \
  -d "$OUT/classes" "${SOURCES[@]}"

echo "== 4/6 d8 dex (app + androidx.car.app + deps) =="
(cd "$OUT/classes" && zip -q -r "$OUT/app.jar" .)
(cd "$SRC/cp" && zip -q -r "$OUT/cp.jar" .)
"$BT/d8" --release --lib "$PLAT" --min-api 23 \
  --output "$OUT/dex" \
  "$OUT/app.jar" "$OUT/cp.jar"

echo "== 5/6 package =="
(cd "$OUT/dex" && zip -q -j "$OUT/base.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 sign =="
KS="$SRC/testkey.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias palgate -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass palgatetest -keypass palgatetest \
    -dname "CN=PalGate Car" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:palgatetest --key-pass pass:palgatetest \
  --out "$SRC/PalGateCar.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$SRC/PalGateCar.apk" | head -2
echo "== done: $SRC/PalGateCar.apk =="
