# Processing Layer – Seedhi Simple Bhasha Mein 🚚

> Ye file **shuruaat karne walon** ke liye hai. Badi detail wala README alag hai ([README.md](README.md)).
> Yahan sirf itna: **kya bana hai, kaise chalta hai, aur code mein kahan hai.**
> Upar se neeche ek baar padh lo – poora project dimaag mein baith jayega.

---

## 0. Ek line mein poora project

**Truck ka GPS device har minute bolta hai "main yahan hoon, itni speed pe" → humara system usko check karta hai → save karta hai → aur us se samajhdaar baatein nikalta hai (trip, over-speed, rukna, engine on/off) → sab ek dashboard pe dikhata hai.**

Bas. Baaki sab is line ki detail hai.

---

## 1. Pehle 6 shabd samajh lo (baaki sab aasaan ho jayega)

| Shabd | Simple matlab | Desi example |
|---|---|---|
| **IMEI** | GPS device ka unique number (jaise phone ka) | Truck ka "Aadhaar number" |
| **Frame / Ping / Reading** | Device ka ek message: IMEI, time, lat, lon, speed | Truck ka ek "main yahan hoon" SMS |
| **Kafka** | Messages ki **line / queue**. Bhejne wala daal deta hai, padhne wala apni speed se uthata hai | Post office ka letter box |
| **Topic** | Kafka ke andar ek **naam wala dabba** | Alag alag letter box: "naye letters", "check ho chuke", "reject" |
| **Producer / Consumer** | Topic mein **daalne** wala / topic se **padhne** wala | Daakiya letter daalta hai / office wala uthata hai |
| **Consumer Group** | Ek **kaam** ka naam. Har group ko har message ki **apni copy** milti hai | Akhbaar ki photocopy – har office ko apni |

Aur 3 storage:

| Storage | Kya rakhta hai | Kyun |
|---|---|---|
| **PostgreSQL** | Pakka record – har reading, har trip, har event | Register / bahi-khata – kabhi mitna nahi chahiye |
| **Redis** | "Abhi ka" haal – har truck ki latest position aur state | Whiteboard – fast, har second update |
| **MySQL (smart_truck)** | Purana system, jahan se asli purane GPS pings **sirf padhte** hain | Purani diary – sirf padhna, likhna mana |

---

## 2. Poora system ek photo mein

```
  smart_truck MySQL (sirf PADHNA)
         │  150 trucks ki asli purani trips
         ▼
  ┌────────────────────┐
  │  SIMULATOR         │  "nakli trucks" – purane pings ko aaj ke time pe dobara chalata hai
  └────────┬───────────┘
           ▼
  [Topic 1] gps.raw-device-frames          ← kachcha maal
           │
           ▼
  ┌────────────────────┐
  │  PROCESSING        │  1) check karo (valid hai?)  2) distance nikaalo  3) MOVING ya STOPPED
  └───┬────────────┬───┘
      │ achha      │ kharab
      ▼            ▼
  [Topic 2]     [Topic 3] gps.rejected-frames ──► Logger (log mein "kyun reject hua")
  gps.processed-positions
      │
      │   Is ek topic ko 7 alag groups padhte hain – sabko apni copy:
      ├──► 1. Storage        → PostgreSQL gps_position      (har reading save)
      ├──► 2. Live location  → Redis                        (truck abhi kahan hai)
      ├──► 3. Trip           → PostgreSQL gps_trip/gps_stop (kab chala, kab ruka, kitna chala)
      ├──► 4. Speed          → over-speed pakdo
      ├──► 5. Vehicle state  → direction, jhatka-brake, smooth distance, history
      ├──► 6. Stoppage       → ruka kyun? traffic? mausam? ya bina wajah?
      └──► 7. Ignition       → engine on/off, engine hours, idling
                    │
                    ▼ (4-7 jo "event" banate hain)
            [Topic 4] gps.vehicle-events  +  PostgreSQL vehicle_event  +  Redis list

  Sabse upar:  Dashboard (Next.js, :3000)  ←→  Spring Boot API (:8080)
```

