// Database Schema Specification for gps_tracking (PostgreSQL), plus the Redis key space, Kafka topics, Kafka Streams
// stores and the read-only smart_truck source. The full DDL appendix is read from the Flyway migrations themselves.
const fs = require("fs");
const path = require("path");
const { DocBuilder, ref } = require("./lib");

const MIGRATIONS = path.join(__dirname, "..", "..", "gps-application", "src", "main", "resources", "db", "migration");
const FIG = (name) => path.join(__dirname, "figures", name);

const META = {
  organisation: "SRMECOTECH",
  title: "Database Schema Specification",
  subtitle: "PostgreSQL database gps_tracking (Flyway V1 to V11), with the Redis key space, Kafka topics and state stores of the GPS Processing Layer",
  shortTitle: "DB Schema: gps_tracking",
  docId: "SRM-GPS-DBS-001",
  version: "0.11",
  status: "Draft for review (not yet baselined)",
  date: "3 November 2026",
  author: "Sanjoy Chattopadhay, SRMECOTECH",
  classification: "Internal",
  standard: "ISO/IEC/IEEE 29148:2018 (data requirements)",
  confidentialNote: "Companion to SRM-GPS-SRS-001. The schema described here is the one created by the Flyway migrations "
    + "V1 to V11 in gps-application/src/main/resources/db/migration; if the two ever differ, the migrations are authoritative "
    + "and this document shall be corrected.",
};

/** Column table: [column, type, null, default, description]. */
function columns(d, caption, rows) {
  d.table(caption, [
    { header: "#", width: 400, align: "center" },
    { header: "Column", width: 2150 },
    { header: "Type", width: 1550 },
    { header: "Null", width: 560, align: "center" },
    { header: "Default", width: 1100 },
    { header: "Description", width: 3266 },
  ], rows.map((r, i) => [String(i + 1), { text: r[0], bold: true }, r[1], r[2], r[3], r[4]]), { size: 16 });
}

function constraints(d, caption, rows) {
  d.table(caption, [
    { header: "Constraint", width: 3000 }, { header: "Kind", width: 1000, align: "center" }, { header: "Definition and purpose", width: 5026 },
  ], rows, { size: 16 });
}

function indexes(d, caption, rows) {
  d.table(caption, [
    { header: "Index", width: 3000 }, { header: "Columns", width: 2600 }, { header: "Serves", width: 3426 },
  ], rows, { size: 16 });
}

function tableHeader(d, name, facts) {
  d.h2(`Table ${name}`);
  d.keyValue(null, facts, 2000);
}

