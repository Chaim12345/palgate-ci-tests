package com.palgate.carapp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Header;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.List;
import java.util.Locale;

/**
 * Car screen v1.2 — live precision readout + custom canvas map.
 *
 * Rows: (1) distance with fix quality + closing speed / ETA, (2) gate radio
 * (BLE RSSI → meters) + trigger config, (3) phase / detail. Map: gates with
 * geofence circles, dashed radio-proximity ring around the gate, GPS accuracy
 * circle around the car, heading-up rotation, scale bar.
 *
 * Template updates are throttled to ~1/s (hosts reject faster refreshes and
 * v1.1 invalidated on every log line); the canvas map repaints at ≥500 ms.
 */
public class MapScreen extends Screen implements State.Listener {

    private SurfaceContainer surface;
    private long lastRender;
    private long lastInvalidateAt;
    private boolean invalidateQueued;
    private String lastToastKey = "";
    private final Handler uiH = new Handler(Looper.getMainLooper());

    public MapScreen(CarContext ctx) {
        super(ctx);
        State.get().addListener(this);
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(surfaceListener);
        // stop listening when the AA session dies, else state updates keep
        // calling invalidate()/render() on a dead screen
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(LifecycleOwner owner) {
                State.get().removeListener(MapScreen.this);
            }
        });
    }

    @Override
    public Template onGetTemplate() {
        State s = State.get();
        State.Prefs p = State.Prefs.get();

        Row dist = new Row.Builder()
                .setTitle(s.distanceText())
                .addText(!s.hasFix
                        ? (s.monitoring ? "listening for fixes…" : "monitor not running")
                        : s.approachText())
                .build();

        String trig = "trigger: " + State.Prefs.trigName(p.triggerMode);
        if (p.triggerMode == State.TRIG_GPS) {
            trig += String.format(Locale.ROOT, " (%d m)", p.radiusM);
        } else if (p.triggerMode == State.TRIG_BLE) {
            trig += String.format(Locale.ROOT, " (≥%d dBm)", p.rssiTrigDb);
        } else {
            trig += String.format(Locale.ROOT, " (%d m / ≥%d dBm)", p.radiusM, p.rssiTrigDb);
        }
        trig += p.autoOpen ? " · auto ✓" : " · auto ✗";
        Row radio = new Row.Builder()
                .setTitle(s.radioText())
                .addText(trig)
                .build();

        Row phaseRow = new Row.Builder()
                .setTitle(s.phaseText())
                .addText(s.phaseDetail.isEmpty()
                        ? "fix: " + (s.hasFix
                                ? (s.fixQualified ? "GPS-qualified " : "too coarse ")
                                        + (s.fixProvider.isEmpty() ? "" : "(" + s.fixProvider + ")")
                                : "none")
                        : s.phaseDetail)
                .build();

        Pane pane = new Pane.Builder()
                .addRow(dist)
                .addRow(radio)
                .addRow(phaseRow)
                .build();

        PaneTemplate content = new PaneTemplate.Builder(pane)
                .setTitle("PalGate")
                .setHeaderAction(Action.APP_ICON)
                .build();

        return new MapWithContentTemplate.Builder()
                .setContentTemplate(content)
                .setActionStrip(new ActionStrip.Builder()
                        .addAction(new Action.Builder()
                                .setTitle(s.inRange || !s.hasFix ? "OPEN" : "OPEN (far)")
                                .setEnabled(!s.bleBusy)
                                .setOnClickListener(new androidx.car.app.model.OnClickListener() {
                                    @Override public void onClick() { openNow(); }
                                })
                                .build())
                        .build())
                .build();
    }

    private void openNow() {
        CarContext ctx = getCarContext();
        android.content.Intent i = new android.content.Intent(ctx, GateMonitorService.class);
        i.setAction(GateMonitorService.ACTION_OPEN_NOW);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            State.get().log("OPEN tapped on car screen");
        } catch (Exception e) {
            State.get().log("could not trigger open from car UI: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onStateChanged() {
        // runs on main thread (State guarantees)
        maybeToast();
        scheduleInvalidate();
        render();
    }

    /** Hosts rate-limit template pushes; send at most ~1/s with one trailing update. */
    private void scheduleInvalidate() {
        long now = System.currentTimeMillis();
        long since = now - lastInvalidateAt;
        if (since >= 1100) {
            lastInvalidateAt = now;
            try {
                invalidate();
            } catch (Exception ignored) {
                // screen already destroyed / session gone
            }
        } else if (!invalidateQueued) {
            invalidateQueued = true;
            uiH.postDelayed(new Runnable() {
                @Override public void run() {
                    invalidateQueued = false;
                    scheduleInvalidate();
                }
            }, 1100 - since);
        }
    }

    /** Speak open events through the car UI itself (not just the log). */
    private void maybeToast() {
        State s = State.get();
        String key = s.phase + "|" + s.phaseDetail;
        if (key.equals(lastToastKey)) return;
        lastToastKey = key;
        String msg = null;
        if (s.phase == State.PHASE_OPENING && s.phaseDetail.startsWith("auto")) {
            msg = "PalGate: opening " + (s.nearest == null ? "gate" : s.nearest.name) + "…";
        } else if (s.phase == State.PHASE_OPENED) {
            msg = s.phaseDetail.contains("PalGate app")
                    ? "PalGate: open command sent — see PalGate app"
                    : "PalGate: gate opened";
        } else if (s.phase == State.PHASE_FAILED) {
            msg = "PalGate: open failed — see log";
        }
        if (msg != null) {
            try {
                CarToast.makeText(getCarContext(), msg, CarToast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }
    }

    // ----------------------------------------------------------- rendering --

    private final SurfaceCallback surfaceListener = new SurfaceCallback() {
        @Override public void onSurfaceAvailable(SurfaceContainer s) {
            surface = s;
            render();
        }

        @Override public void onSurfaceDestroyed(SurfaceContainer s) {
            surface = null;
        }

        @Override public void onVisibleAreaChanged(android.graphics.Rect r) { }

        @Override public void onStableAreaChanged(android.graphics.Rect r) { }
    };

    private void render() {
        final SurfaceContainer sc = surface;
        if (sc == null) return;
        long now = System.currentTimeMillis();
        if (now - lastRender < 500) return;
        lastRender = now;
        Surface s = sc.getSurface();
        if (s == null) return;
        try {
            Canvas c = s.lockCanvas(null);
            try {
                draw(c, sc.getWidth(), sc.getHeight(), sc.getDpi());
            } finally {
                s.unlockCanvasAndPost(c);
            }
        } catch (Exception ignored) {
        }
    }

    private static final int BG = 0xFF0D1117;
    private static final int GRID = 0x22303C48;
    private static final int GRID_MAJOR = 0x3A46606E;
    private static final int GATE_C = 0xFF4FC3F7;
    private static final int GATE_HOT = 0xFF69F0AE;
    private static final int CAR_C = 0xFFFFD54F;
    private static final int TEXT = 0xFFE6F1FF;
    private static final int DIM = 0xFF8899AA;

    private void draw(Canvas c, int w, int h, int dpi) {
        State s = State.get();
        float d = dpi <= 0 ? 160f : dpi;
        float k = d / 160f;   // scale factor vs a 160dpi baseline

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(BG);

        if (!s.hasFix || s.nearest == null) {
            p.setColor(DIM);
            p.setTextSize(14 * k);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("waiting for GPS fix…", w / 2f, h / 2f, p);
            drawHud(c, w, k, s);
            return;
        }

        // visible range: nearest gate + margin, clamped
        double rangeM = Math.max(220.0, Math.min(600.0, s.nearestDistM + 140.0));
        float pxPerM = Math.min(w, h) / (float) rangeM;

        // meters per degree at this latitude
        double mPerDegLat = 111132.0;
        double mPerDegLng = 111320.0 * Math.cos(Math.toRadians(s.lat));

        // heading-up rotation when bearing known
        boolean rotate = s.bearing >= 0 && s.bearing <= 360 && s.speed > 1;
        float rot = rotate ? (float) s.bearing : 0f;

        c.save();
        c.translate(w / 2f, h / 2f);
        if (rotate) c.rotate(-rot);

        // grid, 25 m minor / 100 m major
        p.setStrokeWidth(1);
        for (int m = -600; m <= 600; m += 25) {
            p.setColor(m % 100 == 0 ? GRID_MAJOR : GRID);
            float px = m * pxPerM;
            c.drawLine(px, -h, px, h, p);
            c.drawLine(-w, px, w, px, p);
        }

        List<Gate> gates = Gate.load(getCarContext());   // cached, no re-parse
        int radius = State.Prefs.get().radiusM;
        for (Gate g : gates) {
            float gx = (float) ((g.lng - s.lng) * mPerDegLng * pxPerM);
            float gy = -(float) ((g.lat - s.lat) * mPerDegLat * pxPerM);
            if (Math.abs(gx) > w || Math.abs(gy) > h) continue;

            boolean isNear = g.id.equals(s.nearest.id);
            double dist = g.distanceM(s.lat, s.lng);
            boolean in = dist <= radius;

            // geofence circle
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * k);
            p.setColor(in ? GATE_HOT : (isNear ? GATE_C : Color.argb(160, 79, 195, 247)));
            c.drawCircle(gx, gy, radius * pxPerM, p);

            // dashed radio-proximity ring: gate's own BLE signal says this far
            if (isNear && s.radioFresh() && s.rssiEstM > 1
                    && s.rssiEstM * pxPerM < Math.max(w, h)) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * k);
                p.setColor(0x9969F0AE);
                p.setPathEffect(new DashPathEffect(new float[]{6 * k, 5 * k}, 0));
                c.drawCircle(gx, gy, (float) (s.rssiEstM * pxPerM), p);
                p.setPathEffect(null);
            }

            // gate glyph: post + 3 barrier bars
            p.setStyle(Paint.Style.FILL);
            p.setColor(in ? GATE_HOT : GATE_C);
            c.drawRoundRect(gx - 14 * k, gy - 16 * k, 6 * k, 32 * k, 2 * k, 2 * k, p);
            c.drawRoundRect(gx + 8 * k, gy - 16 * k, 6 * k, 32 * k, 2 * k, 2 * k, p);
            for (int i = 0; i < 3; i++) {
                float y = gy - 9 * k + i * 9 * k;
                c.drawRoundRect(gx - 12 * k, y, gx + 12 * k, y + 3 * k, 1.5f * k, 1.5f * k, p);
            }

            // label (counter-rotate so it stays readable)
            c.save();
            c.translate(gx, gy + 26 * k);
            if (rotate) c.rotate(rot);
            p.setColor(TEXT);
            p.setTextSize(11 * k);
            p.setTextAlign(Paint.Align.CENTER);
            String label = String.format(Locale.ROOT, "%s · %.0f m", g.name, dist);
            if (g.posOverridden) label += " ⌖";
            c.drawText(label, 0, 0, p);
            c.restore();
        }

        // GPS accuracy circle around the car — honesty about the fix quality
        if (s.accuracy > 1 && s.accuracy * pxPerM < Math.max(w, h) * 0.9f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x18FFD54F);
            c.drawCircle(0, 0, s.accuracy * pxPerM, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x46FFD54F);
            c.drawCircle(0, 0, s.accuracy * pxPerM, p);
        }

        // car at center (always up when rotating)
        p.setStyle(Paint.Style.FILL);
        p.setColor(CAR_C);
        Path car = new Path();
        car.moveTo(0, -10 * k);
        car.lineTo(7 * k, 9 * k);
        car.lineTo(0, 5 * k);
        car.lineTo(-7 * k, 9 * k);
        car.close();
        c.drawPath(car, p);
        c.restore();

        // north indicator
        if (rotate) {
            c.save();
            c.translate(w - 26 * k, 30 * k);
            c.rotate(-rot);
            p.setColor(DIM);
            p.setTextSize(11 * k);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("N↑", 0, 0, p);
            c.restore();
        }

        // scale bar — 50 m
        p.setColor(DIM);
        p.setStrokeWidth(2 * k);
        c.drawLine(14 * k, h - 16 * k, 14 * k + 50 * pxPerM, h - 16 * k, p);
        p.setTextSize(10 * k);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("50 m", 14 * k, h - 22 * k, p);

        drawHud(c, w, k, s);
    }

    private void drawHud(Canvas c, int w, float k, State s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(TEXT);
        p.setTextSize(12 * k);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(s.phaseText(), 14 * k, 22 * k, p);
        p.setColor(DIM);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(10 * k);
        c.drawText(s.statusLine(), 14 * k, 38 * k, p);
        if (s.hasFix) {
            c.drawText(String.format(Locale.ROOT, "%.0f km/h · %s", s.speed * 3.6, s.approachText()),
                    14 * k, 52 * k, p);
        }
        c.drawText(s.radioText(), 14 * k, 66 * k, p);
    }

    @SuppressWarnings("unused")
    private static Matrix unusedMatrix() { return new Matrix(); }
}
