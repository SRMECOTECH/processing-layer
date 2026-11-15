package com.srm.gps.application.auth;

import com.srm.gps.application.auth.AccountStore.Account;
import com.srm.gps.application.auth.PortalUser.Role;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Sign-in for the three kinds of user. The dashboard's sign-in page posts the role it asked for together with the
 * user id and password; the answer is a token the dashboard keeps in its session cookie.
 *
 *   POST /api/auth/login   {role, username, password, remember}  ->  {token, expiresAt, user}  or 401
 *   GET  /api/portal/me    the user of the token
 */
@RestController
public class AuthController {

    /** Same lengths as the dashboard's session: 12 hours, or 30 days with "keep me signed in". */
    static final Duration SESSION = Duration.ofHours(12);
    static final Duration REMEMBERED = Duration.ofDays(30);
    /** Compared against when the user id does not exist, so a wrong user id takes as long as a wrong password. */
    private static final String NO_SUCH_USER_HASH = PasswordHashing.hash("no such user");

    public record LoginRequest(String role, String username, String password, Boolean remember) {
    }

    public record LoginResponse(String token, long expiresAt, PortalUser user) {
    }

    private final AccountStore accounts;
    private final PortalTokens tokens;

    public AuthController(AccountStore accounts, PortalTokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        Role role;
        try {
            role = Role.valueOf(String.valueOf(request.role()).trim().toUpperCase());
        } catch (IllegalArgumentException unknownRole) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose GPS provider, transporter or consigner"));
        }
        String username = request.username() == null ? "" : request.username().trim();
        Optional<Account> account = username.isEmpty() ? Optional.empty() : accounts.find(role, username);
        boolean matches = PasswordHashing.matches(request.password(), account.map(Account::passwordHash).orElse(NO_SUCH_USER_HASH));
        if (account.isEmpty() || !matches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "That user id and password don't match for this role"));
        }
        Account a = account.get();
        if (!a.active()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This user has been switched off. Ask the GPS provider."));
        }
        long expires = Instant.now().plus(Boolean.TRUE.equals(request.remember()) ? REMEMBERED : SESSION).getEpochSecond();
        PortalUser user = new PortalUser(a.id().toString(), a.username(), a.role(), a.displayName(), a.tenantCode(), a.tenantName(), expires);
        accounts.signedIn(a.id());
        return ResponseEntity.ok(new LoginResponse(tokens.issue(user), expires, user));
    }

    @GetMapping("/api/portal/me")
    public PortalUser me(HttpServletRequest request) {
        return PortalUser.of(request);
    }

    @ExceptionHandler(PortalUser.NotSignedInException.class)
    public ResponseEntity<Map<String, String>> notSignedIn(PortalUser.NotSignedInException problem) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", problem.getMessage()));
    }
}
