# Data Safety Guide: a month in Kafka, and no record ever lost

> **In one paragraph:**
> - Every platform topic keeps its records for **31 days**, and existing topics are brought up to that at every start.
> - Device frames and vehicle events are written and fsynced to an **ingest journal on disk** before they are sent, and stay there until Kafka confirms them.
> - Producers wait for every in-sync copy (`acks=all`, idempotent). A single broker flushes to disk before it acknowledges; the 3-broker setup keeps 3 copies, 2 written before the acknowledgement.
> - Consumers **never skip a record**. An outage is waited out; a record that cannot be handled is parked in **`gps.dead-letters`** (listeners and both Kafka Streams apps) and archived in PostgreSQL for good, where it can be replayed or discarded with a note.
> - Rejected frames are archived as well.
> - A **data-safety report** compares every consumer group's position with what Kafka still keeps, so a loss could not go unnoticed: it turns `/actuator/health` DOWN.

---

## 1. Desi analogy: the courier office that never loses a parcel

- **The counter clerk** (the ingest journal) writes every parcel in a carbon-copy register *before* handing it to the van. Until the warehouse signs for it, the parcel's entry stays open. If the van breaks down, the clerk sends it again, in the same order.
- **The warehouse** (Kafka) keeps every parcel for a month. It signs only once the parcel is on its shelf (and, with three warehouses, on two shelves in two buildings).
- **The delivery boys** (consumers) never throw a parcel away:
  - If the customer's shop is closed (PostgreSQL down), they wait outside and try again.
  - If the address is unreadable (a broken record), they put the parcel in the **problem-parcel room** (the dead-letter queue) with a note saying where it came from and what went wrong.
- **The problem-parcel register** (PostgreSQL `dead_letter`) is kept forever. A parcel leaves it only by being re-sent (replay) or by a signed note explaining why not (discard).
- **The auditor** (the data-safety report) checks every day that no delivery boy fell so far behind that the warehouse cleared a shelf he had not collected yet.

---

## 2. Where a record could be lost, and what closes each gap

| # | Where it could be lost | What closes it |
|---|---|---|
| 1 | The simulator or device parser hands a frame to the producer, and Kafka is down longer than the client's timeout. The old producer was fire-and-forget. | **Ingest journal** (§3): fsynced before `send()` returns, kept until Kafka confirms it, re-sent in order. |
| 2 | The service is killed with frames still in the producer's memory buffer. | The same journal: anything not confirmed is re-sent after the restart. |
| 3 | Kafka acknowledges, then the broker loses power before its disk flush. | Single broker: `log.flush.interval.messages=1` (on disk before the acknowledgement). HA: 3 copies, `min.insync.replicas=2`. |
| 4 | A consumer cannot handle a record and the old error handler **skipped** it (bad JSON, a database rule). | **Dead-letter queue** (§4): parked in `gps.dead-letters` before the offset moves on, archived in PostgreSQL. |
| 5 | Kafka Streams skipped a record it could not read (`LogAndContinue`). | Kafka Streams' own DLQ (`errors.dead.letter.queue.topic.name`, KIP-1034) plus a processing-exception handler. The dead letter is written in the same exactly-once transaction. |
| 6 | Retention was 7 days. A consumer stopped for longer missed records Kafka had already deleted. | **31 days** on every topic (§5). Committed offsets are kept 31 days too, and the safety report flags a group falling behind (§6). |
| 7 | A consumer group stopped for over 7 days lost its committed offsets. | `offsets.retention.minutes=44640` (31 days). |
| 8 | A misspelled topic name was auto-created with 1 partition and nobody read it. | `auto.create.topics.enable=false`; every topic is declared. |
| 9 | A replica that missed records was elected leader. | `unclean.leader.election.enable=false`, on the broker and on every topic. |
| 10 | Rejected frames only reached the service log, which rotates. | Archived in PostgreSQL `rejected_frame` with the original payload. |
| 11 | A dead letter older than a month was deleted from Kafka. | Archived in PostgreSQL `dead_letter`; rows are never deleted. |

---

## 3. The ingest journal

Code: [`IngestJournal`](../gps-connectivity/src/main/java/com/srm/gps/connectivity/journal/IngestJournal.java), [`DurableKafkaSender`](../gps-connectivity/src/main/java/com/srm/gps/connectivity/journal/DurableKafkaSender.java). Used by `RawGpsFramePublisher` (device frames) and `VehicleEventPublisher` (vehicle events): the two producers that nobody else keeps a copy for.

```
send(frame) ─► append to segment-000…042.log + fsync ─► return to the caller (it never waits for Kafka)
                        │
                        └─► Kafka ──► confirmed: marked; a segment is deleted once all its records are confirmed
                                  └─► refused / timed out: stays; drain() re-sends the journal in order
```

