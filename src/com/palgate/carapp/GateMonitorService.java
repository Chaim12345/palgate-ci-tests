package com.palgate.carapp;

import android.app.Notification;
import android.util.Log;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Driving monitor (foreground service, type=location) — v1.2 precision engine.
 *
 * Battery model (unchanged): the service only lives while a car session is
 * alive; nothing runs 24/7.
 *
 * Proximity measurement, in layers:
 *   1. FIX QUALIFICATION — only fixes accurate enough (≤ max(40 m, radius/2),
 *      fresh ≤15 s) drive geofence decisions. A ±800 m network fix can no
 *      longer flip the state; it is still shown, but marked coarse.
 *   2. SMOOTHING — distance is EWMA-filtered (α=0.45) with spike damping:
 *      a jump larger than max(80 m, 3×accuracy) inside 12 s is treated as a
 *      GPS teleport and blended in slowly (α=0.15) instead of yanking the
 *      geofence.
 *   3. CLOSING VELOCITY — d(distance)/dt, EWMA-smoothed → approach speed and
 *      ETA to the radius line ("closing 8 m/s · in range in ~6 s").
 *   4. GATE RADIO — continuous BLE RSSI of the gate's own advertisement
 *      (log-distance path loss, calibrated @1 m) → meters to the barrier
 *      itself, immune to GPS drift. Usable as the auto-open trigger.
 *   5. ADAPTIVE POLLING — far (>500 m): 10 s/20 m; mid: 3 s/5 m;
 *      near (<150 m): 1 s/0 m (+ fused provider on Android 12+).
 *
 * Retry policy: while ARMED and in range, EVERY qualified fix re-evaluates
 * the auto-open (v1.1 only tried at the enter instant — if accuracy was poor
 * at that second, it never fired).
 *
 * Every fix is appended to a CSV track log (files/tracks/track.csv) so the
 * measured approach can be reviewed and the radius/trigger calibrated.
 */
public class GateMonitorService extends Service {

    public static final String ACTION_START = "com.palgate.carapp.START";
    public static final String ACTION_STOP = "com.palgate.carapp.STOP";
    public static final String ACTION_OPEN_NOW = "com.palgate.carapp.OPEN_NOW";
    public static final String ACTION_REFRESH = "com.palgate.carapp.REFRESH";
    private static final String CHANNEL_ID = "palgate_car";
    private static final int NOTIF_ID = 42;
    private static final long OPEN_COOLDOWN_MS = 45000;

    private static final int TIER_FAR = 0, TIER_MID = 1, TIER_NEAR = 2;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final HandlerThread ioThread = new HandlerThread("palgate-track-csv");
    private Handler io;
    private List<Gate> gates;
    private LocationManager lm;
    private boolean listening;
    private String gpsProvider;
    private boolean fusedAvailable;
    private BluetoothHeadset headsetProxy;
    private BleGateOpener opener;
    private BleRssiScanner scanner;

    // proximity engine (main thread)
    private int tier = TIER_FAR;
    private double distSm = Double.MAX_VALUE;
    private long lastFixAtMs;
    private double lastSmForClosing = Double.MAX_VALUE;
    private float closingEwma;
    private int lastMile = -1;
    private String lastSkipReason = "";
    private boolean rssiFired;
    private long rssiFiredAtMs;
    private long lastMotionAtMs;
    private long lastCsvRowAt;

    private final Runnable stopCheck = new Runnable() {
        @Override public void run() {
            if (!State.get().aaConnected && !State.get().testMode
                    && State.Prefs.get().requireAA) {
                State.get().log("car session gone → stopping monitor");
                stopSelf();
            }
        }
    };

    // ------------------------------------------------------------ lifecycle --

