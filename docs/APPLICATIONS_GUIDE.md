# Applications Guide – kaun kaun se app hain, kaise chalayein, main files kaunsi 🛠️

> **Ye file kis liye?** README poora project samjhata hai. Ye file ek **haath ki register** hai: humare system mein kitne alag alag applications chalte hain, har ek ko **kaise start / stop / check** karna hai, aur har ek ki **main files** kaunsi hain. Kuch toota to seedha yahan aao.

**Sabse latest docs (2026-10-19 tak):**

| File | Kya hai | Bhasha |
|---|---|---|
| `README.md` | Poora project A to Z – flow, topics, groups, logic, DB, roadmap | Desi Hinglish |
| `docs/APPLICATIONS_GUIDE.md` (ye wali) | Har application: start, stop, check, main files | Desi Hinglish |
| `docs/LEARNING_GUIDE.md` | Kafka zero se, har module ki gehri padhai, interview Qs | English |
| `docs/ENGINE_AND_DOCKER_GUIDE.md` | Docker, dashboard, Redis, logs ki detail | English |

---

## 1. Ek nazar mein – poori dukaan 🏪

Socho humari ek **courier company** hai:

- **smart_truck MySQL** = purana godown. Wahan se sirf maal **dekhte** hain, uthake kuch rakhte nahi (READ-ONLY).
- **Kafka** = courier ka **sorting hub**. Parcel (GPS frame) aata hai, line mein lagta hai, sahi counter pe jaata hai.
- **gps-streaming-service** = humara **office** jahan saare karmchaari (simulator, processing, storage, live location, logger, dashboard) baithte hain.
- **PostgreSQL** = office ka **pakka register** (record hamesha ke liye).
- **Redis** = office ka **whiteboard** – "abhi kaun truck kahan hai", jaldi se likho, jaldi se padho.
- **Kafka UI / RedisInsight** = CCTV camera – hub aur whiteboard ke andar jhaank ke dekhne ke liye.

| # | Application | Kaam | Port (Windows install) | Port (Docker) | Kis ka hai |
|---|---|---|---|---|---|
| 1 | **smart_truck MySQL** | Purane asli GPS pings ka source | `3306` | `host.docker.internal:3306` | Dusre system ka – **sirf padhna** |
| 2 | **PostgreSQL** `gps_tracking` | Positions + master data | `5432` | `5433` | Humara |
| 3 | **Apache Kafka** (KRaft) | 3 topics, buffer + post office | `9092` | `29092` | Humara |
| 4 | **Redis** | Live location + engine counters | `6379` | `6380` | Humara |
| 5 | **RedisInsight** | Redis ke andar dekhna (browser) | – | http://localhost:8001 | Docker only |
| 6 | **Kafka UI** | Kafka ke andar dekhna (browser) | – | http://localhost:8085 | Docker only |
| 7 | **gps-streaming-service** (Spring Boot) | Simulator, processing, storage, live location, logger, dashboard, API | http://localhost:8080 | http://localhost:8080 | Humara – **yahi asli app hai** |
| 8 | **gps-dashboard-ui** (Next.js) | PulseStream IoT console – operations UI (4 sections + Developer), service ki APIs pe chalta hai | http://localhost:3000 (`npm run dev`) | http://localhost:3000 | Humara – sirf UI, service ke bina kuch nahi dikhata |

**Kaun kis pe depend karta hai:**

```
                    smart_truck MySQL (sirf simulator ko chahiye, fleet start karte waqt)
                           │
gps-streaming-service ─────┼──► Kafka        (kafka flow mein zaroori, direct flow mein nahi)
                           ├──► PostgreSQL   (hamesha zaroori – iske bina service start nahi hogi)
                           └──► Redis        (live location + counters; band ho to dashboard "Redis down" dikhata hai)
```

---

## 2. Start karne ka sahi order (Windows, bina Docker) 🪜

Pehli baar? Pehle **setup** (sirf ek baar):

