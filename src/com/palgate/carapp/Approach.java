package com.palgate.carapp;

/**
 * Auto-open approach policy, pure and JVM-testable. All times are MONOTONIC
 * nanoseconds (elapsedRealtimeNanos), never wall clock: device clock changes
 * must not make a stale Location look fresh.
 *
 * This is risk reduction, not radio authentication: the residual spoof/relay
 * risk of an unauthenticated advertisement is disclosed at the auto-open
 * toggle, and autoOpen defaults OFF.
 */
public final class Approach {

    private Approach() {
    }

    /** True only for a fresh, accurate-enough fix; future/bogus times fail. */
    public static boolean freshQualified(long fixElapsedNs, float accM, float radiusM, long nowElapsedNs) {
        if (fixElapsedNs <= 0) return false;
        long age = nowElapsedNs - fixElapsedNs;
        if (age < -2_000_000_000L) return false;          // from the future
        if (age > 15_000_000_000L) return false;          // stale
        return accM > 0 && accM <= Math.max(40f, radiusM * 0.5f);
    }

    /** History fixes noisier than this would let GPS wander fake approach. */
    public static final float HISTORY_MAX_ACC_M = 20f;

    private static final long MIN_WINDOW_NS = 8_000_000_000L;    // 8 s
    private static final long MAX_WINDOW_NS = 12_000_000_000L;   // 12 s
    private static final double MIN_DISPLACEMENT_M = 6.0;

    /**
     * True only on a MEASURED approach: over a window of 8-12 s ending at the
     * last fix, the distance to the gate shrank by more than
     * max(6 m, 2x the last fix's accuracy) — more than the measurement's own
     * noise. A stationary phone's GPS wander stays under that bound. History
     * that is out-of-order, too short, too old, or too noisy fails closed.
     */
    public static boolean approaching(long[] timesNs, double[] lats, double[] lngs,
                                      float[] accsM, Gate gate) {
        if (gate == null || timesNs == null || timesNs.length < 2) return false;
        int n = timesNs.length;
        for (int i = 0; i + 1 < n; i++) {
            if (timesNs[i] >= timesNs[i + 1]) return false;        // incoherent history
        }
        for (int i = 0; i < n; i++) {
            if (accsM[i] <= 0 || accsM[i] > HISTORY_MAX_ACC_M) return false;
        }
        int last = n - 1;
        int first = -1;
        for (int i = n - 2; i >= 0; i--) {
            if (timesNs[last] - timesNs[i] > MAX_WINDOW_NS) break;
            first = i;
        }
        if (first < 0 || timesNs[last] - timesNs[first] < MIN_WINDOW_NS) return false;
        double d0 = gate.distanceM(lats[first], lngs[first]);
        double d1 = gate.distanceM(lats[last], lngs[last]);
        return d0 - d1 > Math.max(MIN_DISPLACEMENT_M, 2.0 * accsM[last]);
    }
}
