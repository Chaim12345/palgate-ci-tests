package com.palgate.carapp;

import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * PalGate gate crypto, isolated from Android classes so it can be unit-tested
 * on a plain JVM (see test/GateCryptoKat.java).
 *
 * MODERN (service 9c23af10): read 16-byte challenge, dec = AES-128-ECB-decrypt(gateKey,
 * challenge), response = dec[0:4] || AES-encrypt(gateKey, dec[0:4] || CMD_OPEN) = 20 bytes.
 * CLASSIC (FFE0/FFE1): ASCII block "AB" + serial[:10] + digits, padded to 16 bytes,
 * AES-128-ECB-encrypt.
 *
 * Protocol constants are model-generic (public PalGate protocol implementations).
 * Keys used in tests are synthetic golden vectors - never real gate keys.
 */
public final class GateCrypto {

    /** MODERN open command block: 00 00 00 02 followed by eight zero bytes. */
    public static final byte[] CMD_OPEN = {0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0};

    private GateCrypto() {
    }

    /** MODERN challenge-response: 16-byte challenge -> 20-byte response. */
    public static byte[] buildModernResponse(byte[] key, byte[] challenge) throws Exception {
        if (challenge == null || challenge.length != 16) {
            throw new IllegalArgumentException("challenge must be 16 bytes");
        }
        byte[] dec = aes(key, challenge, false);
        byte[] prefix = copy(dec, 4);
        return concat(prefix, aes(key, concat(prefix, CMD_OPEN), true));
    }

    /** CLASSIC payload with caller-supplied digits (deterministic; used by tests). */
    public static byte[] buildClassicPayload(byte[] key, String gateId, String digits) throws Exception {
        String clean = gateId.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (clean.length() > 10) clean = clean.substring(0, 10);
        StringBuilder sb = new StringBuilder("AB").append(clean);
        for (int i = 0; i < digits.length() && sb.length() < 16; i++) sb.append(digits.charAt(i));
        while (sb.length() < 16) sb.append('0');
        return aes(key, sb.toString().getBytes("US-ASCII"), true);
    }

    /** CLASSIC payload with random digits (production path). */
    public static byte[] buildClassicPayload(byte[] key, String gateId, SecureRandom rng) throws Exception {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 16; i++) digits.append((char) ('0' + rng.nextInt(10)));
        return buildClassicPayload(key, gateId, digits.toString());
    }

    public static byte[] aes(byte[] key, byte[] block, boolean encrypt) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(block);
    }

    public static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++)
            r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }

    public static String hexStr(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.length() > 24 ? sb.substring(0, 24) + "…" : sb.toString();
    }

    /** Full hex string, no truncation (test assertions). */
    public static String hexFull(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public static byte[] copy(byte[] a, int n) {
        byte[] r = new byte[n];
        System.arraycopy(a, 0, r, 0, Math.min(n, a.length));
        return r;
    }
}
