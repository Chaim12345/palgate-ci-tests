package com.palgate.carapp;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Criteria;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phone-side UI v1.2 — permission bootstrap, settings, manual open, status,
 * and the precision toolkit: trigger mode, RSSI threshold + @1 m calibration,
 * on-site gate position calibration, track CSV export.
 */
public class PhoneActivity extends Activity implements State.Listener {

    private TextView tvStatus, tvCap, tvLog;
    private ScrollView scrollLog;

    // gate-position capture
    private LocationManager lm;
    private boolean capturing;
    private Gate captureGate;
    private final List<double[]> captureFixes = new ArrayList<double[]>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable captureEnd = new Runnable() {
        @Override public void run() { finishCapture(); }
    };
    private final LocationListener captureListener = new LocationListener() {
        @Override public void onLocationChanged(android.location.Location loc) {
            if (!capturing) return;
            float acc = loc.hasAccuracy() ? loc.getAccuracy() : 999f;
            captureFixes.add(new double[]{loc.getLatitude(), loc.getLongitude(), acc});
            if (tvCap != null) {
                tvCap.setText(String.format(Locale.ROOT,
                        "capturing %s: %d fixes (best ±%.0f m)…",
                        captureGate.name, captureFixes.size(), bestAcc()));
            }
        }
    };

