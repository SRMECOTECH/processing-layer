# Route Intelligence – deviation, recalculation, reassessment 🧭

The plan for `gps-route-service`: what it will do, how, and in which order. The routing engine it stands on is
installed and documented in [ROUTING_ENGINE_SETUP.md](ROUTING_ENGINE_SETUP.md).

> **Ek line mein:** har trip ka ek "plan" raasta hoga. Truck us raaste se hata to hum turant pakdenge (deviation),
> naya raasta aur naya ETA nikalenge (recalculation), aur phir decide karenge ki hatna sahi tha ya galat, aur
> kitne ka pada (reassessment). Owner ko roz ka hisaab milega, ops ko live alert.

---

## 1. Two people, two questions

| | **Owner** (end of the day / week) | **Operations** (right now) |
|---|---|---|
| Asks | "Where am I losing money on routes, and what do I change?" | "Which truck is off its route right now, and does it matter?" |
| Gets | ₹ leakage from unplanned km, on-time %, route adherence per vehicle / driver / customer / lane, and **recommendations** | Live board: on plan / off plan / rejoined, recalculated ETAs, alerts with a verdict |
| Decides | Change a planned route, coach or reward a driver, re-price a lane, dispute a bill | Call the driver, inform the customer, escalate |

Both views read the same events, so the owner's numbers are exactly the sum of what ops saw live.

---

## 2. The three engines of the module

```
gps.processed-positions ──► [1] DEVIATION ──► OFF_ROUTE confirmed ──► [2] RECALCULATION ──► [3] REASSESSMENT
   (key = IMEI)              corridor match       (hysteresis)          new route + ETA        verdict + ₹ cost
                             per reading                                (GraphHopper CH)       (+ traffic/weather)
                                   │                                          │                      │
                                   └──────────── gps.vehicle-events (ROUTE_DEVIATION, ROUTE_REJOINED, ───┘
                                                 ROUTE_RECALCULATED, ETA_CHANGED, WAYPOINT_SKIPPED)
                                                          │
                                         vehicle_event (PostgreSQL) · notifications · dashboard
                                                          │
                                         route_adherence_daily  ──►  owner insights
```

### [1] Deviation – "is the truck on its planned route?"

- **Plan + corridor.** Every active trip has a planned route (geometry from GraphHopper) and a **corridor** around it:
  250 m on highways, 100 m in cities (configurable per lane). The planned route's **alternatives** (GraphHopper
  `alternative_route`) are stored as **accepted corridors** – driving one of them is not a violation.
- **Per reading** (consumer group `gps-route-deviation-service`, 3 threads like the others): distance from the position
  to the nearest planned segment (`DistanceCalculator.distanceToLine`, already in `gps-processing`), searched only near
  the last matched segment, so it is O(1) per reading. That also gives **chainage** – km done along the plan – which
  later drives ETA and waypoint order.
- **No false alarms (hysteresis):** `ON_ROUTE → SUSPECT → OFF_ROUTE` only after 3 readings in a row **and** at least
  500 m or 2 min outside; back to `ON_ROUTE` after 2 readings inside. Standing vehicles are left to the stoppage module.
- **Confirmation by map matching:** before raising OFF_ROUTE, the last ~10 readings go to GraphHopper `/match`
  (Hidden Markov Model + Viterbi). That tells which **road** the truck is really on, so a service road beside the
  highway, a flyover above it or GPS drift in a city never becomes an alert.
- **Events:** `ROUTE_DEVIATION` as an episode (OPEN when confirmed, CLOSED when rejoined, same key – exactly how
  over-speed and stoppage episodes already work), with max distance off route, minutes and km off route.

### [2] Recalculation – "what now?"

