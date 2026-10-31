package com.palgate.widgethost;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Minimal AppWidgetHost for CI: binds the PalGate widget in-process,
 * renders it via createView, then auto-clicks the first clickable child
 * (the widget's OPEN button) and reports through logcat + on-screen text.
 * Requires: adb shell appwidget grantbind --package com.palgate.widgethost
 */
public class HostActivity extends Activity {
    private static final String TAG = "WidgetHostTest";
    private static final int HOST_ID = 2042;
    private AppWidgetHost host;
    private AppWidgetManager mgr;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        status = new TextView(this);
        status.setText("host: starting");
        status.setTextSize(14);
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        mgr = AppWidgetManager.getInstance(this);
        host = new AppWidgetHost(this, HOST_ID);
        int id = host.allocateAppWidgetId();
        ComponentName provider = new ComponentName("com.palgate.carapp",
                "com.palgate.carapp.GateWidgetProvider");
        boolean bound = mgr.bindAppWidgetIdIfAllowed(id, provider);
        Log.i(TAG, "bindAppWidgetIdIfAllowed=" + bound);
        if (!bound) { set("HOST-RESULT: BIND-FAILED"); return; }

        AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
        if (info == null) { set("HOST-RESULT: NO-INFO"); return; }
        host.startListening();
        AppWidgetHostView view = host.createView(this, id, info);
        view.setAppWidget(id, info);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 120;
        root.addView(view, lp);
        set("HOST-RESULT: RENDERED provider=" + info.provider);
        Log.i(TAG, "widget rendered, scheduling auto-click");

        final AppWidgetHostView fv = view;
        new Handler().postDelayed(new Runnable() {
            @Override public void run() {
                // Prefer the widget's OPEN GATE button; fall back to any clickable child.
                boolean clicked = clickByText(fv, "OPEN GATE");
                Log.i(TAG, "auto-click OPEN result=" + clicked);
                if (clicked) { set("HOST-RESULT: OPEN-TAPPED"); return; }
                clicked = clickFirst(fv);
                Log.i(TAG, "auto-click fallback result=" + clicked);
                set("HOST-RESULT: " + (clicked ? "TAPPED" : "NO-CLICKABLE"));
            }
        }, 4000);
    }

    private boolean clickByText(View v, String text) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.toString().contains(text) && v.isClickable()) {
                v.performClick();
                return true;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (clickByText(g.getChildAt(i), text)) return true;
            }
        }
        return false;
    }

    private boolean clickFirst(View v) {
        if (v.isClickable()) { v.performClick(); return true; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                if (clickFirst(g.getChildAt(i))) return true;
        }
        return false;
    }

    private void set(String s) { status.setText(s); Log.i(TAG, s); }

    @Override protected void onDestroy() { super.onDestroy(); host.stopListening(); }
}
