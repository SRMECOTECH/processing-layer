package com.srm.gps.application.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Passwords are stored as PBKDF2-HMAC-SHA256 with a random salt per user, from the JDK alone (no security library):
 *
 *   pbkdf2-sha256$210000$&lt;salt, base64&gt;$&lt;hash, base64&gt;
 *
 * 210,000 iterations is OWASP's figure for PBKDF2-SHA256; the count is part of the stored text, so it can be raised
 * later without breaking existing passwords.
 */
public final class PasswordHashing {

    private static final String SCHEME = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHashing() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return SCHEME + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    /** True when {@code password} is the one {@code stored} was made from; compares in constant time. */
    public static boolean matches(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is part of every JDK", impossible);
        } finally {
            spec.clearPassword();
        }
    }
}