- On OFF_ROUTE: one CH request from the current position (with the truck's **heading**, so no U-turn is suggested) to
  the next remaining waypoint / destination. Milliseconds; done on a small pool so Kafka never waits (the pattern of
  `StoppageContextAgents`). At most once per 2 minutes per vehicle.
- Result classified:
  - **Rejoin** – the new route comes back to the plan within a few km → short detour.
  - **Alternate corridor** – it follows an accepted alternative → `ACCEPTED`, no alert.
  - **New route** – genuinely different → keep following the new route as the temporary plan.
- **ETA** = time already driven + recalculated remaining time; `ETA_CHANGED` when it moves by more than 15 min
  (the customer-facing number).
- Road closed / restricted? A **custom model per request** (LM mode) avoids an area or a road – the same call.

### [3] Reassessment – "was it right, and what did it cost?"

- **Cost of the deviation:** extra km × cost per km (diesel price ÷ vehicle mileage + wear), extra toll
  (path details `toll`), extra driver hours, and **SLA impact** (recalculated ETA vs the promised window).
- **Why:** reuse the stoppage agents – **fleet traffic** (other trucks crawling nearby), **Open-Meteo weather**,
  optional TomTom – plus dispatcher notes ("ordered via Kharagpur") and known places (fuel pumps, dhabas, depots as
  geofences, once nested geofencing is live).
- **Verdict:** `JUSTIFIED` (traffic / weather / closure / dispatcher), `EXPLAINED` (fuel, rest, at a known place),
  `UNEXPLAINED` (raised to HIGH), `SUSPICIOUS` (stopped ≥ 15 min off route at an unknown place – possible pilferage or
  an unauthorised load).

---

## 3. What the owner sees (business value)

A daily / weekly page under **Analytics → Route economics**, and a short weekly summary:

| Metric | How it is computed | Decision it drives |
|---|---|---|
| **₹ leakage** | Σ cost of `UNEXPLAINED` + `SUSPICIOUS` deviations | Which vehicles / drivers to look at first |
| **Excess km %** | (actual km − optimal truck km) ÷ optimal km per trip | Driver coaching, fuel budgets |
| **Route adherence %** | km inside corridor ÷ km driven | Fleet discipline score |
| **On-time %** and **ETA accuracy** | delivered within window; first ETA vs actual | Customer promises, lane pricing |
| **Planned vs actual km per trip** | plan distance vs trip distance (Kalman-filtered) | Billing disputes – bill on plan, prove actual |
| **Toll vs non-toll** | toll km and ₹ per lane, vs the "avoid tolls" route | Whether tolls pay for themselves on a lane |

**Recommendations, not just charts** – generated from the same data, for example:

- "Lane Jamshedpur → Kolkata port: 38% of trips leave the planned NH-18 for NH-16 via Kharagpur and arrive 22 min
  earlier on average → **update the planned route**." (The plan is wrong, not the drivers.)
- "JH05XX1234: 6 unexplained detours this week, +214 km, ≈ ₹4,200 diesel → **review with the driver**."
- "Lane Tata works → Ranchi: on-time 71% → **promise 45 min later or start earlier**."

### The quick win: shadow plans (no new input needed)

Trips are already detected (`gps_trip`, section 15 of the README). For every closed trip we can ask GraphHopper for
the optimal **truck** route between its start and end and compare: **excess km, excess time, toll used** – per
vehicle, driver and lane, for the past weeks too. It needs no dispatcher, no plan entry, no new device data, and gives
the owner the "₹ leakage" number in the first iteration. Live deviation then builds on the same pieces.

---

## 4. How – inside `gps-route-service`

```
gps-route-service/src/main/java/com/srm/gps/route/
  engine/          ✅ GraphHopperClient (route, info), algorithms, health      – done
  api/             ✅ /api/routing/engine | route | compare                   – done
  plan/            route plans, waypoints, corridors, alternatives (PostgreSQL)
  deviation/       plain Java: CorridorMatcher, DeviationDetector (state machine) – unit-tested on recorded tracks
  recalculation/   async pool → CH re-route with heading, ETA, classification
  reassessment/    cost model (diesel, mileage, toll), context agents, verdict
  insights/        route_adherence_daily, shadow plans, recommendations, owner API
  kafka/           listener gps-route-deviation-service on gps.processed-positions → gps.vehicle-events
```

- **Storage:** PostgreSQL `gps_tracking`, its own Flyway history (schema `route`) so the two services never fight over
  migrations. Plan geometry as an encoded polyline (no PostGIS needed: ~150 active plans fit in memory). Live state per
  vehicle in Redis `gps:state:route:{imei}` – the key the dashboard's module registry already reserves.
- **Events:** new `VehicleEventType` values (`ROUTE_DEVIATION`, `ROUTE_REJOINED`, `ROUTE_RECALCULATED`, `ETA_CHANGED`,
  `WAYPOINT_SKIPPED`) on the existing `gps.vehicle-events` topic, so the store, notifications and the alerts UI pick
  them up without changes.
- **Dashboard:** `/alerts/route-deviation` (Corridors) and `/alerts/route-deviation/log` switch from example data to
  live; new Analytics page for the owner; a Developer page for the algorithm comparison.
- **Tests:** the deviation state machine on recorded tracks (like `osmand-sent-positions.jsonl` for trips), plus the
  150 simulated trucks, whose real smart_truck tracks naturally leave a straight plan now and then.

---

## 5. Phases

| Phase | What | Done when |
|---|---|---|
| **A** ✅ | GraphHopper on India + `gps-route-service` (route, compare, health) | Truck route Jamshedpur → Kolkata in ms; all algorithms agree |
| **B** | **Shadow plans** for closed trips + owner page v1 (excess km, ₹, per vehicle / lane) | Owner sees last week's leakage |
| **C** | Route plans + corridors + alternatives (API + UI, or from the trip master) | A trip can carry a plan |
| **D** | Live deviation: corridor match, hysteresis, `/match` confirmation, events, live board | Off-route alert within ~2 min, no alerts from GPS drift |
| **E** | Recalculation + ETA + `ETA_CHANGED` | New ETA within seconds of leaving the plan |
| **F** | Reassessment: verdicts + ₹ + recommendations; weekly summary | "Update the planned route" style recommendations |

---

## 6. Decisions needed from you

1. **Where do planned routes come from?** Dispatcher enters them (UI), the trip master in smart_truck / ERP, or
   learnt from history (most common path per lane) – or a mix.
2. **Cost model:** diesel price, mileage per vehicle class (e.g. 32 T trailer ≈ 3.5 km/l), toll rates source.
3. **Thresholds:** corridor 250 m highway / 100 m city, 3 readings / 2 min, ETA change 15 min – fine as defaults?
4. **Start with Phase B (shadow plans)?** It gives the owner a ₹ number first, with no new input.
