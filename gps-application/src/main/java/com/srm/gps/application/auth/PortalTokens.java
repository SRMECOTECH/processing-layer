package com.srm.gps.application.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Sign-in tokens: the user (id, role, company) and an expiry, signed with HMAC-SHA256 by this service.
 *
 *   base64url(JSON) "." base64url(HMAC-SHA256(key, base64url(JSON)))
 *
 * The dashboard keeps the token inside its own signed session cookie and sends it with every /api/portal call
 * (Authorization: Bearer ...), so the role and the company are decided here, not by whatever the browser sends.
 * The key is made once by the database migration (auth_key) and read at the first use.
 */
@Component
public class PortalTokens {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();

    private final JdbcTemplate jdbc;
    private volatile byte[] key;

    public PortalTokens(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(PortalUser user) {
        return issue(user, key());
    }

    /** The user of a token, or empty when it is malformed, tampered with or expired. */
    public Optional<PortalUser> verify(String token) {
        return verify(token, key(), Instant.now());
    }

    static String issue(PortalUser user, byte[] key) {
        String payload = ENCODE.encodeToString(JSON.writeValueAsBytes(user));
        return payload + "." + ENCODE.encodeToString(sign(payload, key));
    }

    static Optional<PortalUser> verify(String token, byte[] key, Instant now) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            if (!MessageDigest.isEqual(DECODE.decode(parts[1]), sign(parts[0], key))) {
                return Optional.empty();
            }
            PortalUser user = JSON.readValue(DECODE.decode(parts[0]), PortalUser.class);
            return user.role() != null && user.expiresAt() > now.getEpochSecond() ? Optional.of(user) : Optional.empty();
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static byte[] sign(String payload, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is part of every JDK", impossible);
        }
    }

    private byte[] key() {
        if (key == null) {
            String secret = jdbc.queryForObject("SELECT secret FROM auth_key WHERE id = 1", String.class);
            key = secret.getBytes(StandardCharsets.UTF_8);
        }
        return key;
    }
}
