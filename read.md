Checking the stack before wiping: the database is 163 MB (308k positions, 3,218 geofence visits). I'll keep a backup of it outside the repo first, then wipe and restart.

Backup saved (12 MB, 22 tables). Now wiping the stack's data and starting it fresh; the 1.7 GB India routing map volume stays.

The HP answers at 192.168.137.170 (Valhalla OK), so I'll start the stack pointed there instead of the old .1 address.

The fresh stack is up, the 150-truck simulator is filling it, and there are no errors. Now I'm collecting the facts for your overview.

The system has been restarted from an empty database and is running again on **localhost:3000** (sign-in abc / abc). Here's the overview; ask about any part and I'll explain it in detail.

## Where things stand now

- **Fresh start:** I wiped the database, Kafka and Redis. On startup the service rebuilt all tables (database changes V1 to V10), the 87 geofences and the logins.
- **Data:** the simulator replays 150 real trucks from your smart_truck data. It started 2 hours in the past at 30× speed and becomes live once it catches up to now. In the first minute it had stored 1,114 positions, 243 events and 7 geofence visits, with 0 errors.
- **Backup of the old data:** `D:\Tech-Projects\processing-layer-backups\gps_tracking-2026-11-02-before-fresh-start.dump` (12 MB). I kept the 1.7 GB India map, so it won't need downloading again.
- **HP:** it is reachable at 192.168.137.170 and Valhalla works there. The service now points to it.
- **For tomorrow:** keep this laptop and Docker running overnight. Otherwise the day report will only cover from tonight.

## How data flows

GPS device or simulator → ingestion (each reading is saved to disk until Kafka confirms it) → Kafka → cleaning step (bad readings go to a "dead letters" queue) → **6 plug-in modules**, each reading the same feed on its own → PostgreSQL (history), Redis (live state), events → dashboard.

Each module can be switched on, switched off or replayed on its own, which is what your team lead asked for.

## What's built, and the algorithm behind each

| Module | What it answers | How |
|---|---|---|
| **Ignition** | Engine on, off or idling | The ignition wire if the device has one; otherwise worked out from movement |
| **Vehicle state** | Heading, harsh braking or acceleration, distance | Kalman filter to smooth the GPS track and the distance |
| **Trips & stops** | When a trip starts and ends, and where it stopped | Traccar's trip-detection logic, rewritten so it gives the same answer every time it's run |
| **Stoppage** | Stopped with engine on (an alarm) or parked | Ignition plus a traffic/weather check |
| **Speed violation** | Overspeeding, faulty speedometer | See below |
| **Geofencing** | Which fence a truck is in, when it entered and left, how long it stayed | See below |
| **Shipment tracking** | Departed, arrived, delayed | Links each shipment to its truck and its origin and destination fences |

**Speed violation:**
- A truck goes from normal to "possibly over" to "violating". An alert is raised only if the overspeed lasts several readings and a minimum time, so a short burst raises nothing.
- The episode ends only when speed falls clearly below the limit.
- Each speedometer reading is checked against GPS speed (distance over time across the last few readings). If the GPS contradicts the speedometer, it's reported as a faulty sensor, not as a driver offence.
- Alerts are graded as warning, breach or severe. The limit can be stricter at night, and inside a plant the fence's own lower limit applies.

**Geofencing:**
- **Finding nearby fences:** India is divided into a grid of 0.05° squares (about 5.5 km), and each fence is listed under every square it touches. A reading only checks the fences in its own square, instead of all 87.
- **Inside or outside:** for a circle it's a distance check. For a polygon it's ray casting: an odd number of border crossings means inside.
- **Rules copied from smart_truck:**
  - a truck must stay inside 15 minutes before entry is confirmed;
  - it counts as out at once beyond 2× the radius;
  - a short drive-through is recorded as a pass;
  - a crossing between two far-apart readings is still detected;
  - a long stay raises an alert, with the limit set per fence type: origin 12 h, destination 6 h, yard 4 h.
- **Numbers you can quote:**
  - it takes 0.51 microseconds per reading;
  - I replayed 2,220 smart_truck trips (3.48 million readings) and found 95.6% of smart_truck's own geofence events;
  - 98.8% of the events we found were confirmed by smart_truck.

**Day report:**
- Time spent at each fence, compared with that fence's usual over the last 30 days.
- How crowded each fence is hour by hour, and a 24-hour timeline for each truck.
- Plain-English insights, for example "truck X spent 3× longer than usual at Y", plus a CSV download.

**Three logins:** you (the GPS provider, who sees everything), the transporter and the consigner. Each sees only their own trucks.

## Not done yet

- **Route deviation and live ETA:** designed only (`docs/ROUTE_DEVIATION_ETA_DESIGN.md`).
  - Valhalla on the HP plans the route.
  - Each reading is placed against that route to see how far along it is and how far off it is.
  - A deviation is confirmed after 3 readings, 5 minutes and 1 km off the route, or at once if more than 2 km off.
  - The design also covers spotting a truck driving the wrong way.
  - ETA = remaining planned time × that truck's usual pace + its usual stops.
  - This is next to build.
- **GraphHopper:** its India import never finished, so Valhalla replaces it.
- **Waypoints module:** still shows example data.
- **Requirements documents (`srs/`):** not yet updated for the last two database changes.
- **Not pushed to GitHub:** the last 3 commits (09b5ff7, 0c7824a, be387cb).