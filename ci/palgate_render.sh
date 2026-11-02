#!/usr/bin/env bash
# PalGate template render proofs through AARE's LOCAL car runtime
# (CarPreviewActivity, third-party host - NOT stock AA, never a stock-AA claim).
# Encoder-free by design: no head-unit session, no H.264 path.
#
#   Phase A (empty build):  empty-state template - LIMITED proof: launcher
#                           discovery + host binding + session + template render.
#   Phase B1 (TEST build):  populated grid (2 bundled synthetic gates), item
#                           titles asserted, then one safe OPEN tap: MUST hit
#                           the test-marker choke (SYNTHETIC-DISPATCH) and MUST
#                           NOT actuate (no BLE/broadcast dispatch markers).
#   Phase B2 (TEST build):  overflow - seed 5 more synthetic runtime gates
#                           (root adb only) => 7 total; assert exactly the
#                           host limit shown, 7th hidden, cap disclosed in
#                           the title. Explicitly reported when not attempted.
# Any required phase that fails fails the lane; nothing reads as a blind green.
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
FAIL=0

tap_text() { # $1=regex $2=dump -> tap center of first match; rc 1 if absent
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

grant_perms() {
  for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION NEARBY_WIFI_DEVICES BLUETOOTH_CONNECT BLUETOOTH_SCAN; do
    adb shell pm grant com.palgate.carapp android.permission.$p >/dev/null 2>&1 || true
  done
  for p in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS NEARBY_WIFI_DEVICES; do
    adb shell pm grant net.prototux.aare android.permission.$p >/dev/null 2>&1 || true
  done
}

open_preview() { # -> opens AARE preview, launcher, taps PalGate Car; rc 1 on failure
  adb shell am force-stop net.prototux.aare >/dev/null 2>&1 || true
  adb shell am start -n net.prototux.aare/.ui.MainActivity >/dev/null 2>&1
  sleep 8
  adb shell uiautomator dump /sdcard/ui_main.xml >/dev/null 2>&1
  tap_text 'Preview' /sdcard/ui_main.xml || return 1
  sleep 8
  adb shell uiautomator dump /sdcard/ui_dash.xml >/dev/null 2>&1
  tap_text 'Apps' /sdcard/ui_dash.xml || return 1
  sleep 5
  adb shell uiautomator dump /sdcard/ui_launcher.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui_launcher.xml > "$ART/render_ui_launcher.xml" 2>/dev/null || true
  grep -q 'PalGate' "$ART/render_ui_launcher.xml" || { echo "launcher: PalGate Car not discovered"; return 1; }
  tap_text 'PalGate' /sdcard/ui_launcher.xml || return 1
  sleep 10
}

dump_gate_screen() { # $1=tag
  adb shell uiautomator dump /sdcard/ui_gate.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui_gate.xml > "$ART/render_ui_gate_$1.xml" 2>/dev/null || true
  adb shell screencap -p "/sdcard/palgate_render_$1.png" > "$ART/screencap_$1.log" 2>&1
  adb pull "/sdcard/palgate_render_$1.png" "$ART/palgate_render_$1.png" >/dev/null 2>&1 || true
}

mask_logs() {
  sed -i -E 's/\b[0-9a-fA-F]{32,64}\b/[hex]/g' "$@" 2>/dev/null || true
}

adb logcat -c || true

echo "== Phase A: empty-state template (limited proof) =="
adb install -r PalGateCar-TEST-empty.apk && echo "empty-gates TEST build installed"
adb install -r "$AARE_APK" >/dev/null && echo "AARE installed"
grant_perms
if ! open_preview; then
  echo "palgate_render_empty,gate,fail,\"preview/launcher flow failed before template\"" >> "$MATRIX"
  echo "::error::PHASE A FAILED: could not reach PalGate template"
  FAIL=1
fi
dump_gate_screen "empty"
if grep -q 'No gates configured' "$ART/render_ui_gate_empty.xml" 2>/dev/null \
   && grep -q 'PalGate - ' "$ART/render_ui_gate_empty.xml" \
   && [ -s "$ART/palgate_render_empty.png" ]; then
  echo "palgate_render_empty,gate,pass,\"LIMITED proof: discovery+bind+session+empty template (no gate flow): render_ui_gate_empty.xml + palgate_render_empty.png (AARE third-party host)\"" >> "$MATRIX"
  echo "PHASE A OK (limited: empty state only)"
else
  echo "palgate_render_empty,gate,fail,\"empty template not observed: render_ui_gate_empty.xml\"" >> "$MATRIX"
  echo "::error::PHASE A FAILED: empty-state template not observed"
  FAIL=1
fi

echo "== Phase B1: populated grid + safe OPEN tap (blocked) =="
adb install -r PalGateCar.apk && echo "standard TEST build installed (2 bundled synthetic gates)"
grant_perms
adb logcat -c || true
if ! open_preview; then
  echo "palgate_render_populated,gate,fail,\"preview/launcher flow failed before template\"" >> "$MATRIX"
  echo "::error::PHASE B1 FAILED: could not reach PalGate template"
  FAIL=1
else
  dump_gate_screen "populated"
  UI="$ART/render_ui_gate_populated.xml"
  ITEMS=$(grep -o 'text="Test Gate [A-Z] (SYNTHETIC)' "$UI" 2>/dev/null | sort -u | wc -l)
  echo "visible synthetic gate items: $ITEMS"
  if grep -q 'PalGate - ' "$UI" && [ "$ITEMS" -eq 2 ] && [ -s "$ART/palgate_render_populated.png" ]; then
    echo "palgate_render_populated,gate,pass,\"2 synthetic gates rendered with titles (AARE third-party host): render_ui_gate_populated.xml + png\"" >> "$MATRIX"
    echo "PHASE B1 render OK"
  else
    echo "palgate_render_populated,gate,fail,\"expected 2 synthetic gate items + title, saw $ITEMS: $UI\"" >> "$MATRIX"
    echo "::error::PHASE B1 render FAILED"
    FAIL=1
  fi

  echo "-- safe OPEN tap: must choke at the test marker, must not actuate --"
  tap_text 'Test Gate A' /sdcard/ui_gate.xml || echo "(tap target missing - counted below)"
  sleep 6
  adb logcat -d -s PalGate:I > "$ART/render_tap_palgate.log" 2>/dev/null || true
  adb logcat -d | grep -iE 'connectGatt|BluetoothGatt|ACTION_3G_OPEN|ACTION_4GBT_OPEN|ACTION_BT_OPEN|writeCharacteristic' > "$ART/render_tap_actuation.log" 2>/dev/null || true
  TAPLOG=0; grep -qE 'SYNTHETIC-DISPATCH|OPEN tapped on car screen' "$ART/render_tap_palgate.log" && TAPLOG=1
  ACT=0; [ -s "$ART/render_tap_actuation.log" ] && ACT=1
  if [ "$TAPLOG" = "1" ] && [ "$ACT" = "0" ]; then
    echo "palgate_render_open_blocked,gate,pass,\"car OPEN tap reached app and was choked by the TEST marker (SYNTHETIC-DISPATCH logged); zero actuation markers\"" >> "$MATRIX"
    echo "PHASE B1 tap OK: blocked, nothing actuated"
  else
    echo "palgate_render_open_blocked,gate,fail,\"taplog=$TAPLOG actuation=$ACT (render_tap_palgate.log / render_tap_actuation.log)\"" >> "$MATRIX"
    echo "::error::PHASE B1 tap FAILED or ACTUATION MARKERS PRESENT"
    FAIL=1
  fi
fi

echo "== Phase B2: overflow (7 synthetic gates -> host limit shown, cap disclosed) =="
ROOT=0; [ "$(adb shell id -u 2>/dev/null | tr -d '\r')" = "0" ] && ROOT=1
if [ "$ROOT" != "1" ]; then
  echo "palgate_render_overflow,gate,blocked,\"adb not root on this device: cannot seed runtime gates - NOT attempted (not a pass)\"" >> "$MATRIX"
  echo "PHASE B2 NOT ATTEMPTED (no root adb)"
else
  python3 - <<'PYSEED'
import json, subprocess
gates = [{"id": f"TEST00000{i}", "name": f"Test Gate {chr(64+i)} (SYNTHETIC)",
          "lat": 32.0853 + i*0.001, "lng": 34.7818 - i*0.001,
          "key": "00112233445566778899AABBCCDDEEFF"} for i in range(3, 8)]  # C..G
payload = json.dumps(gates)
esc = payload.replace("&", "&amp;").replace("<", "&lt;").replace('"', "&quot;")
xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
       "<map>\n    <string name=\"runtime_gates\">" + esc + "</string>\n</map>\n")
