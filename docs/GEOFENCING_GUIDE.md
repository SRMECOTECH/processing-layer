# Geofencing module — live detection, visits and the day-wise report

Module id `geofence`, consumer group `gps-geofence-service`, switch `gps.modules.geofence.enabled`. It plugs in through
`gps-module-api` like every other module ([MODULE_PLUGIN_GUIDE.md](MODULE_PLUGIN_GUIDE.md)); the detection itself is
plain Java with no framework, so it can be used on its own.

## 1. The algorithm

```
build (once, and within 30 s of any fence change)
  India in grid cells of 0.05° (≈ 5.5 km; follows the median fence size, never smaller)
  every fence filed in EVERY cell its bounding box touches          -> 87 fences fill 1,489 cells, ≤ 8 per cell

per reading
  cell        = (floor(lat / 0.05), floor(lon / 0.05))               two divisions, no trigonometry
  candidates  = grid[cell]                                          one hash lookup; on a highway: empty, done
  inside      = candidates that contain the point
                  circle : dx = Δlon·111,320·cos(centre lat), dy = Δlat·111,320; inside when dx²+dy² ≤ r²
                  polygon: ray casting (even-odd), half-open test (yi > y) != (yj > y), bounding box first
  check       = inside ∪ fences remembered for this vehicle (inside, or about to change sides)
  each fence  : state machine, smart_truck's rules (backend/app/services/geofence/detection.py)
                  a new side of the border is believed after 15 min on it,
                  or at once for an exit beyond 2 radii from the centre
                  ENTER stamped at the FIRST reading inside, EXIT at the LAST reading inside (never interpolated),
                  with the gap to the neighbouring reading as the stamp's uncertainty
                  in and out within 15 min while moving ≥ 300 m: a pass-through (PASS), not a visit
                  inside longer than the dwell limit: DWELL (once per visit, closed at the exit)
  between two readings ≤ 30 min apart and ≥ 200 m: fences crossed with no reading inside -> inferred PASS
```

Why one cell and not "the k nearest cells": a fence registered in every cell it touches is always in the reading's
own cell, so no fence is ever missed (a 20 km fence covers dozens of cells) and nothing is sorted by distance.

Nested fences (Adityapur inside Jamshedpur, Gamharia-TSG inside Gamharia) are entered together; each keeps its visit.
A truck's "time inside fences" in the report counts overlapping visits once.

### Checked against smart_truck (2026-11-02)

`SmartTruckParityCheck` (gps-processing test sources) replays every smart_truck trip that has geofence events through
`GeofenceTracker` and compares with `tta_trip_geofence_events`:

| | |
|---|---|
| trips / readings replayed | 2,220 / 3,483,057 |
| smart_truck's enter/exit events found again | 95.6 % (16,126 of 16,873) |
| our events confirmed by smart_truck | 98.8 % |
| same second | 15,848 of the 16,126 matches |
| speed | 0.51 µs per reading on one thread (≈ 1.9 million readings/s) |

The differences sit at trip edges: smart_truck takes a trip's first ping as already inside (no confirmation), while
the live tracker believes a visit only after 15 minutes, so a trip that starts inside a 1 km fence and leaves within
15 minutes is an exit for smart_truck and a pass for us.

To run it again (read-only on smart_truck):

```bash
./mvnw -q -o -pl gps-processing test-compile
ST_USER=... ST_PASS=... java -cp "gps-processing/target/classes;gps-processing/target/test-classes;$HOME/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar" com.srm.gps.processing.geofence.SmartTruckParityCheck 2220
```

## 2. Using the engine without the platform

Everything in `com.srm.gps.processing.geofence` is pure Java (it needs only `DistanceCalculator` next to it):

```java
GeofenceIndex index = GeofenceIndex.of(List.of(
        Geofence.circle(44, "JAMSHEDPUR", "Tata Steel Jamshedpur", "ORIGIN", 22.788598, 86.1996, 10_000, 720, 20),
        Geofence.polygon(9, "YARD-7", "Yard 7", "YARD", corners, null, null)));

GeofenceTrackState state = GeofenceTrackState.fromFields(redisHash);           // or GeofenceTrackState.EMPTY
GeofenceTracker.Update u = GeofenceTracker.update(state,
        new GeofenceReading(recordedAt, lat, lon, speedKmph, secondsSincePrevious, false), index, GeofenceSettings.defaults());
u.findings();      // ENTERED / EXITED / PASSED / DWELL_EXCEEDED, with fence, stamps, gaps
if (u.durableChange()) { save(u.state().toFields()); }                         // only when a fence changed
```

