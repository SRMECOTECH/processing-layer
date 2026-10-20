# PulseStream IoT console

Operations console for the GPS streaming platform, built with Next.js 16 and React 19 from the
"Fleet Telematics Analytics Platform" design in Google Stitch.

Layout: a top bar (menu, logo, search, live Kafka rate, alerts, appearance, user), section tabs, and
for each section its own left menu of sub-pages. Each sub-page holds one or two cards, so sections grow
by adding sub-pages rather than crowding one page. Off-white page with navy accent; background
(off-white / white / warm) and accent (navy / sky) can be changed from the sliders icon and are remembered
per browser.

## Run it

The GPS service must be running on http://localhost:8080 (see the main README).

```powershell
npm install
npm run dev        # http://localhost:3000
```

Production build: `npm run build` then `npm start`. With Docker, `docker compose up -d --build`
from the project root starts it as the `dashboard` service on port 3000.

## Sections

| Section | Sub-pages |
|---|---|
| Stream Processors | Pipeline overview, Vehicle state, Speed violation, Stoppage & ignition, Nested geofencing, Waypoints & corridors, Kalman filter distance |
| Business Rules & Alerts | Over-speed verification, Trip detection master, Route deviation, Dispatch & escalations, Ingest audit queue |
| Ingestion & DB Sink | Schema validation gate, PostgreSQL sink, ClickHouse OLAP sink, Worker health & backpressure |
| Telemetry Analytics | Live stream telemetry, Latency & backpressure, Violations & anomalies, Vehicle state & trip efficiency |
| Developer | The engineering pages: engine overview, producers & consumers, trips/stops/readings, topics & storage |

The menu lives in `src/lib/nav.ts`: add a sub-page there and create its `page.tsx`.

## Live and demo cards

Every card says where its numbers come from:

- **Live** (green dot): fed by the service – throughput, consumer groups and lag, partitions, Redis live
  state, PostgreSQL counts, trips, stops, the audit queue (speedometer against distance-over-time speed),
  validation rules and rejected frames, and the alerts in the bell.
- **Demo data** (amber): the service does not produce this yet – speed-rule configuration in Redis,
  AI traffic/weather enrichment, geofences, corridors, waypoints, Kalman filtering, ClickHouse, dispatch
  webhooks. Buttons on these cards answer "Not connected yet". Replace them as the backend adds each feature.

## How it talks to the service

The browser only calls the dashboard. `src/app/api/[...path]/route.ts` forwards every `/api/*`
request to `GPS_API_URL` (default `http://localhost:8080`), so there is no CORS setup and the
address can change per environment without a rebuild. One shared poll of `/api/engine/overview`
(every 2 s, paused while the tab is hidden) feeds the header and most pages.

## Layout of the code

```
src/app/                 one folder per section and sub-page; layout.tsx = header + left menu
src/app/api/             the proxy to the GPS service
src/components/shell/    header, search, alerts, appearance, tabs, left menu
src/components/ops/      cards, tiles, tags, charts used by the operational pages
src/components/          pieces used by the Developer pages
src/lib/                 API types (mirror the Java records), navigation, polling, alerts, formatting
```
