package com.palgate.carapp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-radio dwell + latch for Bluetooth-only auto-open. Pure and JVM-testable;
 * all times are monotonic nanoseconds.
 *
 * Semantics (review amendments A1-A4):
 * - every radio starts LATCHED: it must be observed continuously BELOW the
 *   threshold for REARM_BELOW_NS before it can ever fire, so enabling the
 *   mode while standing at the gate fires nothing;
 * - firing requires the radio to hold AT/ABOVE the threshold for the whole
 *   fire dwell, uninterrupted;
 * - firing latches the radio again; re-arm needs its own sustained drop,
 *   so a short fading dip re-arms nothing (fading is not departure: no
 *   "at most once" guarantee is made anywhere);
 * - state is per radio MAC: one gate's radio never re-arms another's latch;
 * - CONTINUITY: observations must keep coming. A gap longer than OBS_GAP_NS
 *   (scan pause, stale radio, permission loss) resets both clocks - a pause
 *   is neither approach nor departure, and cached/stale sightings never
 *   count as continuous presence;
 * - resetAll() (mode switch, auto-open toggle; service restart gets a fresh
 *   instance) returns every radio to the latched state.
 */
public final class RadioLatch {

    public static final long REARM_BELOW_NS = 30_000_000_000L;   // 30 s sustained below
    public static final long OBS_GAP_NS = 3_000_000_000L;        // max gap between real sightings

    private final Map<String, long[]> st = new HashMap<>();      // mac -> [aboveSinceNs, belowSinceNs, lastObsNs]
    private final Set<String> armed = new HashSet<>();

    public void observe(String mac, boolean aboveThresh, long nowNs) {
        if (mac == null || mac.isEmpty()) return;
        long[] s = st.get(mac);
        if (s == null) {
            s = new long[3];
            st.put(mac, s);
        }
        if (s[2] != 0 && nowNs - s[2] > OBS_GAP_NS) {
            s[0] = 0;   // observation gap: neither approach nor departure
            s[1] = 0;
        }
        s[2] = nowNs;
        if (aboveThresh) {
            if (s[0] == 0) s[0] = nowNs;
            s[1] = 0;
        } else {
            s[0] = 0;
            if (s[1] == 0) s[1] = nowNs;
            if (nowNs - s[1] >= REARM_BELOW_NS) armed.add(mac);
        }
    }

    public boolean isArmed(String mac) {
        return armed.contains(mac);
    }

    /** ns of fire dwell still missing; 0 once the sustained-above dwell is met. */
    public long dwellRemainingNs(String mac, long nowNs, long dwellNs) {
        long[] s = st.get(mac);
        if (s == null || s[0] == 0) return dwellNs;
        return Math.max(0, dwellNs - (nowNs - s[0]));
    }

    public boolean mayFire(String mac, long nowNs, long dwellNs) {
        long[] s = st.get(mac);
        if (s == null || s[2] == 0 || nowNs - s[2] > OBS_GAP_NS) return false;   // stale last sighting
        return armed.contains(mac) && dwellRemainingNs(mac, nowNs, dwellNs) == 0;
    }

    public void fire(String mac) {
        armed.remove(mac);
    }

    public void resetAll() {
        armed.clear();
        st.clear();
    }
}