**Yaad rakhne ka formula:** **4 topics, 4 producers, 9 consumer groups.**
(1 processing + 7 jo processed topic padhte hain + 1 rejected logger = 9)

---

## 3. Ek ping ka safar – shuru se ant tak 🛣️

Maan lo truck `0861000000000002` ne ping bheja: *"10:05 baje, Jamshedpur ke paas, 42 km/h"*.

### Step 1 – Simulator ping banata hai
- Asli device abhi juda nahi hai, isliye **simulator** acting karta hai.
- Wo smart_truck MySQL se **150 trucks ki asli purani trip** padhta hai aur unko **aaj ke time** ke saath dobara bhejta hai.
- Shuru mein 2 ghante peeche se start karke **30x tez** chalta hai (jaldi data mil jaye), phir **real time** pe aa jata hai.
- Trip khatam? Truck **ulta wapas** chalta hai – taaki "teleport" na kare. Aur ye hamesha chalta rehta hai.
- smart_truck mein ignition/odometer nahi hai, to simulator **aadhe trucks** ko ignition wire deta hai, **aadhon** ko odometer, aur **chauthai drivers** ko "idler" bana deta hai (rukte hain par engine chalu).
- 📁 `gps-application/.../simulator/GpsDeviceSimulatorService.java`

### Step 2 – Kafka ke Topic 1 mein jaata hai
- Message ki **key = IMEI**. Kafka key dekh ke partition chunta hai, to **ek truck ke saare pings hamesha ek hi partition** mein → **order kabhi nahi bigadta** (10:05 wala 10:04 se pehle process nahi hoga).
- Har topic ke **3 partitions** = 3 lanes, 3 kaam karne wale ek saath.
- 📁 `gps-connectivity/.../kafka/RawGpsFramePublisher.java`

### Step 3 – Processing: check + distance
Ye project ka **dil** hai. Plain Java hai – na Kafka, na database – isliye aasani se test hota hai.

**(a) Validation – kachra bahar:**
- IMEI khali? Time nahi? Time future mein? Latitude 95? Position 0,0? Speed 150 se upar? → **REJECT** (saaf reason ke saath, jaise `Latitude 95.0 is outside -90..90`)
- 📁 `gps-processing/.../rule/GpsFrameValidationRules.java`

**(b) Movement – kitna chala:**
- Is truck ki **pichhli reading** yaad hai. Uske aur abhi ke beech:
  - **Same time dobara aaya** → duplicate → reject.
  - **Purani reading late aayi** (network wapas aaya) → accept, par distance 0 (`OUT_OF_ORDER`). History bigadni nahi chahiye.
  - Odometer hai aur sahi lag raha → **odometer se** distance.
  - Warna **GPS se** – do points ki doori (haversine formula = dharti ko gola maan ke).
  - Khade truck ka GPS 10 m se kam "hila" → ye jitter hai, distance **0** (`JITTER_FILTERED`). Warna parked truck raat bhar mein km jod deta!
  - 200 km/h se tez chhalang → GPS glitch, **count nahi** (`JUMP_FILTERED`).
- **MOVING ya STOPPED:** speed ≥ 5 km/h **ya** 50 m se zyada chala → MOVING, warna STOPPED.
- 📁 `gps-processing/.../step/CalculateMovementStep.java`, `rule/MovementRules.java`
- 📁 Sab ek jagah jodne wala: `gps-processing/.../GpsFrameProcessor.java`

**(c) Result:** achha → **Topic 2**, kharab → **Topic 3**.
⚠️ Dhyaan do: processing **consumer bhi hai aur producer bhi** – Topic 1 se padhta hai, Topic 2/3 mein likhta hai.

