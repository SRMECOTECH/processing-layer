# Kafka Streams Guide: the processing layer as stream processing

> **In one paragraph:** two Kafka Streams apps now run inside `gps-streaming-service`.
> - The **normalizer** turns raw device frames into processed positions. It is exactly-once: output records, state updates and input offsets commit in one transaction. Each device's state lives in RocksDB with a changelog topic.
> - The **analytics** app keeps the latest state of every vehicle as a KTable and counts readings in 5-minute **event-time** windows with a grace period. You read both live through **Interactive Queries**.
>
> Who normalizes is a switch (`owner` / `shadow` / `off`). A shadow run compares Kafka Streams with the old `@KafkaListener`, reading by reading, before the hand-over.

This guide follows the [Kafka Streams architecture guide](https://kafka.apache.org/43/streams/architecture/) (Apache Kafka 4.3) concept by concept, and shows where each concept lives in this code. The client library is Kafka Streams 4.2.1 (managed by Spring Boot 4.1.1); the broker is Apache Kafka 4.3.1.

---

## 1. Desi analogy: the dhaba kitchen gets lockers

Before, the cleaning helper (the raw-frame listener) kept "what did each truck send last time?" in **his own pocket notebook** (an in-memory map). When he went home (a restart) the notebook was gone, so he asked the munshi's register (PostgreSQL) for every truck again.

Now every counter (partition) has a **locker** (a state store). Every page written in a locker is **also copied into the back-office ledger** (the changelog topic in Kafka). If a counter's helper goes home, the next helper opens the same locker, or rebuilds it page by page from the ledger, and carries on exactly where it stopped. On top of that, the helper's three actions are **one stamp** (one transaction):

- put the cleaned item on the tray (output record);
- write in the locker (state update);
- tick the order slip (input offset).

They happen together or not at all.

A second team (analytics) watches the cleaned tray. It keeps a **board with one row per truck** (the KTable) and a **5-minute tally sheet** (windows). Each tally sheet is closed and filed once the 5 minutes, plus a 2-minute grace for latecomers, are over.

---

## 2. The two apps

```
                    ┌──────────────────────── normalizer (application.id = gps-raw-frame-processing-service) ─────┐
gps.raw-device-frames ─► normalize-frame ─► split ─┬─ accepted ─► gps.processed-positions   (event-time stamped)  │
 (key = IMEI, 3 parts)   │  previous-reading-store │                                                                │
                         │  seen-reading-store     └─ rejected ─► gps.rejected-frames                              │
                    └──────────────────── exactly_once_v2 · one sub-topology · tasks 0_0 0_1 0_2 ──────────────────┘

                    ┌──────────────────────── analytics (application.id = gps-streams-analytics) ─────────────────┐
gps.processed-positions ─► observe ─► group by IMEI (no repartition: the key already is the IMEI)                   │
 (event time = recordedAt)            ├─► aggregate ─────────────► vehicle-table-store    KTable: latest per vehicle │
                                      └─► 5 min windows + 2 min grace ─► activity-window-store (queryable 6 h)       │
                                              emit on window close ─► gps.vehicle-activity  (one final record)       │
gps.shadow.processed-positions ─► outer join on IMEI + recordedAt ─► parity-tally-store        (shadow mode only)   │
                    └──────────────────── at_least_once, read_committed · one sub-topology · tasks 0_0 0_1 0_2 ───────┘
```

**Why two apps and not one?** They have different jobs and different needs:

| | normalizer | analytics |
|---|---|---|
| On the critical path? | Yes. Every reading waits for it. | No. It can lag, restart or be rebuilt. |
| Guarantee | `exactly_once_v2` | `at_least_once` (reads committed data only) |
| State | small, one entry per device | per vehicle and per window |
| Reset | rare | whenever window settings change |

Separate application ids mean separate consumer groups, offsets, state directories and changelog topics. Each app scales, fails and is reset on its own. Code: [`GpsStreamsTopologies`](../gps-streams/src/main/java/com/srm/gps/streams/GpsStreamsTopologies.java).

---

## 3. The architecture guide, concept by concept

| Concept (Kafka 4.3 guide) | In this project |
|---|---|
| **Stream partition** = topic partition, record = message, **key decides the partition** | Every topic has 3 partitions and key = IMEI, so one device always lands on one partition. The normalizer **enforces** this: a frame whose record key is not its IMEI is rejected. |
| **Task** = the fixed unit of parallelism, one per input partition per sub-topology; its partitions never change | Each app has one sub-topology, so tasks are `0_0`, `0_1`, `0_2`. Task `0_1` always owns partition 1: the same devices, the same state. |
| **Max parallelism = number of partitions**; extra instances sit idle | 3 partitions means at most 3 busy threads per app across all instances. A 4th instance only waits as a hot spare. |
| **Threading model**: threads share nothing; scale by adding threads or instances | `gps.streams.threads: 3` gives one task per thread. Add an instance and Kafka moves tasks to it; no code change. |
| **Local state stores**, created by the DSL for aggregations, joins and windows, and embedded per task | `previous-reading-store` and `seen-reading-store` (normalizer); `vehicle-table-store`, `activity-window-store` and `parity-tally-store` (analytics). RocksDB, under one shared memory ceiling ([`BoundedMemoryRocksDbConfig`](../gps-streams/src/main/java/com/srm/gps/streams/runtime/BoundedMemoryRocksDbConfig.java)). |
| **Changelog topics**: one per store, partitioned like the store, log-compacted | `gps-raw-frame-processing-service-previous-reading-store-changelog`, `…-seen-reading-store-changelog`, `gps-streams-analytics-vehicle-table-store-changelog`, `…-activity-window-store-changelog`. They are created by Kafka Streams. |
| **Restoration**: a moved task replays its changelog before processing | Tracked per store and partition ([`RestoreTracker`](../gps-streams/src/main/java/com/srm/gps/streams/runtime/RestoreTracker.java)) and shown on the dashboard. The state directory sits on a Docker volume, so a restart replays only the tail after the last checkpoint. |
| **Standby replicas** (`num.standby.replicas`): warm copies for fast fail-over; tasks go to caught-up instances (since 2.6) | `gps.streams.standby-replicas`. Default 0 with one instance: a standby never sits on the same instance as its active task. Set it to 1 as soon as there are two instances. |
| **Rack awareness** (`rack.aware.assignment.tags`, `client.rack`, `rack.aware.assignment.strategy`) | Passed through as-is with `gps.streams.properties.*` (for example `client.rack: az-1`). |
| **Runtime thread changes**, a dead thread replaced (since 2.8) | Uncaught exception handler = `REPLACE_THREAD`: a failed thread is replaced and its tasks resume from the last commit ([`StreamsApp`](../gps-streams/src/main/java/com/srm/gps/streams/runtime/StreamsApp.java)). |

---

## 4. The normalizer in detail

Code: [`NormalizeFrameProcessor`](../gps-streams/src/main/java/com/srm/gps/streams/normalize/NormalizeFrameProcessor.java), [`NormalizationTopology`](../gps-streams/src/main/java/com/srm/gps/streams/normalize/NormalizationTopology.java).

It runs **the same rules as the listener**, with nothing copied: `ValidateRawFrameStep`, `CalculateMovementStep` and the distance filters all come from `gps-processing`. What changes is where the state lives, and three explicit policies the listener never had:

| Step | What happens | Rejection reason |
|---|---|---|
| 1. Validate (stateless) | JSON, IMEI, time, coordinates, speed | `Latitude 95.0 is outside -90..90` … |
| 2. Key contract | record key must equal the frame's IMEI, otherwise per-device order and state are not guaranteed | `Record key X is not the frame's IMEI Y…` / `Record has no key…` |
| 3. Stale policy | more than `max-lateness` (24 h) older than the device's newest reading | `Stale reading: recorded at …` |
| 4. Duplicate policy | the device already sent a reading with this `recordedAt`, looked up in a **window store** that remembers every accepted reading for 24 h | `Duplicate reading: … (first seen …)` |
| 5. Movement | distance, time gap, MOVING/STOPPED, distance source, total distance | (a repeat of the newest reading) |
| 6. Remember | `previous-reading-store` gets the newest reading (late ones never replace it); `seen-reading-store` gets this reading | – |

**Why a duplicate store, when the listener already rejects duplicates?** The listener compares only with the newest reading. If a device reconnects and uploads an older batch again, the listener sees the repeats as late readings and accepts them a second time. Producer idempotence does not help: it prevents Kafka-level repeats, not a device sending the same reading twice. A window store keyed by IMEI, with the reading's time as window start, makes the check a single point lookup: `fetch(imei, recordedAt)`.

**Exactly-once, concretely.** Under `exactly_once_v2` one transaction contains:

- the processed or rejected record;
- the store updates, through their changelog topics;
- the raw frame's input offset.

If the app crashes in the middle, all of it is rolled back and the frame is processed again with the old state. The listener needs an explicit "publish, wait for both, only then remember" dance for this. Here it comes from Kafka.

**Event time travels on.** An accepted position is written with the **record timestamp = recordedAt**, so `gps.processed-positions` is event-time stamped for any downstream stream processor.

**First reading after a hand-over.** When the store has never seen a device, it asks PostgreSQL once for the newest stored position (`DatabaseBackedPreviousReadingStore.findStoredReading`) and writes the answer to the store. That way distances and the running total carry on instead of restarting at 0. Shadow mode skips this: the database is being written by the listener at the same moment.

---

## 5. The analytics app in detail

Code: [`AnalyticsTopology`](../gps-streams/src/main/java/com/srm/gps/streams/analytics/AnalyticsTopology.java).

- **Event time.** [`RecordedAtTimestampExtractor`](../gps-streams/src/main/java/com/srm/gps/streams/analytics/RecordedAtTimestampExtractor.java) uses `recordedAt` from the value, so positions written by the listener (whose record time is the publish time) and by Kafka Streams are treated the same.
- **KTable (latest state).** One row per vehicle ([`VehicleSnapshot`](../gps-streams/src/main/java/com/srm/gps/streams/analytics/VehicleSnapshot.java)). "Latest" means newest **by event time**: a late reading is counted but never replaces a newer position. Kafka Streams does not decide that by itself; the fold does.
- **Windows with grace.** Tumbling 5-minute windows, 2 minutes of grace. A reading 1 minute after its window ended still counts in its own window. A reading after the grace is dropped (Kafka Streams' `dropped-records` metric). `emitStrategy(onWindowClose())` publishes **one final record** per vehicle and window to `gps.vehicle-activity`, with no intermediate updates, which makes it ready for a time-series store.
- **No hot partition for fleet numbers.** Re-keying every reading to a single `"fleet"` key would send the whole fleet through one partition. Instead, fleet totals are summed at query time from the per-vehicle windows ([`ActivityWindow.merge`](../gps-streams/src/main/java/com/srm/gps/streams/analytics/ActivityWindow.java)).
- **No repartition topic at all.** The input key already is the IMEI, so `groupByKey()` needs no repartition. `TopologyShapeTest` fails if one ever appears.

---

## 6. One owner per responsibility: owner, shadow, off

```yaml
gps:
  streams:
    enabled: true
    normalization: owner   # owner | shadow | off
```

| Mode | Raw frames → positions done by | Kafka Streams also does |
|---|---|---|
| `owner` (default) | Kafka Streams (exactly-once) | analytics |
| `shadow` | the listener | the same logic into `gps.shadow.*`, plus parity |
| `off` | the listener | analytics |
| `enabled: false` | the listener | nothing |

**The hand-over trick.** A Kafka Streams `application.id` **is** its consumer group. The owner normalizer therefore uses the listener's own group id, `gps-raw-frame-processing-service`. Switching listener → Kafka Streams continues from the listener's committed offsets, and Kafka Streams → listener continues from Kafka Streams' offsets: no replay, no gap. The two never run together. `@OnlyWhenListenerNormalizes` removes the listener in owner mode, and both close with *leave group*, so the successor gets the partitions at once instead of after the 45-second session timeout. This was verified on the real broker: the listener was assigned all three raw partitions in the same second the service restarted.

**Shadow parity.** The analytics app outer-joins `gps.processed-positions` (listener) with `gps.shadow.processed-positions` (Kafka Streams). Both are keyed by IMEI with the same partition count (co-partitioned), and both carry `recordedAt` as timestamp. So a join window of **0** pairs exactly the two results for the same reading, with no re-keying. An outer join also emits the unpaired readings once the window plus grace has closed. Verdicts ([`ParityVerdict`](../gps-streams/src/main/java/com/srm/gps/streams/analytics/ParityVerdict.java)):

- `MATCH`: every compared field is equal.
- `MISMATCH`: a field differs, and the verdict lists which.
- `WARMUP`: the shadow's first reading of a device, measured from nothing.
- `ONLY_LISTENER` / `ONLY_STREAMS`: one side rejected the reading.

The expected `ONLY_LISTENER` cases are exactly the new policies: re-sent older readings, stale readings, and bad keys.

**Hand-over steps:**
1. Run `shadow` with live traffic.
2. Check `/processing/streams/parity`: only warm-up and explained differences.
3. Switch to `owner`.
4. To roll back, switch back to `shadow` or `off`.

---

## 7. Interactive Queries and scaling out

Code: [`KafkaStreamsQueryService`](../gps-application/src/main/java/com/srm/gps/application/kafkastreams/KafkaStreamsQueryService.java).

- Each instance holds only the partitions (vehicles) of its own tasks. `application.server` (`gps.streams.advertised-host` + `server.port`) tells the other instances where to reach it.
- **One vehicle:** `queryMetadataForKey(store, imei)` says which instance is active for the key (and which hold standbys). The service answers locally or forwards to `/api/streams/local/vehicles/{imei}` on the owner.
- **Whole fleet:** scatter-gather. Every instance answers `/api/streams/local/...` for its share and the answers are merged. An instance that does not answer leaves a gap instead of failing the request.
- While an app rebalances or restores, queries answer **503** with the reason.

**Running a second instance:**
1. Give it its own `advertised-host` (for example its container name).
2. Start it.
3. Kafka rebalances: each app moves one or two of its three tasks over, and each moved task restores its stores from the changelogs.
4. Set `standby-replicas: 1` so the next fail-over finds a warm copy.

Beyond 3 instances nothing more runs in parallel: add partitions first. That changes which partition a key maps to, so it needs a reset of the streams state.

---

## 8. Configuration (`gps.streams.*`)

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Run Kafka Streams at all (Kafka flow only) |
| `normalization` | `owner` | `owner`, `shadow` or `off` (section 6) |
| `threads` | `3` | stream threads per app; one per partition |
| `standby-replicas` | `0` | warm store copies on **other** instances |
| `replication-factor` | `-1` | of the changelog topics; -1 = broker default (1 here, 3 on a cluster) |
| `state-dir` | Kafka Streams' temp folder | Docker: `/var/lib/gps-streams` on the `gps-streams-state` volume |
| `advertised-host` | `localhost` | Docker: `gps-service` |
| `leave-group-on-close` | `true` | fast hand-over to a successor |
| `max-lateness` | `24h` | stale policy |
| `seen-readings-retention` | `24h` | duplicate detection horizon |
| `window-size` / `window-grace` / `window-retention` | `5m` / `2m` / `6h` | activity windows |
| `parity-grace` | `2m` | shadow join: how long one side waits for the other |
| `rocksdb-off-heap-mb` | `64` | one memory ceiling for every RocksDB store in the JVM |
| `normalizer-guarantee` / `analytics-guarantee` | `exactly_once_v2` / `at_least_once` | |
| `properties.*` | – | any Kafka Streams setting, passed through (`client.rack`, `rack.aware.assignment.tags`, …) |

Docker reads these as environment variables (`GPS_STREAMS_NORMALIZATION=shadow`, …). Treat window size, grace, retention and store names like a schema: change them together with a reset (the demo reset does it).

---

## 9. Operations

| Want to… | Where |
|---|---|
| See apps, sub-topologies, threads → tasks → partitions, store lag, restorations, metrics | Console **Processing → Kafka Streams → Topology & tasks**, or `GET /api/streams/overview` |
| Read the vehicle KTable | **Vehicle table**, `GET /api/streams/vehicles`, `GET /api/streams/vehicles/{imei}` |
| See the fleet per 5-minute window | **Activity windows**, `GET /api/streams/fleet/activity?windows=24` |
| Compare the listener and Kafka Streams | **Shadow parity**, `GET /api/streams/parity` |
| Health | `GET /actuator/health` → `kafkaStreams` (DOWN only when an app is in ERROR) |
| Consumer lag | **Consumer groups**. The owner normalizer shows up as `gps-raw-frame-processing-service`, the analytics as `gps-streams-analytics` |
| Logs | Normalizer lines carry the same MDC as the listeners: `[stage=raw-frame-processing imei=… p=… o=…]` |
| Start from nothing | **Demo tools → Empty DB**. It also closes both apps, deletes their consumer groups and changelog topics, wipes local state and starts them again |

**Offsets are not message counts on transactional topics.** Every exactly-once commit writes a commit marker into each partition it touched. So on `gps.processed-positions` and `gps.rejected-frames`, "latest offset − earliest offset" counts markers too. The message counters (msg/s, totals) count records. Consumer lag is still right: a caught-up consumer sits after the marker.

**Downstream consumers read committed data only**: `spring.kafka.consumer.isolation-level: read_committed`. They never see the records of a transaction the normalizer had to abort.

---

## 10. Testing and what was verified

- **Without a broker:** `gps-streams` has 23 tests on `TopologyTestDriver`, which runs the real RocksDB stores:
  - `NormalizationTopologyTest`: event-time stamp, validation, key contract, duplicates (newest and re-sent older), late readings, stale policy, fallback, shadow topics, byte-identical JSON;
  - `AnalyticsTopologyTest`: KTable newest-by-event-time, window counting, emit once after grace, late beyond grace dropped, parity match, mismatch, only-one-side and warm-up;
  - `TopologyShapeTest`: sub-topologies, store names, no repartition topic.
- **On a real broker** (an isolated compose project, 150 simulated vehicles, Kafka 4.3.1):
  - both apps RUNNING, 3 tasks each, exactly-once normalizer at about 70 frames/s during catch-up;
  - every downstream group Stable with lag ≈ 0;
  - 150 live positions;
  - 1,324 final window records with **0 duplicates**;
  - a restart restored only 1 changelog record per store partition (local state reused);
  - switching to shadow handed the raw partitions to the listener at once, and the shadow apps ran without a warning.

```powershell
.\mvnw.cmd -pl gps-streams test
```

---

## 11. How this maps to the roadmap

| Roadmap item | Status |
|---|---|
| `gps.raw-device-frames`, `gps.processed-positions`, `gps.rejected-frames` | Live; the normalizer owns the boundary between them |
| `gps.vehicle-events` | Live (stream consumers: over-speed, stoppage, ignition) |
| `gps.vehicle-activity` | **New**: final 5-minute windows per vehicle |
| `gps.map-matched-positions`, `gps.trip-updates` | Not yet: OSRM and trip lifecycle events are next stages, as separate apps or consumers |
| KStream, KTable, stateless processing, state store, changelog, event time, grace period, windowing, partitions & tasks, exactly-once, Interactive Queries | All in use (sections 3–7) |
| "Kafka Streams does not solve GPS ordering, distance or duplicates by itself" | Explicit policies: key contract, stale horizon, duplicate store, newest-by-event-time fold, distance sources kept separate (`distanceSource`: odometer, GPS, jitter-filtered, jump-filtered, out-of-order) |
| "Introduce it at the normalization boundary and compare before migrating" | `shadow` mode + parity, then `owner`, with a no-gap hand-over |
| Observability per topic, partition, vehicle and stage | Stage counters (unchanged), per-task lag, store lag, restorations, Kafka Streams metrics, per-vehicle KTable |

---

## 12. Tricky questions

1. **Why does the owner normalizer use the listener's consumer group id?** `application.id` is the group id, so the hand-over in either direction continues from the committed offsets. Both never run at the same time; a classic group cannot hold the two kinds of members together.
2. **What exactly is atomic under exactly-once?** The output records, the changelog writes of both stores, and the input offset. Not the database fallback read, which is a read only.
3. **A device re-sends a reading from 3 hours ago. What happens?** The seen-reading store knows it, so it is rejected as a duplicate. The listener would have accepted it as a late reading.
4. **A reading arrives 10 minutes after its window ended.** The window closed at end + 2 minutes, so the reading is dropped from the windows (counted in `dropped-records`). It still went through the normalizer and into PostgreSQL. Only the window aggregate ignores it.
5. **Why no `"fleet"` key for fleet statistics?** It would put the whole fleet on one partition, one task and one thread. Summing per-vehicle windows at query time scales with the partitions.
6. **What does a fourth instance do?** With 3 partitions, nothing. It is a hot spare, and with standby replicas a warm one.
7. **Why are processed-positions offsets larger than the number of positions?** Transaction commit markers (section 9).
8. **Why at-least-once for the analytics?** It is off the critical path. A crash replays at most about 1 second of positions into counters, and its inputs are read committed. The normalizer is where exactly-once matters.

---

## 13. Mental model

> **Raw frames go in, trusted positions come out, exactly once. State lives in lockers that are copied into Kafka. Windows run on the device's clock and close after grace. Queries go to whoever holds the partition. One owner at a time; shadow first, then hand over.**
