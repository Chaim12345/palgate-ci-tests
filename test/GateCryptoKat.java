import com.palgate.carapp.GateCrypto;

/**
 * Known-answer tests for GateCrypto. Golden vectors were computed independently
 * with OpenSSL (AES-128-ECB, no padding) from synthetic keys/challenges.
 * No real gate keys anywhere.
 */
public final class GateCryptoKat {

    private static int failures = 0;

    private static void expect(String name, String actual, String golden) {
        if (actual.equalsIgnoreCase(golden)) {
            System.out.println("PASS " + name);
        } else {
            System.out.println("FAIL " + name + ": got " + actual + " want " + golden);
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        // MODERN challenge-response, vector 1
        expect("modern-1",
            GateCrypto.hexFull(GateCrypto.buildModernResponse(
                GateCrypto.hex("00112233445566778899AABBCCDDEEFF"),
                GateCrypto.hex("0102030405060708090A0B0C0D0E0F10"))),
            "813cb5efc7562cfeb019d6f908c5b201dca9cc28");
        // vector 2
        expect("modern-2",
            GateCrypto.hexFull(GateCrypto.buildModernResponse(
                GateCrypto.hex("FFEEDDCCBBAA99887766554433221100"),
                GateCrypto.hex("00000000000000000000000000000000"))),
            "ac371711d2b0b687c5ea24a46d81b2f8fb196590");
        // vector 3
        expect("modern-3",
            GateCrypto.hexFull(GateCrypto.buildModernResponse(
                GateCrypto.hex("A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5"),
                GateCrypto.hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"))),
            "0ade3d4cded5cda48752324ab50d782148d16bdc");

        // CLASSIC payload golden: "ABTEST000001482910" under the synthetic key
        expect("classic-1",
            GateCrypto.hexFull(GateCrypto.buildClassicPayload(
                GateCrypto.hex("00112233445566778899AABBCCDDEEFF"),
                "TEST000001", "482910")),
            "31e4da56914828b26f7f464fdf3cfcae");

        // CLASSIC round-trip structure: decrypt gives back "AB" + serial[:10] + digits
        byte[] enc = GateCrypto.buildClassicPayload(
            GateCrypto.hex("FFEEDDCCBBAA99887766554433221100"), "TEST000002", "730214");
        String back = new String(GateCrypto.aes(
            GateCrypto.hex("FFEEDDCCBBAA99887766554433221100"), enc, false), "US-ASCII");
        expect("classic-roundtrip", back, "ABTEST0000027302");

        // challenge-length validation
        boolean threw = false;
        try {
            GateCrypto.buildModernResponse(GateCrypto.hex("00112233445566778899AABBCCDDEEFF"),
                new byte[8]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        expect("modern-rejects-short-challenge", String.valueOf(threw), "true");

        if (failures > 0) {
            System.out.println(failures + " KAT failure(s)");
            System.exit(1);
        }
        System.out.println("All GateCrypto known-answer tests passed");
    }
}
