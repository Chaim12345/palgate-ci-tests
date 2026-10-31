package com.palgate.carapp;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
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

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;


/**
 * BLE gate opener — byte-identical protocol to the validated palgate_ble_open.py /
 * PalGateBLETest implementations:
 *
 *  MODERN (service 9c23af10-...-34fb): read 16-byte challenge from char ...1201,
 *  dec = AES-128-ECB-decrypt(gateKey, challenge),
 *  write 20 bytes dec[0:4] || AES-enc(gateKey, dec[0:4] || 00000002 || 00*8) to ...1202
 *  (no-response write).
 *
 *  CLASSIC (FFE0 units): write AES-enc(gateKey, ("AB" + serial[:10] + digits)[:16])
 *  to char 0000ffe1-...-34fb (no-response).
 *
 * Discovery: connect directly to a known MAC when we have one, else 15 s scan
 * for PalGate service UUIDs / bt-at-4g-bw-pal-blue names, strongest RSSI wins.
 * Key choice: learned MAC→gate binding first, then advertised-name→serial,
 * then round-robin over the gate list.
 */
public final class BleGateOpener {

    public interface Callback {
        void onResult(boolean written, String detail);
    }

    private static final String UUID_CHALLENGE = "9c23af10-0000-1000-8000-00805f9b1201";
    private static final String UUID_RESPONSE  = "9c23af10-0000-1000-8000-00805f9b1202";
    private static final String UUID_CLASSIC   = "0000ffe1-0000-1000-8000-00805f9b34fb";