**(d) Ye sab chalata kaun hai? Kafka Streams 🔒**
- Rules wahi upar wale hain (`gps-processing`), lekin unko ab **Kafka Streams** chalata hai – ek library jo isi service ke andar chalti hai.
- "Truck ki pichhli reading" ab memory mein nahi, **locker** (RocksDB state store) mein hai, aur locker ki copy Kafka mein (changelog topic). Restart pe kuch gum nahi.
- Output likhna + locker update + "ye frame ho gaya" (offset) – teeno **ek saath ya koi nahi** (exactly-once).
- Do naye check: device **purani reading dobara** bheje → duplicate; 24 ghante se purani → stale.
- Ek doosra Kafka Streams app har truck ki latest state (KTable) aur **5-minute ka hisaab** (`gps.vehicle-activity`) rakhta hai.
- Purana listener abhi bhi hai: `gps.streams.normalization=shadow` pe wo kaam karta hai aur Kafka Streams ka output usse **milaaya** jaata hai.
- 📁 `gps-streams/` · UI: Processing → Kafka Streams · Poori kahani: README section 15c, [docs/KAFKA_STREAMS_GUIDE.md](docs/KAFKA_STREAMS_GUIDE.md)

### Step 4 – Topic 2 se 7 log apni apni copy uthate hain
Har ek alag consumer group hai, to **ek slow ho to baaki nahi rukte**. Ab ek ek karke:

---

## 4. Saare kaam (functionalities) – ek ek karke

### 4.1 💾 Storage – har reading pakki save
- **Kya:** har achhi reading PostgreSQL `gps_position` table mein.
- **Extra:** device ko nahi pata wo kis truck mein hai. Hum **master data** se dekhte hain: "is IMEI wala device, **is reading ke time**, kis gaadi aur kis company mein laga tha?" (`device_assignment` table mein from–to period). Ye answer cache hota hai taaki har message pe DB query na ho.
- **Duplicate se bachao:** `(imei, recorded_at)` unique hai – Kafka do baar bhi de de to ek hi row.
- 📁 `gps-application/.../storage/GpsPositionStorageService.java`, `masterdata/DeviceVehicleDirectory.java`

### 4.2 📍 Live location – truck abhi kahan hai
- **Kya:** Redis mein har truck ki **sirf latest** position (`gps:live:vehicle:{imei}`) + ek **GEO set** (`gps:live:positions`) jisse "5 km ke andar kaun kaun hai" pooch sakte ho.
- **Kyun Redis?** Crore rows wali table se "abhi kahan hai" poochna mehnga. Redis memory mein hai – millisecond mein jawab.
- Late reading kabhi nayi position ko overwrite nahi karti.
- 📁 `gps-application/.../livelocation/LiveLocationService.java`

### 4.3 🧭 Trips aur Stops – kab chala, kab ruka, kitna chala
- **Logic Traccar se liya hai** (open-source GPS server, saalon se production mein).
- **Teen rule:**
  - Jahan ruka tha wahan se **200 m door** gaya → **trip shuru** (start time peeche khiska ke wahan jahan sach mein chalna shuru hua).
  - **3 minute** tak 200 m ke andar hi raha → **ruk gaya** → trip band, stop shuru.
  - Device **1 ghante** chup → trip aakhri reading pe band.
- Ye **distance** pe chalta hai, har ping ke MOVING/STOPPED pe nahi → GPS jitter se fake trip nahi banti.
- Device doosri gaadi mein laga → purani trip band. Ek trip kabhi do gaadiyon ki nahi.
- **Traccar ki galti theek ki:** wahan ek late reading se trip ka distance **-1020 m** ban gaya tha. Yahan distance = in-order readings ka **jod**, aur DB mein `CHECK (distance_m >= 0)`.
- Truck ki "yaaddasht" `vehicle_motion_state` table mein; ek reading = ek transaction. Same reading dobara aayi → update 0 rows → kuch nahi likha.
- 📁 Dimaag: `gps-processing/.../trip/TripMotionProcessor.java` (pure function)
- 📁 Database wala hissa: `gps-application/.../trip/TripDetectionService.java`

