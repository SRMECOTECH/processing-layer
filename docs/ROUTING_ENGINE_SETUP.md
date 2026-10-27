# Routing Engine Setup – GraphHopper on the India map 🗺️

How to install, run, check and maintain the **road routing engine** that the route service (`gps-route-service`)
uses for route planning, recalculation and deviation checks. What we build on top of it, and in which order:
[ROUTE_INTELLIGENCE_PLAN.md](ROUTE_INTELLIGENCE_PLAN.md). Running it on the old HP laptop as a home server:
[HOME_SERVER_SETUP.md](HOME_SERVER_SETUP.md) (import here, serve there).

> **Ek line mein:** GraphHopper ek Java server hai jo India ka poora road map (OpenStreetMap) padh ke ek graph
> banata hai, phir kisi bhi do point ke beech truck ka sabse achha raasta milliseconds mein bata deta hai –
> Dijkstra, A*, bidirectional, sab algorithms ke saath.

---

## 1. Why GraphHopper (and not OSRM or Valhalla)

| | **GraphHopper 11.1** ✅ chosen | Valhalla | OSRM |
|---|---|---|---|
| Language | Java – same as our services | C++ | C++ |
| Algorithm per request | **Dijkstra, bidirectional Dijkstra, A\*, bidirectional A\*, alternatives** + CH + Landmarks | A\* only (fixed) | CH or MLD only (fixed) |
| Change costs per request | Yes – custom model (avoid tolls, avoid a closed road, truck limits) | Yes (costing options) | No – re-process the map |
| Map matching (snap a GPS trace to roads) | Built in (`/match`, Hidden Markov Model + Viterbi) | Built in | Built in |
| Truck rules | hgv access, max height/width/weight from OSM | Strongest truck model | Needs a custom Lua profile |
| India import on a laptop | Reading India 7 min (measured); with CH + landmarks about an hour; 7 GB heap | Similar | Heavy pre-processing, re-run for every profile change |
| Install | One jar from Maven Central | Docker image + tile build | Three pre-processing steps |

**Why it fits us:** you asked to use and compare **A\*, Dijkstra and the rest** – GraphHopper is the only one of the three
that lets every request pick its algorithm. It is plain Java (we can read and debug it like our own code), the map
matching we need for deviation is inside the same jar, and a custom model per request turns "a road is closed,
recalculate around it" into one API call instead of a new import.

Valhalla stays the fallback if we later need its time-dependent truck costing; the route service talks to the engine
through one class (`GraphHopperClient`), so swapping engines touches one place.

---

## 2. What is installed

```
infrastructure/routing/graphhopper/
  Dockerfile               eclipse-temurin:21-jre + graphhopper-web-11.1.jar (Maven Central, SHA-256 pinned)
  config.yml               profiles, CH/LM preparation, encoded values, memory, guards  (every line explained)
  custom_models/
    truck_india.json       our truck profile: GraphHopper's truck rules + Indian speed caps (80/60 km/h)
  start-graphhopper.sh     first start: download map (MD5 checked) → import → serve; later starts: serve

docker-compose.yml         services graphhopper (:8989) and route-service (:8090), profile "routing"
gps-route-service/         the new Spring Boot app that calls the engine (see section 6)
```

**Profiles** (what is being routed):

| Profile | Used for | Speed mode (CH) | Hybrid mode (LM) |
|---|---|---|---|
| `truck` | our fleet – goods carriers | ✅ | ✅ |
| `car` | light vehicles, and the "what would a car do" reference | ✅ | – |

---

## 3. What you need

| | India (`asia/india`) | One zone (e.g. `asia/india/eastern-zone`) |
|---|---|---|
| Download | 1.7 GB (Geofabrik, updated daily) | 250 MB |
| Docker memory | 12 GB VM (Docker Desktop default on a 24 GB laptop); import heap 7 GB | 4 GB |
| Disk (Docker volume) | ~6.6 GB: map 1.6 + graph ≈ 5 (base 2.1 and truck landmarks 1.2 measured, CH ≈ 1.5–2 estimated) | under 2 GB |
| First start | download ~4 min + reading 7 min (measured) + landmarks and CH (about 45 min expected, not yet measured end to end) | ~10 min |
| Every later start | about a minute (loads the imported graph) | seconds |

Check Docker's memory: `docker info --format '{{.MemTotal}}'`. Less than 10 GB? Either give Docker more
(`%UserProfile%\.wslconfig` → `[wsl2]` `memory=14GB`, then `wsl --shutdown`) or start with one zone (section 7).

---

## 4. Install and start (Docker – recommended)

From the project root (PowerShell):

```powershell
# 1. Build the two images (engine + route service)
docker compose --profile routing build graphhopper route-service

# 2. Start them. The rest of the platform is not touched.
docker compose --profile routing up -d graphhopper route-service

# 3. Watch the first start: download, MD5 check, import, "Serving"
docker compose logs -f graphhopper
```