    private static final ParcelUuid SVC_MODERN =
            ParcelUuid.fromString("9c23af10-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SVC_CLASSIC =
            ParcelUuid.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final List<Gate> gates;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler work = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private volatile boolean aborted;

    // attempt generation: stale GATT callbacks from an abandoned attempt
    // (e.g. failed direct connect → scan fallback) must be ignored
    private int gen;
    private int hintGen = -1;
    private String targetGateId;   // when set, only this gate's key is ever used

    public BleGateOpener(Context ctx, List<Gate> gates, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.gates = gates;
        this.cb = cb;
    }

    private static boolean hasPerm(Context c, String p) {
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean btPermsOk() {
        if (Build.VERSION.SDK_INT >= 31) {
            return hasPerm(ctx, Manifest.permission.BLUETOOTH_SCAN)
                    && hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT);
        }
        return hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /** Start the discover+open sequence. Callback fires exactly once. */
    public void open() {
        open(null);
    }

    /**
     * Start the sequence. When we already know the gate's MAC (learned
     * binding or a live gate-radio sighting) connect straight to it — the
     * 15 s discovery scan is most of the open latency. Falls back to a
     * scan if the direct connect fails.
     */
    /** Open one specific gate: its key, its bound radio, never a guess. */
    public void openFor(String gateId, String preferredMac) {
        targetGateId = gateId;
        open(preferredMac);
    }

    public void open(String preferredMac) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            done(false, "Bluetooth is off");
            return;
        }
        if (!btPermsOk()) {
            done(false, "missing Bluetooth permission (open PalGate app once)");
            return;
        }
        if (preferredMac != null && !preferredMac.isEmpty()) {
            hintGen = ++gen;
            State.get().log("connecting known gate radio " + preferredMac + " (skip scan)");
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(preferredMac);
                connect(new Cand(preferredMac, "", 0, dev), hintGen, 10000);
            } catch (Exception e) {
                fail(hintGen, "getRemoteDevice: " + e);
            }
            return;
        }
        scan(15000);
    }

    public void abort() {
        aborted = true;
    }

    // ---------------------------------------------------------------- scan --

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (aborted) return;
            BluetoothDevice dev = result.getDevice();
            String name = dev.getName();
            if (name == null || name.trim().isEmpty()) {
                name = result.getScanRecord() == null ? "" : result.getScanRecord().getDeviceName();
            }
            name = name == null ? "" : name.trim();
            List<ParcelUuid> uuids = result.getScanRecord() == null
                    ? null : result.getScanRecord().getServiceUuids();
            boolean palSvc = false;
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    String s = u.getUuid().toString().toLowerCase(Locale.ROOT);
                    if (s.startsWith("0000ffe0") || s.startsWith("9c23af10")) palSvc = true;
                }
            }
            boolean nameMatch = name.toLowerCase(Locale.ROOT).matches("^(bt|at|4g|bw|pal|blue).*");
            boolean learned = State.Prefs.macMap(ctx).containsKey(dev.getAddress());
            boolean knownSerial = gateByName(name) != null;
            if (palSvc || nameMatch || learned || knownSerial) {
                candidates.add(new Cand(dev.getAddress(), name, result.getRssi(), dev));
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            done(false, "BLE scan failed (error " + errorCode + ")");
        }
    };

    private static class Cand {
        final String mac, name;
        final int rssi;
        final BluetoothDevice dev;
        Cand(String m, String n, int r, BluetoothDevice d) { mac = m; name = n; rssi = r; dev = d; }
    }

    private final List<Cand> candidates = new ArrayList<Cand>();

    private void scan(final int ms) {
        List<ScanFilter> filters = new ArrayList<ScanFilter>();
        try {
            filters.add(new ScanFilter.Builder().setServiceUuid(SVC_MODERN).build());
            filters.add(new ScanFilter.Builder().setServiceUuid(SVC_CLASSIC).build());
        } catch (Exception ignored) {
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            adapter.getBluetoothLeScanner().startScan(filters, settings, scanCb);
        } catch (Exception e) {
            done(false, "scan start failed: " + e);
            return;
        }
        State.get().log("BLE scan running (" + ms / 1000 + " s)…");
        final int g = ++gen;
        work.postDelayed(new Runnable() {
            @Override public void run() {
                if (g == gen) stopScanAndConnect();
            }
        }, ms);
    }

    private void stopScanAndConnect() {
        try {
            adapter.getBluetoothLeScanner().stopScan(scanCb);
        } catch (Exception ignored) {
        }
        if (aborted) return;
        if (candidates.isEmpty()) {
            done(false, "no PalGate BLE device found (scan finished)");
            return;
        }
        Cand best = null;
        synchronized (candidates) {
            for (Cand c : candidates) {
                if (targetGateId != null) {
                    String gid = State.Prefs.macMap(ctx).get(c.mac);
                    if (gid != null && !gid.equals(targetGateId)) continue; // another gate's radio
                }
                if (best == null || c.rssi > best.rssi) best = c;
            }
        }
        if (best == null) {
            done(false, "only radios bound to other gates in range");
            return;
        }
        connect(best, gen, 15000);
    }

    /** Generation-aware failure: stale attempts are ignored; a failed direct
     *  connect falls back to a discovery scan exactly once. */
    private void fail(int g, String why) {
        if (finished || g != gen) return;
        if (g == hintGen) {
            hintGen = -1;
            State.get().log("direct connect failed (" + why + ") — scanning instead");
            scan(10000);
            return;
        }
        done(false, why);
    }

    // ------------------------------------------------------------- connect --

    private Gate pickGate(Cand c) {
        // 0. explicit target (all monitor opens): always that gate's key
        if (targetGateId != null) {
            for (Gate g : gates) if (g.id.equals(targetGateId)) return g;
        }
        // 1. learned binding
        String gateId = State.Prefs.macMap(ctx).get(c.mac);
        if (gateId != null) {
            for (Gate g : gates) if (g.id.equals(gateId)) return g;
        }
        // 2. advertised name == serial
        Gate byName = gateByName(c.name);
        if (byName != null) {
            State.Prefs.learnMac(ctx, c.mac, byName.id);
            return byName;
        }
        // 3. round-robin
        int idx = ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE)
                .getInt("lastKeyIdx", -1);
        return gates.get((idx + 1) % gates.size());
    }

    private Gate gateByName(String name) {
        if (name == null || name.isEmpty()) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Gate g : gates) {
            if (n.contains(g.id.toLowerCase(Locale.ROOT))) return g;
        }
        return null;
    }

    private void connect(final Cand c, final int g, final int timeoutMs) {
        final Gate gate = pickGate(c);
        ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE).edit()
                .putInt("lastKeyIdx", gates.indexOf(gate)).apply();
        State.get().log("connecting " + c.mac + " (" + (c.name.isEmpty() ? "unnamed" : c.name)
                + ") key=" + gate.name);
        try {
            c.dev.connectGatt(ctx, false, new GattCb(gate, c, g),
                    BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            fail(g, "connectGatt failed: " + e);
        }
        work.postDelayed(new Runnable() {
            @Override public void run() {
                if (g == gen && !finished) {
                    fail(g, "GATT timeout (gate unreachable?)");
                }
            }
        }, timeoutMs);
    }

    private volatile boolean finished;

    private class GattCb extends BluetoothGattCallback {
        private final Gate gate;
        private final Cand cand;
        private final int myGen;

        GattCb(Gate g, Cand c, int gen) { gate = g; cand = c; myGen = gen; }

        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            if (myGen != gen || finished || aborted) return;
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) { }
                fail(myGen, "disconnected before write (status " + status + ")");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (myGen != gen || finished || aborted) return;
            if (status != 0) {
                fail(myGen, "service discovery failed (" + status + ")");
                try { g.disconnect(); } catch (Exception ignored) { }
                return;
            }
            BluetoothGattCharacteristic ch = findChar(g, UUID_CHALLENGE);
            if (ch != null) {
                if (!g.readCharacteristic(ch)) fail(myGen, "readCharacteristic rejected");
                return;
            }
            BluetoothGattCharacteristic cl = findChar(g, UUID_CLASSIC);
            if (cl != null) {
                writeClassic(g, cl, gate, cand);
                return;
            }
            fail(myGen, "neither modern (…1201) nor classic (FFE1) char present");
            try { g.disconnect(); } catch (Exception ignored) { }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (myGen != gen || finished || aborted) return;
            if (!UUID_CHALLENGE.equals(c.getUuid().toString().toLowerCase(Locale.ROOT))) return;
            byte[] value = c.getValue();
            if (status != 0 || value == null || value.length != 16) {
                BluetoothGattCharacteristic cl = findChar(g, UUID_CLASSIC);
                if (cl != null) {
                    State.get().log("challenge read failed → trying classic FFE1");
                    writeClassic(g, cl, gate, cand);
                } else {
                    fail(myGen, "challenge read failed (status " + status + ")");
                    try { g.disconnect(); } catch (Exception ignored) { }
                }
                return;
            }
            try {
                byte[] key = GateCrypto.hex(gate.keyHex);
                byte[] resp = GateCrypto.buildModernResponse(key, value);
                BluetoothGattCharacteristic wc = findChar(g, UUID_RESPONSE);
                if (wc == null) {
                    done(false, "response char …1202 missing");
                    try { g.disconnect(); } catch (Exception ignored) { }
                    return;
                }
                State.get().log("CR response written (" + gate.name + "): " + GateCrypto.hexStr(resp));
                wc.setValue(resp);
                wc.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                if (!g.writeCharacteristic(wc)) {
                    done(false, "writeCharacteristic rejected");
                    return;
                }
                State.Prefs.learnMac(ctx, cand.mac, gate.id);
                done(true, "challenge-response written to " + gate.name
                        + " (" + (cand.name.isEmpty() ? cand.mac : cand.name) + ")");
            } catch (Exception e) {
                done(false, "crypto error: " + e);
                try { g.disconnect(); } catch (Exception ignored) { }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (myGen != gen || finished) return;
            if (status != 0) fail(myGen, "GATT write failed (status " + status + ")");
            try { g.disconnect(); } catch (Exception ignored) { }
        }
    }

    private void writeClassic(BluetoothGatt g, BluetoothGattCharacteristic c, Gate gate, Cand cand) {
        try {
            String clean = gate.id.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
            if (clean.length() > 10) clean = clean.substring(0, 10);
            StringBuilder sb = new StringBuilder("AB").append(clean);
            while (sb.length() < 16) sb.append('?');
            byte[] enc = GateCrypto.buildClassicPayload(GateCrypto.hex(gate.keyHex), gate.id, new SecureRandom());
            State.get().log("classic payload written (" + gate.name + ")");
            c.setValue(enc);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            if (!g.writeCharacteristic(c)) {
                done(false, "writeCharacteristic rejected (classic)");
                return;
            }
            State.Prefs.learnMac(ctx, cand.mac, gate.id);
            done(true, "classic payload written to " + gate.name);
        } catch (Exception e) {
            done(false, "classic error: " + e);
            try { g.disconnect(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- util --

    private void done(final boolean ok, final String detail) {
        if (finished) return;
        finished = true;
        main.post(new Runnable() {
            @Override public void run() {
                cb.onResult(ok, detail);
            }
        });
    }

    private static BluetoothGattCharacteristic findChar(BluetoothGatt g, String uuid) {
        for (BluetoothGattService s : g.getServices()) {
            BluetoothGattCharacteristic c = s.getCharacteristic(UUID.fromString(uuid));
            if (c != null) return c;
        }
        return null;
    }
}
