#!/usr/bin/env bash
# AARE host lane (third-party CAL host, NOT stock AA): install the PalGateCar
# TEST apk + AARE debug apk, bring up AARE's head-unit server, connect AARE's
# desktop head unit headless, and capture render pixels. First iteration:
# render proof only (headless PNGs); tap scripts get added from inspected
# coordinates, not guessed blind.
set -uo pipefail
ART=artifacts; mkdir -p "$ART" "$ART/aare"
MATRIX="$ART/results.csv"
echo "check,tier,result,evidence" > "$MATRIX"
AARE_DIR="${AARE_DIR:-$HOME/aare}"

echo "-- aare lane: install apks --"
adb install -r PalGateCar.apk && echo "PalGateCar (test build) installed"
adb install -r "$AARE_DIR/app/build/outputs/apk/debug/app-debug.apk" && echo "AARE debug installed"

# Pre-grant PalGate's runtime perms so its own dialogs don't cover the session.
adb shell pm grant com.palgate.carapp android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant com.palgate.carapp android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1 || true
adb shell pm grant com.palgate.carapp android.permission.NEARBY_WIFI_DEVICES >/dev/null 2>&1 || true
adb shell pm grant com.palgate.carapp android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1 || true
adb shell pm grant com.palgate.carapp android.permission.BLUETOOTH_SCAN >/dev/null 2>&1 || true
adb emu geo fix 34.7818 32.0853 >/dev/null 2>&1 || true

# Pre-grant AARE's first-run permissions (isolated emulator, no user data) so
# its onboarding dialogs don't block the head-unit server.
for p in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS NEARBY_WIFI_DEVICES CALL_PHONE ANSWER_PHONE_CALLS READ_CALL_LOG READ_CONTACTS READ_PHONE_STATE READ_CALENDAR; do
  adb shell pm grant net.prototux.aare android.permission.$p >/dev/null 2>&1 || true
done

echo "-- aare lane: head-unit server --"
adb forward tcp:5277 localabstract:aare_hu
adb shell am start -n net.prototux.aare/.ui.MainActivity --ez hu_server true >/dev/null 2>&1 || true
for i in $(seq 1 15); do
  adb shell cat /proc/net/unix 2>/dev/null | grep -q '@aare_hu' && break
  sleep 1
done
adb shell cat /proc/net/unix | grep -q '@aare_hu' && echo "AARE head-unit server listening" || echo "AARE head-unit server NOT listening yet"

echo "-- aare lane: headless head unit --"
adb logcat -c || true

# DIAGNOSTIC (temporary): wrap ffmpeg so its stdin (the phone's actual H.264
# bytes) is captured per invocation. Only the decode invocation (-i pipe:0)
# gets the tee; -version/-h checks exec the real binary directly (tee would
# deadlock on the never-closed Java stdin pipe). Self-tested immediately.
if [ -x /usr/local/bin/ffmpeg ] && [ ! -x /usr/local/bin/ffmpeg.real ]; then
  sudo mv /usr/local/bin/ffmpeg /usr/local/bin/ffmpeg.real || echo "MV FAILED"
fi
ls -la /usr/local/bin/ffmpeg* || true
printf '#!/bin/bash\nfor a in "$@"; do\n  if [ "$a" = "pipe:0" ]; then\n    tee "$PWD/ffstdin.$$.h264" | /usr/local/bin/ffmpeg.real "$@"\n    exit $?\n  fi\ndone\nexec /usr/local/bin/ffmpeg.real "$@"\n' | sudo tee /usr/local/bin/ffmpeg >/dev/null
sudo chmod +x /usr/local/bin/ffmpeg
echo "wrapper content:"; cat /usr/local/bin/ffmpeg
echo "wrapper self-test:"; ffmpeg -version | head -1; echo "self-test exit: $?"
HU="$AARE_DIR/headunit/build/install/aare-headunit/bin/aare-headunit"
timeout 240 "$HU" --serial "$ANDROID_SERIAL" --headless "$ART/aare" > "$ART/aare_hu.log" 2>&1 &
HUPID=$!