- **Format:** one JSON line per record, segments of 1,000 records. `acknowledged-through` holds the highest sequence up to which Kafka confirmed everything, written atomically every second.
- **Order:** while anything is unconfirmed and not on its way, new records are only journaled. `drain()` sends the journal oldest first, one batch at a time. A device's frames reach Kafka in the order they arrived, even across an outage.
- **Restart:** every segment still on disk is read back. Whatever is past the checkpoint is sent again. This can repeat a record Kafka already had; that is safe because the normalizer's duplicate store and storage's `UNIQUE (imei, recorded_at)` absorb it. Re-sending is safe; forgetting is not.
- **A half-written last line** (killed mid-append) is ignored. Nothing after it was ever acknowledged to a caller.
- **The caller is never held up by Kafka**, only by the disk. `max.block.ms=5000` makes a down Kafka refuse quickly; the journal already has the record.
- **The only failure a caller can see:** the journal cannot write (disk full, folder not writable). Then nothing was accepted and the caller gets an exception, so the frame is never dropped silently.
- **Docker:** `/var/lib/gps-journal` on the `gps-ingest-journal` volume, so a new container sends what the old one could not.

| Setting (`gps.journal.*`) | Default | |
|---|---|---|
| `directory` | `./data/ingest-journal` (Docker: `/var/lib/gps-journal`) | keep it on a disk that survives the service |
| `fsync-every-record` | `true` | on disk before `send()` returns: a power cut loses nothing |
| `records-per-segment` | `1000` | |
| `drain-batch-size` | `5000` | records re-sent per round while catching up |

---

## 4. The dead-letter queue

**Listeners** ([`KafkaRetrySettings`](../gps-connectivity/src/main/java/com/srm/gps/connectivity/kafka/KafkaRetrySettings.java), [`FailureClassifier`](../gps-connectivity/src/main/java/com/srm/gps/connectivity/kafka/FailureClassifier.java)):

| The failure | What happens |
|---|---|
| Infrastructure: PostgreSQL, Redis, Kafka or the network unavailable | Retry after 1 s, 2 s, 4 s … then every 60 s, **forever**. The partition waits; the record waits in Kafka. |
| The message itself (`InvalidGpsMessageException`, `DataIntegrityViolationException`) | Straight to `gps.dead-letters`. |
| Anything else (most likely a bug) | 3 tries, then `gps.dead-letters`. |

The dead letter is published **and confirmed** before the offset moves on. If that publish fails, the record is delivered again. Headers carry the original topic, partition, offset, consumer group, timestamp, exception class, message and stack trace.

**Kafka Streams** (both apps):

- `errors.dead.letter.queue.topic.name=gps.dead-letters`: a record that cannot be deserialized is parked (`LogAndContinueExceptionHandler`, KIP-1034).
- [`DeadLetterProcessingExceptionHandler`](../gps-streams/src/main/java/com/srm/gps/streams/errors/DeadLetterProcessingExceptionHandler.java):
  - A processor's exception parks the **source record as it was read** and resumes.
  - A `StateUnavailableException` (the database fallback could not be reached) **fails** the task instead. The thread is replaced and the record is processed again from the last commit, because the record is fine.
  - Under exactly-once the dead letter is part of the same transaction as the offset that moves past the record.

**Archive** ([`DeadLetterArchive`](../gps-application/src/main/java/com/srm/gps/application/archive/DeadLetterArchive.java)):

- Consumer group `gps-dead-letter-archive` stores every dead letter in `dead_letter`.
- It is idempotent: dead-letter partition + offset + time.
- Text is made storable first (no NUL, column lengths), so the database can never refuse a dead letter.
- Its own failures are retried until it is stored; a dead letter is never parked twice.

**Way out** (console: Storage → Dead letters, or the API):

```
POST /api/dead-letters/{id}/replay              back to its original topic, confirmed by Kafka, then REPLAYED
POST /api/dead-letters/replay?topic=&limit=200  the oldest parked ones
POST /api/dead-letters/{id}/discard?note=...    not to be replayed; the note is kept with the record
```

A replay reaches every consumer group of the topic again, not only the one that failed. That is safe because they are idempotent.

---

## 5. One month in Kafka

- Every platform topic is declared by [`KafkaTopicDeclarations.durable`](../gps-connectivity/src/main/java/com/srm/gps/connectivity/kafka/KafkaTopicDeclarations.java) with:
  - `retention.ms` = 31 days and `retention.bytes=-1` (age only, never size);
  - `cleanup.policy=delete` (every record kept, not only the last per key);
  - `min.insync.replicas`;
  - `unclean.leader.election.enable=false`.
