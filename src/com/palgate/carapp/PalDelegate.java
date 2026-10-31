package com.palgate.carapp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.RemoteViews;

import com.bluegate.shared.data.types.Device;

import java.util.List;

/**
 * Opens the gate by delegating to the OFFICIAL PalGate app through its
 * exported home-screen widget receivers.
 *
 * The official app (com.bluegate.app) registers
 *   com.bluegate.shared.widget.PalWidget      (exported=true)
 *   com.bluegate.shared.widget.PalWidgetLarge (exported=true)
 * with intent-filter actions
 *   com.bluegate.app.broadcast.3gOpen    — open over the network (cloud API)
 *   com.bluegate.app.broadcast.4gBtOpen  — network + their own BLE fallback
 *   com.bluegate.app.broadcast.btOpen    — their own BLE stack only
 *
 * A widget button press in the official app sends exactly this broadcast,
 * with extras: device (their Device Parcelable), relayToOpen ("1"/"2"),
 * appWidgetId (int), view (RemoteViews), isEsp, widgetIcon, epochTime.
 * The receiver forwards everything to PalWidgetGateOpenService, which calls
 * the PalGate cloud API with the session stored inside the official app.
 *
 * We rebuild that broadcast byte-for-byte from our cached gate data: the
 * Device extra is produced by our Parcel-compatible stand-in class
 * (com.bluegate.shared.data.types.Device), which the official app's class
 * loader transparently deserializes into its real Device class.
 */
public final class PalDelegate {

    public static final String PAL_PACKAGE = "com.bluegate.app";
    public static final String RECEIVER = "com.bluegate.shared.widget.PalWidget";

    public static final String ACTION_3G_OPEN = "com.bluegate.app.broadcast.3gOpen";
    public static final String ACTION_4GBT_OPEN = "com.bluegate.app.broadcast.4gBtOpen";
    public static final String ACTION_BT_OPEN = "com.bluegate.app.broadcast.btOpen";

    /** Synthetic widget id used when the user has no PalGate widget pinned. */
    private static final int FAKE_WIDGET_ID = 990001;

    private PalDelegate() {
    }

    /** Is the official PalGate app installed (and visible to us)? */
    public static boolean palgateInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PAL_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fire the widget open command for the given gate.
     *
     * @param action one of ACTION_3G_OPEN / ACTION_4GBT_OPEN / ACTION_BT_OPEN
     * @return null on success, or a human-readable failure reason.
     */
    public static String open(Context ctx, Gate gate, String action) {
        if (!palgateInstalled(ctx)) {
            return "official PalGate app not installed";
        }
        try {
            Device d = forge(gate);

            RemoteViews view = new RemoteViews(PAL_PACKAGE, R.layout.widget_gate);
            int widgetId = realWidgetId(ctx);

            Intent i = new Intent(action);
            i.setPackage(PAL_PACKAGE);
            i.setComponent(new ComponentName(PAL_PACKAGE, RECEIVER));
            i.putExtra("device", (android.os.Parcelable) d);
            i.putExtra("relayToOpen", "1");
            i.putExtra("appWidgetId", widgetId);
            i.putExtra("view", (android.os.Parcelable) view);
            i.putExtra("isEsp", false);
            i.putExtra("widgetIcon", "barrier");
            i.putExtra("epochTime", System.currentTimeMillis() / 1000L);
            ctx.sendBroadcast(i);
            return null;
        } catch (Exception e) {
            return "delegate failed: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    /** Build the Parcel-compatible Device from our cached gate data. */
    private static Device forge(Gate g) {
        Device d = new Device();
        d.id = g.id;                       // e.g. TEST000001 — the API deviceId
        d.key = g.keyHex;                  // per-gate AES key (BLE path inside their app)
        d.model = g.model;                 // SG304GI
        d.macAddress = g.mac;
        d.address = g.address;
        d.name1 = g.name;
        d.name2 = "";
        d.customName1 = g.name;
        d.customName2 = "";
        d.displayName = g.name;
        d.relay1 = g.relay;                // "02"
        d.relay2 = "";
        d.validUntil = g.validUntil;
        d.simStatus = "activated";
        d.groupId = "0";
        d.gateIndex = 0;
        d.callOrder = 0;
        d.output1 = Boolean.TRUE;
        d.output2 = Boolean.FALSE;
        d.output1Disabled = Boolean.FALSE;
        d.output2Disabled = Boolean.FALSE;
        d.localOnly = Boolean.FALSE;
        return d;
    }

    /**
     * If the user has a real PalGate widget on the home screen, borrow its
     * appWidgetId so the official app refreshes that widget after opening.
     * Otherwise use a synthetic id (the open still works; only the visual
     * widget refresh is skipped by the launcher).
     */
    private static int realWidgetId(Context ctx) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
            if (awm != null) {
                List<AppWidgetProviderInfo> provs = awm.getInstalledProviders();
                if (provs != null) {
                    for (AppWidgetProviderInfo p : provs) {
                        if (PAL_PACKAGE.equals(p.provider.getPackageName())) {
                            int[] ids = awm.getAppWidgetIds(p.provider);
                            if (ids != null && ids.length > 0) return ids[0];
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return FAKE_WIDGET_ID;
    }
}