# Mid-session pixel evidence: once the HU reports the video stream starting,
# capture the phone's own screens - display 2 is the "AARE car" virtual display
# (what the encoder should be seeing), display 0 is the phone screen.
( for i in $(seq 1 30); do
    sleep 8
    if grep -q 'video started' "$ART/aare_hu.log" 2>/dev/null; then
      sleep 15
      adb shell screencap -d 2 -p /sdcard/aare_car2.png 2>/dev/null && \
        adb pull /sdcard/aare_car2.png "$ART/aare_car_display.png" >/dev/null 2>&1 || true
      adb shell screencap -p /sdcard/aare_ph0.png 2>/dev/null && \
        adb pull /sdcard/aare_ph0.png "$ART/aare_phone_screen.png" >/dev/null 2>&1 || true
      exit 0
    fi
  done ) &

# The first connection needs a Trust tap on the phone (AARE cert model,
# docs/certificates.md); the HU retries, so watch for the prompt and accept.
for i in $(seq 1 20); do
  sleep 5
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | grep -qi "trust" || continue
  echo "trust prompt detected, tapping Trust"
  python3 - <<'PY'
import subprocess, re
xml = subprocess.run(["adb","shell","cat","/sdcard/ui.xml"], capture_output=True, text=True).stdout
best = None
for m in re.finditer(r'text="([^"]*[Tt]rust[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    best = m
for m in re.finditer(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="([^"]*[Tt]rust[^"]*)"', xml):
    if best is None: best = m
if best:
    g = best.groups()
    if len(g) == 5 and not g[0].isdigit():
        nums = g[1:]
    else:
        nums = g[:4]
    x1,y1,x2,y2 = map(int, nums)
    subprocess.run(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)])
    print("tapped Trust at", (x1+x2)//2, (y1+y2)//2)
else:
    print("trust text found but no bounds parsed")
PY
done
wait $HUPID || true
mv ffstdin.*.h264 "$ART/" 2>/dev/null || true
ls -la "$ART"/ffstdin.*.h264 2>/dev/null || echo "no ffstdin captures"
ls -la "$ART/aare" 2>/dev/null | head

adb logcat -d -s PalGate:I > "$ART/aare_palgate.log" 2>/dev/null || true
# Phone-side evidence: AARE's own encoder/session logs (VideoEncoder logs its
# codec choice and every rejected configure). Filtered, 40-hex masked below.
adb logcat -d | grep -E 'Aare|C2Soft|CCodec|MediaCodec|BufferQueue|SurfaceFlinger|gralloc' > "$ART/aare_phone.log" 2>/dev/null || true
adb logcat -d > "$ART/aare_phone_full.log" 2>/dev/null || true
adb logcat -d | grep -iE 'connectGatt|BluetoothGatt|DELEGATED |writeCharacteristic|ACTION_3G_OPEN|ACTION_4GBT_OPEN|ACTION_BT_OPEN' > "$ART/aare_actuation.log" 2>/dev/null || true

# The artifact secret scan patterns-match 40-hex blobs; third-party headunit
# logs may contain session/cert material. Mask 40-hex in the untrusted-source
# logs (evidence value preserved; everything else stays verbatim).
sed -i -E 's/\b[0-9a-fA-F]{40}\b/[hex40]/g' "$ART/aare_hu.log" "$ART/aare_actuation.log" "$ART/aare_phone.log" "$ART/aare_phone_full.log" 2>/dev/null || true

PNG=$(ls "$ART"/aare/*.png 2>/dev/null | head -1)
if [ -n "$PNG" ] && [ ! -s "$ART/aare_actuation.log" ]; then
  echo "[optional] AARE head unit rendered frames: $(ls "$ART"/aare/*.png | wc -l) snapshot(s) (third-party CAL host, not stock AA)"
  echo "aare_host,optional,executed,\"$PNG + aare_hu.log\"" >> "$MATRIX"
else
  echo "[optional] AARE head unit produced no frames (see aare_hu.log)"
  echo "aare_host,optional,blocked,\"aare_hu.log\"" >> "$MATRIX"
fi
