package com.srm.gps.application.auth;

import com.srm.gps.application.auth.PortalUser.Role;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalSignInTest {

    private static final byte[] KEY = "a-test-key-made-by-the-migration".getBytes(StandardCharsets.UTF_8);

    @Test
    void aPasswordIsStoredSaltedAndOnlyItsOwnTextMatches() {
        String stored = PasswordHashing.hash("abc");
        assertTrue(stored.startsWith("pbkdf2-sha256$210000$"));
        assertTrue(PasswordHashing.matches("abc", stored));
        assertFalse(PasswordHashing.matches("abd", stored));
        assertFalse(PasswordHashing.matches("abc", "not a hash"));
        assertNotEquals(stored, PasswordHashing.hash("abc"));   // a new salt every time
    }

    @Test
    void aTokenCarriesTheRoleAndCompanyAndCannotBeChanged() {
        long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        PortalUser user = new PortalUser("id-1", "abc", Role.TRANSPORTER, "Virk (demo)", "virk-eenterprises", "Virk Eenterprises", expires);
        String token = PortalTokens.issue(user, KEY);
        assertEquals(Optional.of(user), PortalTokens.verify(token, KEY, Instant.now()));

        // a transporter rewriting its token into a consigner, or into another company, is refused
        String[] parts = token.split("\\.");
        String forged = new String(java.util.Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
                .replace("TRANSPORTER", "DEVELOPER");
        String forgedToken = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + parts[1];
        assertTrue(PortalTokens.verify(forgedToken, KEY, Instant.now()).isEmpty());
        assertTrue(PortalTokens.verify(token, "another key".getBytes(StandardCharsets.UTF_8), Instant.now()).isEmpty());
    }

    @Test
    void anExpiredTokenIsRefused() {
        PortalUser user = new PortalUser("id-1", "abc", Role.CONSIGNER, "Tata", "tata-steel", "TATA STEEL", 1_000);
        assertTrue(PortalTokens.verify(PortalTokens.issue(user, KEY), KEY, Instant.ofEpochSecond(1_001)).isEmpty());
    }
}
