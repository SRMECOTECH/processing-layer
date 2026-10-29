# Home Server Setup – the old HP laptop as the routing server 🖥️

Turns the old HP laptop (Intel i5, 8 GB RAM) into an always-on server on the home network for the **routing engine
(GraphHopper, India)** and the **route service**, so the 24 GB laptop stays free for development. Files:
`infrastructure/home-server/`. Engine details: [ROUTING_ENGINE_SETUP.md](ROUTING_ENGINE_SETUP.md).

> **Ek line mein:** bhaari kaam (India ka map import, ~7 GB RAM, ek baar) naye laptop pe; halka kaam (roz routes
> serve karna, ~2 GB heap) purane HP pe, 24×7. Naya laptop HP ko `http://<hp-ip>:8989` pe call karta hai.

---

## 1. The split

```
 24 GB laptop  (development + the one-time import)             HP laptop  (Ubuntu Server, always on)
 ┌──────────────────────────────────────────────┐             ┌──────────────────────────────────────┐
 │ IDE, dashboard :3000, Kafka, Postgres, Redis,│   LAN       │ graphhopper   :8989  (serves India)   │
 │ gps-service :8080, simulator                 │ ──────────► │ route-service :8090                   │
 │                                              │  HTTP       │                                      │
 │ graphhopper import (monthly, ~1 h, 7 GB heap)│ ── graph ─► │ install-routing-graph.sh             │
 │ export-routing-graph.ps1                     │  (scp/USB)  │                                      │
 └──────────────────────────────────────────────┘             └──────────────────────────────────────┘
```

**Why import on the laptop and serve on the HP?** Building the India graph holds ~7 GB in the Java heap for about an
hour – it does not fit next to an operating system in 8 GB. Serving the finished graph is different: GraphHopper
memory-maps the files, needs a ~2 GB heap, and the operating system keeps the busy roads in the remaining RAM.
The graph files are portable between machines with the same GraphHopper image and `config.yml`.

---

## 2. How much space and memory

Measured on 2026-10-27 with the India extract of 2026-10-25 (19.2 M junctions, 24.0 M road segments).

| What | Size | Where |
|---|---|---|
| India map download (`asia-india.osm.pbf`) | **1.6 GB** | laptop only (needed to import, not to serve) |
| Base road graph (roads, geometry, names, spatial index) | **2.1 GB** measured | both |
| Truck landmarks (A\* with landmarks) | **1.2 GB** measured (19.2 M × 64 bytes) | both |
| Contraction Hierarchies, truck + car | **~1.5–2 GB** estimated (not yet measured: the import was stopped before CH) | both |
| **Imported graph, total** | **≈ 5 GB** | both |
| Docker images (graphhopper 638 MB + route-service 498 MB, sharing a 461 MB Java base) | **≈ 0.7 GB** | both |
| Export file (`asia-india-graph.tar`, temporary) | ≈ 5 GB | laptop → HP, delete after installing |

| Machine | Disk to keep free | RAM |
|---|---|---|
| **24 GB laptop** (imports) | **~13 GB** while importing + exporting (map 1.6 + graph 5 + tar 5 + images 0.7); ~7.5 GB after the tar is deleted | ~7 GB Java heap for ~1 h, once a month. Easiest with the platform stopped |
| **HP** (serves) | **~12 GB** while installing (tar 5 + graph 5 + images 0.7 + Ubuntu updates); ~6 GB after. 25 GB+ free recommended | see the budget below |

**HP memory budget (8 GB):**

| | RAM |
|---|---|
| Ubuntu Server + Docker | ~0.6 GB |
| GraphHopper heap (`-Xmx2g`) | 2 GB |
| route-service (`-Xmx384m`) | ~0.5 GB |
| Page cache for the graph (the busy parts of the 5 GB) | the rest, ~4 GB – more = faster |
| Later: PostgreSQL + Redis | ~0.5 GB |
| Later: Kafka | ~1 GB – tight; keep Kafka on the laptop until the HP is measured under load |

**SSD or HDD matters more than RAM here.** A memory-mapped graph is read at random; from an SSD a cold route takes
milliseconds, from a spinning disk it can take seconds until the graph is cached. Check with `lsblk -d -o NAME,ROTA,SIZE`
(`ROTA 1` = HDD). A cheap SATA SSD is the best upgrade for this laptop; a second RAM stick (16 GB) the next one.

---

## 3. Steps

### 3.1 Look at the HP

On the HP (any Linux live USB, or Windows: Task Manager → Performance) note: CPU, RAM, **SSD or HDD**, disk size, and
whether Windows on it must be kept. Those decide the install (erase vs dual boot) and how much it can run.

