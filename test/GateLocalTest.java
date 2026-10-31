import android.content.Context;
import android.content.SharedPreferences;
import com.palgate.carapp.Gate;
import com.palgate.carapp.GateCrypto;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

/**
 * Android-free behavior tests for Gate.java (fixture loading, defaults,
 * position overrides, geo math) plus a full gate-side challenge-response
 * roundtrip for GateCrypto (simulated gate peer, synthetic keys only).
 * Run from the repo root: java -cp out:tools/json-20240303.jar GateLocalTest
 */
public class GateLocalTest {
    static int failures = 0;

    static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name);
            failures++;
        }
    }

    static void eq(String a, String b, String name) { check(a == null ? b == null : a.equals(b), name); }
    static void close(double a, double b, double tol, String name) { check(Math.abs(a - b) <= tol, name + " (got " + a + ")"); }

    static SharedPreferences prefs(int rev) {
        SharedPreferences sp = new SharedPreferences();
        sp.seed("gate_overrides_rev", Integer.valueOf(rev)); // distinct rev per test: bypass static cache
        return sp;
    }

    static Gate mk(double lat, double lng) {
        return new Gate("T", "t", lat, lng, "00", "SG304GI", null, "", "02", "2027-10-11T21:00:00.000Z");
    }

    public static void main(String[] args) throws Exception {
        testLoadRealFixture();
        testLoadDefaults();
        testOverrideRoundtrip();
        testGeoMath();
        testModernGateSideRoundtrip();
        System.out.println(failures == 0 ? "ALL LOCAL TESTS PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void testLoadRealFixture() {
        Context ctx = new Context(new File("assets"), prefs(101));
        List<Gate> gates = Gate.load(ctx);
        check(gates.size() == 2, "fixture loads exactly 2 gates");
        Gate g0 = gates.get(0);
        eq(g0.id, "TEST000001", "g0 id");
        check(g0.name.indexOf("SYNTHETIC") >= 0, "g0 synthetic marker");
        eq(g0.keyHex, "00112233445566778899AABBCCDDEEFF", "g0 pinned synthetic key");
        eq(g0.mac, "02:11:22:33:44:55", "g0 pinned synthetic MAC");
        eq(g0.model, "SG304GI", "g0 model");
        eq(g0.type, "4G", "g0 type");
        eq(g0.subType, "I", "g0 subType");
        eq(g0.firmware, "5.054", "g0 firmware");
        check(g0.outputCount == 1, "g0 outputCount");
        check(!g0.admin, "g0 non-admin");
        check(g0.secondaryDevice, "g0 secondaryDevice");
        check(g0.dialToOpen, "g0 dialToOpen");
        check(!g0.localOnly, "g0 localOnly false");
        eq(g0.relay, "02", "g0 relay");
        Gate g1 = gates.get(1);
        eq(g1.id, "TEST000002", "g1 id");
        eq(g1.keyHex, "FFEEDDCCBBAA99887766554433221100", "g1 pinned synthetic key");
        eq(g1.mac, "02:66:77:88:99:AA", "g1 pinned synthetic MAC");
    }

    static void testLoadDefaults() throws Exception {
        File dir = new File("/tmp/gate-local-test-assets");
        dir.mkdirs();
        FileWriter w = new FileWriter(new File(dir, "gates.json"));
        w.write("{\"gates\":[{\"id\":\"TEST000009\",\"name\":\"Minimal (SYNTHETIC)\","
                + "\"lat\":31.0,\"lng\":34.0,\"key\":\"00112233445566778899AABBCCDDEEFF\"}]}");
        w.close();
        Context ctx = new Context(dir, prefs(102));
        List<Gate> gates = Gate.load(ctx);
        check(gates.size() == 1, "minimal fixture loads");
        Gate g = gates.get(0);
        eq(g.model, "SG304GI", "default model SG304GI");
        eq(g.type, "4G", "default type 4G");
        eq(g.subType, "I", "default subType I");
        eq(g.firmware, "", "default firmware empty");
        check(g.outputCount == 1, "default outputCount 1");
        check(!g.admin && !g.secondaryDevice && !g.dialToOpen && !g.localOnly, "default role flags false");
        eq(g.relay, "02", "default relay 02");
        check(g.mac == null, "default mac null");
        eq(g.address, "", "default address empty");
        eq(g.validUntil, "2027-10-11T21:00:00.000Z", "default validUntil");
    }

    static void testOverrideRoundtrip() {
        SharedPreferences sp = prefs(103);
        Context ctx = new Context(new File("assets"), sp);
        Gate before = Gate.load(ctx).get(0);
        close(before.lat, 32.0853, 1e-9, "baseline lat");
        check(!before.posOverridden, "baseline not overridden");
        check(!Gate.hasOverride(ctx, "TEST000001"), "no override stored yet");

        Gate.setOverride(ctx, "TEST000001", 31.5, 34.5);
        Gate after = Gate.load(ctx).get(0);
        close(after.lat, 31.5, 1e-12, "override lat applied");
        close(after.lng, 34.5, 1e-12, "override lng applied");
        check(after.posOverridden, "override flag set");
        check(Gate.hasOverride(ctx, "TEST000001"), "hasOverride true");
        check(Gate.load(ctx).get(1).lat != 31.5, "other gate untouched by override");
        check(Gate.load(ctx).get(0) == after, "cache reused at same rev");

        Gate.clearOverride(ctx, "TEST000001");
        Gate restored = Gate.load(ctx).get(0);
        close(restored.lat, 32.0853, 1e-9, "clear restores asset lat");
        check(!restored.posOverridden, "override flag cleared");
    }

    static void testGeoMath() {
        Gate same = mk(32.0, 34.0);
        close(same.distanceM(32.0, 34.0), 0.0, 1e-6, "distance same point 0");
        Gate north = mk(1.0, 0.0);
        close(north.distanceM(0.0, 0.0), 111194.92664455874, 0.01, "distance 1deg latitude");
        close(north.bearingFrom(0.0, 0.0), 0.0, 1e-9, "bearing due north");
        Gate east = mk(0.0, 1.0);
        close(east.distanceM(0.0, 0.0), 111194.92664455874, 0.01, "distance 1deg longitude at equator");
        close(east.bearingFrom(0.0, 0.0), 90.0, 1e-9, "bearing due east");
        Gate ta = mk(32.0853, 34.7818); // fixture gate 1 position
        close(ta.distanceM(31.7683, 35.2137), 53887.372286784186, 0.01, "distance TA<->JLM");
        Gate jlm = mk(31.7683, 35.2137);
        close(jlm.bearingFrom(32.0853, 34.7818), 130.73846427801573, 1e-9, "bearing TA->JLM");
    }

    static void testModernGateSideRoundtrip() throws Exception {
        String[] keyHexes = {
            "00112233445566778899AABBCCDDEEFF",
            "FFEEDDCCBBAA99887766554433221100",
            "A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5"
        };
        byte[] challenge = new byte[16];
        for (int i = 0; i < 16; i++) challenge[i] = (byte) (0x30 + i); // deterministic
        for (int k = 0; k < keyHexes.length; k++) {
            byte[] key = GateCrypto.hex(keyHexes[k]);
            byte[] resp = GateCrypto.buildModernResponse(key, challenge);
            check(resp.length == 20, "roundtrip-" + (k + 1) + " response is 20 bytes");
            // gate-side verification: decrypt the trailing 16 bytes with the gate key
            byte[] tail = new byte[16];
            System.arraycopy(resp, 4, tail, 0, 16);
            byte[] pt = GateCrypto.aes(key, tail, false);
            boolean prefixEcho = pt[0] == resp[0] && pt[1] == resp[1] && pt[2] == resp[2] && pt[3] == resp[3];
            check(prefixEcho, "roundtrip-" + (k + 1) + " gate decrypts prefix echo");
            boolean cmdMatch = true;
            for (int i = 0; i < 12; i++) cmdMatch &= pt[4 + i] == GateCrypto.CMD_OPEN[i];
            check(cmdMatch, "roundtrip-" + (k + 1) + " gate recovers CMD_OPEN intact");
            // wrong gate key must NOT validate (different key -> no prefix echo)
            byte[] wrongKey = GateCrypto.hex(keyHexes[(k + 1) % keyHexes.length]);
            byte[] ptWrong = GateCrypto.aes(wrongKey, tail, false);
            boolean wrongEcho = ptWrong[0] == resp[0] && ptWrong[1] == resp[1]
                    && ptWrong[2] == resp[2] && ptWrong[3] == resp[3];
            check(!wrongEcho, "roundtrip-" + (k + 1) + " wrong key rejected by gate");
        }
    }
}
