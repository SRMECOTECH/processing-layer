# Engine and Docker Guide

How the platform runs in **Docker**, how to read the **engine dashboard**, what lives in **Redis**, and how to **filter the logs**.
Written for someone who has just learnt Docker. Read [LEARNING_GUIDE.md](LEARNING_GUIDE.md) first for Kafka and the modules.

Tags: **[Teacher's note]** explanation or opinion · **[Illustrative]** simplified example · **[In our code]** exact file.

---

## 1. Docker in our project

### 1.1 Desi analogy: tiffin boxes

Your laptop is a big kitchen. Without Docker, every dish (Kafka, PostgreSQL, Redis, our service) is cooked on the same stove, with ingredients mixed on one shelf. A wrong Java version or a port clash spoils another dish.

Docker gives every dish its own **tiffin box**:
- **Image** = the recipe card, plus all ingredients packed in a sealed box (`postgres:14.22`, `apache/kafka:4.3.1`, `redis/redis-stack`, our `gps-streaming-service:local`).
- **Container** = a tiffin box actually opened and being eaten from: a running image.
- **Volume** = a steel container that outlives the tiffin. Throw the tiffin away (`docker compose down`) and the data in the volume stays.
- **Network** = the dabbawala route. Boxes in one compose project can reach each other **by name**: our service talks to `postgres:5432`, `kafka:9092`, `redis:6379`.
- **Port mapping** = a window from the kitchen to the street: `"5433:5432"` means "Windows `localhost:5433` → container port 5432".
- **docker-compose.yml** = the tiffin order list: which boxes, in which order, with which labels.

### 1.2 Our containers

| Service | Image | Inside the network | From Windows | Job |
|---|---|---|---|---|
| `postgres` | `postgres:14.22` | `postgres:5432` | `localhost:5433` | `gps_tracking` database |
| `kafka` | `apache/kafka:4.3.1` | `kafka:9092` | `localhost:29092` | the 3 topics |
| `redis` | `redis/redis-stack:7.4.0-v8` | `redis:6379` | `localhost:6380`, http://localhost:8001 | live positions + RedisInsight |
| `gps-service` | built from our `Dockerfile` | `gps-service:8080` | http://localhost:8080 | the Spring Boot engine |
| (smart_truck) | not a container | `host.docker.internal:3306` | `localhost:3306` | stays your Windows MySQL, read-only |

**[Teacher's note]** The Windows ports (5433, 29092, 6380) differ from the Windows installs (5432, 9092, 6379) on purpose: both setups can run at the same time without fighting over a port.

### 1.3 The Dockerfile: two stages

```
stage 1 "build":   eclipse-temurin:21-jdk + our source → ./mvnw package → gps-streaming-service.jar
stage 2 "runtime": eclipse-temurin:21-jre + only that jar → java -jar
```
- **Why two stages?** The final image carries no source code, no Maven and no JDK. It's smaller, and there's less inside that could have a security hole.
- `--mount=type=cache,target=/root/.m2` keeps downloaded libraries between builds, so a rebuild after a code change takes seconds.
- `USER gps`: the app doesn't run as root inside the container.
- `.dockerignore` keeps `local-secrets.properties`, `.env` and `target/` **out** of the image. Secrets must never be baked into an image.

### 1.4 How the passwords get in

```
local-secrets.properties ──create-docker-env.ps1──► .env ──docker compose──► container environment variables
     (git-ignored)                                (git-ignored)            SPRING_DATASOURCE_PASSWORD=...
```
Spring Boot reads environment variables as properties:
- `SPRING_DATASOURCE_URL` = `spring.datasource.url`
- `GPS_PROCESSING_FLOW` = `gps.processing-flow`

So the same jar runs on Windows and in Docker, with only the addresses changed.

### 1.5 Start-up order and health checks

`depends_on: condition: service_healthy` means "don't start `gps-service` until `postgres` answers `pg_isready` and `redis` answers `PING`". Kafka is marked `required: false`, because the direct flow runs without it.

### 1.6 Everyday commands

```powershell
docker compose up -d --build        # build our image, start everything in the background
docker compose ps                   # what is running, and is it healthy?
docker compose logs -f gps-service  # follow our service's log
docker compose restart gps-service  # restart only our service
docker compose down                 # stop and remove containers (volumes = data stay)
docker compose down -v              # ...and delete the data volumes too (fresh start)
docker compose exec postgres psql -U gps_app -d gps_tracking    # SQL inside the postgres container
docker compose exec redis redis-cli                             # Redis commands inside the redis container
```

### 1.7 Tricky questions

1. **"`localhost` inside a container is my laptop."** No. Inside a container, `localhost` is **that container**. To reach Windows, use `host.docker.internal`; to reach another container, use its service name.
2. **"`docker compose down` deletes my database."** Only `down -v` does. Named volumes survive `down`.
3. **"If I change Java code, the container picks it up."** No. The image holds the jar it was built with. Use `docker compose up -d --build` to rebuild.
4. **"The smart_truck login works on Windows, so it works from Docker."** Not necessarily. MySQL checks *where* you connect from. A user allowed only from `localhost` is refused from a container. The fix is a read-only MySQL user allowed from Docker; that's the smart_truck owner's decision.

> **Mental model:** *An image is a sealed tiffin, a container is an opened one, a volume is the steel box that outlives it, and compose is the order list that delivers all six together.*

---

## 2. The engine dashboard

### 2.1 Analogy: the railway control room

A control room shows every train (message) on a big board: which line it is on (topic/partition), which station handled it (producer/consumer), how many are waiting at each signal (**lag**), and you can look up one train by number (**IMEI filter**).

### 2.2 Reading each section

| Section | Read it like this | Healthy looks like |
|---|---|---|
| KPIs | frames in per second, devices reporting, stored positions, live vehicles, total lag, rejections | 75/s with 150 vehicles every 2 s; lag near 0 |
| Pipeline | every stage's messages per second, left to right | the same number all the way through (75 → 75 → 75) |
| Producers | P1, P2, P3: rate, sparkline, total, failures, devices, per partition | failures 0; P1 ≈ P2 + P3 |
| Topics | offsets per partition, kept vs ever written | "ever written" keeps rising |
| Consumers | state, consumer → partition, rate, eaten, load per partition, lag per partition | `Stable`, one consumer per partition, lag small |
| Devices | per-IMEI counts at every stage, partition, live position | a device's counts are equal across stages (minus its rejections) |

### 2.3 Where the numbers come from

- **Our counters** (`PipelineMetrics`, in memory): every publisher and listener calls `recordMessage(stage, imei, partition)` after handling a message successfully. They give messages per second, totals, per partition and per IMEI. They start at 0 when the service starts.
- **Kafka itself** (`KafkaTopologyInspector`, Admin API): topic offsets, group state, members and assignments, committed offsets → **lag**. This is the same as `kafka-consumer-groups --describe`.
- **PostgreSQL:** `count(*)` of `gps_position`, cached for 5 s.
- **Redis:** the number of devices in `gps:live:positions`.

**[Teacher's note]** Lag comes from Kafka, not from our counters. If our service crashed, our counters would vanish, but Kafka would still know exactly how far each group got.

### 2.4 Diagnosing with the dashboard

| You see | It means | Look at |
|---|---|---|
| P1 at 75/s, processing at 75/s, storage at 0/s, storage lag growing | the storage consumer is failing | `failures` on storage; log `stage=position-storage`; `/actuator/health` → `db` |
| Live location lag growing, storage fine | Redis problem | health → `redis` |
| P3 suddenly high | many frames rejected | log `stage=rejected-frame-logger`: the reasons |
| One partition's load much higher than the others | a few busy IMEIs hash to it | device table: sort by partition |
| Group state `PreparingRebalance` | a consumer joined or left | normally settles in seconds |

### 2.5 Tricky questions

1. **"Live location ate more than storage."** That happens when a group is new: it starts from the oldest message (`auto-offset-reset: earliest`) and eats the history first.
2. **"Lag 150 means something is broken."** No. 150 vehicles send in one burst every 2 s. Catching the dashboard mid-burst shows up to one round (150) waiting. Worry only when lag **keeps growing**.
3. **"Why is IMEI X always on p2?"** `partition = murmur2(IMEI) % 3`. The device detail shows it, and that's what keeps one device's frames in order.

> **Mental model:** *Our counters tell you how fast each station works; Kafka's lag tells you how many trains are waiting for it.*

---

## 3. Redis

### 3.1 Why Redis at all?

"Where is truck X **right now**?" could be answered from `gps_position`, but that table grows by millions of rows a day. Redis keeps **one small hash per vehicle**, always the newest, in memory: answering takes microseconds and never touches PostgreSQL.

| Key | Written by | Example use |
|---|---|---|
| `gps:live:vehicle:{imei}` | consumer C3 (`LiveLocationService`) | live map, "last seen" |
| `gps:live:positions` (GEO) | consumer C3 | `GEOSEARCH ... BYRADIUS 5 km`: trucks near a plant, the **base for geofencing** |
| `gps:engine:stage:*` | `EngineReportingJobs` every 5 s | browse engine load in RedisInsight |

**Rules in our code:**
- An older reading never overwrites a newer one: it checks `arrivedOutOfOrder` and compares `recordedAt`.
- Nothing is saved to disk (`--save "" --appendonly no`). After a Redis restart, every vehicle reappears with its next reading, within 2 seconds.
- `maxmemory-policy noeviction`: if memory is full, Redis refuses writes rather than silently dropping vehicles.

### 3.2 Try it in RedisInsight (http://localhost:8001)

1. Filter keys by `gps:live:vehicle:*` and open one: vehicle number, company, lat/lon, speed, status.
2. Open the Workbench and run: `GEOSEARCH gps:live:positions FROMLONLAT 86.2 22.8 BYRADIUS 10 km ASC WITHDIST COUNT 10`. These are the 10 trucks nearest a point in Jamshedpur.
3. Open `gps:engine:stage:position-storage`: the storage consumer's load.

> **Mental model:** *PostgreSQL is the diary of every reading; Redis is the whiteboard showing only where everyone is right now.*

---

## 4. Classified logs

Every log line written while a message is handled carries four labels (the MDC, set by `PipelineLogContext`):
```
WARN [stage=rejected-frame-logger imei=0861000000000003 p=0 o=0] ... Frame rejected: Latitude 95.0 is outside -90..90
      └ which consumer/producer   └ which device          └ partition and offset in Kafka
```

| Question | Command |
|---|---|
| Everything about one device | `docker compose logs gps-service \| Select-String "imei=0861000000000003"` |
| Everything one consumer said | `... \| Select-String "stage=position-storage"` |
| Only problems | `... \| Select-String " WARN \| ERROR "` |
| Engine load every 30 s | `... \| Select-String "ENGINE"` |
| Per-message detail | start with `LOGGING_LEVEL_COM_SRM_GPS=DEBUG` |

With partition and offset you can read **exactly that message** with Kafka's console consumer:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 `
  --topic gps.raw-device-frames --partition 0 --offset N --max-messages 1 --property print.key=true
```

**[Teacher's note]** Lines from start-up and background jobs show `stage=none`. That's correct: they don't belong to any one message.

---

## 5. What's still teaching-grade

| Area | Now | Production |
|---|---|---|
| Counters | in memory, reset on restart | Micrometer + Prometheus + Grafana (long history, alerts) |
| Logs | text with MDC labels | JSON logs into Loki or Elasticsearch, searchable by `imei` |
| Redis | one container, no persistence | Redis with replicas; live store rebuilt from Kafka if lost |
| Docker | one machine, compose | Kubernetes, managed Kafka/PostgreSQL/Redis |
| Fleet | simulator replaying smart_truck | the real device parser publishing to `gps.raw-device-frames` |
