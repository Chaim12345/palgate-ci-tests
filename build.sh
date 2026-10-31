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

echo "== 1/6 aapt2 compile resources =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res"
# AAR (library) resources: androidx.car.app reads its own R (content limits,
# library version string) at runtime on Android Auto — without these linked
# into the APK the car app dies with NoClassDefFoundError while the phone UI
# works fine. Each lib's res is link-checked standalone first; libs whose res
# references unavailable packages (e.g. media -> appcompat) are skipped.
LIB_FLATS=()
EXTRA_PKGS=()
if [ -d "$SRC/res_libs" ]; then
  for d in "$SRC"/res_libs/*/; do
    [ -d "$d/res" ] || continue
    pkg="$(cat "$d/package.txt" 2>/dev/null || true)"
    [ -n "$pkg" ] || continue
    name="$(basename "$d")"
    mf="$OUT/libmanifest-$name.xml"
    printf '<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s"/>\n' "$pkg" > "$mf"
    # note: aapt2 compile --dir -o <path> writes a single zip of flats
    "$BT/aapt2" compile --dir "$d/res" -o "$OUT/lib-res-$name.zip"
    if "$BT/aapt2" link --static-lib -I "$PLAT" --manifest "$mf" \
        -o "$OUT/lib-$name.ap_" "$OUT/lib-res-$name.zip" 2>"$OUT/lib-$name.err"; then
      LIB_FLATS+=("$OUT/lib-res-$name.zip")
      EXTRA_PKGS+=("$pkg")
      echo "   lib res ok: $name ($pkg)"
    else
      echo "   lib res skipped (does not link standalone): $name"
    fi
  done
fi

echo "== 2/6 aapt2 link (base APK + R.java) =="
mapfile -t FLATS < <(find "$OUT/compiled-res" -name '*.flat' | sort)
FLATS+=("${LIB_FLATS[@]}")
EXTRA=""
if [ ${#EXTRA_PKGS[@]} -gt 0 ]; then
  EXTRA="--extra-packages $(IFS=:; echo "${EXTRA_PKGS[*]}")"
fi
# shellcheck disable=SC2086
"$BT/aapt2" link -I "$PLAT" \
  --manifest "$SRC/AndroidManifest.xml" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 3 --version-name 1.2 \
  -A "$SRC/assets" \
  --java "$OUT/gen" \
  $EXTRA \
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
