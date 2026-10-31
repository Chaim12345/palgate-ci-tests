package com.palgate.carapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Phone home-screen widget: status + always-available OPEN button. */
public class GateWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager m, int[] ids) {
        push(ctx);
    }

    /** Re-render every widget instance from current state. */
    public static void push(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        if (m == null) return;
        ComponentName cn = new ComponentName(ctx, GateWidgetProvider.class);
        int[] ids = m.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;

        State s = State.get();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_gate);

        String title = s.nearest == null ? "PalGate"
                : String.format(java.util.Locale.ROOT, "PalGate — %s", s.nearest.name);
        String status;
        if (!s.monitoring) {
            status = "not monitoring\nopen PalGate app or Android Auto app";
        } else if (!s.hasFix) {
            status = "monitoring — waiting for GPS…";
        } else if (s.inRange) {
            status = "IN RANGE · " + s.phaseText();
        } else {
            status = String.format(java.util.Locale.ROOT, "%.0f m away", s.nearestDistM);
        }
        v.setTextViewText(R.id.w_title, title);
        v.setTextViewText(R.id.w_status, status);
        v.setTextColor(R.id.w_status, s.inRange ? 0xFF69F0AE : 0xFFE6F1FF);

        Intent open = new Intent(ctx, GateMonitorService.class);
        open.setAction(GateMonitorService.ACTION_OPEN_NOW);
        PendingIntent pi = PendingIntent.getForegroundService(ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_open, pi);

        Intent app = new Intent(ctx, PhoneActivity.class);
        PendingIntent piApp = PendingIntent.getActivity(ctx, 2, app,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_root, piApp);

        m.updateAppWidget(ids, v);
    }
}