    @Override
    public void onCreate() {
        super.onCreate();
        State.Prefs.load(this);
        gates = Gate.load(this);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        scanner = new BleRssiScanner(this, rssiListener);
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH.equals(action)) {
            // Widget root tap: refresh state display only. Never starts
            // monitoring here, so no geofence/auto-open side effects.
            GateWidgetProvider.push(this);
            if (!listening) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_OPEN_NOW.equals(action)) {
            manualOpen(intent.getStringExtra("gateId"));
            // fallthrough: keep running (or start properly below if not yet)
        }
        if (!listening) {
            startAsForeground();
            startListening();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (listening) stopListening();
        ioThread.quitSafely();
        State.get().post(new Runnable() {
            @Override public void run() { State.get().monitoring = false; }
        });
        GateWidgetProvider.push(this);
        super.onDestroy();
    }

    // ---------------------------------------------------------- foreground --

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    "PalGate car monitor", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, PhoneActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("PalGate — car mode")
                .setContentText(State.get().statusLine())
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification());
    }

    // ----------------------------------------------------------- monitoring --

    private void startListening() {
        if (lm == null || gates.isEmpty()) {
            State.get().log("monitor: no LocationManager or no gates — stopping");
            stopSelf();
            return;
        }
        listening = true;
        State.get().post(new Runnable() {
            @Override public void run() { State.get().monitoring = true; }
        });

        Criteria fine = new Criteria();
        fine.setAccuracy(Criteria.ACCURACY_FINE);
        try {
            gpsProvider = lm.getBestProvider(fine, true);
        } catch (Exception e) {
            gpsProvider = null;
        }
        if (gpsProvider == null) gpsProvider = LocationManager.GPS_PROVIDER;
        fusedAvailable = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                fusedAvailable = lm.getAllProviders().contains("fused");
            } catch (Exception ignored) {
            }
        }

        tier = TIER_FAR;
        distSm = Double.MAX_VALUE;
        lastSmForClosing = Double.MAX_VALUE;
        closingEwma = 0;
        lastMile = -1;
        requestLocations();

        registerCarBtWatcher();
        if (State.Prefs.get().triggerMode != State.TRIG_GPS && scanner != null) {
            scanner.update(-1);   // walk-up: the radio must not wait for a GPS fix
        }
        h.postDelayed(statusTicker, 10000);
        State.get().log("monitor started: " + gates.size() + " gate(s), radius "
                + State.Prefs.get().radiusM + " m"
                + (fusedAvailable ? ", fused provider" : "")
                + (State.get().testMode ? " [TEST MODE]" : ""));
        GateWidgetProvider.push(this);
    }

    /** (Re)request location streams with the current tier's intervals. */
    private void requestLocations() {
        try {
            lm.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
        long gpsMs, gpsMinM, auxMs, auxMinM;
        switch (tier) {
            case TIER_NEAR: gpsMs = 1000; gpsMinM = 0; auxMs = 1000; auxMinM = 0; break;
            case TIER_MID:  gpsMs = 3000; gpsMinM = 5; auxMs = 5000; auxMinM = 10; break;
            default:        gpsMs = 10000; gpsMinM = 20; auxMs = 15000; auxMinM = 50; break;
        }
        try {
            lm.requestLocationUpdates(gpsProvider, gpsMs, gpsMinM,
                    locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            State.get().log("monitor: location permission missing — open the PalGate app");
            stopSelf();
            return;
        } catch (Exception e) {
            State.get().log("monitor: GPS unavailable (" + e + ") — trying network");
        }
        if (fusedAvailable) {
            try {
                lm.requestLocationUpdates("fused", auxMs, auxMinM,
                        locationListener, Looper.getMainLooper());
            } catch (Exception ignored) {
            }
        } else {
            try {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, auxMs, auxMinM,
                        locationListener, Looper.getMainLooper());
            } catch (Exception ignored) {
            }
        }
    }

    /** Tier transitions with hysteresis so we don't churn the radio. */
    private void applyTier(double d) {
        if (d == Double.MAX_VALUE) return;
        int nt = tier;
        switch (tier) {
            case TIER_FAR:
                if (d < 500) nt = TIER_MID;
                break;
            case TIER_MID:
                if (d > 560) nt = TIER_FAR;
                else if (d < 150) nt = TIER_NEAR;
                break;
            case TIER_NEAR:
                if (d > 190) nt = d > 560 ? TIER_FAR : TIER_MID;
                break;
        }
        if (nt != tier) {
            tier = nt;
            requestLocations();
            State.get().log("location tier → " + tierName());
        }
    }

    private String tierName() {
        switch (tier) {
            case TIER_NEAR: return "NEAR (1 s / 0 m)";
            case TIER_MID:  return "MID (3 s / 5 m)";
            default:        return "FAR (10 s / 20 m)";
        }
    }

    private void stopListening() {
        listening = false;
        try {
            lm.removeUpdates(locationListener);
        } catch (Exception ignored) {
        }
        h.removeCallbacks(statusTicker);
        h.removeCallbacks(stopCheck);
        if (scanner != null) scanner.stop();
        if (headsetProxy != null) {
            try {
                BluetoothAdapter ad = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
                if (ad != null) ad.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy);
            } catch (Exception ignored) {
            }
            headsetProxy = null;
        }
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        if (opener != null) opener.abort();
    }

    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            refreshNotification();
            GateWidgetProvider.push(GateMonitorService.this);
            if (scanner != null && !scanner.isRunning()
                    && State.Prefs.get().triggerMode != State.TRIG_GPS) {
                scanner.update(-1);
            }
            // auto-stop when the car session is gone (unless phone-side test mode
            // or the user dropped the Android Auto requirement for walk-up use)
            if (!State.get().aaConnected && !State.get().testMode
                    && State.Prefs.get().requireAA) {
                h.removeCallbacks(stopCheck);
                h.postDelayed(stopCheck, 90000);   // grace: nav may re-open us
            }
            h.postDelayed(this, 10000);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(final Location loc) {
            // delivered on the main looper — process inline, notify once
            handleFix(loc.getLatitude(), loc.getLongitude(),
                    loc.hasAccuracy() ? loc.getAccuracy() : 999f,
                    loc.hasSpeed() ? loc.getSpeed() : 0f,
                    loc.hasBearing() ? loc.getBearing() : -1,
                    loc.getProvider() == null ? "" : loc.getProvider(),
                    loc.getTime());
        }

        @Override public void onStatusChanged(String p, int st, Bundle ex) { }

        @Override public void onProviderEnabled(String p) { }

        @Override public void onProviderDisabled(String p) { }
    };

    // ------------------------------------------------------ proximity core --

    private void handleFix(double lat, double lng, float acc, float speed,
                           double bearing, String provider, long fixTime) {
        List<Gate> g = Gate.load(this);          // cached; reloads on override change
        if (g != gates) {
            gates = g;
            State.get().log("gate list reloaded (" + gates.size() + " gate(s))");
        }
        State s = State.get();
        s.hasFix = true;
        s.lat = lat;
        s.lng = lng;
        s.speed = speed;
        s.accuracy = acc;
        s.bearing = bearing;
        s.fixProvider = provider;

        long now = System.currentTimeMillis();
        int radius = State.Prefs.get().radiusM;
        boolean qualified = acc <= Math.max(40f, radius * 0.5f)
                && fixTime > now - 15000L;
        s.fixQualified = qualified;

        // ---- nearest gate, raw distance (from ANY fix: the gate radio must
        //      not wait for a geofence-qualified fix)
        Gate nearest = null;
        double raw = Double.MAX_VALUE;
        for (Gate gate : gates) {
            double d = gate.distanceM(lat, lng);
            if (d < raw) {
                raw = d;
                nearest = gate;
            }
        }
        if (scanner != null) scanner.update(raw == Double.MAX_VALUE ? -1 : raw);
        if (!qualified) {
            skipLogOnce(String.format(Locale.ROOT,
                    "coarse fix ignored (±%.0f m, %s) — waiting for GPS", acc, provider));
            State.get().notifyListeners();
            return;
        }

        // ---- smoothing with GPS-teleport damping
        double dt = lastFixAtMs == 0 ? 0 : (now - lastFixAtMs) / 1000.0;
        lastFixAtMs = now;
        if (distSm == Double.MAX_VALUE || dt <= 0 || dt > 30) {
            distSm = raw;
        } else {
            double jump = Math.abs(raw - distSm);
            double alpha = 0.45;
            if (jump > Math.max(80.0, acc * 3) && dt < 12) alpha = 0.15;
            distSm += alpha * (raw - distSm);
        }

        // ---- closing velocity (how fast the distance shrinks)
        if (lastSmForClosing != Double.MAX_VALUE && dt > 0.3 && dt < 20) {
            float v = (float) ((lastSmForClosing - distSm) / dt);
            closingEwma = closingEwma == 0f ? v : closingEwma * 0.6f + v * 0.4f;
        }
        if (speed > 0.5f || closingEwma > 0.5f) lastMotionAtMs = now;
        lastSmForClosing = distSm;
        float eta = closingEwma > 0.8f && distSm > radius
                ? (float) ((distSm - radius) / closingEwma) : -1f;

        s.nearest = nearest;
        s.nearestDistM = distSm;
        s.nearestRawM = raw;
        s.closingMps = closingEwma;
        s.etaS = eta;

        // ---- approach milestones (readable play-by-play, also lands in CSV)
        int mile = (int) (distSm / 50);
        if (mile != lastMile) {
            if (lastMile >= 0 && mile < lastMile && lastMile * 50 <= 500 && nearest != null) {
                State.get().log(String.format(Locale.ROOT, "approach %s: %d→%d m (±%.0f, %s)",
                        nearest.name, lastMile * 50, mile * 50, acc, provider));
            }
            lastMile = mile;
        }

        // ---- geofence with hysteresis (on the smoothed distance)
        boolean near = distSm <= radius;
        if (near && !s.inRange) {
            s.inRange = true;
            s.phase = State.PHASE_ARMED;
            State.get().log(String.format(Locale.ROOT,
                    "ENTER geofence: %s at %.0f m (±%.0f, radius %d m)",
                    nearest.name, distSm, acc, radius));
        } else if (!near && s.inRange && distSm > radius * 1.5) {
            s.inRange = false;
            if (s.phase == State.PHASE_ARMED || s.phase == State.PHASE_OPENED
                    || s.phase == State.PHASE_FAILED) {
                s.phase = State.PHASE_IDLE;
            }
            State.get().log(String.format(Locale.ROOT, "EXIT geofence (%.0f m) — re-armed", distSm));
        }
        if (System.currentTimeMillis() - s.lastOpenAtMs > OPEN_COOLDOWN_MS
                && s.phase == State.PHASE_OPENED) {
            s.phase = s.inRange ? State.PHASE_ARMED : State.PHASE_IDLE;
        }

        // ---- gate radio staleness
        if (s.rssi != 127 && s.rssiAtMs > 0 && now - s.rssiAtMs > 10000) {
            s.rssi = 127;
            s.rssiEstM = -1;
        }
        // re-arm the radio trigger once we clearly left the gate or 3 min passed
        if (rssiFired && (s.rssi >= 127
                || s.rssi < State.Prefs.get().rssiTrigDb - 8
                || now - rssiFiredAtMs > 180000)) {
            rssiFired = false;
        }

        // ---- auto-open retry on EVERY qualified fix while armed
        if (s.inRange && s.phase == State.PHASE_ARMED) {
            maybeAutoOpen("gps", acc, speed, nearest);
        }

        csv("fix");
        applyTier(distSm);
        State.get().notifyListeners();
    }

    /** Log a skip reason only when it changes (avoids 1/s log spam). */
    private void skipLogOnce(String reason) {
        if (reason == null) {
            lastSkipReason = "";
            return;
        }
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            State.get().log(reason);
        }
    }

    // ------------------------------------------------------------ triggers --

    private void maybeAutoOpen(String reason, float acc, float speed, Gate target) {
        State s = State.get();
        State.Prefs p = State.Prefs.get();
        if (target == null) {
            skipLogOnce("no gate resolved for this trigger — not opening");
            return;
        }
        if (!p.autoOpen) {
            skipLogOnce("auto-open disabled — manual only");
            return;
        }
        if (p.openMode != State.Prefs.MODE_DIRECT_BLE) {
            // A bound-MAC advertisement is unauthenticated: anyone can spoof a
            // MAC. Direct BLE still authenticates the gate by challenge-
            // response; the PalGate-app delegate modes would open the REAL
            // gate from the cloud on nothing but a spoofed radio sighting.
            skipLogOnce("auto-open needs direct BLE mode — PalGate-app modes stay manual");
            return;
        }
        if (!s.conditionsMet()) {
            skipLogOnce("in range but conditions not met ("
                    + (s.testMode || !p.requireAA || s.aaConnected ? "AA✓" : "AA✗")
                    + (s.testMode || !p.requireBT || s.carBt ? " BT✓" : " BT✗")
                    + ") — will retry");
            return;
        }
        if (reason.equals("gps")) {
            if (p.triggerMode == State.TRIG_BLE) return;   // radio-only trigger: silence
            if (acc > Math.max(40f, p.radiusM * 0.5f)) {
                skipLogOnce(String.format(Locale.ROOT,
                        "fix too coarse (%.0f m) — waiting for better GPS", acc));
                return;
            }
            if (speed > 25) {
                skipLogOnce(String.format(Locale.ROOT,
                        "too fast (%.0f km/h) — not opening", speed * 3.6));
                return;
            }
            if (p.approachFilter && closingEwma < 0.5f) {
                skipLogOnce("not closing on the gate — approach filter active");
                return;
            }
        } else {
            if (p.triggerMode == State.TRIG_GPS) return;
            if (speed > 25) {
                skipLogOnce(String.format(Locale.ROOT,
                        "too fast (%.0f km/h) — not opening", speed * 3.6));
                return;
            }
        }
        if (s.lastOpenAtMs > 0
                && System.currentTimeMillis() - s.lastOpenAtMs < OPEN_COOLDOWN_MS) {
            skipLogOnce("cooldown active — manual OPEN available");
            return;
        }
        skipLogOnce(null);
        runOpen(reason, target);
    }

    private final BleRssiScanner.Listener rssiListener = new BleRssiScanner.Listener() {
        @Override public void onProximity(final int rssi, final String mac, final String name,
                                          final double estM) {
            State s = State.get();
            s.rssi = rssi;
            s.rssiMac = mac == null ? "" : mac;
            s.rssiName = name == null ? "" : name;
            s.rssiEstM = estM;
            s.rssiAtMs = System.currentTimeMillis();
            State.Prefs p = State.Prefs.get();
            if (p.triggerMode != State.TRIG_GPS && !rssiFired && rssi >= p.rssiTrigDb) {
                // Wrong-gate guard: the radio must be bound to a gate on this
                // phone. PalGate units share service UUIDs, so a neighbor's
                // gate (or the second gate 3 m away) looks identical until
                // bound; unbound radios never trigger an open.
                if (p.openMode != State.Prefs.MODE_DIRECT_BLE) {
                    State.get().notifyListeners();
                    return;   // delegate modes never auto-open (unauthenticated radio)
                }
                Gate bound = GateAssoc.boundGate(
                        State.Prefs.macMap(GateMonitorService.this), gates, mac);
                if (bound == null) {
                    State.get().log(String.format(Locale.ROOT,
                            "gate radio %d dBm ≥ trigger but %s is unbound — not auto-opening (bind it under GATES)",
                            rssi, mac));
                    State.get().notifyListeners();
                    return;
                }
                if (!p.autoOpen || !s.conditionsMet() || s.bleBusy) {
                    State.get().notifyListeners();
                    return;
                }
                if (s.lastOpenAtMs > 0
                        && System.currentTimeMillis() - s.lastOpenAtMs < OPEN_COOLDOWN_MS) {
                    State.get().notifyListeners();
                    return;
                }
                // Approach evidence (risk reduction, NOT radio authentication):
                // with a recent qualified fix, require actual motion; with no
                // fix (parking garage), the RSSI rise edge + one-shot latch
                // stand alone. A stationary phone at home never auto-opens.
                long nowMs = System.currentTimeMillis();
                boolean recentFix = lastFixAtMs > 0 && nowMs - lastFixAtMs < 30000;
                if (recentFix && nowMs - lastMotionAtMs > 30000) {
                    State.get().log("gate radio ≥ trigger but no approach motion — not auto-opening");
                    State.get().notifyListeners();
                    return;
                }
                rssiFired = true;
                rssiFiredAtMs = System.currentTimeMillis();
                State.get().log(String.format(Locale.ROOT,
                        "gate radio %d dBm ≥ %d dBm trigger (≈%.0f m) — auto open %s",
                        rssi, p.rssiTrigDb, estM, bound.name));
                csv("rssi-trig");
                maybeAutoOpen("ble", s.accuracy, s.speed, bound);
            }
            State.get().notifyListeners();
        }

        @Override public void onNothingSeen() {
            State s = State.get();
            if (s.rssi != 127) {
                s.rssi = 127;
                s.rssiEstM = -1;
                State.get().notifyListeners();
            }
        }
    };

    // ---------------------------------------------------------------- open --

    private void manualOpen(String gateId) {
        if (State.get().bleBusy) {
            State.get().log("open already in flight");
            return;
        }
        Gate target = null;
        if (gateId != null) {
            for (Gate g : gates) {
                if (g.id.equals(gateId)) {
                    target = g;
                    break;
                }
            }
            if (target == null) {
                State.get().log("✘ OPEN FAILED — unknown gate id " + gateId);
                return;
            }
        } else {
            // Generic (widget / OPEN NOW) press with several gates configured:
            // GPS cannot tell gates a few meters apart, so only an
            // unambiguous in-range single gate may be opened; otherwise the
            // user picks the gate explicitly in the app.
            State s = State.get();
            Gate[] out = new Gate[1];
            int r = GateAssoc.resolveManual(gates, s.hasFix, s.lat, s.lng,
                    Math.min(State.Prefs.get().radiusM, 40), out);
            if (r == GateAssoc.RESOLVE_ONE) {
                target = out[0];
            } else if (r == GateAssoc.RESOLVE_NONE) {
                State.get().log("✘ OPEN FAILED — no gate configured");
                return;
            } else if (r == GateAssoc.RESOLVE_AMBIGUOUS) {
                Log.i("PalGate", "OPEN-REFUSED ambiguous: several gates in range");
                State.get().log("✘ OPEN REFUSED — several gates in range; open the specific gate from the app");
                return;
            } else {
                Log.i("PalGate", "OPEN-REFUSED out-of-range: not at any gate");
                State.get().log("✘ OPEN REFUSED — not at any gate; open the specific gate from the app");
                return;
            }
        }
        runOpen("manual", target);
    }

    /** MAC already proven to belong to the target gate (bound + radio-visible,
     *  or bound from an earlier open). Unbound sightings are never used. */
    private String hintMac(Gate target) {
        if (target == null) return null;
        State s = State.get();
        long age = System.currentTimeMillis() - s.rssiAtMs;
        if (s.rssi < 127 && !s.rssiMac.isEmpty() && age < 15000) {
            String gid = State.Prefs.macMap(this).get(s.rssiMac);
            if (target.id.equals(gid)) return s.rssiMac;
        }
        for (Map.Entry<String, String> e : State.Prefs.macMap(this).entrySet()) {
            if (e.getValue().equals(target.id)) return e.getKey();
        }
        return null;
    }

    private void runOpen(final String why, final Gate target) {
        final State s = State.get();

        // TEST BUILD ONLY (build-time marker, see GateSafety): never actuate.
        // runOpen is the single choke point for every open path (manual widget/
        // notification opens, GPS/BLE auto-open, direct BLE and PalGate delegate
        // modes) - nothing below runs in a test build.
        if (GateSafety.isTestBuild(this)) {
            Log.i("PalGate", "SYNTHETIC-DISPATCH " + why + " open requested"
                    + " (test build: BLE + delegate broadcast disabled at dispatcher)");
            s.log("SYNTHETIC-DISPATCH " + why + " open (test build; nothing actuated)");
            csv("open-" + why + "-synthetic");
            refreshNotification();
            GateWidgetProvider.push(this);
            return;
        }

        int mode = State.Prefs.get().openMode;

        // ---- delegate modes: fire PalGate's own widget open broadcast ----
        if (mode != State.Prefs.MODE_DIRECT_BLE) {
            if (target == null) {
                s.phase = State.PHASE_FAILED;
                s.phaseDetail = "no gate configured";
                State.get().log("✘ OPEN FAILED — no gate configured");
                refreshNotification();
                GateWidgetProvider.push(this);
                return;
            }
            final String action = mode == State.Prefs.MODE_PAL_NETWORK ? PalDelegate.ACTION_3G_OPEN
                    : mode == State.Prefs.MODE_PAL_4GBT ? PalDelegate.ACTION_4GBT_OPEN
                    : PalDelegate.ACTION_BT_OPEN;
            s.phase = State.PHASE_OPENING;
            s.phaseDetail = why + " → PalGate app";
            refreshNotification();
            String err = PalDelegate.open(this, target, action);
            if (err == null) {
                s.phase = State.PHASE_OPENED;
                s.phaseDetail = why + " open via PalGate app (" + action.substring(action.lastIndexOf('.') + 1) + ")";
                s.lastOpenAtMs = System.currentTimeMillis();
                s.lastOpenGateId = target.id;
                State.get().log("✔ DELEGATED " + why + " open — " + target.name
                        + " via official PalGate app (" + action + "). "
                        + "Watch the PalGate app notifications for the result.");
            } else {
                s.phase = State.PHASE_FAILED;
                s.phaseDetail = err;
                State.get().log("✘ DELEGATE FAILED — " + err);
            }
            csv("open-" + why + (err == null ? "-sent" : "-fail"));
            refreshNotification();
            GateWidgetProvider.push(this);
            return;
        }

        // ---- direct BLE mode (offline path) ----
        if (target == null) {
            s.phase = State.PHASE_FAILED;
            s.phaseDetail = "no gate configured";
            State.get().log("✘ OPEN FAILED — no gate configured");
            refreshNotification();
            GateWidgetProvider.push(this);
            return;
        }
        if (!Gate.hasUsableKey(target)) {
            s.phase = State.PHASE_FAILED;
            s.phaseDetail = "no BLE key for " + target.name;
            State.get().log("✘ OPEN FAILED — no BLE key stored for " + target.name
                    + " — add it under GATES in the app (or pick a PalGate-app mode)");
            csv("open-" + why + "-nokey");
            refreshNotification();
            GateWidgetProvider.push(this);
            return;
        }
        s.bleBusy = true;
        s.phase = State.PHASE_OPENING;
        s.phaseDetail = why + " open " + target.name;
        refreshNotification();
        GateWidgetProvider.push(this);
        if (scanner != null) scanner.pause();     // don't fight the GATT connect
        csv("open-" + why);
        opener = new BleGateOpener(this, gates, new BleGateOpener.Callback() {
            @Override public void onResult(boolean ok, String detail) {
                State st = State.get();
                st.bleBusy = false;
                st.phase = ok ? State.PHASE_OPENED : State.PHASE_FAILED;
                st.phaseDetail = detail;
                if (ok) {
                    st.lastOpenAtMs = System.currentTimeMillis();
                    st.lastOpenGateId = target.id;
                }
                // Honest result: a no-response BLE write can never confirm the
                // barrier moved — say what we know, not "OPEN OK".
                st.log(ok ? "✔ OPEN COMMAND SENT — " + detail
                        + " (BLE gives no open confirmation — watch the gate)"
                        : "✘ OPEN FAILED — " + detail);
                csv("open-" + (ok ? "ok" : "fail"));
                refreshNotification();
                GateWidgetProvider.push(GateMonitorService.this);
                if (scanner != null) scanner.resume();
            }
        });
        opener.openFor(target.id, hintMac(target));   // bound MAC → skip the 15 s discovery scan
    }

    // ------------------------------------------------------------ track log --

    /** Append a row to files/tracks/track.csv (rotates at 4 MB).
     *  Snapshots the state and writes on a background thread — never on main. */
    private void csv(final String event) {
        if (!State.Prefs.get().trackCsv) return;
        long now = System.currentTimeMillis();
        if (event.equals("fix") && now - lastCsvRowAt < 1500) return;
        if (event.equals("fix")) lastCsvRowAt = now;
        final State s = State.get();
        final long ts = now;
        final double lat = s.lat, lng = s.lng, raw = s.nearestRawM, sm = s.nearestDistM;
        final float acc = s.accuracy, spd = s.speed, brg = (float) s.bearing, clos = s.closingMps;
        final int rssi = s.rssi;
        final double rssiEst = s.rssiEstM;
        final String prov = s.fixProvider, gateId = s.nearest == null ? "" : s.nearest.id;
        io.post(new Runnable() {
            @Override public void run() {
                writeCsvRow(ts, prov, lat, lng, acc, spd, brg, gateId, raw, sm, clos, rssi, rssiEst, event);
            }
        });
    }

    private void writeCsvRow(long ts, String prov, double lat, double lng, float acc,
                             float spd, float brg, String gateId, double raw, double sm,
                             float clos, int rssi, double rssiEst, String event) {
        try {
            File dir = new File(getFilesDir(), "tracks");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "track.csv");
            if (f.length() > 4000000L) {
                File old = new File(dir, "track_old.csv");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
                f = new File(dir, "track.csv");
            }
            boolean fresh = !f.exists() || f.length() == 0;
            FileWriter w = new FileWriter(f, true);
            if (fresh) {
                w.write("time,provider,lat,lng,acc_m,speed_mps,bearing,gate,"
                        + "dist_raw_m,dist_smooth_m,closing_mps,rssi_dbm,rssi_est_m,event\n");
            }
            w.write(String.format(Locale.ROOT,
                    "%d,%s,%.6f,%.6f,%.0f,%.1f,%.0f,%s,%.1f,%.1f,%.1f,%d,%.1f,%s\n",
                    ts, prov, lat, lng, acc, spd, brg, gateId,
                    raw == Double.MAX_VALUE ? -1 : raw,
                    sm == Double.MAX_VALUE ? -1 : sm,
                    clos,
                    rssi >= 127 ? 999 : rssi,
                    rssiEst < 0 ? -1 : rssiEst,
                    event));
            w.close();
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------------------- car bluetooth --

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            pollCarBt();
        }
    };

    private void registerCarBtWatcher() {
        pollCarBt();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            registerReceiver(btReceiver, f);
        } catch (Exception ignored) {
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad != null) {
            ad.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    headsetProxy = (BluetoothHeadset) proxy;
                    pollCarBt();
                }

                @Override public void onServiceDisconnected(int profile) {
                    headsetProxy = null;
                }
            }, BluetoothProfile.HEADSET);
        }
    }

    private void pollCarBt() {
        boolean connected = false;
        try {
            if (headsetProxy != null) {
                List<android.bluetooth.BluetoothDevice> ds = headsetProxy.getConnectedDevices();
                connected = ds != null && !ds.isEmpty();
            }
        } catch (SecurityException e) {
            connected = State.get().carBt;   // keep last known
        }
        final boolean c = connected;
        if (c != State.get().carBt) {
            State.get().post(new Runnable() {
                @Override public void run() {
                    State.get().carBt = c;
                    State.get().log(c ? "car Bluetooth connected" : "car Bluetooth disconnected");
                }
            });
        }
    }
}