function build() {
  const d = new DocBuilder(META);
  d.titlePage();
  d.documentControl(
    [
      ["0.1", "19 Oct 2026", "S. Chattopadhay", "Tables listed in README section 11 (V1 to V4)."],
      ["0.9", "29 Oct 2026", "S. Chattopadhay", "First formal specification: data dictionary for all 15 tables (V1 to V8), constraints, indexes, relationships, lifecycle, Redis, Kafka and state stores, full DDL."],
      ["0.11", "3 Nov 2026", "S. Chattopadhay", "V9 (transport management: sign-in, places, shipments), V10 (geofencing: place as the geofence master, geofence_visit) and V11 (route plans and stops): eight new tables, second ER figure, new value sets, queries and idempotency keys."],
    ],
    [
      ["Technical lead / architect", "", "", ""],
      ["Database administrator", "", "", ""],
      ["Quality assurance lead", "", "", ""],
    ],
  );
  d.toc();

  // ---------------------------------------------------------------------------------------------- 1
  d.h1("Introduction");
  d.h2("Purpose");
  d.p("This document specifies the persistent data of the GPS Processing Layer: the PostgreSQL database gps_tracking in full, "
    + "and the Redis key space, Kafka topics and Kafka Streams state stores that hold the rest of the platform's state. It is "
    + "the physical counterpart of Sections 3.2 and 6 of the Software Requirements Specification SRM-GPS-SRS-001 and "
    + "satisfies its data requirements DR-001 to DR-010.");
  d.h2("Scope");
  d.bullets([
    "Twenty-three PostgreSQL tables created by Flyway migrations V1 to V11: master data, telemetry, trip detection, events, reporting, archives, configuration, sign-in, shipments, geofencing and route plans.",
    "Constraints, indexes, relationships, value sets, write patterns, idempotency keys, retention and recovery.",
    "Redis keys (live state, configuration, counters), Kafka topics and Kafka Streams state stores.",
    "The read-only legacy source smart_truck, as far as this platform reads it.",
  ]);
  d.h2("References");
  d.table("Referenced documents", [{ header: "Ref.", width: 700 }, { header: "Document", width: 8326 }], [
    ["[S1]", "SRM-GPS-SRS-001, Software Requirements Specification: GPS Processing Layer."],
    ["[S2]", "SRM-GPS-SRS-RUL-001, SRS Rules and Regulations."],
    ["[S3]", "gps-application/src/main/resources/db/migration/V1 to V11 (authoritative DDL)."],
    ["[S4]", "README.md sections 11, 12, 14 and 15; docs/DATA_SAFETY_GUIDE.md; docs/REPORTS_GUIDE.md; docs/TMS_GUIDE.md; docs/GEOFENCING_GUIDE.md; docs/ROUTE_DEVIATION_ETA_DESIGN.md."],
    ["[S5]", "PostgreSQL 14 documentation: data types, constraints (EXCLUDE with btree_gist), indexes."],
  ]);
  d.h2("Notation");
  d.table("Notation used in this document", [{ header: "Symbol", width: 1500 }, { header: "Meaning", width: 7526 }], [
    ["PK", "Primary key"], ["UK", "Unique key (UNIQUE constraint)"], ["FK", "Foreign key"],
    ["CK", "CHECK constraint"], ["EX", "Exclusion constraint (EXCLUDE USING gist)"], ["IX", "Secondary index"],
    ["Null = N", "Column is NOT NULL"],
    ["Logical reference", "A column that names a row of another table (for example imei) without a foreign key; integrity is kept by the application (Section 5)."],
    ["timestamptz", "timestamp with time zone; every value is stored and compared as an absolute instant (UTC)."],
  ]);

  // ---------------------------------------------------------------------------------------------- 2
  d.h1("Database Overview");
  d.keyValue("Database facts", [
    ["DBMS", "PostgreSQL 14 (Docker image postgres:14; native 14.22)"],
    ["Database / schema", "gps_tracking / public"],
    ["Application user", "gps_app (password from local-secrets.properties or .env; never in version control)"],
    ["Extensions", "btree_gist (needed for the exclusion constraint on device_assignment)"],
    ["Schema management", "Flyway, migrations V1 to V11; Hibernate ddl-auto = validate (it never changes the schema)"],
    ["Time zone", "JDBC session time zone UTC (hibernate.jdbc.time_zone); all timestamps are timestamptz"],
    ["Character set", "UTF-8"],
    ["Connection", "jdbc:postgresql://localhost:5432/gps_tracking (Docker: postgres:5432, host port 5433)"],
  ]);
  d.h2("Subject Areas");
  d.table("Tables by subject area", [
    { header: "Subject area", width: 1900 }, { header: "Tables", width: 3300 }, { header: "Written by", width: 3826 },
  ], [
    ["Master data", "tenant, vehicle, device, device_assignment", "SimulationMasterDataSeeder (future: master-data admin API)"],
    ["Telemetry", "gps_position", "Consumer group gps-position-storage-service (or the direct flow)"],
    ["Trip detection", "vehicle_motion_state, gps_trip, gps_stop, gps_trip_late_reading", "Consumer group gps-trip-service"],
    ["Events", "vehicle_event", "Speed-violation, vehicle-state, stoppage and ignition consumers"],
    ["Reporting", "vehicle_day_summary, report_setting", "DaySummaryJob; Reports → Cost assumptions"],
    ["Archives", "dead_letter, rejected_frame", "gps-dead-letter-archive; gps-rejected-frame-logger"],
    ["Configuration", "connection_setting", "Developer → Connections"],
    ["Sign-in (V9)", "app_user, auth_key; tenant.kind", "AuthController, PortalAdminController"],
    ["Shipments (V9)", "shipment, shipment_import, place", "ShipmentImportService (Excel / CSV / tta_trips), module shipment-tracking"],
    ["Geofencing (V10)", "place (geofence master), geofence_visit", "Live geofences page, module geofence"],
    ["Route plans (V11)", "route_plan, route_stop", "RoutePlanService, modules route-deviation and waypoints"],
  ]);
  d.p(ref("dbs-er") + " (next page) shows the tables of V1 to V8 and their relationships; " + ref("dbs-er-tms") + " those added by V9 to V11.");
  d.landscape();
  d.figure(FIG("er-diagram.png"), "Entity-relationship overview of gps_tracking", 9.0, "dbs-er");
  d.figure(FIG("er-tms-routes.png"), "Tables added by V9 to V11: sign-in, shipments, geofencing, route plans", 9.0, "dbs-er-tms");
  d.portrait();

  // ---------------------------------------------------------------------------------------------- 3
  d.h1("Design Principles and Naming Conventions");
  d.h2("Design Principles");
  d.table("Design decisions", [{ header: "Decision", width: 2900 }, { header: "Rationale", width: 6126 }], [
    ["Master data uses uuid keys and foreign keys.", "Few rows, changed rarely, referenced by other master data: the database guarantees integrity."],
    ["Telemetry uses bigint identity keys and logical references (imei, tenant_code, vehicle_registration_no).", "Written at high rate; readable without joins; must survive master-data changes; planned to move to a time-series store."],
    ["Device assignments are periods, never a “current vehicle” column.", "A late reading must be matched to the vehicle the device was in at the reading's time (BR-004)."],
    ["Every consumer-written table has a natural idempotency key.", "Kafka delivers at least once; a redelivered record must not create a second row (Section 8)."],
    ["Trip state is updated with a guard on last_recorded_at.", "A repeated or late reading updates 0 rows, so the whole transaction is rolled back."],
    ["Value sets are CHECK constraints, not lookup tables.", "Small, stable sets; the check documents the allowed values next to the column."],
    ["Archives are never deleted; status changes instead.", "Every record that did not become a position stays traceable (BR-006)."],
    ["Reports read a summary table.", "One row per vehicle per day keeps month reports small whatever the position volume."],
  ]);
  d.h2("Naming Conventions");
  d.bullets([
    "Tables: singular, lower snake_case (gps_position, not GpsPositions).",
    "Time columns end in `_at` and are timestamptz; a date-only column is named `day`.",
    "Units are suffixes: `_m` metres, `_s` seconds, `_kmph` km/h, `_meters` in older columns of the same meaning.",
    "Constraints: <table>_<rule> (gps_trip_distance_not_negative); indexes: <table>_<columns or purpose>.",
    "Status columns hold upper-case words (OPEN, CLOSED, PARKED …).",
  ]);

  // ---------------------------------------------------------------------------------------------- 4
  d.h1("Data Dictionary");
  d.p("For each table: purpose, writer and readers, write pattern, columns, constraints and indexes. Column order follows the migration that created it; columns added later (V5) are listed at the end.");

  tableHeader(d, "tenant", [
    ["Purpose", "A customer company on the platform."],
    ["Created in", "V1__create_master_data_tables.sql"],
    ["Written by / read by", "SimulationMasterDataSeeder / DeviceVehicleDirectory, reports filters"],
    ["Expected size", "Tens of rows"],
  ]);
  columns(d, "tenant columns", [
    ["id", "uuid", "N", "", "Surrogate key, generated by the application."],
    ["code", "varchar(40)", "N", "", "Short lower-case code stamped on every position, never re-used (BR-003)."],
    ["legal_name", "varchar(255)", "N", "", "Registered company name."],
    ["status", "varchar(20)", "N", "'ACTIVE'", "ACTIVE, SUSPENDED or CLOSED."],
    ["created_at", "timestamptz", "N", "now()", "Creation time."],
  ]);
  constraints(d, "tenant constraints", [
    ["tenant_pkey", "PK", "(id)"],
    ["tenant_code_key", "UK", "(code): one code per tenant, platform-wide."],
    ["tenant_code_format", "CK", "code ~ '^[a-z0-9][a-z0-9_-]*$'"],
    ["tenant_status_values", "CK", "status IN ('ACTIVE','SUSPENDED','CLOSED')"],
  ]);

  tableHeader(d, "vehicle", [
    ["Purpose", "A truck, identified by its registration number."],
    ["Created in", "V1"],
    ["Written by / read by", "SimulationMasterDataSeeder / DeviceVehicleDirectory"],
    ["Expected size", "Hundreds to thousands of rows"],
  ]);
  columns(d, "vehicle columns", [
    ["id", "uuid", "N", "", "Surrogate key."],
    ["registration_no", "varchar(50)", "N", "", "Registration plate, unique across the platform (BR-002)."],
    ["owner_tenant_id", "uuid", "Y", "", "Owning tenant; null means a market vehicle."],
    ["created_at", "timestamptz", "N", "now()", "Creation time."],
  ]);
  constraints(d, "vehicle constraints", [
    ["vehicle_pkey", "PK", "(id)"],
    ["vehicle_registration_no_key", "UK", "(registration_no)"],
    ["vehicle_owner_tenant_id_fkey", "FK", "owner_tenant_id → tenant(id); default NO ACTION on delete and update."],
  ]);

  tableHeader(d, "device", [
    ["Purpose", "A tracking device, known only by its IMEI."],
    ["Created in", "V1"],
    ["Written by / read by", "SimulationMasterDataSeeder / DeviceVehicleDirectory"],
    ["Expected size", "Hundreds to thousands of rows"],
  ]);
  columns(d, "device columns", [
    ["id", "uuid", "N", "", "Surrogate key."],
    ["imei", "varchar(50)", "N", "", "Device identity; the Kafka record key of all its messages."],
    ["kind", "varchar(20)", "N", "'GPS'", "GPS, FUEL, TEMPERATURE, DOOR or OBD."],
    ["protocol", "varchar(50)", "Y", "", "Device protocol name, for the future parser."],
    ["created_at", "timestamptz", "N", "now()", "Creation time."],
  ]);
  constraints(d, "device constraints", [
    ["device_pkey", "PK", "(id)"],
    ["device_imei_key", "UK", "(imei)"],
    ["device_kind_values", "CK", "kind IN ('GPS','FUEL','TEMPERATURE','DOOR','OBD')"],
  ]);

  tableHeader(d, "device_assignment", [
    ["Purpose", "The period during which a device is installed in a vehicle."],
    ["Created in", "V1"],
    ["Written by / read by", "SimulationMasterDataSeeder / DeviceVehicleDirectory (cached per period)"],
    ["Rule", "A device is in at most one vehicle at any instant (BR-001). The period is half-open: [valid_from, valid_to)."],
  ]);
  columns(d, "device_assignment columns", [
    ["id", "uuid", "N", "", "Surrogate key; also stored in vehicle_motion_state.assignment_id."],
    ["device_id", "uuid", "N", "", "The device."],
    ["vehicle_id", "uuid", "N", "", "The vehicle."],
    ["valid_from", "timestamptz", "N", "", "Start of the installation (inclusive)."],
    ["valid_to", "timestamptz", "Y", "", "End (exclusive); null while still installed."],
  ]);
  constraints(d, "device_assignment constraints", [
    ["device_assignment_pkey", "PK", "(id)"],
    ["device_assignment_device_id_fkey", "FK", "device_id → device(id)"],
    ["device_assignment_vehicle_id_fkey", "FK", "vehicle_id → vehicle(id)"],
    ["device_assignment_period_is_valid", "CK", "valid_to IS NULL OR valid_to > valid_from"],
    ["device_assignment_one_vehicle_per_device_at_a_time", "EX", "EXCLUDE USING gist (device_id WITH =, tstzrange(valid_from, valid_to) WITH &&): no two periods of one device overlap."],
  ]);
  indexes(d, "device_assignment indexes", [
    ["device_assignment_vehicle", "(vehicle_id)", "Devices of a vehicle"],
    ["(exclusion constraint index)", "gist (device_id, tstzrange)", "Assignment of a device at an instant"],
  ]);

  tableHeader(d, "gps_position", [
    ["Purpose", "Every accepted reading, after normalisation, with tenant and vehicle resolved at the reading's time."],
    ["Created in", "V2 (columns total_distance_m, distance_source, ignition, odometer_m added in V5)"],
    ["Written by / read by", "gps-position-storage-service / position APIs, report summary job, vehicle-day sheet, previous-reading fallback"],
    ["Write pattern", "Insert only; a duplicate (imei, recorded_at) is skipped (checked first, and enforced by the unique key)."],
    ["Expected volume", "About 216,000 rows a day for 150 vehicles at one reading a minute; about 6.5 million a day in compressed simulator timing."],
  ]);
  columns(d, "gps_position columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["imei", "varchar(50)", "N", "", "Device (logical reference to device.imei)."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant at the reading's time; null when the device was unassigned."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle at the reading's time; null when unassigned."],
    ["recorded_at", "timestamptz", "N", "", "Time the device recorded the reading."],
    ["latitude", "double precision", "N", "", "WGS-84 degrees, −90 to 90."],
    ["longitude", "double precision", "N", "", "WGS-84 degrees, −180 to 180."],
    ["speed_kmph", "integer", "N", "", "Reported speed, 0 to 150."],
    ["movement_status", "varchar(10)", "N", "", "MOVING or STOPPED."],
    ["distance_from_previous_m", "double precision", "N", "", "Counted distance since the previous in-order reading (0 for first, jitter, jump and late readings)."],
    ["seconds_since_previous", "bigint", "N", "", "Seconds since the previous in-order reading (0 for first and late readings)."],
    ["arrived_out_of_order", "boolean", "N", "", "True for a late reading."],
    ["processed_at", "timestamptz", "N", "", "When the normaliser produced the position."],
    ["stored_at", "timestamptz", "N", "now()", "When the row was inserted."],
    ["total_distance_m", "double precision", "Y", "", "Running distance total of the device (V5)."],
    ["distance_source", "varchar(20)", "Y", "", "FIRST_READING, GPS, DEVICE_ODOMETER, JITTER_FILTERED, JUMP_FILTERED or OUT_OF_ORDER (V5)."],
    ["ignition", "boolean", "Y", "", "Device ignition input, when wired (V5)."],
    ["odometer_m", "bigint", "Y", "", "Device odometer, when reported (V5)."],
  ]);
  constraints(d, "gps_position constraints", [
    ["gps_position_pkey", "PK", "(id)"],
    ["gps_position_one_reading_per_device_per_moment", "UK", "(imei, recorded_at): Kafka redelivery stores a reading once (BR-005)."],
    ["gps_position_latitude_range", "CK", "latitude BETWEEN -90 AND 90"],
    ["gps_position_longitude_range", "CK", "longitude BETWEEN -180 AND 180"],
    ["gps_position_movement_status_values", "CK", "movement_status IN ('MOVING','STOPPED')"],
  ]);
  indexes(d, "gps_position indexes", [
    ["gps_position_vehicle_time", "(vehicle_registration_no, recorded_at DESC)", "Latest position and history of a vehicle"],
    ["gps_position_stored_at", "(stored_at DESC)", "Recently stored positions"],
    ["gps_position_recorded_at", "(recorded_at)", "Report job: one day of positions for all vehicles (V8)"],
    ["(unique key index)", "(imei, recorded_at)", "Duplicate check; previous reading of a device after restart"],
  ]);

  tableHeader(d, "vehicle_motion_state", [
    ["Purpose", "Working memory of the trip detector: one row per device."],
    ["Created in", "V3"],
    ["Written by / read by", "gps-trip-service (TripDetectionService, VehicleMotionStateStore) / vehicle inspector"],
    ["Write pattern", "Upsert in the same transaction as the gps_trip and gps_stop rows it produces; guarded by last_recorded_at < the new reading's time."],
  ]);
  columns(d, "vehicle_motion_state columns", [
    ["imei", "varchar(50)", "N", "", "Device (PK)."],
    ["assignment_id", "uuid", "Y", "", "device_assignment of the newest reading; a change closes the open trip. Null: not in a vehicle."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant of the newest reading."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle of the newest reading."],
    ["motion_streak", "boolean", "N", "", "True between “started moving” and “stopped” (moving state)."],
    ["event_recorded_at", "timestamptz", "N", "", "When the vehicle last started moving or stopped."],
    ["event_latitude", "double precision", "N", "", "Where that happened."],
    ["event_longitude", "double precision", "N", "", "Where that happened."],
    ["last_recorded_at", "timestamptz", "N", "", "Newest reading applied; older or equal readings are skipped."],
    ["reading_window", "jsonb", "N", "", "In-order readings of the last min-duration (a few dozen small objects), oldest first."],
    ["open_trip", "jsonb", "Y", "", "The trip in progress (same values as its OPEN row in gps_trip), or null."],
    ["open_stop", "jsonb", "Y", "", "The stop in progress (same values as its OPEN row in gps_stop), or null."],
    ["updated_at", "timestamptz", "N", "now()", "Last update."],
  ]);
  constraints(d, "vehicle_motion_state constraints", [["vehicle_motion_state_pkey", "PK", "(imei)"]]);

  tableHeader(d, "gps_trip", [
    ["Purpose", "A trip: from the moment a vehicle starts moving until it stops."],
    ["Created in", "V4"],
    ["Written by / read by", "gps-trip-service / GET /api/trips, report summary job, vehicle-day sheet"],
    ["Write pattern", "Upsert on (imei, start_recorded_at): an OPEN trip is extended with every reading and finally CLOSED."],
  ]);
  columns(d, "gps_trip columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["imei", "varchar(50)", "N", "", "Device."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant from the assignment valid at the trip's readings."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle from the same assignment."],
    ["start_recorded_at", "timestamptz", "N", "", "Start, back-dated to the first movement."],
    ["end_recorded_at", "timestamptz", "N", "", "End (moves with every reading while OPEN)."],
    ["start_latitude / start_longitude", "double precision", "N", "", "Start position."],
    ["end_latitude / end_longitude", "double precision", "N", "", "End position."],
    ["distance_m", "double precision", "N", "", "Sum of distance_from_previous_m of in-order readings; never end − start odometer."],
    ["duration_s", "bigint", "N", "", "end − start in seconds."],
    ["max_speed_kmph", "integer", "N", "", "Highest reported speed."],
    ["avg_speed_kmph", "double precision", "N", "", "distance ÷ duration."],
    ["reading_count", "integer", "N", "", "In-order readings in the trip."],
    ["late_reading_count", "integer", "N", "0", "Late readings that fell into the trip (not in distance or duration)."],
    ["status", "varchar(10)", "N", "", "OPEN or CLOSED."],
    ["updated_at", "timestamptz", "N", "now()", "Last update."],
  ]);
  constraints(d, "gps_trip constraints", [
    ["gps_trip_pkey", "PK", "(id)"],
    ["gps_trip_one_trip_per_device_per_start", "UK", "(imei, start_recorded_at): a replayed reading updates the same trip."],
    ["gps_trip_status_values", "CK", "status IN ('OPEN','CLOSED')"],
    ["gps_trip_ends_after_it_starts", "CK", "end_recorded_at >= start_recorded_at"],
    ["gps_trip_distance_not_negative", "CK", "distance_m >= 0"],
  ]);
  indexes(d, "gps_trip indexes", [["gps_trip_vehicle_time", "(vehicle_registration_no, start_recorded_at DESC)", "Trips of a vehicle in a period"]]);

  tableHeader(d, "gps_stop", [
    ["Purpose", "A stop: the vehicle stays within the minimum distance for at least the minimum duration."],
    ["Created in", "V4"],
    ["Written by / read by", "gps-trip-service / GET /api/stops, report summary job, vehicle-day sheet"],
    ["Write pattern", "Upsert on (imei, start_recorded_at)."],
  ]);
  columns(d, "gps_stop columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["imei", "varchar(50)", "N", "", "Device."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant at the stop."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle at the stop."],
    ["start_recorded_at", "timestamptz", "N", "", "Start of the stop."],
    ["end_recorded_at", "timestamptz", "N", "", "End (moves while OPEN)."],
    ["latitude / longitude", "double precision", "N", "", "Stop position."],
    ["duration_s", "bigint", "N", "", "end − start in seconds."],
    ["reading_count", "integer", "N", "", "Readings during the stop."],
    ["status", "varchar(10)", "N", "", "OPEN or CLOSED."],
    ["updated_at", "timestamptz", "N", "now()", "Last update."],
  ]);
  constraints(d, "gps_stop constraints", [
    ["gps_stop_pkey", "PK", "(id)"],
    ["gps_stop_one_stop_per_device_per_start", "UK", "(imei, start_recorded_at)"],
    ["gps_stop_status_values", "CK", "status IN ('OPEN','CLOSED')"],
    ["gps_stop_ends_after_it_starts", "CK", "end_recorded_at >= start_recorded_at"],
  ]);
  indexes(d, "gps_stop indexes", [["gps_stop_vehicle_time", "(vehicle_registration_no, start_recorded_at DESC)", "Stops of a vehicle in a period"]]);

  tableHeader(d, "gps_trip_late_reading", [
    ["Purpose", "Every late reading the trip detector skipped, once; also the work list of the future trip repair job."],
    ["Created in", "V4"],
    ["Written by / read by", "gps-trip-service / (future) repair job"],
  ]);
  columns(d, "gps_trip_late_reading columns", [
    ["imei", "varchar(50)", "N", "", "Device."],
    ["recorded_at", "timestamptz", "N", "", "Time of the late reading."],
    ["trip_start_recorded_at", "timestamptz", "Y", "", "Start of the trip it fell into (logical reference to gps_trip); null if it fell into a stop or no trip."],
    ["noted_at", "timestamptz", "N", "now()", "When it was noted."],
  ]);
  constraints(d, "gps_trip_late_reading constraints", [["gps_trip_late_reading_pkey", "PK", "(imei, recorded_at): a Kafka redelivery is not counted again."]]);

  tableHeader(d, "vehicle_event", [
    ["Purpose", "Events raised by the stream consumers (over-speed, sensor mismatch, stoppage, parking, idling, ignition, harsh driving)."],
    ["Created in", "V5"],
    ["Written by / read by", "Speed-violation, vehicle-state, stoppage and ignition consumers / GET /api/events, report summary job, console Alerts"],
    ["Write pattern", "Upsert on event_key: written OPEN when an episode starts and again CLOSED when it ends."],
  ]);
  columns(d, "vehicle_event columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["event_key", "varchar(120)", "N", "", "Natural key of the episode (type, device, start)."],
    ["imei", "varchar(50)", "N", "", "Device."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle."],
    ["event_type", "varchar(40)", "N", "", "OVERSPEED, SPEED_SENSOR_MISMATCH, STOPPAGE, PARKED, IDLING, IGNITION_ON, IGNITION_OFF, HARSH_ACCELERATION, HARSH_BRAKING."],
    ["severity", "varchar(10)", "N", "", "INFO, LOW, MEDIUM, HIGH, CRITICAL (not checked by the database)."],
    ["status", "varchar(10)", "N", "", "OPEN or CLOSED."],
    ["started_at", "timestamptz", "N", "", "Episode start."],
    ["ended_at", "timestamptz", "Y", "", "Episode end; null while OPEN."],
    ["latitude / longitude", "double precision", "N", "", "Where the episode started."],
    ["detected_by", "varchar(40)", "N", "", "Consumer that raised it."],
    ["details", "jsonb", "N", "'{}'", "Type-specific facts (limit, peak speed, verdict, agents' findings …)."],
    ["created_at", "timestamptz", "N", "now()", "First write."],
    ["updated_at", "timestamptz", "N", "now()", "Last write."],
  ]);
  constraints(d, "vehicle_event constraints", [
    ["vehicle_event_pkey", "PK", "(id)"],
    ["vehicle_event_event_key_key", "UK", "(event_key): redelivery and the CLOSED update hit the same row."],
    ["vehicle_event_status_values", "CK", "status IN ('OPEN','CLOSED')"],
  ]);
  indexes(d, "vehicle_event indexes", [
    ["vehicle_event_type_time", "(event_type, started_at DESC)", "Events of a type"],
    ["vehicle_event_imei_time", "(imei, started_at DESC)", "Events of a vehicle"],
    ["vehicle_event_recent", "(updated_at DESC)", "Newest first (GET /api/events)"],
  ]);

  tableHeader(d, "dead_letter", [
    ["Purpose", "Permanent archive of every record a consumer or Kafka Streams application could not handle."],
    ["Created in", "V6"],
    ["Written by / read by", "gps-dead-letter-archive / Storage → Dead letters, GET /api/dead-letters"],
    ["Write pattern", "Insert once per dead letter; afterwards only status, replay_count, last_replayed_at and resolution_note change. Never deleted (except demo reset)."],
  ]);
  columns(d, "dead_letter columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["source", "varchar(20)", "N", "", "Listener or Kafka Streams application that parked it."],
    ["original_topic", "varchar(250)", "Y", "", "Topic the record came from."],
    ["original_partition", "integer", "Y", "", "Its partition."],
    ["original_offset", "bigint", "Y", "", "Its offset."],
    ["consumer_group", "varchar(250)", "Y", "", "Group that failed."],
    ["record_key", "varchar(250)", "Y", "", "Record key (IMEI)."],
    ["record_value", "text", "Y", "", "Record value exactly as received."],
    ["exception_class", "varchar(500)", "Y", "", "Error type."],
    ["exception_message", "text", "Y", "", "Error message."],
    ["stacktrace", "text", "Y", "", "Stack trace."],
    ["parked_at", "timestamptz", "N", "", "When it was parked."],
    ["dead_letter_partition", "integer", "N", "", "Partition in gps.dead-letters."],
    ["dead_letter_offset", "bigint", "N", "", "Offset in gps.dead-letters."],
    ["status", "varchar(10)", "N", "'PARKED'", "PARKED, REPLAYED or DISCARDED."],
    ["replay_count", "integer", "N", "0", "Times replayed."],
    ["last_replayed_at", "timestamptz", "Y", "", "Last replay."],
    ["resolution_note", "text", "Y", "", "Why it was discarded (required for DISCARDED)."],
    ["archived_at", "timestamptz", "N", "now()", "When the row was written."],
  ]);
  constraints(d, "dead_letter constraints", [
    ["dead_letter_pkey", "PK", "(id)"],
    ["dead_letter_status_values", "CK", "status IN ('PARKED','REPLAYED','DISCARDED')"],
    ["dead_letter_archived_once", "UK", "(dead_letter_partition, dead_letter_offset, parked_at): archived once, even if the topic is recreated."],
  ]);
  indexes(d, "dead_letter indexes", [
    ["dead_letter_status_time", "(status, parked_at DESC)", "Parked letters, newest first"],
    ["dead_letter_topic_time", "(original_topic, parked_at DESC)", "Replay the oldest parked of a topic"],
    ["dead_letter_key", "(record_key)", "Dead letters of a device"],
  ]);
  d.figure(FIG("dead-letter.png"), "Status life cycle of dead_letter", 5.8);

  tableHeader(d, "rejected_frame", [
    ["Purpose", "Permanent archive of frames the normaliser refused, with the reason and the payload exactly as received."],
    ["Created in", "V6"],
    ["Written by / read by", "gps-rejected-frame-logger / Storage → Rejected frames, GET /api/rejected-frames"],
  ]);
  columns(d, "rejected_frame columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["imei", "varchar(50)", "Y", "", "IMEI if it could be read."],
    ["rejection_reason", "text", "N", "", "Reason (for example “Latitude 95.0 is outside -90..90”)."],
    ["original_payload", "text", "N", "", "Frame as received."],
    ["rejected_at", "timestamptz", "N", "", "Time of rejection."],
    ["payload_hash", "char(32)", "N", "", "Hash of the payload, for de-duplication."],
    ["archived_at", "timestamptz", "N", "now()", "When the row was written."],
  ]);
  constraints(d, "rejected_frame constraints", [
    ["rejected_frame_pkey", "PK", "(id)"],
    ["rejected_frame_archived_once", "UK", "(rejected_at, payload_hash): a redelivered rejection is stored once."],
  ]);
  indexes(d, "rejected_frame indexes", [
    ["rejected_frame_time", "(rejected_at DESC)", "Newest rejections"],
    ["rejected_frame_imei_time", "(imei, rejected_at DESC)", "Rejections of a device"],
  ]);

  tableHeader(d, "connection_setting", [
    ["Purpose", "External addresses changed from the console while the service runs; a missing row means the application.yml default."],
    ["Created in", "V7"],
    ["Why in PostgreSQL", "The Redis address cannot be kept in the Redis it points to."],
  ]);
  columns(d, "connection_setting columns", [
    ["name", "varchar(40)", "N", "", "redis or valhalla."],
    ["url", "varchar(500)", "N", "", "redis://host:port/database or http://host:port."],
    ["updated_at", "timestamptz", "N", "now()", "Last change."],
  ]);
  constraints(d, "connection_setting constraints", [["connection_setting_pkey", "PK", "(name)"]]);

  tableHeader(d, "vehicle_day_summary", [
    ["Purpose", "One row per vehicle per day (and generation): the source of all day and month reports."],
    ["Created in", "V8"],
    ["Written by / read by", "DaySummaryJob (today and yesterday every 5 minutes; backfill at start; on demand) / GET /api/reports/*"],
    ["Generations", "0 = live row, recomputed from the raw tables. Clear database freezes the live rows into a new generation > 0 before emptying the raw tables; a report adds all generations of a day."],
    ["Expected size", "About 150 rows a day (4,650 a month) for 150 vehicles."],
  ]);
  columns(d, "vehicle_day_summary columns", [
    ["day", "date", "N", "", "Report day (Asia/Kolkata)."],
    ["imei", "varchar(50)", "N", "", "Device."],
    ["generation", "integer", "N", "0", "0 live; > 0 frozen by Clear database."],
    ["tenant_code", "varchar(40)", "Y", "", "Tenant."],
    ["vehicle_registration_no", "varchar(50)", "Y", "", "Vehicle."],
    ["readings", "integer", "N", "", "Positions in the day."],
    ["first_seen_at / last_seen_at", "timestamptz", "Y", "", "First and last reading of the day."],
    ["distance_m", "double precision", "N", "", "Distance driven."],
    ["moving_s / stopped_s", "bigint", "N", "", "Driving and standing time (long silences excluded)."],
    ["max_speed_kmph", "integer", "N", "", "Highest speed."],
    ["fast_distance_m", "double precision", "N", "", "Distance driven above economical_speed_kmph."],
    ["economical_speed_kmph", "integer", "N", "", "Economical speed in force when computed."],
    ["idle_s", "bigint", "N", "", "Time in IDLING episodes."],
    ["stops / stop_s", "integer / bigint", "N", "", "Number and total time of stops."],
    ["engine_on_stops", "integer", "N", "", "Stops with the engine running."],
    ["trips", "integer", "N", "", "Trips started in the day."],
    ["overspeed_events / harsh_events", "integer", "N", "", "Event counts."],
    ["computed_at", "timestamptz", "N", "now()", "When computed."],
  ]);
  constraints(d, "vehicle_day_summary constraints", [["vehicle_day_summary_pkey", "PK", "(day, imei, generation)"]]);
  indexes(d, "vehicle_day_summary indexes", [["vehicle_day_summary_vehicle", "(imei, day)", "Month of one vehicle"]]);

  tableHeader(d, "report_setting", [
    ["Purpose", "Cost assumptions edited on Reports → Cost assumptions; a missing row means the built-in default. Not touched by Clear database."],
    ["Created in", "V8"],
  ]);
  columns(d, "report_setting columns", [
    ["name", "varchar(60)", "N", "", "Setting name (Table below)."],
    ["value", "varchar(100)", "N", "", "Value as text."],
    ["updated_at", "timestamptz", "N", "now()", "Last change."],
  ]);
  constraints(d, "report_setting constraints", [["report_setting_pkey", "PK", "(name)"]]);
  d.table("report_setting names and defaults", [
    { header: "name", width: 3000 }, { header: "Default", width: 1600 }, { header: "Validation", width: 4426 },
  ], [
    ["fuel-type", "diesel", "diesel or petrol"],
    ["price-per-litre", "92.0", "≥ 0 (rupees)"],
    ["mileage-km-per-litre", "4.0", "> 0"],
    ["idle-litres-per-hour", "2.5", "≥ 0"],
    ["economical-speed-kmph", "60", "10 to 150; a change rebuilds stored days"],
    ["fast-driving-penalty-percent", "20.0", "≥ 0"],
    ["co2-kg-per-litre", "2.68 (petrol 2.31)", "≥ 0"],
  ]);

  // ---------------------------------------------------------------------------------------------- 4b (V9 to V11)
  d.h2("Tables added by V9 to V11");
  d.p("V9 adds transport management (three sign-in roles, places and shipments), V10 makes place the geofence master and "
    + "adds the visits the geofence module finds, V11 adds the road route plans that the route-deviation and waypoints "
    + "modules follow. tenant gains two columns in V9: kind (GPS_PROVIDER, TRANSPORTER, CONSIGNER; default TRANSPORTER) and "
    + "external_code (the company's number in the TMS file; unique per kind when present).");

  tableHeader(d, "app_user", [
    ["Purpose", "Who may sign in, and as what: DEVELOPER (the GPS provider, sees everything), TRANSPORTER or CONSIGNER (one company each, sees only its data)."],
    ["Created in", "V9"],
    ["Written by / read by", "PortalAdminController (users), demo accounts at start when a role has none / AuthController (sign-in)"],
  ]);
  columns(d, "app_user columns", [
    ["id", "uuid", "N", "gen_random_uuid()", "Surrogate key."],
    ["role", "varchar(20)", "N", "", "DEVELOPER, TRANSPORTER or CONSIGNER."],
    ["username", "varchar(80)", "N", "", "User id typed at sign-in (unique per role, case-insensitive)."],
    ["display_name", "varchar(120)", "N", "", "Name shown in the console."],
    ["tenant_id", "uuid", "Y", "", "FK tenant: the user's company (required except for DEVELOPER)."],
    ["password_hash", "varchar(200)", "N", "", "PBKDF2-HMAC-SHA256 with salt; never the password."],
    ["active", "boolean", "N", "true", "False = may not sign in."],
    ["created_at / last_login_at", "timestamptz", "N / Y", "now() / –", "Created; last successful sign-in."],
  ]);
  constraints(d, "app_user constraints", [
    ["app_user_pkey", "PK", "(id)"],
    ["app_user_role_values", "CK", "role IN ('DEVELOPER','TRANSPORTER','CONSIGNER')"],
    ["app_user_company_for_business_roles", "CK", "role = 'DEVELOPER' OR tenant_id IS NOT NULL"],
    ["app_user_role_username", "UK (index)", "(role, lower(username))"],
  ]);

  tableHeader(d, "auth_key", [
    ["Purpose", "The single secret that signs sign-in tokens (HMAC). Made by the migration, so it survives restarts and never sits in a configuration file; replacing it signs everyone out."],
    ["Created in", "V9"],
  ]);
  columns(d, "auth_key columns", [
    ["id", "smallint", "N", "", "Always 1."],
    ["secret", "varchar(128)", "N", "random", "Signing key."],
    ["created_at", "timestamptz", "N", "now()", "When made."],
  ]);
  constraints(d, "auth_key constraints", [["auth_key_pkey", "PK", "(id)"], ["auth_key_single_row", "CK", "id = 1"]]);

  tableHeader(d, "place", [
    ["Purpose", "The geofence master: plants, customers, yards, checkpoints and zones. A shipment file names its origin and destination; the name finds the place by code. Every active place is a geofence of the geofence module and can be a stop of a route plan."],
    ["Created in", "V9 (circle, ORIGIN / DESTINATION); V10 adds shapes, roles, dwell and speed limits and the 87 smart_truck fences"],
    ["Written by / read by", "Live geofences page (PUT /api/geofences/{code}), import from smart_truck / GeofenceDirectory (index rebuilt within 30 s of a change), RoutePlanService"],
  ]);
  columns(d, "place columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["code / name", "varchar(255)", "N", "", "Normalised name the files use (\"ADITYAPUR\") / display name."],
    ["role", "varchar(12)", "N", "", "ORIGIN, DESTINATION, YARD, CHECKPOINT, ZONE."],
    ["shape", "varchar(10)", "N", "'CIRCLE'", "CIRCLE or POLYGON (V10)."],
    ["polygon", "jsonb", "Y", "", "[[latitude, longitude], …] around the border, at least 3 corners (V10)."],
    ["latitude / longitude", "double precision", "N", "", "Centre (for a polygon: the average of its corners)."],
    ["radius_m", "integer", "N", "", "Circle radius, 50 to 50,000 m; for a polygon the covering radius."],
    ["dwell_alert_min", "integer", "Y", "", "Inside longer raises GEOFENCE_DWELL; null = the role's default, 0 = never (V10)."],
    ["speed_limit_kmph", "integer", "Y", "", "Limit inside the fence (5 to 150); the strictest applies to over-speed (V10)."],
    ["source / source_detail", "varchar(20) / varchar(30)", "N / Y", "'MANUAL'", "MANUAL or SMART_TRUCK; how smart_truck derived it (plant, anchor, gazetteer)."],
    ["external_ref", "varchar(60)", "Y", "", "smart_truck:{id} for copied fences (V10)."],
    ["trips_seen / spread_m", "integer", "Y", "", "smart_truck evidence: trips seen at the fence, spread of their pings (V10)."],
    ["active", "boolean", "N", "true", "Inactive places are not geofences."],
    ["created_at / updated_at", "timestamptz", "N", "now()", "Created; last change (the directory's fingerprint)."],
  ]);
  constraints(d, "place constraints", [
    ["place_pkey", "PK", "(id)"],
    ["place_once_per_role", "UK", "(code, role)"],
    ["place_role_values", "CK", "role IN ('ORIGIN','DESTINATION','YARD','CHECKPOINT','ZONE')"],
    ["place_shape_values", "CK", "shape IN ('CIRCLE','POLYGON')"],
    ["place_radius_sensible", "CK", "shape = 'POLYGON' OR radius_m BETWEEN 50 AND 50000"],
    ["place_polygon_present", "CK", "shape = 'CIRCLE' OR polygon is a JSON array of at least 3 corners"],
    ["place_position_valid", "CK", "latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180"],
    ["place_dwell_sensible / place_speed_sensible", "CK", "dwell_alert_min ≥ 0; speed_limit_kmph BETWEEN 5 AND 150"],
  ]);

  tableHeader(d, "shipment_import", [
    ["Purpose", "One row per uploaded shipment file (Excel, CSV or the smart_truck tta_trips layout): what it changed and the problems found."],
    ["Created in", "V9"],
    ["Written by / read by", "ShipmentImportService / Transporter → Shipments → Upload, import history"],
  ]);
  columns(d, "shipment_import columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["file_name / file_sha256 / format", "varchar", "N", "", "The file, its hash (an identical file is recognised), XLSX, CSV or SMART_TRUCK."],
    ["uploaded_by / uploaded_role / tenant_id", "varchar / varchar / uuid", "N / N / Y", "", "Who uploaded it, as what, for which company."],
    ["rows_total, rows_created, rows_updated, rows_unchanged, rows_failed", "integer", "N", "", "What the upload did."],
    ["problems / column_mapping", "jsonb", "N", "'[]' / '{}'", "Row problems; the column mapping used."],
    ["uploaded_at", "timestamptz", "N", "now()", "When."],
  ]);

  tableHeader(d, "shipment", [
    ["Purpose", "One trip of one truck carrying one consigner's goods, as the transporter's file describes it (the smart_truck tta_trips layout), plus what the GPS says about it."],
    ["Created in", "V9"],
    ["Identity", "(transporter_tenant_id, trip_no): the same row uploaded again updates its shipment, so a file uploaded twice cannot duplicate."],
    ["Written by / read by", "ShipmentImportService; GPS columns by module shipment-tracking (always the earliest reading that proves them) / portals, RoutePlanService (a plan per open shipment)"],
  ]);
  columns(d, "shipment columns", [
    ["id / trip_no / source", "bigint / varchar(60) / varchar(12)", "N", "", "Key; trip number in the file; where it came from."],
    ["transporter_tenant_id / consigner_tenant_id", "uuid", "N / Y", "", "FK tenant: the truck company and the goods owner."],
    ["transporter_code, consigner_code, consigner_unit, consignee_name, consignee_address", "varchar", "Y", "", "As written in the file."],
    ["vehicle_registration_no / device_imei", "varchar(50)", "N / Y", "", "The truck; the device named in the file (the mapped device wins)."],
    ["origin_name / destination_name / load_plant", "varchar(255)", "Y", "", "Names from the file."],
    ["origin_place_id / destination_place_id", "bigint", "Y", "", "FK place: the geofences found for those names."],
    ["booked_at / dispatched_at / eta_at", "timestamptz", "Y / N / Y", "", "Booking, gate-out, the TMS's ETA."],
    ["reported_ata_at / reported_end_at / source_status / close_reason", "timestamptz / varchar", "Y", "", "What the TMS reported later."],
    ["invoice_no, shipment_ref, gate_entry_no, card_id, route_id, material, quantity, driver_name, driver_mobile, vehicle_type, trip_class, trip_type", "varchar / numeric", "Y", "", "Further columns of the file."],
    ["left_origin_at / outside_destination_at / arrived_at", "timestamptz", "Y", "", "GPS: cleared the origin fence; last outside the destination; entered it."],
    ["last_reading_at, last_latitude, last_longitude, last_speed_kmph", "timestamptz / double / integer", "Y", "", "GPS: the truck's latest reading during the trip."],
    ["closed_at / closed_reason", "timestamptz / varchar(60)", "Y", "", "Closed (arrived, a later trip of the truck, by hand)."],
    ["row_hash / import_id", "varchar(64) / bigint", "N / Y", "", "Hash of the file row (unchanged rows are skipped); FK shipment_import."],
    ["created_at / updated_at", "timestamptz", "N", "now()", "Created; last change."],
  ]);
  constraints(d, "shipment constraints", [
    ["shipment_pkey", "PK", "(id)"],
    ["shipment_trip_once_per_transporter", "UK", "(transporter_tenant_id, trip_no)"],
  ]);
  indexes(d, "shipment indexes", [
    ["shipment_vehicle_dispatch / shipment_device_dispatch", "(vehicle_registration_no | device_imei, dispatched_at DESC)", "Shipments of a reading's truck (module shipment-tracking)"],
    ["shipment_consigner / shipment_transporter", "(… _tenant_id, dispatched_at DESC)", "Portal lists"],
  ]);

  tableHeader(d, "geofence_visit", [
    ["Purpose", "Every visit to and drive-through of a geofence, as the geofence module finds it live: the source of the live board, the entry & exit log and the day-wise geofence report."],
    ["Created in", "V10"],
    ["Identity", "(imei, place_id, kind, entered_at): a visit found twice (a redelivery, a replay) is one row."],
    ["Stamps", "entered_at = first reading inside, exited_at = last reading inside; the gaps to the readings outside are their uncertainty."],
    ["No foreign key", "place_id is logical and code, name and role are copied, so a fence deleted later keeps its history."],
  ]);
  columns(d, "geofence_visit columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key."],
    ["imei / vehicle_registration_no / tenant_code", "varchar", "N / Y / Y", "", "The device and its truck and company at the time."],
    ["place_id, place_code, place_name, place_role", "bigint / varchar", "N", "", "The fence as it was."],
    ["kind", "varchar(6)", "N", "", "VISIT (inside 15 min: believed) or PASS (through, or over it between two readings)."],
    ["entered_at / exited_at", "timestamptz", "N / Y", "", "Null exited_at = inside now."],
    ["entry_gap_s / exit_gap_s", "bigint", "Y", "", "Seconds to the reading outside: uncertainty of each stamp."],
    ["entry_latitude, entry_longitude, exit_latitude, exit_longitude", "double precision", "N / Y", "", "Where."],
    ["dwell_alert_at / dwell_limit_min", "timestamptz / integer", "Y", "", "When GEOFENCE_DWELL was raised, against which limit."],
    ["inferred / exit_reason", "boolean / varchar(20)", "N / Y", "false", "A pass worked out between two readings; HELD, ESCAPED, FENCE_REMOVED, REPLAY_CUT."],
    ["created_at / updated_at", "timestamptz", "N", "now()", "Created; last change."],
  ]);
  constraints(d, "geofence_visit constraints", [
    ["geofence_visit_once", "UK", "(imei, place_id, kind, entered_at)"],
    ["geofence_visit_kind_values", "CK", "kind IN ('VISIT','PASS')"],
    ["geofence_visit_period_valid", "CK", "exited_at IS NULL OR exited_at >= entered_at"],
  ]);
  indexes(d, "geofence_visit indexes", [
    ["geofence_visit_open", "(place_id) WHERE exited_at IS NULL", "Who is inside which fence now"],
    ["geofence_visit_time / _place_time / _vehicle_time / _imei_time", "(… , entered_at DESC)", "Day report; logs per fence, truck or device"],
  ]);

  tableHeader(d, "route_plan", [
    ["Purpose", "One trip of one truck along a planned road route: the line from the routing engine (Valhalla on the HP; GraphHopper on this laptop as fallback), its per-instruction timings, and the live numbers the route-deviation module writes when they change."],
    ["Created in", "V11"],
    ["Made by", "RoutePlanService: every open shipment with an origin and a destination geofence (within 30 s), or by hand (Plan a route). A new plan closes the truck's other active plan."],
    ["Versions", "version 1 is the dispatch plan; a ROUTE_ALTERNATE (another road about as good) replaces the line from where the truck was: version + 1, offset_m = distance already done; first_shape keeps the original."],
    ["Read by", "RoutePlanDirectory (active plans in memory, reloaded within 30 s of a change), RouteLiveService (strips, trip map), portals"],
  ]);
  columns(d, "route_plan columns", [
    ["id", "bigint identity", "N", "generated", "Surrogate key; the episode scope of the plan's events."],
    ["shipment_id", "bigint", "Y", "", "FK shipment (ON DELETE CASCADE); null for a plan made by hand. One plan per shipment."],
    ["imei / vehicle_registration_no / tenant_code", "varchar", "Y / N / Y", "", "The truck followed."],
    ["source / status", "varchar(12)", "N", "– / 'ACTIVE'", "SHIPMENT or MANUAL; ACTIVE, ARRIVED, CANCELLED, CLOSED."],
    ["origin_name, origin_place_id, origin_lat, origin_lon", "varchar / bigint / double", "Y / Y / N / N", "", "Start (FK place when a geofence)."],
    ["destination_name, destination_place_id, destination_lat, destination_lon, destination_radius_m", "varchar / bigint / double", "Y / Y / N / N / N", "", "Destination; arrival = a reading inside its geofence, or within the radius of a point."],
    ["engine / costing", "varchar(20)", "N", "", "valhalla or graphhopper; truck."],
    ["version / reason", "integer / varchar(20)", "N", "1 / –", "DISPATCH, MANUAL or ALTERNATE."],
    ["shape / timings", "text / jsonb", "N", "– / '[]'", "Current line (encoded polyline, precision 6); [[from vertex, to vertex, seconds], …] of the engine's instructions."],
    ["length_m / planned_s / offset_m", "double precision", "N", "– / – / 0", "Current line's length and planned driving time; distance done before this version."],
    ["first_shape / first_length_m / first_planned_s", "text / double", "N", "", "The dispatch plan, kept after re-plans."],
    ["initial_pace", "double precision", "N", "", "Actual / planned driving time to start with: the lane's median of 3+ arrived trips, else 1.0."],
    ["planned_start_at", "timestamptz", "N", "", "Dispatch time (shipment) or when planned."],
    ["route_status", "varchar(12)", "N", "'WAITING'", "WAITING, ON_ROUTE, SUSPECT, OFF_ROUTE, ARRIVED."],
    ["departed_at / promised_eta_at / eta_at / arrived_at", "timestamptz", "Y", "", "Left the origin; ETA at departure; latest shown ETA; arrival."],
    ["pace, chainage_m, cross_track_m", "double precision", "Y", "", "Live pace; progress along the line; distance from it."],
    ["last_reading_at, last_lat, last_lon", "timestamptz / double", "Y", "", "Latest reading applied."],
    ["deviations, deviation_km, deviation_minutes, wrong_ways, alternates", "integer / double", "N", "0", "Trip totals."],
    ["created_by / created_at / updated_at", "varchar / timestamptz", "Y / N / N", "– / now()", "Who planned it; created; last change."],
  ]);
  constraints(d, "route_plan constraints", [
    ["route_plan_pkey", "PK", "(id)"],
    ["route_plan_status_values / _source_values / _route_status_values", "CK", "The value sets of Section 6."],
    ["route_plan_positive", "CK", "length_m > 0 AND planned_s > 0 AND destination_radius_m > 0"],
    ["route_plan_one_per_shipment", "UK (partial)", "(shipment_id) WHERE shipment_id IS NOT NULL"],
  ]);
  indexes(d, "route_plan indexes", [
    ["route_plan_active_vehicle / route_plan_active_imei", "(vehicle_registration_no | imei) WHERE status = 'ACTIVE'", "A reading's plan (directory reload)"],
    ["route_plan_recent", "(planned_start_at DESC)", "Live strips of the last hours"],
    ["route_plan_lane", "(origin_place_id, destination_place_id) WHERE status = 'ARRIVED'", "The lane's pace history"],
  ]);

  tableHeader(d, "route_stop", [
    ["Purpose", "The stops of a route plan, in the order the line meets them: waypoints planned by hand (MANUAL) and the geofences the planned line crosses (FENCE)."],
    ["Created in", "V11"],
    ["Written by", "RoutePlanService (made with the plan; after a re-plan, FENCE stops not reached are found again) and module waypoints (status)"],
  ]);
  columns(d, "route_stop columns", [
    ["id / plan_id / seq", "bigint / bigint / integer", "N", "", "Key; FK route_plan (ON DELETE CASCADE); order within the plan."],
    ["kind / place_id / name", "varchar(10) / bigint / varchar(255)", "N / Y / N", "", "MANUAL or FENCE; FK place when a geofence."],
    ["latitude, longitude, radius_m", "double precision", "N", "", "The stop's area when it is not a geofence."],
    ["chainage_m", "double precision", "N", "", "Distance along the current line; -1 when the line does not pass it."],
    ["stay_min", "integer", "N", "0", "Planned stay (counted in the ETA)."],
    ["status", "varchar(10)", "N", "'PENDING'", "PENDING, INSIDE, ARRIVED, DEPARTED, PASSED, MISSED."],
    ["arrived_at / departed_at / missed_at / minutes_inside / out_of_order", "timestamptz / integer / boolean", "Y (out_of_order N)", "out_of_order false", "What the truck did there."],
    ["updated_at", "timestamptz", "N", "now()", "Last change."],
  ]);
  constraints(d, "route_stop constraints", [
    ["route_stop_once", "UK", "(plan_id, seq)"],
    ["route_stop_kind_values / route_stop_status_values", "CK", "The value sets of Section 6."],
  ]);

  // ---------------------------------------------------------------------------------------------- 5
  d.h1("Relationships and Referential Integrity");
  d.table("Relationships", [
    { header: "Parent", width: 1700 }, { header: "Child", width: 1900 }, { header: "Cardinality", width: 1200 }, { header: "Enforced by", width: 1500 }, { header: "Rule", width: 2726 },
  ], [
    ["tenant", "vehicle", "0..1 : N", "FK", "A vehicle may have no owner (market vehicle)."],
    ["device", "device_assignment", "1 : N", "FK + EX", "Periods of one device never overlap."],
    ["vehicle", "device_assignment", "1 : N", "FK", "A vehicle may carry several devices (GPS, fuel …)."],
    ["device (imei)", "gps_position, vehicle_motion_state, gps_trip, gps_stop, gps_trip_late_reading, vehicle_event, vehicle_day_summary, rejected_frame", "1 : N", "Logical", "Rows are kept even if the device is retired."],
    ["tenant (code)", "telemetry tables (tenant_code)", "1 : N", "Logical", "Code is never reused, so history stays unambiguous."],
    ["vehicle (registration_no)", "telemetry tables (vehicle_registration_no)", "1 : N", "Logical", "Copied at the reading's time (BR-004)."],
    ["device_assignment", "vehicle_motion_state.assignment_id", "1 : N", "Logical", "A change of assignment closes the open trip."],
    ["gps_trip", "gps_trip_late_reading.trip_start_recorded_at", "1 : N", "Logical (imei + start)", "Late readings of a trip."],
    ["tenant", "app_user, shipment (transporter, consigner)", "1 : N", "FK", "A business user belongs to one company."],
    ["place", "shipment (origin, destination), route_plan (origin, destination), route_stop", "1 : N", "FK", "A place in use is deactivated, not deleted."],
    ["place", "geofence_visit", "1 : N", "Logical (place_id + copied name)", "History outlives the fence."],
    ["shipment_import", "shipment", "1 : N", "FK", "The upload that last wrote the row."],
    ["shipment", "route_plan", "1 : 0..1", "FK + partial UK, cascade", "One plan per shipment."],
    ["route_plan", "route_stop", "1 : N", "FK, cascade", "Stops are deleted with their plan."],
  ], { size: 16 });
  d.p("Logical references are deliberate (SRS DR-002). The application guarantees them as follows: tenant and vehicle are resolved from device_assignment for every reading (or left null with a WARN log); codes and registration numbers are never reused; master-data rows are never deleted while telemetry refers to them.");

  // ---------------------------------------------------------------------------------------------- 6
  d.h1("Value Sets");
  d.table("Enumerated values", [{ header: "Column(s)", width: 3000 }, { header: "Allowed values", width: 4026 }, { header: "Checked by", width: 2000 }], [
    ["tenant.status", "ACTIVE, SUSPENDED, CLOSED", "CK"],
    ["device.kind", "GPS, FUEL, TEMPERATURE, DOOR, OBD", "CK"],
    ["gps_position.movement_status", "MOVING, STOPPED", "CK"],
    ["gps_position.distance_source", "FIRST_READING, GPS, DEVICE_ODOMETER, JITTER_FILTERED, JUMP_FILTERED, OUT_OF_ORDER", "Application (enum)"],
    ["gps_trip.status, gps_stop.status, vehicle_event.status", "OPEN, CLOSED", "CK"],
    ["vehicle_event.event_type", "OVERSPEED, SPEED_SENSOR_MISMATCH, STOPPAGE, PARKED, IDLING, IGNITION_ON, IGNITION_OFF, HARSH_ACCELERATION, HARSH_BRAKING, SHIPMENT_DEPARTED, SHIPMENT_ARRIVED, SHIPMENT_DELAYED, GEOFENCE_ENTER, GEOFENCE_EXIT, GEOFENCE_PASS, GEOFENCE_DWELL, ROUTE_DEVIATION, ROUTE_ALTERNATE, WRONG_DIRECTION, ETA_CHANGED, DESTINATION_REACHED, WAYPOINT_ARRIVED, WAYPOINT_DEPARTED, WAYPOINT_MISSED", "Application (enum)"],
    ["vehicle_event.severity", "INFO, LOW, MEDIUM, HIGH, CRITICAL", "Application (enum)"],
    ["dead_letter.status", "PARKED, REPLAYED, DISCARDED", "CK"],
    ["connection_setting.name", "redis, valhalla", "Application"],
    ["tenant.kind", "GPS_PROVIDER, TRANSPORTER, CONSIGNER", "CK"],
    ["app_user.role", "DEVELOPER, TRANSPORTER, CONSIGNER", "CK"],
    ["place.role / place.shape", "ORIGIN, DESTINATION, YARD, CHECKPOINT, ZONE / CIRCLE, POLYGON", "CK"],
    ["geofence_visit.kind", "VISIT, PASS", "CK"],
    ["route_plan.status", "ACTIVE, ARRIVED, CANCELLED, CLOSED", "CK"],
    ["route_plan.route_status", "WAITING, ON_ROUTE, SUSPECT, OFF_ROUTE, ARRIVED", "CK"],
    ["route_plan.source / reason", "SHIPMENT, MANUAL / DISPATCH, MANUAL, ALTERNATE", "CK / Application"],
    ["route_stop.kind / status", "MANUAL, FENCE / PENDING, INSIDE, ARRIVED, DEPARTED, PASSED, MISSED", "CK"],
  ]);
  d.note("event_type, severity and distance_source are checked only by the application. Adding CHECK constraints for them is recommended in a future migration (open issue DB-OI-02).");

  // ---------------------------------------------------------------------------------------------- 7
  d.h1("Index Strategy and Main Queries");
  d.p("Every frequent query is served by an index whose leading columns match its filter. The main access paths are:");
  d.table("Main queries and the index that serves them", [
    { header: "Query", width: 3900 }, { header: "Index", width: 2600 }, { header: "Frequency", width: 2526 },
  ], [
    ["Duplicate check before insert: exists (imei, recorded_at)", "unique (imei, recorded_at)", "Every position"],
    ["Previous reading after restart: newest row of an imei", "unique (imei, recorded_at)", "First reading per device after start"],
    ["Latest position of a vehicle", "gps_position_vehicle_time", "API, on demand"],
    ["One day of positions of all vehicles (summary job)", "gps_position_recorded_at", "Every 5 minutes, two days"],
    ["Trips / stops of a vehicle overlapping a period", "gps_trip_vehicle_time, gps_stop_vehicle_time", "API, reports"],
    ["Newest events, events of a type or a vehicle", "vehicle_event_recent, _type_time, _imei_time", "Console polling (2 s)"],
    ["Parked dead letters; oldest parked of a topic", "dead_letter_status_time, dead_letter_topic_time", "Console, replay"],
    ["Month of one vehicle", "vehicle_day_summary_vehicle", "Reports"],
    ["Shipments of a reading's truck", "shipment_vehicle_dispatch, shipment_device_dispatch", "Every reading (shipment-tracking)"],
    ["Who is inside a fence now; visits overlapping a day", "geofence_visit_open; geofence_visit_*_time", "Live board 5 s; day report"],
    ["Active plans (directory reload)", "route_plan_active_vehicle, route_plan_active_imei", "Within 30 s of a change"],
    ["Plans of the last hours; the lane's pace", "route_plan_recent; route_plan_lane", "Live strips 5 s; each new plan"],
  ]);
  d.code([
    "-- Assignment valid at a reading's time (served by the exclusion-constraint gist index)",
    "SELECT a.id, v.registration_no, t.code",
    "  FROM device_assignment a",
    "  JOIN device  d ON d.id = a.device_id",
    "  JOIN vehicle v ON v.id = a.vehicle_id",
    "  LEFT JOIN tenant t ON t.id = v.owner_tenant_id",
    " WHERE d.imei = :imei",
    "   AND a.valid_from <= :recordedAt AND (a.valid_to IS NULL OR a.valid_to > :recordedAt);",
  ]);

  // ---------------------------------------------------------------------------------------------- 8
  d.h1("Data Integrity, Idempotency and Concurrency");
  d.table("Idempotency key of each consumer-written table", [
    { header: "Table", width: 2400 }, { header: "Key", width: 2900 }, { header: "On redelivery", width: 3726 },
  ], [
    ["gps_position", "(imei, recorded_at)", "Existence check first; unique key as the last line of defence"],
    ["gps_trip, gps_stop", "(imei, start_recorded_at)", "Upsert updates the same row"],
    ["geofence_visit", "(imei, place_id, kind, entered_at)", "Upsert; a replay first cuts the visits after its start"],
    ["route_plan", "(shipment_id) partial unique", "A shipment is planned once; live columns are overwritten"],
    ["route_stop", "(plan_id, seq)", "Status update of the same row"],
    ["vehicle_motion_state", "imei + guard last_recorded_at < :recordedAt", "0 rows updated → transaction rolled back"],
    ["gps_trip_late_reading", "(imei, recorded_at)", "Insert … ON CONFLICT DO NOTHING; late_reading_count not raised twice"],
    ["vehicle_event", "event_key", "Upsert (OPEN then CLOSED)"],
    ["dead_letter", "(dead_letter_partition, dead_letter_offset, parked_at)", "Stored once"],
    ["rejected_frame", "(rejected_at, payload_hash)", "Stored once"],
    ["vehicle_day_summary", "(day, imei, generation)", "Generation 0 replaced on every rebuild"],
  ]);
  d.p("Guarded update used by the trip detector (one transaction per reading, together with the trip and stop upserts):");
  d.code([
    "UPDATE vehicle_motion_state",
    "   SET motion_streak = :motionStreak, event_recorded_at = :eventAt, event_latitude = :eventLat,",
    "       event_longitude = :eventLon, last_recorded_at = :recordedAt, reading_window = :window::jsonb,",
    "       open_trip = :openTrip::jsonb, open_stop = :openStop::jsonb, assignment_id = :assignmentId,",
    "       tenant_code = :tenant, vehicle_registration_no = :vehicle, updated_at = now()",
    " WHERE imei = :imei",
    "   AND last_recorded_at < :recordedAt;   -- 0 rows: a later or equal reading was already applied",
  ]);
  d.p("Concurrency: all readings of one device are in one Kafka partition and therefore handled by one thread at a time, so rows keyed by IMEI have a single writer. Configuration rows (report_setting, connection_setting) are last-writer-wins.");

  // ---------------------------------------------------------------------------------------------- 9
  d.h1("Data Lifecycle: Retention, Clearing, Backup and Recovery");
  d.h2("Retention");
  d.table("Retention by data store", [{ header: "Data", width: 3000 }, { header: "Retention", width: 2600 }, { header: "Basis", width: 3426 }], [
    ["Kafka topics", "31 days, by age only", "gps.kafka.retention; SRS NFR-CAP-002"],
    ["Telemetry and trip tables", "Until deleted (no automatic deletion yet)", "Retention policy is open: SRS NFR-PRV-003, OI-04"],
    ["dead_letter, rejected_frame", "Indefinite", "Never deleted except by demo reset (SRS FR-DLQ-008)"],
    ["vehicle_day_summary", "Indefinite", "Small; frozen generations survive Clear database"],
    ["Master data, settings", "Indefinite", "Survive Clear database and demo reset"],
    ["Redis live state", "Until replaced; history about 1000 entries per vehicle", "Rebuilt from the next readings"],
  ]);
  d.h2("Clear Database and Demo Reset");
  d.table("What is emptied", [{ header: "Table", width: 3000 }, { header: "Clear database (keep reports)", width: 3000 }, { header: "Demo reset / clear without reports", width: 3026 }], [
    ["gps_position, gps_trip, gps_stop, gps_trip_late_reading, vehicle_motion_state, vehicle_event", "Emptied (TRUNCATE … RESTART IDENTITY)", "Emptied"],
    ["dead_letter, rejected_frame", "Emptied", "Emptied"],
    ["vehicle_day_summary", "Kept; live rows frozen into a new generation first", "Emptied"],
    ["tenant, vehicle, device, device_assignment", "Kept", "Kept"],
    ["report_setting, connection_setting", "Kept", "Kept"],
  ]);
  d.h2("Backup and Recovery");
  d.bullets([
    "Proposed (SRS DR-008): a daily logical backup (pg_dump -Fc gps_tracking), kept 31 days, with a quarterly restore test.",
    "Recovery to a point in time within 31 days: restore the newest backup older than the target, then reset the consumer groups' offsets to the backup time and let them re-consume Kafka. Idempotency keys (Section 8) make the overlap harmless.",
    "Redis needs no backup: live state is rebuilt from the next readings; speed rules (gps:config:*) should be exported with the database backup.",
  ]);
  d.h2("Capacity Estimate");
  d.table("Growth estimate for 150 vehicles", [{ header: "Table", width: 2600 }, { header: "Rows per day", width: 2600 }, { header: "Approx. size per day (with indexes)", width: 3826 }], [
    ["gps_position (1 reading/min)", "216,000", "about 60 to 80 MB"],
    ["gps_position (compressed simulator)", "about 6,500,000", "about 2 GB; demo use only"],
    ["gps_trip + gps_stop", "a few thousand", "under 2 MB"],
    ["vehicle_event", "a few hundred to thousands", "under 2 MB"],
    ["vehicle_day_summary", "150", "negligible"],
  ]);
  d.note("Sizes are estimates (about 250 to 350 bytes per gps_position row including indexes) and shall be confirmed with pg_total_relation_size after a week of normal operation.");

  // ---------------------------------------------------------------------------------------------- 10
  d.h1("Migration History and Change Rules");
  d.table("Flyway migrations", [{ header: "Version", width: 900 }, { header: "File", width: 3700 }, { header: "Change", width: 4426 }], [
    ["V1", "V1__create_master_data_tables.sql", "btree_gist; tenant, vehicle, device, device_assignment"],
    ["V2", "V2__create_gps_position_table.sql", "gps_position with checks, unique key and indexes"],
    ["V3", "V3__create_vehicle_motion_state_table.sql", "Trip-detector state"],
    ["V4", "V4__create_gps_trip_and_gps_stop_tables.sql", "gps_trip, gps_stop, gps_trip_late_reading"],
    ["V5", "V5__distance_details_and_vehicle_events.sql", "gps_position distance columns, ignition, odometer; vehicle_event"],
    ["V6", "V6__dead_letter_and_rejected_frame_archive.sql", "dead_letter, rejected_frame"],
    ["V7", "V7__connection_setting.sql", "connection_setting"],
    ["V8", "V8__vehicle_day_summary_and_report_setting.sql", "vehicle_day_summary, report_setting, index gps_position_recorded_at"],
  ]);
  d.bullets([
    "An applied migration shall never be edited: Flyway's checksum check stops the service. Every change is a new file V9__<description>.sql, V10__ …",
    "Every new column or table shall be added to the JPA entities (where one exists) in the same change, because Hibernate validates the schema at start.",
    "A migration that rewrites a large table (for example adding a NOT NULL column with a default to gps_position) shall be reviewed for lock time and run outside peak hours.",
    "This document shall be updated in the same change as the migration (SRS Rules and Regulations, change control).",
  ]);

  // ---------------------------------------------------------------------------------------------- 11
  d.h1("Non-Relational Data Stores");
  d.h2("Redis Key Space");
  d.table("Redis keys", [{ header: "Key pattern", width: 3200 }, { header: "Type", width: 900 }, { header: "Written by", width: 2100 }, { header: "Content / lifetime", width: 2826 }], [
    ["gps:live:vehicle:{imei}", "hash", "live-location", "vehicle, tenant, lat, lon, speed, status, recordedAt, updatedAt; newer wins"],
    ["gps:live:positions", "geo set", "live-location", "One member per IMEI, for GEOSEARCH by radius"],
    ["gps:state:vehicle:{imei}", "hash", "vehicle-state", "Heading, acceleration, Kalman track, counters"],
    ["gps:state:speed:{imei}", "hash", "speed-violation", "Current episode, GPS window, verdict"],
    ["gps:state:ignition:{imei}", "hash", "ignition", "Ignition state, engine hours, idling"],
    ["gps:state:stoppage:{imei}", "hash", "stoppage", "Stay-point candidate or current stop"],
    ["gps:history:{imei}", "stream", "vehicle-state", "XADD per reading, XTRIM MAXLEN ~1000"],
    ["gps:events:{imei}", "list", "event recorder", "Recent events of the vehicle"],
    ["gps:context:weather:{cell}", "string", "stoppage agents", "Weather of a 0.1° cell, 10-minute expiry"],
    ["gps:config:speed-rules", "set", "speed rules API", "Scopes that have a rule"],
    ["gps:config:speed-rule:{scope}", "hash", "speed rules API", "Rule fields (Section 3.9 of the SRS); kept by resets"],
    ["gps:engine:stage:{stage}", "hash", "metrics mirror", "Totals, rate, failures, last message; every 5 s"],
    ["gps:engine:stage:{stage}:by-imei", "hash", "metrics mirror", "Messages per IMEI for the stage"],
  ], { size: 16 });
  d.p("Telemetry patterns deleted by the demo reset: gps:live:*, gps:engine:*, gps:state:*, gps:history:*, gps:events:*, gps:context:*. Configuration (gps:config:*) and other programs' keys are never deleted.");

  d.h2("Kafka Topics");
  d.table("Topics (all keyed by IMEI, 3 partitions, 31-day retention, zstd)", [
    { header: "Topic", width: 3100 }, { header: "Value", width: 2600 }, { header: "Notes", width: 3326 },
  ], [
    ["gps.raw-device-frames", "Raw frame JSON", "Written through the ingest journal"],
    ["gps.processed-positions", "Processed position JSON", "Transactional (exactly-once); timestamp = recordedAt"],
    ["gps.rejected-frames", "Rejected frame JSON", "Transactional"],
    ["gps.vehicle-events", "Vehicle event JSON", "Written through the ingest journal"],
    ["gps.vehicle-activity", "Activity window JSON", "One final record per closed 5-minute window"],
    ["gps.dead-letters", "Original bytes + error headers", "Archived to dead_letter"],
    ["gps.shadow.processed-positions / rejected-frames", "As above", "Shadow mode only"],
  ]);

  d.h2("Kafka Streams State Stores");
  d.table("State stores (RocksDB, each backed by a compacted changelog topic)", [
    { header: "Store", width: 2600 }, { header: "Application", width: 2600 }, { header: "Content", width: 3826 },
  ], [
    ["previous-reading-store", "normaliser", "Newest reading per IMEI (distance and order)"],
    ["seen-reading-store", "normaliser", "Window store of readings seen in the last 24 h (duplicates)"],
    ["vehicle-table-store", "analytics", "KTable: latest snapshot per vehicle"],
    ["activity-window-store", "analytics", "5-minute windows, kept 6 h for queries"],
  ]);

  // ---------------------------------------------------------------------------------------------- 12
  d.h1("External Source: smart_truck (Read-Only)");
  d.p("The simulator reads the legacy MySQL database smart_truck through a separate read-only pool, with SELECT statements only (SRS CON-002). Times in smart_truck are India time and are converted to UTC instants.");
  d.table("smart_truck columns read", [{ header: "Table.column", width: 3200 }, { header: "Meaning", width: 2800 }, { header: "Becomes", width: 3026 }], [
    ["tta_trips.i_trip_no", "Trip number", "Selects the latest trip per device"],
    ["tta_trips.i_gps_ping_count", "Pings in the trip", "Filter: at least 200 (minimum-pings-per-trip)"],
    ["tta_trip_gps.s_device_id", "Device identifier", "imei"],
    ["tta_trip_gps.s_asset_id", "Vehicle identifier", "vehicle.registration_no"],
    ["tta_trip_gps.s_entity_name", "Transporter name", "tenant.legal_name → tenant.code"],
    ["tta_trip_gps.dt_message", "Ping time (IST)", "recordedAt (UTC)"],
    ["tta_trip_gps.d_lat / d_long", "Position", "latitude / longitude"],
    ["tta_trip_gps.i_speed", "Speed km/h", "speedKmph"],
    ["tta_trip_gps.i_dist", "Distance increment", "Simulated odometer (for 50 % of devices)"],
  ]);

  // ---------------------------------------------------------------------------------------------- Open issues + DDL
  d.h1("Open Issues");
  d.table("Database open issues", [{ header: "ID", width: 1100 }, { header: "Issue", width: 5826 }, { header: "Owner", width: 2100 }], [
    ["DB-OI-01", "Define and implement retention and deletion for telemetry tables (time-based partitions on gps_position are the likely solution).", "Technical lead"],
    ["DB-OI-02", "Add CHECK constraints for vehicle_event.event_type and severity and gps_position.distance_source.", "Developer"],
    ["DB-OI-03", "Decide the time-series store (ClickHouse is planned) and the hand-over of gps_position history.", "Architect"],
    ["DB-OI-04", "Master-data administration API: who may create, change and close tenants, vehicles, devices and assignments.", "Product owner"],
    ["DB-OI-05", "Automated backup and restore test (SRS DR-008).", "Operations"],
  ]);

  d.h1("Appendices");
  d.h2("Appendix A: Complete DDL");
  d.p("The exact text of the Flyway migrations, in order. This appendix is regenerated from the files whenever the document is built.");
  for (const file of fs.readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort((a, b) => version(a) - version(b))) {
    d.h3(file);
    d.code(fs.readFileSync(path.join(MIGRATIONS, file), "utf-8").replace(/\r/g, "").trimEnd().split("\n"));
  }
  return d.build();
}

function version(file) {
  return Number(/^V(\d+)__/.exec(file)[1]);
}

module.exports = { build, META };
