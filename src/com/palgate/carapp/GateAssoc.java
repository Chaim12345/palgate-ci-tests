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
}
