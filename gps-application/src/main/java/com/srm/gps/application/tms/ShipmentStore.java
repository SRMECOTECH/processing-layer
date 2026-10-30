package com.srm.gps.application.tms;

import com.srm.gps.application.auth.PortalUser;
import com.srm.gps.processing.shipment.ShipmentProgress;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.srm.gps.application.tms.DeviceMappingService.instant;

/** The shipment table: written by the import and the tracking module, read by the portals - always within a scope. */
@Component
public class ShipmentStore {

    /** One stored shipment with its companies' names and the device fitted in its truck right now. */
    public record Stored(long id, String tripNo, String source, String transporterCode, String transporterName,
                         String consignerCode, String consignerName, String consignerUnit, String consignee, String consigneeAddress,
                         String vehicleNo, String deviceImei, String mappedImei, String vehicleType, String tripClass, String tripType,
                         String origin, String destination, String loadPlant, Long originPlaceId, Long destinationPlaceId,
                         Instant bookedAt, Instant dispatchedAt, Instant etaAt, Instant reportedAtaAt, Instant reportedEndAt,
                         String invoiceNo, String shipmentRef, String gateEntryNo, String cardId, String routeId, String material,
                         BigDecimal quantity, String driverName, String driverMobile, String sourceStatus, String closeReason,
                         Instant leftOriginAt, Instant outsideDestinationAt, Instant arrivedAt, Instant lastReadingAt,
                         Double lastLatitude, Double lastLongitude, Integer lastSpeedKmph, Instant closedAt, String closedReason,
                         Long importId, Instant createdAt, Instant updatedAt) {

        public ShipmentProgress progress() {
            return new ShipmentProgress(leftOriginAt, outsideDestinationAt, arrivedAt, lastReadingAt, lastLatitude, lastLongitude, lastSpeedKmph);
        }

        public boolean sourceClosed() {
            return sourceStatus != null && sourceStatus.toLowerCase().startsWith("clos");
        }
    }

    /**
     * Whose shipments a request may see: a transporter its own, a consigner its own, the GPS provider everyone's
     * (optionally narrowed to one transporter or consigner).
     */
    public record Scope(PortalUser.Role role, UUID tenantId, UUID transporterFilter, UUID consignerFilter) {

        public static Scope everyone() {
            return new Scope(PortalUser.Role.DEVELOPER, null, null, null);
        }