- The topics are `gps.raw-device-frames`, `gps.processed-positions`, `gps.rejected-frames`, `gps.vehicle-events`, `gps.vehicle-activity` and `gps.dead-letters`, plus the shadow topics.
- `spring.kafka.admin.modify-topic-configs: true` brings **existing** topics in line at every start: a 7-day topic becomes 31 days without a manual step. Replication factor of an existing topic is the exception; that needs a partition reassignment.
- The broker default is the same (`KAFKA_LOG_RETENTION_HOURS=744`) for anything else. Kafka Streams changelogs are compacted (latest per key), which is what they need.

**Disk sizing** (zstd compression is on):
- Live, 150 vehicles at about 2.5 frames/s, a month of raw + processed + events is a few GB.
- The compressed simulator mode (75 frames/s) is 30× that.
- `GET /api/data-safety` shows the bytes on disk per topic.

---

## 6. Proof: the data-safety report

`GET /api/data-safety`, console **Storage → Data safety**, and `dataSafety` in `/actuator/health`. Code: [`DataSafetyChecks`](../gps-application/src/main/java/com/srm/gps/application/safety/DataSafetyChecks.java) (pure rules, unit-tested) and [`DataSafetyService`](../gps-application/src/main/java/com/srm/gps/application/safety/DataSafetyService.java) (gathers the facts).

| Check | FAIL when | WARN when |
|---|---|---|
| retention | a platform topic keeps less than 31 days, or is limited by size | – |
| no-loss | **a consumer group's committed offset is below the log start**: Kafka deleted records it never read | – |
| replication | – | fewer than 3 copies or 2 in sync (the single local broker) |
| broker-fsync | – | one copy and the broker acknowledges before its disk flush |
| offsets | – | committed offsets kept shorter than the data |
| auto-create | – | the broker creates topics on first use |
| producers | not `acks=all` with idempotence | – |
| journal | – | records waiting for Kafka, or no fsync per record |
| dead-letters | – | records parked, waiting for a fix |
| stopped-groups | – | a group not running while records wait for it |

**FAIL** means data was lost or can be lost now, and health goes DOWN. **WARN** means nothing is lost, but a protection is weaker than it could be. With the single local broker the verdict is WARN for one reason only: one copy.

---

## 7. One broker, or three

| | `docker-compose.yml` (default) | `+ docker-compose.ha.yml` |
|---|---|---|
| Brokers | 1 | 3 (KRaft, each broker + controller) |
| Copies per record | 1 | 3 |
| Copies before "ok" | 1, flushed to disk first | 2 |
| Survives | crash, restart, power cut | all of those, plus a lost broker or disk |
| Start | `docker compose up -d --build` | `docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d --build` |

The HA override uses its own volumes: a single-broker KRaft log cannot join a 3-broker quorum. In production, put the three brokers on three machines (and racks: `client.rack` / `rack.aware.assignment.tags` are passed through `gps.streams.properties`).

Windows without Docker: `infrastructure\windows\setup-kafka.ps1` applies the same broker settings, also to a broker set up earlier. Restart Kafka afterwards.

---

## 8. What is still your responsibility

- **The disk under a single broker.** With one copy, a lost disk loses what is on it. Run the HA setup for anything that matters, and back up the PostgreSQL archive like any database.
- **Starting a stopped consumer within the month.** Records wait 31 days for it. After that Kafka deletes them, and the safety report would show the loss (FAIL), not hide it.
- **The direct flow** (`gps.processing-flow=direct`) has no Kafka, no journal and no dead letters: a failure is returned to the caller. It is a debugging mode.
- **The demo reset** (`POST /api/admin/reset`) deliberately empties everything, archives included. It is switched off with `gps.admin.reset-enabled=false`.

---

## 9. Tested

- `IngestJournalTest`:
  - a segment is deleted only when fully acknowledged;
  - unacknowledged records survive a restart;
  - without a checkpoint everything is re-sent;
  - sequences continue after a restart;
  - a torn last line is ignored.
- `DurableKafkaSenderTest`:
  - a confirmed record leaves the journal;
  - a failed one is re-sent **in order**, with new records queued behind it;
  - a Kafka that refuses at once holds nobody up and loses nothing;
  - records journaled before a crash are sent after the restart.
- `DeadLetterHandlingTest`: outages are waited for, poison is parked; both dead-letter header styles are read.
- `DeadLetterTopologyTest` (Kafka Streams):
  - an unreadable record is parked with its origin and the stream moves on;
  - an unreachable database stops the task instead of parking or skipping the frame.
- `DataSafetyChecksTest`: every rule.
