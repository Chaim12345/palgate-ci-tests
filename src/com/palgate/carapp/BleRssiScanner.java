package com.palgate.carapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Continuous BLE proximity scanner — "how close is the CAR to the GATE", as
 * measured by the gate's own radio.
 *
 * GPS answers "where is the phone" (± its accuracy circle); RSSI answers
 * "how strong is the gate's advertisement here", which is a direct physical
 * proximity measurement to the barrier hardware and immune to GPS drift.
 *
 * Model: log-distance path loss, d = 10^((rssi1m − rssi) / (10·n)) with
 * n = 2.0. rssi1m is user-calibratable (stand 1 m from the gate, tap the
 * calibrate button); every −20 dB ⇒ ×10 distance.
 *
 * Battery: scan is OFF while far away. It starts inside
 * max(400 m, 2.5×radius) of the nearest gate (LOW_POWER mode) and switches to
 * LOW_LATENCY inside ~150 m. It pauses while an open attempt is in flight so
 * it never fights the GATT connection for the radio.
 */
public final class BleRssiScanner {

    public interface Listener {
        /** Strongest fresh PalGate advertisement. rssi is EWMA-smoothed. */
        void onProximity(int rssi, String mac, String name, double estM);

        /** Nothing PalGate-like visible in the last few seconds. */
        void onNothingSeen();
    }

