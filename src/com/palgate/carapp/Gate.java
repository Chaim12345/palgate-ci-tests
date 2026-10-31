package com.palgate.carapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** A PalGate gate: GPS position + cached per-gate AES-128 BLE key + cloud identity. */
public class Gate {
    public final String id;      // PalGate device id, e.g. TEST000001
    public final String name;    // display name
    public final double lat, lng;
    public final String keyHex;
    public final String model;   // e.g. SG304GI (4G+BT)
    public final String mac;     // BLE MAC if known (nullable)
    public final String address; // street address (goes into the Device parcel)
    public final String relay;   // relay1 setting, e.g. "02"
    public final String validUntil; // account access expiry, e.g. 2027-10-11T21:00:00.000Z
    public final boolean posOverridden; // position came from on-site calibration

    public Gate(String id, String name, double lat, double lng, String keyHex,
                String model, String mac, String address, String relay, String validUntil) {
        this(id, name, lat, lng, keyHex, model, mac, address, relay, validUntil, false);
    }

    public Gate(String id, String name, double lat, double lng, String keyHex,
                String model, String mac, String address, String relay, String validUntil,
                boolean posOverridden) {
        this.id = id;
        this.name = name;
        this.lat = lat;
        this.lng = lng;
        this.keyHex = keyHex;
        this.model = model;
        this.mac = mac;
        this.address = address;
        this.relay = relay;
        this.validUntil = validUntil;
        this.posOverridden = posOverridden;
    }

    /** Great-circle distance in meters (haversine). */
    public double distanceM(double lat2, double lng2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat);
        double dLng = Math.toRadians(lng2 - lng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial bearing from (lat2,lng2) to this gate, degrees 0..360 (0 = north). */
    public double bearingFrom(double lat2, double lng2) {
        double phi1 = Math.toRadians(lat2), phi2 = Math.toRadians(lat);
        double dLng = Math.toRadians(lng - lng2);
        double y = Math.sin(dLng) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s(%s)", name, id);
    }

    // ------------------------------------------------------------ loading --
    // gates.json is baked into assets (read-only); on-site position calibration
    // is stored as an override layer in SharedPreferences. The list is cached
    // in memory — MapScreen redraws several times a second and must not
    // re-parse the asset per frame.

    private static final String PREFS = "palgate_car";
    private static List<Gate> cache;
    private static int cacheRev = -1;

    /** Cached load; re-reads only when the override revision changes. */
    public static List<Gate> load(Context ctx) {
        int rev = overrideRev(ctx);
        if (cache != null && cacheRev == rev) return cache;
        List<Gate> out = new ArrayList<Gate>();
        try {
            InputStream in = ctx.getAssets().open("gates.json");
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            in.close();
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("gates");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                out.add(new Gate(g.getString("id"), g.getString("name"),
                        g.getDouble("lat"), g.getDouble("lng"), g.getString("key"),
                        g.optString("model", "SG304GI"),
                        g.optString("mac", null),
                        g.optString("address", ""),
                        g.optString("relay", "02"),
                        g.optString("validUntil", "2027-10-11T21:00:00.000Z")));
            }
        } catch (Exception e) {
            android.util.Log.e("PalGate", "gates.json load failed", e);
        }
        applyOverrides(ctx, out);
        cache = out;
        cacheRev = rev;
        return out;
    }

    /** On-site calibrated position for a gate ("id" -> {"lat":..,"lng":..}). */
    public static void setOverride(Context ctx, String id, double lat, double lng) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject(sp.getString("gate_pos_overrides", "{}"));
            root.put(id, new JSONObject().put("lat", lat).put("lng", lng));
            sp.edit().putString("gate_pos_overrides", root.toString())
                    .putInt("gate_overrides_rev", sp.getInt("gate_overrides_rev", 0) + 1)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static void clearOverride(Context ctx, String id) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject(sp.getString("gate_pos_overrides", "{}"));
            root.remove(id);
            sp.edit().putString("gate_pos_overrides", root.toString())
                    .putInt("gate_overrides_rev", sp.getInt("gate_overrides_rev", 0) + 1)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean hasOverride(Context ctx, String id) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONObject(sp.getString("gate_pos_overrides", "{}")).has(id);
        } catch (Exception e) {
            return false;
        }
    }

    private static int overrideRev(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("gate_overrides_rev", 0);
    }

    private static void applyOverrides(Context ctx, List<Gate> gates) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject root = new JSONObject(sp.getString("gate_pos_overrides", "{}"));
            if (root.length() == 0) return;
            Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject o = root.getJSONObject(id);
                double lat = o.getDouble("lat"), lng = o.getDouble("lng");
                for (int i = 0; i < gates.size(); i++) {
                    Gate g = gates.get(i);
                    if (g.id.equals(id)) {
                        gates.set(i, new Gate(g.id, g.name, lat, lng, g.keyHex, g.model,
                                g.mac, g.address, g.relay, g.validUntil, true));
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
