# palgate-ci-tests

CI tests for the SANITIZED PalGate car-app build (Android Auto + home-screen widget).

- All gate data is synthetic (assets/gates.json: Test Gate A/B, fake keys/MACs).
- No real gate, Bluetooth, or official-app actions. BLE stays disabled; the
  expected open-attempt outcome on an emulator is the app's graceful
  "Bluetooth is off" path (asserted as such - never a gate-open success).
- The signing key is generated fresh inside the CI job (build.sh creates a
  disposable testkey.jks when absent). No keystore is committed.
- Open Headunit 3.5.0 APK is downloaded from the official release and pinned
  by sha256.

Workflow: build the APK from source (no Gradle: aapt2 + ecj + d8), boot an
Android emulator (KVM), then run ci/aa_test.sh: installs, permissions, fake
GPS, Android Auto head-unit-server loopback with Open Headunit, PalGate car
app launch via the AA launcher, and the home-screen widget add/render/tap
checks. Screenshots, uiautomator dumps and logcat are uploaded as artifacts;
executed vs blocked checks are reported explicitly. Any FATAL EXCEPTION in
com.palgate.carapp fails the run. See TESTING.md for local emulator and
Desktop Head Unit recipes.
