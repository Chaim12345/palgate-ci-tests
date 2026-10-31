# Testing PalGate Car on Android Auto

Three ways to run the car app (GateCarAppService + MapScreen) under a real
Android Auto host. All use synthetic gates only; BLE stays off, so the only
expected open outcome is the graceful "Bluetooth is off" path.

## 1. CI (emulator + head-unit loopback) — recommended

`.github/workflows/ci.yml` builds the APK (aapt2 + ecj + d8, no Gradle),
boots an API-30 emulator with KVM, installs Open Headunit 3.5.0 (sha256
pinned), enables Android Auto developer mode, starts the head unit server on
port 5277, connects the loopback client, launches the PalGate car app inside
the AA projection, and exercises the home-screen widget. It fails the run on
any `FATAL EXCEPTION` in `com.palgate.carapp` and uploads screenshots,
uiautomator dumps, `logcat_full.txt`, `services.txt` and `ps.txt` as the
`aa-widget-artifacts` artifact.

Run it with:

```bash
gh workflow run ci.yml            # or: Actions tab -> Run workflow
# watch:
gh run watch --exit-status
# artifacts:
gh run download <run-id> -n aa-widget-artifacts
```

## 2. Local emulator loopback (same recipe as CI)

```bash
export ANDROID_HOME=~/android-sdk
python3 fetch_deps.py && python3 extract_cp.py && ./build.sh   # -> PalGateCar.apk

# boot an emulator (needs KVM)
$ANDROID_HOME/emulator/emulator -avd Pixel_6_API_30 -no-window -gpu swiftshader_indirect &
adb wait-for-device

# install + permissions + fake GPS at synthetic gate A
adb install -r PalGateCar.apk
curl -sSL -o open-headunit.apk https://github.com/andreknieriem/open-headunit/releases/download/v.3.5.0/com.andrerinas.headunitrevived_3.5.0.apk
adb install -r open-headunit.apk
adb shell pm grant com.palgate.carapp android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.palgate.carapp android.permission.ACCESS_COARSE_LOCATION
adb emu geo fix 34.7818 32.0853

# run the full AA + widget test script (same one CI runs)
./ci/aa_test.sh
```

## 3. Desktop Head Unit (DHU) against a real phone

```bash
# on the phone: Settings > Connected devices > Connection preferences >
# Android Auto > tap Version 12x (developer mode) > Start head unit server
adb forward tcp:5277 tcp:5277
# on the desktop: download DHU from https://developer.android.com/studio/preview/features#dhu
./dhu start   # connects to 127.0.0.1:5277
# in the DHU window: open the app launcher, tap "PalGate Car"
# watch the phone: adb logcat | grep -i palgate
```

The car app must also be allowed as an unknown source in AA developer settings
for sideloaded builds.

## What "works on phone, crashes on AA" meant here

The phone UI (PhoneActivity) never loads `androidx.car.app` classes, but the
AA host binds `GateCarAppService` immediately, which loads `CarAppBinder` /
`AppInfo` / `ConstraintManager` — all of which read `androidx.car.app.R`
(library version string, template content limits). The no-Gradle build linked
only the app's own `res/`, so `androidx.car.app.R` was missing from the dex
and the resources missing from `resources.arsc`: instant `NoClassDefFoundError`
on AA, while the phone UI worked. Fixed by linking the AARs' self-contained
resources into the APK and generating their R classes (`aapt2 link
--extra-packages`, see `extract_cp.py` / `build.sh`). The CI crash check
above guards against regressions.
