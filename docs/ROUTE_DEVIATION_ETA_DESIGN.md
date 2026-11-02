# Route deviation, wrong direction and live ETA on Valhalla — design for implementation

Status: **design, not built** (2026-11-02). It replaces the GraphHopper parts of
[ROUTE_INTELLIGENCE_PLAN.md](ROUTE_INTELLIGENCE_PLAN.md): Valhalla is already built for all of India (the HP home
server), and the GraphHopper import never finished. Everything per reading is local geometry in a module, like
geofencing; Valhalla is called once per trip and then only when a truck looks off its route.

## 1. What the user asked, answered in one line each

| Question | Answer |
|---|---|
| Give a source and a destination lat/lon: can we get the route? | Yes: Valhalla `POST /route` with `costing: "truck"` returns the road geometry (polyline6), distance, time and every turn. One call per trip. |
| Show it like Google Maps, live? | The planned line in grey, the truck's real trail on top coloured by state (on route / off route / wrong way), a moving marker, and an ETA strip (origin → waypoints → destination) per truck. Drawn in plain SVG: no map library (client servers forbid UI packages). |
| Dijkstra or A*? | Neither by hand on 19 million nodes. Valhalla runs **bidirectional A\*** on a **hierarchical, tiled graph** (motorways / arterials / local roads). Measured on the HP on 2026-11-02 over the hotspot: Jamshedpur plant → Adityapur 8.9 km / 27 min, Jamshedpur → Chennai 1,806 km / 30.9 h of truck driving, answered in 1.4 s (first request) - fine for one plan per trip, too slow to call per ping. Dijkstra is the special case of A\* with a zero heuristic: it explores in every direction and is far slower at country scale. We use Valhalla's search for the plan and for re-plans; per ping we only project the truck onto the plan we already have. |
| Going the opposite way? | Detected from **progress along the route** (chainage) going down while the truck moves, and from heading against the road's direction (section 4.3). |
| When is it *really* a deviation? | Only when it is off the corridor for long enough **and** far enough **and** Valhalla says the way it is going is worse than the plan (section 4.2). A different but equally good road is an *alternate route*, not an alarm. |
| Live ETA per truck? | Remaining distance along the plan ÷ a live pace learned from the truck itself, plus the usual stay at the stops still ahead (from the geofence module), smoothed so it does not jump (section 5). |
| Destination reached? | The geofence module's `GEOFENCE_ENTER` at the destination fence (already live): no second detector. |

## 2. Valhalla calls we need (all on the HP, `gps.connections` Valhalla URL)

| Call | When | What we keep |
|---|---|---|
| `POST /route` `{locations:[origin, waypoints…, destination], costing:"truck", costing_options:{truck:{height, width, length, weight, axle_load, hazmat}}, alternates:2, units:"kilometers"}` | once when a shipment is dispatched (or the first reading after it) | the shape (decoded polyline6), per-maneuver length and time, the alternates |
| `POST /route` from the truck's position (with `heading` so no U-turn is proposed) to the destination | only when a deviation is suspected, at most once per 2 min per truck | the re-plan, to classify the deviation |
| `POST /trace_attributes` (Meili map matching: a Hidden Markov Model + Viterbi over the road graph) | to confirm a suspicion with the last ~10 readings, and nightly for the day's real path | which road the truck is really on (a service road beside the highway is not a deviation) |
| `POST /sources_to_targets` (time-distance matrix) | dispatch planning, many trucks to one plant | not needed for deviation |

The HP's address is not fixed: on 2026-11-02 the HP joined the Lenovo's hotspot and became 192.168.137.170 (the Lenovo
is 192.168.137.1), so the Valhalla URL must come from Developer → Connections, not a hard-coded 192.168.137.1.
Valhalla down: plans wait, the module keeps the last plan, ETA falls back to straight-line distance × a road factor
(1.3) and says so; no deviation is raised without a plan.

## 3. Data

- `route_plan`: shipment id, version (re-plans add versions), polyline (lat/lon arrays), cumulative distance per
  vertex, cumulative planned seconds per vertex, alternates (same shape), created_at, reason (DISPATCH, REPLAN).
- Per vehicle state in Redis (`gps:state:route:{imei}`, module-owned): plan id + version, last matched segment index,
  chainage, cross-track distance, state (ON_ROUTE / SUSPECT / OFF_ROUTE / WRONG_WAY), when the state started, pace
  factor (EWMA), last ETA.
- Events: `ROUTE_DEVIATION` (episode), `ROUTE_ALTERNATE` (info), `WRONG_DIRECTION` (episode), `ETA_CHANGED`.

## 4. Per reading (module `route-deviation`, consumer group `gps-route-deviation-service`)

### 4.1 Project the reading onto the plan — O(1)

The plan's segments go into a small grid index (the same idea as the geofence index: each segment filed in every
0.01° cell its box touches). For a reading: look in its cell and the last matched segment's neighbours, keep the
nearest segment →

