package com.srm.gps.application.auth;

import com.srm.gps.application.auth.PortalUser.Role;
import com.srm.gps.application.tms.TenantDirectory;
import com.srm.gps.application.tms.TenantDirectory.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Demo sign-ins, so each of the three roles can be tried at once (gps.auth.demo-accounts, on by default; switch it
 * off on a real installation and create the users on Developer -> TMS -> Companies & users). All use the user id
 * "abc" and the password gps.auth.demo-password ("abc"):
 *
 *   DEVELOPER    abc  the GPS provider
 *   CONSIGNER    abc  TATA STEEL (consignor 540 in the smart_truck TMS)
 *   TRANSPORTER  abc  the transporter carrying the most shipments, as soon as there are shipments
 *
 * Only made when that role has no user yet, so a changed password or a deleted demo user is never brought back.
 */
@Component
public class DemoAccounts {

    private static final Logger LOG = LoggerFactory.getLogger(DemoAccounts.class);
    static final String DEMO_USER = "abc";

    private final AccountStore accounts;
    private final TenantDirectory tenants;
    private final NamedParameterJdbcTemplate jdbc;
    private final boolean enabled;
    private final String password;

    public DemoAccounts(AccountStore accounts, TenantDirectory tenants, NamedParameterJdbcTemplate jdbc,
                        @Value("${gps.auth.demo-accounts:true}") boolean enabled,
                        @Value("${gps.auth.demo-password:abc}") String password) {
        this.accounts = accounts;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createAtStart() {
        if (!enabled) {
            return;
        }
        try {
            if (!accounts.anyWithRole(Role.DEVELOPER)) {
                accounts.create(Role.DEVELOPER, DEMO_USER, "GPS provider (demo)", password, null);
                LOG.warn("Created the demo DEVELOPER sign-in {} - change its password or switch gps.auth.demo-accounts off", DEMO_USER);
            }
            if (!accounts.anyWithRole(Role.CONSIGNER)) {
                Company tataSteel = tenants.findOrCreate(TenantDirectory.Kind.CONSIGNER, "TATA STEEL", "540");
                accounts.create(Role.CONSIGNER, DEMO_USER, "Tata Steel logistics (demo)", password, tataSteel.code());
                LOG.warn("Created the demo CONSIGNER sign-in {} for {}", DEMO_USER, tataSteel.name());
            }
            ensureTransporter();
        } catch (RuntimeException databaseProblem) {
            LOG.warn("Demo sign-ins not created: {}", databaseProblem.getMessage());
        }
    }

    /** Once there are shipments: a demo transporter sign-in for the transporter carrying the most of them. */
    public void ensureTransporter() {
        if (!enabled || accounts.anyWithRole(Role.TRANSPORTER)) {
            return;
        }
        List<String> busiest = jdbc.queryForList("""
                SELECT t.code FROM shipment s JOIN tenant t ON t.id = s.transporter_tenant_id
                WHERE t.kind = 'TRANSPORTER'
                GROUP BY t.code ORDER BY count(*) DESC, t.code LIMIT 1
                """, new MapSqlParameterSource(), String.class);
        if (busiest.isEmpty()) {
            return;
        }
        Company transporter = tenants.byCode(busiest.getFirst()).orElseThrow();
        accounts.create(Role.TRANSPORTER, DEMO_USER, transporter.name() + " (demo)", password, transporter.code());
        LOG.warn("Created the demo TRANSPORTER sign-in {} for {}", DEMO_USER, transporter.name());
    }
}
