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

restore_test_overlays() {
  rm -f "$SRC/assets/test_build.marker"
  if [ -f "$OUT/gates.json.orig" ]; then
    cp "$OUT/gates.json.orig" "$SRC/assets/gates.json"
    rm -f "$OUT/gates.json.orig"
  fi
}
trap restore_test_overlays EXIT

MANIFEST="$SRC/AndroidManifest.xml"
if [ "${TEST_BUILD:-0}" = "1" ]; then
  echo "ci-test-build" > "$SRC/assets/test_build.marker"
  echo "== TEST_BUILD=1: actuation-disabled marker injected =="
  # Test builds only: temporarily export GateMonitorService so CI can drive the
  # exact targeted dispatch (ACTION_OPEN_NOW + gateId) from the shell. The
  # production manifest keeps it non-exported; the test-marker choke makes
  # actuation impossible in any build that has this overlay.
  mkdir -p "$OUT"
  MANIFEST="$OUT/AndroidManifest.test.xml"
  sed -e 's|android:name=".GateMonitorService" android:exported="false"|android:name=".GateMonitorService" android:exported="true"|' \
      -e 's|<application|<application android:debuggable="true"|' \
    "$SRC/AndroidManifest.xml" > "$MANIFEST"
  grep -q 'GateMonitorService" android:exported="true"' "$MANIFEST" || { echo "test manifest overlay failed"; exit 1; }
  grep -q '<application android:debuggable="true"' "$MANIFEST" || { echo "test debuggable overlay failed"; exit 1; }
  [ "$(grep -c 'android:debuggable' "$MANIFEST")" = "1" ] || { echo "unexpected debuggable count"; exit 1; }
  echo "== TEST_BUILD=1: GateMonitorService exported + debuggable (test-only manifest overlays; enable CI dispatch + run-as seeding) =="
  if [ "${EMPTY_GATES:-0}" = "1" ]; then
    # Empty-state render proof variant: no bundled synthetic gates, so the
    # car screen must render its empty template. TEST builds only.
    cp "$SRC/assets/gates.json" "$OUT/gates.json.orig"
    printf '{"gates":[]}' > "$SRC/assets/gates.json"
    echo "== TEST_BUILD=1 EMPTY_GATES=1: bundled gate fixtures emptied (empty-state variant) =="
  fi
else
  # Production never ships the synthetic test fixtures: Gate.load reads
  # assets/gates.json in every build, while real user gates live in runtime
  # SharedPreferences (untouched). Without this the production APK would
  # bundle "Test Gate A/B" and a fake key.
  cp "$SRC/assets/gates.json" "$OUT/gates.json.orig"
  printf '{"gates":[]}' > "$SRC/assets/gates.json"
  echo "== production: synthetic bundled fixtures excluded (runtime prefs preserved) =="
fi

echo "== 1/6 aapt2 compile resources =="
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled-res"

echo "== 2/6 aapt2 link (base APK + R.java) =="
mapfile -t FLATS < <(find "$OUT/compiled-res" -name '*.flat' | sort)
"$BT/aapt2" link -I "$PLAT" \
  --manifest "$MANIFEST" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 5 --version-name 1.4 \
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

echo "== manifest/resource assertions (review fixes) =="
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$SRC/PalGateCar.apk" > "$OUT/manifest.txt"
grep -A1 'androidx.car.app.minCarApiLevel' "$OUT/manifest.txt" | grep -qE '=1$|0x1$' \
  || { echo "FAIL: androidx.car.app.minCarApiLevel=1 metadata missing from APK"; exit 1; }
echo "ok: minCarApiLevel=1 present in built APK"
"$BT/aapt2" dump resources "$SRC/PalGateCar.apk" | grep -q 'hosts_allowlist' \
  || { echo "FAIL: hosts_allowlist array missing from APK resources"; exit 1; }
echo "ok: hosts_allowlist resource present in built APK"

echo "== build-mode assertions =="
if [ "${TEST_BUILD:-0}" = "1" ]; then
  unzip -l "$SRC/PalGateCar.apk" | grep -q 'assets/test_build.marker' \
    || { echo "FAIL: TEST build missing actuation-choke marker"; exit 1; }
  grep -A4 'GateMonitorService' "$OUT/manifest.txt" | grep -qE 'exported.*(0xffffffff|=true|0x1\b)' \
    || { echo "FAIL: TEST overlay did not export GateMonitorService"; exit 1; }
  grep -q 'android:debuggable.*=true' "$OUT/manifest.txt" \
    || { echo "FAIL: TEST overlay missing debuggable"; exit 1; }
  echo "ok: TEST artifact = NON-PRODUCTION (marker present -> actuation choked + ALLOW_ALL host validation; monitor exported + debuggable for CI)"
else
  if unzip -l "$SRC/PalGateCar.apk" | grep -q 'assets/test_build.marker'; then
    echo "FAIL: production build contains test marker"; exit 1
  fi
  grep -A4 'GateMonitorService' "$OUT/manifest.txt" | grep -qE 'exported.*(0x0\b|=false)' \
    || { echo "FAIL: production GateMonitorService must stay unexported"; exit 1; }
  if grep -q 'android:debuggable.*=true' "$OUT/manifest.txt"; then
    echo "FAIL: production build must not be debuggable"; exit 1
  fi
  if unzip -p "$SRC/PalGateCar.apk" assets/gates.json | grep -qiE 'TEST000|SYNTHETIC|00112233445566778899AABBCCDDEEFF'; then
    echo "FAIL: production APK bundles synthetic gate fixtures"; exit 1
  fi
  echo "ok: production artifact (no marker -> strict hosts_allowlist validation; monitor unexported; not debuggable; no synthetic fixtures)"
fi

echo "== done: $SRC/PalGateCar.apk =="
