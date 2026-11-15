package com.srm.gps.application.tms;

import com.srm.gps.application.masterdata.TenantCodeFormatter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The companies on the platform (table tenant) by kind: transporters and consigners. A shipment file names them in
 * words ("Virk Eenterprises", "TATA STEEL-JSR") and by their number in the consigner's TMS (vendor code 0000015300,
 * consignor 540); the number wins when there is one, so a renamed transporter stays the same company.
 */
@Component
public class TenantDirectory {

    public enum Kind { GPS_PROVIDER, TRANSPORTER, CONSIGNER }

    public record Company(UUID id, String code, String name, Kind kind, String externalCode, String status, Instant createdAt) {
    }

    private static final String COLUMNS = "id, code, legal_name, kind, external_code, status, created_at";

    private final NamedParameterJdbcTemplate jdbc;

    public TenantDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Company> byCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tenant WHERE code = :code", new MapSqlParameterSource("code", code),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    public Optional<Company> byId(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tenant WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, n) -> map(rs)).stream().findFirst();
    }

    public List<Company> list(Kind kind) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tenant WHERE (CAST(:kind AS varchar) IS NULL OR kind = :kind) ORDER BY legal_name",
                new MapSqlParameterSource("kind", kind == null ? null : kind.name()), (rs, n) -> map(rs));
    }

    /**
     * The company of that kind with this external code, or else with this name's code; created when there is none.
     * A code already taken by a company of another kind gets the kind appended, so a consigner and a transporter
     * with the same name stay two companies.
     */
    /** The company findOrCreate would return, without creating one. */
    public Optional<Company> find(Kind kind, String name, String externalCode) {
        String external = blankToNull(externalCode);
        if (external != null) {
            Optional<Company> byExternal = jdbc.query("SELECT " + COLUMNS + " FROM tenant WHERE kind = :kind AND external_code = :external",
                    new MapSqlParameterSource("kind", kind.name()).addValue("external", external), (rs, n) -> map(rs)).stream().findFirst();
            if (byExternal.isPresent()) {
                return byExternal;
            }
        }
        if (blankToNull(name) == null) {
            return Optional.empty();
        }
        String code = TenantCodeFormatter.toTenantCode(name.trim());
        Optional<Company> sameCode = byCode(code);
        if (sameCode.isPresent() && sameCode.get().kind() == kind) {
            return sameCode;
        }
        String suffix = "-" + kind.name().toLowerCase().replace('_', '-');
        return byCode((code.length() + suffix.length() > 40 ? code.substring(0, 40 - suffix.length()) : code) + suffix)
                .filter(c -> c.kind() == kind);
    }

    @Transactional
    public Company findOrCreate(Kind kind, String name, String externalCode) {
        String external = blankToNull(externalCode);
        if (external != null) {
            Optional<Company> byExternal = jdbc.query("SELECT " + COLUMNS + " FROM tenant WHERE kind = :kind AND external_code = :external",
                    new MapSqlParameterSource("kind", kind.name()).addValue("external", external), (rs, n) -> map(rs)).stream().findFirst();
            if (byExternal.isPresent()) {
                return byExternal.get();
            }
        }
        String legalName = blankToNull(name) != null ? name.trim() : (external != null ? kind.name().toLowerCase() + " " + external : "Unknown company");
        String code = TenantCodeFormatter.toTenantCode(legalName);
        Optional<Company> sameCode = byCode(code);
        if (sameCode.isPresent() && sameCode.get().kind() == kind) {
            if (external != null && sameCode.get().externalCode() == null) {
                jdbc.update("UPDATE tenant SET external_code = :external WHERE id = :id",
                        new MapSqlParameterSource("external", external).addValue("id", sameCode.get().id()));
                return byCode(code).orElseThrow();
            }
            return sameCode.get();
        }
        if (sameCode.isPresent()) {
            String suffix = "-" + kind.name().toLowerCase().replace('_', '-');
            code = (code.length() + suffix.length() > 40 ? code.substring(0, 40 - suffix.length()) : code) + suffix;
            Optional<Company> suffixed = byCode(code);
            if (suffixed.isPresent()) {
                return suffixed.get();
            }
        }
        jdbc.update("""
                INSERT INTO tenant (id, code, legal_name, kind, external_code) VALUES (gen_random_uuid(), :code, :name, :kind, :external)
                ON CONFLICT (code) DO NOTHING
                """, new MapSqlParameterSource("code", code).addValue("name", legalName).addValue("kind", kind.name())
                .addValue("external", external));
        return byCode(code).orElseThrow();
    }

    private static Company map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Company(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("legal_name"),
                Kind.valueOf(rs.getString("kind")), rs.getString("external_code"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
