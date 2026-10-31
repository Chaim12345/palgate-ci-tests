package com.palgate.carapp;

import java.util.List;
import java.util.Map;

/**
 * Which gate does a sighted BLE radio belong to? Binding-first, never a guess:
 * a PalGate-looking advertisement only counts for a gate once the user has
 * bound that radio's MAC to the gate on this phone. Two gates advertising the
 * same service UUIDs (or a neighbor's unit) must never steer an automatic
 * open to the wrong gate, so unbound radios are display-only.
 */
public final class GateAssoc {

    private GateAssoc() {
    }

    /** The gate bound to this MAC, or null when the radio is unknown. */
    public static Gate boundGate(Map<String, String> mac2gate, List<Gate> gates, String mac) {
        if (mac == null || mac.isEmpty() || mac2gate == null || gates == null) return null;
        String gid = mac2gate.get(mac);
        if (gid == null) return null;
        for (Gate g : gates) {
            if (g.id.equals(gid)) return g;
        }
        return null;
    }

    /** True only when this MAC is bound to exactly the gate we would open. */
    public static boolean mayAutoOpen(Map<String, String> mac2gate, List<Gate> gates,
                                      String mac, Gate target) {
        Gate b = boundGate(mac2gate, gates, mac);
        return b != null && target != null && b.id.equals(target.id);
    }

    // ---- manual open without an explicit gate (widget / generic OPEN) ------
    public static final int RESOLVE_NONE = 0;          // no gates configured
    public static final int RESOLVE_ONE = 1;           // exactly one plausible target
    public static final int RESOLVE_AMBIGUOUS = 2;     // several gates in manual range
    public static final int RESOLVE_OUT_OF_RANGE = 3;  // fix exists, none in manual range

    /**
     * Resolve a target for a generic (widget) open. GPS cannot discriminate
     * gates a few meters apart, so with several gates configured a generic
     * open is only honored when exactly ONE gate is inside rangeM of the
     * phone; anything else must be refused so the user picks the gate
     * explicitly in the app. A single configured gate keeps the old
     * nearest-gate behavior regardless of distance.
     */
    public static int resolveManual(List<Gate> gates, boolean hasFix, double lat, double lng,
                                    double rangeM, Gate[] out) {
        if (gates == null || gates.isEmpty()) return RESOLVE_NONE;
        if (gates.size() == 1) {
            if (out != null && out.length > 0) out[0] = gates.get(0);
            return RESOLVE_ONE;
        }
        if (!hasFix) return RESOLVE_AMBIGUOUS;
        Gate inRange = null;
        int count = 0;
        for (Gate g : gates) {
            if (g.distanceM(lat, lng) <= rangeM) {
                count++;
                inRange = g;
            }
        }
        if (count == 1) {
            if (out != null && out.length > 0) out[0] = inRange;
            return RESOLVE_ONE;
        }
        return count == 0 ? RESOLVE_OUT_OF_RANGE : RESOLVE_AMBIGUOUS;
    }
}