    // RSSI @1m calibration
    private boolean calibrating;
    private final List<Integer> calibSamples = new ArrayList<Integer>();
    private BluetoothAdapter calibAdapter;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        State.Prefs.load(this);
        buildUi();
        State.get().addListener(this);
        ensurePerms();
    }

    @Override
    protected void onDestroy() {
        State.get().removeListener(this);
        try {
            if (lm != null) lm.removeUpdates(captureListener);
        } catch (Exception ignored) {
        }
        stopCalib();
        h.removeCallbacks(captureEnd);
        super.onDestroy();
    }

    @Override
    public void onStateChanged() {
        runOnUiThread(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    // ------------------------------------------------------------------ UI --

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        outer.addView(root);
        setContentView(outer);

        TextView t = new TextView(this);
        t.setText("PalGate Car — Android Auto gate opener");
        t.setTextSize(19);
        t.setTypeface(null, Typeface.BOLD);
        root.addView(t);
        TextView sub = new TextView(this);
        sub.setText("Opens via the official PalGate app's widget command (network), "
                + "or direct BLE with the cached key. No server of ours, no login of ours.\n"
                + "v1.3: auto-open starts OFF — test detection first. Walk-up needs no car connection. Radio trigger fires only for a bound gate radio.");
        sub.setPadding(0, dp(2), 0, dp(10));
        root.addView(sub);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        tvStatus.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(tvStatus);

        tvCap = new TextView(this);
        tvCap.setTextSize(12);
        tvCap.setTypeface(Typeface.MONOSPACE);
        tvCap.setPadding(dp(8), 0, dp(8), dp(8));
        root.addView(tvCap);

        Button start = new Button(this);
        start.setText("START monitoring now");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { svc(GateMonitorService.ACTION_START); }
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("stop monitoring");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { svc(GateMonitorService.ACTION_STOP); }
        });
        root.addView(stop);

        Button open = new Button(this);
        open.setText("OPEN GATE NOW (current mode)");
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { svc(GateMonitorService.ACTION_OPEN_NOW); }
        });
        root.addView(open);

        // ------------------------------------------------------ open mode --
        root.addView(label("OPEN MODE — how the gate is opened"));
        String[] modeTitles = {
                "1 · PalGate app — NETWORK (3g open, cloud API)",
                "2 · PalGate app — NETWORK + BLE (4g+BT, like their widget)",
                "3 · PalGate app — BLE only (their BT stack)",
                "4 · Direct BLE from this app (offline, cached key)"
        };
        final int[] modeVals = {
                State.Prefs.MODE_PAL_NETWORK, State.Prefs.MODE_PAL_4GBT,
                State.Prefs.MODE_PAL_BT, State.Prefs.MODE_DIRECT_BLE
        };
        for (int i = 0; i < modeTitles.length; i++) {
            final int val = modeVals[i];
            final TextView row = new TextView(this);
            boolean cur = State.Prefs.get().openMode == val;
            row.setText((cur ? "◉ " : "○ ") + modeTitles[i]);
            row.setTextSize(14);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    State.Prefs.get().openMode = val;
                    State.Prefs.save(PhoneActivity.this);
                    recreate();
                }
            });
            root.addView(row);
        }
        String pal = PalDelegate.palgateInstalled(this)
                ? "official PalGate app: INSTALLED ✓ (login inside it stays active)"
                : "official PalGate app: NOT FOUND — delegate modes need it installed";
        TextView palv = new TextView(this);
        palv.setText(pal);
        palv.setTextSize(12);
        palv.setPadding(dp(6), dp(2), dp(6), dp(8));
        root.addView(palv);

        // ------------------------------------------------------- triggers --
        root.addView(label("AUTO-OPEN TRIGGER — what fires the open"));
        String[] trigTitles = {
                "A · GPS radius only (classic geofence)",
                "B · Gate radio only — open when BLE RSSI ≥ threshold (most exact at the barrier)",
                "C · Either — GPS radius or gate radio, whichever first"
        };
        final int[] trigVals = { State.TRIG_GPS, State.TRIG_BLE, State.TRIG_EITHER };
        for (int i = 0; i < trigTitles.length; i++) {
            final int val = trigVals[i];
            final TextView row = new TextView(this);
            boolean cur = State.Prefs.get().triggerMode == val;
            row.setText((cur ? "◉ " : "○ ") + trigTitles[i]);
            row.setTextSize(14);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    State.Prefs.get().triggerMode = val;
                    State.Prefs.save(PhoneActivity.this);
                    recreate();
                }
            });
            root.addView(row);
        }

        root.addView(label("GATE RADIO THRESHOLD (dBm) — e.g. -75 ≈ 10 m, -85 ≈ 30 m after calibration"));
        final EditText edDb = new EditText(this);
        edDb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        edDb.setText(String.valueOf(State.Prefs.get().rssiTrigDb));
        root.addView(edDb);
        Button setDb = new Button(this);
        setDb.setText("save radio threshold");
        setDb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int db = Integer.parseInt(edDb.getText().toString().trim());
                    if (db < -100 || db > -40) throw new NumberFormatException();
                    State.Prefs.get().rssiTrigDb = db;
                    State.Prefs.save(PhoneActivity.this);
                    State.get().log("radio trigger threshold set to " + db + " dBm");
                    toast("threshold " + db + " dBm saved");
                } catch (NumberFormatException e) {
                    toast("enter a value between -100 and -40");
                }
            }
        });
        root.addView(setDb);

        Button cal = new Button(this);
        cal.setText("CALIBRATE gate radio @ 1 m (stand next to the gate, then tap)");
        cal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startCalib(); }
        });
        root.addView(cal);

        // -------------------------------------------------------- radius --
        root.addView(label("GEOFENCE RADIUS (m) — enter 80 / 120 / 180 / 250"));
        final EditText edR = new EditText(this);
        edR.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        edR.setText(String.valueOf(State.Prefs.get().radiusM));
        root.addView(edR);
        Button setR = new Button(this);
        setR.setText("save radius");
        setR.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    State.Prefs.get().radiusM = Integer.parseInt(edR.getText().toString().trim());
                    State.Prefs.save(PhoneActivity.this);
                    State.get().log("radius set to " + State.Prefs.get().radiusM + " m");
                } catch (NumberFormatException e) {
                    toast("enter a number");
                }
            }
        });
        root.addView(setR);

        final State.Prefs p = State.Prefs.get();
        addToggle(root, "auto-open when trigger fires", p.autoOpen, new ToggleCb() {
            @Override public void set(boolean v) { p.autoOpen = v; State.Prefs.save(PhoneActivity.this); }
        });
        addToggle(root, "require Android Auto connected", p.requireAA, new ToggleCb() {
            @Override public void set(boolean v) { p.requireAA = v; State.Prefs.save(PhoneActivity.this); }
        });
        addToggle(root, "require car Bluetooth connected", p.requireBT, new ToggleCb() {
            @Override public void set(boolean v) { p.requireBT = v; State.Prefs.save(PhoneActivity.this); }
        });
        addToggle(root, "GPS trigger only while closing (approach filter)", p.approachFilter, new ToggleCb() {
            @Override public void set(boolean v) { p.approachFilter = v; State.Prefs.save(PhoneActivity.this); }
        });
        addToggle(root, "write track CSV (review measured approach)", p.trackCsv, new ToggleCb() {
            @Override public void set(boolean v) { p.trackCsv = v; State.Prefs.save(PhoneActivity.this); }
        });
        addToggle(root, "TEST MODE — ignore AA/BT gates (walk test)", State.get().testMode, new ToggleCb() {
            @Override public void set(boolean v) { State.get().testMode = v; }
        });

        // ------------------------------------------- gates + calibration --
        root.addView(label("GATES — bundled gates ship keyless; keys you enter stay on this phone (private storage, backup off)"));
        final Map<String, String> bindings = State.Prefs.macMap(this);
        final State stNow = State.get();
        final boolean freshRadio = stNow.rssi < 127 && !stNow.rssiMac.isEmpty()
                && System.currentTimeMillis() - stNow.rssiAtMs < 30000;
        for (final Gate g : Gate.load(this)) {
            boolean ov = Gate.hasOverride(this, g.id);
            final boolean rt = Gate.isRuntimeGate(this, g.id);
            String boundMac = null;
            for (Map.Entry<String, String> e : bindings.entrySet()) {
                if (e.getValue().equals(g.id)) boundMac = e.getKey();
            }
            final String boundMacF = boundMac;
            TextView info = new TextView(this);
            info.setText(String.format(Locale.ROOT,
                    "%s · %s · %s%n  %s%.6f, %.6f%n  BLE key: %s · radio: %s",
                    g.name, g.id, rt ? "on-device" : "bundled",
                    ov ? "⌖ calibrated: " : "cloud: ", g.lat, g.lng,
                    Gate.hasUsableKey(g) ? "set ✓" : "missing (PalGate-app network mode still works)",
                    boundMac == null ? "unbound" : boundMac));
            info.setTypeface(Typeface.MONOSPACE);
            info.setTextSize(11);
            info.setPadding(0, dp(8), 0, dp(2));
            root.addView(info);

            Button openG = new Button(this);
            openG.setText("OPEN " + g.name + " NOW");
            openG.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { svcOpen(g.id); }
            });
            root.addView(openG);

            final EditText edKey = new EditText(this);
            edKey.setHint("BLE key (32 hex) for " + g.name);
            edKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            root.addView(edKey);
            Button setKey = new Button(this);
            setKey.setText("save BLE key for " + g.name);
            setKey.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String k = edKey.getText().toString().trim();
                    if (!k.matches("(?i)[0-9a-f]{32}")) {
                        toast("key must be 32 hex chars (16 bytes)");
                        return;
                    }
                    Gate.setKeyOverride(PhoneActivity.this, g.id, k.toUpperCase(Locale.ROOT));
                    State.get().log("BLE key saved on-device for " + g.name);
                    toast("key saved for " + g.name + " (stays on this phone)");
                    recreate();
                }
            });
            root.addView(setKey);

            if (freshRadio && !stNow.rssiMac.equals(boundMacF)) {
                Button bind = new Button(this);
                bind.setText("bind seen radio " + stNow.rssiMac + " → " + g.name);
                bind.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        State.Prefs.learnMac(PhoneActivity.this, stNow.rssiMac, g.id);
                        State.get().log("radio " + stNow.rssiMac + " bound to " + g.name);
                        toast("bound — this radio now triggers " + g.name);
                        recreate();
                    }
                });
                root.addView(bind);
            }
            if (boundMac != null) {
                Button unbind = new Button(this);
                unbind.setText("unbind radio " + boundMacF);
                unbind.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        State.Prefs.unlearnMac(PhoneActivity.this, boundMacF);
                        State.get().log("radio " + boundMacF + " unbound from " + g.name);
                        recreate();
                    }
                });
                root.addView(unbind);
            }

            Button cap = new Button(this);
            cap.setText(String.format(Locale.ROOT,
                    "set %s position = HERE (20 s GPS average)", g.name));
            cap.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { startCapture(g); }
            });
            root.addView(cap);

            if (ov) {
                Button rst = new Button(this);
                rst.setText("reset " + g.name + " to cloud position");
                rst.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Gate.clearOverride(PhoneActivity.this, g.id);
                        State.get().log(g.name + " position reset to cloud value");
                        recreate();
                    }
                });
                root.addView(rst);
            }
            if (rt) {
                Button rm = new Button(this);
                rm.setText("remove on-device gate " + g.name);
                rm.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Gate.removeRuntimeGate(PhoneActivity.this, g.id);
                        State.get().log("on-device gate " + g.name + " removed");
                        recreate();
                    }
                });
                root.addView(rm);
            }
        }

        // ---------------------------------------------------- add a gate --
        root.addView(label("ADD GATE ON THIS PHONE — from your own PalGate account details"));
        final EditText edId = new EditText(this);
        edId.setHint("gate id (e.g. 4G…)");
        final EditText edName = new EditText(this);
        edName.setHint("name (e.g. Gate 1)");
        final EditText edNKey = new EditText(this);
        edNKey.setHint("BLE key, 32 hex (optional)");
        final EditText edLat = new EditText(this);
        edLat.setHint("latitude (or use GPS fix)");
        final EditText edLng = new EditText(this);
        edLng.setHint("longitude (or use GPS fix)");
        final EditText edRelay = new EditText(this);
        edRelay.setHint("relay (default 02)");
        root.addView(edId);
        root.addView(edName);
        root.addView(edNKey);
        root.addView(edLat);
        root.addView(edLng);
        root.addView(edRelay);
        Button useFix = new Button(this);
        useFix.setText("use last GPS fix for position");
        useFix.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                State s = State.get();
                if (!s.hasFix) {
                    toast("no GPS fix yet");
                    return;
                }
                edLat.setText(String.format(Locale.ROOT, "%.6f", s.lat));
                edLng.setText(String.format(Locale.ROOT, "%.6f", s.lng));
            }
        });
        root.addView(useFix);
        Button add = new Button(this);
        add.setText("ADD GATE");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String id = edId.getText().toString().trim();
                String name = edName.getText().toString().trim();
                String key = edNKey.getText().toString().trim();
                String relay = edRelay.getText().toString().trim();
                if (id.isEmpty() || id.contains(";") || id.contains("=")) {
                    toast("gate id required (no ; or =)");
                    return;
                }
                if (!key.isEmpty() && !key.matches("(?i)[0-9a-f]{32}")) {
                    toast("key must be 32 hex chars, or leave empty");
                    return;
                }
                double lat, lng;
                try {
                    lat = Double.parseDouble(edLat.getText().toString().trim());
                    lng = Double.parseDouble(edLng.getText().toString().trim());
                } catch (NumberFormatException e) {
                    State s2 = State.get();
                    if (!s2.hasFix) {
                        toast("enter coordinates or tap USE LAST GPS FIX");
                        return;
                    }
                    lat = s2.lat;
                    lng = s2.lng;
                }
                if (Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                    toast("bad coordinates");
                    return;
                }
                Gate g = new Gate(id, name.isEmpty() ? id : name, lat, lng,
                        key.toUpperCase(Locale.ROOT), "SG304GI", null, "",
                        relay.isEmpty() ? "02" : relay, "2027-10-11T21:00:00.000Z");
                Gate.addRuntimeGate(PhoneActivity.this, g);
                State.get().log("gate " + g.name + " (" + g.id + ") added on-device");
                toast("added " + g.name);
                recreate();
            }
        });
        root.addView(add);

        // ----------------------------------------------------------- data --
        root.addView(label("MEASUREMENT DATA"));
        Button export = new Button(this);
        export.setText("export track CSV (approach log)");
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportCsv(); }
        });
        root.addView(export);
        Button share = new Button(this);
        share.setText("share event log (text)");
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareLog(); }
        });
        root.addView(share);

        TextView how = new TextView(this);
        how.setText("How to make detection exact: 1) drive to the gate, tap “set position "
                + "= HERE” — this removes the cloud-coordinate offset. 2) Stand 1 m from "
                + "the gate, tap “CALIBRATE gate radio”. 3) Pick trigger B or C so the "
                + "gate’s own radio confirms arrival. 4) Export the track CSV after a few "
                + "drives and compare dist_smooth_m / rssi_est_m with reality.\n\n"
                + "Android Auto: enable Developer settings in the Android Auto app "
                + "(tap the version 10×), then allow “Unknown sources”. PalGate Car "
                + "appears in the car’s app list. Add the “PalGate” widget to the home "
                + "screen for the OPEN button.");
        how.setTextSize(12);
        how.setPadding(0, dp(6), 0, dp(10));
        root.addView(how);

        root.addView(label("LOG"));
        scrollLog = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(11);
        tvLog.setTextIsSelectable(true);
        scrollLog.addView(tvLog);
        root.addView(scrollLog, lp);
    }

    private interface ToggleCb {
        void set(boolean v);
    }

    private final List<TextView> toggles = new ArrayList<TextView>();

    private void addToggle(LinearLayout root, String title, boolean initial, final ToggleCb cb) {
        final TextView tv = new TextView(this);
        tv.setText((initial ? "☑ " : "☐ ") + title);
        tv.setTextSize(14);
        tv.setPadding(dp(6), dp(10), dp(6), dp(10));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setOnClickListener(new View.OnClickListener() {
            boolean on = tv.getText().toString().startsWith("☑");

            @Override public void onClick(View v) {
                on = !on;
                tv.setText((on ? "☑ " : "☐ ") + title);
                cb.set(on);
            }
        });
        toggles.add(tv);
        root.addView(tv);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private void svcOpen(String gateId) {
        Intent i = new Intent(this, GateMonitorService.class);
        i.setAction(GateMonitorService.ACTION_OPEN_NOW);
        if (gateId != null) i.putExtra("gateId", gateId);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("could not start service: " + e.getClass().getSimpleName());
        }
    }

    private void svc(String action) {
        Intent i = new Intent(this, GateMonitorService.class);
        i.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26 && !GateMonitorService.ACTION_STOP.equals(action)) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("could not start service: " + e.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------- readout --

    private void refresh() {
        State s = State.get();
        State.Prefs p = State.Prefs.get();
        String nearest = s.nearest == null ? "—"
                : String.format(Locale.ROOT, "%s%n  dist %.0f m (raw %.0f) ±%.0f · %s%n  %s",
                        s.distanceText(), s.nearestDistM == Double.MAX_VALUE ? -1 : s.nearestDistM,
                        s.nearestRawM == Double.MAX_VALUE ? -1 : s.nearestRawM,
                        Math.max(1f, s.accuracy),
                        s.hasFix ? (s.fixQualified ? "qualified" : "COARSE") : "no fix",
                        s.approachText());
        String radio = s.radioText()
                + (s.rssi < 127 ? String.format(Locale.ROOT, " [%s]", s.rssiMac) : "")
                + String.format(Locale.ROOT, "%n  ref @1 m: %d dBm · trigger ≥%d dBm",
                        p.rssi1m, p.rssiTrigDb);
        tvStatus.setText(String.format(Locale.ROOT,
                "monitoring: %s%nmode: %s · trigger: %s%nAA: %s   carBT: %s   GPS: %s (%s)%nnearest: %s%n%s%nphase: %s",
                s.monitoring ? "ON" : "off",
                State.Prefs.modeName(p.openMode),
                State.Prefs.trigName(p.triggerMode),
                s.aaConnected ? "✓" : "✗",
                s.carBt ? "✓" : "✗",
                s.hasFix ? String.format(Locale.ROOT, "±%.0f m", s.accuracy) : "✗",
                s.fixProvider.isEmpty() ? "?" : s.fixProvider,
                nearest, radio, s.phaseText()));
        StringBuilder sb = new StringBuilder();
        for (String line : s.drainLog()) sb.append(line).append('\n');
        tvLog.setText(sb);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        scrollLog.post(new Runnable() {
            @Override public void run() { scrollLog.fullScroll(ScrollView.FOCUS_DOWN); }
        });
    }

    // ------------------------------------------------- position calibration --

    private float bestAcc() {
        float best = 999f;
        for (double[] f : captureFixes) best = (float) Math.min(best, f[2]);
        return best;
    }

    private void startCapture(Gate g) {
        if (capturing) {
            toast("already capturing — wait");
            return;
        }
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("location permission missing");
            return;
        }
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            toast("no location service");
            return;
        }
        Criteria fine = new Criteria();
        fine.setAccuracy(Criteria.ACCURACY_FINE);
        String prov = lm.getBestProvider(fine, true);
        if (prov == null) prov = LocationManager.GPS_PROVIDER;
        captureFixes.clear();
        captureGate = g;
        try {
            lm.requestLocationUpdates(prov, 1000, 0, captureListener, Looper.getMainLooper());
        } catch (Exception e) {
            toast("could not start GPS: " + e.getClass().getSimpleName());
            return;
        }
        capturing = true;
        tvCap.setText("capturing " + g.name + ": 0 fixes…");
        State.get().log("position capture for " + g.name + " started (20 s)");
        h.postDelayed(captureEnd, 20000);
    }

    private void finishCapture() {
        try {
            if (lm != null) lm.removeUpdates(captureListener);
        } catch (Exception ignored) {
        }
        if (!capturing) return;
        capturing = false;
        List<double[]> good = new ArrayList<double[]>();
        for (double[] f : captureFixes) {
            if (f[2] <= 25) good.add(f);
        }
        if (good.size() < 5 && captureFixes.size() >= 5) {
            good.clear();
            for (double[] f : captureFixes) {
                if (f[2] <= 45) good.add(f);
            }
        }
        if (good.size() < 5) {
            tvCap.setText("");
            toast("capture failed — only " + captureFixes.size()
                    + " fixes (need 5 with good accuracy, sky view?)");
            State.get().log("position capture failed (" + captureFixes.size() + " fixes)");
            return;
        }
        double sw = 0, lat = 0, lng = 0, accSum = 0;
        for (double[] f : good) {
            double wgt = 1.0 / (f[2] * f[2]);
            sw += wgt;
            lat += f[0] * wgt;
            lng += f[1] * wgt;
            accSum += f[2];
        }
        lat /= sw;
        lng /= sw;
        double shift = captureGate.distanceM(lat, lng);   // old position → new
        Gate old = captureGate;
        Gate.setOverride(this, captureGate.id, lat, lng);
        String msg = String.format(Locale.ROOT,
                "%s calibrated: moved %.1f m from cloud position (±%.0f m avg fix, %d fixes)",
                captureGate.name, shift, accSum / good.size(), good.size());
        tvCap.setText(msg);
        State.get().log("⌖ " + msg);
        toast(String.format(Locale.ROOT, "%s position set (%.1f m shift)", old.name, shift));
    }

    // ---------------------------------------------------- radio calibration --

    private void startCalib() {
        if (calibrating) return;
        if (!BleRssiScanner.permsOk(this)) {
            toast("Bluetooth permission missing — grant it first");
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        calibAdapter = bm == null ? null : bm.getAdapter();
        if (calibAdapter == null || !calibAdapter.isEnabled()) {
            toast("Bluetooth is off");
            return;
        }
        calibrating = true;
        calibSamples.clear();
        tvCap.setText("calibrating @1 m: 0 samples…");
        State.get().log("radio calibration started — hold the phone ~1 m from the gate");
        try {
            calibAdapter.getBluetoothLeScanner().startScan(
                    BleRssiScanner.palFilters(this),
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    calibCb);
        } catch (Exception e) {
            calibrating = false;
            toast("scan failed: " + e.getClass().getSimpleName());
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() { stopCalib(); }
        }, 8000);
    }

    private final ScanCallback calibCb = new ScanCallback() {
        @Override public void onScanResult(int type, final ScanResult r) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (!calibrating) return;
                    String name = r.getDevice().getName();
                    if (name == null || name.trim().isEmpty()) {
                        name = r.getScanRecord() == null ? "" : r.getScanRecord().getDeviceName();
                    }
                    name = name == null ? "" : name.trim();
                    List<ParcelUuid> uuids = r.getScanRecord() == null
                            ? null : r.getScanRecord().getServiceUuids();
                    if (!BleRssiScanner.looksPalGate(PhoneActivity.this, name, uuids)
                            && !State.Prefs.macMap(PhoneActivity.this)
                                    .containsKey(r.getDevice().getAddress())) {
                        return;
                    }
                    calibSamples.add(r.getRssi());
                    if (tvCap != null) {
                        tvCap.setText(String.format(Locale.ROOT,
                                "calibrating @1 m: %d samples…", calibSamples.size()));
                    }
                }
            });
        }
    };

    private void stopCalib() {
        if (!calibrating) return;
        calibrating = false;
        try {
            if (calibAdapter != null && calibAdapter.getBluetoothLeScanner() != null) {
                calibAdapter.getBluetoothLeScanner().stopScan(calibCb);
            }
        } catch (Exception ignored) {
        }
        if (calibSamples.isEmpty()) {
            tvCap.setText("");
            toast("no gate radio seen in 8 s — stand closer / check Bluetooth");
            State.get().log("radio calibration failed — no advertisement seen");
            return;
        }
        double avg = 0;
        for (int s : calibSamples) avg += s;
        avg /= calibSamples.size();
        int ref = (int) Math.round(avg);
        State.Prefs.get().rssi1m = ref;
        State.Prefs.save(this);
        String msg = String.format(Locale.ROOT,
                "1 m reference = %d dBm (%d samples, avg %.1f)", ref, calibSamples.size(), avg);
        tvCap.setText(msg);
        State.get().log("⌖ radio " + msg);
        toast(msg);
    }

    // ------------------------------------------------------------ data out --

    private void exportCsv() {
        File dir = new File(getFilesDir(), "tracks");
        File cur = new File(dir, "track.csv");
        File old = new File(dir, "track_old.csv");
        if ((!cur.exists() || cur.length() == 0) && (!old.exists() || old.length() == 0)) {
            toast("no track data yet — drive once with monitoring on");
            return;
        }
        StringBuilder sb = new StringBuilder();
        readInto(sb, old);
        readInto(sb, cur);
        String name = "PalGate_track_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".csv";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PalGate");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("insert returned null");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                os.close();
                toast("saved to Downloads/PalGate/" + name);
                State.get().log("track CSV exported → Downloads/PalGate/" + name);
            } else {
                File out = new File(getExternalFilesDir(null), name);
                FileOutputStream fo = new FileOutputStream(out);
                fo.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fo.close();
                toast("saved to " + out.getAbsolutePath());
                State.get().log("track CSV exported → " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("export failed (" + e.getClass().getSimpleName()
                    + ") — sharing as text instead");
            shareText(name, sb.toString());
        }
    }

    private void readInto(StringBuilder sb, File f) {
        try {
            if (!f.exists() || f.length() == 0) return;
            FileInputStream fi = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 8L * 1024 * 1024)];
            int n = fi.read(buf);
            fi.close();
            if (n > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        } catch (Exception ignored) {
        }
    }

    private void shareLog() {
        StringBuilder sb = new StringBuilder("PalGate Car event log\n\n");
        List<String> log = State.get().drainLog();
        int from = Math.max(0, log.size() - 300);
        for (int i = from; i < log.size(); i++) sb.append(log.get(i)).append('\n');
        shareText("palgate_log.txt", sb.toString());
    }

    private void shareText(String name, String body) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, name);
        i.putExtra(Intent.EXTRA_TEXT, body.length() > 350000 ? body.substring(0, 350000) : body);
        try {
            startActivity(Intent.createChooser(i, "Share " + name));
        } catch (Exception e) {
            toast("no share target: " + e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------- permissions --

    private boolean hasPerm(String p) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePerms() {
        List<String> need = new ArrayList<String>();
        if (!hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_SCAN)) need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33 && !hasPerm(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            refresh();
            return;
        }
        requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean all = true;
        for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) all = false;
        if (all) {
            toast("permissions granted — ready");
        } else {
            toast("some permissions denied — features will fail");
        }
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