### 4.4 🚨 Speed violation – over-speed pakdo (aur speedometer ka jhooth bhi)
- **Rule Redis mein** hai, UI se badal sakte ho. Default: **60 km/h**, kam se kam **60 second** aur **3 pings lagatar** upar → tab hi OVERSPEED. Ek ping ka spike alarm nahi bajata.
- **Hysteresis:** khatam tab maana jayega jab limit − 5 (yaani 55) se neeche aaye. Warna 59-61-59-61 pe baar baar alarm bajta.
- **Rule kaun sa lage:** pehle us **gaadi** ka rule, nahi to **company** ka, nahi to **default**.
- **Speedometer vs GPS:** device bole 80, par distance ÷ time bole 50 → `SENSOR_SUSPECT`. Lagatar aisa → `SPEED_SENSOR_MISMATCH` event (tampering ka shak).
- 💡 In trucks ki max speed hi ~60 hai (speed governor), to demo ke liye UI se limit 40-45 kar do.
- 📁 `gps-processing/.../speed/SpeedViolationProcessor.java`, `gps-application/.../streaming/SpeedViolationService.java`

### 4.5 🧠 Vehicle state – truck ki poori kundli
- Redis `gps:state:vehicle:{imei}` mein: **direction** (heading), **acceleration**, **harsh braking / harsh acceleration** events, data quality.
- **Kalman filter:** GPS points thode zig-zag hote hain. Kalman ek "smart average" hai jo andaza lagata hai truck sach mein kahan hai → **smooth track aur sahi distance**. Bilkul ajeeb point aaye to usko ignore karta hai.
- **History:** Redis Stream `gps:history:{imei}` mein last ~1000 readings.
- 📁 `gps-processing/.../state/VehicleStateProcessor.java`, `state/KalmanFilter2D.java`

### 4.6 🛑 Stoppage – truck ruka kyun?
- **Ruka kab maana jaye:** 100 m ke ghere mein, **3 minute** se zyada, speed ≤ 3 km/h.
- **Engine ON + ruka** → `STOPPAGE` (shak wali baat, alarm). Phir 2 "agents" wajah dhoondhte hain:
  - **Traffic agent:** 1 km ke andar fleet ke baaki trucks bhi dheere hain? → jam hai → `JUSTIFIED_TRAFFIC`
  - **Weather agent:** Open-Meteo API se mausam → `JUSTIFIED_WEATHER`
  - Kuch nahi mila → `UNEXPLAINED` (HIGH severity)
- **Engine OFF + ruka** → `PARKED` – normal parking, koi alarm nahi.
- 📁 `gps-processing/.../stoppage/StoppageDetector.java`, `gps-application/.../streaming/StoppageService.java`, `StoppageContextAgents.java`

### 4.7 🔑 Ignition – engine on/off, engine hours, idling
- Wire hai → **device ka ignition** use karo.
- Wire nahi → **virtual ignition**: chal raha = ON, **5 min** khada = OFF.
- **Engine hours** jodta hai.
- **Idling:** engine ON + khada (≤ 3 km/h) + **5 min** se zyada → `IDLING` event (fuel waste).
- 📁 `gps-processing/.../ignition/IgnitionProcessor.java`, `gps-application/.../streaming/IgnitionService.java`

### 4.8 🗑️ Rejected logger
- Topic 3 padh ke log mein likhta hai **kaunsa frame, kyun** reject hua. Kharab device pakadne ke kaam aata hai.
- 📁 `gps-application/.../rejection/RejectedGpsFrameLoggingService.java`

### 4.9 📣 Events – sab alarms ek jagah
4.4 se 4.7 tak jo bhi "event" bane (OVERSPEED, STOPPAGE, PARKED, IDLING, IGNITION_ON/OFF, HARSH_BRAKING …) wo **3 jagah** jaata hai:
1. PostgreSQL `vehicle_event` table (pakka record; start pe OPEN, khatam pe CLOSED – same row update)
2. Redis `gps:events:{imei}` (truck ke last 50 events, tracking window ke liye)
3. Kafka Topic 4 `gps.vehicle-events` (aage notification/dispatch ke liye)
- 📁 `gps-application/.../events/VehicleEventRecorder.java`

---

## 5. Do raaste: Kafka flow vs Direct flow

Setting: `gps.processing-flow` (`application.yml`)

