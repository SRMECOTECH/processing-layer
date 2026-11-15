package com.srm.gps.processing.route;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the route tracker remembers about one vehicle on one plan, kept as a Redis hash
 * (gps:state:route-deviation:{imei}) by {@link #toFields()} / {@link #fromFields(Map)}.
 *
 * A plain mutable holder: {@link RouteTracker} copies it, changes the copy and hands it back, so a caller never sees a
 * half-applied reading. Distances in metres along the plan (chainage), times as instants or seconds.
 */
public final class RouteTrackState {

    public enum Status {
        /** Planned, the truck has not got onto the route outside the origin yet. */
        WAITING,
        ON_ROUTE,
        /** Outside the corridor, not yet long / far enough to believe (or the re-plan check is running). */
        SUSPECT,
        /** A confirmed deviation (ROUTE_DEVIATION open) until it is back on the line. */
        OFF_ROUTE,
        ARRIVED
    }

    public long planId;
    public int planVersion;
    public Instant lastAt;
    public double lastLatitude = Double.NaN;
    public double lastLongitude = Double.NaN;
    public double heading = Double.NaN;
    public Status status = Status.WAITING;
    public Instant statusSince;
    /** Seen inside the origin while WAITING: leaving it off the line then counts as departing off route. */
    public boolean seenAtOrigin;
    /** GPS jumps ignored in a row since the last accepted reading. */
    public int jumps;

    /** Progress along the current plan version; frozen at the last point on the line while off it. */
    public double chainage;
    public double crossTrack;
    /** Furthest progress lately: going back from it is how wrong direction shows. */
    public double peakChainage;

    // the current off-route episode (SUSPECT, then OFF_ROUTE)
    public int offReadings;
    public int backInside;
    public Instant offSince;
    public double offLatitude = Double.NaN;
    public double offLongitude = Double.NaN;
    public double offDriven;
    public double offMovingSeconds;
    public double lastOnChainage;
    public double maxOff;
    public boolean checkPending;
    public Instant checkAskedAt;
    public Instant lastCheckAt;
    public boolean deviationOpen;
    public Instant deviationAt;
    public long addedSeconds;
    public String verdict;
    public double replanRemainingMeters = Double.NaN;

    // wrong direction
    public boolean wrongOpen;
    public boolean wrongOffPlan;
    public Instant wrongAt;
    public int wrongReadings;
    public int risingReadings;
    public double wrongFrom;
    public double wrongLowest;

    // live pace and driver hours
    public double pace = 1.0;
    public double paceAnchor;
    public double paceMovingSeconds;
    public double drivenSinceRest;
    public double stillSeconds;
    /** Moving time since departure: how much the truck's own pace is trusted against the plan's starting pace. */
    public double movingSinceDeparture;
    /** The deviation time in the last ETA: when it changes, the ETA may jump instead of gliding. */
    public long etaExtraSeconds;

    // ETA
    public Instant departedAt;
    public Instant etaShown;
    public Instant etaAnnounced;
    public Instant etaPromised;
    public Instant arrivedAt;

    // trip totals
    public int deviations;
    public double deviationMeters;
    public long deviationSeconds;
    public int wrongWays;
    public int alternates;

    /** A fresh state for a new plan. */
    public static RouteTrackState start(RoutePlan plan) {
        RouteTrackState s = new RouteTrackState();
        s.planId = plan.id();
        s.planVersion = plan.version();
        s.pace = plan.initialPace();
        return s;
    }

    public RouteTrackState copy() {
        RouteTrackState c = new RouteTrackState();
        c.planId = planId;
        c.planVersion = planVersion;
        c.lastAt = lastAt;
        c.lastLatitude = lastLatitude;
        c.lastLongitude = lastLongitude;
        c.heading = heading;
        c.status = status;
        c.statusSince = statusSince;
        c.seenAtOrigin = seenAtOrigin;
        c.jumps = jumps;
        c.chainage = chainage;
        c.crossTrack = crossTrack;
        c.peakChainage = peakChainage;
        c.offReadings = offReadings;
        c.backInside = backInside;
        c.offSince = offSince;
        c.offLatitude = offLatitude;
        c.offLongitude = offLongitude;
        c.offDriven = offDriven;
        c.offMovingSeconds = offMovingSeconds;
        c.lastOnChainage = lastOnChainage;
        c.maxOff = maxOff;
        c.checkPending = checkPending;
        c.checkAskedAt = checkAskedAt;
        c.lastCheckAt = lastCheckAt;
        c.deviationOpen = deviationOpen;
        c.deviationAt = deviationAt;
        c.addedSeconds = addedSeconds;
        c.verdict = verdict;
        c.replanRemainingMeters = replanRemainingMeters;
        c.wrongOpen = wrongOpen;
        c.wrongOffPlan = wrongOffPlan;
        c.wrongAt = wrongAt;
        c.wrongReadings = wrongReadings;
        c.risingReadings = risingReadings;
        c.wrongFrom = wrongFrom;
        c.wrongLowest = wrongLowest;
        c.pace = pace;
        c.paceAnchor = paceAnchor;
        c.paceMovingSeconds = paceMovingSeconds;
        c.drivenSinceRest = drivenSinceRest;
        c.stillSeconds = stillSeconds;
        c.movingSinceDeparture = movingSinceDeparture;
        c.etaExtraSeconds = etaExtraSeconds;
        c.departedAt = departedAt;
        c.etaShown = etaShown;
        c.etaAnnounced = etaAnnounced;
        c.etaPromised = etaPromised;
        c.arrivedAt = arrivedAt;
        c.deviations = deviations;
        c.deviationMeters = deviationMeters;
        c.deviationSeconds = deviationSeconds;
        c.wrongWays = wrongWays;
        c.alternates = alternates;
        return c;
    }

    /** Forgets the off-route episode (back on the line, or a new plan version). */
    void resetOff() {
        offReadings = 0;
        backInside = 0;
        offSince = null;
        offLatitude = Double.NaN;
        offLongitude = Double.NaN;
        offDriven = 0;
        offMovingSeconds = 0;
        maxOff = 0;
        checkPending = false;
        deviationOpen = false;
        deviationAt = null;
        addedSeconds = 0;
        verdict = null;
        replanRemainingMeters = Double.NaN;
    }

    public Map<String, String> toFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("v", "1");
        f.put("plan", planId + "," + planVersion);
        f.put("status", status.name());
        f.put("statusSince", time(statusSince));
        f.put("seenAtOrigin", Boolean.toString(seenAtOrigin));
        f.put("jumps", Integer.toString(jumps));
        f.put("last", time(lastAt) + "," + lastLatitude + "," + lastLongitude + "," + heading);
        f.put("chainage", chainage + "," + crossTrack + "," + peakChainage);
        f.put("off", offReadings + "," + backInside + "," + time(offSince) + "," + offLatitude + "," + offLongitude + "," + offDriven
                + "," + offMovingSeconds + "," + lastOnChainage + "," + maxOff);
        f.put("check", checkPending + "," + time(checkAskedAt) + "," + time(lastCheckAt));
        f.put("deviation", deviationOpen + "," + time(deviationAt) + "," + addedSeconds + "," + (verdict == null ? "-" : verdict) + ","
                + replanRemainingMeters);
        f.put("wrong", wrongOpen + "," + wrongOffPlan + "," + time(wrongAt) + "," + wrongReadings + "," + risingReadings + "," + wrongFrom
                + "," + wrongLowest);
        f.put("pace", pace + "," + paceAnchor + "," + paceMovingSeconds + "," + drivenSinceRest + "," + stillSeconds + ","
                + movingSinceDeparture + "," + etaExtraSeconds);
        f.put("eta", time(departedAt) + "," + time(etaShown) + "," + time(etaAnnounced) + "," + time(etaPromised) + "," + time(arrivedAt));
        f.put("totals", deviations + "," + deviationMeters + "," + deviationSeconds + "," + wrongWays + "," + alternates);
        return f;
    }

    /** The state written by {@link #toFields()}, or null when there is none (or it cannot be read: start again). */
    public static RouteTrackState fromFields(Map<String, String> f) {
        if (f == null || !"1".equals(f.get("v"))) {
            return null;
        }
        try {
            RouteTrackState s = new RouteTrackState();
            String[] plan = f.get("plan").split(",");
            s.planId = Long.parseLong(plan[0]);
            s.planVersion = Integer.parseInt(plan[1]);
            s.status = Status.valueOf(f.get("status"));
            s.statusSince = instant(f.get("statusSince"));
            s.seenAtOrigin = Boolean.parseBoolean(f.get("seenAtOrigin"));
            s.jumps = f.get("jumps") == null ? 0 : Integer.parseInt(f.get("jumps"));
            String[] last = f.get("last").split(",");
            s.lastAt = instant(last[0]);
            s.lastLatitude = Double.parseDouble(last[1]);
            s.lastLongitude = Double.parseDouble(last[2]);
            s.heading = Double.parseDouble(last[3]);
            String[] ch = f.get("chainage").split(",");
            s.chainage = Double.parseDouble(ch[0]);
            s.crossTrack = Double.parseDouble(ch[1]);
            s.peakChainage = Double.parseDouble(ch[2]);
            String[] off = f.get("off").split(",");
            s.offReadings = Integer.parseInt(off[0]);
            s.backInside = Integer.parseInt(off[1]);
            s.offSince = instant(off[2]);
            s.offLatitude = Double.parseDouble(off[3]);
            s.offLongitude = Double.parseDouble(off[4]);
            s.offDriven = Double.parseDouble(off[5]);
            s.offMovingSeconds = Double.parseDouble(off[6]);
            s.lastOnChainage = Double.parseDouble(off[7]);
            s.maxOff = Double.parseDouble(off[8]);
            String[] check = f.get("check").split(",");
            s.checkPending = Boolean.parseBoolean(check[0]);
            s.checkAskedAt = instant(check[1]);
            s.lastCheckAt = instant(check[2]);
            String[] dev = f.get("deviation").split(",");
            s.deviationOpen = Boolean.parseBoolean(dev[0]);
            s.deviationAt = instant(dev[1]);
            s.addedSeconds = Long.parseLong(dev[2]);
            s.verdict = "-".equals(dev[3]) ? null : dev[3];
            s.replanRemainingMeters = Double.parseDouble(dev[4]);
            String[] wrong = f.get("wrong").split(",");
            s.wrongOpen = Boolean.parseBoolean(wrong[0]);
            s.wrongOffPlan = Boolean.parseBoolean(wrong[1]);
            s.wrongAt = instant(wrong[2]);
            s.wrongReadings = Integer.parseInt(wrong[3]);
            s.risingReadings = Integer.parseInt(wrong[4]);
            s.wrongFrom = Double.parseDouble(wrong[5]);
            s.wrongLowest = Double.parseDouble(wrong[6]);
            String[] pace = f.get("pace").split(",");
            s.pace = Double.parseDouble(pace[0]);
            s.paceAnchor = Double.parseDouble(pace[1]);
            s.paceMovingSeconds = Double.parseDouble(pace[2]);
            s.drivenSinceRest = Double.parseDouble(pace[3]);
            s.stillSeconds = Double.parseDouble(pace[4]);
            s.movingSinceDeparture = Double.parseDouble(pace[5]);
            s.etaExtraSeconds = Long.parseLong(pace[6]);
            String[] eta = f.get("eta").split(",");
            s.departedAt = instant(eta[0]);
            s.etaShown = instant(eta[1]);
            s.etaAnnounced = instant(eta[2]);
            s.etaPromised = instant(eta[3]);
            s.arrivedAt = instant(eta[4]);
            String[] totals = f.get("totals").split(",");
            s.deviations = Integer.parseInt(totals[0]);
            s.deviationMeters = Double.parseDouble(totals[1]);
            s.deviationSeconds = Long.parseLong(totals[2]);
            s.wrongWays = Integer.parseInt(totals[3]);
            s.alternates = Integer.parseInt(totals[4]);
            return s;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String time(Instant t) {
        return t == null ? "-" : Long.toString(t.toEpochMilli());
    }

    private static Instant instant(String s) {
        return s == null || "-".equals(s) ? null : Instant.ofEpochMilli(Long.parseLong(s));
    }
}
