package com.srm.gps.application.tms;

import com.srm.gps.application.auth.PortalUser;
import com.srm.gps.application.auth.PortalUser.Role;
import com.srm.gps.application.tms.ShipmentStore.Scope;
import com.srm.gps.application.tms.TenantDirectory.Company;
import com.srm.gps.connectivity.redis.LiveVehiclePositionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * What each kind of user sees, always inside their scope (ShipmentStore.Scope): a transporter its fleet and the
 * shipments it carries, a consigner its shipments and how its transporters do, the GPS provider all of it and the
 * platform underneath. The numbers are worked out from the shipments of the period on every request.
 */
@Service
public class PortalService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** A truck that has not reported for this long is offline on the fleet board. */
    static final Duration OFFLINE_AFTER = Duration.ofMinutes(30);
    private static final int PERIOD_DAYS = 30;
    private static final int MAX_SHIPMENTS = 5_000;

    private final ShipmentStore shipments;
    private final PlaceStore places;
    private final TenantDirectory tenants;
    private final NamedParameterJdbcTemplate jdbc;
    private final LiveVehiclePositionStore live;
    private final ZoneId zone;

    public PortalService(ShipmentStore shipments, PlaceStore places, TenantDirectory tenants, NamedParameterJdbcTemplate jdbc,
                         LiveVehiclePositionStore live, @Value("${gps.tms.time-zone:Asia/Kolkata}") String zone) {
        this.shipments = shipments;
        this.places = places;
        this.tenants = tenants;
        this.jdbc = jdbc;
        this.live = live;
        this.zone = ZoneId.of(zone);
    }

    // ---------------------------------------------------------------- scope

    /**
     * The scope of a request. A transporter or consigner gets its own company whatever it asks for; the GPS provider may
     * narrow to one transporter and/or consigner by code.
     */
    public Scope scope(PortalUser user, String transporterCode, String consignerCode) {
        return switch (user.role()) {
            case TRANSPORTER, CONSIGNER -> new Scope(user.role(), company(user.tenantCode()).id(), null, null);
            case DEVELOPER -> new Scope(Role.DEVELOPER, null, codeToId(transporterCode), codeToId(consignerCode));
        };
    }

    private Company company(String code) {
        return tenants.byCode(code == null ? "" : code).orElseThrow(() -> new PortalUser.ForbiddenException("Your company is not on the platform"));
    }

    private java.util.UUID codeToId(String code) {
        return code == null || code.isBlank() ? null
                : tenants.byCode(code).orElseThrow(() -> new IllegalArgumentException("No company " + code)).id();
    }

    // ---------------------------------------------------------------- shipments

    public List<ShipmentView> shipments(Scope scope, String search, Integer days, int limit) {
        Instant since = days == null || days <= 0 ? null : Instant.now().minus(Duration.ofDays(days));
        Instant now = Instant.now();
        return shipments.list(scope, search, since, limit).stream().map(s -> view(s, now)).toList();
    }

    public Map<String, Object> shipment(Scope scope, long id) {
        ShipmentStore.Stored s = shipments.byId(id, scope).orElseThrow(() -> new IllegalArgumentException("No shipment " + id));
        Instant now = Instant.now();
        ShipmentView view = view(s, now);
        // the truck is followed only for the shipment's own window: gate-out to arrival (or closing, or a week)
        Instant from = s.bookedAt() != null && s.bookedAt().isBefore(s.dispatchedAt()) ? s.bookedAt() : s.dispatchedAt();
        Instant end = view.arrivedAt() != null ? view.arrivedAt().plusSeconds(600)
                : s.closedAt() != null ? s.closedAt() : min(now, s.dispatchedAt().plus(Duration.ofDays(7)));
        String imei = s.mappedImei() != null ? s.mappedImei() : s.deviceImei();
        List<Map<String, Object>> track = imei == null || end.isBefore(from) ? List.of() : shipments.track(imei, from, end, 600);
        List<Map<String, Object>> events = jdbc.query(EVENT_SELECT + """
                WHERE e.vehicle_registration_no = :vehicle AND e.started_at BETWEEN :from AND :to
                ORDER BY e.started_at
                """, new MapSqlParameterSource("vehicle", s.vehicleNo()).addValue("from", ShipmentStore.ts(from))
                .addValue("to", ShipmentStore.ts(end)), (rs, n) -> event(rs));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipment", view);
        detail.put("track", track);
        detail.put("trackFrom", from);
        detail.put("trackTo", end);
        detail.put("events", events);
        detail.put("timeline", timeline(view, events));
        return detail;
    }

    private ShipmentView view(ShipmentStore.Stored s, Instant now) {
        return ShipmentView.of(s, places.byId(s.originPlaceId()).orElse(null), places.byId(s.destinationPlaceId()).orElse(null), now);
    }

    private static List<Map<String, Object>> timeline(ShipmentView v, List<Map<String, Object>> events) {
        List<Map<String, Object>> steps = new ArrayList<>();
        step(steps, v.bookedAt(), "BOOKED", "Booked / reported at the plant" + (v.loadPlant() != null ? " (" + v.loadPlant() + ")" : ""));
        step(steps, v.dispatchedAt(), "GATE_OUT", "Gate-out: trip started");
        step(steps, v.leftOriginAt(), "DEPARTED", "Cleared the " + (v.originFence() != null ? v.originFence().name() : "origin") + " geofence");
        for (Map<String, Object> e : events) {
            String type = String.valueOf(e.get("type"));
            if (!type.startsWith("SHIPMENT_") && !type.startsWith("IGNITION_")) {
                step(steps, (Instant) e.get("startedAt"), type, type.replace('_', ' ').toLowerCase());
            }
        }
        step(steps, v.etaAt(), "ETA", "ETA");
        step(steps, v.arrivedAt(), "ARRIVED", "Arrived at " + (v.destination() != null ? v.destination() : "the destination")
                + (v.gpsArrivedAt() == null && v.reportedAtaAt() != null ? " (reported by the TMS)" : ""));
        step(steps, v.closedAt(), "CLOSED", "Closed" + (v.closedReason() != null ? ": " + v.closedReason().replace('_', ' ').toLowerCase() : ""));
        steps.sort(Comparator.comparing(m -> (Instant) m.get("at")));
        return steps;
    }

    private static void step(List<Map<String, Object>> steps, Instant at, String kind, String label) {
        if (at != null) {
            steps.add(Map.of("at", at, "kind", kind, "label", label));
        }
    }

    // ---------------------------------------------------------------- overviews

    public Map<String, Object> overview(PortalUser user, Scope scope) {
        Instant now = Instant.now();
        List<ShipmentView> period = shipments(scope, null, PERIOD_DAYS, MAX_SHIPMENTS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", user.role());
        out.put("company", user.tenantName());
        out.put("periodDays", PERIOD_DAYS);
        out.put("generatedAt", now);
        out.put("shipments", shipmentKpis(period, now));
        out.put("attention", attention(period));
        if (scope.role() != Role.TRANSPORTER) {
            out.put("lanes", lanes(period));
            out.put("transporters", byTransporter(period, scope));
            out.put("consignees", byConsignee(period));
            out.put("plants", byPlant(period));
        }
        if (scope.role() != Role.CONSIGNER) {
            List<Map<String, Object>> fleet = fleet(scope);
            out.put("fleet", fleetKpis(fleet));
            out.put("today", today(fleet));
            out.put("lanes", lanes(period));
        }
        if (scope.role() == Role.DEVELOPER) {
            out.put("platform", platform());
        }
        out.put("recentAlerts", alerts(scope, 10));
        return out;
    }

    private Map<String, Object> shipmentKpis(List<ShipmentView> period, Instant now) {
        LocalDate today = LocalDate.now(zone);
        Instant startOfDay = today.atStartOfDay(zone).toInstant();
        List<ShipmentView> arrived = period.stream().filter(v -> v.arrivedAt() != null && v.etaAt() != null).toList();
        List<ShipmentView> late = arrived.stream().filter(v -> "LATE".equals(v.verdict())).toList();
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("total", period.size());
        k.put("active", count(period, ShipmentView::onTheRoad));
        k.put("booked", count(period, v -> "BOOKED".equals(v.stage())));
        k.put("atOrigin", count(period, v -> "AT_ORIGIN".equals(v.stage())));
        k.put("inTransit", count(period, v -> "IN_TRANSIT".equals(v.stage())));
        k.put("arrivedToday", count(period, v -> v.arrivedAt() != null && !v.arrivedAt().isBefore(startOfDay)));
        k.put("arrived", count(period, v -> "ARRIVED".equals(v.stage())));
        k.put("closedWithoutArrival", count(period, v -> "CLOSED".equals(v.stage())));
        k.put("delayed", count(period, v -> "DELAYED".equals(v.verdict())));
        k.put("atRisk", count(period, v -> "AT_RISK".equals(v.verdict())));
        k.put("gpsSilent", count(period, ShipmentView::gpsSilent));
        k.put("onTimePct", arrived.isEmpty() ? null : round1(100.0 * (arrived.size() - late.size()) / arrived.size()));
        k.put("onTimeBasis", arrived.size());
        k.put("avgDelayMinutesWhenLate", average(late, v -> v.delayMinutes() == null ? null : v.delayMinutes().doubleValue()));
        k.put("avgTransitHours", average(period.stream().filter(v -> v.arrivedAt() != null).toList(),
                v -> v.transitMinutes() == null ? null : v.transitMinutes() / 60.0));
        k.put("avgWorksDetentionMinutes", average(period, v -> v.worksDetentionMinutes() == null ? null : v.worksDetentionMinutes().doubleValue()));
        k.put("avgOriginHoldMinutes", average(period, v -> v.originHoldMinutes() == null ? null : v.originHoldMinutes().doubleValue()));
        k.put("gpsCoveragePct", period.isEmpty() ? null
                : round1(100.0 * count(period, v -> v.lastReadingAt() != null) / Math.max(1, count(period, v -> !"BOOKED".equals(v.stage())))));
        k.put("noDevice", count(period, v -> "NO_DEVICE".equals(v.gpsLink())));
        k.put("imeiMismatch", count(period, v -> "IMEI_MISMATCH".equals(v.gpsLink())));
        k.put("quantityOnTheRoad", period.stream().filter(ShipmentView::onTheRoad).map(ShipmentView::quantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return k;
    }

    /** What needs someone now: delayed first (longest first), then at risk, then trucks gone silent on the road. */
    private static List<ShipmentView> attention(List<ShipmentView> period) {
        return period.stream().filter(v -> "DELAYED".equals(v.verdict()) || "AT_RISK".equals(v.verdict()) || v.gpsSilent())
                .sorted(Comparator.comparing((ShipmentView v) -> "DELAYED".equals(v.verdict()) ? 0 : "AT_RISK".equals(v.verdict()) ? 1 : 2)
                        .thenComparing(v -> v.delayMinutes() == null ? 0 : -v.delayMinutes()))
                .limit(12).toList();
    }

    private static List<Map<String, Object>> lanes(List<ShipmentView> period) {
        return group(period, v -> nz(v.origin()) + " → " + nz(v.destination()), (lane, list) -> {
            Map<String, Object> row = performance(list);
            row.put("lane", lane);
            return row;
        }, 15);
    }

    private List<Map<String, Object>> byTransporter(List<ShipmentView> period, Scope scope) {
        Map<String, Long> overspeed = overspeedByTransporter(scope);
        return group(period, v -> v.transporterCode(), (code, list) -> {
            Map<String, Object> row = performance(list);
            row.put("code", code);
            row.put("name", list.getFirst().transporterName());
            row.put("trucks", list.stream().map(ShipmentView::vehicleNo).distinct().count());
            row.put("gpsCoveragePct", round1(100.0 * count(list, v -> v.lastReadingAt() != null)
                    / Math.max(1, count(list, v -> !"BOOKED".equals(v.stage())))));
            row.put("overspeedEvents", overspeed.getOrDefault(code, 0L));
            return row;
        }, 50);
    }

    private static List<Map<String, Object>> byConsignee(List<ShipmentView> period) {
        return group(period, v -> nz(v.consignee()), (name, list) -> {
            Map<String, Object> row = performance(list);
            row.put("consignee", name);
            return row;
        }, 15);
    }

    /** Detention per loading plant: plant entry to gate-out (declared) and gate-out to leaving the fence (hidden). */
    private static List<Map<String, Object>> byPlant(List<ShipmentView> period) {
        return group(period, v -> nz(v.loadPlant()), (plant, list) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plant", plant);
            row.put("shipments", list.size());
            row.put("avgWorksDetentionMinutes", average(list, v -> v.worksDetentionMinutes() == null ? null : v.worksDetentionMinutes().doubleValue()));
            row.put("avgOriginHoldMinutes", average(list, v -> v.originHoldMinutes() == null ? null : v.originHoldMinutes().doubleValue()));
            row.put("atOriginNow", count(list, v -> "AT_ORIGIN".equals(v.stage())));
            return row;
        }, 15);
    }

    private static Map<String, Object> performance(List<ShipmentView> list) {
        List<ShipmentView> arrived = list.stream().filter(v -> v.arrivedAt() != null && v.etaAt() != null).toList();
        long late = count(arrived, v -> "LATE".equals(v.verdict()));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shipments", list.size());
        row.put("active", count(list, ShipmentView::onTheRoad));
        row.put("delayed", count(list, v -> "DELAYED".equals(v.verdict())));
        row.put("arrived", arrived.size());
        row.put("onTimePct", arrived.isEmpty() ? null : round1(100.0 * (arrived.size() - late) / arrived.size()));
        row.put("avgTransitHours", average(list.stream().filter(v -> v.arrivedAt() != null).toList(),
                v -> v.transitMinutes() == null ? null : v.transitMinutes() / 60.0));
        row.put("avgDelayMinutesWhenLate", average(arrived.stream().filter(v -> "LATE".equals(v.verdict())).toList(),
                v -> v.delayMinutes() == null ? null : v.delayMinutes().doubleValue()));
        return row;
    }

    private Map<String, Long> overspeedByTransporter(Scope scope) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT tr.code, count(*) AS events FROM vehicle_event e
                JOIN shipment s ON s.vehicle_registration_no = e.vehicle_registration_no
                    AND e.started_at >= s.dispatched_at AND e.started_at <= COALESCE(s.arrived_at, s.closed_at, s.dispatched_at + interval '7 days')
                JOIN tenant tr ON tr.id = s.transporter_tenant_id
                WHERE e.event_type = 'OVERSPEED' AND s.dispatched_at > now() - interval '30 days' AND %s
                GROUP BY tr.code
                """.formatted(scope.where()), scope.parameters(), rs -> {
            counts.put(rs.getString("code"), rs.getLong("events"));
        });
        return counts;
    }

    // ---------------------------------------------------------------- fleet

    /**
     * The trucks of a transporter: its own and those carrying its shipments in the last month (hired trucks). The GPS
     * provider sees every truck, or one transporter's. A consigner has no fleet board.
     */
    public List<Map<String, Object>> fleet(Scope scope) {
        if (scope.role() == Role.CONSIGNER) {
            throw new PortalUser.ForbiddenException("A consigner follows shipments, not a fleet");
        }
        java.util.UUID transporter = scope.role() == Role.TRANSPORTER ? scope.tenantId() : scope.transporterFilter();
        List<Map<String, Object>> vehicles = jdbc.queryForList("""
                SELECT v.registration_no, t.code AS owner_code, t.legal_name AS owner_name, d.imei
                FROM vehicle v
                LEFT JOIN tenant t ON t.id = v.owner_tenant_id
                LEFT JOIN device_assignment a ON a.vehicle_id = v.id AND a.valid_from <= now() AND (a.valid_to IS NULL OR a.valid_to > now())
                LEFT JOIN device d ON d.id = a.device_id
                WHERE CAST(:t AS uuid) IS NULL OR v.owner_tenant_id = :t OR v.registration_no IN (
                    SELECT s.vehicle_registration_no FROM shipment s WHERE s.transporter_tenant_id = :t AND s.dispatched_at > now() - interval '30 days')
                ORDER BY v.registration_no
                """, new MapSqlParameterSource("t", transporter));
        Instant now = Instant.now();
        Map<String, ShipmentView> current = new LinkedHashMap<>();
        for (ShipmentView v : shipments(scope, null, 8, MAX_SHIPMENTS)) {
            if (v.onTheRoad() || "BOOKED".equals(v.stage())) {
                current.putIfAbsent(v.vehicleNo(), v);
            }
        }
        Map<String, Map<String, Object>> todayByImei = todayByImei(vehicles.stream().map(r -> (String) r.get("imei")).filter(Objects::nonNull).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> v : vehicles) {
            String imei = (String) v.get("imei");
            Map<Object, Object> position = Map.of();
            if (imei != null) {
                try {
                    position = live.findFields(imei);
                } catch (RuntimeException redisDown) {
                    position = Map.of();
                }
            }
            Instant last = position.get("recordedAt") == null ? null : Instant.parse(position.get("recordedAt").toString());
            String state = imei == null ? "NO_DEVICE" : last == null ? "NEVER_REPORTED"
                    : Duration.between(last, now).compareTo(OFFLINE_AFTER) > 0 ? "OFFLINE"
                    : "MOVING".equals(String.valueOf(position.get("movementStatus"))) ? "MOVING" : "STANDING";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("vehicleNo", v.get("registration_no"));
            row.put("ownerCode", v.get("owner_code"));
            row.put("ownerName", v.get("owner_name"));
            row.put("imei", imei);
            row.put("state", state);
            row.put("lastReadingAt", last);
            row.put("latitude", number(position.get("latitude")));
            row.put("longitude", number(position.get("longitude")));
            row.put("speedKmph", number(position.get("speedKmph")));
            ShipmentView shipment = current.get((String) v.get("registration_no"));
            row.put("shipment", shipment == null ? null : Map.of("id", shipment.id(), "tripNo", shipment.tripNo(),
                    "destination", nz(shipment.destination()), "stage", shipment.stage(), "verdict", shipment.verdict()));
            row.put("today", imei == null ? null : todayByImei.get(imei));
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> fleetKpis(List<Map<String, Object>> fleet) {
        Map<String, Object> k = new LinkedHashMap<>();
        long total = fleet.size();
        long onShipment = fleet.stream().filter(r -> r.get("shipment") != null).count();
        k.put("vehicles", total);
        k.put("withDevice", fleet.stream().filter(r -> r.get("imei") != null).count());
        for (String state : List.of("MOVING", "STANDING", "OFFLINE", "NEVER_REPORTED", "NO_DEVICE")) {
            k.put(state.toLowerCase(), fleet.stream().filter(r -> state.equals(r.get("state"))).count());
        }
        k.put("onShipment", onShipment);
        k.put("utilisationPct", total == 0 ? null : round1(100.0 * onShipment / total));
        return k;
    }

    /** Today's numbers of the fleet from the day summaries (Reports): distance, over-speed, harsh driving, idling. */
    private static Map<String, Object> today(List<Map<String, Object>> fleet) {
        double km = 0;
        long overspeed = 0;
        long harsh = 0;
        long idleSeconds = 0;
        long engineOnStops = 0;
        for (Map<String, Object> r : fleet) {
            if (r.get("today") instanceof Map<?, ?> t) {
                km += ((Number) t.get("km")).doubleValue();
                overspeed += ((Number) t.get("overspeedEvents")).longValue();
                harsh += ((Number) t.get("harshEvents")).longValue();
                idleSeconds += ((Number) t.get("idleSeconds")).longValue();
                engineOnStops += ((Number) t.get("engineOnStops")).longValue();
            }
        }
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("km", round1(km));
        k.put("overspeedEvents", overspeed);
        k.put("harshEvents", harsh);
        k.put("idleHours", round1(idleSeconds / 3600.0));
        k.put("engineOnStops", engineOnStops);
        return k;
    }

    private Map<String, Map<String, Object>> todayByImei(List<String> imeis) {
        Map<String, Map<String, Object>> byImei = new LinkedHashMap<>();
        if (imeis.isEmpty()) {
            return byImei;
        }
        jdbc.query("""
                SELECT imei, sum(distance_m) AS m, sum(overspeed_events) AS o, sum(harsh_events) AS h, sum(idle_s) AS i,
                       sum(engine_on_stops) AS s
                FROM vehicle_day_summary WHERE day = :day AND imei IN (:imeis) GROUP BY imei
                """, new MapSqlParameterSource("day", java.sql.Date.valueOf(LocalDate.now(zone))).addValue("imeis", imeis), rs -> {
            byImei.put(rs.getString("imei"), Map.of("km", round1(rs.getDouble("m") / 1000.0), "overspeedEvents", rs.getLong("o"),
                    "harshEvents", rs.getLong("h"), "idleSeconds", rs.getLong("i"), "engineOnStops", rs.getLong("s")));
        });
        return byImei;
    }

    // ---------------------------------------------------------------- alerts

    /**
     * Events a user may see: a transporter those of its own trucks, and of hired trucks while they carry its
     * shipments; a consigner only those while a truck carries its goods; the GPS provider every event.
     */
    public List<Map<String, Object>> alerts(Scope scope, int limit) {
        String where = switch (scope.role()) {
            case TRANSPORTER -> """
                    (e.vehicle_registration_no IN (SELECT v.registration_no FROM vehicle v WHERE v.owner_tenant_id = :scopeTenant)
                     OR EXISTS (SELECT 1 FROM shipment s WHERE s.transporter_tenant_id = :scopeTenant AND %s))""".formatted(WINDOW);
            case CONSIGNER -> "EXISTS (SELECT 1 FROM shipment s WHERE s.consigner_tenant_id = :scopeTenant AND %s)".formatted(WINDOW);
            case DEVELOPER -> scope.transporterFilter() == null && scope.consignerFilter() == null ? "true"
                    : "EXISTS (SELECT 1 FROM shipment s WHERE " + scope.where() + " AND " + WINDOW + ")";
        };
        return jdbc.query(EVENT_SELECT + """
                WHERE e.started_at > now() - interval '7 days' AND %s
                ORDER BY e.started_at DESC LIMIT :limit
                """.formatted(where), scope.parameters().addValue("limit", limit), (rs, n) -> event(rs));
    }

    private static final String EVENT_SELECT = """
            SELECT e.id, e.imei, e.vehicle_registration_no, e.tenant_code, e.event_type, e.severity, e.status, e.started_at,
                   e.ended_at, e.latitude, e.longitude, e.detected_by, e.details::text AS details
            FROM vehicle_event e
            """;

    /** An event in the shape of GET /api/events, so the console's event tables show it as they are. */
    private static Map<String, Object> event(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", rs.getLong("id"));
        e.put("imei", rs.getString("imei"));
        e.put("vehicleRegistrationNo", rs.getString("vehicle_registration_no"));
        e.put("tenantCode", rs.getString("tenant_code"));
        e.put("type", rs.getString("event_type"));
        e.put("severity", rs.getString("severity"));
        e.put("status", rs.getString("status"));
        e.put("startedAt", DeviceMappingService.instant(rs, "started_at"));
        e.put("endedAt", DeviceMappingService.instant(rs, "ended_at"));
        e.put("latitude", rs.getDouble("latitude"));
        e.put("longitude", rs.getDouble("longitude"));
        e.put("detectedBy", rs.getString("detected_by"));
        e.put("details", details(rs.getString("details")));
        return e;
    }

    private static final String WINDOW = """
            s.vehicle_registration_no = e.vehicle_registration_no AND e.started_at >= s.dispatched_at
            AND e.started_at <= COALESCE(s.arrived_at, s.closed_at, s.dispatched_at + interval '7 days')""";

    // ---------------------------------------------------------------- the GPS provider's own numbers

    private Map<String, Object> platform() {
        Map<String, Object> p = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM tenant WHERE kind = 'TRANSPORTER') AS transporters,
                       (SELECT count(*) FROM tenant WHERE kind = 'CONSIGNER') AS consigners,
                       (SELECT count(*) FROM app_user WHERE active) AS users,
                       (SELECT count(*) FROM device) AS devices,
                       (SELECT count(DISTINCT device_id) FROM device_assignment WHERE valid_from <= now() AND (valid_to IS NULL OR valid_to > now())) AS mapped_devices,
                       (SELECT count(*) FROM vehicle) AS vehicles,
                       (SELECT count(*) FROM shipment) AS shipments_all_time,
                       (SELECT count(*) FROM shipment_import WHERE uploaded_at > now() - interval '7 days') AS imports_7d,
                       (SELECT coalesce(sum(rows_failed), 0) FROM shipment_import WHERE uploaded_at > now() - interval '7 days') AS rows_refused_7d,
                       (SELECT count(*) FROM dead_letter WHERE status = 'PARKED') AS dead_letters_parked,
                       (SELECT count(*) FROM rejected_frame WHERE rejected_at > now() - interval '24 hours') AS rejected_24h,
                       (SELECT count(*) FROM place WHERE active) AS places
                """, new MapSqlParameterSource()));
        long reporting = 0;
        long silent = 0;
        long never = 0;
        Instant now = Instant.now();
        for (String imei : jdbc.queryForList("SELECT imei FROM device", new MapSqlParameterSource(), String.class)) {
            Instant last;
            try {
                last = live.findRecordedAt(imei).orElse(null);
            } catch (RuntimeException redisDown) {
                break;
            }
            if (last == null) {
                never++;
            } else if (Duration.between(last, now).toMinutes() <= 15) {
                reporting++;
            } else if (Duration.between(last, now).toMinutes() > 60) {
                silent++;
            }
        }
        p.put("devices_reporting_15m", reporting);
        p.put("devices_silent_1h", silent);
        p.put("devices_never_reported", never);
        return p;
    }

    // ---------------------------------------------------------------- helpers

    private static <K> List<Map<String, Object>> group(List<ShipmentView> list, Function<ShipmentView, K> key,
                                                         java.util.function.BiFunction<K, List<ShipmentView>, Map<String, Object>> row, int limit) {
        Map<K, List<ShipmentView>> groups = list.stream().filter(v -> key.apply(v) != null)
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream().sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(limit).map(e -> row.apply(e.getKey(), e.getValue())).toList();
    }

    private static long count(List<ShipmentView> list, Predicate<ShipmentView> test) {
        return list.stream().filter(test).count();
    }

    private static Double average(List<ShipmentView> list, Function<ShipmentView, Double> value) {
        double[] values = list.stream().map(value).filter(Objects::nonNull).mapToDouble(Double::doubleValue).toArray();
        if (values.length == 0) {
            return null;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return round1(sum / values.length);
    }

    private static Map<String, Object> details(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException notJson) {
            return Map.of();
        }
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String nz(String text) {
        return text == null ? "–" : text;
    }

    static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