    private static final ParcelUuid SVC_MODERN =
            ParcelUuid.fromString("9c23af10-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SVC_CLASSIC =
            ParcelUuid.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Listener cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    // mac -> {ewmaRssi} / lastSeenMs / advertised name — main thread only
    private final Map<String, double[]> ewma = new HashMap<String, double[]>();
    private final Map<String, Long> seenAt = new HashMap<String, Long>();
    private final Map<String, String> names = new HashMap<String, String>();

    private BluetoothAdapter adapter;
    private boolean running;          // scanning is wanted
    private boolean paused;           // temporarily off (open attempt in flight)
    private boolean nearMode;         // LOW_LATENCY
    private boolean appliedNear;
    private long lastReport;

    public BleRssiScanner(Context ctx, Listener cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    // ------------------------------------------------------------- statics --

    static boolean permsOk(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            return has(c, Manifest.permission.BLUETOOTH_SCAN);
        }
        return has(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private static boolean has(Context c, String p) {
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** Same device-matching policy as the opener: PalGate service UUIDs,
     *  PalGate-ish advertised names, or a learned MAC. */
    static boolean looksPalGate(Context c, String name, List<ParcelUuid> uuids) {
        if (uuids != null) {
            for (ParcelUuid u : uuids) {
                String s = u.getUuid().toString().toLowerCase(Locale.ROOT);
                if (s.startsWith("0000ffe0") || s.startsWith("9c23af10")) return true;
            }
        }
        if (name != null && !name.trim().isEmpty()
                && name.toLowerCase(Locale.ROOT).matches("^(bt|at|4g|bw|pal|blue).*")) {
            return true;
        }
        return false;   // learned-MAC units are caught by the device-address filters
    }

    /** Hardware filters: the two PalGate service UUIDs + up to 6 learned MACs. */
    static List<ScanFilter> palFilters(Context c) {
        List<ScanFilter> f = new ArrayList<ScanFilter>();
        try {
            f.add(new ScanFilter.Builder().setServiceUuid(SVC_MODERN).build());
            f.add(new ScanFilter.Builder().setServiceUuid(SVC_CLASSIC).build());
            int n = 0;
            for (String mac : State.Prefs.macMap(c).keySet()) {
                if (n++ >= 6) break;
                try {
                    f.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return f;
    }

    /** Path-loss estimate in meters from smoothed RSSI. */
    static double rssiToM(double rssi) {
        double d = Math.pow(10.0, (State.Prefs.get().rssi1m - rssi) / 20.0);
        return Math.max(0.5, Math.min(2000.0, d));
    }

    // ------------------------------------------------------------ lifecycle --

    /** Called on every fix (qualified or not) and on a periodic tick while
     *  monitoring. distM < 0 means "position unknown": keep scanning whenever
     *  a BLE trigger is armed, so walk-up detection works with poor or absent
     *  GPS (parking garage, phone in pocket). */
    public void update(double distM) {
        boolean bleTrigger = State.Prefs.get().triggerMode != State.TRIG_GPS;
        boolean want = distM < 0 ? bleTrigger
                : distM < Math.max(400.0, State.Prefs.get().radiusM * 2.5);
        nearMode = distM >= 0 && distM < 150.0;
        if (want && !running) {
            start();
        } else if (!want && running) {
            stop();
        } else if (running && !paused && nearMode != appliedNear) {
            restart();
        }
    }

    /** Give the radio to the opener for a moment. */
    public void pause() {
        paused = true;
        stopScan();
    }

    public void resume() {
        paused = false;
        if (running) restart();
    }

    public void stop() {
        running = false;
        stopScan();
    }

    public boolean isRunning() {
        return running && !paused;
    }

    private void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled() || !permsOk(ctx)) {
            return;   // stay off; update() will retry on the next fix
        }
        running = true;
        restart();
    }

    private void restart() {
        stopScan();
        if (paused || adapter == null || !adapter.isEnabled()) return;
        appliedNear = nearMode;
        ScanSettings st = new ScanSettings.Builder()
                .setScanMode(nearMode ? ScanSettings.SCAN_MODE_LOW_LATENCY
                        : ScanSettings.SCAN_MODE_LOW_POWER)
                .build();
        try {
            adapter.getBluetoothLeScanner().startScan(palFilters(ctx), st, scanCb);
            State.get().log("gate-radio scan " + (nearMode ? "LOW_LATENCY" : "LOW_POWER") + " on");
        } catch (Exception e) {
            State.get().log("gate-radio scan failed: " + e.getClass().getSimpleName());
            running = false;
        }
    }

    private void stopScan() {
        try {
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(scanCb);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- results --

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, final ScanResult r) {
            main.post(new Runnable() {
                @Override public void run() { handle(r); }
            });
        }

        @Override public void onScanFailed(int errorCode) {
            main.post(new Runnable() {
                @Override public void run() {
                    State.get().log("gate-radio scan error " + errorCode);
                    running = false;
                }
            });
        }
    };

    private void handle(ScanResult r) {
        String name = r.getDevice().getName();
        if (name == null || name.trim().isEmpty()) {
            name = r.getScanRecord() == null ? null : r.getScanRecord().getDeviceName();
        }
        name = name == null ? "" : name.trim();
        List<ParcelUuid> uuids = r.getScanRecord() == null
                ? null : r.getScanRecord().getServiceUuids();
        if (!looksPalGate(ctx, name, uuids)
                && !State.Prefs.macMap(ctx).containsKey(r.getDevice().getAddress())) {
            return;
        }
        String mac = r.getDevice().getAddress();
        double[] e = ewma.get(mac);
        double v = e == null ? r.getRssi() : e[0] + 0.35 * (r.getRssi() - e[0]);
        ewma.put(mac, new double[]{v});
        seenAt.put(mac, System.currentTimeMillis());
        if (!name.isEmpty()) names.put(mac, name);

        long now = System.currentTimeMillis();
        if (now - lastReport < 400) return;   // report ~2.5×/s max
        lastReport = now;

        String bestMac = null;
        double best = -999;
        for (Map.Entry<String, double[]> en : ewma.entrySet()) {
            Long t = seenAt.get(en.getKey());
            if (t == null || now - t > 6000) continue;   // stale
            if (en.getValue()[0] > best) {
                best = en.getValue()[0];
                bestMac = en.getKey();
            }
        }
        if (bestMac == null) {
            cb.onNothingSeen();
            return;
        }
        cb.onProximity((int) Math.round(best), bestMac,
                names.get(bestMac) == null ? "" : names.get(bestMac), rssiToM(best));
    }
}
