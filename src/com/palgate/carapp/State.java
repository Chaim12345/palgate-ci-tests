package com.palgate.carapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** App-wide state + tiny observer bus. All mutations happen on the main thread. */
public final class State {

    public static final int PHASE_IDLE = 0, PHASE_ARMED = 1, PHASE_OPENING = 2,
            PHASE_OPENED = 3, PHASE_FAILED = 4;

    /** What may fire the automatic open. */
    public static final int TRIG_GPS = 0, TRIG_BLE = 1, TRIG_EITHER = 2;

    public interface Listener {
        void onStateChanged();
    }

    private static final State INSTANCE = new State();

    public static State get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final List<String> log = new ArrayList<String>();

    // ---- conditions -------------------------------------------------------
    public boolean monitoring;        // FGS running
    public boolean aaConnected;       // Android Auto session alive
    public boolean carBt;             // car BT (headset profile) connected
    public boolean testMode;          // ignore AA/BT gates (walk testing)
    public boolean bleBusy;           // an open attempt is in flight

    // ---- location (latest fix, raw) ---------------------------------------
    public boolean hasFix;
    public double lat, lng, bearing;
    public float speed, accuracy;
    public String fixProvider = "";
    public boolean fixQualified;      // accurate enough to drive the geofence

    // ---- geofence / proximity ---------------------------------------------
    public Gate nearest;
    public double nearestDistM = Double.MAX_VALUE;   // smoothed distance (display/decisions)
    public double nearestRawM = Double.MAX_VALUE;    // raw single-fix distance
    public boolean inRange;
    public int phase = PHASE_IDLE;
    public String phaseDetail = "";
    public long lastOpenAtMs;
    public String lastOpenGateId = "";
    public float closingMps;          // + = approaching the gate, - = receding
    public float etaS = -1;           // seconds until radius crossing, -1 unknown

    // ---- gate radio (BLE RSSI proximity) ----------------------------------
    public int rssi = 127;            // dBm, 127 = nothing seen
    public String rssiMac = "", rssiName = "";
    public double rssiEstM = -1;      // path-loss estimate in meters
    public long rssiAtMs;

    private State() {
    }

    public boolean conditionsMet() {
        if (!monitoring) return false;
        boolean aa = testMode || !Prefs.get().requireAA || aaConnected;
        boolean bt = testMode || !Prefs.get().requireBT || carBt;
        return aa && bt;
    }

    public synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Mutate state off-main: posts a runnable that edits then notifies. */
    public void post(final Runnable edit) {
        main.post(new Runnable() {
            @Override public void run() {
                edit.run();
                notifyListeners();
            }
        });
    }

