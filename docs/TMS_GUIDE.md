# Transport management (TMS) on the GPS platform

## 1. Who uses it

| Who | Role at sign-in | Owns | Sees |
|---|---|---|---|
| **GPS provider** (us) | `DEVELOPER` | The GPS devices, the platform | Everything below, for every company, plus the engine (Kafka, consumers, dead letters, modules), devices and mapping |
| **Transporter** (truck company) | `TRANSPORTER` | Trucks; carries shipments | Its own trucks always; hired trucks while they carry its shipments; the shipments it carries; their alerts |
| **Consigner** (sends goods: Tata Steel) | `CONSIGNER` | The goods, the plants | Its shipments from gate-out to the consignee; a truck **only while it carries its goods**; how its transporters perform |
| Consignee (receives the goods) | – | – | A name and address on the shipment; not a login |

The sign-in page asks for the role together with the user id and password (a user id is unique per role). The demo
users are `abc` / `abc` for each role (`gps.auth.demo-accounts`; switch it off on a real installation and create users
on TMS → Companies & users). The transporter demo user is created for the transporter with the most shipments, as soon
as there are shipments.

**How the scoping is enforced.** The GPS service signs a token at sign-in (role, user, company), the dashboard keeps it
in its own signed cookie and sends it with every `/api/portal` call. `PortalAuthFilter` refuses a request without a
valid token, and every portal query is cut to the token's company (`ShipmentStore.Scope`): a transporter cannot see
another transporter's shipments by changing a URL. The dashboard additionally keeps a transporter on `/transporter/**`
and a consigner on `/consigner/**`, and lets neither call the engine API (`src/proxy.ts`, `mayCall`).

## 2. What each role gets

| | Transporter | Consigner | GPS provider |
|---|---|---|---|
| Fleet | trucks, moving / standing / offline / no device, utilisation (trucks on a shipment ÷ trucks), km today, over-speed today, idling | – | every company's fleet |
| Shipments | on the road, in the plant fence, delayed, at risk, arrived today, GPS silent | same, plus booked-not-out, quantity on the road | same, every company |
| On time | on-time %, average transit, late by | same, per transporter, lane, consignee | same |
| Detention | works detention (booking → gate-out), hold after gate-out (gate-out → leaving the plant fence) | same, per load plant | same |
| Scorecards | lanes | transporters (on-time, transit, delays, GPS coverage, over-speed on its goods), lanes, consignees, plants | all |
| Alerts | own trucks; hired trucks within the shipment | only within the shipment window | all |
| Upload | its own shipment file | – | any file, plus "map devices from the file" and the demo loader |
| Master data | – | – | device ↔ vehicle mapping, companies & users, places & geofences, module registry with replay |

Every computed number has a "How is this calculated?" drop-down on its page.

## 3. The shipment file

The transporters' files are the consigner TMS's trip export: the `smart_truck.tta_trips` table. The importer reads

- Excel (`.xlsx`, `.xls`), CSV, or rows copied out of the database (`'754710', '540', ... NULL`, even whole
  `INSERT ... VALUES` statements);
- with a header row, matched by name (the `tta_trips` column names, or Excel words like "Trip No", "Vehicle No",
  "IMEI", "Consignee", "Trip Start", "ETA" - see `ShipmentColumns`), or without one, by the `tta_trips` column order.

The sample row the transporter sent, read column by column:

