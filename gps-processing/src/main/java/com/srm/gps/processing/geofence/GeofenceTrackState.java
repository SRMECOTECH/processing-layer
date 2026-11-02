package com.srm.gps.processing.geofence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the geofence tracker remembers about one device: its last reading, and every fence it is inside or about to
 * change sides of. A fence the vehicle is plainly outside of is not stored at all, so a truck on the highway has an
 * empty map - the common case.
 *
 * Stored as a Redis hash (gps:state:geofence:{imei}) with {@link #toFields()} / {@link #fromFields}:
 *   v       format version
 *   last    epoch millis, latitude, longitude of the last reading
 *   fences  one entry per fence, ';' between entries, ',' between values (see {@link FenceTrack})
 */
public record GeofenceTrackState(Instant lastRecordedAt, double lastLatitude, double lastLongitude, Map<Long, FenceTrack> tracks) {

    public static final GeofenceTrackState EMPTY = new GeofenceTrackState(null, 0, 0, Map.of());

    private static final String VERSION = "1";

    public GeofenceTrackState {
        tracks = tracks == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(tracks));
    }

    /**
     * One fence of one vehicle.
     *
     * @param inside           the believed (confirmed) side of the border
     * @param enteredAt        while inside: the first reading inside of this visit
     * @param entryGapSeconds  the gap from the reading before the entry: the uncertainty of enteredAt
     * @param dwellAlertAt     when the visit went over its dwell limit (the alert was raised), or null
     * @param pending          readings on the other side that have not held long enough yet, or null
     */
    public record FenceTrack(boolean inside, Instant enteredAt, double entryLatitude, double entryLongitude,
                             long entryGapSeconds, Instant dwellAlertAt, Pending pending) {

        public static final FenceTrack OUTSIDE = new FenceTrack(false, null, 0, 0, 0, null, null);

        FenceTrack withPending(Pending p) {
            return new FenceTrack(inside, enteredAt, entryLatitude, entryLongitude, entryGapSeconds, dwellAlertAt, p);
        }

        FenceTrack withDwellAlertAt(Instant at) {
            return new FenceTrack(inside, enteredAt, entryLatitude, entryLongitude, entryGapSeconds, at, pending);
        }

        /** Outside with nothing pending: nothing worth storing. */
        boolean settledOutside() {
            return !inside && pending == null;
        }
    }

    /**
     * A run of readings on the other side of the border.
     *
     * @param inside        which side the run is on
     * @param since         its first reading
     * @param gapSeconds    the gap between that reading and the one before (for an exit: last inside -> first outside)
     * @param lastInsideAt  for a run outside: the last reading inside before it, the exit stamp if the run holds
     */
    public record Pending(boolean inside, Instant since, double latitude, double longitude, long gapSeconds,
                          Instant lastInsideAt, double lastInsideLatitude, double lastInsideLongitude) {
    }

    public Map<String, String> toFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("v", VERSION);
        fields.put("last", lastRecordedAt == null ? "" : lastRecordedAt.toEpochMilli() + "," + lastLatitude + "," + lastLongitude);
        List<String> entries = new ArrayList<>();
        tracks.forEach((id, t) -> {
            Pending p = t.pending();
            entries.add(String.join(",", String.valueOf(id), t.inside() ? "1" : "0", millis(t.enteredAt()),
                    String.valueOf(t.entryLatitude()), String.valueOf(t.entryLongitude()), String.valueOf(t.entryGapSeconds()),
                    millis(t.dwellAlertAt()),
                    p == null ? "-" : p.inside() ? "1" : "0",
                    p == null ? "-" : millis(p.since()),
                    p == null ? "-" : String.valueOf(p.latitude()),
                    p == null ? "-" : String.valueOf(p.longitude()),
                    p == null ? "-" : String.valueOf(p.gapSeconds()),
                    p == null ? "-" : millis(p.lastInsideAt()),
                    p == null ? "-" : String.valueOf(p.lastInsideLatitude()),
                    p == null ? "-" : String.valueOf(p.lastInsideLongitude())));
        });
        fields.put("fences", String.join(";", entries));
        return fields;
    }

    /** The state stored by {@link #toFields()}; an empty or unreadable hash is {@link #EMPTY} (a fresh start). */
    public static GeofenceTrackState fromFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty() || !VERSION.equals(fields.get("v"))) {
            return EMPTY;
        }
        try {
            Instant last = null;
            double lat = 0;
            double lon = 0;
            String lastField = fields.getOrDefault("last", "");
            if (!lastField.isEmpty()) {
                String[] parts = lastField.split(",");
                last = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                lat = Double.parseDouble(parts[1]);
                lon = Double.parseDouble(parts[2]);
            }
            Map<Long, FenceTrack> tracks = new TreeMap<>();
            String fences = fields.getOrDefault("fences", "");
            if (!fences.isEmpty()) {
                for (String entry : fences.split(";")) {
                    String[] v = entry.split(",", -1);
                    Pending pending = "-".equals(v[7]) ? null : new Pending("1".equals(v[7]), instant(v[8]),
                            Double.parseDouble(v[9]), Double.parseDouble(v[10]), Long.parseLong(v[11]), instant(v[12]),
                            Double.parseDouble(v[13]), Double.parseDouble(v[14]));
                    tracks.put(Long.parseLong(v[0]), new FenceTrack("1".equals(v[1]), instant(v[2]), Double.parseDouble(v[3]),
                            Double.parseDouble(v[4]), Long.parseLong(v[5]), instant(v[6]), pending));
                }
            }
            return new GeofenceTrackState(last, lat, lon, tracks);
        } catch (RuntimeException unreadable) {
            return EMPTY;
        }
    }

    private static String millis(Instant at) {
        return at == null ? "-" : String.valueOf(at.toEpochMilli());
    }

    private static Instant instant(String value) {
        return value == null || "-".equals(value) ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }
}