| | **kafka** (default) | **direct** |
|---|---|---|
| Kaise | Upar wala poora diagram | Kafka nahi – ek hi function call mein sab: process → save → Redis → trips → 4 stream kaam |
| Database down ho jaye | Data Kafka mein intezaar karta hai, gum nahi | Frame fail |
| Debug | Thoda mushkil | Bahut aasan – ek call, ek stack trace |

Logic **same code** hai, sirf "daakiya" alag. 📁 `gps-application/.../flow/kafka/` aur `flow/direct/`

---

## 6. Data gum na ho – 4 golden rules

1. **Kaam pehle, bookmark baad mein.** Consumer kaam poora karke hi Kafka ko bolta hai "ho gaya" (offset commit). Beech mein crash → message dobara aayega, gum nahi hoga.
2. **Retry karte raho.** DB down → 1s, 2s, 4s … 60s tak ruk ruk ke retry, jab tak wapas na aaye.
3. **Toota message phenko mat, alag rakho.** Jo kabhi theek nahi ho sakta (galat JSON) – `gps.dead-letters` mein park, aage badho, line block mat karo. Baad mein bug theek karke replay (README section 10a).
4. **Do baar aaye to bhi ek hi baar likho (idempotent).** Unique keys + "pehle se hai?" check.

---

## 7. Dashboard aur API – sab dekhte kahan hain

| Link | Kya hai |
|---|---|
| http://localhost:3000 | **PulseStream dashboard** (Next.js) – main UI |
| http://localhost:8080 | Spring Boot service + purana engine dashboard (producers, topics, consumers, lag) |
| http://localhost:8085 | **Kafka UI** – topics ke andar messages dekho |
| http://localhost:8001 | **RedisInsight** – `gps:` keys dekho |
| http://localhost:8080/actuator/health | DB aur Redis zinda hain ya nahi |

**PulseStream dashboard ke 5 tab:**

| Tab | Kya milega |
|---|---|
| **Stream Processors** | Pipeline overview, Vehicle tracking (ek truck ki poori kundli), Speed violation, Stoppage & ignition … |
| **Business Rules & Alerts** | Over-speed verification, trips, route deviation, dispatch |
| **Ingestion & DB Sink** | Validation gate, PostgreSQL sink, worker health |
| **Telemetry Analytics** | Live rate, latency, anomalies, trip efficiency |
| **Developer** | Engine overview, producers/consumers, demo tools (**Empty DB**) |

Har card pe likha hota hai **Live** (asli data service se) ya **Demo data** (backend abhi ye nahi banata – jaise geofence, ClickHouse). Browser sirf `:3000` se baat karta hai; dashboard ka server `/api/*` ko `:8080` tak aage bhej deta hai.
📁 `gps-dashboard-ui/src/app/...` (har folder = ek page), proxy: `src/app/api/[...path]/route.ts`

**Empty DB button:** demo practice ke liye sab saaf – fleet roko → Kafka messages delete → tables khali (master data aur speed rules bachte hain) → Redis saaf → fleet dobara. ~6 second. (`POST /api/admin/reset`)

**Kaam ki APIs:**
```
GET  /api/trips?imei=...            trips
GET  /api/stops?imei=...            stops
GET  /api/events?type=OVERSPEED     events
GET  /api/vehicles/{imei}/state     ek truck ka saara haal
GET  /api/speed/rules               speed rules  (PUT se badlo)
POST /api/simulator/start | stop    fleet chalu/band
POST /api/simulator/send-single-frame   ek frame haath se bhejo (kharab bhej ke reject dekho!)
```

---

## 8. Code ka naksha – kaunsi cheez kahan

Project **4 Java modules + 1 UI** mein bata hai. Har ek ka **ek hi kaam**:

