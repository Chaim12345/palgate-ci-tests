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

echo "== 1b/6 aapt2 compile library resources (R-class merge) =="
# Merged library code references its own R classes (verified by class scan:
# androidx.car.app, androidx.core, androidx.media, androidx.lifecycle.runtime,
# androidx.lifecycle.viewmodel, androidx.savedstate). AARs ship no R bytecode
# and our dex had none: androidx.car.app's handshake (AppInfo /
# car_app_library_version) and ConstraintManager (content_limit_grid) crashed
# with NoClassDefFoundError on first host bind. Compile each AAR's res/ and
# link them in with --extra-packages so real R classes are generated.
AAR_RES_PKGS="androidx.car.app:app-1.4.0 androidx.core:core-1.7.0 androidx.media:media-1.6.0 androidx.lifecycle.runtime:lifecycle-runtime-2.3.1 androidx.lifecycle.viewmodel:lifecycle-viewmodel-2.3.0 androidx.savedstate:savedstate-1.1.0"
EXTRA_PKGS=""
mkdir -p "$OUT/compiled-res-aar"
for pair in $AAR_RES_PKGS; do
  pkg="${pair%%:*}"; art="${pair##*:}"
  rm -rf "$OUT/aar-res-$art"
  mkdir -p "$OUT/aar-res-$art"
  (cd "$OUT/aar-res-$art" && unzip -qo "$SRC/aars/$art.aar" 'res/*')
  # -o must be a pre-existing directory or aapt2 writes a single flat-zip FILE
  mkdir -p "$OUT/compiled-res-aar/$art"
  "$BT/aapt2" compile --dir "$OUT/aar-res-$art/res" -o "$OUT/compiled-res-aar/$art"
  EXTRA_PKGS="${EXTRA_PKGS:+$EXTRA_PKGS:}$pkg"
done

echo "== 2/6 aapt2 link (base APK + R.java) =="
mapfile -t FLATS < <(find "$OUT/compiled-res" "$OUT/compiled-res-aar" -name '*.flat' | sort)
"$BT/aapt2" link -I "$PLAT" \
  --manifest "$MANIFEST" \
  --min-sdk-version 23 --target-sdk-version 34 \
  --version-code 5 --version-name 1.4 \
  -A "$SRC/assets" \
  --java "$OUT/gen" \
  --extra-packages "$EXTRA_PKGS" \
  -o "$OUT/base.apk" \
  "${FLATS[@]}"

echo "== 3/6 ecj compile (app + libs classpath) =="
mapfile -t SOURCES < <(find "$SRC/src" "$OUT/gen" -name '*.java' | sort)
java -jar "$ECJ" -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$PLAT" -classpath "$SRC/cp" \
  -d "$OUT/classes" "${SOURCES[@]}"

echo "== 4/6 d8 dex (app + androidx.car.app + deps) =="
(cd "$OUT/classes" && zip -q -r "$OUT/app.jar" .)
# car-app-api.level is a classpath resource, not dex input: exclude from the
# jar, package into the APK root at step 5 (ClassLoader.getResourceAsStream).
(cd "$SRC/cp" && zip -q -r "$OUT/cp.jar" . -x 'car-app-api.level')
"$BT/d8" --release --lib "$PLAT" --min-api 23 \
  --output "$OUT/dex" \
  "$OUT/app.jar" "$OUT/cp.jar"

echo "== 5/6 package =="
(cd "$OUT/dex" && zip -q -j "$OUT/base.apk" classes.dex)
[ -f "$SRC/cp/car-app-api.level" ] && (cd "$SRC/cp" && zip -q "$OUT/base.apk" car-app-api.level)
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
[ -f "$OUT/classes/androidx/car/app/R\$string.class" ] \
  || { echo "FAIL: androidx.car.app.R classes were not generated/compiled"; exit 1; }
echo "ok: androidx.car.app.R generated and compiled into dex input"
unzip -l "$OUT/base.apk" | grep -q 'car-app-api.level' \
  || { echo "FAIL: car-app-api.level classpath resource missing from APK root"; exit 1; }
echo "ok: car-app-api.level packaged at APK root"
# dump-to-file: a pipe to grep -q is SIGPIPE-racy under pipefail
"$BT/aapt2" dump resources "$SRC/PalGateCar.apk" > "$OUT/resources.txt"
grep -q 'car_app_library_version' "$OUT/resources.txt" \
  || { echo "FAIL: androidx.car.app resources not merged"; exit 1; }
echo "ok: androidx.car.app resources merged"

echo "== identity + collision assertions (values, not just presence) =="
# car-app-api.level in the APK must be byte-identical to the AAR's entry
python3 - "$SRC/aars/app-1.4.0.aar" "$OUT/base.apk" <<'PYID'
import sys, zipfile, hashlib
aar, apk = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z, z.open('classes.jar') as cjf:
    import io
    cj = zipfile.ZipFile(io.BytesIO(cjf.read()))
    want = hashlib.sha256(cj.read('car-app-api.level')).hexdigest()
got = hashlib.sha256(zipfile.ZipFile(apk).read('car-app-api.level')).hexdigest()
if want != got:
    print("FAIL: car-app-api.level content differs from AAR"); sys.exit(1)
print("ok: car-app-api.level byte-identical to AAR entry")
PYID
grep -A1 'string/car_app_library_version' "$OUT/resources.txt" | grep -q '"1.4.0"' \
  || { echo "FAIL: car_app_library_version != 1.4.0"; exit 1; }
grep -A1 'integer/content_limit_grid' "$OUT/resources.txt" | grep -qE '^\s+\(\) 6$' \
  || { echo "FAIL: content_limit_grid != 6"; exit 1; }
echo "ok: library resource VALUES verified (library 1.4.0, grid limit 6)"
# merge must not have shadowed our own resources
grep -A1 'color/widget_bg$' "$OUT/resources.txt" | grep -q '#fff4f8fc' \
  || { echo "FAIL: own widget_bg color shadowed by merge"; exit 1; }
grep -A1 'array/hosts_allowlist' "$OUT/resources.txt" | grep -q 'size=6' \
  || { echo "FAIL: hosts_allowlist altered by merge"; exit 1; }
echo "ok: own resources intact after library merge (no collisions/shadowing)"
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$SRC/PalGateCar.apk" > "$OUT/manifest.txt"
grep -A1 'androidx.car.app.minCarApiLevel' "$OUT/manifest.txt" | grep -qE '=1$|0x1$' \
  || { echo "FAIL: androidx.car.app.minCarApiLevel=1 metadata missing from APK"; exit 1; }
echo "ok: minCarApiLevel=1 present in built APK"
grep -q 'hosts_allowlist' "$OUT/resources.txt" \
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