### 3.2 Install Ubuntu Server 24.04 LTS

1. On the laptop: download Ubuntu Server 24.04 LTS (ubuntu.com/download/server) and write it to a USB stick with Rufus.
2. Boot the HP from the stick (F9 boot menu on most HPs). Choose **"Install OpenSSH server"** during the install.
   Erase the disk, or install next to Windows if it must stay (needs ~40 GB free for Ubuntu).
3. Give it a **fixed address**: in the router's DHCP settings reserve an IP for the HP's MAC address (e.g. 192.168.1.50).
   That survives reinstalls and needs nothing on the HP.

### 3.3 Prepare the server (once)

From the laptop: `ssh <user>@192.168.1.50`, then on the HP:

```bash
git clone <repository-url> processing-layer && cd processing-layer
sudo sh infrastructure/home-server/prepare-ubuntu-server.sh 192.168.1.0/24     # Docker, lid closed = keeps running, firewall
sudo reboot
```

### 3.4 Import on the laptop, copy to the HP

On the laptop (PowerShell, project root) – the import once, then the export:

```powershell
docker compose --profile routing up -d --no-deps graphhopper      # first time: download + import, ~1 h
docker compose logs -f graphhopper                                 # wait for "[routing] Serving ..."
.\infrastructure\home-server\export-routing-graph.ps1             # → D:\dev\routing-export\asia-india-graph.tar
scp D:\dev\routing-export\asia-india-graph.tar* <user>@192.168.1.50:~/
docker compose --profile routing stop graphhopper                  # the laptop does not need to serve any more
```

### 3.5 Serve on the HP

```bash
cd ~/processing-layer
docker compose -f infrastructure/home-server/docker-compose.yml up -d --build
sh infrastructure/home-server/install-routing-graph.sh ~/asia-india-graph.tar
rm ~/asia-india-graph.tar*
```

Until a graph is installed the engine waits and says so in its log (`GH_IMPORT=never`) – it never tries to import
India on 8 GB.

### 3.6 Check from the laptop

| Check | Expect |
|---|---|
| http://192.168.1.50:8989/health | `OK` |
| http://192.168.1.50:8989/maps/ | the map UI, routes on India |
| http://192.168.1.50:8090/api/routing/engine | `status: UP` |
| `curl.exe "http://192.168.1.50:8090/api/routing/route?from=22.8046,86.2029&to=22.5726,88.3639"` | a truck route |

### 3.7 Point the laptop at the HP

Either call the HP's route service directly (`http://192.168.1.50:8090`), or keep a route service on the laptop and
point it at the HP's engine – in `.env`:

```
GPS_ROUTING_ENGINE_URL=http://192.168.1.50:8989
```

then `docker compose --profile routing up -d route-service` (only that service; no local graphhopper).

### 3.8 From outside the house

Install **Tailscale** on the HP and the laptop (free for personal use). The laptop then reaches the HP as
`http://<hp-name>:8989` from anywhere, encrypted, with nothing opened on the router. Never add router port forwards for
8989 / 8090 – they have no login.

---

## 4. Monthly map refresh

On the laptop: delete the old map and graph in the volume, import again, export (section 3.4 and
[ROUTING_ENGINE_SETUP.md § 8](ROUTING_ENGINE_SETUP.md)). On the HP: `install-routing-graph.sh` – the old graph keeps
serving while the new one unpacks; one restart (about a minute) switches over.

---

## 5. What about OSRM and Valhalla on the HP?

Not needed: GraphHopper already covers routing, all the algorithms and GPS map matching. And they hit the same wall –
OSRM's India pre-processing also needs far more than 8 GB. If one is ever wanted for comparison, use the same pattern:
pre-process on the laptop, copy the result, only serve on the HP – and run one engine at a time there.

---

## 6. Troubleshooting

| You see | Meaning / fix |
|---|---|
| Engine log: `No graph for asia/india yet and GH_IMPORT=never` | Install a graph (3.4, 3.5) |
| `Profiles do not match` / encoded value errors after installing | The HP runs a different `config.yml` or image than the laptop that imported – same repository commit on both, rebuild |
| Routes take seconds at first, then fast | Graph not in memory yet – normal after a restart, worse on an HDD |
| Laptop cannot reach 192.168.1.50 | Same Wi-Fi/LAN? `ping 192.168.1.50`; router "AP isolation" off; HP's address reserved? |
| HP sleeps with the lid closed | `prepare-ubuntu-server.sh` not run, or no reboot after it |
