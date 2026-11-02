#!/usr/bin/env bash
# PalGate launcher/local-preview render proof (third-party AARE host, NOT
# stock AA). Encoder-free: AARE's CarPreviewActivity runs the car-app runtime
# LOCALLY on display 0 (no head-unit session, no H.264 path), so this lane is
# unaffected by the consolidated encoder wedge. Flow, grounded in pinned AARE
# source (74271014):
#   MainActivity -> "Preview car screen" pill (ui/MainActivity; CarPreviewActivity
#     is exported=false so it must be reached this way)
#   -> dashboard rail "Apps" button (dash_menu_apps, CarShell.kt)
#   -> launcher grid of discovered car apps (CarAppCatalog discovers
#     CATEGORY_IOT services; our TEST build's GateCarAppService declares it)
#   -> tap "PalGate Car" -> GateSession -> GateGridScreen template rendered
# Asserts ACTUAL rendered template text in the UI hierarchy + captures pixels.
# Fails (exit 1) when the template never renders: no blind greens.
set -uo pipefail
ART=artifacts; mkdir -p "$ART"
MATRIX="$ART/results.csv"
[ -f "$MATRIX" ] || echo "check,tier,result,evidence" > "$MATRIX"
AARE_DIR="${AARE_DIR:-$PWD}"
if [ "${AARE_LAYOUT:-dist}" = "dist" ]; then
  AARE_APK="$AARE_DIR/aare-app-debug.apk"
else
  AARE_APK="$AARE_DIR/app/build/outputs/apk/debug/app-debug.apk"
fi

tap_text() { # $1=regex (case-insensitive-ish), $2=dump file -> taps center of first match
  python3 - "$1" "$2" <<'PY'
import subprocess, re, sys
pat, dump = sys.argv[1], sys.argv[2]
xml = subprocess.run(["adb","shell","cat",dump], capture_output=True, text=True).stdout
best = None
for m in re.finditer(r'text="([^"]*' + pat + r'[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml, re.I):
    best = m
for m in re.finditer(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*' + pat + r'[^"]*)"', xml, re.I):
    if best is None: best = m
if best:
    g = best.groups()
    nums = g[1:] if (len(g) == 5 and not g[0].lstrip('[').isdigit()) else g[:4]
    x1,y1,x2,y2 = map(int, nums)
    subprocess.run(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)])
    print("TAPPED", pat, "at", (x1+x2)//2, (y1+y2)//2)
else:
    print("NOT-FOUND", pat)
    sys.exit(1)
PY
}

echo "-- render lane: install apks --"
adb install -r PalGateCar.apk && echo "PalGateCar (test build) installed"
adb install -r "$AARE_APK" && echo "AARE debug installed"
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION NEARBY_WIFI_DEVICES BLUETOOTH_CONNECT BLUETOOTH_SCAN; do
  adb shell pm grant com.palgate.carapp android.permission.$p >/dev/null 2>&1 || true
done
for p in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS NEARBY_WIFI_DEVICES; do
  adb shell pm grant net.prototux.aare android.permission.$p >/dev/null 2>&1 || true
done
adb logcat -c || true

echo "-- render lane: AARE MainActivity -> Preview car screen --"
adb shell am start -n net.prototux.aare/.ui.MainActivity >/dev/null 2>&1
sleep 8
adb shell uiautomator dump /sdcard/ui_main.xml >/dev/null 2>&1
adb shell cat /sdcard/ui_main.xml > "$ART/render_ui_main.xml" 2>/dev/null || true
tap_text 'Preview' /sdcard/ui_main.xml || { echo "FATAL: no Preview pill"; exit 1; }
sleep 8

echo "-- render lane: dashboard -> Apps (launcher) --"
adb shell uiautomator dump /sdcard/ui_dash.xml >/dev/null 2>&1
adb shell cat /sdcard/ui_dash.xml > "$ART/render_ui_dash.xml" 2>/dev/null || true
tap_text 'Apps' /sdcard/ui_dash.xml || { echo "FATAL: no Apps rail button"; exit 1; }
sleep 5

echo "-- render lane: launcher -> PalGate Car --"
adb shell uiautomator dump /sdcard/ui_launcher.xml >/dev/null 2>&1
adb shell cat /sdcard/ui_launcher.xml > "$ART/render_ui_launcher.xml" 2>/dev/null || true
if ! grep -q 'PalGate' "$ART/render_ui_launcher.xml"; then
  echo "FATAL: PalGate Car not in AARE launcher (discovery failed)"
  exit 1
fi
tap_text 'PalGate' /sdcard/ui_launcher.xml || { echo "FATAL: PalGate label not tappable"; exit 1; }
sleep 10

echo "-- render lane: assert rendered template + capture pixels --"
adb shell uiautomator dump /sdcard/ui_gate.xml >/dev/null 2>&1
adb shell cat /sdcard/ui_gate.xml > "$ART/render_ui_gate.xml" 2>/dev/null || true
adb shell screencap -p /sdcard/palgate_render.png > "$ART/screencap_render.log" 2>&1
adb pull /sdcard/palgate_render.png "$ART/palgate_render.png" >/dev/null 2>&1 || true

adb logcat -d -s PalGate:I > "$ART/render_palgate.log" 2>/dev/null || true
adb logcat -d | grep -iE 'carapp|CarApp|HostValidator|GateCar|GateGrid|palgate' > "$ART/render_carapp.log" 2>/dev/null || true
# Same masking discipline as the other lanes (40-hex blobs, local MACs).
sed -i -E 's/\b[0-9a-fA-F]{32,64}\b/[hex]/g' "$ART/render_palgate.log" "$ART/render_carapp.log" "$ART/render_ui_"*.xml 2>/dev/null || true

RENDERED=0
grep -q 'PalGate - ' "$ART/render_ui_gate.xml" && RENDERED=1
grep -q 'No gates configured' "$ART/render_ui_gate.xml" && RENDERED=1
if [ "$RENDERED" = "1" ] && [ -s "$ART/palgate_render.png" ]; then
  echo "palgate_render,gate,pass,\"GateGridScreen template rendered in AARE local preview: render_ui_gate.xml + palgate_render.png (third-party host, not stock AA)\"" >> "$MATRIX"
  echo "RENDER PROOF OK: template text present, pixels captured"
  exit 0
fi
echo "palgate_render,gate,fail,\"template text absent or no pixels: render_ui_gate.xml\"" >> "$MATRIX"
echo "::error::RENDER PROOF FAILED: GateGridScreen template not observed"
exit 1
