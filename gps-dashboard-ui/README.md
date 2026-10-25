# PulseStream IoT console

Operations console for the GPS streaming platform, built with Next.js 16 and React 19, in the same look as
Workspace Studio (below): a rounded lilac frame on white, white cards with no borders, black pill buttons and
pastel accents.

Layout: a black capsule rail on the left with one icon per section (the lavender circle and notch slide to
the open one, hovering shows its name), a white menu card with that section's sub-pages (a black pill slides
to the open page), and the page itself with a top bar for the menu toggle, search, live Kafka rate, alerts,
appearance and user. Each sub-page holds one or two cards, so sections grow by adding sub-pages rather than
crowding one page. Background (lilac / mist / warm) and accent (lavender / sky) can be changed from the
sliders icon and are remembered per browser.

Motion: on load the rail grows down and its icons fade in; on every page the blocks blur in one after
another in reading order and the title word by word; numbers count up from 80% of their value and progress
bars grow in. Cards lift 2px on hover, so dialogs are rendered at the document root (a portal).

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
| Stream Processors | Pipeline overview (with Empty DB), Vehicle tracking (cache window), Vehicle state, Speed violation (rules editor), Stoppage & ignition, Nested geofencing, Waypoints & corridors, Kalman filter distance |
| Business Rules & Alerts | Over-speed verification, Trip detection master, Route deviation, Dispatch & escalations, Ingest audit queue |
| Ingestion & DB Sink | Schema validation gate, PostgreSQL sink, ClickHouse OLAP sink, Worker health & backpressure |
| Telemetry Analytics | Live stream telemetry, Latency & backpressure, Violations & anomalies, Vehicle state & trip efficiency |
| Developer | The engineering pages: engine overview, producers & consumers, trips/stops/readings, topics & storage |

The menu lives in `src/lib/nav.ts` (each section also has its rail icon there): add a sub-page there and
create its `page.tsx`.

## Live and demo cards

Every card says where its numbers come from:

- **Live** (green dot): fed by the service – throughput, consumer groups and lag, partitions, Redis live
  state, the per-vehicle cache (tracking window), speed rules (editable, stored in Redis), over-speed and
  speedometer-mismatch events, stoppages with their traffic/weather verdicts, parking, idling, ignition,
  Kalman against Traccar distance, PostgreSQL counts, trips, stops, the audit queue, validation rules,
  rejected frames, the escalation feed and the alerts in the bell.
- **Demo data** (amber): the service does not produce this yet – geofences, corridors, waypoints,
  route deviation, ClickHouse, dispatch webhooks. Buttons on these cards answer "Not connected yet".

## How it talks to the service

The browser only calls the dashboard. `src/app/api/[...path]/route.ts` forwards every `/api/*`
request to `GPS_API_URL` (default `http://localhost:8080`), so there is no CORS setup and the
address can change per environment without a rebuild. One shared poll of `/api/engine/overview`
(every 2 s, paused while the tab is hidden) feeds the header and most pages.

## Layout of the code

```
src/app/(console)/       one folder per section and sub-page; layout.tsx = frame, rail, menu, top bar
src/app/(studio)/        Workspace Studio: its own root layout, Tailwind stylesheet and Inter font
src/app/api/             the proxy to the GPS service
src/components/shell/    rail, sub-page menu, top bar (search, alerts, appearance)
src/components/ops/      cards, tiles, tags, charts used by the operational pages
src/components/studio/   Workspace Studio shell, motion kit, SVG art and its four pages
src/components/          pieces used by the Developer pages
src/lib/                 API types (mirror the Java records), navigation, polling, alerts, formatting
```

## Workspace Studio (`/studio`)

A separate single-page workspace (Dashboard, Profile, Saved, Cloud) in a soft lilac design: floating black
sidebar whose active circle and notch slide between items, cards that blur in one after another, numbers
that count up, bars and the storage ring that draw in. Built with Tailwind CSS v4, framer-motion and
lucide-react; the 3D flower, coil and brain thumbnails are SVG. All content is sample data.

It is its own root layout (route group `(studio)`), so its Tailwind stylesheet never loads on console
pages and the console's CSS never reaches it; Tailwind scans only the studio files. Moving between the
console and `/studio` is a full page load. The log-out icon in the studio sidebar goes back to the console.