```
processing-layer/
├─ gps-configuration/   📒 "Dictionary" – topic naam, group naam, Redis key naam, message ka format
│                          (KafkaTopicNames, KafkaConsumerGroupNames, RedisKeyNames, message/*)
├─ gps-connectivity/    🔌 "Bahar ki duniya se taar" – Kafka listeners/publishers, Redis, DB tables (entities),
│                          smart_truck reader, load counters
├─ gps-processing/      🧠 "Dimaag" – saara logic, PLAIN JAVA, poora unit tested
│                          validation, distance, trip, speed, stoppage, ignition, Kalman
├─ gps-application/     🏭 "Factory" – Spring Boot app jo sabko jodta hai: simulator, flows, storage,
│                          streaming services, APIs, DB migrations (resources/db/migration)
├─ gps-dashboard-ui/    🖥️ "Shop ka counter" – Next.js dashboard
├─ infrastructure/      🛠️ Windows aur Docker ki setup scripts
└─ docker-compose.yml   🐳 Sab kuch ek command mein
```

**Kuch toota → kahan dekho:**
| Problem | Module |
|---|---|
| Naam/JSON field mismatch | `gps-configuration` |
| Connect/read/write nahi ho raha | `gps-connectivity` |
| Galat reject, galat distance, galat trip/alarm | `gps-processing` (tests bhi yahin) |
| Pieces sahi jud nahi rahe | `gps-application` |

**Ek smart pattern:** connectivity module sirf message **receive** karta hai aur ek "Handler" interface ko de deta hai. Message ke saath **kya karna hai** wo application module decide karta hai. Kafka ka code aur business ka code alag-alag – saaf rehta hai.

---

## 9. Database tables – ek nazar mein

| Table | Kya hai |
|---|---|
| `tenant` | Company |
| `vehicle` | Truck |
| `device` | GPS device (IMEI) |
| `device_assignment` | Device kis truck mein **kab se kab tak** laga tha |
| `gps_position` | Har achhi reading (+ distance, distance source, ignition, odometer) |
| `vehicle_motion_state` | Trip service ki yaaddasht (har truck ki 1 row) |
| `gps_trip` / `gps_stop` | Trips aur stops |
| `gps_trip_late_reading` | Late aayi readings ki list |
| `vehicle_event` | Saare alarms/events |

Tables **Flyway** banata hai (`V1__ ... V5__` SQL files). ⚠️ Purani `V` file kabhi edit mat karo – naya `V6__` banao.

---

## 10. Chalana kaise hai (Docker – sabse aasan)

```powershell
.\infrastructure\docker\create-docker-env.ps1     # sirf pehli baar – .env (passwords) banata hai
docker compose up -d --build                      # sab chalu – 150 trucks khud chalne lagte hain
docker compose logs -f gps-service                # logs dekho
docker compose down                               # band (data bacha rehta hai)
docker compose down -v                            # band + data bhi saaf
```

Containers: `postgres`, `kafka`, `kafka-ui`, `redis`, `gps-service`, `dashboard`.

**Logs mein ek truck dhoondho:**
```powershell
docker compose logs gps-service | Select-String "imei=0861000000000002"
```
Har log line batati hai **kaunsa stage, kaunsa truck, kaunsa partition/offset**:
`WARN [stage=position-storage imei=0861000000000002 p=2 o=1534] ...`

Tests: `.\mvnw.cmd test`

---

## 11. Abhi kya nahi bana (aage ka kaam)

- Asli device parser (abhi simulator hai)
- Nested geofencing, waypoints, route deviation (UI mein "Demo data")
- ClickHouse (lambi history ke liye)
- Late readings wali trips ko dobara calculate karne wala repair job
- Dispatch / notifications (Topic 4 pe naya consumer)

---

## 12. Mental model – ratta maar lo 🧠

> **Ping aaya → check hua → 3 tukde:**
> **kharab** → logger.
> **achha** → 7 log apni copy padhte hain: *save, live, trip, speed, state, stoppage, ignition*.
> Jo alarm bana → `vehicle_event` + Redis + Topic 4.
>
> **Key = IMEI = order.** **Alag group = apni copy.** **Same group = kaam ka batwara.**
> **Logic `gps-processing` mein, jodna `gps-application` mein, taar `gps-connectivity` mein, naam `gps-configuration` mein.**

Aur gehraai chahiye? → [README.md](README.md) (poori detail) · [docs/APPLICATIONS_GUIDE.md](docs/APPLICATIONS_GUIDE.md) · [docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md)