open("/tmp/seed_prefs.xml", "w").write(xml)
PYSEED
  adb shell am force-stop com.palgate.carapp >/dev/null 2>&1 || true
  adb push /tmp/seed_prefs.xml /data/local/tmp/seed_prefs.xml >/dev/null 2>&1
  OWNER=$(adb shell stat -c '%u:%g' /data/data/com.palgate.carapp 2>/dev/null | tr -d '\r')
  adb shell "mkdir -p /data/data/com.palgate.carapp/shared_prefs && cp /data/local/tmp/seed_prefs.xml /data/data/com.palgate.carapp/shared_prefs/palgate_car.xml && chown $OWNER /data/data/com.palgate.carapp/shared_prefs/palgate_car.xml && chmod 660 /data/data/com.palgate.carapp/shared_prefs/palgate_car.xml" \
    && echo "seeded 5 synthetic runtime gates (7 total with bundled fixtures)"
  adb shell am force-stop com.palgate.carapp >/dev/null 2>&1 || true
  adb logcat -c || true
  if ! open_preview; then
    echo "palgate_render_overflow,gate,fail,\"preview flow failed after seeding\"" >> "$MATRIX"
    echo "::error::PHASE B2 FAILED: could not reach template after seeding"
    FAIL=1
  else
    dump_gate_screen "overflow"
    UI="$ART/render_ui_gate_overflow.xml"
    ITEMS=$(grep -o 'text="Test Gate [A-Z] (SYNTHETIC)' "$UI" 2>/dev/null | sort -u | wc -l)
    HIDDEN=1; grep -q 'Test Gate G (SYNTHETIC)' "$UI" && HIDDEN=0
    DISCLOSED=0; grep -qE 'of 7\)' "$UI" && DISCLOSED=1
    echo "visible items: $ITEMS (expect <=6), 7th hidden: $HIDDEN, cap disclosed: $DISCLOSED"
    if [ "$ITEMS" -ge 1 ] && [ "$ITEMS" -le 6 ] && [ "$HIDDEN" = "1" ] && [ "$DISCLOSED" = "1" ]; then
      echo "palgate_render_overflow,gate,pass,\"7 gates -> $ITEMS shown (host limit respected, template NOT rejected), 7th hidden, cap disclosed in title (AARE third-party host): render_ui_gate_overflow.xml + png\"" >> "$MATRIX"
      echo "PHASE B2 OK"
    else
      echo "palgate_render_overflow,gate,fail,\"items=$ITEMS hidden=$HIDDEN disclosed=$DISCLOSED: $UI\"" >> "$MATRIX"
      echo "::error::PHASE B2 FAILED"
      FAIL=1
    fi
  fi
fi

adb logcat -d -s PalGate:I > "$ART/render_palgate.log" 2>/dev/null || true
adb logcat -d | grep -iE 'carapp|CarApp|HostValidator|GateCar|GateGrid|palgate' > "$ART/render_carapp.log" 2>/dev/null || true
mask_logs "$ART/render_palgate.log" "$ART/render_carapp.log" "$ART/render_tap_palgate.log" "$ART"/render_ui_*.xml

cat "$MATRIX"
[ "$FAIL" = "0" ] && echo "RENDER PROOFS OK (AARE local preview; third-party host, NOT stock AA)" || exit 1
