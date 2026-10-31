package com.palgate.carapp;

import android.content.Context;

/**
 * Actuation safety seam. A TEST BUILD is marked by an assets/test_build.marker
 * file that build.sh injects ONLY when invoked with TEST_BUILD=1 (CI). The
 * marker is never committed and never present in a normal build, and runtime
 * data (gate ids, fixtures, prefs) plays no role in the decision.
 *
 * In a test build the monitor service's single actuation choke point (runOpen)
 * records a SYNTHETIC-DISPATCH line and returns before any BLE connection or
 * delegate broadcast can happen.
 */
public final class GateSafety {
    private static volatile int cached = -1; // -1 unknown, 0 normal, 1 test build

    private GateSafety() {
    }

    public static boolean isTestBuild(Context ctx) {
        if (cached < 0) {
            boolean found = false;
            try {
                ctx.getAssets().open("test_build.marker").close();
                found = true;
            } catch (Exception ignored) {
                // normal build: no marker
            }
            cached = found ? 1 : 0;
        }
        return cached == 1;
    }
}