    void notifyListeners() {
        List<Listener> ls;
        synchronized (this) {
            ls = new ArrayList<Listener>(listeners);
        }
        for (Listener l : ls) {
            try {
                l.onStateChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public void log(String line) {
        long ms = System.currentTimeMillis() % 100000L;
        final String entry = String.format(Locale.ROOT, "[%05d.%03d] %s",
                ms / 1000, ms % 1000, line);
        main.post(new Runnable() {
            @Override public void run() {
                synchronized (log) {
                    log.add(entry);
                    if (log.size() > 400) log.remove(0);
                }
                notifyListeners();
            }
        });
    }

    public List<String> drainLog() {
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    // ---------------------------------------------------------- readouts --

    /** e.g. "Test Gate A - 84 m +/-9" - honest live distance incl. fix quality. */
    public String distanceText() {
        if (nearest == null) return "no gate configured";
        if (!hasFix || nearestDistM == Double.MAX_VALUE) return "waiting for GPS…";
        return String.format(Locale.ROOT, "%s — %.0f m ±%.0f",
                nearest.name, nearestDistM, Math.max(1f, accuracy));
    }

    /** Approach vector: closing speed and ETA to the radius line. */
    public String approachText() {
        if (closingMps > 0.5f) {
            return etaS > 0
                    ? String.format(Locale.ROOT, "closing %.0f m/s · in range in ~%.0f s", closingMps, etaS)
                    : String.format(Locale.ROOT, "closing %.0f m/s", closingMps);
        }
        if (closingMps < -0.5f) {
            return String.format(Locale.ROOT, "moving away %.0f m/s", -closingMps);
        }
        return speed > 0.5f ? "holding distance" : "stopped";
    }

    /** Gate radio proximity from BLE RSSI. */
    public String radioText() {
        if (rssi >= 127 || System.currentTimeMillis() - rssiAtMs > 10000) {
            return "gate radio: not visible";
        }
        return String.format(Locale.ROOT, "gate radio: %d dBm ≈ %.0f m", rssi, rssiEstM);
    }

    public boolean radioFresh() {
        return rssi < 127 && System.currentTimeMillis() - rssiAtMs <= 10000;
    }

    public String phaseText() {
        switch (phase) {
            case PHASE_ARMED:   return "ARMED (in range, waiting for conditions)";
            case PHASE_OPENING: return "OPENING…";
            case PHASE_OPENED:  return "OPENED — " + phaseDetail;
            case PHASE_FAILED:  return "FAILED — " + phaseDetail;
            default:            return inRange ? "IN RANGE" : "idle / out of range";
        }
    }

    public String statusLine() {
        if (!monitoring) return "not monitoring";
        if (!hasFix) return "monitoring — waiting for GPS";
        String d = nearest == null ? "no gate nearby" : distanceText();
        String chips = (testMode || !Prefs.get().requireAA || aaConnected ? "AA✓ " : "AA✗ ")
                + (testMode || !Prefs.get().requireBT || carBt ? "BT✓ " : "BT✗ ");
        return d + "  ·  " + chips;
    }

    /** Simple persisted settings. */
    public static final class Prefs {
        private static Prefs instance;
        public boolean autoOpen = true;
        public boolean requireAA = true;
        public boolean requireBT = true;
        public int radiusM = 120;

        /** How the gate is opened.
         *  0 = direct BLE from this app (offline, cached key)
         *  1 = delegate to official PalGate app — network + their BLE (4gBtOpen)
         *  2 = delegate to official PalGate app — network only (3gOpen)
         *  3 = delegate to official PalGate app — their BLE only (btOpen) */
        public static final int MODE_DIRECT_BLE = 0;
        public static final int MODE_PAL_4GBT = 1;
        public static final int MODE_PAL_NETWORK = 2;
        public static final int MODE_PAL_BT = 3;
        public int openMode = MODE_PAL_NETWORK;

        /** What fires the automatic open (v1.2 precision).
         *  0 = GPS geofence radius only
         *  1 = BLE RSSI threshold only (gate radio says we're at the barrier)
         *  2 = either */
        public int triggerMode = TRIG_GPS;
        public int rssiTrigDb = -75;    // auto-open when gate radio ≥ this
        public int rssi1m = -55;        // calibrated RSSI at 1 m from the gate
        public boolean trackCsv = true; // write the proximity track log
        public boolean approachFilter = false; // require closing motion for GPS trigger

        static Prefs get() {
            if (instance == null) instance = new Prefs();
            return instance;
        }

        public static String modeName(int m) {
            switch (m) {
                case MODE_DIRECT_BLE: return "direct BLE (offline)";
                case MODE_PAL_4GBT:   return "PalGate app — network+BLE";
                case MODE_PAL_NETWORK:return "PalGate app — network";
                case MODE_PAL_BT:     return "PalGate app — BLE";
                default:              return "?";
            }
        }

        public static String trigName(int t) {
            switch (t) {
                case TRIG_BLE:    return "BLE radio only (RSSI)";
                case TRIG_EITHER: return "GPS radius OR BLE radio";
                default:          return "GPS radius only";
            }
        }

        public static void load(Context ctx) {
            SharedPreferences sp = ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE);
            Prefs p = get();
            p.autoOpen = sp.getBoolean("autoOpen", true);
            p.requireAA = sp.getBoolean("requireAA", true);
            p.requireBT = sp.getBoolean("requireBT", true);
            p.radiusM = sp.getInt("radiusM", 120);
            p.openMode = sp.getInt("openMode", MODE_PAL_NETWORK);
            p.triggerMode = sp.getInt("triggerMode", TRIG_GPS);
            p.rssiTrigDb = sp.getInt("rssiTrigDb", -75);
            p.rssi1m = sp.getInt("rssi1m", -55);
            p.trackCsv = sp.getBoolean("trackCsv", true);
            p.approachFilter = sp.getBoolean("approachFilter", false);
        }

        public static void save(Context ctx) {
            Prefs p = get();
            ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("autoOpen", p.autoOpen)
                    .putBoolean("requireAA", p.requireAA)
                    .putBoolean("requireBT", p.requireBT)
                    .putInt("radiusM", p.radiusM)
                    .putInt("openMode", p.openMode)
                    .putInt("triggerMode", p.triggerMode)
                    .putInt("rssiTrigDb", p.rssiTrigDb)
                    .putInt("rssi1m", p.rssi1m)
                    .putBoolean("trackCsv", p.trackCsv)
                    .putBoolean("approachFilter", p.approachFilter)
                    .apply();
        }

        /** learned BLE MAC -> gate id binding (persisted json-ish "mac=gate;mac=gate") */
        public static void learnMac(Context ctx, String mac, String gateId) {
            SharedPreferences sp = ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE);
            String cur = sp.getString("mac2gate", "");
            Map<String, String> m = parseMap(cur);
            m.put(mac, gateId);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            sp.edit().putString("mac2gate", sb.toString()).apply();
        }

        public static Map<String, String> macMap(Context ctx) {
            return parseMap(ctx.getSharedPreferences("palgate_car", Context.MODE_PRIVATE)
                    .getString("mac2gate", ""));
        }

        private static Map<String, String> parseMap(String s) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            if (s == null) return m;
            for (String part : s.split(";")) {
                int i = part.indexOf('=');
                if (i > 0) m.put(part.substring(0, i), part.substring(i + 1));
            }
            return m;
        }
    }
}
