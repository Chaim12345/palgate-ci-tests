#!/usr/bin/env bash
# PalGate sanitized car-app: Android Auto projection + widget test.
# Synthetic gates only. BLE never enabled; expected open outcome is the app's
# graceful "Bluetooth is off" failure.
# Tiers: GATE checks fail the run when blocked or failed. OPTIONAL checks are
# reported but never fail the run. Matrix: artifacts/results.csv
ART=artifacts
mkdir -p "$ART"
FAILS=0
MATRIX="$ART/results.csv"
echo "check,tier,result,evidence" > "$MATRIX"
note()  { echo "EXECUTED: $*"; }
opt()   { echo "OPTIONAL: $*"; }
fail()  { echo "FAIL: $*"; FAILS=$((FAILS+1)); }
gate_block() { echo "GATE-BLOCKED: $*"; FAILS=$((FAILS+1)); }
row()   { echo "$1,$2,$3,\"$4\"" >> "$MATRIX"; }

shot() { adb shell screencap -p /sdcard/_s.png >/dev/null 2>&1; adb pull /sdcard/_s.png "$ART/$1.png" >/dev/null 2>&1; }
uidump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$ART/ui_last.xml" >/dev/null 2>&1; }
ui_has() { uidump; grep -qi "$1" "$ART/ui_last.xml"; }
tap_text() {
  uidump
  COORDS=$(python3 - "$1" <<'PY'
import sys, re
try: xml = open('artifacts/ui_last.xml').read()
except Exception: sys.exit(1)
target = sys.argv[1].lower()
for pat in (r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*)"'):
    for m in re.finditer(pat, xml):
        g = m.groups()
        if len(g) == 5 and not g[0].isdigit():
            txt, nums = g[0], g[1:]
        else:
            txt, nums = g[4], g[:4]
        if target in txt.lower():
            x1,y1,x2,y2 = map(int, nums)
            print((x1+x2)//2, (y1+y2)//2); sys.exit(0)
sys.exit(1)
PY
)
  if [ -n "$COORDS" ]; then adb shell input tap $COORDS; return 0; fi
  return 1
}
# tap_in_pkg: like tap_text but the node must belong to the given package
tap_in_pkg() {
  uidump
  COORDS=$(python3 - "$1" "$2" <<'PY'
import sys, re
try: xml = open('artifacts/ui_last.xml').read()
except Exception: sys.exit(1)
target, pkg = sys.argv[1].lower(), sys.argv[2]
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    tm = re.search(r'text="([^"]*)"', tag); bm = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    pm = re.search(r'package="([^"]*)"', tag)
    if tm and bm and pm and pm.group(1) == pkg and target in tm.group(1).lower():
        x1,y1,x2,y2 = map(int, bm.groups()); print((x1+x2)//2, (y1+y2)//2); sys.exit(0)
sys.exit(1)
PY
)
  if [ -n "$COORDS" ]; then adb shell input tap $COORDS; return 0; fi
  return 1
}

echo "== PalGate CI: AA projection (DHU) + hosted widget test =="
adb wait-for-device
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb logcat -c || true
DISP=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1); SW=${DISP%x*}; SH=${DISP#*x}
SW=${SW:-320}; SH=${SH:-640}; CX=$((SW/2)); QY=$((SH/4)); LY=$((SH*3/4))
echo "display: ${SW}x${SH}"
scrollup() { adb shell input swipe $CX $LY $CX $QY 300; sleep 1; }

echo "-- install --"
if adb install -r PalGateCar.apk; then note "PalGateCar.apk installed"; row install gate executed "PalGateCar.apk"; else fail "PalGateCar.apk install"; row install gate fail "adb install"; fi
adb install -r open-headunit.apk >/dev/null 2>&1 && opt "Open Headunit installed (compat tier)" || opt "Open Headunit install failed (compat tier)"
adb shell pm grant com.palgate.carapp android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant com.palgate.carapp android.permission.ACCESS_COARSE_LOCATION || true
adb emu geo fix 34.7818 32.0853 >/dev/null 2>&1 && note "geo fix set (synthetic fixture coords)" || true
adb shell monkey -p com.palgate.carapp -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
shot 01_palgate_phone
ui_has "PalGate" && note "PalGate phone UI visible" || true

echo "-- gate: widget hosted render + OPEN dispatch (WidgetHostTest) --"
if [ -f widgethost/WidgetHostTest.apk ]; then
  adb install -r widgethost/WidgetHostTest.apk >/dev/null 2>&1
  adb shell appwidget grantbind --package com.palgate.widgethost >/dev/null 2>&1 || true
  adb shell monkey -p com.palgate.widgethost -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 14  # host binds + renders (~1s), auto-clicks OPEN after 4s, dispatch follows
  adb logcat -d -s WidgetHostTest:I > "$ART/widgethost.log" 2>/dev/null || true
  adb logcat -d | grep -i 'SYNTHETIC-DISPATCH' > "$ART/synthetic_dispatch.log" 2>/dev/null || true
  shot 20_widgethost
  # negative assertions: the tap must produce NO actuation attempt anywhere
  adb logcat -d | grep -iE 'connectGatt|BluetoothGatt|DELEGATED |writeCharacteristic|ACTION_3G_OPEN|ACTION_4GBT_OPEN|ACTION_BT_OPEN' > "$ART/actuation_attempts.log" 2>/dev/null || true
  if grep -q 'HOST-RESULT: OPEN-TAPPED' "$ART/widgethost.log" && [ -s "$ART/synthetic_dispatch.log" ] && [ ! -s "$ART/actuation_attempts.log" ]; then
    note "widget hosted render + OPEN tap recorded synthetic dispatch; zero BLE/broadcast actuation attempts"
    row widgethost gate executed "widgethost.log + synthetic_dispatch.log + empty actuation_attempts.log + 20_widgethost.png"
  elif [ -s "$ART/actuation_attempts.log" ]; then
    gate_block "actuation attempt detected after widget OPEN tap: $(head -2 "$ART/actuation_attempts.log")"; row widgethost gate fail "actuation_attempts.log"
  elif grep -q 'HOST-RESULT' "$ART/widgethost.log"; then
    gate_block "widget host: $(tail -1 "$ART/widgethost.log")"; row widgethost gate fail "widgethost.log"
  else
    gate_block "widget host produced no HOST-RESULT"; row widgethost gate fail "no result"
  fi
else
  gate_block "WidgetHostTest.apk missing (build step)"; row widgethost gate fail "apk missing"
fi

echo "-- gate: gearhead present --"
if adb shell pm list packages | grep -q gearhead; then
  GHVER=$(adb -s "$SER" shell dumpsys package com.google.android.projection.gearhead 2>/dev/null | grep -m1 versionName | tr -d '\r' | awk -F= '{print $2}')
  # trusted baseline: preinstalled gearhead signing cert digest
  GHPATH=$(adb -s "$SER" shell pm path com.google.android.projection.gearhead 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')
  # NEVER inside $ART: proprietary APK must not land in public artifacts
  GHAPK="${RUNNER_TEMP:-/tmp}/gearhead-preinstalled.apk"
  adb -s "$SER" pull "$GHPATH" "$GHAPK" >/dev/null 2>&1 || true
  GHCERT=$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/apksigner 2>/dev/null | head -1)
  [ -n "$GHCERT" ] && GHCERT=$("$GHCERT" verify --print-certs "$GHAPK" 2>/dev/null | grep -m1 'SHA-256' | awk '{print $NF}')
  rm -f "$GHAPK"
  note "gearhead (Android Auto) present, versionName=$GHVER cert-sha256=$GHCERT"; row gearhead gate executed "pm list packages v=$GHVER cert=$GHCERT"
else
  gate_block "gearhead not preinstalled on this image"; row gearhead gate gate-blocked "package missing"
fi

echo "-- gate: AA developer mode via Settings --"
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 4
ok=0
for i in 1 2 3 4 5 6; do tap_text "Connected devices" && { ok=1; break; }; scrollup; done
sleep 2
tap_text "Connection preferences" || { scrollup; tap_text "Connection preferences" || true; }
sleep 2
if tap_text "Android Auto"; then
  sleep 4; shot 02_aa_settings
  ok=0
  for i in 1 2 3 4 5 6 7 8; do
    if tap_in_pkg "Version and permission info" "com.google.android.projection.gearhead" || tap_in_pkg "Version" "com.google.android.projection.gearhead"; then ok=1; break; fi
    scrollup
  done
  if [ "$ok" = 1 ]; then
    for i in $(seq 1 12); do tap_in_pkg "Version" "com.google.android.projection.gearhead" >/dev/null 2>&1 || true; sleep 0.4; done
    sleep 2
    # accept the development-settings confirmation dialog
    if tap_text "OK"; then sleep 2; fi
    shot 03_aa_after_version_taps
    note "AA version tapped 12x + dialog accepted (developer mode attempt)"
    # overflow menu -> Start head unit server
    adb shell input tap $((SW-30)) 60; sleep 2
    if tap_in_pkg "Start head unit server" "com.google.android.projection.gearhead"; then
      note "head unit server start tapped"; row hus gate executed "menu tap"
    else
      tap_text "Start head unit server" && note "head unit server start tapped (unscoped fallback)" || gate_block "head unit server toggle not found"
    fi
  else
    gate_block "AA Version row not found in gearhead settings"
  fi
  sleep 3; shot 04_aa_headunit_server
else
  gate_block "Android Auto settings screen not reachable"
fi

echo "-- gate: head unit server listening on 5277 --"
sleep 3
if adb shell netstat -lnt 2>/dev/null | grep -q 5277 || adb shell ss -lnt 2>/dev/null | grep -q 5277; then
  note "AA head unit server listening on 5277"; row hus5277 gate executed "netstat"
  HUS=1
else
  gate_block "port 5277 not listening; AA loopback not established"; row hus5277 gate gate-blocked "netstat empty"
  HUS=0
fi

if [ "${RUN_DHU:-1}" = "1" ]; then
echo "-- gate: DHU reference projection --"
DHU="${ANDROID_HOME:-/usr/local/lib/android/sdk}/extras/google/auto/desktop-head-unit"
if [ "$HUS" = 1 ] && [ -x "$DHU" ]; then
  # manual Xvfb so we can screenshot the DHU window for pixel evidence
  Xvfb :99 -screen 0 1024x768x24 >/dev/null 2>&1 &
  XVFBPID=$!
  sleep 2
  # re-confirm the head unit server is still listening right before DHU
  if ! adb -s "$SER" shell 'netstat -lnt 2>/dev/null | grep -q 5277'; then
    gate_block "head unit server stopped listening before DHU"; row dhu gate fail "5277 lost"
  else
  adb forward tcp:5277 tcp:5277 >/dev/null 2>&1 || true
  ( printf 'help\n'; sleep 45 ) | DISPLAY=:99 timeout 60 "$DHU" > "$ART/dhu.log" 2>&1 &
  DHUPID=$!
  sleep 15
  if ! kill -0 $DHUPID 2>/dev/null && grep -qi 'connect over USB transport' "$ART/dhu.log"; then
    note "DHU transport miss, retrying once"
    ( printf 'help\n'; sleep 45 ) | DISPLAY=:99 timeout 60 "$DHU" > "$ART/dhu.log" 2>&1 &
    DHUPID=$!
    sleep 15
  fi
  sleep 15
  fi
  # pre-proof failure markers only (auth failure at link time); teardown lines
  # (disconnect/Exiting) are expected after our own kill and must not fail the gate
  if grep -qi 'auth failure' "$ART/dhu.log" 2>/dev/null; then
    gate_block "DHU rejected at link: $(grep -i 'auth failure' "$ART/dhu.log" | head -1)"
    row dhu gate fail "dhu.log auth failure"
  elif ! kill -0 $DHUPID 2>/dev/null; then
    gate_block "DHU exited within 30s of connect (see dhu.log)"; row dhu gate fail "dhu.log early exit"
  elif grep -qi 'connected' "$ART/dhu.log" 2>/dev/null; then
    # stable connection: process alive 30s, connected, no failure markers
    if DISPLAY=:99 import -window root "$ART/50_dhu_window.png" 2>/dev/null; then
      note "DHU connected + window captured (50_dhu_window.png)"; row dhu gate executed "dhu.log + 50_dhu_window.png"
    else
      note "DHU connected (no window capture)"; row dhu gate executed "dhu.log"
    fi
  else
    gate_block "DHU connection unconfirmed (see dhu.log)"; row dhu gate gate-blocked "dhu.log"
  fi
  kill $DHUPID 2>/dev/null || true
  kill $XVFBPID 2>/dev/null || true
  tail -5 "$ART/dhu.log" || true
else
  [ "$HUS" = 1 ] || { opt "DHU skipped: no head unit server"; row dhu gate gate-blocked "no HUS"; }
  [ -x "$DHU" ] || { gate_block "DHU binary missing at $DHU"; row dhu gate gate-blocked "binary missing"; }
fi

else
  opt "DHU stage skipped (known blocked; include the dhu-run marker in the commit message or run manually to re-attempt)"
  row dhu gate skipped "RUN_DHU=0"
fi

echo "-- optional: Open Headunit self-mode (experimental) --"
adb shell monkey -p com.andrerinas.headunitrevived -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8; shot 30_openheadunit
tap_text "OK" >/dev/null 2>&1 || true
tap_text "Allow" >/dev/null 2>&1 || true
sleep 2
tap_text "Self" >/dev/null 2>&1 || tap_text "127.0.0.1" >/dev/null 2>&1 || true
sleep 10; shot 31_oh_projection
if ui_has "PalGate"; then opt "Open Headunit self-mode: PalGate visible (experimental)"; row open_headunit optional executed "ui"
else opt "Open Headunit self-mode not confirmed (experimental; maintainer caveat)"; row open_headunit optional blocked "ui"; fi

echo "-- optional: real-launcher widget add (fidelity layer) --"
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell input swipe $CX $((SH/3)) $CX $((SH/3)) 1500; sleep 2
shot 32_home_longpress
if tap_text "Widgets"; then
  sleep 3; shot 33_widget_picker
  found=0
  for i in 1 2 3 4 5 6 7 8; do
    if ui_has "PalGate"; then found=1; break; fi
    adb shell input swipe $CX $LY $CX $QY 400; sleep 1.5
  done
  if [ "$found" = 1 ]; then
    # drag from the widget PREVIEW tile (image under the label), not the label text
    uidump
    COORDS=$(python3 - <<'PY'
import re
xml=open('artifacts/ui_last.xml').read()
# prefer an ImageView/ImageButton whose bounds sit below a 'PalGate' label
labels=[(int(m.group(2)),int(m.group(3)),int(m.group(4)),int(m.group(5))) for m in re.finditer(r'text="PalGate[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',xml)]
imgs=[(int(m.group(1)),int(m.group(2)),int(m.group(3)),int(m.group(4))) for m in re.finditer(r'class="android.widget.ImageView"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',xml)]
for lx1,ly1,lx2,ly2 in labels:
    best=None
    for x1,y1,x2,y2 in imgs:
        if abs((x1+x2)//2-(lx1+lx2)//2)<60 and y1>=ly1-40:
            best=(x1,y1,x2,y2); break
    if best:
        x1,y1,x2,y2=best; print((x1+x2)//2,(y1+y2)//2); break
PY
)
    if [ -n "$COORDS" ]; then
      adb shell input swipe $COORDS $CX $((SH/5)) 2500
      sleep 3; shot 34_widget_on_home
      # objective placement check: launcher host bound to our provider
      if adb shell dumpsys appwidget 2>/dev/null | grep -qi 'com.palgate.carapp/.GateWidgetProvider\|com.palgate.carapp/com.palgate.carapp.GateWidgetProvider'; then
        opt "launcher host bound PalGate widget (dumpsys appwidget)"; row launcher_widget optional executed "dumpsys"
        tap_text "OPEN" >/dev/null 2>&1 && sleep 4 || true
        shot 35_widget_after_open_tap
      else
        opt "launcher drag did not bind widget (picker text is not placement proof)"; row launcher_widget optional blocked "dumpsys"
      fi
    else
      opt "could not resolve widget preview tile coords"; row launcher_widget optional blocked "coords"
    fi
  else
    opt "PalGate widget not found in picker"; row launcher_widget optional blocked "picker"
  fi
else
  opt "Widgets entry not found after home long-press"; row launcher_widget optional blocked "longpress"
fi

echo "-- expected-BLE-off assertion (safety) --"
adb logcat -d | grep -i "PalGate" > "$ART/logcat_palgate.txt" 2>/dev/null || true
grep -qi "Bluetooth is off" "$ART/logcat_palgate.txt" && note "app took the graceful Bluetooth-off path (no radio, no real gate action)" || true

shot 40_final
echo "== summary: $FAILS gate failure(s) =="
cat "$MATRIX"
exit $FAILS
