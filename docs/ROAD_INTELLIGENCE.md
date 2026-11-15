# Road Intelligence 🛣️

> **The question it answers:** what do we know about every road our trucks use, and how does that help the
> operation? Separate from vehicle intelligence, driver analytics, ETA prediction and dispatch optimisation.

The first part, **H. Road speed & regulatory intelligence**, is built and running (see
[ROAD_SPEED_GUIDE.md](ROAD_SPEED_GUIDE.md)). This document is the plan for the rest, and the console's
**Road Intelligence** section has a page for each sub-module with its status.

---

## 1. How it is built

```
OpenStreetMap (India) ──► Valhalla tiles on the HP ──► /trace_attributes, /locate, /route, /isochrone
                                                            │  (map matching of our trips)
                                                            ▼
                                     road_segment (PostgreSQL)  ◄── hand-set limits, enrichment by OSM way id
                                                            │
                         ┌──────────────┬──────────────────┼───────────────┬──────────────┐
                    live speed check   day-end report    explorer / class   restrictions   risk score
```

* Roads are **learned once** (a trip on request, live readings every 2 minutes) and kept in `road_segment`.
  Pages and modules read the library; Valhalla is asked only for routing, map matching and graph questions, never per
  page view.
* Every road piece keeps its **OpenStreetMap way id**, so attributes Valhalla does not return can be added later from
  an offline OpenStreetMap extract.

## 2. The sub-modules

| | Module | Status | Phase |
|---|---|---|---|
| A | Road network explorer: names, numbers, class, lanes, map, road under a point | partly built | 1 |
| B | Classification & hierarchy: length by class, highway vs local, town vs open road | partly built | 1 |
| C | Restrictions & accessibility: height, width, weight, axle load, access, vehicle type | planned | 1 |
| D | Surface & physical: paved, surface, width, lanes, lighting | planned | 1 |
| E | Geometry & elevation: curvature, bends, gradients, mountain roads | planned | 1 |
| F | Bridges, tunnels & infrastructure: bridges, tunnels, level crossings, toll booths, ferries | planned | 1 |
| G | Connectivity & network: intersections, dead ends, alternatives, bottlenecks | planned | 2 |
| H | Speed & regulations: limit per road, road-aware over-speed, day-end report | **built** | 1 |
| I | Toll & cost: toll roads, plazas, toll vs toll-free, spend per trip | planned | 2 |
| J | Risk & quality score: factors + our incidents + accident black spots | planned | 1 |
| K | Reachability: isochrones from plants and yards | planned | 2 |
| L | Road comparison: alternatives side by side | planned | 2 |
| M | Road data quality: coverage of every attribute | planned | 1 |

## 3. What this Valhalla actually gives (checked 2026-11-15, first 96 km of trip 28860462)

| Attribute | Result | Usable |
|---|---|---|
| Names, numbers, class, lanes | every edge | ✅ |
| OpenStreetMap speed limit | 81 of 963 km, car limits | partly |
| One-way / two-way | 77 km one-way (divided), 18 km two-way | ✅ |
| Bridges | 2.2 km flagged | ✅ |
| Curvature | 0..7 | ✅ |
| Road use (ramp, service road, turn lane) | returned | ✅ |
| Dead end, access per vehicle type, destination-only for trucks, signals, reach | `/locate` verbose | ✅ |
| Surface | always `paved_smooth` (the default when untagged) | ❌ |
| Elevation, gradients (`/height`) | `null`: tiles built **without elevation** | ❌ |
| Tolls | no toll edges; booths are points | ❌ |
| State code | not returned by this build | ❌ |

## 4. Our take on the plan

The direction is right: Road Intelligence as its own system, a road database in front of Valhalla, analytics on top.
Five corrections from what the data showed:

1. **Valhalla cannot list every road.** It has no export call. What we can know cheaply is the roads our trucks drive
   (map matching), which is also what an operator cares about first. Network-wide questions (G, density by district in
   B, a full explorer) need the OpenStreetMap extract the HP's tiles were built from, imported into PostGIS
   (osm2pgsql). Do that in phase 2, for our corridors first, not all of India.
2. **Indian OpenStreetMap is thin on exactly the tags C and D need.** Surface comes back untagged, maxheight /
   maxweight are rare, speed limits cover 8 %. So build **M (data quality)** early: it shows how much each module can be
   trusted, and where to enrich by hand.
3. **Elevation needs a one-time job on the HP.** Build Valhalla's elevation tiles for India and rebuild; then
   `/height` and the edge gradients fill in and E becomes possible. Curvature works already.
4. **The strongest risk signal is our own telemetry.** Over-speed episodes already carry their road; harsh driving and
   unplanned stops can too. Combine that with the public MoRTH accident black-spot list and the geometry factors, and
   always show the factors next to the score.
5. **Tolls need a price dataset** (NHAI's plaza fee tables). Valhalla only knows where tolls are, and here not even
   that on the edges.

Re-ordered priority:

1. **Store what Valhalla already returns** and we drop today: one-way, bridge, tunnel, roundabout, use, curvature,
   truck route, dead end, truck access → A, B and F partly live, no new data source.
2. **M. Data quality**: coverage per attribute.
3. **E. Elevation tiles on the HP** → gradients, elevation profiles.
4. **C. Restrictions**: maxheight / maxweight / maxaxleload for our way ids from an offline extract; truck profiles.
5. **J. Risk score** from our incidents + black spots + geometry.
6. **Phase 2**: PostGIS network import (G, district density), tolls (I), isochrones (K), comparison (L).

Constraint to keep in mind: the dashboard may not use third-party UI libraries, so maps are drawn in-house (the
explorer draws the learned roads as SVG, to scale, without map tiles).
