package com.srm.gps.processing.route;

import com.srm.gps.processing.rule.DistanceCalculator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route deviation, wrong direction and live ETA for one vehicle on one plan. Pure: state + reading + plan in, new state
 * + findings out; no clock, no database, no engine. The re-plan a suspicion needs is asked by the caller
 * ({@link Update#checkWanted()}) and its answer comes back through {@link #applyCheck}.
 *
 * <pre>
 * WAITING ──on the line, outside the origin──► ON_ROUTE
 * ON_ROUTE ──outside the corridor──► SUSPECT
 * SUSPECT ──back inside for 2 readings──► ON_ROUTE (drift, a parallel lane: nothing happened)
 * SUSPECT ──(3 readings AND 5 min AND 1 km driven off the line) or 2 km off──► re-plan check
 *     check: from here to the destination within 1.10 x the planned remaining time (or + 15 min) → ROUTE_ALTERNATE
 *            (the re-plan becomes the plan); otherwise → OFF_ROUTE, ROUTE_DEVIATION opens (HIGH > 30 min, CRITICAL > 90)
 * OFF_ROUTE ──3 readings back inside──► ON_ROUTE, ROUTE_DEVIATION closes (minutes and km off, extra km)
 * any ──inside the destination area──► ARRIVED (episodes closed, promised vs actual)
 * </pre>
 *
 * Wrong direction on the line: progress going back 1 km over 3 moving readings, or heading more than 135 deg against
 * the road for 3 readings with at least 300 m lost; closed after 3 readings going forward. Off the line: the re-plan's
 * remaining distance grew by 2 km since the previous check.
 *
 * ETA = reading time + planned seconds still ahead x pace + planned stays ahead + driver rests + the added time of
 * an open deviation. The truck's live pace is an exponentially weighted mean (60 min horizon) of moving seconds /
 * planned seconds, so traffic and its own speed show; stops are not in it (they are the rests and stays). The pace used
 * weighs that live pace against the plan's starting pace (the lane's history) by how long the truck has driven: an
 * hour of driving weighs as much as the prior, so the first slow kilometres out of a plant do not swing a 1,000 km ETA.
 * The shown ETA glides at most 2 min per minute; it jumps only when a deviation opens, changes or closes. ETA_CHANGED
 * when it moved 30 min, or 10 % of the time left if that is more, from the last one announced.
 */
public final class RouteTracker {

    public record Reading(Instant recordedAt, double latitude, double longitude, int speedKmph, boolean moving) {
    }

    /** @param checkWanted ask the routing engine {@link #checkRequest} now (asynchronously) */
    public record Update(RouteTrackState state, List<RouteFinding> findings, boolean checkWanted, boolean statusChanged) {
    }

    /**
     * What a re-plan check needs.
     *
     * @param plannedRemainingSeconds planned driving time from where the truck left the line to the end
     * @param offMovingSeconds        how long it has been driving off the line
     * @param stopsAhead              planned (manual) stops still ahead of where it left the line, to keep in the re-plan
     */
    public record CheckRequest(long planId, int planVersion, Instant askedAt, double latitude, double longitude, double heading,
                               double plannedRemainingSeconds, double offMovingSeconds, double pace, List<RoutePlan.Stop> stopsAhead) {
    }

    public enum Verdict { ALTERNATE, DEVIATION, UNAVAILABLE }

    /** The answer to a check: from where the truck is, the engine's way to the destination. */
    public record Check(long planId, int planVersion, Instant askedAt, Verdict verdict, double remainingMeters, double remainingSeconds,
                        long addedSeconds) {
    }

    private RouteTracker() {
    }

    public static Update update(RouteTrackState before, Reading r, RoutePlan plan, RouteSettings cfg) {
        if (before != null && before.planId == plan.id() && before.lastAt != null && !r.recordedAt().isAfter(before.lastAt)) {
            return new Update(before, List.of(), false, false);
        }
        RouteTrackState s = before == null || before.planId != plan.id() ? RouteTrackState.start(plan) : before.copy();
        boolean statusChanged = before == null || before.planId != plan.id();
        if (s.planVersion != plan.version()) {
            rebase(s, plan);
            statusChanged = true;
        }
        if (s.status == RouteTrackState.Status.ARRIVED) {
            s.lastAt = r.recordedAt();
            s.lastLatitude = r.latitude();
            s.lastLongitude = r.longitude();
            return new Update(s, List.of(), false, false);
        }
        List<RouteFinding> found = new ArrayList<>();
        long dt = 0;
        double dist = 0;
        if (s.lastAt != null) {
            dt = Math.max(0, Duration.between(s.lastAt, r.recordedAt()).getSeconds());
            dist = DistanceCalculator.distance(s.lastLatitude, s.lastLongitude, r.latitude(), r.longitude());
            if (dt > 0 && dist > 500 && dist / dt * 3.6 > cfg.maxJumpKmph) {
                // a GPS jump is ignored; three in a row from the same last position mean the truck really is elsewhere
                // (a device moved to another truck, a long gap in coverage): start again from there
                if (s.jumps < 2) {
                    s.jumps++;
                    return new Update(s, List.of(), false, false);
                }
                dist = 0;
            }
            s.jumps = 0;
        }
        double impliedKmph = dt > 0 ? dist / dt * 3.6 : 0;
        boolean moving = r.moving() || r.speedKmph() >= 5;
        double moveSeconds = moving ? Math.min(dt, 600) : 0;
        if (s.lastAt != null && dist >= 20) {
            s.heading = DistanceCalculator.bearing(s.lastLatitude, s.lastLongitude, r.latitude(), r.longitude());
        }
        if (moving) {
            s.drivenSinceRest += moveSeconds;
            if (s.departedAt != null) {
                s.movingSinceDeparture += moveSeconds;
            }
            s.stillSeconds = 0;
        } else {
            s.stillSeconds += Math.min(dt, 86_400);
            if (s.stillSeconds >= cfg.restSeconds) {
                s.drivenSinceRest = 0;
            }
        }
        RoutePlan.Projection p = plan.project(r.latitude(), r.longitude(),
                s.status == RouteTrackState.Status.WAITING ? Double.NaN : s.chainage);
        boolean inside = p.crossTrackMeters() <= p.corridorMeters();
        boolean atOrigin = plan.atOrigin(r.latitude(), r.longitude(), cfg.originClearMeters);
        s.crossTrack = p.crossTrackMeters();
        RouteTrackState.Status was = s.status;
        boolean checkWanted = false;

        if (plan.atDestination(r.latitude(), r.longitude()) && (s.status != RouteTrackState.Status.WAITING || !atOrigin)) {
            closeEpisodes(s, r, found, "ARRIVED");
            s.status = RouteTrackState.Status.ARRIVED;
            s.statusSince = r.recordedAt();
            s.arrivedAt = r.recordedAt();
            if (inside) {
                s.chainage = p.chainageMeters();
            }
            Map<String, Object> d = totals(s);
            d.put("promisedEta", text(s.etaPromised));
            d.put("lastEta", text(s.etaShown));
            d.put("departedAt", text(s.departedAt));
            if (s.etaPromised != null) {
                d.put("lateMinutes", Duration.between(s.etaPromised, r.recordedAt()).toMinutes());
            }
            if (s.departedAt != null) {
                d.put("tripMinutes", Duration.between(s.departedAt, r.recordedAt()).toMinutes());
            }
            d.put("pace", round2(s.pace));
            found.add(new RouteFinding(RouteFinding.Kind.ARRIVED, r.recordedAt(), r.recordedAt(), r.latitude(), r.longitude(), d));
            finish(s, r);
            return new Update(s, found, false, true);
        }

        boolean inStop = plan.stopContaining(r.latitude(), r.longitude()) != null || atOrigin;
        switch (s.status) {
            case WAITING -> {
                if (atOrigin) {
                    s.seenAtOrigin = true;
                } else if (inside) {
                    s.status = RouteTrackState.Status.ON_ROUTE;
                    s.statusSince = r.recordedAt();
                    s.departedAt = r.recordedAt();
                    s.chainage = p.chainageMeters();
                    s.peakChainage = p.chainageMeters();
                    s.lastOnChainage = p.chainageMeters();
                    s.paceAnchor = p.chainageMeters();
                    s.paceMovingSeconds = 0;
                } else if (s.seenAtOrigin && moving) {
                    // left the origin by another road than the plan's: departed, and off the line from the start
                    s.status = RouteTrackState.Status.SUSPECT;
                    s.statusSince = r.recordedAt();
                    s.departedAt = r.recordedAt();
                    s.resetOff();
                    s.offReadings = 1;
                    s.offSince = r.recordedAt();
                    s.offLatitude = r.latitude();
                    s.offLongitude = r.longitude();
                    s.maxOff = p.crossTrackMeters();
                    s.chainage = 0;
                    s.lastOnChainage = 0;
                }
            }
            case ON_ROUTE -> {
                if (inside || inStop) {
                    if (inside) {
                        onLine(s, p, plan, r, moving, moveSeconds, Math.max(r.speedKmph(), impliedKmph), cfg, found);
                    }
                } else {
                    s.status = RouteTrackState.Status.SUSPECT;
                    s.statusSince = r.recordedAt();
                    s.resetOff();
                    s.offReadings = 1;
                    s.offSince = r.recordedAt();
                    s.offLatitude = r.latitude();
                    s.offLongitude = r.longitude();
                    s.maxOff = p.crossTrackMeters();
                    s.lastOnChainage = s.chainage;
                    if (p.crossTrackMeters() > cfg.farOffMeters) {
                        checkWanted = ask(s, r, cfg);
                    }
                }
            }
            case SUSPECT -> {
                if (inside || inStop) {
                    s.backInside++;
                    if (s.backInside >= cfg.driftForgiveReadings) {
                        s.status = RouteTrackState.Status.ON_ROUTE;
                        s.statusSince = r.recordedAt();
                        s.resetOff();
                    }
                    if (inside) {
                        onLine(s, p, plan, r, moving, moveSeconds, Math.max(r.speedKmph(), impliedKmph), cfg, found);
                    }
                } else {
                    s.backInside = 0;
                    s.offReadings++;
                    s.offDriven += dist;
                    s.offMovingSeconds += moveSeconds;
                    s.maxOff = Math.max(s.maxOff, p.crossTrackMeters());
                    boolean enough = s.offReadings >= cfg.suspectReadings
                            && Duration.between(s.offSince, r.recordedAt()).getSeconds() >= cfg.suspectSeconds
                            && s.offDriven >= cfg.suspectOffMeters;
                    if (enough || p.crossTrackMeters() > cfg.farOffMeters) {
                        checkWanted = ask(s, r, cfg);
                    }
                }
            }
            case OFF_ROUTE -> {
                if (inside) {
                    s.backInside++;
                    onLine(s, p, plan, r, moving, moveSeconds, Math.max(r.speedKmph(), impliedKmph), cfg, found);
                    if (s.backInside >= cfg.rejoinReadings) {
                        found.add(closeDeviation(s, r, "REJOINED"));
                        s.status = RouteTrackState.Status.ON_ROUTE;
                        s.statusSince = r.recordedAt();
                        s.resetOff();
                        s.paceAnchor = s.chainage;
                        s.paceMovingSeconds = 0;
                    }
                } else {
                    s.backInside = 0;
                    s.offDriven += dist;
                    s.offMovingSeconds += moveSeconds;
                    s.maxOff = Math.max(s.maxOff, p.crossTrackMeters());
                    if (s.lastCheckAt == null || Duration.between(s.lastCheckAt, r.recordedAt()).getSeconds() >= cfg.recheckSeconds) {
                        checkWanted = ask(s, r, cfg);
                    }
                }
            }
            default -> {
            }
        }
        eta(s, plan, r, dt, cfg, found);
        finish(s, r);
        return new Update(s, found, checkWanted, statusChanged || s.status != was || !found.isEmpty() || checkWanted);
    }

    /** The answer to a check asked by an earlier update; a stale answer (another plan, an earlier episode) changes nothing. */
    public static Update applyCheck(RouteTrackState before, Check c, RouteSettings cfg) {
        if (before == null || c.planId() != before.planId || c.planVersion() != before.planVersion) {
            return new Update(before, List.of(), false, false);
        }
        RouteTrackState s = before.copy();
        s.checkPending = false;
        if (s.offSince == null || c.askedAt().isBefore(s.offSince)) {
            return new Update(s, List.of(), false, false);
        }
        s.lastCheckAt = c.askedAt();
        List<RouteFinding> found = new ArrayList<>();
        RouteTrackState.Status was = s.status;
        if (s.status == RouteTrackState.Status.SUSPECT) {
            if (c.verdict() == Verdict.ALTERNATE) {
                found.add(alternate(s, c));
                s.status = RouteTrackState.Status.ON_ROUTE;
                s.statusSince = c.askedAt();
                s.resetOff();
            } else {
                s.status = RouteTrackState.Status.OFF_ROUTE;
                s.statusSince = c.askedAt();
                s.deviationOpen = true;
                s.deviationAt = s.offSince;
                s.addedSeconds = c.addedSeconds();
                s.verdict = c.verdict() == Verdict.UNAVAILABLE ? "UNVERIFIED" : "SLOWER_ROUTE";
                s.replanRemainingMeters = c.remainingMeters();
                s.deviations++;
                Map<String, Object> d = deviationDetails(s);
                found.add(new RouteFinding(RouteFinding.Kind.DEVIATION_OPENED, s.offSince, c.askedAt(), s.offLatitude, s.offLongitude, d));
            }
        } else if (s.status == RouteTrackState.Status.OFF_ROUTE) {
            if (c.verdict() == Verdict.ALTERNATE) {
                found.add(closeDeviationAt(s, c.askedAt(), s.lastLatitude, s.lastLongitude, "NOW_ON_A_GOOD_ROAD"));
                found.add(alternate(s, c));
                s.status = RouteTrackState.Status.ON_ROUTE;
                s.statusSince = c.askedAt();
                s.resetOff();
            } else if (c.verdict() == Verdict.DEVIATION) {
                double before2 = s.replanRemainingMeters;
                if (c.addedSeconds() != s.addedSeconds) {
                    s.addedSeconds = c.addedSeconds();
                    found.add(new RouteFinding(RouteFinding.Kind.DEVIATION_UPDATED, s.deviationAt, c.askedAt(), s.offLatitude,
                            s.offLongitude, deviationDetails(s)));
                }
                if (!Double.isNaN(before2) && c.remainingMeters() > before2 + cfg.offPlanWrongWayMeters && !s.wrongOpen) {
                    s.wrongOpen = true;
                    s.wrongOffPlan = true;
                    s.wrongAt = c.askedAt();
                    s.wrongWays++;
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("onPlan", false);
                    d.put("awayKm", round2((c.remainingMeters() - before2) / 1000));
                    found.add(new RouteFinding(RouteFinding.Kind.WRONG_WAY_OPENED, c.askedAt(), c.askedAt(), s.lastLatitude, s.lastLongitude, d));
                } else if (s.wrongOpen && s.wrongOffPlan && !Double.isNaN(before2) && c.remainingMeters() < before2) {
                    found.add(closeWrongWay(s, c.askedAt(), s.lastLatitude, s.lastLongitude, "HEADING_BACK"));
                }
                s.replanRemainingMeters = c.remainingMeters();
            }
        }
        return new Update(s, found, false, s.status != was || !found.isEmpty());
    }

    /** What to ask the engine for the current suspicion. */
    public static CheckRequest checkRequest(RouteTrackState s, RoutePlan plan) {
        List<RoutePlan.Stop> ahead = new ArrayList<>();
        for (RoutePlan.Stop stop : plan.stops()) {
            if (stop.manual() && stop.chainageMeters() > s.lastOnChainage) {
                ahead.add(stop);
            }
        }
        return new CheckRequest(plan.id(), plan.version(), s.lastAt, s.lastLatitude, s.lastLongitude, s.heading,
                plan.totalSeconds() - plan.secondsAt(s.lastOnChainage), s.offMovingSeconds, s.pace, ahead);
    }

    /**
     * Classifies the engine's answer: the time it will have taken on this way (driven off the line so far at the
     * truck's pace, plus the re-plan) against what the plan had left from where it left the line.
     */
    public static Check judge(CheckRequest q, double remainingSeconds, double remainingMeters, RouteSettings cfg) {
        double pace = Math.max(cfg.paceMin, q.pace());
        double thisWay = q.offMovingSeconds() / pace + remainingSeconds;
        double added = thisWay - q.plannedRemainingSeconds();
        boolean alternate = thisWay <= q.plannedRemainingSeconds() * cfg.alternateTimeFactor || added <= cfg.alternateExtraSeconds;
        return new Check(q.planId(), q.planVersion(), q.askedAt(), alternate ? Verdict.ALTERNATE : Verdict.DEVIATION, remainingMeters,
                remainingSeconds, Math.max(0, Math.round(added * pace)));
    }

    /** The engine could not answer: the deviation is opened unverified (the truck is far and long off the line). */
    public static Check unavailable(CheckRequest q) {
        return new Check(q.planId(), q.planVersion(), q.askedAt(), Verdict.UNAVAILABLE, Double.NaN, Double.NaN, 0);
    }

    /** Severity name of a deviation by its added time. */
    public static String severity(long addedSeconds, String verdict, RouteSettings cfg) {
        if ("UNVERIFIED".equals(verdict)) {
            return "MEDIUM";
        }
        long minutes = addedSeconds / 60;
        return minutes > cfg.criticalAddedMinutes ? "CRITICAL" : minutes > cfg.highAddedMinutes ? "HIGH" : "MEDIUM";
    }

    private static boolean ask(RouteTrackState s, Reading r, RouteSettings cfg) {
        if (s.checkPending && s.checkAskedAt != null
                && Duration.between(s.checkAskedAt, r.recordedAt()).getSeconds() < cfg.checkTimeoutSeconds) {
            return false;
        }
        s.checkPending = true;
        s.checkAskedAt = r.recordedAt();
        return true;
    }

    private static void onLine(RouteTrackState s, RoutePlan.Projection p, RoutePlan plan, Reading r, boolean moving, double moveSeconds,
                               double kmph, RouteSettings cfg, List<RouteFinding> found) {
        double previous = s.chainage;
        s.chainage = p.chainageMeters();
        if (s.status == RouteTrackState.Status.ON_ROUTE) {
            s.paceMovingSeconds += moveSeconds;
            double progress = s.chainage - s.paceAnchor;
            if (progress < -200) {
                s.paceAnchor = s.chainage;
                s.paceMovingSeconds = 0;
            } else if (s.paceMovingSeconds >= cfg.paceSampleSeconds && progress >= cfg.paceSampleMeters) {
                double planned = plan.secondsAt(s.chainage) - plan.secondsAt(s.paceAnchor);
                if (planned > 0) {
                    double sample = Math.max(cfg.paceMin, Math.min(cfg.paceMax, s.paceMovingSeconds / planned));
                    double alpha = 1 - Math.exp(-s.paceMovingSeconds / cfg.paceHorizonSeconds);
                    s.pace += alpha * (sample - s.pace);
                }
                s.paceAnchor = s.chainage;
                s.paceMovingSeconds = 0;
            }
        }
        if (!moving || kmph < cfg.wrongWayMinKmph) {
            return;
        }
        if (!s.wrongOpen) {
            s.peakChainage = Math.max(s.peakChainage, s.chainage);
            boolean back = s.chainage < previous - 5;
            boolean opposite = !Double.isNaN(s.heading) && angle(s.heading, p.bearing()) > cfg.wrongWayHeadingDegrees;
            s.wrongReadings = back || opposite ? s.wrongReadings + 1 : 0;
            double lost = s.peakChainage - s.chainage;
            if (s.wrongReadings >= cfg.wrongWayReadings
                    && (lost >= cfg.wrongWayBackMeters || (opposite && lost >= cfg.wrongWayHeadingBackMeters))) {
                s.wrongOpen = true;
                s.wrongOffPlan = false;
                s.wrongAt = r.recordedAt();
                s.wrongFrom = s.peakChainage;
                s.wrongLowest = s.chainage;
                s.risingReadings = 0;
                s.wrongWays++;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("onPlan", true);
                d.put("backKm", round2(lost / 1000));
                d.put("atKm", round2((plan.offsetMeters() + s.chainage) / 1000));
                found.add(new RouteFinding(RouteFinding.Kind.WRONG_WAY_OPENED, r.recordedAt(), r.recordedAt(), r.latitude(), r.longitude(), d));
            }
        } else {
            s.wrongLowest = Math.min(s.wrongLowest, s.chainage);
            s.risingReadings = s.chainage > previous + 5 ? s.risingReadings + 1 : 0;
            if (s.risingReadings >= cfg.wrongWayReadings) {
                found.add(closeWrongWay(s, r.recordedAt(), r.latitude(), r.longitude(), "FORWARD_AGAIN"));
                s.peakChainage = s.chainage;
            }
        }
    }

    private static void eta(RouteTrackState s, RoutePlan plan, Reading r, long dt, RouteSettings cfg, List<RouteFinding> found) {
        double ahead = plan.totalSeconds() - plan.secondsAt(s.chainage);
        double driving = ahead * effectivePace(s, plan.initialPace(), cfg);
        long rests = (long) Math.floor((s.drivenSinceRest + driving) / cfg.restAfterDrivingSeconds) * cfg.restSeconds;
        double stays = plan.staySecondsAhead(s.chainage);
        long extra = s.deviationOpen ? s.addedSeconds : 0;
        Instant raw = r.recordedAt().plusSeconds(Math.round(driving + rests + stays + extra));
        if (s.status == RouteTrackState.Status.WAITING) {
            s.etaShown = raw;
            s.etaExtraSeconds = extra;
            return;
        }
        if (s.etaPromised == null) {
            s.etaShown = raw;
            s.etaPromised = raw;
            s.etaAnnounced = raw;
            s.etaExtraSeconds = extra;
            return;
        }
        long diff = Duration.between(s.etaShown, raw).getSeconds();
        if (extra != s.etaExtraSeconds && Math.abs(diff) > cfg.etaJumpSeconds) {
            // a deviation opened, changed or closed: say so at once
            s.etaShown = raw;
        } else {
            long max = Math.round(cfg.etaMaxShiftRate * dt);
            s.etaShown = s.etaShown.plusSeconds(Math.max(-max, Math.min(max, diff)));
        }
        s.etaExtraSeconds = extra;
        long moved = Duration.between(s.etaAnnounced, s.etaShown).getSeconds();
        long left = Math.max(0, Duration.between(r.recordedAt(), s.etaShown).getSeconds());
        if (Math.abs(moved) >= Math.max(cfg.etaAnnounceSeconds, Math.round(cfg.etaAnnounceShare * left))) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("from", text(s.etaAnnounced));
            d.put("to", text(s.etaShown));
            d.put("promised", text(s.etaPromised));
            d.put("changeMinutes", moved / 60);
            d.put("lateMinutes", Duration.between(s.etaPromised, s.etaShown).toMinutes());
            d.put("reason", s.deviationOpen ? "DEVIATION" : s.stillSeconds >= cfg.restSeconds ? "STOPPED" : "PACE");
            d.put("pace", round2(effectivePace(s, plan.initialPace(), cfg)));
            found.add(new RouteFinding(RouteFinding.Kind.ETA_CHANGED, r.recordedAt(), r.recordedAt(), r.latitude(), r.longitude(), d));
            s.etaAnnounced = s.etaShown;
        }
    }

    /**
     * The pace the ETA uses: the truck's live pace weighed against the plan's starting pace by moving time since
     * departure (pace-prior-minutes of driving weigh as much as the prior).
     */
    public static double effectivePace(RouteTrackState s, double prior, RouteSettings cfg) {
        double driven = s.movingSinceDeparture;
        double w = cfg.pacePriorSeconds;
        double pace = (prior * w + s.pace * driven) / (w + driven);
        return Math.max(cfg.paceMin, Math.min(cfg.paceMax, pace));
    }

    /** A new version of the plan (an alternate became the plan): progress starts again on the new line. */
    private static void rebase(RouteTrackState s, RoutePlan plan) {
        s.planVersion = plan.version();
        s.chainage = 0;
        s.peakChainage = 0;
        s.lastOnChainage = 0;
        s.paceAnchor = 0;
        s.paceMovingSeconds = 0;
        s.wrongReadings = 0;
        s.risingReadings = 0;
        if (s.status == RouteTrackState.Status.SUSPECT || s.status == RouteTrackState.Status.OFF_ROUTE) {
            s.status = RouteTrackState.Status.ON_ROUTE;
        }
        s.resetOff();
    }

    private static void closeEpisodes(RouteTrackState s, Reading r, List<RouteFinding> found, String reason) {
        if (s.deviationOpen) {
            found.add(closeDeviation(s, r, reason));
        }
        if (s.wrongOpen) {
            found.add(closeWrongWay(s, r.recordedAt(), r.latitude(), r.longitude(), reason));
        }
        s.resetOff();
    }

    private static RouteFinding closeDeviation(RouteTrackState s, Reading r, String reason) {
        return closeDeviationAt(s, r.recordedAt(), r.latitude(), r.longitude(), reason);
    }

    private static RouteFinding closeDeviationAt(RouteTrackState s, Instant at, double lat, double lon, String reason) {
        double extra = Math.max(0, s.offDriven - Math.max(0, s.chainage - s.lastOnChainage));
        long seconds = s.deviationAt == null ? 0 : Duration.between(s.deviationAt, at).getSeconds();
        s.deviationMeters += extra;
        s.deviationSeconds += seconds;
        Map<String, Object> d = deviationDetails(s);
        d.put("minutesOff", seconds / 60);
        d.put("kmOff", round2(s.offDriven / 1000));
        d.put("extraKm", round2(extra / 1000));
        d.put("closedBy", reason);
        s.deviationOpen = false;
        if (s.wrongOpen && s.wrongOffPlan) {
            s.wrongOpen = false;
        }
        return new RouteFinding(RouteFinding.Kind.DEVIATION_CLOSED, s.deviationAt == null ? at : s.deviationAt, at, lat, lon, d);
    }

    private static RouteFinding closeWrongWay(RouteTrackState s, Instant at, double lat, double lon, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("onPlan", !s.wrongOffPlan);
        if (!s.wrongOffPlan) {
            d.put("backKm", round2((s.wrongFrom - s.wrongLowest) / 1000));
        }
        d.put("minutes", s.wrongAt == null ? 0 : Duration.between(s.wrongAt, at).toMinutes());
        d.put("closedBy", reason);
        Instant started = s.wrongAt == null ? at : s.wrongAt;
        s.wrongOpen = false;
        s.wrongOffPlan = false;
        s.wrongReadings = 0;
        s.risingReadings = 0;
        return new RouteFinding(RouteFinding.Kind.WRONG_WAY_CLOSED, started, at, lat, lon, d);
    }

    private static RouteFinding alternate(RouteTrackState s, Check c) {
        s.alternates++;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("addedMinutes", c.addedSeconds() / 60);
        d.put("remainingKm", round2(c.remainingMeters() / 1000));
        d.put("offKm", round2(s.offDriven / 1000));
        return new RouteFinding(RouteFinding.Kind.ALTERNATE_TAKEN, s.offSince == null ? c.askedAt() : s.offSince, c.askedAt(),
                s.lastLatitude, s.lastLongitude, d);
    }

    private static Map<String, Object> deviationDetails(RouteTrackState s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("addedMinutes", s.addedSeconds / 60);
        d.put("verdict", s.verdict);
        d.put("maxOffMeters", Math.round(s.maxOff));
        d.put("offKm", round2(s.offDriven / 1000));
        if (!Double.isNaN(s.replanRemainingMeters)) {
            d.put("remainingKm", round2(s.replanRemainingMeters / 1000));
        }
        return d;
    }

    private static Map<String, Object> totals(RouteTrackState s) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("deviations", s.deviations);
        d.put("deviationKm", round2(s.deviationMeters / 1000));
        d.put("deviationMinutes", s.deviationSeconds / 60);
        d.put("wrongWays", s.wrongWays);
        d.put("alternates", s.alternates);
        return d;
    }

    private static void finish(RouteTrackState s, Reading r) {
        s.lastAt = r.recordedAt();
        s.lastLatitude = r.latitude();
        s.lastLongitude = r.longitude();
    }

    /** Smallest angle between two bearings, 0..180. */
    static double angle(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String text(Instant t) {
        return t == null ? null : t.toString();
    }
}
