# Processing Layer – GPS Streaming Platform 🚚📡

> **Ek line mein:** 150 trucks ka GPS data aata hai → Kafka mein line lagta hai → Spring Boot usko check karta hai, saaf karta hai → PostgreSQL mein save hota hai, Redis mein "abhi truck kahan hai" wala live board update hota hai, aur trip service "kab chali, kab ruki, kitna chali" likhti hai. Aur ek **engine dashboard** hai jahan har producer, topic, consumer live dikhta hai.

> 🟢 **Naye ho?** Pehle [README_SIMPLE.md](README_SIMPLE.md) padho – poora project seedhi simple bhasha mein, ek ping ke safar ke saath.

Ye README maine apni bhasha mein likha hai – desi Hinglish – taaki koi bhi (main khud bhi 6 mahine baad 😅) padh ke poora project samajh jaye. Har cheez jo is project mein use hui hai, uska **kya**, **kyun** aur **kaise** neeche hai.

> **Note:** Is repo mein jitne bhi IMEI, gaadi number, transporter naam dikhenge (jaise `0861000000000001`, `XX01AB1234`, `DEMO-TRANSPORT-JSR`) – sab **dummy** hain. Asli data public nahi kiya gaya hai.

---

## 📑 Index

1. [Pehle ek desi kahani (analogy)](#1-pehle-ek-desi-kahani-analogy)
2. [Kya kya khulta hai (links)](#2-kya-kya-khulta-hai-links)
3. [Tech stack – har tool kyun liya](#3-tech-stack--har-tool-kyun-liya)
4. [Poora flow ek diagram mein](#4-poora-flow-ek-diagram-mein)
5. [Topics – 3 trays](#5-topics--3-trays)
6. [Producers – tray mein maal kaun daalta hai](#6-producers--tray-mein-maal-kaun-daalta-hai)
7. [Consumers aur Consumer Groups](#7-consumers-aur-consumer-groups)
8. [Do raaste: Kafka flow vs Direct flow](#8-do-raaste-kafka-flow-vs-direct-flow)
9. [Processing ka andar ka logic (step by step)](#9-processing-ka-andar-ka-logic-step-by-step)
10. [Data kabhi gum na ho – reliability ke rules](#10-data-kabhi-gum-na-ho--reliability-ke-rules)
    - [10a. Ek mahina Kafka mein, ek bhi record gum nahi](#10a-ek-mahina-kafka-mein-ek-bhi-record-gum-nahi)
11. [Database: gps_tracking (PostgreSQL + Flyway + Hibernate)](#11-database-gps_tracking-postgresql--flyway--hibernate)
12. [Master data: IMEI → gaadi → company](#12-master-data-imei--gaadi--company)
13. [Simulator – nakli trucks jo asli jaise chalte hain](#13-simulator--nakli-trucks-jo-asli-jaise-chalte-hain)
14. [Redis – live board](#14-redis--live-board)
    - [14a. Connections – is laptop ka Redis ya HP home server ka](#14a-connections--is-laptop-ka-redis-ya-hp-home-server-ka-ui-developer--connections)
15. [Trips aur stops – gps-trip-service](#15-trips-aur-stops--gps-trip-service)
    - [15c. Kafka Streams – processing layer ka engine](#15c-kafka-streams--processing-layer-ka-engine)
    - [15d. Reports – din aur mahine ka hisaab](#15d-reports--din-aur-mahine-ka-hisaab-ui-reports)
16. [Engine dashboard aur REST APIs](#16-engine-dashboard-aur-rest-apis)
17. [Classified logs – har line bataye kaun, kis truck ka, kahan se](#17-classified-logs--har-line-bataye-kaun-kis-truck-ka-kahan-se)
18. [Maven modules – har module ka ek hi kaam](#18-maven-modules--har-module-ka-ek-hi-kaam)
19. [Passwords aur secrets kaise sambhale](#19-passwords-aur-secrets-kaise-sambhale)
20. [Docker se chalao (recommended)](#20-docker-se-chalao-recommended)
21. [Bina Docker ke chalao (Windows, D:\dev)](#21-bina-docker-ke-chalao-windows-ddev)
22. [Windows ke nakhre (quirks)](#22-windows-ke-nakhre-quirks)
23. [Kuch toot jaye to kahan dekhein (troubleshooting)](#23-kuch-toot-jaye-to-kahan-dekhein-troubleshooting)
24. [Aage kya banana hai – roadmap](#24-aage-kya-banana-hai--roadmap)
25. [Interview ke tricky sawaal](#25-interview-ke-tricky-sawaal)
26. [Mental model – yaad rakhne ke liye](#26-mental-model--yaad-rakhne-ke-liye)

---

## 1. Pehle ek desi kahani (analogy)

Socho ek **dhaba kitchen** hai 🍛:

- Peeche ke darwaze se **kachchi sabzi** aati hai → ye hai **GPS device ka raw data** (abhi simulator bhejta hai, baad mein asli device parser bhejega).
- Ek **helper** sabzi dhota hai, check karta hai. Achhi sabzi **saaf tray** mein, sadi hui **reject wali balti** mein → ye hai **processing consumer**.
- Saaf tray se **teen log apni apni copy** uthate hain:
  - **Munshi** register mein likhta hai → **PostgreSQL** mein save.
  - **Waiter** board pe likhta hai "kaun table pe kya chal raha hai" → **Redis** mein live location.
  - **Hisaab wala** apni diary mein likhta hai "gaadi kab chali, kab ruki, kitna chali" → **trip service** (PostgreSQL `gps_trip`, `gps_stop`).
- Ek aur banda reject balti dekh ke likhta hai **kyun phenka** → **rejected frame logger**.

Bas, yahi poora project hai. Baaki sab is kahani ki detail hai. 😄

---

## 2. Kya kya khulta hai (links)

| Link | Kya dikhega |
|---|---|
| http://localhost:3000 | **PulseStream IoT console** (Next.js, `gps-dashboard-ui/`) – operations UI: Live, Processing, Alerts, Storage, Analytics, Notifications, Developer |
| http://localhost:8080 | **Engine dashboard** – 3 producers, 3 topics, consumers, har partition ka load, lag, IMEI filter, simulator start/stop button |
| http://localhost:8001 | **RedisInsight** (Docker mein) – `gps:` se shuru hone wali saari keys |
| http://localhost:8080/actuator/health | PostgreSQL (`db`) aur Redis (`redis`) UP hai ya DOWN |
| http://localhost:8989/maps/ | **GraphHopper** (profile `routing`) – India ka road map, do point click karo, truck/car ka raasta |
| http://localhost:8090/api/routing/engine | **Route service** (`gps-route-service`) – engine UP/DOWN, algorithms; setup: [docs/ROUTING_ENGINE_SETUP.md](docs/ROUTING_ENGINE_SETUP.md) |

---

## 3. Tech stack – har tool kyun liya

| Tool | Version | Kyun liya (simple bhasha mein) |
|---|---|---|
| **Java** | 21 | Records, `switch` pattern matching, sealed interfaces – code chhota aur saaf rehta hai |
| **Spring Boot** | 4.1.1 | Kafka, JPA, Redis, REST, Actuator sab ek jagah, kam configuration |
| **Apache Kafka** | 4.3.1 (KRaft, ZooKeeper nahi) | GPS data ka **buffer + post office**. Database down ho to data Kafka mein intezaar karta hai, gum nahi hota |
| **Spring for Apache Kafka** | Boot ke saath | `@KafkaListener`, `KafkaTemplate`, retry (`DefaultErrorHandler`) |
| **Kafka Streams** (+ RocksDB) | 4.2.1 (Boot ke saath) | Raw frame → position ka kaam **exactly-once**, har gaadi ki state RocksDB + changelog topic mein, KTable, 5-min event-time windows, Interactive Queries (section 15c) |
| **PostgreSQL** | 14.22 | Hamara apna naya database `gps_tracking` – positions aur master data |
| **Hibernate / Spring Data JPA** | Boot ke saath | Java class ↔ table. `ddl-auto: validate` – Hibernate sirf check karta hai, table nahi banata |
| **Flyway** | Boot ke saath | Tables numbered SQL files se bante hain (`V1__`, `V2__`). Kaunsi file chal chuki, wo yaad rakhta hai |
| **Redis Stack** (+ RedisInsight) | 7.4 | Super fast memory store – "truck abhi kahan hai", GEO set, engine counters |
| **MySQL** (smart_truck) | 8 | Purana system – yahan se **sirf padhte hain** (READ-ONLY), likhte kabhi nahi |
| **HikariCP** | Boot ke saath | smart_truck ke liye alag **read-only** connection pool |
| **Jackson 3** (`tools.jackson`) | Boot ke saath | Messages ko JSON banana aur wapas padhna, ek hi tarike se |
| **Maven** (multi-module + wrapper `mvnw`) | – | 4 modules, har ek ka ek kaam. Maven install karne ki zarurat nahi, `mvnw` khud le aata hai |
| **Docker + Docker Compose** | – | Poora system ek command mein: postgres, kafka, redis, service, dashboard |
| **Spring Actuator** | Boot ke saath | `/actuator/health` – sab zinda hai ya nahi |
| **JUnit 5** | – | Processing ke har rule ka test, bina kuch start kiye |

**Apache Flink nahi hai** – pehle tha, hata diya. Processing ke rules `gps-processing` mein plain Java mein hain. Unko chalata hai **Kafka Streams** – ek library jo isi Spring service ke andar chalti hai, alag cluster nahi (section 15c). State, windows aur exactly-once Kafka se hi milte hain, bina naye system ke.

---

## 4. Poora flow ek diagram mein

```
smart_truck (MySQL, READ-ONLY): 150 alag devices ki sabse nayi trip
      │
      ▼
P1  GPS device simulator ──► gps.raw-device-frames   (key = IMEI, 3 partitions)
    (baad mein: device parser)        │
                                      │ group gps-raw-frame-processing-service   (3 consumers)
                                      ▼
                        Processing (gps-processing, plain Java)
                          1. frame validate karo   2. distance / time gap, MOVING ya STOPPED
                         │                                     │
P2                       ▼  (achha)                   P3       ▼  (kharab)
          gps.processed-positions                     gps.rejected-frames
           │                     │                    │        │
 group gps-position-       group gps-live-     group gps-trip-   group gps-rejected-
 storage-service (3)       location-service (3) service (3)      frame-logger (1)
           ▼                     ▼                    ▼        ▼
   PostgreSQL gps_position   Redis: har gaadi ki   PostgreSQL   log line, reason ke saath
   (IMEI → gaadi → company   latest position       gps_trip +
    READING ke time ka)      + GEO set             gps_stop
```

**Ginti yaad rakho: 3 topics, 3 producers, 5 consumer groups.**

> **Update (Kafka Streams):** C1 ka kaam (raw → processed / rejected) ab default se **Kafka Streams normalizer** karta hai – wahi group id `gps-raw-frame-processing-service`, wahi rules, lekin **exactly-once** aur state RocksDB + changelog mein. Ek doosra Kafka Streams app (`gps-streams-analytics`) processed positions se har gaadi ki KTable aur 5-minute windows banata hai. Poori kahani: section 15c.

---

## 5. Topics – 3 trays

| # | Topic | Isme kya hota hai |
|---|---|---|
| 1 | `gps.raw-device-frames` | Frame bilkul waisa jaisa device ne bheja: IMEI, time, lat, lon, speed |
| 2 | `gps.processed-positions` | Jo frame check pass kar gaya + distance, kitne second baad aaya, MOVING/STOPPED |
| 3 | `gps.rejected-frames` | Jo fail hua + **reason** (jaise "Latitude 95.0 is outside -90..90") |
| 4 | `gps.vehicle-events` | Stream consumers ke events: over-speed, stoppage, ignition … |
| 5 | `gps.vehicle-activity` | **Kafka Streams**: har gaadi ka har 5-minute window ka final hisaab (window band hone ke baad ek hi record) |
| 6 | `gps.dead-letters` | Jo record koi consumer handle hi nahi kar paaya – original payload + kahan se aaya + error (section 10a) |
| – | `gps.shadow.processed-positions`, `gps.shadow.rejected-frames` | Sirf **shadow mode** mein: Kafka Streams ka output, listener ke output se milaane ke liye |

- Har topic mein **3 partitions** hain, aur har topic **31 din** ka data rakhta hai (section 10a).
- **Key = IMEI.** Kafka key ka hash leta hai (`murmur2(key) % 3`), to ek truck ke saare frames hamesha **ek hi partition** mein jaate hain → **order bana rehta hai**. Truck ka 10:01 wala ping 10:00 wale se pehle process nahi hoga.
- Topics haath se nahi banane padte – `KafkaTopicDeclarations` service start hote hi khud bana deta hai, aur pehle se bane topics ki settings (retention) bhi theek kar deta hai.
- Topic ke naam sirf ek jagah likhe hain: `KafkaTopicNames.java`. Spelling mistake ka chance hi nahi.

---

## 6. Producers – tray mein maal kaun daalta hai

| | Class | Kis topic mein | Kya bhejta hai |
|---|---|---|---|
| **P1** | `RawGpsFramePublisher` | `gps.raw-device-frames` | Raw frame (abhi simulator call karta hai, baad mein device parser) |
| **P2** | `ProcessedGpsPositionPublisher` | `gps.processed-positions` | Achha, processed position |
| **P3** | `RejectedGpsFramePublisher` | `gps.rejected-frames` | Reject hua frame + reason |

⚠️ **Sabse confusing part:** P2 aur P3 alag app nahi hain. Ye **consumer 1 ke andar** chalte hain. Consumer 1 raw frame padhta hai, process karta hai, aur phir **producer ban ke** result agle topic mein daal deta hai. Matlab consumer 1 = **consumer bhi, producer bhi**.

**Chhoti si baat jo important hai:**
- **P1 Kafka ka wait nahi karta** (device bhi bhej ke aage badh jata hai). Lekin bhejne se **pehle** frame disk pe journal mein likhta hai (fsync). Kafka down ho to frame journal mein rukta hai, gum nahi hota (section 10a).
- **P2 aur P3 wait karte hain** (`.join()`) jab tak Kafka bole "mil gaya". Tabhi raw frame ka offset commit hota hai. Isliye beech mein crash ho jaye to bhi frame gum nahi hota.

---

## 7. Consumers aur Consumer Groups

**Rule: ek group = ek kaam (purpose).**

| # | Consumer group | Kahan se padhta hai | Kaam | Result kahan |
|---|---|---|---|---|
| C1 | `gps-raw-frame-processing-service` | `gps.raw-device-frames` | Validate + movement calculate + duplicate pakdo | Achha → topic 2, kharab → topic 3 |
| C2 | `gps-position-storage-service` | `gps.processed-positions` | Position save karo | PostgreSQL `gps_position` |
| C3 | `gps-live-location-service` | `gps.processed-positions` | Har gaadi ki **sirf latest** position rakho | Redis |
| C4 | `gps-rejected-frame-logger` | `gps.rejected-frames` | Reject ko archive karo + reason log karo | PostgreSQL `rejected_frame` + service ka log |
| C5 | `gps-trip-service` | `gps.processed-positions` | Trips aur stops pehchano (section 15) | PostgreSQL `gps_trip`, `gps_stop` |
| KS | `gps-streams-analytics` | `gps.processed-positions` | **Kafka Streams**: vehicle KTable + 5-min windows (+ shadow parity) | RocksDB stores, `gps.vehicle-activity` |
| DL | `gps-dead-letter-archive` | `gps.dead-letters` | Har dead letter hamesha ke liye save (section 10a) | PostgreSQL `dead_letter` |

C1 (`gps-raw-frame-processing-service`) ab default se **Kafka Streams** chalata hai (listener tab banta hi nahi) – group id same hai, isliye dono taraf switch karne pe offsets wahin se chalte hain.

### Group ka asli funda (sabse important Kafka concept)

- **Alag groups → har group ko HAR message milta hai.** C2, C3 aur C5 teeno ko topic 2 ki poori copy milti hai. Dono ka apna **bookmark (offset)** hai. Redis slow hai to PostgreSQL wala ruka nahi rehta.
- **Ek hi group ke andar → kaam baant jaata hai.** Ek partition ek time pe group ke sirf ek consumer ke paas jaata hai. Humare paas 3 partitions hain aur `concurrency = 3`, to C1, C2, C3, C5 – sab **3 threads** chalate hain, ek thread ek partition. 4th thread daaloge to wo bas baitha rahega, kaam nahi milega.
- **C4 ek hi thread** – reject kam aate hain.

> Desi version: do alag offices ko akhbaar ki **apni apni photocopy** milti hai (alag groups). Ek office ke andar teen clerk **pages baant** lete hain (same group).

`auto-offset-reset: earliest` – **naya** group jiska koi bookmark nahi, wo sabse purane message se padhna shuru karta hai (sirf naye se nahi). Isliye naya consumer group jodo to wo purana data bhi padh sakta hai.

---

## 8. Do raaste: Kafka flow vs Direct flow

Setting: `gps.processing-flow` (`application.yml` mein, default `kafka`).

- **`kafka`** – upar wala poora diagram.
- **`direct`** – Kafka bilkul nahi. Ek hi method call mein: frame → processing → PostgreSQL + Redis. Code wahi same (processing, storage, live location), sirf transport alag.

| | Kafka flow | Direct flow |
|---|---|---|
| Kafka chalu chahiye? | Haan | Nahi |
| PostgreSQL down ho gaya | Frames Kafka mein wait karte hain, consumer retry karta rehta hai | Frame turant fail |
| Achanak bahut traffic | Kafka jhel leta hai | Bhejne wala slow ho jata hai |
| Naya consumer (geofencing…) jodna | Naya group, baaki kuch nahi badalta | Code mein call karna padega |
| Debug karna sabse aasan | | Haan – ek call, ek stack trace |

Ye switch kaise kaam karta hai: `@OnlyInKafkaFlow` aur `@OnlyInDirectFlow` annotations (`@ConditionalOnProperty` ke upar bane hain). Direct flow mein Kafka wale beans **bante hi nahi**, to service Kafka se connect bhi nahi karti.

Direct flow mein `send()` `synchronized` hai – ek time pe ek frame, to ek truck ke frames order mein hi rehte hain.

```powershell
--gps.processing-flow=direct        # start karte waqt switch karo
```

---

## 9. Processing ka andar ka logic (step by step)

Sab kuch `GpsFrameProcessor` mein ek screen pe hai. Ye **plain Java** hai – na Spring, na Kafka, na database. Isliye dono flows same logic use karte hain aur har rule ka unit test hai.

```
raw frame (JSON text)
   → Step 1: validate             (kharab frame → reject)
   → Step 2: pichhle reading se movement nikaalo   (duplicate → reject)
   → onAccepted(processed position)
   → Step 3: ye reading yaad rakho "is device ki sabse nayi"
```

### Step 1 – Validation rules (`GpsFrameValidationRules`)

Pehli problem milte hi reject, saaf English reason ke saath:

| Check | Reason |
|---|---|
| Text JSON hi nahi hai | `Frame is not valid JSON` |
| IMEI khali | `IMEI is missing` |
| Time nahi hai | `Recording time is missing` |
| Time future mein (5 min se zyada) | `Recording time ... is in the future` (device ki ghadi kharab) |
| Lat/lon missing | `Latitude or longitude is missing` |
| Latitude -90..90 se bahar | `Latitude ... is outside -90..90` |
| Longitude -180..180 se bahar | `Longitude ... is outside -180..180` |
| Position 0,0 | `Position is 0,0 - the device has no GPS fix` |
| Speed missing | `Speed is missing` |
| Speed 0..150 se bahar | `Speed ... km/h is outside 0..150` (goods vehicle 150 se upar = device error) |

`RawGpsFrame` mein fields `Double`, `Integer` (wrapper) hain, `double`/`int` nahi – taaki missing value `null` aaye aur clear reason ke saath reject ho, chupchaap 0 na ban jaye.

### Step 2 – Movement (`CalculateMovementStep`)

- Is device ki **pichhli reading** nikaalo (`PreviousReadingStore`).
- **Same time** ki reading dobara aayi → `Duplicate reading` → reject.
- **Purani** reading baad mein aayi (network wapas aaya to device ne purane ping bheje) → `arrivedOutOfOrder = true`. Accept hoti hai (history ke liye), lekin distance/time 0 rehta hai aur ye "sabse nayi" nahi banti.
- **Distance** = haversine formula (`GeoDistanceCalculator`) – dharti ko gola maan ke do points ki seedhi doori.
- **MOVING ya STOPPED** (`MovementRules`):
  - speed ≥ **5 km/h** → MOVING
  - ya pichhle point se ≥ **50 metre** ka jump → MOVING (device speed 0 bole tab bhi)
  - warna STOPPED. Kyun 50 m? Khadi gaadi ka GPS bhi thoda "bhatakta" hai, chhote jump ignore karne hain.

### Step 3 – Yaad rakhna, lekin sahi time pe

Ye step **tabhi** chalta hai jab result successfully deliver ho gaya. Agar Kafka/PostgreSQL down tha aur handler fail hua, to kuch yaad nahi rakha jata – retry pe frame normally process hota hai, duplicate nahi samjha jata. (Is case ka test bhi hai: `aFrameWhoseResultCouldNotBeDeliveredIsProcessedNormallyWhenRetried`.)

### Restart ke baad memory

`InMemoryPreviousReadingStore` – `ConcurrentHashMap`, har device ki ek entry (150 trucks = 150 entries). Restart pe khali ho jata hai, isliye `DatabaseBackedPreviousReadingStore` miss hone pe PostgreSQL se `findFirstByImeiOrderByRecordedAtDesc(imei)` dekh leta hai. Restart ke baad ka pehla frame bhi sahi distance pata hai, 0 nahi.

Ye thread-safe kyun hai? Ek IMEI → ek partition → ek thread. Ek device ko kabhi do threads ek saath touch nahi karte.

---

## 10. Data kabhi gum na ho – reliability ke rules

1. **Offset baad mein commit.** Consumer ka kaam poora hone ke baad hi Spring offset commit karta hai. Beech mein crash = message dobara aayega, gum nahi hoga (**at-least-once**).
2. **Retry with backoff** (`KafkaRetrySettings`): DB, Redis ya Kafka down → 1s, 2s, 4s … max har 60s pe retry, **jab tak wapas na aaye**. Message skip nahi hota.
3. **Toota hua message skip nahi – dead-letter queue mein.** Invalid JSON ya database rule tootna (`DataIntegrityViolationException`) – retry se theek nahi hoga, to turant `gps.dead-letters` mein park (koi anjaan error ho to 3 try ke baad). Peeche wale messages block nahi hote, aur message gum bhi nahi hota (section 10a).
4. **Idempotent storage.** Kafka ek message do baar de sakta hai. `gps_position` mein `UNIQUE (imei, recorded_at)` hai aur save se pehle `existsByImeiAndRecordedAt` check hota hai – ek reading ek hi baar save hoti hai.
5. **Live location mein purana naye ko replace nahi karta.** Late ya replay reading Redis ki newer position ko overwrite nahi karti.
6. **Direct flow mein Redis aur trips best-effort hain.** `gps_position` asli record hai – Redis ya trip detection ki hichki log hoti hai, frame fail nahi hota.
7. **Trip service: state + trip ek hi transaction mein.** Har reading pe `vehicle_motion_state` ka update `WHERE last_recorded_at < :recordedAt` se guarded hai. Dobara aayi reading → 0 rows update → rollback, kuch nahi likha. Isse "message do baar aaya" ka asar **exactly-once** jaisa hota hai (section 15).

---

## 10a. Ek mahina Kafka mein, ek bhi record gum nahi

> Poori gehraai English mein: [docs/DATA_SAFETY_GUIDE.md](docs/DATA_SAFETY_GUIDE.md) – har woh jagah jahan record gum ho sakta tha, aur usko kya band karta hai.

**Desi version:** courier office socho 📦. Counter wala clerk (**ingest journal**) har parcel van ko dene se **pehle** carbon-copy register mein likhta hai, aur entry tabhi band karta hai jab godown (**Kafka**) sign kar de. Godown har parcel **ek mahina** rakhta hai. Delivery boy (**consumer**) parcel kabhi nahi phenkta: dukaan band hai (PostgreSQL down) to bahar wait karta hai, address padhne layak hi nahi (toota record) to **problem-parcel room** (dead-letter queue) mein note ke saath rakh deta hai. Aur ek auditor (**data-safety report**) check karta rehta hai ki koi delivery boy itna peeche to nahi reh gaya ki godown ne uska parcel saaf kar diya.

| Layer | Kya karta hai | Kahan |
|---|---|---|
| **31 din retention** | Har topic 31 din rakhta hai, sirf umar se delete (size se kabhi nahi). Purane 7 din wale topics start pe khud 31 din ho jaate hain | `KafkaTopicDeclarations.durable`, `gps.kafka.retention` |
| **Broker settings** | Consumer bookmarks bhi 31 din, galat spelling wala topic khud nahi banta, peeche reh gaya replica leader nahi banta, single broker record disk pe likh ke hi "ok" bolta hai | `docker-compose.yml`, `setup-kafka.ps1` |
| **Producers** | `acks=all` + idempotent + zstd | `application.yml` |
| **Ingest journal** | Device frames aur vehicle events pehle disk pe (fsync), phir Kafka. Kafka "mil gaya" bole tabhi journal se hatte hain. Outage ya crash ke baad **usi order** mein dobara jaate hain | `journal/`, `gps.journal.*` |
| **Dead-letter queue** | `gps.dead-letters`: listeners aur dono Kafka Streams apps (KIP-1034) toota record yahan park karte hain – original topic, partition, offset, group aur error headers mein | `KafkaRetrySettings`, `DeadLetterProcessingExceptionHandler` |
| **Archive (hamesha ke liye)** | Dead letters PostgreSQL `dead_letter` mein, rejected frames `rejected_frame` mein – Kafka ke mahine ke baad bhi. Rows kabhi delete nahi hoti | `archive/`, `V6__…sql` |
| **Data-safety report** | Har consumer group ka bookmark vs Kafka mein bacha data. Bookmark log start se peeche = data gaya → **FAIL**, `/actuator/health` DOWN | `safety/` |

**Kuch toota to kya hota hai:**

| Kya hua | Kya hota hai |
|---|---|
| Kafka down | Frames journal mein rukte hain, simulator/device ko wait nahi karna padta. Kafka wapas aaya → order mein chale jaate hain |
| Service crash (kill -9) | Restart pe journal padha jaata hai, jo confirm nahi hua tha wo dobara jaata hai. Do baar pahuncha record duplicate store + `UNIQUE (imei, recorded_at)` sambhal leta hai |
| PostgreSQL ya Redis down | Consumer wait karta hai (1s → 2s → … har 60s), **hamesha**. Record Kafka mein intezaar karta hai |
| Record hi kharab | `gps.dead-letters` → `dead_letter` table. Wahan se replay, ya note ke saath discard |
| Kafka Streams ko database fallback nahi mila | Park nahi hota – task fail, naya thread usi record ko dobara process karta hai (record theek tha, DB nahi tha) |

**Dekho kahan:** console mein Storage → **Data safety** (har check, har group ka bookmark vs log start, disk pe kitna data) aur **Dead letters** (replay / discard). API section 16 mein.

**Ek broker ya teen:** default `docker-compose.yml` mein 1 broker, har record ki 1 copy – crash, restart, power cut sab jhel leta hai, lekin disk hi khoyi to data gaya. Teen copies chahiye to:

```powershell
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d --build
```

3 brokers, har record 3 jagah, 2 pe likhne ke baad hi "ok". Ek broker mar jaye to bhi platform chalta rehta hai.

**Dhyan rakho:**
- Consumer 31 din se zyada band raha to Kafka purana data delete kar dega – report **FAIL** dikhayegi, chhupayegi nahi.
- **Direct flow** mein ye kuch nahi hai (na Kafka, na journal, na DLQ) – wo sirf debugging ke liye hai.
- Demo reset (`POST /api/admin/reset`) jaan-boojh ke sab khali karta hai, archive bhi.

---

## 11. Database: gps_tracking (PostgreSQL + Flyway + Hibernate)

Tables Flyway banata hai (`gps-application/src/main/resources/db/migration`), Hibernate sirf validate karta hai.

| Table | Kaam |
|---|---|
| `tenant` | Ek company. `code` chhota, lower-case (`^[a-z0-9][a-z0-9_-]*$`), har position pe stamp hota hai. Status: ACTIVE / SUSPENDED / CLOSED |
| `vehicle` | Ek truck. `registration_no` poore platform pe unique. Owner tenant nahi = **market vehicle** |
| `device` | Tracking device, sirf `imei` se pehchana jata hai. Kind: GPS, FUEL, TEMPERATURE, DOOR, OBD |
| `device_assignment` | Device kis gaadi mein **kab se kab tak** laga tha (`valid_from` – `valid_to`) |
| `gps_position` | Processed readings + `tenant_code` + `vehicle_registration_no`. Unique `(imei, recorded_at)` |
| `vehicle_motion_state` | Trip service ki yaaddasht: har device ki ek row – chal rahi hai ya khadi, last reading kab ki, pichhle 3 minute ki readings (`reading_window` jsonb) |
| `gps_trip` | Ek trip: start/end time + jagah, distance, duration, max/avg speed, `status` OPEN/CLOSED. Unique `(imei, start_recorded_at)` |
| `gps_stop` | Ek stop (gaadi khadi): start/end, jagah, duration. Unique `(imei, start_recorded_at)` |
| `gps_trip_late_reading` | Jo reading late aayi aur trip service ne skip ki – ek baar note hoti hai (baad wale "repair job" ki list) |
| `dead_letter` | Har dead letter, hamesha ke liye: payload, kahan se aaya, error, status PARKED / REPLAYED / DISCARDED (section 10a) |
| `rejected_frame` | Har rejected frame original payload + reason ke saath (log rotate ho jaata hai, ye nahi) |

**Kuch design decisions:**
- **Period mein store, "current vehicle" nahi.** Late reading ko us gaadi se match karna hai jisme device **reading ke time** laga tha, aaj wali gaadi se nahi.
- **Exclusion constraint** (`btree_gist` extension): `EXCLUDE USING gist (device_id WITH =, tstzrange(valid_from, valid_to) WITH &&)` – ek device ek time pe sirf ek gaadi mein. Database khud guarantee karta hai.
- `gps_position` mein tenant/vehicle **readable code** hain, foreign key nahi – telemetry fast likhi jaati hai aur baad mein time-series store (ClickHouse) mein jayegi.
- **Applied Flyway file kabhi edit mat karo** – checksum mismatch pe service start hi nahi hogi. Naya `V7__...sql` banao (abhi V6 tak bane hain).
- Indexes: `(vehicle_registration_no, recorded_at DESC)` aur `stored_at DESC`.

---

## 12. Master data: IMEI → gaadi → company

Device ko sirf apna IMEI pata hai – usko nahi pata wo kis truck ya company ka hai. Ye mapping **master data** hai:

- `SimulationMasterDataSeeder` – simulator jin devices ko chalata hai unke tenant, vehicle, device, device_assignment naye database mein register karta hai.
- `TenantCodeFormatter` – company naam jaise `"DEMO-TRANSPORT-JSR"` ko code `"demo-transport-jsr"` banata hai.
- `DeviceVehicleDirectory` – "is IMEI wala device **is moment** pe kis gaadi mein tha?" Answer device_assignment se aata hai aur period (from–to) ke saath **cache** hota hai. 150 trucks har 2 second = har message pe DB query nahi. Reading cached period ke bahar ho (device shift hua) to DB se dobara poochta hai.
- Device kisi gaadi mein nahi mila → position phir bhi save hoti hai, vehicle/tenant khali + WARN log.

---

## 13. Simulator – nakli trucks jo asli jaise chalte hain

`GpsDeviceSimulatorService` – jab tak asli device parser nahi judta, ye fleet ki acting karta hai:

1. smart_truck (READ-ONLY) se **150 alag devices ki sabse nayi trip** uthata hai (sirf wo trips jinme kam se kam 200 pings hon).
2. Un devices/gaadiyon ko naye database mein register karta hai.
3. **Faithful timing (default):** har ping apna **asli gap** rakhta hai (smart_truck mein ~1 minute). Replay clock 2 ghante pichhe se shuru hota hai, **30x tez** chalta hai jab tak "abhi" tak na pahunch jaye (~4 min ka burst, ~75 frames/s), phir **real time** (~2.5 frames/s). Isse distance/time wali speed, "60 sec over-speed", "3 min stop", idling – sab asli lagte hain. Pehle (compressed) har 2 sec mein ek round bhejte the – 1 minute ki driving 2 sec mein, isliye speed 30 guna galat aati thi aur trips mein avg speed > max speed dikhti thi. Wo mode ab bhi hai: `timing: compressed`.
4. Trip khatam → **ulta wapas chalata hai** (driving back), taaki gaadi start point pe "teleport" na kare. Fleet dobara start ho to replay **DB ki sabse nayi reading ke baad** se shuru hota hai – purane time pe "late" readings ka dher nahi banta.
5. smart_truck mein ignition/odometer nahi hai, isliye simulator **aadhe devices** ko ignition wire deta hai (baaki = virtual ignition), **aadhon** ko odometer (smart_truck ka `i_dist` jodta hai; baaki GPS se napte hain), aur **chauthai drivers "idler"** hain jo rukne par engine chalu rakhte hain (stoppage + idling alarm ke liye).

| Setting (`gps.simulator.*`) | Default | Matlab |
|---|---|---|
| `vehicle-count` | 150 | Kitni gaadiyan ek saath |
| `pings-per-vehicle` | 500 | Har gaadi ke kitne ping replay |
| `minimum-pings-per-trip` | 200 | Sirf itne ya zyada ping wali trips |
| `delay-between-rounds-millis` | 2000 | 150 gaadi / 2 sec = **75 frames per second** |
| `replay-as-live` | true | Frame pe **abhi ka time** lagao – data live lagta hai, replay duplicate nahi banta |
| `repeat-forever` | true | Trip khatam → wapas chalao → phir aage … data aata rahe |
| `start-when-application-starts` | false (Docker mein true) | Service start hote hi fleet chalu |

| `timing` | faithful | `faithful` (asli gaps) ya `compressed` (purana tarika) |
| `catch-up-minutes` / `catch-up-speed-factor` | 120 / 30 | Kitna pichhe se aur kitna tez catch-up |
| `devices-with-ignition` / `devices-with-odometer` / `idling-devices` | 0.5 / 0.5 / 0.25 | Kitne devices ke paas wire / odometer, kitne idler |

⚠️ Compressed timing (75 frames/sec) pe PostgreSQL roz lagbhag **65 lakh rows** badhta hai. Faithful timing live hone ke baad ~2.5 frames/sec.

**Ek baat jo data ne dikhayi:** in trucks ki **max speed hi 60 km/h** hai (speed governor). Isliye 60 km/h ka over-speed rule is data pe kabhi trigger nahi hota. Demo mein over-speed dikhana ho to UI se limit 40-45 kar do (Speed violation → Speed rules) – 40 pe test kiya: NL01AG3477, 5 pings, max 55 km/h, GPS ne 53.3 confirm kiya, verdict GPS_VERIFIED.

**smart_truck read-only kaise pakka hai?** `SmartTruckGpsReader` ka connection pool `setReadOnly(true)` pe khulta hai (MySQL khud INSERT/UPDATE/DELETE mana kar dega), class mein sirf SELECT hai, aur ye pool Spring ka main DataSource bhi nahi hai – galti se bhi mix nahi hoga. smart_truck down ho to service start ho jaati hai, sirf padhte waqt fail hota hai. Time India time (`Asia/Kolkata`) se `Instant` (UTC) mein convert hota hai.

---

## 14. Redis – live board

RedisInsight mein `gps:` filter karo:

| Key | Type | Kya hai |
|---|---|---|
| `gps:live:vehicle:{imei}` | hash | Latest position: gaadi, tenant, lat, lon, speed, status, recordedAt, updatedAt |
| `gps:live:positions` | geo set | Har device ka ek point. `GEOSEARCH gps:live:positions FROMMEMBER <imei> BYRADIUS 5 km` – geofencing ki shuruaat |
| `gps:engine:stage:{stage}` | hash | Ek producer/consumer ka load: total, per second, failures, last message |
| `gps:engine:stage:{stage}:by-imei` | hash | Us stage ne har IMEI ke kitne messages handle kiye |

Saari key names ek jagah: `RedisKeyNames.java`.

**Redis kyun, sirf PostgreSQL kyun nahi?** "Truck X abhi kahan hai?" ke liye crore rows wali `gps_position` table ko baar baar query karna mehnga hai. Redis memory mein hai, har gaadi ki ek entry, millisecond se kam mein jawab. Docker mein Redis disk pe save nahi karta – live position agle reading se khud ban jaati hai.

### 14a. Connections – is laptop ka Redis ya HP home server ka (UI: Developer → Connections)

HP home server laptop ke hotspot **`SANJOY-HP 0161`** pe hamesha `192.168.137.1` hai. Wahan teen cheezein chalti hain:

| HP pe | Address | Hamare system mein kya role |
|---|---|---|
| **Valhalla** (poore India ka map) | `http://192.168.137.1:8002` | Routing engine – route deviation isi ko call karega. Address UI se badlo, **Test with a route** Kolkata ka chhota route nikaal ke dikhata hai |
| **Redis 7.4** | `192.168.137.1:6379` | Optional: consumers ki working memory (live position, state, speed rules) yahan bhi rakh sakte ho – **database 1** pe |
| **Ingestion API** (FastAPI) | `http://192.168.137.1:8000` | HP ka apna darwaza devices ke liye: `POST /ingest` → HP ka Redis (`gps:points`, `last:*`). **Hamari Kafka pipeline ka hissa nahi** – sirf health dikhti hai, address yahan se nahi badalta |

- **Redis switch bina restart:** *This laptop / HP home server / Custom* chuno → **Test** (ping, memory, eviction policy, us database mein kya hai) → **Use this Redis**. Speed rules (`gps:config:*`) saath copy hote hain; gaadi ki state agle readings se dobara banti hai. Choice PostgreSQL (`connection_setting`) mein save – agle start pe bhi wahi.
- **Database 1 kyun?** HP ka ingestion API database 0 use karta hai. Database 0 mein uski keys (`gps:points`, `devices`) dikhi to switch **mana** ho jaata hai. Empty DB bhi ab sirf hamari telemetry keys (`gps:live:*`, `gps:state:*` …) mitata hai – kisi aur program ki key kabhi nahi.
- **Dhyan rakho:** HP ka Redis 256 MB, aur bhar jaaye to keys khud delete karta hai (LRU) – page pe warning aati hai. Har call Wi-Fi ke paar jaati hai. HP so gaya to consumers ruk ke retry karte hain; Kafka mein data 31 din safe.
- HP ka Redis use ho raha ho to is laptop ka Redis band kar sakte ho: `docker compose stop redis` (gps-service ab uske bina bhi start hota hai).
- Defaults badalne hon to env: `VALHALLA_URL`, `HOME_SERVER_REDIS_URL`, `GPS_INGESTION_API_URL` (application.yml → `gps.connections`).

---

## 15. Trips aur stops – gps-trip-service

Pehla "downstream" consumer: `gps.processed-positions` padh ke batata hai **gaadi kab chali, kab ruki, kitna chali**. Logic Traccar (open-source GPS server) ke `NewMotionProcessor` se port kiya hai – production mein saalon se chal raha hai – aur Traccar ki do galtiyan yahan nahi hain (neeche dekho).

**Teen settings** (`application.yml` → `gps.trips`, Traccar ke defaults):

| Setting | Default | Matlab |
|---|---|---|
| `min-distance-meters` | 200 | Jahan ruki thi wahan se 200 m door gayi → **trip shuru** |
| `min-duration-seconds` | 180 | 3 minute tak koi reading 200 m door nahi → **ruk gayi** (stop) |
| `stop-gap-seconds` | 3600 | 1 ghante se zyada device chup → trip **chup hone se pehle wali reading** pe band |

**Kaise sochta hai (desi version):**
- **Khadi gaadi:** "Jahan ruki thi wahan se 200 m door gayi kya?" Haan → trip shuru. Start time **peeche khiska ke** wahan rakhta hai jahan gaadi sach mein chalna shuru hui thi (pichhli readings dekh ke), wahan nahi jahan 200 m poore hue.
- **Chalti gaadi:** "Pichhle 3 minute ki koi reading abhi wali se 200 m door hai?" Nahi → gaadi ruk gayi, trip band, stop shuru.
- Ye **distance** pe chalta hai, har reading ke `MOVING/STOPPED` pe nahi. Isliye khadi gaadi ka GPS jitter (10-50 m idhar udhar) kabhi fake trip nahi banata.
- **Chup device (gap > 1 ghanta):** trip aakhri reading pe band. Agar is beech gaadi saaf saaf kahin aur pahunch gayi (200 m se zyada, theek speed pe) to stop → trip → stop record hota hai.
- **Device doosri gaadi mein lag gaya** (`device_assignment` badla) → khuli trip band. Ek trip kabhi do gaadiyon ki nahi hoti. Tenant/gaadi har reading ke **apne time** wale assignment se aate hain.

**Code kahan hai:**

| Kya | Kahan |
|---|---|
| Pure function `updateState(state, reading, settings)` → naya state + events + trips + stops. Spring nahi, DB nahi | `gps-processing/.../trip/TripMotionProcessor.java` |
| Uske tests – Traccar wali drive ka replay (reading 10 pe MOVING, reading 40 pe STOPPED), late reading, gap, jitter, gaadi badalna | `gps-processing/src/test/.../trip/TripMotionProcessorTest.java` |
| Kafka listener (group `gps-trip-service`, 3 threads) | `gps-connectivity/.../kafka/TripDetectionListener.java` |
| Ek reading = ek transaction | `gps-application/.../trip/TripDetectionService.java` |
| SQL (guarded upserts) | `VehicleMotionStateStore`, `TripAndStopStore` |
| Tables | `V3__create_vehicle_motion_state_table.sql`, `V4__create_gps_trip_and_gps_stop_tables.sql` |

**Ek reading ka safar (ek transaction):**
1. `vehicle_motion_state` se device ki state padho.
2. Reading `last_recorded_at` se nayi nahi hai → **skip**. Late reading (`arrivedOutOfOrder`) ho to `gps_trip_late_reading` mein ek baar note + us trip ka `late_reading_count` +1. Distance mein kuch nahi judta.
3. Pure function naya state + badli hui trips/stops nikalta hai.
4. State save – `WHERE last_recorded_at < :recordedAt`. 0 rows = kisi aur delivery ne pehle hi laga diya → rollback, aage kuch nahi likhna.
5. Trips aur stops `(imei, start_recorded_at)` pe **upsert**. Khuli trip (`OPEN`) har reading pe lambi hoti hai; band hone pe `CLOSED`.

**Traccar ki galtiyan jo yahan nahi hongi:**
- Traccar ne ek 5 minute late reading se trip ka distance **-1020 m** bana diya tha (odometer end − start). Yahan distance = in-order readings ke `distanceFromPreviousMeters` ka **jod**, late reading kuch nahi jodti, aur table mein `CHECK (distance_m >= 0)` bhi hai.
- Traccar mein unique key nahi hai, to same reading do baar save ho gayi. Yahan `UNIQUE (imei, start_recorded_at)` + guarded state update.

**Dekho kya bana:**
```powershell
Invoke-RestMethod http://localhost:8080/api/trips/count
Invoke-RestMethod "http://localhost:8080/api/trips?imei=0861000000000001"
Invoke-RestMethod "http://localhost:8080/api/stops?imei=0861000000000001&from=2026-10-19T00:00:00Z&to=2026-10-20T00:00:00Z"
docker compose logs gps-service | Select-String "stage=trip-detection"    # "started a trip" / "stopped" lines
```
Dashboard pe naya box: **Trips & stops → PostgreSQL** (msg/s aur lag).

**Abhi nahi hai (aage):** `gps.events` topic pe `DEVICE_MOVING/STOPPED` events (roadmap step 3), per-vehicle/tenant settings, aur late readings wali trips ko `gps_position` se dobara calculate karne wala repair job.

---

## 15a. Distance – Traccar ka logic (rev_engineer/traccar-rnd se)

`CalculateMovementStep` ab Traccar ke `DistanceHandler` + `FilterHandler` jaisa chalta hai (`DistanceCalculator` = Traccar ka port, WGS-84 equatorial radius):

1. **Device ka apna distance pehle:** odometer hai aur believable hai → uska delta (`DEVICE_ODOMETER`).
2. Warna **haversine** previous position se (`GPS`).
3. **Coordinates filter:** khadi gaadi ka GPS 10 m se kam "bhatke" → position pichhli wali pe clamp, distance 0 (`JITTER_FILTERED`). Parked truck roz ke kilometer nahi jodta.
4. **Max-speed filter:** 200 km/h se tez chhalang (aur 500 m se lambi) → GPS glitch/relocation, count nahi (`JUMP_FILTERED`), nayi position rakhi.
5. `totalDistance` = pichhla total + counted distance (Traccar "totalDistance"), `gps_position.total_distance_m` mein.
6. **Late reading kabhi naye se nahi napi jaati** (Traccar napta hai – R&D mein -1020 m ki trip bani thi): distance 0, `OUT_OF_ORDER`.

Har reading pe `distance_source` aur `implied_speed` bhi jaata hai. Settings: `gps.distance.*`.

## 15b. Chaar stream consumers (Kafka ke baad)

Sab `gps.processed-positions` padhte hain, har ek ka **apna consumer group** (apna offset) – ek slow weather API speed check ko nahi rokti. Logic `gps-processing` mein **pure Java** (unit tested), state **Redis** mein, events **`vehicle_event`** table + Redis list + Kafka topic **`gps.vehicle-events`**.

| Consumer group | Kya karta hai | Algorithm |
|---|---|---|
| `gps-speed-violation-service` | Over-speed + speedometer vs GPS | Rule **Redis** mein (`gps:config:speed-rule:*`): vehicle → company → default (Traccar ka device→group→server lookup). Episode tab jab **lagatar N pings** limit ke upar **aur** kam se kam X seconds (60). **Hysteresis**: limit − 5 se neeche aaye tabhi khatam. Har ping pe speedometer ko **pichhle 2-3 pings ki GPS speed** (path ÷ time, odometer ho to uska) se cross-check: `GPS_VERIFIED` / `SENSOR_SUSPECT` (tamper). Lagatar mismatch → `SPEED_SENSOR_MISMATCH`. Lambi chuppi episode tod deti hai. |
| `gps-vehicle-state-service` | Har gaadi ki state + history | `gps:state:vehicle:{imei}`: heading (bearing), acceleration, harsh braking/acceleration (sirf <10 s gap wali readings pe), **Kalman filter** (constant velocity, local metres, Mahalanobis outlier gate, 3 reject → reset) ka smooth track + distance, data-quality counters, moving/standing time. **History:** Redis **Stream** `gps:history:{imei}` (XADD + XTRIM MAXLEN ~1000) – historical context. |
| `gps-stoppage-service` | Stoppage / parking | **Streaming stay-point** (radius 100 m, 3 min, "khada" = speedometer ≤ 3 km/h **aur** distance/time speed bhi kam), arrival pe back-dated, centroid position, paas-paas ke stops merge. **Ignition ON → STOPPAGE** (alarm) → **agents**: traffic (fleet ki engine-on gaadiyan 1 km mein, zyada tar slow → jam; TomTom key ho to wo bhi) + weather (Open-Meteo, 0.1° cell, 10 min cache) → verdict `JUSTIFIED_TRAFFIC / JUSTIFIED_WEATHER / UNEXPLAINED (HIGH)`. **Ignition OFF → PARKED** (neutralised, koi alarm nahi). Stop ke beech ignition badle to kind badal jaata hai. |
| `gps-ignition-service` | Ignition, engine hours, idling | Wire ho to **device ignition** (Traccar IgnitionEventHandler), warna **virtual ignition** (chal rahi = ON, 5 min khadi = OFF), debounce, back-dating. Engine hours (Traccar EngineHoursHandler, lambi chuppi credit nahi). **Idling** = engine ON + ≤ 3 km/h + khadi > 5 min → `IDLING`. |

Settings: `gps.stream.*` (stoppage, ignition, vehicle-state, enrichment). Speed rules UI se (Speed violation page) ya API se.

**Vehicle inspector** (UI: Live → Vehicle inspector): gaadi chuno → har Redis key (live, state, speed, ignition, stoppage) field-by-field, history stream (speed chart + table), events, processing ki memory, trip detector ki state.

**Empty DB** (UI: Developer → Demo tools; API `POST /api/admin/reset`): fleet roko → saare consumers pause + topic ke end pe seek → saare platform topics ke messages delete → telemetry tables TRUNCATE (master data aur speed rules bachte hain) → Redis ki hamari telemetry keys (`gps:live:*`, `gps:state:*` …; config aur doosre programs ki keys chhod ke) delete → memory/counters zero → **Kafka Streams reset** (local state, consumer groups, changelog topics saaf, apps dobara start) → consumers resume → fleet dobara (2 ghante ka catch-up). ~6 sec. Production mein band: `gps.admin.reset-enabled=false`.

---

## 15c. Kafka Streams – processing layer ka engine

> Poori gehraai English mein: [docs/KAFKA_STREAMS_GUIDE.md](docs/KAFKA_STREAMS_GUIDE.md) – Kafka 4.3 ke [architecture guide](https://kafka.apache.org/43/streams/architecture/) ka har concept, is code mein kahan hai.

**Desi version:** pehle helper (listener) "har truck ne pichhli baar kya bheja" apni **jeb ki diary** (memory) mein rakhta tha. Ghar gaya (restart) to diary gayi, phir munshi ke register (PostgreSQL) se poochta tha. Ab har counter (partition) ka apna **locker** (state store, RocksDB) hai, aur locker ka har page back-office ke **ledger** (changelog topic, Kafka) mein bhi copy hota hai. Helper badla to naya helper wahi locker kholta hai, ya ledger se dobara bana leta hai. Aur teen kaam – tray pe maal rakhna, locker mein likhna, order slip pe tick – **ek hi thappa** (transaction) hain: ya teeno, ya koi nahi.

**Do apps, service ke andar hi (Kafka Streams library hai, cluster nahi):**

```
normalizer  (application.id = gps-raw-frame-processing-service, exactly-once)
  gps.raw-device-frames ─► normalize-frame ─► split ─┬─► gps.processed-positions   (timestamp = recordedAt)
                            previous-reading-store    └─► gps.rejected-frames
                            seen-reading-store

analytics   (application.id = gps-streams-analytics, at-least-once, read_committed)
  gps.processed-positions ─► group by IMEI ─┬─► vehicle-table-store    (KTable: har gaadi ki latest state)
     (event time = recordedAt)              └─► 5 min window + 2 min grace ─► gps.vehicle-activity (band hone pe ek record)
```

**Architecture guide ke concepts, yahan:**

| Concept | Is project mein |
|---|---|
| Partition + key | 3 partitions, key = IMEI. Normalizer **check** karta hai: record key ≠ frame ka IMEI → reject (warna ek gaadi ki state do jagah bat jaati) |
| Task | Har app ke 3 tasks: `0_0`, `0_1`, `0_2` – task `0_1` hamesha partition 1 ka maalik |
| Threads | Har app 3 stream threads, ek task per thread. Doosra instance chalao → Kafka tasks baant deta hai |
| State store + changelog | `previous-reading-store`, `seen-reading-store`, `vehicle-table-store`, `activity-window-store` – sab RocksDB mein, har ek ka compacted changelog topic |
| Restoration / standby | Task naye instance pe gaya → changelog se state wapas. `standby-replicas: 1` (2+ instances pe) → garam copy ready |
| Exactly-once | Output record + state update + input offset = ek transaction |
| Event time + grace | Window device ke time (`recordedAt`) pe, 2 min late reading bhi apne window mein ginti |
| Interactive Queries | `GET /api/streams/...` seedha RocksDB store padhta hai; doosre instance ke paas wali gaadi → wahan forward |

**Teen naye rules jo listener mein nahi the:**
- **Key contract** – frame ka record key uska IMEI hi hona chahiye.
- **Duplicate store** – listener sirf "sabse nayi reading dobara aayi" pakadta tha. Device reconnect hoke **purani** readings dobara bheje, to listener unko late maan ke dobara le leta tha. Ab 24 ghante ki har reading yaad hai (window store) → `Duplicate reading`.
- **Stale** – device ki sabse nayi reading se 24 ghante se purani reading → `Stale reading` (reject topic mein, payload ke saath – kuch gum nahi).

**Kaun normalize karega – ek hi maalik (`gps.streams.normalization`):**

| Mode | Raw → position kaun | Kafka Streams aur kya |
|---|---|---|
| `owner` (default) | Kafka Streams | analytics |
| `shadow` | listener | wahi logic `gps.shadow.*` mein + **parity** (dono ka output reading-by-reading match) |
| `off` | listener | sirf analytics |

**Hand-over ka jugaad:** Kafka Streams ka `application.id` hi uska consumer group hai. Isliye owner normalizer listener ka hi group id use karta hai. Listener se Kafka Streams pe (ya wapas) switch karo, to kaam **committed offsets se hi aage** chalta hai – na replay, na gap. Dono band hote waqt group chhod dete hain, to naya maalik turant partitions le leta hai (45 sec ka intezaar nahi).

**Shadow parity kaise:** dono outputs IMEI se keyed hain, partitions same hain, aur dono ka timestamp `recordedAt` hai. Isliye **0 ke join window** wala outer join ek hi reading ke dono results ko jod deta hai. Verdict: `MATCH`, `MISMATCH` (kaunsa field), `WARMUP` (shadow ki pehli reading), `ONLY_LISTENER` / `ONLY_STREAMS`.

**Dekho kahan:**

| Kya | Kahan |
|---|---|
| Apps, topology, threads → tasks → partitions, stores, restore, metrics | UI: Processing → **Kafka Streams** → Topology & tasks · `GET /api/streams/overview` |
| Har gaadi ki KTable row | Vehicle table · `GET /api/streams/vehicles`, `/api/streams/vehicles/{imei}` |
| Fleet per 5-min window | Activity windows · `GET /api/streams/fleet/activity?windows=24` |
| Listener vs Kafka Streams | Shadow parity · `GET /api/streams/parity` |
| Health | `/actuator/health` → `kafkaStreams` |

**Settings (`gps.streams.*`):**

| Setting | Default |
|---|---|
| `enabled` | `true` |
| `normalization` | `owner` |
| `threads` | `3` |
| `standby-replicas` | `0` |
| `replication-factor` | `-1` (broker default) |
| `state-dir` | Docker: `/var/lib/gps-streams` (volume pe) |
| `advertised-host` | `localhost` |
| `max-lateness`, `seen-readings-retention` | `24h`, `24h` |
| `window-size`, `window-grace`, `window-retention` | `5m`, `2m`, `6h` |
| `parity-grace` | `2m` |
| `rocksdb-off-heap-mb` | `64` |
| `normalizer-guarantee`, `analytics-guarantee` | `exactly_once_v2`, `at_least_once` |
| `properties.*` | jaise `client.rack` |

Docker mein env se badlo, jaise `GPS_STREAMS_NORMALIZATION=shadow`.

**Dhyan rakhne wali baat:** exactly-once wale topics (`gps.processed-positions`, `gps.rejected-frames`) mein har transaction ek **commit marker** likhta hai. Isliye "latest − earliest offset" messages se zyada dikhata hai. msg/s aur totals sahi hain, lag bhi sahi hai. Saare listeners `isolation-level: read_committed` pe padhte hain – aborted transaction ka record kabhi nahi dikhta.

**Code kahan:**
- `gps-streams/` (plain Java, Spring nahi):
  - `normalize/` – NormalizationTopology, NormalizeFrameProcessor
  - `analytics/` – AnalyticsTopology, VehicleSnapshot, ActivityWindow, ParityVerdict
  - `runtime/` – StreamsApp, RestoreTracker, BoundedMemoryRocksDbConfig, StreamsIntrospection
- Spring wiring + IQ API: `gps-application/.../kafkastreams/`
- Tests: `.\mvnw.cmd -pl gps-streams test` – 23 tests, `TopologyTestDriver` pe, broker ke bina.

---

## 15d. Reports – din aur mahine ka hisaab (UI: Reports)

Din ya mahine ke end pe har gaadi ka hisaab: kitna chali, kahan kitni der khadi rahi, engine kitni der idle chala,
diesel ka kharcha, aur **kitna bach sakta tha**. Har number ke neeche **"How is this calculated?"** drop-down – formula
usi number ki values ke saath.

| Page | Kya milta hai |
|---|---|
| **Day report** | Har gaadi ka din (IST raat 12 se raat 12): distance, driving/standing time, stops, idling, max speed, over-speed, fuel, ₹ kharcha, ₹ bachat. Row pe click = poora breakdown. Print/PDF, CSV |
| **Month report** | Wahi mahine bhar ka + fleet roz ka bar chart |
| **Vehicle day sheet** | Ek gaadi ka din time order mein: har trip (km, speed) aur har stop (kitni der, kahan, engine on/off, idling) |
| **Fuel & savings** | Business case: idling aur tez driving se kitna ₹ gaya, kaun sabse zyada, target slider – mahine/saal ki bachat, CO₂ |
| **Cost assumptions** | Diesel/petrol, ₹ per litre, mileage, idle burn, economical speed, penalty % – badlo to har report turant naye daam se |
| **Clear database** | Report print karke database khaali – reports ke numbers by default bach jaate hain |

**Formula (ek line mein):** fuel = distance ÷ mileage + idling ghante × idle burn + (economical speed se upar ke km ÷
mileage × penalty %); bachat = idling + tez-driving wala fuel × ₹ per litre. Defaults: ₹92/L, 4 km/L, 2.5 L/h, 60 km/h, 20 %.

**150 gaadiyon pe bhi tez kyun:** reports kabhi positions scan nahi karte. `vehicle_day_summary` mein har gaadi ka
har din ek row (150 gaadi = 150 row/din, ~4,650/mahina); job har 5 min aaj + kal ka summary banata hai (ek SQL, ek din
ki positions, `recorded_at` index se). Poori detail: [docs/REPORTS_GUIDE.md](docs/REPORTS_GUIDE.md).

---

## 15e. TMS – teen login, shipments, device ↔ gaadi mapping (UI: TMS, /transporter, /consigner)

> Poori kahani English mein: [docs/TMS_GUIDE.md](docs/TMS_GUIDE.md). Naya module jodna: [docs/MODULE_PLUGIN_GUIDE.md](docs/MODULE_PLUGIN_GUIDE.md).

**Desi version:** hum GPS wale hain 📡. Truck company (**transporter**) ke trucks mein humara device laga hai, aur Tata Steel jaisi company (**consigner**) apna maal (steel) un trucks se **consignee** tak bhejti hai. Teeno ko alag cheez dekhni hai, to login pe teen option:

| Login (role) | Kya dikhta hai |
|---|---|
| **GPS provider** (`DEVELOPER`) | Sab kuch + engine: devices, device ↔ gaadi mapping, companies & users, places (geofence), module registry + replay |
| **Transporter** | Apne trucks (live), jo shipments woh le ja raha hai, detention, on-time %, alerts |
| **Consigner** | Apne shipments plant se consignee tak, ETA, delay, transporter scorecard, plant detention. Truck **sirf tab** dikhta hai jab us pe uska maal hai |

Demo login: teeno role ke liye `abc` / `abc` (`gps.auth.demo-accounts`). Token service sign karti hai; transporter URL badal ke doosre ka data nahi dekh sakta.

- **Shipment file** = smart_truck ka `tta_trips` (44 column). Excel, CSV ya seedha copy kiye rows (`'754710', '540', ... NULL`) – header ho to naam se, na ho to column order se. Pehle **preview** (kuch nahi likhta), phir import. Same file dobara = "N unchanged" (trip no + transporter = ek shipment).
- **Shipment ↔ GPS**: gaadi number → `device_assignment` (mapping, dropdown se) → IMEI → readings. Origin/destination naam se **place** (geofence) milta hai – smart_truck ke 87 geofence migration V9 mein aa gaye.
- **Shipment tracking module**: plant fence se bahar = departure, destination fence ke andar = arrival, ETA ke baad = delayed. Hamesha **sabse pehli** reading jo saabit kare – late ya replay reading se jawab nahi badalta.
- **Modules plug-in**: speed, vehicle state, stoppage, ignition, shipment tracking – sab `GpsModule` (gps-module-api). Naya module = ek class + `gps.modules.<id>.enabled`; apna consumer group, retry/DLQ, replay platform deta hai.


## 16. Engine dashboard aur REST APIs

**Dashboard** (http://localhost:8080) har 2 second refresh:
- **Producers:** msg/sec, 60 second ki sparkline, total, failures, devices, har partition ka load.
- **Topics:** har partition ke offsets, kitne messages rakhe hain, ab tak kitne likhe gaye (Kafka se seedha poocha – `KafkaTopologyInspector`, AdminClient se).
- **Consumers:** group state (STABLE?), kaunsa consumer kaunsa partition, msg/sec, kitna khaya, **lag per partition**.
- **Devices:** IMEI ka hissa type karo → filter. Device pe click → uska partition, har stage ne uske kitne messages liye, Redis wali live position.

Load counters (`PipelineMetrics`, `StageCounters`) memory mein hain – har message pe network call nahi. 60 one-second slots ka ring, last 10 sec ka average. Har 5 sec copy Redis mein, har 30 sec ek `ENGINE` summary log line (`EngineReportingJobs`).

| API | Kaam |
|---|---|
| `GET /api/engine/overview` | Poora dashboard ek JSON mein |
| `GET /api/engine/devices?imei=0861` | Devices list, IMEI filter |
| `GET /api/engine/devices/{imei}` | Ek device detail + live position |
| `POST /api/simulator/start?vehicleCount=150&pingsPerVehicle=500` | Fleet chalu |
| `POST /api/simulator/stop` | Fleet band |
| `GET /api/simulator/status` | Simulator abhi kya kar raha |
| `POST /api/simulator/send-single-frame` (body: raw frame JSON) | Ek frame bhejo – kharab frame bhej ke reject dekhne ke liye mast |
| `GET /api/gps-positions/count` | DB mein kitni positions |
| `GET /api/gps-positions/recently-stored` | Last 20 saved |
| `GET /api/vehicles/{registrationNo}/latest-position` | Gaadi ki latest position |
| `GET /api/trips?imei=…&from=…&to=…` | Trips jo is period se overlap karti hain (imei na do to sab devices, from/to na do to last 24 ghante) |
| `GET /api/stops?imei=…&from=…&to=…` | Stops, same rules |
| `GET /api/trips/count` | Kitni trips/stops OPEN aur CLOSED |
| `GET /api/events?type=OVERSPEED,STOPPAGE&imei=…&status=OPEN&limit=100` | Stream consumers ke events (vehicle_event) |
| `GET /api/events/summary` | Har type ke events: last 24 ghante, abhi chalu |
| `GET /api/speed/rules` · `PUT /api/speed/rules/{scope}` · `DELETE …` | Redis wale speed rules (default, tenant:{code}, vehicle:{reg}) |
| `GET /api/speed/active` | Abhi limit ke upar wali gaadiyan (CANDIDATE / VIOLATING) |
| `GET /api/vehicles/{imei}/state?history=50` | Tracking window: gaadi ka saara cache (Redis keys), history stream, events, processing memory, trip state |
| `GET /api/vehicles/states` | Har gaadi ka vehicle-state hash |
| `GET /api/streams/overview` | Kafka Streams: dono apps, topology, threads/tasks, stores, restore, metrics |
| `GET /api/streams/vehicles` · `/api/streams/vehicles/{imei}` | Vehicle KTable (Interactive Queries), ek gaadi ka partition + windows |
| `GET /api/streams/fleet/activity?windows=24` | Fleet per 5-minute event-time window |
| `GET /api/streams/parity` | Shadow mode: listener vs Kafka Streams |
| `GET /api/data-safety` | Data-safety report: retention, har group ka bookmark vs log start, journal, dead letters (section 10a) |
| `GET /api/dead-letters?status=PARKED&topic=…` · `/api/dead-letters/summary` | Dead letters ki list aur ginti |
| `POST /api/dead-letters/{id}/replay` · `POST /api/dead-letters/replay?topic=…` | Dead letter wapas uske original topic mein (ek, ya sabse purane parked) |
| `POST /api/dead-letters/{id}/discard?note=…` | Replay nahi karna – note ke saath DISCARDED |
| `GET /api/rejected-frames?imei=…` · `/api/rejected-frames/summary` | Rejected frames ka archive |
| `POST /api/admin/reset?restartFleet=true` | **Empty DB** (demo practice) – neeche section 15b |
| `GET /actuator/health` | DB + Redis + `kafkaStreams` + `dataSafety` UP/DOWN |

Try karo – ek kharab frame:
```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/simulator/send-single-frame -ContentType 'application/json' `
  -Body '{"imei":"0861000000000003","recordedAt":"2026-10-15T05:17:11Z","latitude":95.0,"longitude":86.2,"speedKmph":10}'
```
Log mein dikhega: `Frame rejected: Latitude 95.0 is outside -90..90`.

### PulseStream IoT console (Next.js) – `gps-dashboard-ui/`

Operations ka web UI. Upar ek hi bar mein fixed sections (Live, Processing, Alerts, Storage, Analytics, Notifications, Developer) + search (Ctrl K), live Kafka rate, notifications bell aur appearance menu (light/dark, 5 accent, density, live updates pause). Har section ki apni left menu – groups, aur har processor ke andar sub-pages. Browser sirf dashboard (`:3000`) se baat karta hai, dashboard ka server `/api/*` ko service (`GPS_API_URL`, default `http://localhost:8080`) tak forward karta hai.

| Section (top bar) | Left menu |
|---|---|
| **Live** | Fleet overview, Live map, Vehicle list, Vehicle inspector, Throughput, Event stream, Fleet simulator |
| **Processing** | Pipeline overview (Kafka output → parallel processors → business logic → ingestion → stores), Consumer groups; processors: **Speed violation** (live episodes, entity rules in Redis, distance cross-check, events), **Vehicle state** (state store, historical context, key layout), **Stoppage & ignition** (live stoppages, ignition on rules, ignition off neutralised, traffic & weather agents), **Geofencing** (zone hierarchy, nested distance, entry & exit), **Waypoints** (routes, arrivals & ETA), **Cumulative distance** (Kalman vs raw, trip increments, filter tuning) |
| **Alerts** | All alerts, Escalations; business logic: **Over-speed** (dual verification, device log audit, alert policy), **Trip detection** (trips, master table lookup, stops), **Route deviation** (corridors, deviation log) |
| **Storage** | Ingestion overview, Schema validation, Rejected frames, PostgreSQL, ClickHouse, Workers & backpressure; no data loss: **Data safety**, **Dead letters** |
| **Analytics** | Live telemetry, Latency, Violations & anomalies, Trip efficiency |
| **Notifications** | Inbox, Channels, Routing rules, Delivery log, Quiet hours & digest |
| **Developer** | Engine overview, producers & consumers, topics & storage, trips/stops/readings, API reference, demo tools, service settings, module registry |

Har card pe likha hota hai data kahan se aaya: **Live** (service se) ya **Example data** (module abhi bana nahi – geofencing, waypoints, route deviation, ClickHouse, notification channels). Menu mein aise modules pe **Planned** likha hai.

```powershell
cd gps-dashboard-ui
npm install          # pehli baar
npm run dev          # http://localhost:3000  (service :8080 pe chalni chahiye)
```
Docker mein `docker compose up -d --build` isko `dashboard` service ke naam se khud chala deta hai. Har service pe `restart: always` hai, isliye ek baar `up` karne ke baad Docker Desktop khulte hi poora platform (dashboard bhi) apne aap chal jaata hai – http://localhost:3000.

---

## 17. Classified logs – har line bataye kaun, kis truck ka, kahan se

Message handle hote waqt `PipelineLogContext` MDC mein stage, IMEI, partition, offset daal deta hai. Log pattern har line pe print karta hai:

```
WARN [stage=rejected-frame-logger imei=0861000000000003 p=0 o=0] ... Frame rejected: Latitude 95.0 is outside -90..90
```

```powershell
docker compose logs gps-service | Select-String "imei=0861000000000003"    # ek device
docker compose logs gps-service | Select-String "stage=position-storage"   # ek consumer
docker compose logs gps-service | Select-String "ENGINE"                   # load summary har 30 sec
```
Har message ki debug line chahiye: `LOGGING_LEVEL_COM_SRM_GPS=DEBUG`.

---

## 18. Maven modules – har module ka ek hi kaam

Kuch toota → kis module mein dekhna hai, turant pata.

| Module | Kya karta hai | Yahan debug karo jab… |
|---|---|---|
| `gps-configuration` | Topic, group, flow, stage, Redis key ke naam; message formats (records); JSON. **Plain Java** | Do hisse ek naam ya JSON field pe agree nahi kar rahe |
| `gps-connectivity` | Bahar ki duniya se har connection: smart_truck reader, Kafka publishers/listeners/retry/inspector, Redis stores, Hibernate entities + repositories, load counters | Kuch connect/read/write nahi ho raha |
| `gps-processing` | Processing logic: `GpsFrameProcessor` + `step/` + `rule/`, aur trip detection `trip/`. **Plain Java, poora unit tested** | Frames reject ho rahe, distance/status galat, ya trip galat jagah shuru/band |
| `gps-streams` | **Kafka Streams** topologies (normalizer, analytics) + runtime. Spring nahi, `TopologyTestDriver` se tested | Duplicate/stale/key wale rejections, KTable/windows galat, parity mismatch |
| `gps-application` | Spring Boot service: dono flows, simulator, master data, storage, live location, trips, engine API + dashboard, Flyway migrations | Hisso ke beech ka flow galat hai |
| `gps-route-service` | **Alag Spring Boot app** (port 8090): GraphHopper se India map pe route planning – Dijkstra, A\*, CH, alternatives. Aage: route deviation, recalculation, reassessment ([plan](docs/ROUTE_INTELLIGENCE_PLAN.md)) | Route/ETA galat, ya engine DOWN |

```
processing-layer/
├─ gps-configuration/   message/ (RawGpsFrame, ProcessedGpsPosition, RejectedGpsFrame, MovementStatus)
│                       json/GpsMessageJson, KafkaTopicNames, KafkaConsumerGroupNames, PipelineStage, RedisKeyNames
├─ gps-connectivity/    kafka/ (publishers, listeners, retry + dead letters, topics, inspector)  journal/  redis/  database/  smarttruck/
│                       flow/ (@OnlyInKafkaFlow, @OnlyInDirectFlow)  monitoring/ (metrics, MDC log context)
├─ gps-processing/      GpsFrameProcessor, step/ (validate, movement, previous reading), rule/ (validation, movement, distance)
│                       trip/ (TripMotionProcessor: trips aur stops, pure function)
├─ gps-streams/         Kafka Streams: normalize/ (exactly-once normalizer), analytics/ (KTable, windows, parity), runtime/
├─ gps-application/     flow/ (kafka, direct)  simulator/  masterdata/  storage/  livelocation/  trip/  rejection/
│                       processing/  engine/  api/  archive/  safety/  resources/ (application.yml, db/migration, static/index.html)
├─ gps-route-service/   engine/ (GraphHopperClient, RoutingAlgorithm)  api/ (/api/routing/*)
├─ infrastructure/      windows/ (Kafka, PostgreSQL setup + start/stop)  docker/ (.env banane ki script)
│                       routing/graphhopper/ (Dockerfile, config.yml, truck_india.json, start script)
├─ docs/                LEARNING_GUIDE.md, ENGINE_AND_DOCKER_GUIDE.md, KAFKA_STREAMS_GUIDE.md, DATA_SAFETY_GUIDE.md, ROUTING_ENGINE_SETUP.md (English, aur bhi gehraai)
├─ Dockerfile, docker-compose.yml (+ docker-compose.ha.yml: 3 brokers), .env.example, local-secrets.example.properties
└─ pom.xml (parent), mvnw / mvnw.cmd
```

**Handler interfaces ka pattern:** connectivity module sirf message **receive** karta hai (`RawGpsFrameListener` etc.) aur ek interface (`RawGpsFrameHandler`, `ProcessedGpsPositionHandler`…) ko de deta hai. Aage kya karna hai wo application module decide karta hai. Isse Kafka ka code aur business ka code alag rehta hai.

---

## 19. Passwords aur secrets kaise sambhale

- `application.yml` mein **koi password nahi**.
- `local-secrets.properties` (git-ignored) mein passwords – template: `local-secrets.example.properties`.
- Docker ke liye `.env` (git-ignored) – banane ki script: `.\infrastructure\docker\create-docker-env.ps1` (local-secrets se padh ke likhti hai, bina BOM ke taaki compose sahi padhe).
- `.gitignore` mein `local-secrets.properties`, `.env`, `target/` – galti se commit na ho.

---

## 20. Docker se chalao (recommended)

**Containers:**

| Container | Kya | Windows se |
|---|---|---|
| `postgres` | PostgreSQL 14, `gps_tracking` | `localhost:5433` |
| `kafka` | Apache Kafka 4.3.1 (KRaft, ek node) | `localhost:29092` |
| `redis` | Redis Stack + RedisInsight | `localhost:6380`, http://localhost:8001 |
| `gps-service` | Humari Spring Boot app (+ dono Kafka Streams apps; state `gps-streams-state` volume mein) | http://localhost:8080 |

Windows wale ports (5432, 9092, 6379) se alag rakhe hain taaki dono saath chal sakein. Containers ek dusre ko service naam se dhoondhte hain (`postgres`, `kafka`, `redis`). Health checks ki wajah se `gps-service` tabhi start hota hai jab postgres, redis, kafka healthy hon.

**Dockerfile do stage mein:** (1) `build` – JDK + Maven se jar banao, (2) `runtime` – sirf JRE + ek jar, root user nahi (`uid 1001`). Image chhoti aur safe.

```powershell
.\infrastructure\docker\create-docker-env.ps1     # .env likhta hai (git-ignored)
docker compose up -d --build                      # sab chalu
docker compose ps                                 # sab "running" / "healthy"
docker compose logs -f gps-service                # logs
docker compose down                               # band (data volumes mein bacha rehta hai)
docker compose down -v                            # data bhi saaf
```

Direct flow Docker mein:
```powershell
docker compose up -d postgres redis
$env:GPS_PROCESSING_FLOW='direct'; docker compose up -d --no-deps --build gps-service
```

**Docker Hub image (public):** https://hub.docker.com/r/srmecotech/processing-layer – tags `latest`, `0.1.1` (trips and stops), `0.1.0` (~188 MB). Build kiye bina seedha pull karke chalao:
```powershell
docker compose pull gps-service          # srmecotech/processing-layer:latest le aata hai
docker compose up -d --no-build          # baaki containers + Hub wali image
```
Naya version Hub pe daalna (pehle `docker login`):
```powershell
docker compose build gps-service
docker tag srmecotech/processing-layer:latest srmecotech/processing-layer:0.1.1
docker push srmecotech/processing-layer:0.1.1; docker push srmecotech/processing-layer:latest
```
Dusra image naam chahiye to `.env` mein `GPS_SERVICE_IMAGE=...` set karo.

✅ **Verified 2026-10-19 (Docker Desktop 29.8, is machine pe):** 5 containers up, health UP (db + redis), 150 gaadiyan, 75 msg/s, chaaron consumer groups STABLE, lag 0, failures 0, Redis mein 150 live positions – Hub wali image se bhi.

**smart_truck Docker ke andar se:** container Windows ki MySQL tak `host.docker.internal:3306` se pahunchta hai. MySQL bole `Access denied for user ... @'172.x.x.x'` → user sirf `localhost` se allowed hai. smart_truck ke owner se ek **read-only** user maango jo Docker se connect ho sake, aur `local-secrets.properties` mein daal do.

---

## 21. Bina Docker ke chalao (Windows, D:\dev)

| Software | Version | Kahan |
|---|---|---|
| Apache Kafka (KRaft) | 4.3.1 | `D:\dev\kafka_2.13-4.3.1` |
| PostgreSQL (portable) | 14.22 | `D:\dev\postgresql-14.22` |
| Redis | 8.2 | `D:\dev\redis-8.2.10` |
| Java | 21 | pehle se installed |

```powershell
.\infrastructure\windows\setup-postgresql.ps1      # sirf pehli baar: DB + user + random password
.\infrastructure\windows\setup-kafka.ps1           # sirf pehli baar
.\infrastructure\windows\start-postgresql.ps1
.\infrastructure\windows\start-kafka.ps1
D:\dev\redis-8.2.10\redis-server.exe --port 6379 --save '""' --appendonly no
.\mvnw.cmd -DskipTests package
.\infrastructure\windows\start-gps-service.ps1                          # Kafka flow
.\infrastructure\windows\start-gps-service.ps1 -ProcessingFlow direct   # ya direct flow
```
Phir dashboard pe **Start fleet** dabao, ya:
```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/simulator/start?vehicleCount=150"
```
Tests: `.\mvnw.cmd test`

---

## 22. Windows ke nakhre (quirks)

- **`SocketException: Invalid argument: connect`** – Java ka unix-domain socket temp folder wala issue. Start scripts `-Djdk.net.unixdomain.tmpdir=D:\dev\data\tmp` pass karte hain. Kafka CLI tools ke liye `$env:KAFKA_OPTS` mein yahi flag daalo.
- PostgreSQL portable hai, `psql` nahi hai – check karna ho to Python `psycopg2` chalta hai.
- Local Kafka broker pe dusre projects ke topics/groups bhi ho sakte hain – unko chhedna nahi.

---

## 23. Kuch toot jaye to kahan dekhein (troubleshooting)

| Kya dikh raha | Kahan dekho |
|---|---|
| `SocketException: Invalid argument: connect` | Upar section 22 |
| Start pe Flyway ya "Schema-validation" error | `db/migration/*.sql` aur `gps-connectivity/.../database/entity` match nahi kar rahe |
| Dashboard: ek consumer ka lag badhta ja raha | Wo consumer fail ho raha – uska `failures` badh raha hoga, log mein `stage=<wo consumer>` errors |
| Dashboard: "Redis down" | `/actuator/health` → `redis`; Redis (ya `redis` container) start karo |
| Simulator status "Failed: Send failed" | Kafka band hai – start karo |
| Simulator "Failed: ... Access denied" Docker mein | Section 20, smart_truck wala part |
| Position save hui par vehicle/tenant khali | Us device ka `device_assignment` reading ke time ko cover nahi karta |
| `/actuator/health` → `dataSafety` DOWN | Storage → **Data safety**: kaunsa check FAIL hai (jaise kisi group ka data Kafka ne delete kar diya) |
| Dead letters badh rahe hain | Storage → **Dead letters**: error padho, bug theek karo, phir replay |

---

## 24. Aage kya banana hai – roadmap

Ye repo ab **starting point** hai. Agla kaam: Kafka ke baad processing layer ke **attachable modules** (plug-in consumers). Idea – railway junction: main track = topic, har naya platform = ek naya consumer group. Naya module jodna = ek Maven module + ek config line, baaki kuch nahi badlega.

```
gps.processed-positions → ★ Vehicle State Keeper (Redis state + Kafka compacted backup)
                        → gps.enriched-positions → [ speed | stoppage | geofence | waypoint | trip distance | route deviation ]  (sab parallel)
                        → gps.vehicle-events + gps.alerts → schema validation gate → PostgreSQL (7 din) + ClickHouse (poori history)
                                                                                   → gps.dead-letters (invalid) ✅ ban gaya, section 10a
```

| Phase | Kya | Done kab |
|---|---|---|
| 0 | Current base live data ke saath perfect chale | 150 trucks end to end |
| 1 | Module framework (✅ `gps-module-api`: `GpsModule` + `gps.modules.<id>.enabled`, replay – section 15e) + State Keeper + enriched topic + Redis config ladder (default → tenant → vehicle) + dead-letter (✅ `gps.dead-letters`) + **ignition aur odometer** frame mein | Dummy module 1 config line se jud jaye |
| 2 | Storage gate: schema validation + PostgreSQL (7 din) + **ClickHouse** | Pings dono DB mein |
| 3 | **Speed violation** – 1 min lagatar > 60 km/h, device speed vs lat/lon se calculated speed cross-check, alert ek baar | 60 nahi, 1 alert |
| 4 | **Stoppage** – ignition ON + < 2-3 km/h; ignition OFF → points neutralise (fake km nahi) | Khadi gaadi 0 km jode |
| 5 | **Nested geofencing** – City → Depot → Loading bay, ENTER/EXIT | Events sahi |
| 6 | **Trip detection** (master table) + **Kalman filter** se cumulative distance – pehla version ban gaya: `gps-trip-service` (section 15) | Trip km route se match |
| 7 | **Waypoint** + **route deviation** – engine ready: GraphHopper on India + `gps-route-service` ([setup](docs/ROUTING_ENGINE_SETUP.md), [plan](docs/ROUTE_INTELLIGENCE_PLAN.md)) | Deviation alert |
| 8 | Stoppage reason agent – weather (Open-Meteo) / traffic, alag consumer, hot path mein nahi | Stoppage pe reason |

---

## 25. Interview ke tricky sawaal

1. **Ek truck ke frames order mein kaise rehte hain?** Key = IMEI → same partition → group mein ek partition ek thread. Order partition ke andar guaranteed hai.
2. **C2 aur C3 same topic padhte hain, ek doosre ko slow kyun nahi karte?** Alag groups, alag offsets. Dono ko poori copy milti hai.
3. **4 consumers, 3 partitions – kya hoga?** Ek idle baithega. Parallelism ki limit = partitions.
4. **Kafka do baar message de de to?** At-least-once hai. Storage idempotent – `UNIQUE (imei, recorded_at)` + pehle check.
5. **Crash beech mein ho jaye?** Offset kaam ke baad commit hota hai, P2/P3 Kafka ke "mil gaya" ka wait karte hain → message dobara aayega, gum nahi.
6. **Restart ke baad pehle frame ka distance?** Memory khali → DB se latest reading → sahi distance.
7. **Late ping (network wapas aaya)?** `arrivedOutOfOrder` – history mein jaata hai, live state/distance nahi bigadta.
8. **Flink kyun hataya?** 150 trucks ke liye Spring kaafi; ek kam system chalana, ek jagah debug. Jo chhoda: Flink ka managed state, windows, exactly-once – interview mein ye honestly bolo.
9. **Device ek gaadi se doosri mein shift hua to purani readings?** `device_assignment` periods mein hai – reading ke time wali gaadi milti hai.
10. **Trip ka distance kabhi negative kyun nahi hoga?** Distance in-order readings ke legs ka jod hai, "end odometer − start" nahi. Late reading skip hoti hai (sirf ginti), aur DB mein `CHECK (distance_m >= 0)`.
11. **Trip service ko same reading do baar mili to?** State update `WHERE last_recorded_at < :recordedAt` – doosri baar 0 rows, transaction rollback. Trip `(imei, start_recorded_at)` pe upsert, to duplicate row ban hi nahi sakti.
12. **Kafka Streams normalizer listener ka group id kyun use karta hai?** `application.id` = consumer group. Same id → switch karne pe committed offsets se aage, na replay na gap. Dono kabhi saath nahi chalte.
13. **Exactly-once mein exactly kya ek saath hota hai?** Output record + dono stores ke changelog writes + input offset – ek transaction.
14. **Fleet ke numbers ke liye ek "fleet" key kyun nahi?** Saari fleet ek partition, ek thread pe aa jaati. Har gaadi ke window ko query ke waqt jod lo – partitions ke saath scale hota hai.
15. **4 instances, 3 partitions?** Ek khali baithega (hot spare; standby replicas ho to garam spare).
16. **Kafka down hai aur device frame bhej raha hai – frame kahan jaata hai?** Ingest journal mein disk pe (fsync), phir Kafka wapas aane pe usi order mein. Kafka "mil gaya" bole tabhi journal se hatta hai.
17. **Toota message skip kyun nahi karte?** Skip = data gum. `gps.dead-letters` mein park, PostgreSQL mein hamesha ke liye – bug theek karke replay.
18. **Kaise pata chale ki data gum nahi hua?** Data-safety report: har group ka committed offset Kafka ke log start se aage hona chahiye. Peeche = Kafka ne bina padhe delete kiya → FAIL, health DOWN.

---

## 26. Mental model – yaad rakhne ke liye 🧠

> **3 trays, 3 producers, 5 kaam.**
> Raw tray → 1 cleaner (jo agli do trays bhi bharta hai) → saaf tray ko 3 alag log padhte hain (DB, Redis, trip diary) → reject tray ko 1 logger.
> **Key = IMEI = order. Alag group = poori copy. Same group = kaam ka batwara.**
> **Kafka Streams: state locker mein, locker ki copy Kafka mein, teen kaam ek thappe mein. Ek waqt pe ek hi maalik – pehle shadow, phir owner.**
> **Data safety: pehle disk (journal), phir Kafka (31 din). Rukna chalega, phenkna nahi – kharab maal dead-letter room mein.**

Har application ko kaise start/stop/check karein aur uski main files kaunsi hain: [docs/APPLICATIONS_GUIDE.md](docs/APPLICATIONS_GUIDE.md).

English mein aur gehraai chahiye to: [docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md) (Kafka, flows, har module) aur [docs/ENGINE_AND_DOCKER_GUIDE.md](docs/ENGINE_AND_DOCKER_GUIDE.md) (Docker, dashboard, Redis, logs).
