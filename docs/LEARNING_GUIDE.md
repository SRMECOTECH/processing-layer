# Learning Guide: Kafka, the two processing flows and every module of this project

Written for someone who has **never used Kafka**. Read it top to bottom once; after that, use it as a reference.

There is **no Apache Flink** in this project. All processing runs inside the Spring Boot service, and a frame can travel in one of two ways:
- the **Kafka flow** (buffered, decoupled)
- the **direct flow** (no Kafka at all)

Tags used:
- **[Teacher's note]**: an explanation or opinion that is not code.
- **[Illustrative]**: a simplified example; real numbers or production setups differ.
- **[In our code]**: points at the exact file, so you can open it next to the text.

Contents
1. [The whole system in one picture](#1-the-whole-system-in-one-picture)
2. [Apache Kafka from zero](#2-apache-kafka-from-zero)
3. [Processing without Flink: the Kafka flow and the direct flow](#3-processing-without-flink-the-kafka-flow-and-the-direct-flow)
4. [Module 1: gps-configuration](#4-module-1-gps-configuration)
5. [Module 2: gps-connectivity](#5-module-2-gps-connectivity)
6. [Module 3: gps-processing](#6-module-3-gps-processing)
7. [Module 4: gps-application (Spring Boot)](#7-module-4-gps-application-spring-boot)
8. [The database: Flyway + Hibernate](#8-the-database-flyway--hibernate)
9. [The infrastructure scripts](#9-the-infrastructure-scripts)
10. [One ping, traced through every line of code](#10-one-ping-traced-through-every-line-of-code)
11. [Teaching version vs production version](#11-teaching-version-vs-production-version)
12. [Hands-on exercises](#12-hands-on-exercises)
13. [Tricky interview questions](#13-tricky-interview-questions)
14. [Mental models to remember](#14-mental-models-to-remember)

---

## 1. The whole system in one picture

### Kafka flow (`gps.processing-flow=kafka`, the default)

```
┌──────────────────────┐
│ smart_truck (MySQL)  │  existing system, we only READ from it
│ table tta_trip_gps   │
└──────────┬───────────┘
           │ SELECT (read-only connection)
           ▼
┌──────────────────────────────────────────┐
│ GpsDeviceSimulatorService                │  pretends to be N GPS devices
│  → RawGpsFrameSender (KafkaRawGpsFrameSender)
└──────────┬───────────────────────────────┘
           │ send JSON, key = IMEI
           ▼
╔══════════════════════════════════════════╗
║ KAFKA gps.raw-device-frames  (3 parts)   ║  the "inbox" of raw readings
╚══════════┬═══════════════════════════════╝
           │ CONSUMER GROUP gps-raw-frame-processing-service (3 consumers)
           ▼
┌──────────────────────────────────────────┐
│ KafkaRawFrameProcessingService           │
│  GpsFrameProcessor (gps-processing):     │
│   1. ValidateRawFrameStep                │──► bad frames ─┐
│   2. CalculateMovementStep (uses the     │──► duplicates ─┤
│      previous reading of the device)     │                │
└──────────┬───────────────────────────────┘                ▼
           ▼                                ╔═══════════════════════════╗
╔══════════════════════════════════════════╗║ KAFKA gps.rejected-frames ║
║ KAFKA gps.processed-positions (3 parts)  ║╚═════════════╤═════════════╝
╚══════════┬═══════════════════════════════╝              │ GROUP gps-rejected-frame-logger (1)
           │ GROUP gps-position-storage-service (3)        ▼
           ▼                                  RejectedGpsFrameLoggingService (logs reason)
┌──────────────────────────────────────────┐
│ GpsPositionStorageService → Hibernate    │  IMEI → vehicle → tenant
└──────────┬───────────────────────────────┘
           ▼
┌──────────────────────────────────────────┐
│ gps_tracking (PostgreSQL)                │  our NEW database
│ tenant, vehicle, device,                 │
│ device_assignment, gps_position          │
└──────────────────────────────────────────┘
```

### Direct flow (`gps.processing-flow=direct`, no Kafka)

```
GpsDeviceSimulatorService
   → RawGpsFrameSender (DirectProcessingFlow)
       → GpsFrameProcessor (same code as above)
            ├─ accepted → GpsPositionStorageService → PostgreSQL   (same code as above)
            └─ rejected → RejectedGpsFrameLoggingService → log     (same code as above)
```

**[Teacher's note]** Look at what is **shared**: the processor, the storage service and the rejected-frame logger are the same classes in both flows. The only thing that changes is the **transport**: either the pieces talk through Kafka topics, or they call each other directly. That is why the switch is just one property, and why a bug in processing is debugged in exactly the same place whichever flow is active.

---

## 2. Apache Kafka from zero

### 2.1 Desi analogy: the railway parcel office register

Think of the parcel office at **Howrah station**.

- There are several big **registers** (ledgers) on the counter: one for "Parcels to Delhi", one for "Parcels to Chennai" and so on.
- A **clerk who receives parcels** (the producer) writes each parcel as a **new line at the bottom** of the correct register. Lines are numbered 0, 1, 2, 3… and **nobody ever erases or edits a line**.
- **Delivery boys** (consumers) come and read the register. Each delivery boy keeps a **bookmark**: "I have handled everything up to line 57."
- Two different teams can read the **same register** for different reasons. The *delivery team* delivers parcels; the *accounts team* counts money. Each team has **its own bookmark**, and reading a line doesn't remove it for the other team.
- A register that gets too busy is split into **3 registers side by side** (partitions), so 3 clerks can write and 3 delivery boys can read at the same time.
- Old pages are thrown away after 7 days (retention), whether or not anybody read them.

That is Kafka: **a set of append-only, numbered registers that many people can read at their own pace.**

### 2.2 Connected to our project

| Railway parcel office | Kafka | In our project |
|---|---|---|
| The station office | **Broker** (a Kafka server) | one broker on `localhost:9092`, in `D:\dev\kafka_2.13-4.3.1` |
| A register ("Parcels to Delhi") | **Topic** | `gps.raw-device-frames`, `gps.processed-positions`, `gps.rejected-frames` |
| 3 side-by-side registers | **Partitions** | every topic has 3 partitions |
| Line number | **Offset** | 0, 1, 2… per partition |
| Clerk writing parcels | **Producer** | `RawGpsFramePublisher` (simulator, later the parser), `ProcessedGpsPositionPublisher` and `RejectedGpsFramePublisher` (the processing consumer) |
| A delivery team | **Consumer group** | `gps-raw-frame-processing-service`, `gps-position-storage-service`, `gps-rejected-frame-logger` |
| The team's bookmark | **Committed offset** | stored by Kafka in an internal topic `__consumer_offsets` |
| "Which register?" decided by the parcel's PIN code | **Message key** | the device **IMEI** |

### 2.3 Terminology, one word at a time

| Word | What it means | Why it exists |
|---|---|---|
| **Message / record** | one item: `key`, `value`, timestamp. Our value is JSON text. | the unit of data |
| **Topic** | a named stream of messages, e.g. `gps.raw-device-frames` | to separate different **kinds** of data |
| **Partition** | a topic is split into N ordered logs. Each partition is a separate file on disk. | **parallelism** (N readers at once) and **ordering** (order is kept *inside* one partition) |
| **Offset** | the position of a message inside one partition (0, 1, 2…) | lets a reader say "I'm at line 57" |
| **Key** | an optional value chosen by the producer. Same key → same partition (always). | to keep all messages of one device **in order** |
| **Producer** | a program that writes messages | |
| **Consumer** | a program that reads messages | |
| **Consumer group** | a team of consumers sharing the work. Each partition is read by **exactly one** member of the group. | scale out reading; each team has its own bookmark |
| **Commit** | the consumer tells Kafka "I've processed up to offset X" | so after a crash it restarts from X, not from 0 |
| **Lag** | log-end offset − committed offset = messages not yet handled by a group | the health signal of a consumer |
| **Retention** | how long messages are kept (ours: `log.retention.hours=168` = 7 days) | Kafka is not a database; old data is deleted |
| **Broker** | a Kafka server process | |
| **Replication factor** | how many brokers keep a copy of each partition (ours: 1) | survive a broker dying (needs ≥ 3 brokers in production) |
| **KRaft / controller** | Kafka's built-in coordination (who leads which partition). Older Kafka needed a separate program, ZooKeeper. | our broker is both "broker" and "controller" (`process.roles=broker,controller`) |

### 2.4 Building the intuition: why not just call the database directly?

That question matters here, because **we do also have a flow that calls the database directly**: the direct flow. So here is the honest comparison.

**Problem.** In production, about 170 GPS frames arrive every second (this figure comes from the master-data design). What if we wrote them directly into PostgreSQL from the parser?

1. **Spikes.** At 9 a.m. 2,000 trucks start at once. The DB slows down, the parser blocks, the devices time out, and **readings are lost**.
2. **Coupling.** The parser must know about the DB, the processing rules, the alert system, the reports… Every new feature means changing the parser.
3. **Restarts.** If the storage code or the DB is down, every frame that arrives during that time is lost.

**Kafka fixes all three:**

1. It is a **shock absorber**: the parser writes to Kafka (very fast, sequential disk writes), and readers catch up at their own speed.
2. **Decoupling**: the parser only knows "write to `gps.raw-device-frames`". Later a geofencing service, a waypoint service or an analytics loader can each become a **new consumer group** reading the same topic without changing the parser at all.
3. **Replay**: messages stay for 7 days. If the storage service was down for 2 hours, it restarts from its bookmark and processes the backlog. Nothing is lost.

**And the direct flow?** It is simpler: one process, one method call, one stack trace, nothing else to install. For a laptop, a demo or a small fleet that is a real advantage. It just doesn't have points 1–3. [Teacher's note] That is exactly why we keep both: the direct flow for simplicity and debugging, the Kafka flow for anything that must not lose data.

### 2.5 Diagram: how partitions, keys and consumer groups work

```
Topic gps.raw-device-frames (3 partitions)

key = IMEI "0861000000000004" ─ hash % 3 = 1 ─┐
key = IMEI "0861000000000005" ─ hash % 3 = 0 ─┼─┐
key = IMEI "831765"           ─ hash % 3 = 2 ─┼─┼─┐
                                              │ │ │
  partition 0: [0][1][2][3][4][5]... ◄────────┘ │ │   one device's frames
  partition 1: [0][1][2][3][4]...    ◄──────────┘ │   are ALWAYS in the
  partition 2: [0][1][2][3]...       ◄────────────┘   same partition, in order

Consumer group "gps-raw-frame-processing-service" (3 consumer threads)
  consumer A ← partition 0      bookmark: offset 5
  consumer B ← partition 1      bookmark: offset 4
  consumer C ← partition 2      bookmark: offset 3

Consumer group "some-future-geofencing-service" (2 consumers)
  consumer X ← partitions 0 and 1   its OWN bookmarks
  consumer Y ← partition 2          (does not disturb the processing group's)
```

**[Illustrative]** Which partition a given IMEI lands in depends on Kafka's hash function (murmur2). The numbers above are made up; the rule "same key → same partition" is real.

You saw this assignment in the service log at start-up:
```
gps-raw-frame-processing-service: partitions assigned: [gps.raw-device-frames-0]
gps-raw-frame-processing-service: partitions assigned: [gps.raw-device-frames-1]
gps-raw-frame-processing-service: partitions assigned: [gps.raw-device-frames-2]
gps-rejected-frame-logger: partitions assigned: [gps.rejected-frames-0, gps.rejected-frames-1, gps.rejected-frames-2]
```

### 2.6 Deeper: design decisions and trade-offs

**Why a log and not a queue (like RabbitMQ)?**
In a classic queue a message is **deleted when it is consumed**, so one consumer "steals" it from the others. In Kafka the message **stays**, and each consumer group keeps its own offset. So:
- many independent readers are free (fan-out)
- you can **re-read** (reset the offset to replay last night's data after fixing a bug)
- the trade-off: there are no per-message acknowledgements or per-message priorities. You only move a bookmark forward.

**Why 3 partitions, and why 3 consumers?**
- At most **one consumer per partition per group**. With 3 partitions, at most 3 consumers of a group can work in parallel. A 4th sits idle. So `concurrency = 3` (`KafkaConsumerGroupNames.ONE_CONSUMER_PER_PARTITION`) for processing and storage.
- **Ordering is guaranteed only inside a partition.** We key by IMEI so that one device's frames stay in order. Frames of *different* devices may interleave. That's fine, because each device is processed independently.
- You can **increase** partitions later but never decrease them, and increasing **changes where keys go** (hash % 4 ≠ hash % 3). That temporarily breaks per-key ordering. So choose with headroom.
- [Teacher's note] 3 is a teaching number. For ~150 vehicles, even 1 partition would cope (15 msg/s vs thousands per partition). Choose 6 to leave room for 6 parallel consumers. Production GPS ingestion for tens of thousands of vehicles would use something like 12–48.

**Why JSON as text?** We use plain `StringSerializer`. You can read every message with the console consumer, which is great for learning and debugging. The production alternative is Avro or Protobuf with a Schema Registry: smaller messages, and enforced schemas so a producer can't silently rename a field.

**What does "at-least-once" mean?**
A consumer (1) reads a message, (2) processes it, (3) commits the offset. If it crashes **between 2 and 3**, it re-reads the message after restarting and processes it **again**. So every message is processed **at least once, sometimes twice**. The fix is to make processing **idempotent**, meaning doing it twice has the same effect as doing it once.
- [In our code] `GpsPositionStorageService` checks `existsByImeiAndRecordedAt`, and the table has `UNIQUE (imei, recorded_at)`. A duplicate is skipped.

**Complexity.** Writes are appends to the end of a file: O(1) and sequential, which is why Kafka handles millions of messages per second on ordinary disks. Reads by offset are near O(1) using sparse index files. Kafka also lets the OS page cache do most of the work.

### 2.7 Tricky questions about Kafka

1. **"Kafka guarantees ordering."** Only **within a partition**. Two devices in different partitions have no ordering relation at all.
2. **"Once read, the message is gone."** No. It stays until retention deletes it. Reading only moves *your* group's offset.
3. **"More consumers means faster."** Only up to the number of partitions. Partitions are the unit of parallelism.
4. **"For a new purpose I need a new topic with a copy of the data."** No. Same data, same topic, **new consumer group**. Create a new topic only for a new **kind** of data (e.g. "vehicle entered geofence" events).
5. **"Two services can share a group id."** They then **split** the messages, so each sees only part of them. One purpose = one unique group id.
6. **"If the consumer crashes, the message is lost."** No. It wasn't committed, so it's re-delivered. The real danger is the opposite: **duplicates**.
7. **"Retention = until consumed."** No. It's time- or size-based. An unread message is deleted after 7 days too. If a consumer is down longer than retention, it **loses data**.
8. **"Kafka inserts into the database."** No. Kafka only stores messages. A consumer (`GpsPositionStorageService`) does the insert.
9. **"Replication factor 1 is fine."** Only on a laptop. If our single broker's disk dies, everything is gone.

### 2.8 Mental model

> **Kafka is a set of numbered, append-only registers; writers add lines, and every reading team keeps its own bookmark.**

---

## 3. Processing without Flink: the Kafka flow and the direct flow

### 3.1 Desi analogy: two kinds of post office

**The big city GPO (Kafka flow).**
- Letters are dropped in a **post box** (topic `gps.raw-device-frames`).
- Behind it sit **3 sorting clerks**, one per post-box slot (one consumer per partition). Each truck's letters always land in the **same slot**, so one clerk handles all letters of one truck, in order.
- Each clerk keeps a **drawer with one card per truck**: "last seen at 22.78, 86.19 at 06:48" (the in-memory previous-reading store). A new letter is compared with the card (distance, time gap, moving or stopped).
- The clerk then puts the result in the **out-tray** (topic `gps.processed-positions`) and **only after it is safely in the tray** updates the card and ticks the letter off (remember after delivery, then commit the offset).
- A separate **filing clerk** (storage consumer) files the out-tray into the **head-office ledger** (PostgreSQL).
- If a clerk goes on holiday, his drawer is empty when he comes back. So for a truck he has no card for, he first checks the **head-office ledger** for the last filed position (`DatabaseBackedPreviousReadingStore`).
- If the ledger room is closed (PostgreSQL down), the filing clerk **keeps trying** every few seconds. Meanwhile letters pile up safely in the trays (Kafka) and nothing is thrown away.

**The village post office (direct flow).**
- **One clerk** takes the letter at the window, checks it, compares it with his card, writes it into the ledger and only then takes the next letter.
- No post box, no trays: simple and easy to watch. But if the ledger room is closed, the clerk has to tell the customer "come back later" (the call fails).

### 3.2 Connected to our project

| Post office | In our project | File |
|---|---|---|
| Post box | topic `gps.raw-device-frames` | `KafkaTopicNames` |
| Sorting clerks (3) | consumer group `gps-raw-frame-processing-service`, `concurrency = 3` | `RawGpsFrameListener` |
| What the clerk does | validate + movement | `GpsFrameProcessor`, `ValidateRawFrameStep`, `CalculateMovementStep` |
| Card per truck | newest reading per IMEI | `PreviousReadingStore`, `InMemoryPreviousReadingStore` |
| Checking the head-office ledger after a holiday | look up the newest stored position on a cache miss | `DatabaseBackedPreviousReadingStore` |
| Out-trays | topics `gps.processed-positions`, `gps.rejected-frames` | `ProcessedGpsPositionPublisher`, `RejectedGpsFramePublisher` |
| Filing clerk | group `gps-position-storage-service` | `ProcessedGpsPositionListener` → `GpsPositionStorageService` |
| Complaints desk | group `gps-rejected-frame-logger` | `RejectedGpsFrameListener` → `RejectedGpsFrameLoggingService` |
| "Keep trying until the ledger room opens" | retry with growing pauses | `KafkaRetrySettings` |
| The village clerk | direct flow | `DirectProcessingFlow` |
| Choosing which post office | `gps.processing-flow: kafka` or `direct` | `application.yml`, `ProcessingFlowNames` |

### 3.3 Terminology

| Word | Meaning |
|---|---|
| **Processing flow** | the path a frame takes: `kafka` (through topics and consumers) or `direct` (one method call) |
| **`GpsFrameProcessor`** | the plain-Java brain: frame in, result out. Knows nothing about Kafka or databases. |
| **`ProcessingResultHandler`** | "where should the result go?" Each flow passes its own: Kafka flow = publish; direct flow = store or log. |
| **Previous reading** | the newest reading we know of one device: time, latitude, longitude |
| **Previous reading store** | where those are kept: a `ConcurrentHashMap` in memory, with the database as a fallback |
| **Concurrency** (`@KafkaListener(concurrency = "3")`) | how many consumer threads Spring starts in a group. More than the partition count = idle threads. |
| **Error handler / back-off** | what happens when handling a message throws: retry after 1 s, 2 s, 4 s … up to 60 s; never skip, except for broken messages |
| **Not-retryable exception** | an error that can't fix itself (broken JSON, DB rule violated): logged and skipped at once |
| **`@OnlyInKafkaFlow` / `@OnlyInDirectFlow`** | our own annotations: "create this bean only in that flow" |

### 3.4 Intuition: how do we remember each device without Flink?

The movement calculation needs **memory per device**: to say "moved 111 m in 30 s" we need the previous reading of *that* device. Three questions decide whether a simple `Map<imei, previousReading>` is safe.

**1. Can two threads work on the same device at once (race conditions)?**
- **Kafka flow:** no. Key = IMEI → one partition → exactly one consumer thread of the group. So one device is always handled by one thread, one frame after another. Different devices run on different threads, which is why the map is a `ConcurrentHashMap`.
- **Direct flow:** `DirectProcessingFlow.send` is `synchronized`, so one frame at a time. At 15 frames/s for 150 vehicles that costs nothing.

**2. What happens after a restart (the map is empty)?**
`DatabaseBackedPreviousReadingStore` looks up `findFirstByImeiOrderByRecordedAtDesc(imei)` in PostgreSQL on a miss, and then caches it. So the first frame after a restart still gets the right distance and time gap. [Teacher's note] Our old Flink version could **not** do this locally: its notebooks lived in memory and started empty after every restart.

**3. What if delivering the result fails (Kafka or PostgreSQL down)?**
The processor **remembers the reading only after the handler succeeded** (step 3 in `GpsFrameProcessor`). If we remembered first and the publish then failed, the retry would see "same time as the remembered reading" and wrongly reject the frame as a **duplicate**. That frame would be lost for good. The unit test `aFrameWhoseResultCouldNotBeDeliveredIsProcessedNormallyWhenRetried` guards this.

### 3.5 Diagram: threads in the Kafka flow

```
                       gps.raw-device-frames
              partition 0      partition 1      partition 2
                   │                │                │
   group gps-raw-frame-processing-service (3 threads)
            ┌──────▼─────┐   ┌──────▼─────┐   ┌──────▼─────┐
            │ thread 0   │   │ thread 1   │   │ thread 2   │
            │ validate   │   │ validate   │   │ validate   │
            │ movement   │   │ movement   │   │ movement   │
            │ publish ⏳ │   │ publish ⏳ │   │ publish ⏳ │  waits for Kafka's "OK"
            │ remember   │   │ remember   │   │ remember   │
            │ commit     │   │ commit     │   │ commit     │
            └──────┬─────┘   └──────┬─────┘   └──────┬─────┘
                   └──── shared ConcurrentHashMap<IMEI, PreviousReading> ────┘
                         (each IMEI is only ever touched by ONE thread)
                   │                │                │
                   ▼                ▼                ▼
           gps.processed-positions (key = IMEI, so still per-device ordered)
                   │                │                │
   group gps-position-storage-service (3 threads) → PostgreSQL
```

### 3.6 Deeper: design decisions and trade-offs

**Why the processor is plain Java.** `GpsFrameProcessor` takes a string and calls a handler. It needs no Spring, no Kafka, no database, so it is unit-tested in milliseconds (`GpsFrameProcessorTest`, 7 tests), and **both flows share it**. If the numbers look wrong, the bug is in `gps-processing`, whichever flow is active.

**Why the publishers wait (`send(...).join()`).** `KafkaTemplate.send` is asynchronous: it returns before Kafka has the message. Spring commits the raw frame's offset when our listener method returns. Without `join()`, a crash in that small window means the raw frame is committed but the processed message never arrived, so a frame is **lost**. With `join()`, the method returns only after Kafka confirmed. The cost is a few milliseconds per frame, which is nothing at our volume.

**Retry policy (`KafkaRetrySettings`).**

| Failure | What happens | Why |
|---|---|---|
| PostgreSQL or Kafka down | retry after 1 s, 2 s, 4 s … then every 60 s, **forever** | it will come back; skipping would lose data |
| Message is not valid JSON | log and skip immediately | retrying the same bytes can never succeed |
| Database rule violated (`DataIntegrityViolationException`) | log and skip immediately | same reason |

[Teacher's note] Spring Kafka's default is "retry 9 times quickly, then skip". That's fine for a demo, but it would silently drop positions during a 30-second database restart.

**Kafka flow vs direct flow.**

| | Kafka flow | Direct flow |
|---|---|---|
| Needs Kafka running | yes | no |
| PostgreSQL down | frames wait in Kafka, consumers retry | `send()` fails immediately; the caller sees the error |
| Burst of traffic | absorbed by Kafka | slows the caller |
| New consumer (geofencing, analytics…) | new consumer group; nothing else changes | must be called in code |
| Parallelism | 3 threads (one per partition) | 1 at a time |
| Debugging | follow 3 groups + lag | one stack trace |
| Good for | production, anything that must not lose data | laptop, demo, tests, small fleets |

**What we gave up by removing Flink (be honest in interviews).** Flink would give:
- checkpointed state, kept exactly consistent with Kafka offsets
- built-in time **windows** ("km per hour", "stopped > 30 min")
- automatic state redistribution when scaling out

Our design covers the per-device "previous reading" case well for one service instance. For heavy windowed analytics or many instances, you would add a shared store (Redis) or bring a stream processor back for that one job.

### 3.7 Tricky questions about the flows

1. **"With 3 threads, two frames of the same truck can be processed at once."** No. Same IMEI → same partition → same thread.
2. **"A restart resets distances to 0."** Not here. On a cache miss the store reads the newest stored position from PostgreSQL.
3. **"What if we run 2 instances of the service?"** Kafka splits the 3 partitions between them. The catch: if a partition moves back and forth (a **rebalance**), an instance may hold an **outdated card** for a truck it handled earlier. The fix is to clear the cache for revoked partitions (a rebalance listener) or keep the cards in Redis. With one instance, as today, this never happens.
4. **"Direct flow is just Kafka flow without Kafka, so it's equally safe."** No. It has no buffer and no replay. A database outage means failed frames, and the caller must retry.
5. **"Why not remember the reading before publishing? It's simpler."** Because a failed publish would then turn the retry into a false "duplicate". See 3.4, question 3.
6. **"In direct mode the service still connects to Kafka."** No. Every Kafka bean is `@OnlyInKafkaFlow`. We started the service with Kafka **stopped**, and the log shows no Kafka connection attempt at all.

### 3.8 Mental model

> **One brain (`GpsFrameProcessor`), two roads: through Kafka, where every step is a separate consumer group and nothing is lost while a step is down, or straight to the database in one call. The brain remembers a truck's last reading only after the result has safely arrived.**

---

## 4. Module 1: gps-configuration

**Job:** the **shared vocabulary**. Plain Java, no Spring, because *every* other module depends on it, including the framework-free processing module.

**Analogy:** the Indian Railways **timetable and parcel form format**. Every station uses the same station codes and the same form, otherwise a parcel booked in Howrah can't be read in Delhi.

| File | What it does | Why it's designed this way |
|---|---|---|
| `KafkaTopicNames.java` | constants `RAW_DEVICE_FRAMES`, `PROCESSED_POSITIONS`, `REJECTED_FRAMES`, `PARTITIONS_PER_TOPIC = 3` | a topic name typed in two places will one day be typed differently, and then a producer writes to `gps.raw-frames` while the consumer reads `gps.raw-device-frames`. **Nothing errors; data just never arrives.** One constant avoids that whole bug class. |
| `KafkaConsumerGroupNames.java` | `RAW_FRAME_PROCESSING`, `POSITION_STORAGE`, `REJECTED_FRAME_LOGGER`, `ONE_CONSUMER_PER_PARTITION` | the same reason. Two services that accidentally share a group id **split** the messages instead of both getting all of them. |
| `ProcessingFlowNames.java` | `PROPERTY = "gps.processing-flow"`, `KAFKA`, `DIRECT` | the flow switch, spelled in one place |
| `message/RawGpsFrame.java` | `imei, recordedAt, latitude, longitude, speedKmph` | exactly what a device knows. **No vehicle, no company**: a device only knows its IMEI. Fields are `Double`/`Integer` (not `double`/`int`) so a **missing** value arrives as `null` and can be rejected with "Speed is missing", instead of silently becoming `0`. |
| `message/ProcessedGpsPosition.java` | raw fields + `movementStatus`, `distanceFromPreviousMeters`, `secondsSincePreviousReading`, `arrivedOutOfOrder`, `processedAt` | everything the processing adds. Primitive types now, because after validation nothing can be null. |
| `message/RejectedGpsFrame.java` | `imei` (when known), `originalPayload`, `rejectionReason`, `rejectedAt` | keeps the **original text**, so a bad device's frame can be reproduced exactly |
| `message/MovementStatus.java` | `MOVING`, `STOPPED` | an enum instead of free text, so "Moving", "moving" and "MOVNG" can't exist |
| `json/GpsMessageJson.java` | `toJson(...)`, `fromJson(...)` using one shared Jackson `JsonMapper` | one place decides the JSON format (dates as ISO text like `"2026-10-14T06:49:38Z"`). `FAIL_ON_UNKNOWN_PROPERTIES` is disabled so an **older** reader doesn't crash when a **newer** writer adds a field (forward compatibility). Errors become one clear `InvalidGpsMessageException`. |

**Java `record`s:** `public record RawGpsFrame(String imei, ...)` makes an immutable class with a constructor, accessors (`frame.imei()`), `equals`, `hashCode` and `toString`. That's perfect for messages, which should never be modified after creation.

**Test:** `GpsMessageJsonTest` proves a message survives `toJson → fromJson` unchanged, and that broken JSON gives a clear exception.

---

## 5. Module 2: gps-connectivity

**Job:** **every** connection of the Spring service to the outside world. If something "can't connect / can't read / can't write", the answer is in this module.

**Analogy:** the **ports and customs office** of a country. All goods enter and leave through it, so you know exactly where to inspect.

It has four packages: one per outside system, plus the flow switch.

### 5.1 `smarttruck`: reading the old system (read-only)

| File | Explanation |
|---|---|
| `SmartTruckDatabaseSettings` | a record bound to the properties `smart-truck.database.url/username/password` (`@ConfigurationProperties`). Spring fills it from `application.yml` + `local-secrets.properties`. |
| `SmartTruckGpsPing` | one row of `tta_trip_gps` with **readable names** (`deviceImei` instead of `s_device_id`). |
| `SmartTruckGpsReader` | the only class that touches smart_truck. |

How `SmartTruckGpsReader` guarantees **read-only**:
```java
poolConfiguration.setReadOnly(true);   // every connection is opened read-only
```
The MySQL driver then puts each session in **read-only transaction mode**, so even a bug that tried `INSERT` would be **refused by MySQL itself** (error 1792). The class also contains only `SELECT` statements. That's two layers of protection.

Why the pool is **created inside the class** and not declared as a Spring `DataSource` bean: Spring Boot auto-configures **one** main `DataSource`, and if you declare your own DataSource bean, Boot backs off and stops configuring the main one. By keeping this pool private, the main DataSource stays the PostgreSQL `gps_tracking` one and can never be confused with smart_truck.

Time zone: smart_truck stores `dt_message` as local India time without a zone. We convert it with `atZone(Asia/Kolkata).toInstant()`, so everything after this point is unambiguous UTC `Instant`s.

`setInitializationFailTimeout(-1)`: the service still **starts** if MySQL is down. It only fails when you actually start a simulation.

### 5.2 `flow`: the switch

`@OnlyInKafkaFlow` and `@OnlyInDirectFlow` are our own annotations, each built on Spring Boot's `@ConditionalOnProperty`:
```java
@ConditionalOnProperty(name = "gps.processing-flow", havingValue = "kafka", matchIfMissing = true)
public @interface OnlyInKafkaFlow { }
```
Put it on a class and Spring **creates that bean only in that flow**. `matchIfMissing = true` makes Kafka the default. Every Kafka class in this module carries `@OnlyInKafkaFlow`, which is why the direct flow runs with Kafka stopped.

### 5.3 `kafka`: talking to Kafka

| File | Role | Kafka concept |
|---|---|---|
| `KafkaTopicDeclarations` | declares 3 `NewTopic` beans (3 partitions, 1 replica). On start-up Spring's `KafkaAdmin` creates any missing topic. | topic administration |
| `RawGpsFramePublisher` | `send(RAW_DEVICE_FRAMES, frame.imei(), json)` | **producer**, **key = IMEI** |
| `RawGpsFrameListener` → `RawGpsFrameHandler` | group `gps-raw-frame-processing-service`, `concurrency = 3`, passes the **raw JSON text** on | **consumer group 1** |
| `ProcessedGpsPositionPublisher` | `send(PROCESSED_POSITIONS, imei, json).join()` (waits for Kafka's OK) | producer |
| `RejectedGpsFramePublisher` | `send(REJECTED_FRAMES, json).join()`, no key | producer |
| `ProcessedGpsPositionListener` → `ProcessedGpsPositionHandler` | group `gps-position-storage-service`, `concurrency = 3` | **consumer group 2** |
| `RejectedGpsFrameListener` → `RejectedGpsFrameHandler` | group `gps-rejected-frame-logger`, 1 consumer | **consumer group 3** |
| `KafkaRetrySettings` | the `DefaultErrorHandler` with exponential back-off (see 3.6) | consumer error handling |

**Why the handler interfaces? (dependency inversion)**
The connectivity module **receives** the message, but it must **not** know what the application does with it (that's business logic, which lives in `gps-application`). If the listener called `GpsPositionStorageService` directly, connectivity would depend on application, and application already depends on connectivity: a circular dependency. Instead:
```
gps-connectivity:  ProcessedGpsPositionListener ──calls──► ProcessedGpsPositionHandler (interface)
gps-application:   GpsPositionStorageService implements ProcessedGpsPositionHandler
```
Spring injects the implementation at runtime. Same pattern as a socket in the wall: the wiring (connectivity) doesn't care which appliance (application) you plug in.

**Why does the raw listener pass text, not a parsed frame?** So that even **broken JSON** reaches the processor and is turned into a proper rejection ("Frame is not valid JSON"), visible in `gps.rejected-frames`, instead of an exception in the listener.

**What Spring Kafka does for you behind `@KafkaListener`:**
- creates `concurrency` consumers and joins the group
- gets partitions assigned
- polls in a loop and calls your method for each record
- **commits offsets after your method returns**
- if your method throws, our `KafkaRetrySettings` decides between retrying and skipping

`auto-offset-reset: earliest` (in `application.yml`) only applies to a **brand-new group with no bookmark yet**: "start from the oldest message, not just new ones". [Teacher's note] When we replaced Flink, the new group `gps-raw-frame-processing-service` therefore re-read the whole 7 days of raw frames on first start. The storage dedup absorbed them all: the row count did not change.

### 5.4 `database`: Hibernate entities and repositories for `gps_tracking`

**Entities** are Java classes mapped to tables (`@Entity @Table(name = "...")`):

| Entity | Table | Key points |
|---|---|---|
| `Tenant` | `tenant` | UUID id; `code` unique (e.g. `demo-transport-jsr`); `legalName`; `status` |
| `Vehicle` | `vehicle` | `registrationNo` unique platform-wide; `@ManyToOne(fetch = LAZY) ownerTenant` (null = market vehicle) |
| `Device` | `device` | `imei` unique; `kind` (GPS…); `protocol` |
| `DeviceAssignment` | `device_assignment` | device + vehicle + `validFrom` / `validTo` (null = still fitted) |
| `GpsPosition` | `gps_position` | `Long` identity id; stores **codes** (`tenantCode`, `vehicleRegistrationNo`), not foreign keys |

Things worth understanding in these classes:
- `protected Tenant() {}`: Hibernate needs a no-argument constructor to build objects from rows. It's `protected` so **our** code can't create half-empty entities.
- `@GeneratedValue(strategy = GenerationType.UUID)`: Hibernate generates the UUID in Java before inserting.
- `@GeneratedValue(strategy = IDENTITY)` on `GpsPosition`: the database generates the id (`GENERATED BY DEFAULT AS IDENTITY`). [Teacher's note] One side effect: Hibernate can't batch inserts for IDENTITY ids. Switch to a sequence if you ever need batching.
- `FetchType.LAZY`: loading a `Vehicle` does **not** immediately load its `Tenant`. It's loaded only if accessed, which saves queries.
- `@Enumerated(EnumType.STRING)`: stores `"MOVING"`, not `0`. The default (`ORDINAL`) would silently corrupt data the day someone reorders the enum.
- **Why periods (`validFrom`/`validTo`) instead of "current vehicle"?** A device buffers readings while out of coverage and delivers them hours later. If the device was moved to another truck in between, "current vehicle" would give the old readings to the **new** truck. A period answers "which truck **at the time of the reading**".

**Repositories** are interfaces. Spring Data **writes the implementation for you** from the method name:
- `findByCode(code)` → `select … where code = ?`
- `existsByImeiAndRecordedAt(imei, t)` → `select exists(… where imei = ? and recorded_at = ?)`
- `findFirstByImeiOrderByRecordedAtDesc(imei)` → the newest stored reading of a device (used after a restart; served by the `(imei, recorded_at)` unique index)
- `findFirstByVehicleRegistrationNoOrderByRecordedAtDesc(reg)` → the latest position of a vehicle
- `findTop20ByOrderByStoredAtDesc()` → the 20 newest rows

The most important query is written by hand (JPQL) in `DeviceAssignmentRepository`:
```java
select assignment from DeviceAssignment assignment
  join fetch assignment.vehicle vehicle
  left join fetch vehicle.ownerTenant
where assignment.device.imei = :imei
  and assignment.validFrom <= :moment
  and (assignment.validTo is null or assignment.validTo > :moment)
```
- `:moment` is the **reading time**, never `now()`.
- `join fetch` loads the vehicle and tenant **in the same query** (avoids the "N+1 queries" problem).
- The period is "from inclusive, to exclusive", matching PostgreSQL's `tstzrange` default `[ )`.

---

## 6. Module 3: gps-processing

**Job:** turn a raw frame into a checked, enriched position, or a rejection with a reason. **Plain Java: no Spring, no Kafka, no database.** Its only dependency is `gps-configuration`.

Read the files in this order:

### 6.1 `GpsFrameProcessor.java`: the whole processing on one screen

```java
public void process(String rawFrameJson, ProcessingResultHandler resultHandler) {
    // Step 1: validate
    ValidationResult validation = validateRawFrameStep.validate(rawFrameJson, now);
    if (!validation.isValid()) { resultHandler.onRejected(...); return; }

    // Step 2: calculate movement using the device's previous reading
    Optional<PreviousReading> previous = previousReadingStore.findLatestReading(imei);
    ProcessingResult result = calculateMovementStep.calculate(frame, previous, rawFrameJson, now);

    switch (result) {
        case Rejected rejected -> resultHandler.onRejected(rejected.rejection());
        case Accepted accepted -> {
            resultHandler.onAccepted(position);
            // Step 3: remember it (only AFTER delivery succeeded, and never an older reading)
            if (!position.arrivedOutOfOrder()) previousReadingStore.rememberLatestReading(imei, ...);
        }
    }
}
```
- The processor **doesn't know** where the frame came from or where results go. The caller passes a `ProcessingResultHandler`.
- `switch` over a **sealed interface** (`ProcessingResult` is either `Accepted` or `Rejected`): the compiler checks that both cases are handled. That's Java 21 pattern matching.

### 6.2 `ProcessingResultHandler.java` and `ProcessingResult.java`

- `ProcessingResultHandler`: two methods, `onAccepted(position)` and `onRejected(rejection)`. It should **throw** if delivery failed; then nothing is remembered and the frame can be retried.
- `ProcessingResult`: `sealed interface` with two records. "Exactly one of the two" is expressed in the type itself.

### 6.3 `step/ValidateRawFrameStep.java` and `step/ValidationResult.java`: Step 1

```java
public ValidationResult validate(String rawFrameJson, Instant now) {
    try { frame = GpsMessageJson.fromJson(rawFrameJson, RawGpsFrame.class); }
    catch (InvalidGpsMessageException e) { return ValidationResult.invalid("Frame is not valid JSON"); }
    return GpsFrameValidationRules.findProblem(frame, now)
            .map(ValidationResult::invalid)
            .orElseGet(() -> ValidationResult.valid(frame));
}
```
`ValidationResult` is either "valid + the frame" or "invalid + the problem in plain English".

### 6.4 `rule/GpsFrameValidationRules.java`: the checks

Checked in this order; the **first** problem found is returned in plain English:

| Check | Example reason |
|---|---|
| IMEI missing/blank | `IMEI is missing` |
| no `recordedAt` | `Recording time is missing` |
| more than 5 min in the future | `Recording time … is in the future` (broken device clock) |
| lat/long missing | `Latitude or longitude is missing` |
| latitude ∉ [-90, 90] | `Latitude 95.0 is outside -90..90` |
| longitude ∉ [-180, 180] | `Longitude … is outside -180..180` |
| exactly (0, 0) | `Position is 0,0 - the device has no GPS fix` (a classic GPS failure value: it's in the Atlantic Ocean) |
| speed missing | `Speed is missing` |
| speed ∉ [0, 150] | `Speed 400 km/h is outside 0..150` |

`now` is **passed in** rather than read inside, so tests can use a fixed time. That's why `GpsFrameValidationRulesTest` is deterministic.

### 6.5 `step/CalculateMovementStep.java`: Step 2 (pure calculation)

```
if previous exists and same recordedAt   → Rejected "Duplicate reading"
arrivedOutOfOrder = previous exists and recordedAt < previous.recordedAt
if previous exists and not out of order:
    distance = haversine(previous position, this position)
    seconds  = this time − previous time
status = MovementRules.decideMovementStatus(speed, distance)
→ Accepted ProcessedGpsPosition(...)
```
It only **calculates**. It doesn't touch the store: remembering is the processor's job, after delivery (see 3.4).

**Event time, simply.** We compare `recordedAt`, the time the reading was **taken**, not the time it arrived. A device that was out of coverage sends old readings late. These get `arrivedOutOfOrder = true` and distance 0, and they don't overwrite the newer remembered reading. [Teacher's note] Production would buffer and re-sort by event time, or recompute the trip segment. That makes a good next exercise.

### 6.6 `step/PreviousReading.java`, `PreviousReadingStore.java`, `InMemoryPreviousReadingStore.java`

- `PreviousReading(Instant recordedAt, double latitude, double longitude)`: a record, all that movement needs.
- `PreviousReadingStore`: an interface with `findLatestReading(imei)` and `rememberLatestReading(imei, reading)`.
- `InMemoryPreviousReadingStore`: a `ConcurrentHashMap`. 150 vehicles = 150 tiny entries.
- The Spring service plugs in `DatabaseBackedPreviousReadingStore` (section 7.5), which **extends** the in-memory one and adds the database fallback.

### 6.7 `rule/GeoDistanceCalculator.java` and `rule/MovementRules.java`

- **Haversine** gives the distance on a sphere (Earth radius 6,371 km) between two lat/long points. Flat-earth Pythagoras is wrong because a degree of longitude shrinks as you move away from the equator. The test checks Jamshedpur → Kolkata ≈ 223 km.
- **Movement rule:** `MOVING` if speed ≥ 5 km/h **or** jump ≥ 50 m, otherwise `STOPPED`. Why not just "speed > 0"? A parked truck's GPS **wanders** a few metres, and devices sometimes report 1–3 km/h while standing still. The thresholds absorb this noise. In our real data about 10% of positions are MOVING, because the first pings of each trip are inside the plant.

### 6.8 Tests

| Test | Proves |
|---|---|
| `GpsFrameProcessorTest` (7) | first reading has distance 0; second is compared with the first; duplicate rejected; late reading flagged and doesn't replace the newest; **failed delivery → retry works**; broken JSON and invalid frames rejected with the reason |
| `GpsFrameValidationRulesTest` (6) | each validation rule |
| `MovementRulesTest` (4) | thresholds and haversine distance |

---

## 7. Module 4: gps-application (Spring Boot)

**Job:** the runnable service. It **wires** the modules into one of the two flows and holds the business flow.

### 7.1 `GpsStreamingApplication.java`

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class GpsStreamingApplication { ... }
```
- It sits in package **`com.srm.gps`**, the common parent of `com.srm.gps.application` **and** `com.srm.gps.connectivity`. Spring scans from this package downwards, so it finds the components, entities and repositories of the connectivity module without any extra configuration. **If you move this class one package deeper, the listeners and repositories disappear.**
- `@ConfigurationPropertiesScan` finds the settings records (`SmartTruckDatabaseSettings`, `GpsDeviceSimulatorSettings`) and fills them from configuration.

### 7.2 `resources/application.yml`

| Section | Meaning |
|---|---|
| `spring.config.import: optional:file:./local-secrets.properties,…` | loads the git-ignored passwords file. Imported values **override** this file. `optional:` means "don't fail if missing". |
| `spring.datasource.*` | the **main** database = PostgreSQL `gps_tracking`, login `gps_app` |
| `spring.jpa.hibernate.ddl-auto: validate` | Hibernate **never** changes tables. It only checks the entities match them, and **fails at start-up** if not. Flyway owns the schema. |
| `spring.jpa.open-in-view: false` | no database session hanging open during web requests (a common source of hidden slow queries) |
| `hibernate.jdbc.time_zone: UTC` | timestamps are written and read as UTC |
| `spring.kafka.bootstrap-servers` | where Kafka is; overridable with `KAFKA_BOOTSTRAP_SERVERS` |
| `smart-truck.database.url` | the read-only source |
| `gps.processing-flow` | `kafka` (default) or `direct`; override at start-up with `--gps.processing-flow=direct` |
| `gps.simulator.*` | defaults: 150 vehicles, 500 pings each, one round every 2 s, live timestamps, repeat forever |
| `management.endpoint.health.show-details: always` | `/actuator/health` shows `"db": {"status": "UP"}`: the quickest "is PostgreSQL working?" check |

### 7.3 `flow/`: the two roads

| File | Flow | What it does |
|---|---|---|
| `RawGpsFrameSender` | both | the **entry door**: `send(frame)` + `flowName()`. The simulator only knows this interface. |
| `kafka/KafkaRawGpsFrameSender` | kafka | `send` = publish to `gps.raw-device-frames`. Everything else happens in the consumers. |
| `kafka/KafkaRawFrameProcessingService` | kafka | implements `RawGpsFrameHandler` (consumer group 1): runs the processor with a handler that **publishes** to the processed or rejected topic |
| `direct/DirectProcessingFlow` | direct | `synchronized send` = run the processor with a handler that **stores** (`GpsPositionStorageService`) or **logs** (`RejectedGpsFrameLoggingService`) |

Exactly one `RawGpsFrameSender` bean exists at a time, thanks to `@OnlyInKafkaFlow` / `@OnlyInDirectFlow`. If both existed, Spring would fail at start-up with "expected single matching bean".

### 7.4 `simulator/`: pretending to be the devices

`GpsDeviceSimulatorService.startFleet(vehicleCount, pingsPerVehicle)` (default 150 vehicles):
1. `running.compareAndSet(false, true)`: an **atomic** "start only if not already running". Two quick clicks can't start two fleets.
2. The work runs on **one background thread** (`Executors.newSingleThreadExecutor`), so the HTTP request returns immediately with the status.
3. `driveFleet(...)`:
   - asks `SmartTruckGpsReader.findLatestTripOfDistinctDevices` for the **newest trip of N different devices**, so 150 trips really are 150 trucks
   - reads the first `pingsPerVehicle` pings of each trip
   - calls `SimulationMasterDataSeeder` so the new database knows those devices and vehicles
   - drives in **rounds**: round 1 = ping 1 of every vehicle, round 2 = ping 2 of every vehicle… then sleeps `delayBetweenRoundsMillis` (2 s). That's how a real fleet reports: everyone, every few seconds. 150 vehicles / 2 s = 75 frames per second.
   - `replayAsLive`: each frame gets the **current time**, so the data looks live and a second drive never collides with the first
   - `repeatForever`: when the trips end, it drives them **back** (reversed), then forwards again, so no truck "teleports" to its start and data keeps coming
   - hands each ping to the `RawGpsFrameSender` as a `RawGpsFrame` **with only what a device knows** (IMEI, time, position, speed)
4. `stopFleet()` flips `running` to false; the loop checks it before every frame.

`GpsDeviceSimulatorController` exposes this over HTTP: `POST /api/simulator/start?vehicleCount=150`, `POST /stop`, `GET /status`, and `POST /send-single-frame` (sends your JSON exactly as given, the easy way to test rejections). The engine dashboard has Start/Stop buttons for the same thing.

`SimulationStatus` is the JSON you get back: `processingFlow`, `running`, `vehicleCount`, `framesSent`, `round`, `roundsPerDrive`, `driveNumber`, `replayAsLive`, `lastMessage`.

### 7.4b `livelocation/`, `engine/` and the 4th consumer

- `LiveLocationService` (consumer group `gps-live-location-service`, the 4th group) keeps the newest position of each vehicle in Redis. It reads the **same topic** as storage, with its own bookmark.
- `DeviceVehicleDirectory` caches "IMEI → vehicle at that moment", so storage and live location don't query `device_assignment` for every message.
- `engine/` builds the dashboard at http://localhost:8080 from our load counters and Kafka's own view (lag).
- Details, Redis keys and log filtering: [ENGINE_AND_DOCKER_GUIDE.md](ENGINE_AND_DOCKER_GUIDE.md).

### 7.5 `processing/`: plugging the plain-Java processor into Spring

- `GpsProcessingConfiguration`: one `@Bean GpsFrameProcessor`, shared by both flows.
- `DatabaseBackedPreviousReadingStore`: in-memory first; on a miss, `findFirstByImeiOrderByRecordedAtDesc` from PostgreSQL, then cached (see 3.4).

### 7.6 `masterdata/`: making the new database know the devices

**Why it's needed:** the storage step must turn IMEI → vehicle → tenant. In the real platform, master data comes from its own service. For the simulation we **derive** it from smart_truck:

| smart_truck column | becomes |
|---|---|
| `s_entity_name` (e.g. `DEMO-TRANSPORT-JSR`) | `tenant` with code `demo-transport-jsr` (`TenantCodeFormatter`) |
| `s_asset_id` (e.g. `XX02CD5678`) | `vehicle.registration_no` |
| `s_device_id` | `device.imei` |
| first ping time of that device | `device_assignment.valid_from` |

`SimulationMasterDataSeeder.registerVehiclesAndDevices` is `@Transactional`: all inserts for one replay succeed together or not at all. It uses "find, or create if missing" for each row, so running it twice creates nothing new (idempotent).

`TenantCodeFormatter`: lower-case, anything that isn't a letter or digit becomes `-`, no leading or trailing dashes, max 40 characters. This matches the database check `code ~ '^[a-z0-9][a-z0-9_-]*$'`.

**[Teacher's note]** The transporter is stored as the **owner**. In the full design, a transporter running someone else's truck is an **operator** (`vehicle_operation` table), not an owner. That's a deliberate simplification for now.

### 7.7 `storage/GpsPositionStorageService.java`: storing a position

```java
@Transactional
public void handle(ProcessedGpsPosition position) {
    if (gpsPositionRepository.existsByImeiAndRecordedAt(...)) return;          // idempotency
    Optional<Vehicle> vehicle = deviceAssignmentRepository
            .findAssignmentActiveAt(position.imei(), position.recordedAt())   // at READING time
            .map(DeviceAssignment::getVehicle);
    // log a warning if the device is not fitted anywhere at that time
    gpsPositionRepository.save(new GpsPosition(... tenantCode, vehicleRegistrationNo ...));
}
```
- Kafka flow: called by consumer group `gps-position-storage-service`. Direct flow: called by `DirectProcessingFlow`.
- `@Transactional`: the lookups and the insert happen in **one** database transaction.
- If the device is unknown, the position is **still stored** (with empty vehicle and tenant) and a warning is logged. Losing data silently is worse than storing it unresolved.

### 7.8 `rejection/RejectedGpsFrameLoggingService.java`

`LOG.warn("Frame rejected: {} | frame: {}", reason, originalPayload)`. Kafka flow: consumer group `gps-rejected-frame-logger`. Direct flow: called directly. [Teacher's note] A natural next step is a `rejected_gps_frame` table, so the reasons can be queried per device.

### 7.9 `api/`: looking at the results

`GpsPositionQueryController` returns `GpsPositionView` records, **not** entities. Returning entities directly leaks internal fields, can trigger lazy-loading surprises during JSON conversion, and ties your public API to your table layout.

---

## 8. The database: Flyway + Hibernate

**Two tools, two jobs:**
- **Flyway** **creates and changes** tables using numbered SQL files in `gps-application/src/main/resources/db/migration`. On start-up it runs every file not yet applied, in order, and records each in `flyway_schema_history`. **Never edit an applied file**: Flyway stores a checksum and refuses to start if an applied file changed. Add `V3__...sql` instead. (This is why the comment in `V2` still says "after Flink processing": changing it would break the checksum.)
- **Hibernate** **maps** Java objects ↔ rows and, with `ddl-auto: validate`, checks the mapping matches.

`V1__create_master_data_tables.sql`:
- `CREATE EXTENSION btree_gist`: needed so an exclusion constraint can mix `=` on a UUID with `&&` (overlap) on a time range.
- `device_assignment_one_vehicle_per_device_at_a_time`:
  ```sql
  EXCLUDE USING gist (device_id WITH =, tstzrange(valid_from, valid_to) WITH &&)
  ```
  This reads as: "no two rows may have the **same device** and **overlapping periods**". A normal `UNIQUE` can't express "overlapping". This is what guarantees a reading resolves to **exactly one** truck. `valid_to = NULL` means an open-ended range ("still fitted").
- `CHECK` constraints on `tenant.code`, `status`, `device.kind`: the database refuses bad values even if application code has a bug.

`V2__create_gps_position_table.sql`:
- `UNIQUE (imei, recorded_at)`: the database-level guarantee behind idempotent storage. Its index also serves the "newest reading of this device" lookup.
- `CHECK` on latitude and longitude ranges and on `movement_status`.
- Indexes `(vehicle_registration_no, recorded_at DESC)` (fast "latest position of truck X") and `(stored_at DESC)` (fast "recently stored").

**How to check that PostgreSQL is healthy:**
1. `GET http://localhost:8080/actuator/health` → `"db": {"status": "UP"}`.
2. `flyway_schema_history` has versions 1 and 2, both `success = true`.
3. No duplicates: `select imei, recorded_at from gps_position group by 1, 2 having count(*) > 1` returns nothing.
4. Every position resolved: `select count(*) from gps_position where vehicle_registration_no is null` is 0.

(The portable PostgreSQL in `D:\dev` has no `psql`. Use DBeaver or pgAdmin, or a small script.)

---

## 9. The infrastructure scripts

All are in `infrastructure/windows/`:

| Script | When | What it does |
|---|---|---|
| `local-infrastructure-paths.ps1` | loaded by all others | install and data paths, helpers to read and write `local-secrets.properties` |
| `setup-kafka.ps1` | once | copies Kafka's config with `log.dirs=D:/dev/data/kafka`, generates a cluster id, formats the storage (KRaft) |
| `start-kafka.ps1` / `stop-kafka.ps1` | every time (Kafka flow only) | starts or stops the broker; sets `KAFKA_HEAP_OPTS` and the socket temp-folder fix |
| `setup-postgresql.ps1` | once | `initdb` (random superuser password), creates login `gps_app` + database `gps_tracking` in single-user mode, saves the password to `local-secrets.properties` |
| `start-postgresql.ps1` / `stop-postgresql.ps1` | every time | `pg_ctl start/stop` |
| `start-gps-service.ps1 [-ProcessingFlow kafka\|direct]` | every time | `java -jar gps-streaming-service.jar --gps.processing-flow=...`, from the project root so it finds the secrets file |

---

## 10. One ping, traced through every line of code

**[Illustrative]** Times and offsets are examples; the flow is exact.

```
① smart_truck.tta_trip_gps row
   i_trip_no=10000001, s_asset_id=XX02CD5678, s_device_id=0861000000000004,
   dt_message=2026-10-14 12:19:38 (India time), d_lat=22.780933, d_long=86.196862, i_speed=0

② SmartTruckGpsReader.toGpsPing → SmartTruckGpsPing(recordedAt=2026-10-14T06:49:38Z …)
   (12:19:38 IST − 5:30 = 06:49:38 UTC)

③ GpsDeviceSimulatorService.toRawFrame → RawGpsFrame → rawGpsFrameSender.send(frame)

──────────── KAFKA FLOW ────────────
④ KafkaRawGpsFrameSender → RawGpsFramePublisher.publish
      topic  = gps.raw-device-frames,  key = "0861000000000004" → partition hash % 3, say 1
      value  = {"imei":"0861000000000004","recordedAt":"2026-10-14T06:49:38Z",
                "latitude":22.780933,"longitude":86.196862,"speedKmph":0}
      offset = 57 in partition 1

⑤ RawGpsFrameListener (thread for partition 1, group gps-raw-frame-processing-service)
      → KafkaRawFrameProcessingService.handle(json)
      → GpsFrameProcessor.process(json, publishResultToKafka)
⑥   Step 1 ValidateRawFrameStep: JSON → RawGpsFrame; findProblem(...) = empty → valid
⑦   Step 2 DatabaseBackedPreviousReadingStore.findLatestReading(IMEI)
           → in memory: {06:48:38Z, 22.780933, 86.196862}
         CalculateMovementStep: not duplicate, not out of order,
           distance = 0.0 m, seconds = 60, speed 0 → STOPPED → Accepted
⑧   onAccepted → ProcessedGpsPositionPublisher: send(gps.processed-positions, key = IMEI).join()
⑨   Step 3 remember {06:49:38Z, ...}; listener returns → Spring commits offset 58 for partition 1

⑩ ProcessedGpsPositionListener (group gps-position-storage-service)
      → GpsMessageJson.fromJson → GpsPositionStorageService.handle
      → existsByImeiAndRecordedAt? no
      → findAssignmentActiveAt(IMEI, 06:49:38Z) → vehicle XX02CD5678, tenant demo-fleet-owner-jsr1
      → INSERT INTO gps_position (...)
      → commit offset for group gps-position-storage-service

──────────── DIRECT FLOW ────────────
④' DirectProcessingFlow.send(frame)  (synchronized)
      → GpsFrameProcessor.process(json, storeOrLogResult)
      → steps ⑥ ⑦ exactly as above
      → onAccepted → GpsPositionStorageService.handle → INSERT (step ⑩ without Kafka)
      → step 3 remember

⑪ GET /api/vehicles/XX02CD5678/latest-position → this row as JSON (either flow)
```

---

## 11. Teaching version vs production version

| Area | What we have (teaching) | What production would do |
|---|---|---|
| Kafka cluster | 1 broker, replication 1, 3 partitions | ≥ 3 brokers, replication 3, `min.insync.replicas=2`, more partitions, lag monitoring and alerts |
| Message format | JSON strings | Avro or Protobuf + Schema Registry |
| Processing | inside the Spring service, 3 consumer threads, one instance | several instances; per-device state in Redis (or cache cleared on rebalance); a stream processor only for heavy windowed analytics |
| Delivery | at-least-once + idempotent DB insert; publishers wait for Kafka's OK | the same, plus producer `acks=all` and idempotence explicitly configured |
| Failure handling | retry forever with back-off; skip broken messages with a log line | also a **dead-letter topic** for skipped messages, so they can be inspected and replayed |
| Out-of-order data | flagged, not re-ordered | buffer and re-sort by event time, or recompute the segment |
| Device → vehicle lookup | one DB query per position | in-process cache of assignments, refreshed on change |
| Storage writes | one insert per message (IDENTITY id) | batch listener + `INSERT … ON CONFLICT DO NOTHING`, sequence ids, monthly table partitions (or a time-series store) |
| Direct flow | for laptop and debugging | usually not used in production, or only behind a durable queue |
| Master data | seeded from smart_truck | its own service; full schema with branches, trips, row-level security |
| Secrets | git-ignored properties file | a secrets manager (Vault, cloud secret store) |
| Source | simulator | the real device parser publishing to the same topic |

---

## 12. Hands-on exercises

Do these with everything running. Each one teaches one concept. For the Kafka tools, first run `$env:KAFKA_OPTS = '-Djdk.net.unixdomain.tmpdir=D:\dev\data\tmp'` in that PowerShell window.

1. **See raw messages (Kafka is just a log).**
   `D:\dev\kafka_2.13-4.3.1\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 --topic gps.raw-device-frames --from-beginning --property print.key=true --property print.partition=true`
   Notice: the same IMEI is always in the same partition.
2. **See consumer-group bookmarks (offsets and lag).**
   `D:\dev\kafka_2.13-4.3.1\bin\windows\kafka-consumer-groups.bat --bootstrap-server localhost:9092 --describe --group gps-position-storage-service`
   Columns: `CURRENT-OFFSET` (bookmark), `LOG-END-OFFSET` (last line), `LAG` (unread lines).
3. **Prove Kafka buffers.**
   1. Stop PostgreSQL (`stop-postgresql.ps1`) and run a replay.
   2. Watch the service log retry, and watch `LAG` grow on `gps-position-storage-service`.
   3. Start PostgreSQL again: the lag drains to 0 and the count jumps. Nothing was lost.
4. **Same test in the direct flow.** Start with `-ProcessingFlow direct`, stop PostgreSQL, run a replay: the simulator status says `Failed`. That is the trade-off from 3.6, seen with your own eyes.
5. **Prove idempotency.** Replay the same trips twice and watch the count stay the same. Look for `Duplicate reading` in the log.
6. **Break a frame.** Use `/api/simulator/send-single-frame` with latitude 95, no speed, a future time, or `0,0`. Find each reason in the service log (both flows).
7. **Prove the restart fallback.** Note a truck's latest position, restart the service, send one newer frame for that IMEI. Its `distanceFromPreviousMeters` is **not** 0.
8. **Code change: add a rule.** Reject frames outside India (latitude 6–37, longitude 68–98). Add it to `GpsFrameValidationRules`, add a test, rebuild, restart. Both flows get it at once.
9. **Code change: a new consumer.** Add a `live-location` consumer group on `gps.processed-positions` that keeps only the newest position per vehicle. Notice that you change nothing in the processing or storage code.

---

## 13. Tricky interview questions

1. *How does Kafka guarantee ordering and how did you use it?* Only per partition; we key by IMEI so each device's frames stay ordered, and one consumer thread per partition processes them sequentially.
2. *What happens if the storage service crashes after inserting but before committing the offset?* The message is re-delivered, and `existsByImeiAndRecordedAt` + `UNIQUE(imei, recorded_at)` make the second insert a no-op.
3. *How do you compute "distance since previous reading" without Flink?* Per-device memory in a concurrent map. It's safe because one IMEI is only processed by one thread (key → partition → consumer). After a restart the store falls back to the newest stored row.
4. *Why remember the reading only after publishing?* Otherwise a failed publish turns the retry into a false duplicate and the frame is lost.
5. *Why does the publisher call `.join()`?* So the raw offset is committed only after the processed message is safely in Kafka. Otherwise a crash in between loses the frame.
6. *What breaks if you increase partitions from 3 to 6?* Key → partition mapping changes, so for a while one device's frames can be in two partitions, and the old and new ones may be processed out of order.
7. *Why store device assignments as periods?* Late, buffered readings must resolve to the vehicle at the **reading** time.
8. *Kafka flow vs direct flow: when would you pick which?* Direct for simplicity, tests and tiny fleets. Kafka when data must survive outages and spikes, or when several independent consumers need the same data.
9. *What's wrong with Spring Kafka's default error handling for this system?* It retries a few times and then **skips**, so a 30-second DB restart would drop positions. We retry infrastructure errors forever with back-off and skip only messages that can never succeed.
10. *How would you scale to 100,000 trucks?* More partitions and instances; per-device state in Redis or rebalance-aware caching; cached device lookups; batch inserts; a time-series store; possibly a stream processor for windowed analytics.
11. *Why is the smart_truck connection safe?* A read-only connection pool, enforced by MySQL itself, plus SELECT-only code. The pool is not a Spring DataSource bean, so it can't be confused with the main database.
12. *What is consumer lag and why would it grow?* Unread messages = log end − committed offset. It grows when consumers are slower than producers (slow DB, too few partitions or consumers, a message being retried).

---

## 14. Mental models to remember

- **Kafka:** numbered, append-only registers; writers add lines, each reading team keeps its own bookmark.
- **Partition + key:** the key picks the register; order is guaranteed only inside one register.
- **Consumer group:** one purpose, one bookmark, at most one member per partition. Groups are unlimited.
- **At-least-once:** "maybe twice, never zero", so make the effect idempotent.
- **Our processing:** one plain-Java brain, two roads (Kafka or direct). It remembers a truck only after the result has safely arrived.
- **Kafka flow vs direct flow:** Kafka = a shock absorber and many independent readers; direct = one call, one stack trace, no safety net.
- **This project:** *Simulator writes raw frames → (Kafka holds them →) the processor checks them and remembers each device → (Kafka holds the results →) storage finds the truck at the reading time and stores it once.*
