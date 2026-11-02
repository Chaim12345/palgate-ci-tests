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
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    /** Library default grid limit (content_limit_grid = 6 in
     *  androidx.car.app:app:1.4.0 res/values/values.xml); the host-reported
     *  limit via ConstraintManager wins when the negotiated API allows it. */
    private static final int FALLBACK_GRID_LIMIT = 6;

    /** A GPS fix older than this is stale: never sort or display by it. */
    private static final long FIX_FRESH_MS = 30_000;

    /** Session-stable gate ordering (gate ids): computed ONCE so the ~1/s
     *  template refresh can never shuffle tap targets under a driver's
     *  finger. Recomputed only when a brand-new Screen instance is created
     *  (new session), never on invalidate(). */
    private List<String> frozenOrder;
    private boolean frozenByDistance;

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
        int limit = gridContentLimit();

        ItemList.Builder list = new ItemList.Builder();
        String title = "PalGate - " + s.phaseText();
        if (gates.isEmpty()) {
            list.addItem(new GridItem.Builder()
                    .setTitle("No gates configured")
                    .setText("Add gates in the PalGate Car phone app")
                    .setImage(icon(), GridItem.IMAGE_TYPE_ICON)
                    .build());
        } else {
            List<Gate> show = applySessionOrder(gates, s);
            String capNote = "";
            if (show.size() > limit) {
                // Hosts reject the whole template above the content limit.
                // Disclose the cap inside a normal template title (no extra
                // items, no invalid template); the full list stays in the
                // phone app.
                capNote = frozenByDistance
                        ? " (nearest " + limit + " of " + show.size() + ")"
                        : " (first " + limit + " of " + show.size() + ")";
                show = new ArrayList<>(show.subList(0, limit));
            }
            title += capNote;
            for (final Gate g : show) {
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
                .setTitle(title)
                .setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build())
                .build();
    }

    /** Host-reported grid limit when available (ConstraintManager is
     *  @RequiresCarApi(2); getCarAppApiLevel() itself is API-1 safe), else
     *  the library's own default. */
    private int gridContentLimit() {
        try {
            if (getCarContext().getCarAppApiLevel() >= 2) {
                ConstraintManager cm = (ConstraintManager) getCarContext()
                        .getCarService(ConstraintManager.class);
                int limit = cm.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID);
                if (limit > 0) return limit;
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_GRID_LIMIT;
    }

    private static boolean freshFix(State s) {
        return s.hasFix && s.lastFixAtMs > 0
                && System.currentTimeMillis() - s.lastFixAtMs <= FIX_FRESH_MS;
    }

    /** Compute the gate ordering once per screen session, then freeze it.
     *  Nearest-first only when the fix is FRESH (a sticky/stale fix must not
     *  silently reorder targets); otherwise the configured order. Gates that
     *  appear later append at the end; removed gates drop out. */
    private List<Gate> applySessionOrder(List<Gate> gates, State s) {
        if (frozenOrder == null) {
            List<Gate> ordered = new ArrayList<>(gates);
            frozenByDistance = freshFix(s);
            if (frozenByDistance) {
                final double lat = s.lat;
                final double lng = s.lng;
                Collections.sort(ordered, new Comparator<Gate>() {
                    @Override public int compare(Gate a, Gate b) {
                        return Double.compare(a.distanceM(lat, lng), b.distanceM(lat, lng));
                    }
                });
            }
            frozenOrder = new ArrayList<>();
            for (Gate g : ordered) frozenOrder.add(g.id);
        }
        List<Gate> show = new ArrayList<>();
        for (String id : frozenOrder) {
            for (Gate g : gates) {
                if (g.id.equals(id)) { show.add(g); break; }
            }
        }
        for (Gate g : gates) {
            if (!frozenOrder.contains(g.id)) {
                show.add(g);
                frozenOrder.add(g.id); // freeze late arrivals too
            }
        }
        return show;
    }

    private CarIcon icon() {
        return new CarIcon.Builder(
                IconCompat.createWithResource(getCarContext(), R.drawable.ic_launcher)).build();
    }

    private static String subtitle(State s, Gate g) {
        String dist = freshFix(s)
                ? String.format(Locale.ROOT, "%.0f m", g.distanceM(s.lat, s.lng))
                : "no fresh GPS fix";
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
