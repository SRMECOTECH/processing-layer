package com.srm.gps.processing.route;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planned stops of a trip, one vehicle at a time. Pure: state + reading + plan in, new state + findings out.
 *
 * Per stop:
 * <pre>
 * PENDING ──first reading inside──► INSIDE ──inside 2 min──► ARRIVED (stamped at the first reading inside)
 *                                     └──left sooner──► PASSED (drove through)
 * ARRIVED ──first reading outside──► DEPARTED (stamped at the last reading inside, with the stay)
 * PENDING ──the truck is 2 km past it along the plan, or at the destination──► MISSED
 * </pre>
 * An arrival while an earlier stop is still pending is marked out of order (the earlier one can still be visited
 * later; it is missed only when the truck is past it).
 */
public final class WaypointTracker {

    public enum Status { PENDING, INSIDE, ARRIVED, DEPARTED, PASSED, MISSED }

    public record StopState(Status status, Instant firstInsideAt, Instant lastInsideAt, boolean outOfOrder) {

        static final StopState NEW = new StopState(Status.PENDING, null, null, false);

        public boolean done() {
            return status == Status.DEPARTED || status == Status.PASSED || status == Status.MISSED;
        }
    }

    /** One vehicle's waypoints on one plan; kept as a Redis hash (gps:state:waypoints:{imei}). */
    public record State(long planId, int planVersion, Instant lastAt, double chainage, Map<Integer, StopState> stops) {

        public static State start(RoutePlan plan) {
            Map<Integer, StopState> stops = new LinkedHashMap<>();
            plan.stops().forEach(stop -> stops.put(stop.seq(), StopState.NEW));
            return new State(plan.id(), plan.version(), null, Double.NaN, stops);
        }

        public Map<String, String> toFields() {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("v", "1");
            f.put("plan", planId + "," + planVersion);
            f.put("at", lastAt == null ? "-" : Long.toString(lastAt.toEpochMilli()));
            f.put("chainage", Double.toString(chainage));
            stops.forEach((seq, s) -> f.put("s" + seq, s.status() + "," + time(s.firstInsideAt()) + "," + time(s.lastInsideAt()) + ","
                    + s.outOfOrder()));
            return f;
        }