- **cross-track distance** d (metres off the line, `DistanceCalculator.distanceToLine`),
- **chainage** c = cumulative distance at the segment start + the projection along it (km done along the plan),
- **road bearing** at that point.

A plan of 1,500 km has ~40,000 vertices; this is microseconds, like geofencing.

### 4.2 Deviation: when to believe it

Corridor half-width: 150 m on highways, 80 m in towns (from the maneuver's road class), never below 3 × the GPS
accuracy the device reports. A reading with an implied speed above 150 km/h (a jump) is ignored.

```
ON_ROUTE ──d > corridor──► SUSPECT ──(≥ 3 readings AND ≥ 5 min AND ≥ 1 km driven off the line) or d > 2 km──► check
   ▲                          │ back within the corridor for 2 readings: nothing happened (GPS drift, a parallel lane)
   │
   └──── 3 readings back inside the corridor (closes the episode: km and minutes off route, extra km) ◄── OFF_ROUTE
check (asynchronous, never blocks Kafka):
   1. map-match the last 10 readings (trace_attributes): on a road that belongs to the plan or an alternate → ON_ROUTE
   2. re-plan from here to the destination:
        new remaining time ≤ planned remaining time × 1.10 (or + 15 min)  → ROUTE_ALTERNATE (INFO), the re-plan becomes
                                                                            the plan: a different but fine road
        otherwise                                                         → ROUTE_DEVIATION OPEN, severity by the
                                                                            added time: > 30 min HIGH, > 90 min CRITICAL
```

Standing still off the route is the stoppage module's job; both together (stopped ≥ 15 min, off route, at no known
geofence) is the "possible pilferage" case of the plan document.

### 4.3 Wrong direction

- On the plan: chainage goes **down** by more than 1 km over at least 3 readings while the truck moves (speed > 10 km/h),
  or the truck's heading is more than 135° from the road bearing for 3 readings → `WRONG_DIRECTION` OPEN.
- Off the plan: the re-plan's remaining distance grows by more than 2 km in 10 minutes while moving → the truck is
  driving away from the destination.
- Closed when chainage rises again for 3 readings. A U-turn at a toll plaza or a dhaba is short and never reaches the
  3 readings / 1 km.

## 5. Live ETA

```
remaining km   = plan length − chainage                       (off route: the re-plan's length)
planned time   = planned seconds from chainage to the end     (Valhalla's truck speeds per road, not one average)
pace factor    = EWMA over the last 60 min of (actual seconds / planned seconds for the chainage covered),
                 clamped 0.6 … 2.5, starting at the lane's history (median of past trips on this origin → destination)
stops ahead    = Σ usual stay at the waypoints / fences still ahead (geofence_visit median, the geofence module)
rest           = driver-hour rule: 30 min after every 4.5 h driving that is still ahead
ETA            = now + planned time × pace factor + stops ahead + rest
shown ETA      = moved at most 2 min per minute towards the new value (no jumping), unless a deviation or a stop changes
                 it by more than 30 min (then at once, with ETA_CHANGED)
confidence     = the lane's historical ETA error at this % of the trip (p10 … p90), shown as "14:20 – 15:05"
```

When the truck enters the destination fence (`GEOFENCE_ENTER`), the shipment is **arrived**: actual vs planned vs the
ETA promised at dispatch go into the shipment and the lane's history.

## 6. The picture per truck

- **Strip** (every shipment row): origin ● ─── waypoints ○ ─── destination ◎, the truck at its chainage, coloured
  green on route, amber suspect, red off route / wrong way; ETA and its band at the end; detours drawn as a bump.
- **Map** (shipment page): plan in grey, alternates dotted, the real trail coloured by state, the truck marker with
  its heading, deviation start/end pins. SVG over an equirectangular projection of the trip's bounding box; the road
  network itself is the plan's own geometry, so no tile server is needed (tiles can be added where allowed).
- **Owner view**: deviation minutes and extra km per transporter, per lane, per driver; on-time %, ETA error.

## 7. Build order (tomorrow)

1. `ValhallaClient` in `gps-route-service` (route, trace_attributes) + `route_plan` table (Flyway) + plan on dispatch.
2. `gps-processing/route/`: `RoutePlanIndex` (segment grid, projection), `RouteTracker` (the state machine above, pure,
   unit-tested like `GeofenceTracker`: drift, parallel road, real detour, U-turn, wrong way, GPS jump).
3. `RouteDeviationModule` (`GpsModule`, id `route-deviation`) with the async check pool (pattern of
   `StoppageContextAgents`).
4. ETA service + `ETA_CHANGED`; strip and map in the shipment pages; the Alerts → Route deviation page goes live.
5. Replay smart_truck trips (tta_trip_gps against their planned lane) the way the geofence module was checked, and tune
   the corridor and the 3 readings / 5 min / 1 km against real false alarms before switching alerts on.
