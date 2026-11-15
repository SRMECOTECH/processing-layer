package com.srm.gps.application.tms;

import com.srm.gps.application.masterdata.DeviceVehicleDirectory;
import com.srm.gps.connectivity.redis.LiveVehiclePositionStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which GPS device (IMEI) is fitted in which truck (vehicle number), from when to when - the mapping the GPS provider
 * and the transporter agree on, kept in device_assignment as periods, never as "current vehicle": a reading delivered
 * late is matched to the truck the device was in AT THE TIME of the reading.
 *
 * Assigning is idempotent: the same device to the same truck again changes nothing. Moving a device closes its old
 * period (and the truck's period with another device) at the moment of the move, then opens the new one; the
 * database's exclusion constraint guarantees a device is never in two trucks at once.
 */
@Service
public class DeviceMappingService {

    public record DeviceRow(String imei, String kind, String protocol, String vehicleRegistrationNo, String ownerCode,
                            String ownerName, Instant fittedSince, Instant lastReadingAt) {
    }

    public record VehicleRow(String registrationNo, String ownerCode, String ownerName, String imei, Instant fittedSince) {
    }

    public record AssignmentRow(UUID id, String imei, String vehicleRegistrationNo, Instant validFrom, Instant validTo) {
    }

    public record Change(String outcome, AssignmentRow assignment, List<AssignmentRow> closed) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final DeviceVehicleDirectory directory;
    private final LiveVehiclePositionStore live;
    private final TenantDirectory tenants;

    public DeviceMappingService(NamedParameterJdbcTemplate jdbc, DeviceVehicleDirectory directory, LiveVehiclePositionStore live,
                                TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.live = live;
        this.tenants = tenants;
    }

    public List<DeviceRow> devices() {
        List<DeviceRow> rows = jdbc.query("""
                SELECT d.imei, d.kind, d.protocol, v.registration_no, t.code AS owner_code, t.legal_name AS owner_name, a.valid_from
                FROM device d
                LEFT JOIN device_assignment a ON a.device_id = d.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                LEFT JOIN vehicle v ON v.id = a.vehicle_id
                LEFT JOIN tenant t ON t.id = v.owner_tenant_id
                ORDER BY v.registration_no NULLS FIRST, d.imei
                """, new MapSqlParameterSource(), (rs, n) -> new DeviceRow(rs.getString("imei"), rs.getString("kind"),
                rs.getString("protocol"), rs.getString("registration_no"), rs.getString("owner_code"), rs.getString("owner_name"),
                instant(rs, "valid_from"), null));
        List<DeviceRow> withLastReading = new ArrayList<>(rows.size());
        for (DeviceRow row : rows) {
            Instant last;
            try {
                last = live.findRecordedAt(row.imei()).orElse(null);
            } catch (RuntimeException redisDown) {
                last = null;
            }
            withLastReading.add(new DeviceRow(row.imei(), row.kind(), row.protocol(), row.vehicleRegistrationNo(), row.ownerCode(),
                    row.ownerName(), row.fittedSince(), last));
        }
        return withLastReading;
    }

    /** @param ownerCode only this company's trucks, or every truck when null */
    public List<VehicleRow> vehicles(String ownerCode) {
        return jdbc.query("""
                SELECT v.registration_no, t.code AS owner_code, t.legal_name AS owner_name, d.imei, a.valid_from
                FROM vehicle v
                LEFT JOIN tenant t ON t.id = v.owner_tenant_id
                LEFT JOIN device_assignment a ON a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                LEFT JOIN device d ON d.id = a.device_id
                WHERE CAST(:owner AS varchar) IS NULL OR t.code = :owner
                ORDER BY v.registration_no
                """, new MapSqlParameterSource("owner", ownerCode), (rs, n) -> new VehicleRow(rs.getString("registration_no"),
                rs.getString("owner_code"), rs.getString("owner_name"), rs.getString("imei"), instant(rs, "valid_from")));
    }

    public List<AssignmentRow> history(String imei, String registrationNo) {
        return jdbc.query("""
                SELECT a.id, d.imei, v.registration_no, a.valid_from, a.valid_to
                FROM device_assignment a JOIN device d ON d.id = a.device_id JOIN vehicle v ON v.id = a.vehicle_id
                WHERE (CAST(:imei AS varchar) IS NULL OR d.imei = :imei) AND (CAST(:reg AS varchar) IS NULL OR v.registration_no = :reg)
                ORDER BY a.valid_from DESC LIMIT 200
                """, new MapSqlParameterSource("imei", imei).addValue("reg", registrationNo), (rs, n) -> assignment(rs));
    }

    /** A device the GPS provider has in stock: registered once by its IMEI, then mapped to a truck. */
    @Transactional
    public void registerDevice(String imei, String kind, String protocol) {
        if (imei == null || !imei.trim().matches("\\d{6,20}")) {
            throw new IllegalArgumentException("An IMEI is 6 to 20 digits");
        }
        String deviceKind = kind == null || kind.isBlank() ? "GPS" : kind.trim().toUpperCase();
        if (!List.of("GPS", "FUEL", "TEMPERATURE", "DOOR", "OBD").contains(deviceKind)) {
            throw new IllegalArgumentException("A device is GPS, FUEL, TEMPERATURE, DOOR or OBD");
        }
        jdbc.update("""
                INSERT INTO device (id, imei, kind, protocol) VALUES (gen_random_uuid(), :imei, :kind, :protocol)
                ON CONFLICT (imei) DO NOTHING
                """, new MapSqlParameterSource("imei", imei.trim()).addValue("kind", deviceKind).addValue("protocol", protocol));
    }

    /** A truck, created if new; @param ownerCode its transporter, or null for a market vehicle. */
    @Transactional
    public void registerVehicle(String registrationNo, String ownerCode) {
        String plate = ShipmentDraft.vehicleNo(registrationNo);
        if (plate == null) {
            throw new IllegalArgumentException("That is not a vehicle number");
        }
        UUID owner = ownerCode == null || ownerCode.isBlank() ? null
                : tenants.byCode(ownerCode).orElseThrow(() -> new IllegalArgumentException("No company " + ownerCode)).id();
        jdbc.update("""
                INSERT INTO vehicle (id, registration_no, owner_tenant_id) VALUES (gen_random_uuid(), :reg, :owner)
                ON CONFLICT (registration_no) DO UPDATE SET owner_tenant_id = COALESCE(vehicle.owner_tenant_id, EXCLUDED.owner_tenant_id)
                """, new MapSqlParameterSource("reg", plate).addValue("owner", owner));
    }

    /** Fits the device in the truck from {@code from} (now when null). */
    @Transactional
    public Change assign(String imei, String registrationNo, Instant from) {
        Instant at = from == null ? Instant.now() : from;
        UUID deviceId = idOf("SELECT id FROM device WHERE imei = :key FOR UPDATE", imei)
                .orElseThrow(() -> new IllegalArgumentException("No device with IMEI " + imei + " - register it first"));
        String plate = ShipmentDraft.vehicleNo(registrationNo);
        UUID vehicleId = idOf("SELECT id FROM vehicle WHERE registration_no = :key FOR UPDATE", plate)
                .orElseThrow(() -> new IllegalArgumentException("No vehicle " + registrationNo + " - add it first"));

        Optional<AssignmentRow> current = activeAt("a.device_id = :id", deviceId, at);
        if (current.isPresent() && current.get().vehicleRegistrationNo().equals(plate)) {
            return new Change("UNCHANGED", current.get(), List.of());   // already fitted there: nothing to do
        }
        List<AssignmentRow> closed = new ArrayList<>();
        current.ifPresent(a -> closed.add(close(a, at)));
        activeAt("a.vehicle_id = :id", vehicleId, at).ifPresent(a -> closed.add(close(a, at)));
        // a later period already planned for either of them would overlap the new open one
        Integer later = jdbc.queryForObject("SELECT count(*) FROM device_assignment WHERE (device_id = :d OR vehicle_id = :v) AND valid_from > :at",
                new MapSqlParameterSource("d", deviceId).addValue("v", vehicleId).addValue("at", Timestamp.from(at)), Integer.class);
        if (later != null && later > 0) {
            throw new IllegalArgumentException("The device or the truck already has a mapping after " + at + "; map from a later moment");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device_assignment (id, device_id, vehicle_id, valid_from) VALUES (:id, :d, :v, :at)",
                new MapSqlParameterSource("id", id).addValue("d", deviceId).addValue("v", vehicleId).addValue("at", Timestamp.from(at)));
        evict(imei, closed);
        return new Change(closed.isEmpty() ? "MAPPED" : "MOVED", new AssignmentRow(id, imei, plate, at, null), closed);
    }

    /** Takes the device out of its truck at {@code at} (now when null). */
    @Transactional
    public Change unassign(String imei, Instant at) {
        Instant moment = at == null ? Instant.now() : at;
        UUID deviceId = idOf("SELECT id FROM device WHERE imei = :key FOR UPDATE", imei)
                .orElseThrow(() -> new IllegalArgumentException("No device with IMEI " + imei));
        Optional<AssignmentRow> current = activeAt("a.device_id = :id", deviceId, moment);
        if (current.isEmpty()) {
            return new Change("UNCHANGED", null, List.of());
        }
        AssignmentRow closed = close(current.get(), moment);
        evict(imei, List.of(closed));
        return new Change("UNMAPPED", null, List.of(closed));
    }

    /** IMEI -> vehicle of every device fitted right now, for matching a shipment file's IMEI column. */
    public Map<String, String> currentVehicleByImei() {
        Map<String, String> map = new java.util.HashMap<>();
        jdbc.query("""
                SELECT d.imei, v.registration_no FROM device_assignment a JOIN device d ON d.id = a.device_id JOIN vehicle v ON v.id = a.vehicle_id
                WHERE a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                """, new MapSqlParameterSource(), rs -> {
            map.put(rs.getString("imei"), rs.getString("registration_no"));
        });
        return map;
    }

    private Optional<AssignmentRow> activeAt(String condition, UUID id, Instant at) {
        return jdbc.query("""
                SELECT a.id, d.imei, v.registration_no, a.valid_from, a.valid_to
                FROM device_assignment a JOIN device d ON d.id = a.device_id JOIN vehicle v ON v.id = a.vehicle_id
                WHERE %s AND a.valid_from <= :at AND (a.valid_to IS NULL OR a.valid_to > :at)
                """.formatted(condition), new MapSqlParameterSource("id", id).addValue("at", Timestamp.from(at)),
                (rs, n) -> assignment(rs)).stream().findFirst();
    }

    private AssignmentRow close(AssignmentRow a, Instant at) {
        if (!a.validFrom().isBefore(at)) {
            throw new IllegalArgumentException("The mapping of " + a.imei() + " to " + a.vehicleRegistrationNo()
                    + " starts at " + a.validFrom() + "; a change must come after that");
        }
        jdbc.update("UPDATE device_assignment SET valid_to = :at WHERE id = :id",
                new MapSqlParameterSource("at", Timestamp.from(at)).addValue("id", a.id()));
        return new AssignmentRow(a.id(), a.imei(), a.vehicleRegistrationNo(), a.validFrom(), at);
    }

    private void evict(String imei, List<AssignmentRow> closed) {
        directory.evict(imei);
        closed.forEach(a -> directory.evict(a.imei()));
    }

    private Optional<UUID> idOf(String sql, String key) {
        if (key == null) {
            return Optional.empty();
        }
        return jdbc.query(sql, new MapSqlParameterSource("key", key.trim()), (rs, n) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    private static AssignmentRow assignment(ResultSet rs) throws SQLException {
        return new AssignmentRow(rs.getObject("id", UUID.class), rs.getString("imei"), rs.getString("registration_no"),
                instant(rs, "valid_from"), instant(rs, "valid_to"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