```powershell
cd D:\Tech-Projects\processing-layer
Copy-Item local-secrets.example.properties local-secrets.properties   # phir smart_truck user/password bharo
.\infrastructure\windows\setup-postgresql.ps1     # DB + gps_app user + random password (local-secrets mein khud likh deta hai)
.\infrastructure\windows\setup-kafka.ps1          # Kafka ka data folder format (KRaft, cluster id)
```

Roz ka start – **isi order mein**:

```powershell
cd D:\Tech-Projects\processing-layer
.\infrastructure\windows\start-postgresql.ps1                                        # 1. register khol do
.\infrastructure\windows\start-kafka.ps1                                             # 2. sorting hub (naya console window khulega)
D:\dev\redis-8.2.10\redis-server.exe D:\dev\redis-8.2.10\gps-local-redis.conf        # 3. whiteboard (alag window mein chalao)
.\mvnw.cmd -DskipTests package                                                       # 4. jar banao (code badla ho tab)
.\infrastructure\windows\start-gps-service.ps1                                       # 5. office kholo (Kafka flow)
```

Phir browser mein http://localhost:8080 → **Start fleet** dabao. Ya:

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/simulator/start?vehicleCount=150"
```

Bina Kafka ke (direct flow) – sirf PostgreSQL (+ Redis) chahiye:

```powershell
.\infrastructure\windows\start-gps-service.ps1 -ProcessingFlow direct
```

**Band karna:**

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/simulator/stop   # fleet roko (DB bharna band)
# service ki window mein Ctrl+C
.\infrastructure\windows\stop-kafka.ps1
.\infrastructure\windows\stop-postgresql.ps1
D:\dev\redis-8.2.10\redis-cli.exe shutdown nosave
```

---

## 3. Start karne ka tarika (Docker) 🐳

Sab ek saath, ek command mein. Order Docker khud sambhalta hai (health checks: postgres, redis, kafka healthy hone ke baad hi service start hoti hai).

```powershell
cd D:\Tech-Projects\processing-layer
.\infrastructure\docker\create-docker-env.ps1     # pehli baar: local-secrets se .env banata hai (git-ignored)
docker compose up -d --build                      # sab chalu – Docker mein fleet khud start ho jaati hai
docker compose ps                                 # sab "running" / "healthy" hone chahiye
docker compose logs -f gps-service                # service ke logs
docker compose down                               # band (data volumes mein bacha rehta hai)
docker compose down -v                            # band + data bhi saaf
```

