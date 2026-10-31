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
        v.setTextColor(R.id.w_status, ctx.getColor(s.inRange ? R.color.widget_text_inrange : R.color.widget_text));

        Intent open = new Intent(ctx, GateMonitorService.class);
        open.setAction(GateMonitorService.ACTION_OPEN_NOW);
        PendingIntent pi = PendingIntent.getForegroundService(ctx, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_open, pi);

        // Root tap refreshes the widget (service action, no activity launch, no
        // monitoring start - so no geofence/auto-open side effect). Activity
        // launches are blocked by car widget hosts; service actions work on both
        // phone home screens and Android Auto's widget panel.
        Intent start = new Intent(ctx, GateMonitorService.class);
        start.setAction(GateMonitorService.ACTION_REFRESH);
        PendingIntent piApp = PendingIntent.getForegroundService(ctx, 2, start,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_root, piApp);

        m.updateAppWidget(ids, v);
    }
}