        String where() {
            return switch (role) {
                case TRANSPORTER -> "s.transporter_tenant_id = :scopeTenant";
                case CONSIGNER -> "s.consigner_tenant_id = :scopeTenant";
                case DEVELOPER -> "(CAST(:transporterFilter AS uuid) IS NULL OR s.transporter_tenant_id = :transporterFilter)"
                        + " AND (CAST(:consignerFilter AS uuid) IS NULL OR s.consigner_tenant_id = :consignerFilter)";
            };
        }

        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource("scopeTenant", tenantId).addValue("transporterFilter", transporterFilter)
                    .addValue("consignerFilter", consignerFilter);
        }
    }

    public enum Outcome { CREATED, UPDATED, UNCHANGED }

    static final String SELECT = """
            SELECT s.*, tr.code AS transporter_code_t, tr.legal_name AS transporter_name, cn.code AS consigner_code_t,
                   cn.legal_name AS consigner_name, m.imei AS mapped_imei
            FROM shipment s
            JOIN tenant tr ON tr.id = s.transporter_tenant_id
            LEFT JOIN tenant cn ON cn.id = s.consigner_tenant_id
            LEFT JOIN LATERAL (
                SELECT d.imei FROM vehicle v
                JOIN device_assignment a ON a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                JOIN device d ON d.id = a.device_id
                WHERE v.registration_no = s.vehicle_registration_no LIMIT 1) m ON true
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ShipmentStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- import

    /** trip number -> stored row hash, for the preview's "new / changed / unchanged" counts. */
    public Map<String, String> hashes(UUID transporterId, Collection<String> tripNos) {
        Map<String, String> hashes = new HashMap<>();
        if (tripNos.isEmpty()) {
            return hashes;
        }
        jdbc.query("SELECT trip_no, row_hash FROM shipment WHERE transporter_tenant_id = :t AND trip_no IN (:trips)",
                new MapSqlParameterSource("t", transporterId).addValue("trips", tripNos),
                rs -> {
                    hashes.put(rs.getString("trip_no"), rs.getString("row_hash"));
                });
        return hashes;
    }

    /**
     * Inserts the shipment, or updates it when the row changed; an unchanged row is not written at all. When the truck
     * of a shipment is changed, what the GPS proved about the old truck no longer applies and is cleared.
     */
    public Optional<Map.Entry<Long, Outcome>> upsert(ShipmentDraft d, String source, UUID transporterId, UUID consignerId,
                                                     Long originPlaceId, Long destinationPlaceId, long importId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tripNo", d.tripNo()).addValue("source", source).addValue("transporter", transporterId)
                .addValue("transporterCode", d.transporterCode()).addValue("consigner", consignerId)
                .addValue("consignerCode", d.consignerCode()).addValue("consignerUnit", d.consignerUnit())
                .addValue("consignee", d.consignee()).addValue("consigneeAddress", d.finalDestination())
                .addValue("vehicle", d.vehicleNo()).addValue("imei", d.deviceImei()).addValue("vehicleType", d.vehicleType())
                .addValue("tripClass", d.tripClass()).addValue("tripType", d.tripType()).addValue("origin", d.origin())
                .addValue("destination", d.destination()).addValue("loadPlant", d.loadPlant())
                .addValue("originPlace", originPlaceId).addValue("destinationPlace", destinationPlaceId)
                .addValue("bookedAt", ts(d.bookedAt())).addValue("dispatchedAt", ts(d.dispatchedAt())).addValue("eta", ts(d.etaAt()))
                .addValue("ata", ts(d.ataAt())).addValue("ended", ts(d.endedAt())).addValue("invoice", d.invoice())
                .addValue("shipmentRef", d.shipmentRef()).addValue("gateEntry", d.gateEntry()).addValue("cardId", d.cardId())
                .addValue("routeId", d.routeId()).addValue("material", d.material()).addValue("quantity", d.quantity())
                .addValue("driverName", d.driverName()).addValue("driverMobile", d.driverMobile()).addValue("status", d.status())
                .addValue("closeReason", d.closeReason()).addValue("hash", d.hash()).addValue("importId", importId);
        List<Map.Entry<Long, Outcome>> result = jdbc.query("""
                INSERT INTO shipment (trip_no, source, transporter_tenant_id, transporter_code, consigner_tenant_id, consigner_code,
                    consigner_unit, consignee_name, consignee_address, vehicle_registration_no, device_imei, vehicle_type, trip_class,
                    trip_type, origin_name, destination_name, load_plant, origin_place_id, destination_place_id, booked_at,
                    dispatched_at, eta_at, reported_ata_at, reported_end_at, invoice_no, shipment_ref, gate_entry_no, card_id,
                    route_id, material, quantity, driver_name, driver_mobile, source_status, close_reason, row_hash, import_id)
                VALUES (:tripNo, :source, :transporter, :transporterCode, :consigner, :consignerCode, :consignerUnit, :consignee,
                    :consigneeAddress, :vehicle, :imei, :vehicleType, :tripClass, :tripType, :origin, :destination, :loadPlant,
                    :originPlace, :destinationPlace, :bookedAt, :dispatchedAt, :eta, :ata, :ended, :invoice, :shipmentRef,
                    :gateEntry, :cardId, :routeId, :material, :quantity, :driverName, :driverMobile, :status, :closeReason,
                    :hash, :importId)
                ON CONFLICT ON CONSTRAINT shipment_trip_once_per_transporter DO UPDATE SET
                    source = EXCLUDED.source, transporter_code = EXCLUDED.transporter_code,
                    consigner_tenant_id = EXCLUDED.consigner_tenant_id, consigner_code = EXCLUDED.consigner_code,
                    consigner_unit = EXCLUDED.consigner_unit, consignee_name = EXCLUDED.consignee_name,
                    consignee_address = EXCLUDED.consignee_address, vehicle_registration_no = EXCLUDED.vehicle_registration_no,
                    device_imei = EXCLUDED.device_imei, vehicle_type = EXCLUDED.vehicle_type, trip_class = EXCLUDED.trip_class,
                    trip_type = EXCLUDED.trip_type, origin_name = EXCLUDED.origin_name, destination_name = EXCLUDED.destination_name,
                    load_plant = EXCLUDED.load_plant, origin_place_id = EXCLUDED.origin_place_id,
                    destination_place_id = EXCLUDED.destination_place_id, booked_at = EXCLUDED.booked_at,
                    dispatched_at = EXCLUDED.dispatched_at, eta_at = EXCLUDED.eta_at, reported_ata_at = EXCLUDED.reported_ata_at,
                    reported_end_at = EXCLUDED.reported_end_at, invoice_no = EXCLUDED.invoice_no, shipment_ref = EXCLUDED.shipment_ref,
                    gate_entry_no = EXCLUDED.gate_entry_no, card_id = EXCLUDED.card_id, route_id = EXCLUDED.route_id,
                    material = EXCLUDED.material, quantity = EXCLUDED.quantity, driver_name = EXCLUDED.driver_name,
                    driver_mobile = EXCLUDED.driver_mobile, source_status = EXCLUDED.source_status,
                    close_reason = EXCLUDED.close_reason, row_hash = EXCLUDED.row_hash, import_id = EXCLUDED.import_id,
                    left_origin_at = CASE WHEN shipment.vehicle_registration_no = EXCLUDED.vehicle_registration_no
                                          AND shipment.dispatched_at = EXCLUDED.dispatched_at THEN shipment.left_origin_at END,
                    outside_destination_at = CASE WHEN shipment.vehicle_registration_no = EXCLUDED.vehicle_registration_no
                                          AND shipment.dispatched_at = EXCLUDED.dispatched_at THEN shipment.outside_destination_at END,
                    arrived_at = CASE WHEN shipment.vehicle_registration_no = EXCLUDED.vehicle_registration_no
                                          AND shipment.dispatched_at = EXCLUDED.dispatched_at THEN shipment.arrived_at END,
                    updated_at = now()
                WHERE shipment.row_hash IS DISTINCT FROM EXCLUDED.row_hash
                RETURNING id, (xmax = 0) AS inserted
                """, p, (rs, n) -> Map.entry(rs.getLong("id"), rs.getBoolean("inserted") ? Outcome.CREATED : Outcome.UPDATED));
        return result.stream().findFirst();
    }

    /**
     * The consigner TMS's rule "TRIP CLOSED BY NEXT UPLOAD": a truck carries one shipment at a time, so a newer
     * shipment of the same truck closes its older ones that never arrived.
     *
     * @return how many were closed
     */
    public int closeEarlierTripsOfVehicle(String vehicleNo, Instant dispatchedAt, long exceptId) {
        return jdbc.update("""
                UPDATE shipment SET closed_at = :at, closed_reason = 'NEXT_UPLOAD', updated_at = now()
                WHERE vehicle_registration_no = :vehicle AND dispatched_at < :at AND id <> :id
                  AND closed_at IS NULL AND arrived_at IS NULL AND reported_ata_at IS NULL
                """, new MapSqlParameterSource("vehicle", vehicleNo).addValue("at", ts(dispatchedAt)).addValue("id", exceptId));
    }

    public long createImport(String fileName, String sha256, String format, String uploadedBy, String role, UUID tenantId) {
        return jdbc.queryForObject("""
                INSERT INTO shipment_import (file_name, file_sha256, format, uploaded_by, uploaded_role, tenant_id, rows_total,
                    rows_created, rows_updated, rows_unchanged, rows_failed)
                VALUES (:name, :sha, :format, :by, :role, :tenant, 0, 0, 0, 0, 0) RETURNING id
                """, new MapSqlParameterSource("name", fileName).addValue("sha", sha256).addValue("format", format)
                .addValue("by", uploadedBy).addValue("role", role).addValue("tenant", tenantId), Long.class);
    }

    public void finishImport(long id, int total, int created, int updated, int unchanged, int failed, String problemsJson, String mappingJson) {
        jdbc.update("""
                UPDATE shipment_import SET rows_total = :total, rows_created = :created, rows_updated = :updated,
                    rows_unchanged = :unchanged, rows_failed = :failed, problems = CAST(:problems AS jsonb),
                    column_mapping = CAST(:mapping AS jsonb)
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("total", total).addValue("created", created)
                .addValue("updated", updated).addValue("unchanged", unchanged).addValue("failed", failed)
                .addValue("problems", problemsJson).addValue("mapping", mappingJson));
    }

    /** When this exact file was imported before, if it was. */
    public Optional<Instant> importedBefore(String sha256) {
        return jdbc.query("SELECT min(uploaded_at) AS first FROM shipment_import WHERE file_sha256 = :sha",
                new MapSqlParameterSource("sha", sha256), (rs, n) -> instant(rs, "first")).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public List<Map<String, Object>> imports(Scope scope, int limit) {
        String where = switch (scope.role()) {
            case DEVELOPER -> "true";
            default -> "i.tenant_id = :scopeTenant";
        };
        return jdbc.queryForList("""
                SELECT i.id, i.file_name, i.format, i.uploaded_by, i.uploaded_role, t.legal_name AS company, i.rows_total,
                       i.rows_created, i.rows_updated, i.rows_unchanged, i.rows_failed, i.uploaded_at
                FROM shipment_import i LEFT JOIN tenant t ON t.id = i.tenant_id
                WHERE %s ORDER BY i.uploaded_at DESC LIMIT :limit
                """.formatted(where), scope.parameters().addValue("limit", limit));
    }

    // ---------------------------------------------------------------- tracking module

    /**
     * The shipments a reading of this truck can still tell something about: past their gate-out, not closed before the
     * reading, not more than a week old, and not arrived before it. Matched by the truck, or by the IMEI the file named
     * when the truck has no device mapped.
     */
    public List<Stored> trackable(String vehicleNo, String imei, Instant at) {
        return jdbc.query(SELECT + """
                WHERE (s.vehicle_registration_no = :vehicle OR (s.device_imei = :imei AND m.imei IS NULL))
                  AND s.dispatched_at <= :at AND s.dispatched_at > :weekBefore
                  AND (s.closed_at IS NULL OR :at <= s.closed_at)
                  AND (s.arrived_at IS NULL OR :at <= s.arrived_at)
                ORDER BY s.dispatched_at DESC LIMIT 5
                """, new MapSqlParameterSource("vehicle", vehicleNo).addValue("imei", imei).addValue("at", ts(at))
                .addValue("weekBefore", ts(at.minusSeconds(7 * 86_400))), (rs, n) -> map(rs));
    }

    public void saveProgress(long id, ShipmentProgress p) {
        jdbc.update("""
                UPDATE shipment SET left_origin_at = :left, outside_destination_at = :outside, arrived_at = :arrived,
                    last_reading_at = :last, last_latitude = :lat, last_longitude = :lon, last_speed_kmph = :speed
                WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("left", ts(p.leftOriginAt()))
                .addValue("outside", ts(p.outsideDestinationAt())).addValue("arrived", ts(p.arrivedAt()))
                .addValue("last", ts(p.lastReadingAt())).addValue("lat", p.lastLatitude()).addValue("lon", p.lastLongitude())
                .addValue("speed", p.lastSpeedKmph()));
    }

    /** Before a replay from {@code from}: forget what the GPS proved for shipments dispatched since then. */
    public int clearProgressSince(Instant from) {
        return jdbc.update("""
                UPDATE shipment SET left_origin_at = NULL, outside_destination_at = NULL, arrived_at = NULL, last_reading_at = NULL,
                    last_latitude = NULL, last_longitude = NULL, last_speed_kmph = NULL
                WHERE dispatched_at >= :from
                """, new MapSqlParameterSource("from", ts(from)));
    }

    // ---------------------------------------------------------------- portals

    public List<Stored> list(Scope scope, String search, Instant dispatchedSince, int limit) {
        MapSqlParameterSource p = scope.parameters().addValue("q", search == null || search.isBlank() ? null : "%" + search.trim() + "%")
                .addValue("since", ts(dispatchedSince)).addValue("limit", limit);
        return jdbc.query(SELECT + " WHERE " + scope.where() + """
                 AND (CAST(:since AS timestamptz) IS NULL OR s.dispatched_at >= :since)
                 AND (CAST(:q AS varchar) IS NULL OR s.trip_no ILIKE :q OR s.vehicle_registration_no ILIKE :q
                      OR s.consignee_name ILIKE :q OR s.destination_name ILIKE :q OR s.invoice_no ILIKE :q
                      OR s.driver_name ILIKE :q OR tr.legal_name ILIKE :q)
                ORDER BY s.dispatched_at DESC LIMIT :limit
                """, p, (rs, n) -> map(rs));
    }

    public Optional<Stored> byId(long id, Scope scope) {
        return jdbc.query(SELECT + " WHERE s.id = :id AND " + scope.where(), scope.parameters().addValue("id", id), (rs, n) -> map(rs))
                .stream().findFirst();
    }

    /** Readings of a device between two moments, thinned to at most {@code max} points for a map. */
    public List<Map<String, Object>> track(String imei, Instant from, Instant to, int max) {
        return jdbc.queryForList("""
                SELECT recorded_at, latitude, longitude, speed_kmph FROM (
                    SELECT recorded_at, latitude, longitude, speed_kmph,
                           row_number() OVER (ORDER BY recorded_at) AS n, count(*) OVER () AS total
                    FROM gps_position WHERE imei = :imei AND recorded_at BETWEEN :from AND :to) r
                WHERE total <= :max OR n % CEIL(total::numeric / :max)::int = 1 OR n = total
                ORDER BY recorded_at
                """, new MapSqlParameterSource("imei", imei).addValue("from", ts(from)).addValue("to", ts(to)).addValue("max", max));
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Stored map(ResultSet rs) throws SQLException {
        return new Stored(rs.getLong("id"), rs.getString("trip_no"), rs.getString("source"), rs.getString("transporter_code_t"),
                rs.getString("transporter_name"), rs.getString("consigner_code_t"), rs.getString("consigner_name"),
                rs.getString("consigner_unit"), rs.getString("consignee_name"), rs.getString("consignee_address"),
                rs.getString("vehicle_registration_no"), rs.getString("device_imei"), rs.getString("mapped_imei"),
                rs.getString("vehicle_type"), rs.getString("trip_class"), rs.getString("trip_type"), rs.getString("origin_name"),
                rs.getString("destination_name"), rs.getString("load_plant"), (Long) rs.getObject("origin_place_id"),
                (Long) rs.getObject("destination_place_id"), instant(rs, "booked_at"), instant(rs, "dispatched_at"),
                instant(rs, "eta_at"), instant(rs, "reported_ata_at"), instant(rs, "reported_end_at"), rs.getString("invoice_no"),
                rs.getString("shipment_ref"), rs.getString("gate_entry_no"), rs.getString("card_id"), rs.getString("route_id"),
                rs.getString("material"), rs.getBigDecimal("quantity"), rs.getString("driver_name"), rs.getString("driver_mobile"),
                rs.getString("source_status"), rs.getString("close_reason"), instant(rs, "left_origin_at"),
                instant(rs, "outside_destination_at"), instant(rs, "arrived_at"), instant(rs, "last_reading_at"),
                (Double) rs.getObject("last_latitude"), (Double) rs.getObject("last_longitude"),
                (Integer) rs.getObject("last_speed_kmph"), instant(rs, "closed_at"), rs.getString("closed_reason"),
                (Long) rs.getObject("import_id"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