Build nahi karna, seedha **Docker Hub** wali ready image chalani hai (https://hub.docker.com/r/srmecotech/processing-layer):

```powershell
docker compose pull gps-service
docker compose up -d --no-build
```

✅ 2026-10-19 ko is machine pe verify kiya: 5 containers up, health UP, 75 msg/s, saare groups STABLE, lag 0.

Ek hi container restart / rebuild:

```powershell
docker compose up -d --no-deps --build gps-service
```

⚠️ Windows install aur Docker dono **saath chal sakte hain** – isliye Docker ke ports alag hain (5433, 29092, 6380). Lekin dono ki service `8080` pe hai – **ek time pe ek hi service chalao**.

---

## 4. Har application ki detail

### 4.1 smart_truck MySQL – purana godown (READ-ONLY) 🔒

| | |
|---|---|
| **Kaam** | Asli trucks ke purane GPS pings (table `tta_trip_gps`, trips `tta_trips`). Simulator yahan se 150 alag devices ki sabse nayi trip uthata hai |
| **Start** | Ye humara nahi hai – ye pehle se Windows pe chal raha hota hai. Hum start/stop nahi karte |
| **Check** | `Test-NetConnection localhost -Port 3306` |
| **Golden rule** | **Kabhi likhna nahi.** Connection pool `setReadOnly(true)` pe khulta hai, class mein sirf `SELECT` hai |
| **Main files** | `gps-connectivity/src/main/java/com/srm/gps/connectivity/smarttruck/SmartTruckGpsReader.java` – dono SQL queries + read-only pool |
| | `.../smarttruck/SmartTruckGpsPing.java` – ek row readable naamon ke saath |
| | `.../smarttruck/SmartTruckDatabaseSettings.java` – url/user/password (`smart-truck.database.*`) |
| **Password kahan** | `local-secrets.properties` → `smart-truck.database.username/password` |
| **Docker mein dikkat** | MySQL user sirf `localhost` se allowed ho to container se `Access denied` aayega → read-only user maango jo Docker se aa sake |

### 4.2 PostgreSQL `gps_tracking` – pakka register 📒

| | |
|---|---|
| **Kaam** | Humara naya database: `tenant`, `vehicle`, `device`, `device_assignment`, `gps_position` |
| **Start (Windows)** | `.\infrastructure\windows\start-postgresql.ps1` (background mein, log: `D:\dev\data\postgresql.log`) |
| **Stop (Windows)** | `.\infrastructure\windows\stop-postgresql.ps1` |
| **Start (Docker)** | `docker compose up -d postgres` |
| **Check** | http://localhost:8080/actuator/health → `db: UP` · ya `Test-NetConnection localhost -Port 5432` |
| **Login** | user `gps_app`, password `local-secrets.properties` → `spring.datasource.password` |
| **Tables kaun banata hai** | Flyway, service start hote hi. Hibernate sirf **validate** karta hai |
| **Main files** | `gps-application/src/main/resources/db/migration/V1__create_master_data_tables.sql` – master data |
| | `.../db/migration/V2__create_gps_position_table.sql` – positions (unique `imei + recorded_at`) |
| | `gps-connectivity/.../database/entity/*.java` – `Tenant`, `Vehicle`, `Device`, `DeviceAssignment`, `GpsPosition` |
| | `gps-connectivity/.../database/repository/*.java` – Spring Data repositories |
| | `infrastructure/windows/setup-postgresql.ps1` – pehli baar ka setup |
| **Yaad rakho** | Chal chuki Flyway file **edit mat karo** – naya `V3__...sql` banao. `psql` nahi hai; check karna ho to Python `psycopg2` |

### 4.3 Apache Kafka – sorting hub 📮

| | |
|---|---|
| **Kaam** | 3 topics: `gps.raw-device-frames`, `gps.processed-positions`, `gps.rejected-frames` (har ek 3 partitions, key = IMEI) |
| **Start (Windows)** | `.\infrastructure\windows\start-kafka.ps1` (naya console window, `localhost:9092`) |
| **Stop (Windows)** | `.\infrastructure\windows\stop-kafka.ps1` |
| **Start (Docker)** | `docker compose up -d kafka` (Windows se `localhost:29092`) |
| **Topics kaun banata hai** | Service khud (`KafkaTopicDeclarations`) – haath se nahi banane |
| **Check (Windows)** | neeche wale commands |
| **Main files** | `gps-configuration/.../KafkaTopicNames.java` – topic naam + partitions (3) |
| | `gps-configuration/.../KafkaConsumerGroupNames.java` – 4 group naam |
| | `gps-connectivity/.../kafka/KafkaTopicDeclarations.java` – topics banana |
| | `gps-connectivity/.../kafka/KafkaRetrySettings.java` – retry 1s → 60s, toota message skip |
| | `gps-connectivity/.../kafka/KafkaTopologyInspector.java` – dashboard ke liye Kafka se offsets/lag poochna |
| | `infrastructure/windows/setup-kafka.ps1`, `start-kafka.ps1`, `stop-kafka.ps1` |

Kafka ke andar dekhna (Windows):

```powershell
$env:KAFKA_OPTS = "-Djdk.net.unixdomain.tmpdir=D:\dev\data\tmp"      # Windows ka nakhra, iske bina SocketException
$k = "D:\dev\kafka_2.13-4.3.1\bin\windows"
& "$k\kafka-topics.bat" --bootstrap-server localhost:9092 --list
& "$k\kafka-consumer-groups.bat" --bootstrap-server localhost:9092 --describe --group gps-position-storage-service
& "$k\kafka-console-consumer.bat" --bootstrap-server localhost:9092 --topic gps.rejected-frames --from-beginning --max-messages 5
```

⚠️ Local broker pe **dusre projects ke topics/groups** bhi hain (jaise `gps.positions.*`, `geofence-detector`) – unko chhedna nahi.

### 4.4 Redis – whiteboard 🧾

| | |
|---|---|
| **Kaam** | `gps:live:vehicle:{imei}` (latest position), `gps:live:positions` (GEO set), `gps:engine:stage:*` (load counters) |
| **Start (Windows)** | `D:\dev\redis-8.2.10\redis-server.exe D:\dev\redis-8.2.10\gps-local-redis.conf` (port 6379, disk pe save nahi, 256 MB) |
| **Stop (Windows)** | `D:\dev\redis-8.2.10\redis-cli.exe shutdown nosave` |
| **Start (Docker)** | `docker compose up -d redis` (Redis `localhost:6380` + RedisInsight http://localhost:8001) |
| **Check** | `D:\dev\redis-8.2.10\redis-cli.exe ping` → `PONG` · ya `/actuator/health` → `redis: UP` |
| **Main files** | `gps-configuration/.../RedisKeyNames.java` – saari key names ek jagah |
| | `gps-connectivity/.../redis/LiveVehiclePositionStore.java` – live position hash + GEO set |
| | `gps-connectivity/.../redis/EngineMetricsRedisMirror.java` – counters Redis mein copy |

Redis ke andar dekhna:

```powershell
$r = "D:\dev\redis-8.2.10\redis-cli.exe"
& $r --scan --pattern "gps:live:vehicle:*" | Select-Object -First 5
& $r HGETALL gps:live:vehicle:0861000000000001
& $r ZCARD gps:live:positions                 # kitni gaadiyon ki live position hai
```

### 4.5 Kafka UI aur RedisInsight – CCTV 📹

Sirf Docker mein aate hain.

| App | Link | Kya dekho |
|---|---|---|
| Kafka UI (kafbat 1.3.0) | http://localhost:8085 | Topics → messages, key (IMEI) se search, consumer groups ka lag |
| RedisInsight | http://localhost:8001 | `gps:` filter karo → live vehicles, engine counters |

Main file: `docker-compose.yml` (services `kafka-ui` aur `redis`).

### 4.6 gps-streaming-service – asli office 🏢

Ek hi Spring Boot jar (`gps-application/target/gps-streaming-service.jar`), lekin andar **kai karmchaari** hain. Har ek ko alag "mini app" samjho:

| | |
|---|---|
| **Start (Windows)** | `.\mvnw.cmd -DskipTests package` phir `.\infrastructure\windows\start-gps-service.ps1` (`-ProcessingFlow direct` bhi de sakte ho) |
| **Start (Docker)** | `docker compose up -d --build gps-service` |
| **Check** | http://localhost:8080 (dashboard) · http://localhost:8080/actuator/health |
| **Entry point** | `gps-application/src/main/java/com/srm/gps/GpsStreamingApplication.java` |
| **Config** | `gps-application/src/main/resources/application.yml` (passwords nahi, wo `local-secrets.properties` mein) |
| **Flow switch** | `gps.processing-flow: kafka` ya `direct` → `@OnlyInKafkaFlow` / `@OnlyInDirectFlow` (`gps-connectivity/.../flow/`) |

Ab andar ke karmchaari, ek ek karke:

#### (a) Simulator – Producer P1 🚚

Nakli fleet: smart_truck se 150 trips, rounds mein chalata hai, har 2 sec ek round (75 frames/sec).

| Main file | Kaam |
|---|---|
| `gps-application/.../simulator/GpsDeviceSimulatorService.java` | Fleet chalana: trips padho, master data register, rounds, drive back |
| `.../simulator/GpsDeviceSimulatorController.java` | `POST /api/simulator/start`, `/stop`, `GET /status`, `POST /send-single-frame` |
| `.../simulator/GpsDeviceSimulatorSettings.java` | `gps.simulator.*` settings |
| `.../flow/kafka/KafkaRawGpsFrameSender.java` | Kafka flow mein frame ko P1 tak bhejna |
| `gps-connectivity/.../kafka/RawGpsFramePublisher.java` | **P1** → `gps.raw-device-frames` (wait nahi karta) |
| `gps-configuration/.../message/RawGpsFrame.java` | Frame ka format: imei, recordedAt, lat, lon, speed |

#### (b) Processing consumer – C1 (aur P2, P3 isi ke andar) 🧹

Group `gps-raw-frame-processing-service`, 3 threads.

| Main file | Kaam |
|---|---|
| `gps-connectivity/.../kafka/RawGpsFrameListener.java` | **C1** – raw frames padhna |
| `gps-application/.../flow/kafka/KafkaRawFrameProcessingService.java` | Process karke achha → P2, kharab → P3 |
| `gps-processing/.../GpsFrameProcessor.java` | **Poora logic ek screen pe**: validate → movement → yaad rakhna |
| `gps-processing/.../rule/GpsFrameValidationRules.java` | Saare validation rules (IMEI, time, lat/lon, 0,0, speed 0..150) |
| `gps-processing/.../step/CalculateMovementStep.java` | Distance, time gap, duplicate, out-of-order |
| `gps-processing/.../rule/MovementRules.java` | MOVING (≥ 5 km/h ya ≥ 50 m) / STOPPED |
| `gps-processing/.../rule/GeoDistanceCalculator.java` | Haversine distance |
| `gps-application/.../processing/DatabaseBackedPreviousReadingStore.java` | Pichhli reading memory mein, restart ke baad DB se |
| `gps-connectivity/.../kafka/ProcessedGpsPositionPublisher.java` | **P2** → `gps.processed-positions` (Kafka ke "mil gaya" ka wait) |
| `gps-connectivity/.../kafka/RejectedGpsFramePublisher.java` | **P3** → `gps.rejected-frames` (wait karta hai) |

#### (c) Storage consumer – C2 📒

Group `gps-position-storage-service`, 3 threads → PostgreSQL.

| Main file | Kaam |
|---|---|
| `gps-connectivity/.../kafka/ProcessedGpsPositionListener.java` | **C2** – processed positions padhna |
| `gps-application/.../storage/GpsPositionStorageService.java` | Duplicate check, IMEI → gaadi → company, `gps_position` mein save |
| `gps-application/.../masterdata/DeviceVehicleDirectory.java` | "Is moment pe device kis gaadi mein tha?" (cache ke saath) |

#### (d) Live location consumer – C3 📍

Group `gps-live-location-service`, 3 threads → Redis.

| Main file | Kaam |
|---|---|
| `gps-connectivity/.../kafka/LiveLocationListener.java` | **C3** – same topic, apna alag bookmark |
| `gps-application/.../livelocation/LiveLocationService.java` | Sirf **nayi** reading likho, purani naye ko overwrite na kare |
| `gps-connectivity/.../redis/LiveVehiclePositionStore.java` | Redis hash + GEO set |

#### (e) Rejected frame logger – C4 🗑️

Group `gps-rejected-frame-logger`, 1 thread → log.

| Main file | Kaam |
|---|---|
| `gps-connectivity/.../kafka/RejectedGpsFrameListener.java` | **C4** – rejected frames padhna |
| `gps-application/.../rejection/RejectedGpsFrameLoggingService.java` | Reason ke saath WARN log |

#### (f) Direct flow – bina Kafka ka shortcut 🛣️

`gps.processing-flow=direct` pe sirf ye chalta hai: frame → processing → PostgreSQL + Redis, ek call mein.

| Main file | Kaam |
|---|---|
| `gps-application/.../flow/direct/DirectProcessingFlow.java` | Wahi processor, wahi storage, wahi live location – bas Kafka nahi |
| `gps-application/.../flow/RawGpsFrameSender.java` | Interface jo dono flows implement karte hain |

#### (g) Master data seeder 🗂️

| Main file | Kaam |
|---|---|
| `gps-application/.../masterdata/SimulationMasterDataSeeder.java` | Simulator ke devices/gaadiyan/companies naye DB mein register |
| `gps-application/.../masterdata/TenantCodeFormatter.java` | `"DEMO-TRANSPORT-JSR"` → `"demo-transport-jsr"` |

#### (h) Engine dashboard + monitoring 📊

| Main file | Kaam |
|---|---|
| `gps-application/src/main/resources/static/index.html` | Dashboard page (har 2 sec refresh) |
| `gps-application/.../engine/EngineController.java` | `GET /api/engine/overview`, `/devices`, `/devices/{imei}` |
| `gps-application/.../engine/EngineOverviewService.java` | Producers, topics, consumers, lag, Redis/DB numbers jodna |
| `gps-application/.../engine/EngineReportingJobs.java` | Har 5 sec counters Redis mein, har 30 sec `ENGINE` log line |
| `gps-connectivity/.../monitoring/PipelineMetrics.java` + `StageCounters.java` | Memory mein ginti: total, per sec, per partition, per IMEI |
| `gps-connectivity/.../monitoring/PipelineLogContext.java` | Har log line pe `stage / imei / partition / offset` |
| `gps-configuration/.../PipelineStage.java` | Har producer/consumer ka naam aur label – sab jagah ek hi naam |

#### (i) Query API 🔎

| Main file | Kaam |
|---|---|
| `gps-application/.../api/GpsPositionQueryController.java` | `GET /api/gps-positions/count`, `/recently-stored`, `/api/vehicles/{reg}/latest-position` |

---

## 5. "Sab theek chal raha hai?" – 1 minute ka check ✅

```powershell
foreach ($p in 5432, 9092, 6379, 8080) { "$p : " + (Test-NetConnection localhost -Port $p -WarningAction SilentlyContinue).TcpTestSucceeded }
Invoke-RestMethod http://localhost:8080/actuator/health          # db UP, redis UP
Invoke-RestMethod http://localhost:8080/api/simulator/status     # running: True?
Invoke-RestMethod http://localhost:8080/api/gps-positions/count  # number badh raha hai?
```

Dashboard pe dekho:
- **Frames in / s** ~75 (150 gaadi, 2 sec).
- Har consumer ka **group state = STABLE**.
- **Total consumer lag** chhota aur stable – lagatar badhe to wo consumer fail ho raha hai (uske `failures` aur log mein `stage=...` dekho).

---

## 6. Common galtiyan aur ilaaj 🩹

| Kya hua | Ilaaj |
|---|---|
| Simulator status `Failed: Send failed` | Kafka band hai → `start-kafka.ps1` (ya direct flow use karo) |
| `SocketException: Invalid argument: connect` | `-Djdk.net.unixdomain.tmpdir=D:\dev\data\tmp` (scripts khud dete hain; Kafka CLI ke liye `$env:KAFKA_OPTS`) |
| Port 8080 already in use | Ek aur service chal rahi hai (Windows wali ya Docker wali) – pehle wo band karo |
| Service start pe Flyway / Schema-validation error | Migration SQL aur entity match nahi kar rahe – kisi ne applied Flyway file edit kar di? |
| Dashboard "Redis down" | Redis start karo, `redis-cli ping` |
| Docker mein `Access denied` smart_truck ka | MySQL user sirf localhost ke liye hai – Docker se aane wala read-only user chahiye |
| Positions save, par vehicle/tenant khali | Us device ka `device_assignment` reading ke time ko cover nahi karta |
| DB bahut tezi se bhar raha | Fleet band karo: `POST /api/simulator/stop` (75 frames/sec = ~65 lakh rows/din) |

---

## 7. Yaad rakhne wali 4 lines 🧠

> **PostgreSQL pehle, phir Kafka, phir Redis, phir service.** (Docker mein ye order khud lagta hai.)
> **Asli kaam ek hi jar mein hai** – simulator, 4 consumers, dashboard, API sab `gps-streaming-service` ke andar.
> **smart_truck ko kabhi likhna nahi.**
> **Kuch toota → dashboard pe failures/lag dekho → log mein `stage=` aur `imei=` se filter karo → us mini app ki main file kholo.**
