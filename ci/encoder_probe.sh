#!/usr/bin/env bash
# Standalone encoder probe runner: installs EncoderProbe.apk, runs all
# variants, collects RESULT lines. Fails only when the probe itself could not
# run (no PROBE-DONE) - a zero-frame RESULT is data, not a harness failure.
set -uo pipefail
ART=artifacts; mkdir -p "$ART"
adb install -r EncoderProbe.apk || exit 1
adb logcat -c || true
adb shell am start -n com.palgate.encoderprobe/.ProbeActivity >/dev/null
DONE=0
for i in $(seq 1 50); do
  sleep 3
  if adb shell 'logcat -d -s EncProbe:I' 2>/dev/null | grep -q 'PROBE-DONE'; then DONE=1; break; fi
done
adb logcat -d -s EncProbe:I > "$ART/encoder_probe.log" 2>/dev/null || true
adb pull /sdcard/Android/data/com.palgate.encoderprobe/files/probe_results.txt "$ART/probe_results.txt" >/dev/null 2>&1 || true
echo "-- encoder probe results --"
grep -E 'RESULT|PROBE' "$ART/encoder_probe.log" || true
cat "$ART/probe_results.txt" 2>/dev/null || true
if [ "$DONE" != "1" ]; then
  echo "::error::encoder probe did not finish (no PROBE-DONE) - invalid run"
  exit 1
fi