What you see on the first start:

```
[routing] Downloading https://download.geofabrik.de/asia/india-latest.osm.pbf
[routing] Map saved as /data/maps/asia-india.osm.pbf (1.6G)
[routing] Importing /data/maps/asia-india.osm.pbf - the API stays down until this finishes
   ... pass1 / pass2 (read roads) → subnetworks → CH truck → CH car → LM truck ...
[routing] Import finished in NN min
[routing] Serving /data/graph-cache/asia-india on :8989
```

The import writes to `asia-india.importing` and is renamed only when it finished – stop it halfway and the next start
simply imports again, it never serves half a graph.

**Always on:** to start the routing services with every `docker compose up -d`, add one line to `.env`:

```
COMPOSE_PROFILES=routing
```

---

## 5. Check that it works

| Check | Where | Expect |
|---|---|---|
| Engine alive | http://localhost:8989/health | `OK` |
| Map and profiles | http://localhost:8989/info | `bbox` around India, profiles `truck` and `car`, `data_date` |
| Map UI (click two points) | http://localhost:8989/maps/ | route drawn, choose `truck` / `car` top left |
| Route service + engine | http://localhost:8090/actuator/health | `routingEngine: UP` |
| Engine through our API | http://localhost:8090/api/routing/engine | `status: UP`, the 7 algorithms |

**A truck route** (Jamshedpur → Kolkata), straight from the engine:

```powershell
curl.exe "http://localhost:8989/route?point=22.8046,86.2029&point=22.5726,88.3639&profile=truck&instructions=false&calc_points=false"
```

**The same through the route service** – kilometres, minutes, toll km, km per road class, points for a map:

```powershell
curl.exe "http://localhost:8090/api/routing/route?from=22.8046,86.2029&to=22.5726,88.3639&profile=truck&instructions=false"
```

**Every algorithm on one trip** (same route, different work):

```powershell
curl.exe "http://localhost:8090/api/routing/compare?from=22.8046,86.2029&to=23.3441,85.3096&profile=truck"
```

---

## 6. The algorithms – which one, when

GraphHopper searches the road graph in one of three modes. **Every algorithm returns the same best route** for a
profile; they differ in how much of India they look at (`visitedNodes`) and so in speed.

| `algorithm=` (route service) | GraphHopper request | Prepared at import | Use it for |
|---|---|---|---|
| `CONTRACTION_HIERARCHIES` (default) | nothing extra | CH shortcuts | Planning and **recalculation** – milliseconds across India |
| `LANDMARKS_ASTAR` | `ch.disable=true` | 16 landmarks (truck) | Recalculation **with a custom model** (avoid tolls / closed road) |
| `ASTAR_BIDIRECTIONAL` | `ch.disable=true&lm.disable=true&algorithm=astarbi` | nothing | Ad-hoc custom models on `car`, comparisons |
| `ASTAR` | `…&algorithm=astar` | nothing | Teaching / comparison |
| `DIJKSTRA_BIDIRECTIONAL` | `…&algorithm=dijkstrabi` | nothing | Teaching / comparison |
| `DIJKSTRA` | `…&algorithm=dijkstra` | nothing | The exact reference; slowest |
| `ALTERNATIVES` | `algorithm=alternative_route` | CH | Up to 3 different routes – dispatcher choices, **accepted corridors** for deviation |