Readings must reach `update` in order per vehicle (the platform does this with the IMEI as the Kafka key). A reading
not newer than the state's last one changes nothing, so redeliveries are harmless.

## 3. In the platform

| Piece | Where |
|---|---|
| Fence master | table `place` (V10: shape, polygon, dwell_alert_min, speed_limit_kmph, smart_truck metadata); 87 fences copied from smart_truck `geofences` |
| Index in memory | `GeofenceDirectory` (rebuilt after an edit, or within 30 s of any change) |
| Module | `GeofenceModule`: tracker → visits + events → Redis state (only on change) |
| Visits | table `geofence_visit`: one row per visit or pass, upserted on device + fence + kind + entry |
| Events | `GEOFENCE_ENTER`, `GEOFENCE_EXIT`, `GEOFENCE_PASS`, `GEOFENCE_DWELL` in `vehicle_event` and `gps.vehicle-events` (one per fence: details.episodeScope) |
| State | Redis `gps:state:geofence:{imei}` (deleted before a replay); the last reading per device is also kept in memory and trusted only when it is exactly the device's previous reading |
| Settings | `gps.modules.geofence.*` in application.yml (confirm-minutes, escape-factor, dwell limits per role ...) |

API (`/api/geofences`, console):

| | |
|---|---|
| `GET /api/geofences` | every fence with the vehicles inside it now |
| `GET /api/geofences/engine` | index numbers and the rules in force |
| `GET /api/geofences/locate?lat=&lon=` | which fences a point is inside |
| `PUT /api/geofences/{code}` | create or change a fence: `{name, role, shape: CIRCLE\|POLYGON, latitude, longitude, radiusMeters, polygon: [[lat, lon], ...], dwellAlertMinutes, speedLimitKmph}` |
| `POST /api/geofences/{id}/active?value=false` | switch a fence off (history stays) |
| `POST /api/geofences/import/smart-truck` | copy smart_truck's geofences again (fences edited here are kept) |
| `GET /api/geofences/live` | vehicles inside a fence now |
| `GET /api/geofences/visits` | newest visits and passes |
| `GET /api/geofences/report/day?date=&tenant=&imei=` | the day-wise report (`.csv`: the day's visits) |
| `GET /api/portal/geofence-report?date=` | the same, cut to a transporter's or consigner's trucks |

## 4. The day-wise report (Reports → Geofence day report; portals: Geofence day report)

What a transporter or truck owner reads, for one day (midnight to midnight IST):

1. **Headline**: visits, trucks, time inside fences, median stay, stays over the dwell limit, still inside.
2. **What stands out**, worst first, in sentences: a stay far above what is usual at that fence ("MH12AB1234 spent
   9 h 40 min at Tata Steel Jamshedpur: 3.1x the usual 3 h 05 min"), fences slower than usual across several visits
   (a queue, a slow dock), trucks held past the limit right now, where most of the time went, and how many stamps are
   uncertain because of GPS gaps.
3. **Where the time went**: per fence, visits, trucks, time inside on the day, median and 90 % stay, the longest stay
   and its truck, **vs usual** (today's median ÷ the fence's median of the 30 days before), still inside, passes.
4. **When the fences were crowded**: average trucks inside per hour for the busiest fences — the gate queue.
5. **Each truck's day**: a 24-hour bar per truck with its stays (plant, customer, yard), over-limit stays outlined,
   with time inside per role and its driving time and kilometres from the day summary.

A stay crossing midnight counts on each day for its minutes in that day; its length is the whole stay. Medians, not
means (a few multi-day holds would set the "typical" figure), as in smart_truck's detention report.

## 5. Speed limits per fence (the speed module uses them)

A fence's `speed_limit_kmph` (a plant's 20 km/h) lowers the vehicle's speed rule while it is inside; the over-speed
event names the fence. The alert policy (Alerts → Over-speed → Alert policy, `PUT /api/speed/policy`) grades episodes
by how far over the limit they went, can make the limit stricter at night, and keeps a speedometer the GPS contradicts
out of driver alerts.