| # | Column | Sample | Becomes |
|---|---|---|---|
| 1 | `i_trip_no` | 754710 | trip number: **the shipment's identity** within its transporter |
| 2 | `i_cnr_id` | 540 | consignor id → the consigner company (TATA STEEL) |
| 3 | `s_trip_class` | local | local / zonal: the speed the ETA estimate assumes (25 / 40 km/h) |
| 4 | `s_cnr_name` | TATA STEEL-JSR | consigner unit (plant / division); the company is the part before "-" |
| 5 | `s_asset_id` | JH05CY2516 | vehicle number: **the link to the GPS**, through the device mapping |
| 6 | `s_device_id` | 0869925072822461 | IMEI the TMS thinks is fitted: checked against the mapping, never trusted over it |
| 7 | `s_asset_type` | TRAILER | vehicle type |
| 8 | `c_trip_type` | NULL | trip type code |
| 9 | `s_trip_type_desc` | LOCAL TRIP | trip type |
| 10, 12 | `i_org_node_no`, `i_dest_node_no` | NULL | not used |
| 11 | `s_org_node_name` | JAMSHEDPUR | origin → the place (geofence) of that name |
| 13 | `s_dest_node_name` | ADITYAPUR | destination → the place of that name |
| 14 | `s_final_dest` | TATA STEEL PROCESSING (ASOKA SKY ME, JAMSHEDPUR | ship-to address (empty commas removed) |
| 15 | `s_load_plant` | LD3&TSCR Plant | load plant: detention per plant |
| 16 | `dt_booking` | 2026-08-03 04:31:10 | booking / plant entry (IST) |
| 17 | `dt_trip_start` | 2026-08-03 11:20:43 | gate-out: the trip starts |
| 18 | `dt_trip_eta` | 2026-08-04 11:20:43 | ETA |
| 19, 20 | `dt_trip_ata`, `dt_trip_end` | NULL | arrival / end as the TMS reported them |
| 21 | `i_trans_id` | 0000015300 | transporter vendor code → the transporter company |
| 22 | `s_trans_name` | Virk Eenterprises | transporter name |
| 23 | `s_cne_name` | TATA STEEL PROCESSING (ASOKA SKY ME | consignee |
| 24, 25 | `c_trip_status`, `s_close_reason` | Close, TRIP CLOSE BY GRN | the TMS's status and closing reason |
| 26 | `s_invoice` | 2314959959 | invoice number |
| 27 | `s_card_id` | 9FE9E1BB | RFID card |
| 28 - 30 | `s_shipment_id`, `s_event_code`, `s_gate_entry_no` | NULL | shipment / delivery ref, gate entry |
| 31, 32 | `s_driver_name`, `s_driver_mobile_no` | ASH0K KUMAR YADAV, 8229835350 | driver |
| 33 | `i_route_id` | NULL | route id |
| 34 - 37 | created / modified by and at | | not used (audit of the TMS) |
| 38 | `i_gps_ping_count` | 1430 | not used: the platform counts its own readings |
| 39 - 44 | geofence-out, gap, status, detention minutes | intra_fence, 409 | not used: the platform works these out from the GPS itself (`left_origin_at`, works detention = 409 min here) |

Times without a zone are IST (`gps.tms.time-zone`). A row without trip number, vehicle or trip start is refused with
the reason; a bad optional value (a date that is not a date, an IMEI that is not digits) is a warning and the row is
imported without it.

**Idempotent upload.** A shipment is identified by (transporter, trip number). The same file again, a corrected
file, or the next day's export repeating yesterday's trips updates those shipments instead of adding copies (the
TMS's "MULTIPLE UPLOAD" problem cannot happen); an unchanged row is not even written (`row_hash`). The whole file is
one transaction. The preview shows what an import would do without writing anything.

**Next upload closes the previous trip.** A newer shipment of the same truck closes its older ones that never arrived
(the TMS's "TRIP CLOSED BY NEXT UPLOAD").

## 4. How a shipment meets the GPS

```
shipment.vehicle_registration_no  ->  device_assignment (period covering the reading)  ->  IMEI  ->  readings
            (from the file)              (TMS -> Device <-> vehicle mapping)
```

- The **mapping** decides, not the file: the file's IMEI is shown next to the mapped one (`IMEI differs`), and the
  GPS provider can fit the file's IMEI into a truck that has no device yet ("Map devices from the file"). A truck with
  no device mapped is followed through the file's IMEI if there is one (`File IMEI`), otherwise not at all
  (`No device`).
- The **places** (table `place`) are the geofences, matched by name: the 87 smart_truck geofences are loaded by the
  migration (Tata Steel Jamshedpur, 10 km, as the origin; the destination cities and yards). A name without a place
  is listed in the preview: add it on TMS → Places & geofences.
- The **shipment-tracking module** (`gps-shipment-tracking-service`) works out, for every reading of the truck between
  the gate-out and the arrival:
  - **departure**: the first reading outside the origin fence - or, on an intra-fence trip (Adityapur lies inside the
    10 km Jamshedpur fence, like trip 754710), the first moving reading;
  - **arrival**: the first reading inside the destination fence after one outside it;
  - **delay**: the first reading after the ETA without an arrival opens SHIPMENT_DELAYED; the arrival closes it.
  Each is the earliest reading that proves it, so late readings from the device's memory and replays give the same
  answer as a clean live run.
- At read time (`ShipmentStatus`): stage (booked, at origin, in transit, arrived, closed), verdict (on time, late, on
  track, at risk, delayed), the estimate (straight line × 1.3 at 25 / 40 km/h), transit, works detention, hold after
  gate-out, GPS silent (no reading for an hour while on the road).

## 5. The five guarantees

| # | Requirement | What does it | Where | How to see it |
|---|---|---|---|---|
| i | **Idempotent**: the same data arrives many times, keep one | Positions `UNIQUE (imei, recorded_at)`; events upserted by IMEI + type + start; trips guarded by `last_recorded_at`; shipments upserted by (transporter, trip no) with `row_hash`; device mapping "same device, same truck = no change"; shipment GPS moments = earliest proof | `GpsPositionStorageService`, `VehicleEventRecorder`, `ShipmentStore.upsert`, `DeviceMappingService.assign`, `ShipmentProgress` | Upload a file twice: "N unchanged". Replay a module: no duplicate events |
| ii | **Server down / pings not received: no loss** | The device keeps readings while it cannot send and sends them later: they are accepted out of order (`arrivedOutOfOrder`) and matched to the truck **at reading time**. On our side the ingest journal fsyncs every frame before Kafka and resends after a crash or outage; Kafka keeps 31 days | `CalculateMovementStep`, `DeviceVehicleDirectory`, `IngestJournal`, `KafkaTopicDeclarations` | Storage → Data safety: journal, retention, every group's bookmark against the log start |
| iii | **Exception handling: dead-letter queue** | A reading a consumer cannot handle goes to `gps.dead-letters` with topic, partition, offset, group and the error, is archived for good in PostgreSQL `dead_letter`, and can be replayed or discarded with a note | `KafkaRetrySettings`, `DeadLetterArchive`, `DeadLetterService` | Storage → Dead letters |
| iv | **Processing cannot keep up or fails: Kafka holds it** | Offsets are committed only after the work is done; infrastructure failures are retried for ever (1 s → 60 s), so the reading waits in Kafka, and each module has its own group, so one slow module holds up nobody else | `KafkaRetrySettings`, `FailureClassifier`, `ModuleKafkaListeners` | Processing → Consumer groups: lag rises, then falls to 0 |
| v | **Consumer received and failed: replay (old data too)** | Dead letters are replayed to their original topic; a whole module is replayed from any moment Kafka still holds (its bookmark moved back, its state rebuilt, other groups untouched) | `DeadLetterService.replay`, `ModuleReplayService` | Developer → Module registry → Replay |

## 6. Try it

1. Start the fleet simulator (Live → Fleet simulator, faithful timing).
2. Sign in as GPS provider (`abc` / `abc`), TMS → Upload shipments → **Load demo shipments from smart_truck**: the
   trips the simulator is driving become shipments (their TMS rows, moved to the replay clock).
3. TMS → All shipments: watch them go from "At origin" to "In transit" to "Arrived" as the trucks drive.
4. Sign out, sign in as **Consigner** `abc` / `abc` (Tata Steel) and as **Transporter** `abc` / `abc` (the busiest
   transporter): the same shipments, each from its own side, and nothing of anyone else.
5. Upload the transporter's own file (or paste the sample row) on Upload shipments: preview first, then import.

## 7. API

All behind the sign-in token (`Authorization: Bearer`), cut to the user's company.

| | |
|---|---|
| `POST /api/auth/login` `{role, username, password, remember}` | token + user |
| `GET /api/portal/me` | the token's user |
| `GET /api/portal/overview?transporter=&consigner=` | the role's KPIs (the GPS provider may narrow to a company) |
| `GET /api/portal/shipments?q=&days=&limit=` · `/{id}` | shipments; one with its track, events, timeline |
| `POST /api/portal/shipments/import/preview?fileName=` (raw body) · `/import?applyMappings=` | preview / import |
| `GET /api/portal/shipments/imports` · `/template` | upload history, CSV template |
| `GET /api/portal/fleet` · `/alerts` · `/companies` | fleet board, alerts, companies for pickers |
| `/api/portal/admin/users`, `/companies`, `/devices`, `/vehicles`, `/mappings`, `/mappings/unassign`, `/mappings/history`, `/places`, `/demo-shipments` | GPS provider only |
| `GET /api/modules` · `POST /api/modules/{id}/replay?from=` | module registry, replay |