**[Teacher's note] How they differ, in one picture each**

- **Dijkstra** – a circle of "settled" junctions grows around the start until it touches the destination. Exact, but
  for Jamshedpur → Kolkata it settles every junction within ~250 km in every direction.
- **Bidirectional Dijkstra** – two circles, from start and destination, until they meet: roughly half the area.
- **A\*** – Dijkstra plus "how far is the goal in a straight line?", so the circle becomes an egg pointing at the
  destination. Same answer, far fewer junctions.
- **A\* with landmarks (LM / ALT)** – a much better estimate than the straight line, from road distances to 16
  landmarks measured at import. Faster still, and it still accepts a custom model per request.
- **Contraction Hierarchies (CH)** – at import, unimportant junctions are "contracted" and shortcuts added; a query is
  a bidirectional Dijkstra that only climbs to more important roads. A few hundred junctions for any trip in India.
  Price: costs are fixed at import, so per-request custom models need LM or flexible mode.

Guards in `config.yml`: flexible searches stop at 6,000,000 visited junctions or 60 s, so a Dijkstra from Kolkata to
Mumbai cannot eat the engine's memory – it answers "max visited nodes exceeded" instead. Use CH or LM for long trips.

### A custom model per request (LM): avoid tolls

```powershell
$body = @{
  profile = "truck"; "ch.disable" = $true
  points = @(@(86.2029, 22.8046), @(88.3639, 22.5726))      # [longitude, latitude] in POST bodies!
  custom_model = @{ priority = @(@{ if = "toll != NO"; multiply_by = "0.1" }) }
  instructions = $false
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri http://localhost:8989/route -ContentType application/json -Body $body |
  Select-Object -ExpandProperty paths | Select-Object distance, time
```

Custom models in a request may only make roads **worse** (multiply_by ≤ 1, lower speed) when LM is used – exactly
what "avoid" and "closed" need.

---

## 7. Quick try with a smaller region

Any Geofabrik region works. In `.env`:

```
GPS_ROUTING_MAP_REGION=asia/india/eastern-zone     # Jharkhand, West Bengal, Odisha, Bihar ...
```

Zones: `central-zone`, `eastern-zone`, `north-eastern-zone`, `northern-zone`, `southern-zone`, `western-zone`.
Each region gets its own map file and graph folder, so switching back to `asia/india` reuses the India graph.
Then `docker compose --profile routing up -d graphhopper` (it is recreated with the new region).

---

## 8. Refresh the map / change the profiles

OpenStreetMap improves every day; Geofabrik publishes a new India file daily. Refresh **monthly** (or after big road
openings). Changing `config.yml` profiles, `truck_india.json`, encoded values, CH/LM lists or ignored highways also
needs a new import.

```powershell
docker compose exec graphhopper sh -c "rm -f /data/maps/asia-india.osm.pbf && rm -rf /data/graph-cache/asia-india"
docker compose --profile routing up -d --build graphhopper     # rebuild picks up config changes
docker compose restart graphhopper                             # if nothing was rebuilt
```

The engine is down during the import (about an hour for India). Later, a second engine container can import while the
first one serves, then swap – not needed while the route service only plans.

**Wipe everything** (map + graph): `docker compose --profile routing down` then `docker volume rm gps-streaming-platform_gps-routing-data`.

---

## 9. Without Docker (Windows, Java 21)

Everything the container does, by hand – useful to debug the engine itself.

```powershell
mkdir D:\dev\routing; cd D:\dev\routing
curl.exe -LO https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.1/graphhopper-web-11.1.jar
curl.exe -L -o india.osm.pbf https://download.geofabrik.de/asia/india-latest.osm.pbf
Copy-Item -Recurse <project>\infrastructure\routing\graphhopper\custom_models .
# config.yml: copy it and change custom_models.directory to D:/dev/routing/custom_models

java -Xmx7g "-Ddw.graphhopper.datareader.file=india.osm.pbf" "-Ddw.graphhopper.graph.location=graph-cache" `
     -jar graphhopper-web-11.1.jar import config.yml
java -Xmx3g "-Ddw.graphhopper.graph.location=graph-cache" -jar graphhopper-web-11.1.jar server config.yml
```

Then run the route service from the IDE (`GpsRouteServiceApplication`) or
`.\mvnw -pl gps-route-service -am spring-boot:run` – it uses `http://localhost:8989` by default.

---

## 10. Troubleshooting

| You see | Meaning / fix |
|---|---|
| Route service: `Routing engine ... did not answer (ConnectException)` | Engine stopped or still importing – `docker compose logs graphhopper` |
| `Cannot find point 0: …` | No truck-usable road within reach of that point (sea, wrong lat/lon order, or a private compound) |
| `Connection between locations not found` | The points are on road islands not connected for trucks (e.g. hgv=no everywhere around) |
| `Cannot find LM preparation for the requested profile: 'car'` | `LANDMARKS_ASTAR` is truck only; use `ASTAR_BIDIRECTIONAL` for car |
| `... max visited nodes exceeded` | A flexible Dijkstra/A\* over a long distance hit the guard – use CH or LM |
| `Profiles do not match` / encoded value errors at start | `config.yml` changed after the import – section 8 |
| Container restarting during import, `exit=137` | Out of memory – give Docker more (section 3) or import one zone |
| `MD5 mismatch` | Geofabrik published a new file during the download – the next start downloads again |
| Landmarks / CH take ages, disk busy at hundreds of MB/s | The prepared tables are memory-mapped during the import – the operating system writes the same pages back again and again (we measured ~800 GB in 40 min, which wears an SSD). `start-graphhopper.sh` imports them as `RAM_STORE`; keep the two `graph.dataaccess.type.*` lines in `config.yml`. Check: `docker exec <container> sh -c 'grep write_bytes /proc/$(pgrep -o java)/io'` twice, a minute apart |
| Docker Desktop: "An unexpected error occurred … Secrets Engine … engine.sock" | A stale socket from a crash: quit Docker Desktop, rename `%LOCALAPPDATA%\docker-secrets-engine` to `…\docker-secrets-engine.old`, start again. **Never** "Reset to factory defaults" – it deletes all volumes |