        /** The state for this plan, or null when the hash holds another plan's (or nothing readable). */
        public static State fromFields(Map<String, String> f, RoutePlan plan) {
            if (f == null || !"1".equals(f.get("v")) || f.get("plan") == null) {
                return null;
            }
            try {
                String[] p = f.get("plan").split(",");
                if (Long.parseLong(p[0]) != plan.id()) {
                    return null;
                }
                Map<Integer, StopState> stops = new LinkedHashMap<>();
                for (RoutePlan.Stop stop : plan.stops()) {
                    String v = f.get("s" + stop.seq());
                    if (v == null) {
                        stops.put(stop.seq(), StopState.NEW);
                        continue;
                    }
                    String[] parts = v.split(",");
                    stops.put(stop.seq(), new StopState(Status.valueOf(parts[0]), instant(parts[1]), instant(parts[2]),
                            Boolean.parseBoolean(parts[3])));
                }
                String at = f.get("at");
                return new State(plan.id(), Integer.parseInt(p[1]), at == null || "-".equals(at) ? null : Instant.ofEpochMilli(Long.parseLong(at)),
                        Double.parseDouble(f.getOrDefault("chainage", "NaN")), stops);
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
    }

    public record Update(State state, List<RouteFinding> findings, boolean changed) {
    }

    private WaypointTracker() {
    }

    public static Update update(State before, RouteTracker.Reading r, RoutePlan plan, RouteSettings cfg) {
        if (before != null && before.planId() == plan.id() && before.lastAt() != null && !r.recordedAt().isAfter(before.lastAt())) {
            return new Update(before, List.of(), false);
        }
        State s = before == null || before.planId() != plan.id() ? State.start(plan) : before;
        double lastChainage = s.planVersion() == plan.version() ? s.chainage() : Double.NaN;
        RoutePlan.Projection p = plan.project(r.latitude(), r.longitude(), lastChainage);
        double chainage = p.crossTrackMeters() <= 2 * p.corridorMeters() ? p.chainageMeters() : lastChainage;
        boolean atDestination = plan.atDestination(r.latitude(), r.longitude());

        Map<Integer, StopState> stops = new LinkedHashMap<>(s.stops());
        List<RouteFinding> found = new ArrayList<>();
        boolean changed = before == null || before.planId() != plan.id() || s.planVersion() != plan.version();
        for (RoutePlan.Stop stop : plan.stops()) {
            StopState st = stops.getOrDefault(stop.seq(), StopState.NEW);
            boolean inside = stop.area().contains(r.latitude(), r.longitude());
            StopState next = st;
            switch (st.status()) {
                case PENDING -> {
                    if (inside) {
                        next = new StopState(Status.INSIDE, r.recordedAt(), r.recordedAt(), false);
                    } else if (atDestination || (stop.chainageMeters() >= 0 && !Double.isNaN(chainage)
                            && chainage > stop.chainageMeters() + cfg.waypointMissMeters)) {
                        next = new StopState(Status.MISSED, null, null, false);
                        found.add(finding(RouteFinding.Kind.WAYPOINT_MISSED, stop, r.recordedAt(), r.recordedAt(), r,
                                Map.of("reason", atDestination ? "AT_DESTINATION" : "PASSED_BY")));
                    }
                }
                case INSIDE -> {
                    if (inside) {
                        if (Duration.between(st.firstInsideAt(), r.recordedAt()).getSeconds() >= cfg.waypointMinStaySeconds) {
                            boolean outOfOrder = earlierPending(plan, stops, stop.seq());
                            next = new StopState(Status.ARRIVED, st.firstInsideAt(), r.recordedAt(), outOfOrder);
                            found.add(finding(RouteFinding.Kind.WAYPOINT_ARRIVED, stop, st.firstInsideAt(), st.firstInsideAt(), r,
                                    Map.of("outOfOrder", outOfOrder)));
                        } else {
                            next = new StopState(Status.INSIDE, st.firstInsideAt(), r.recordedAt(), false);
                        }
                    } else {
                        next = new StopState(Status.PASSED, st.firstInsideAt(), st.lastInsideAt(), false);
                        found.add(finding(RouteFinding.Kind.WAYPOINT_PASSED, stop, st.firstInsideAt(), st.lastInsideAt(), r,
                                Map.of("minutesInside", Duration.between(st.firstInsideAt(), st.lastInsideAt()).toMinutes())));
                    }
                }
                case ARRIVED -> {
                    if (inside) {
                        next = new StopState(Status.ARRIVED, st.firstInsideAt(), r.recordedAt(), st.outOfOrder());
                    } else {
                        next = new StopState(Status.DEPARTED, st.firstInsideAt(), st.lastInsideAt(), st.outOfOrder());
                        found.add(finding(RouteFinding.Kind.WAYPOINT_DEPARTED, stop, st.firstInsideAt(), st.lastInsideAt(), r,
                                Map.of("stayMinutes", Duration.between(st.firstInsideAt(), st.lastInsideAt()).toMinutes(),
                                        "plannedStayMinutes", stop.stayMinutes(), "outOfOrder", st.outOfOrder())));
                    }
                }
                default -> {
                }
            }
            if (next.status() != st.status()) {
                changed = true;
            }
            stops.put(stop.seq(), next);
        }
        State after = new State(plan.id(), plan.version(), r.recordedAt(), chainage, stops);
        return new Update(after, found, changed);
    }

    private static boolean earlierPending(RoutePlan plan, Map<Integer, StopState> stops, int seq) {
        for (RoutePlan.Stop other : plan.stops()) {
            if (other.seq() < seq && other.manual()) {
                StopState st = stops.get(other.seq());
                if (st == null || st.status() == Status.PENDING) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RouteFinding finding(RouteFinding.Kind kind, RoutePlan.Stop stop, Instant startedAt, Instant at, RouteTracker.Reading r,
                                        Map<String, Object> extra) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("seq", stop.seq());
        d.put("stop", stop.name());
        d.put("stopKind", stop.kind());
        if (stop.placeId() != null) {
            d.put("placeId", stop.placeId());
        }
        d.putAll(extra);
        return new RouteFinding(kind, startedAt, at, r.latitude(), r.longitude(), d);
    }

    private static String time(Instant t) {
        return t == null ? "-" : Long.toString(t.toEpochMilli());
    }

    private static Instant instant(String s) {
        return s == null || "-".equals(s) ? null : Instant.ofEpochMilli(Long.parseLong(s));
    }
}
