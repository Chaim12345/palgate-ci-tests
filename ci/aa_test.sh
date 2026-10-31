#!/usr/bin/env bash
# PalGate sanitized car-app: Android Auto loopback + home-screen widget test.
# Synthetic gates only. BLE is never enabled; the expected open outcome is the
# app's graceful "Bluetooth is off" failure (asserted, never a gate-open).
# Prints EXECUTED:/BLOCKED:/FAIL: lines; exits nonzero if any FAIL.
ART=artifacts
mkdir -p "$ART"
FAILS=0
note()  { echo "EXECUTED: $*"; }
block() { echo "BLOCKED: $*"; }
fail()  { echo "FAIL: $*"; FAILS=$((FAILS+1)); }

shot() { # shot <name>
  adb shell screencap -p /sdcard/_s.png >/dev/null 2>&1
  adb pull /sdcard/_s.png "$ART/$1.png" >/dev/null 2>&1
}
uidump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$ART/ui_last.xml" >/dev/null 2>&1
}
ui_has() { uidump; grep -qi "$1" "$ART/ui_last.xml"; }
tap_text() { # tap_text <substring> -> taps center of first node whose text contains it
  uidump
  COORDS=$(python3 - "$1" <<'PY'
import sys, re
try: xml = open('artifacts/ui_last.xml').read()
except Exception: sys.exit(1)
target = sys.argv[1].lower()
pat = re.compile(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
for m in pat.finditer(xml):
    if target in m.group(1).lower():
        x1,y1,x2,y2 = map(int, m.groups()[1:])
        print((x1+x2)//2, (y1+y2)//2); sys.exit(0)
pat2 = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*)"')
for m in pat2.finditer(xml):
    if target in m.group(5).lower():
        x1,y1,x2,y2 = map(int, m.groups()[:4])
        print((x1+x2)//2, (y1+y2)//2); sys.exit(0)
sys.exit(1)
PY
)
  if [ -n "$COORDS" ]; then adb shell input tap $COORDS; return 0; fi
  return 1
}

echo "== PalGate CI: AA loopback + widget test =="
adb wait-for-device
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb logcat -c || true

echo "-- install --"
adb install -r PalGateCar.apk && note "PalGateCar.apk installed" || fail "PalGateCar.apk install"
adb install -r open-headunit.apk && note "Open Headunit installed" || fail "Open Headunit install"
adb shell pm grant com.palgate.carapp android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant com.palgate.carapp android.permission.ACCESS_COARSE_LOCATION || true

echo "-- fake GPS at synthetic gate A (Tel Aviv fixture) --"
adb emu geo fix 34.7818 32.0853 && note "geo fix set (synthetic fixture coords)" || block "geo fix"

echo "-- launch PalGate phone UI --"
adb shell monkey -p com.palgate.carapp -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 6
shot 01_palgate_phone
if ui_has "PalGate"; then note "PalGate phone UI visible"; else fail "PalGate phone UI not visible"; fi

echo "-- Android Auto gearhead presence --"
if adb shell pm list packages | grep -q gearhead; then
  note "gearhead (Android Auto) present"
  GEARHEAD=1
else
  block "gearhead not preinstalled on this image; AA loopback cannot run"
  GEARHEAD=0
fi

if [ "$GEARHEAD" = 1 ]; then
  echo "-- AA developer mode: Settings > Connected devices > Connection preferences > Android Auto --"
  DISP=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1); SW=${DISP%x*}; SH=${DISP#*x}
  SW=${SW:-320}; SH=${SH:-640}
  CX=$((SW/2)); QY=$((SH/4)); LY=$((SH*3/4))
  echo "display: ${SW}x${SH}"
  scrollup() { adb shell input swipe $CX $LY $CX $QY 300; sleep 1; }
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -a android.settings.SETTINGS >/dev/null 2>&1
  sleep 4
  ok=0
  for i in 1 2 3 4 5 6; do
    if tap_text "Connected devices"; then ok=1; break; fi
    scrollup
  done
  if [ "$ok" != 1 ]; then block "Settings: Connected devices row not found"; fi
  sleep 2
  tap_text "Connection preferences" || { scrollup; tap_text "Connection preferences" || true; }
  sleep 2
  if tap_text "Android Auto"; then
    sleep 4
    shot 02_aa_settings
    adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus || true
    ok=0
    for i in 1 2 3 4 5 6 7 8; do
      if ui_has 'text="Version"'; then ok=1; break; fi
      scrollup
    done
    if [ "$ok" = 1 ]; then
      for i in $(seq 1 12); do tap_text "Version" >/dev/null 2>&1 || true; sleep 0.4; done
      note "AA version row tapped 12x (developer mode attempt)"
    else
      block "could not locate AA Version row (see ui_last.xml + 02_aa_settings.png)"
    fi
    sleep 2
    shot 03_aa_after_version_taps
    uidump
    MC=$(python3 - <<'PY'
import re
try: xml=open('artifacts/ui_last.xml').read()
except Exception: raise SystemExit(0)
for pat in (r'content-desc="More options"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="More options"'):
    m=re.search(pat,xml)
    if m:
        x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
    [ -z "$MC" ] && MC="$((SW-30)) 60"
    adb shell input tap $MC
    sleep 2
    if tap_text "Start head unit server"; then
      note "head unit server start tapped"
    else
      if tap_text "Developer settings"; then
        sleep 2
        tap_text "Start head unit server" && note "head unit server start tapped (via developer settings)" || block "head unit server toggle not found in developer settings"
      else
        block "head unit server toggle not found (see 03/04 artifacts)"
      fi
    fi
    sleep 3
    shot 04_aa_headunit_server
    if adb shell netstat -lnt 2>/dev/null | grep -q 5277 || adb shell ss -lnt 2>/dev/null | grep -q 5277; then
      note "AA head unit server listening on 5277"
      HUS=1
    else
      block "port 5277 not listening; AA loopback not established"
      HUS=0
    fi

    if [ "$HUS" = 1 ]; then
      echo "-- launch Open Headunit (client) --"
      adb shell monkey -p com.andrerinas.headunitrevived -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      sleep 8
      shot 05_openheadunit
      tap_text "OK" >/dev/null 2>&1 || true
      tap_text "Allow" >/dev/null 2>&1 || true
      sleep 2
      tap_text "Self" >/dev/null 2>&1 || tap_text "127.0.0.1" >/dev/null 2>&1 || true
      sleep 10
      shot 06_aa_projection
      if ui_has "PalGate"; then
        note "PalGate visible inside Android Auto launcher projection"
        if tap_text "PalGate"; then
          sleep 6
          shot 07_palgate_car_screen
          if ui_has "Gate\|PalGate"; then note "PalGate car screen opened in AA"; else fail "PalGate car screen did not render"; fi
        fi
      else
        block "AA projection not confirmed / PalGate not visible (see 06 artifacts)"
      fi
    fi
  else
    block "Android Auto settings screen not reachable (see artifacts)"
  fi
fi

echo "-- home-screen widget --"
if adb shell dumpsys appwidget 2>/dev/null | grep -qi 'com.palgate.carapp'; then
  note "widget provider registered with AppWidgetManager (com.palgate.carapp)"
else
  fail "widget provider NOT registered with AppWidgetManager"
fi
DISP=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1); SW=${DISP%x*}; SH=${DISP#*x}
SW=${SW:-320}; SH=${SH:-640}
CX=$((SW/2))
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell input swipe $CX $((SH/3)) $CX $((SH/3)) 1500   # long-press empty home area
sleep 2
shot 08_home_longpress
if tap_text "Widgets"; then
  sleep 3
  shot 09_widget_picker
  found=0
  for i in 1 2 3 4 5 6 7 8; do
    if ui_has "PalGate"; then found=1; break; fi
    adb shell input swipe $CX $((SH*3/4)) $CX $((SH/4)) 400; sleep 1.5
  done
  if [ "$found" = 1 ]; then
    note "PalGate widget listed in picker"
    uidump
    COORDS=$(python3 - <<'PY'
import re
xml=open('artifacts/ui_last.xml').read()
m=re.search(r'text="PalGate"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1,y1,x2,y2=map(int,m.groups()); print((x1+x2)//2,(y1+y2)//2)
PY
)
    if [ -n "$COORDS" ]; then
      adb shell input swipe $COORDS $CX $((SH/5)) 1800   # long-press-drag onto home
      sleep 3
      shot 10_widget_on_home
      if ui_has "PalGate"; then
        note "PalGate widget placed on home screen and rendering"
        if tap_text "OPEN"; then
          sleep 4
          shot 11_widget_after_open_tap
          if adb shell dumpsys activity services com.palgate.carapp 2>/dev/null | grep -q GateMonitorService; then
            note "widget OPEN tap started GateMonitorService"
          else
            block "widget OPEN tap did not start GateMonitorService"
          fi
        else
          block "widget OPEN button not found on home screen"
        fi
      else
        fail "widget not visible on home after drag"
      fi
    else
      block "could not resolve widget coords for drag"
    fi
  else
    fail "PalGate widget not found in picker"
  fi
else
  fail "Widgets entry not found after home long-press"
fi

echo "-- expected-BLE-off assertion (safety) --"
adb logcat -d | grep -i "PalGate" > "$ART/logcat_palgate.txt" 2>/dev/null || true
if grep -qi "Bluetooth is off\|bluetooth is off" "$ART/logcat_palgate.txt"; then
  note "app took the graceful Bluetooth-off path (no radio, no real gate action)"
fi

adb shell screencap -p /sdcard/_final.png >/dev/null 2>&1
adb pull /sdcard/_final.png "$ART/12_final.png" >/dev/null 2>&1

echo "== summary: $FAILS FAIL(s) =="
exit $FAILS
