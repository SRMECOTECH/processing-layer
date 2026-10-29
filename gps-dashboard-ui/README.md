# PulseStream IoT console

Operations console for the GPS streaming platform, built with Next.js 16 and React 19, in the same look as
Workspace Studio (below): a rounded lilac frame on white, borderless white cards, black pill buttons and
pastel accents.

No UI packages: the only runtime dependencies are `next`, `react` and `react-dom`. Styling is plain CSS (the
console's `globals.css`) and CSS Modules (`*.module.css` next to a component); icons are inline SVG in
`src/components/icons.tsx`; motion is CSS animations plus the Web Animations API (`src/components/slide-marker.tsx`
slides the active pill or circle). Nothing is loaded from another host at run time.

Layout: a black capsule rail on the left holds the fixed sections (Live, Processing, Alerts, Storage,
Analytics, Notifications, Developer) with alert and unread counts; the lavender circle and notch slide to the
open section and hovering shows its name. Next to it, a white menu card for the section: groups of pages,
and under a processor its sub-pages, with a black pill that slides to the open page. Above the page, a bar
on the canvas with search (Ctrl K or /), the live Kafka rate (click to pause live updates), notifications,
appearance and the user. The menu collapses to icons and becomes a drawer (with the sections) on narrow
screens.

Motion: on load the rail grows down and its icons fade in; on every page the blocks blur in one after another
and the title word by word; numbers count up from 80% of their value; progress bars grow in. Cards lift 2px
on hover, so dialogs are rendered at the document root (a portal).

Appearance (remembered per browser): light, dark or system theme; lavender, navy, sky, indigo, teal or violet
accent; lilac, mist or warm canvas; comfortable or compact density.

## Run it

The GPS service must be running on http://localhost:8080 (see the main README). Sign in with **abc / abc**
until you set your own account (next section).

```powershell
npm install
npm run dev        # http://localhost:3000
```

Production build: `npm run build` then `npm start`. With Docker, `docker compose up -d --build`
from the project root starts it as the `dashboard` service on port 3000.

## Sign-in

Every page, `/studio` and every `/api` call need a signed-in session; without one a page goes to `/login`
(and returns to the page asked for after signing in) and `/api` answers 401.

| Setting (environment) | Default | What it does |
|---|---|---|
| `DASHBOARD_USERNAME` | `abc` | The account's user name |
| `DASHBOARD_PASSWORD` | `abc` | Its password. **Change it before anyone else can reach the dashboard.** |
| `AUTH_SECRET` | derived from the account | Signs the session cookie; set any long random text. Changing it (or the password) signs everyone out |
| `AUTH_COOKIE_SECURE` | on behind HTTPS | `true` forces the Secure cookie flag, `false` turns it off |

With Docker Compose put `DASHBOARD_USERNAME`, `DASHBOARD_PASSWORD` and `DASHBOARD_AUTH_SECRET` in `.env`
(see `.env.example`).

How it works, without an auth package: `src/lib/session.ts` signs `{user, expires}` with HMAC-SHA256 (Web Crypto)
into an http-only, SameSite=Lax cookie; `src/proxy.ts` checks it on every request and the API forwarder checks
it again; `src/lib/auth-actions.ts` holds the sign-in and sign-out server actions. A session lasts 12 hours, or
30 days with "Keep me signed in". After 5 wrong passwords from one address, sign-in waits a minute. Sign out
from the user menu in the top bar, or the last icon of the Studio sidebar.

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
src/app/(console)/       one folder per section and page; layout.tsx = frame, rail, menu, top bar
src/app/(studio)/        Workspace Studio: its own root layout, colour tokens (light and dark) and Inter font
src/app/(auth)/          the sign-in page (/login), its own root layout on the Studio tokens
src/app/api/             the proxy to the GPS service
src/proxy.ts             the sign-in gate in front of every request
src/components/shell/    rail of sections, section menu, top bar, search palette, popovers
src/components/studio/   Workspace Studio shell, motion kit, SVG art and its four pages (each with a CSS Module)
src/components/auth/     the sign-in form
src/components/icons.tsx the line icons (inline SVG); add one by copying its shapes from lucide.dev
src/components/ops/      cards, tiles, tags, charts, module header/diagram, rule editor, map
src/components/          pieces used by the Developer pages
src/lib/                 API types, navigation, modules, polling, alerts, notifications, examples, formatting
```

## Workspace Studio (`/studio`)

A separate single-page workspace (Dashboard, Profile, Saved, Cloud) in the soft lilac design the console now
follows: floating black sidebar whose active circle and notch slide between items, cards that blur in one
after another, numbers that count up, bars and the storage ring that draw in. Plain React with CSS Modules;
the 3D flower, coil and brain thumbnails are SVG and the avatars are initials, so nothing comes from another
host. All content is sample data.

Light and dark: the moon / sun icon at the bottom of the sidebar switches the theme (a short cross-fade). The
choice is saved with the console's appearance setting, so both open in the same theme. Colours live as tokens
in `src/app/(studio)/studio.css` (`:root` and `:root[data-theme="dark"]`); components only use the tokens.

It is its own root layout (route group `(studio)`), so its stylesheet never loads on console pages and the
console's CSS never reaches it. Moving between the console and `/studio` is a full page load. The bottom icon of
the console rail opens it; the grid icon in its sidebar comes back.
