package com.srm.gps.application.auth;

import com.srm.gps.application.auth.PortalUser.Role;
import com.srm.gps.application.tms.TenantDirectory;
import com.srm.gps.application.tms.TenantDirectory.Company;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The people who may sign in (table app_user): role, user id, company, password hash. */
@Component
public class AccountStore {

    public record Account(UUID id, Role role, String username, String displayName, String passwordHash, boolean active,
                          String tenantCode, String tenantName, Instant createdAt, Instant lastLoginAt) {
    }

    /** An account as the admin pages show it: never the hash. */
    public record AccountView(UUID id, Role role, String username, String displayName, boolean active, String tenantCode,
                              String tenantName, Instant createdAt, Instant lastLoginAt) {
        static AccountView of(Account a) {
            return new AccountView(a.id(), a.role(), a.username(), a.displayName(), a.active(), a.tenantCode(), a.tenantName(),
                    a.createdAt(), a.lastLoginAt());
        }
    }

    private static final String SELECT = """
            SELECT u.id, u.role, u.username, u.display_name, u.password_hash, u.active, u.created_at, u.last_login_at,
                   t.code AS tenant_code, t.legal_name AS tenant_name
            FROM app_user u LEFT JOIN tenant t ON t.id = u.tenant_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDirectory tenants;

    public AccountStore(NamedParameterJdbcTemplate jdbc, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    public Optional<Account> find(Role role, String username) {
        return jdbc.query(SELECT + " WHERE u.role = :role AND lower(u.username) = lower(:username)",
                new MapSqlParameterSource("role", role.name()).addValue("username", username), (rs, n) -> map(rs)).stream().findFirst();
    }

    public List<AccountView> list() {
        return jdbc.query(SELECT + " ORDER BY u.role, t.legal_name NULLS FIRST, u.username", new MapSqlParameterSource(),
                (rs, n) -> AccountView.of(map(rs)));
    }

    public boolean anyWithRole(Role role) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE role = :role",
                new MapSqlParameterSource("role", role.name()), Integer.class);
        return count != null && count > 0;
    }

    /**
     * @param tenantCode the user's company: a transporter for TRANSPORTER, a consigner for CONSIGNER, none for DEVELOPER
     */
    public AccountView create(Role role, String username, String displayName, String password, String tenantCode) {
        if (username == null || !username.trim().matches("[A-Za-z0-9._@-]{2,80}")) {
            throw new IllegalArgumentException("A user id is 2 to 80 letters, digits or . _ @ -");
        }
        if (password == null || password.length() < 3) {
            throw new IllegalArgumentException("A password has at least 3 characters");
        }
        UUID tenantId = null;
        if (role != Role.DEVELOPER) {
            Company company = tenants.byCode(tenantCode == null ? "" : tenantCode)
                    .orElseThrow(() -> new IllegalArgumentException("Choose the user's company"));
            TenantDirectory.Kind expected = role == Role.TRANSPORTER ? TenantDirectory.Kind.TRANSPORTER : TenantDirectory.Kind.CONSIGNER;
            if (company.kind() != expected) {
                throw new IllegalArgumentException(company.name() + " is a " + company.kind().name().toLowerCase()
                        + ", not a " + expected.name().toLowerCase());
            }
            tenantId = company.id();
        }
        if (find(role, username.trim()).isPresent()) {
            throw new IllegalArgumentException("There is already a " + role.name().toLowerCase() + " user " + username.trim());
        }
        jdbc.update("""
                INSERT INTO app_user (role, username, display_name, tenant_id, password_hash)
                VALUES (:role, :username, :displayName, :tenantId, :hash)
                """, new MapSqlParameterSource("role", role.name()).addValue("username", username.trim())
                .addValue("displayName", displayName == null || displayName.isBlank() ? username.trim() : displayName.trim())
                .addValue("tenantId", tenantId).addValue("hash", PasswordHashing.hash(password)));
        return AccountView.of(find(role, username.trim()).orElseThrow());
    }

    public void setPassword(UUID id, String password) {
        if (password == null || password.length() < 3) {
            throw new IllegalArgumentException("A password has at least 3 characters");
        }
        jdbc.update("UPDATE app_user SET password_hash = :hash WHERE id = :id",
                new MapSqlParameterSource("hash", PasswordHashing.hash(password)).addValue("id", id));
    }

    public void setActive(UUID id, boolean active) {
        jdbc.update("UPDATE app_user SET active = :active WHERE id = :id", new MapSqlParameterSource("active", active).addValue("id", id));
    }

    public void signedIn(UUID id) {
        jdbc.update("UPDATE app_user SET last_login_at = :now WHERE id = :id",
                new MapSqlParameterSource("now", Timestamp.from(Instant.now())).addValue("id", id));
    }

    private static Account map(ResultSet rs) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        return new Account(rs.getObject("id", UUID.class), Role.valueOf(rs.getString("role")), rs.getString("username"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("active"), rs.getString("tenant_code"),
                rs.getString("tenant_name"), rs.getTimestamp("created_at").toInstant(), lastLogin == null ? null : lastLogin.toInstant());
    }
}
