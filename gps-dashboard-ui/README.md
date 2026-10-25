# PulseStream IoT console

Operations console for the GPS streaming platform, built with Next.js 16 and React 19.

Layout: one top bar with the fixed sections (Live, Processing, Alerts, Storage, Analytics, Notifications,
Developer), search (Ctrl K or /), the live Kafka rate (click to pause live updates), notifications and an
appearance menu. Each section has its own left menu: groups of pages, and under a processor its sub-pages.
The menu collapses to an icon rail and becomes a drawer on narrow screens.

Appearance (remembered per browser): light, dark or system theme; navy, sky, indigo, teal or violet accent;
off-white, white or warm page; comfortable or compact density.

## Run it

The GPS service must be running on http://localhost:8080 (see the main README).

```powershell
npm install
npm run dev        # http://localhost:3000
```

Production build: `npm run build` then `npm start`. With Docker, `docker compose up -d --build`
from the project root starts it as the `dashboard` service on port 3000.

## Sections

| Section | Left menu |
|---|---|
| Live | Fleet overview, Live map, Vehicle list, Vehicle inspector, Throughput, Event stream, Fleet simulator |
| Processing | Pipeline overview, Consumer groups, and the processors after the Kafka output topic, each with sub-pages: Speed violation, Vehicle state, Stoppage & ignition, Geofencing, Waypoints, Cumulative distance |
| Alerts | All alerts, Escalations, and the business logic: Over-speed, Trip detection, Route deviation |
| Storage | Ingestion overview, Schema validation, Rejected frames, PostgreSQL, ClickHouse, Workers & backpressure |
| Analytics | Live telemetry, Latency & backpressure, Violations & anomalies, Trip efficiency |
| Notifications | All notifications, Channels, Routing rules, Delivery log, Quiet hours & digest |
| Developer | Engine overview, Producers & consumers, Topics & storage, Trips/stops/readings, API reference, Demo tools, Service settings, Module registry |

The menu lives in `src/lib/nav.ts`: add a page there and create its `page.tsx`. Pages of the earlier
layout (`/stream/*`, `/rules/*`, `/ingestion/*`) redirect to their new place (`next.config.ts`).

## Modules

`src/lib/modules.ts` describes every module after the Kafka output topic: the processors, the business
logic and the stores, with status (live, partly built, planned), consumer group, topic, Redis keys, where
the configuration lives, what it emits and its logic step by step. The pipeline diagram, the module page
headers and the "How it works" cards read from it. When you build a planned module, update its entry,
replace the example data on its pages with API calls and change its `feed` in the menu to `live`.

## Live and example cards

Every card says where its numbers come from:

- **Live** (green dot): fed by the service – throughput, consumer groups and lag, Redis live state, the
  vehicle inspector, speed rules (editable, stored in Redis), over-speed episodes and sensor mismatches,
  stoppages with their traffic/weather verdicts, parking, idling, ignition, Kalman against Traccar distance,
  trips, stops, the master lookup, PostgreSQL counts, validation, rejected frames, events and notifications.
- **Example data** (amber): the module is not built yet – geofencing, waypoints, route deviation, the
  over-speed alert policy, ClickHouse and notification delivery. Their example numbers live in
  `src/lib/examples.ts`; buttons on these cards answer "Not connected yet". The menu marks them Planned.

## How it talks to the service

The browser only calls the dashboard. `src/app/api/[...path]/route.ts` forwards every `/api/*`
request to `GPS_API_URL` (default `http://localhost:8080`), so there is no CORS setup and the
address can change per environment without a rebuild. One shared poll of `/api/engine/overview`
(every 2 s, paused while the tab is hidden or live updates are paused) feeds the header, the menu's
system status and most pages.

## Layout of the code

```
src/app/                 one folder per section and page; layout.tsx = top bar + left menu
src/app/api/             the proxy to the GPS service
src/components/shell/    top bar, section nav, left menu, search palette, popovers
src/components/ops/      cards, tiles, tags, charts, module header/diagram, rule editor, map
src/components/          pieces used by the Developer pages
src/lib/                 API types, navigation, modules, polling, alerts, notifications, examples, formatting
```
