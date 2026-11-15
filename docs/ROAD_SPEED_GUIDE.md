# Road Intelligence H – road speed limits: which road, which limit, how fast 🛣️

Part H of [Road Intelligence](ROAD_INTELLIGENCE.md). The speed module judges every reading by the limit of **the road the truck is on**, not one number for the whole
country. This guide covers where the roads come from, how a road's limit is chosen, and what the owner gets at the end
of the day.

> **Ek line mein:** Valhalla har trip ke GPS points ko asli sadak pe "map match" karta hai (NH19, Agra–Lucknow
> Expressway, town ki sadak…). Har sadak ki limit hum table se, OpenStreetMap se, ya haath se set karke lete hain.
> Live check us sadak ki limit se speed jaanchta hai, aur din ke ant mein owner ko sadak-wise report milti hai.

---

## 1. The test bed: one long trip

Everything is tested on **one** trip before the fleet: smart_truck trip **28860462**, Jamshedpur → Ghaziabad
(Delhi NCR), 1,381 km, 7 days, 10,600 pings once a minute, no gaps. If it works end to end for this trip, it works
for many.

| Setting (compose / env) | Value | Meaning |
|---|---|---|
| `GPS_SIMULATOR_TRIP_NUMBERS` | `28860462` (default) | the simulator replays only these trips, every ping. Empty = the 150-vehicle fleet |
| `GPS_SIMULATOR_CATCH_UP_SPEED_FACTOR` | `120` | the whole trip lies in the past and is replayed 120× faster (about 1.4 hours) |

## 2. Where the roads come from

```
smart_truck trip (read-only) ──► POST /api/roads/learn?trip=…  ─┐
                                                                ├─► Valhalla /trace_attributes ─► road_segment
gps_position (live readings) ──► every 2 min, per device cursor ┘     (HP home server, all-India OSM)   (library)
```

* **Map matching** (Valhalla's Meili, a hidden Markov model over the road graph) snaps the points onto roads and returns,
  per road edge: OpenStreetMap way, names and number (NH19, SH4, MDR156), class (motorway, trunk, primary …), lanes,
  density (how built-up the area is), and the OpenStreetMap limit when the road has one.
* Points closer than 15 m to the previous one are left out (a standing truck adds nothing). The rest go in pieces of
  300 points / 80 km (Valhalla refuses traces over 200 km). A piece Valhalla cannot match is retried as two halves.
* The test trip: 2,427 points after thinning, the first 1,500 matched 963 km of road in 6 calls and 2.6 s.
* The library is knowledge of the roads, not telemetry: **Clear database** keeps it.

## 3. The limit of a road

From the first source that has one:

| # | Source | Example |
|---|---|---|
| 1 | **Set by hand** on the console (one piece, or a whole road) | 30 km/h on a bypass through a market |
| 2 | **OpenStreetMap** `maxspeed`, only when lower than the table (it is tagged for cars) | 100 on the Agra–Lucknow Expressway → the table's 80 applies |
| 3 | **Table** by road class × town/open road (× state) | trunk, open road, 2-lane: 60 |

The table's starting values (goods vehicles; check them per state):

| Class | Open road | Multi-lane (divided, 2+ lanes each way) | Town |
|---|---|---|---|
| motorway (expressway) | 80 | – | 80 |
| trunk (national highway) | 60 | 80 | 50 (multi-lane 60) |
| primary (state highway) | 60 | 80 | 50 (multi-lane 60) |
| secondary | 60 | – | 40 |
| tertiary | 50 | – | 40 |
| unclassified (minor) | 40 | – | 30 |
| residential | 30 | – | 30 |
| service / yard | 20 | – | 20 |

"Town" = Valhalla density ≥ 9 (0..15). This Valhalla build returns no state codes, so the all-India rows apply.

**The limit of a reading** = the lowest of: the company's limit for the vehicle (its speed rule, 60 by default – a
cap), the road's limit, the strictest geofence's limit (a plant's 20), and the night reduction when it is on. So an
80 km/h expressway still alerts at the company's 60; a 40 km/h town road alerts at 40.

## 4. The live check

`SpeedViolationService` finds the road under each reading in memory (`RoadIndex`, a 0.01° grid; nearest road piece
within 35 m) and passes its limit to the policy. The episode rules are unchanged: over the limit for 3 readings in a
row and 60 s, ends 5 km/h under it, speedometer checked against the GPS.

New: an episode is judged by the limit **where it went furthest over**, not the limit at its end. A truck doing 65 on a
60 highway and then 55 in a 40 town is 15 over (in the town), not 5. The event's details carry the road: `roadLabel`,
`roadClass`, `roadArea`, `limitSource` (MANUAL, OSM, TABLE, ZONE, COMPANY), `peakLatitude/peakLongitude`.

A reading on a road not learned yet is judged by the company limit, as before, until the live matching (every 2
minutes) has added that road.

## 5. The day-end report (Reports → Road & speed report)

`vehicle_day_road`, rebuilt every 5 minutes for today and yesterday from `gps_position`: one row per vehicle, day and
road (road + class + limit).

* Per vehicle: km, driving time, roads, max speed, **km and minutes above the road's limit**, per 100 km, episodes.
* Every over-speed episode: when, road, limit and its source, top speed, how far over, how long, band, a map link.
* Every road driven: class, town/open, limit, km, driving time, average / max speed, km and minutes above the limit.
* By road class, the roads with most episodes (where a sign helps more than a talk), night episodes, and the share of
  readings on a known road (data quality).

"Above the limit" counts every fast reading (exposure). An episode needs the truck to stay fast. Both are shown.

## 6. Console and API

| Page | What |
|---|---|
| Road Intelligence → H. Speed & regulations → **Road limits** | library size, learn a trip, match live now, edit the limit table, set a limit on a whole road |
| Reports → **Road & speed report** | the day-end report, CSV, print |

```
GET  /api/roads/status                 POST /api/roads/learn?trip=28860462     POST /api/roads/match-live
GET  /api/roads?q=NH19                 GET  /api/roads/pieces?ref=NH19         PUT  /api/roads/limit
GET  /api/roads/locate?lat=&lon=       GET  /api/roads/limits                  PUT  /api/roads/limits
GET  /api/roads/report?day=&imei=      POST /api/roads/report/rebuild?day=
```

Settings: `gps.roads.*` in application.yml (match distance 35 m, Valhalla search radius 50 m, pieces of 300 points /
80 km, live matching every 2 min, `GPS_ROADS_LIVE_MATCHING=false` to switch it off).
