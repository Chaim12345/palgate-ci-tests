package com.palgate.carapp;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;

import java.util.List;
import java.util.Locale;

/**
 * Car screen v1.4 - IoT category.
 *
 * IoT is Android Auto's official category for gate / garage control
 * (developer.android.com/training/cars/apps/iot). One grid item per gate;
 * a tap opens THAT gate by id (no nearest-gate guessing on the car screen).
 * The phone app / home-screen widget are unchanged.
 *
 * Safety: this screen only sends an intent to GateMonitorService; the
 * service's runOpen choke point applies the test-build marker, cooldowns,
 * binding and refusal policy exactly like the widget and phone UI.
 *
 * Template updates are throttled to ~1/s (hosts rate-limit invalidations).
 */
public class GateGridScreen extends Screen implements State.Listener {

    private long lastInvalidateAt;
    private boolean invalidateQueued;
    private String lastToastKey = "";
    private final Handler uiH = new Handler(Looper.getMainLooper());

    public GateGridScreen(CarContext ctx) {
        super(ctx);
        State.get().addListener(this);
    }

    @Override
    public Template onGetTemplate() {
        State s = State.get();
        List<Gate> gates = Gate.load(getCarContext());

        ItemList.Builder list = new ItemList.Builder();
        if (gates.isEmpty()) {
            list.addItem(new GridItem.Builder()
                    .setTitle("No gates configured")
                    .setText("Add gates in the PalGate Car phone app")
                    .setImage(icon(), GridItem.IMAGE_TYPE_ICON)
                    .build());
        } else {
            for (final Gate g : gates) {
                list.addItem(new GridItem.Builder()
                        .setTitle(g.name)
                        .setText(subtitle(s, g))
                        .setImage(icon(), GridItem.IMAGE_TYPE_ICON)
                        .setOnClickListener(new androidx.car.app.model.OnClickListener() {
                            @Override public void onClick() { openGate(g); }
                        })
                        .build());
            }
        }

        return new GridTemplate.Builder()
                .setTitle("PalGate - " + s.phaseText())
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build())
                .build();
    }

    private CarIcon icon() {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), R.drawable.ic_launcher)).build();
    }

    private static String subtitle(State s, Gate g) {
        String dist = s.hasFix
                ? String.format(Locale.ROOT, "%.0f m", g.distanceM(s.lat, s.lng))
                : "no GPS fix";
        return dist + (Gate.hasUsableKey(g) ? " · key ✓" : " · no key on phone");
    }

    private void openGate(Gate g) {
        CarContext ctx = getCarContext();
        Intent i = new Intent(ctx, GateMonitorService.class);
        i.setAction(GateMonitorService.ACTION_OPEN_NOW);
        i.putExtra("gateId", g.id);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            State.get().log("OPEN tapped on car screen for " + g.name);
        } catch (Exception e) {
            State.get().log("could not trigger open from car UI: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onStateChanged() {
        // runs on main thread (State guarantees)
        maybeToast();
        scheduleInvalidate();
    }

    /** Hosts rate-limit template pushes; send at most ~1/s with one trailing update. */
    private void scheduleInvalidate() {
        long now = System.currentTimeMillis();
        long since = now - lastInvalidateAt;
        if (since >= 1100) {
            lastInvalidateAt = now;
            invalidate();
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
}
