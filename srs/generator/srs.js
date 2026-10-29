// Software Requirements Specification for the GPS Processing Layer (ISO/IEC/IEEE 29148:2018 structure).
const path = require("path");
const { DocBuilder, ref } = require("./lib");

const FIG = (name) => path.join(__dirname, "figures", name);

const META = {
  organisation: "SRMECOTECH",
  title: "Software Requirements Specification",
  subtitle: "GPS Processing Layer: fleet telemetry streaming platform (gps-streaming-service and PulseStream IoT Console)",
  shortTitle: "SRS: GPS Processing Layer",
  docId: "SRM-GPS-SRS-001",
  version: "0.9",
  status: "Draft for review (not yet baselined)",
  date: "29 October 2026",
  author: "Sanjoy Chattopadhay, SRMECOTECH",
  classification: "Internal",
  standard: "ISO/IEC/IEEE 29148:2018",
  confidentialNote: "This document describes software owned by SRMECOTECH. All IMEIs, registration numbers and company names "
    + "used as examples are dummy values. Requirements marked “Proposed” or “Future” are not yet approved for implementation.",
  register: [],
};

// Shorthands for the requirement tables: [ID, statement, priority, verification, status]
const M = "Must", S = "Should", C = "Could";
const T = "T", I = "I", A = "A", D = "D";
const B = "Baseline", P = "Proposed", F = "Future";

function build() {
  META.register = [];
  const d = new DocBuilder(META);
  d.titlePage();
  d.documentControl(
    [
      ["0.1", "19 Oct 2026", "S. Chattopadhay", "Initial requirement notes (README, Hinglish) for the Kafka pipeline, storage and trips."],
      ["0.5", "28 Oct 2026", "S. Chattopadhay", "Kafka Streams, data safety, stream consumers, reports and console captured in project guides."],
      ["0.9", "29 Oct 2026", "S. Chattopadhay", "First formal SRS per ISO/IEC/IEEE 29148:2018: requirement IDs, priorities, verification methods, business rules, regulations, RTM and open issues."],
    ],
    [
      ["Product owner", "", "", ""],
      ["Technical lead / architect", "", "", ""],
      ["Quality assurance lead", "", "", ""],
      ["Information security reviewer", "", "", ""],
    ],
    [
      ["Development team", "Design and implementation baseline"],
      ["Quality assurance", "Test design (Section 7 and the RTM in Section 8)"],
      ["Operations / DevOps", "Deployment, monitoring and recovery requirements"],
      ["Product owner and business stakeholders", "Scope confirmation and acceptance"],
    ],
  );
  d.toc();

  introduction(d);
  overallDescription(d);
  functionalRequirements(d);
  nonFunctionalRequirements(d);
  interfaceRequirements(d);
  dataRequirements(d);
  verification(d);
  traceability(d);
  appendices(d);
  return d.build();
}

// ------------------------------------------------------------------------------------------------ 1 Introduction
function introduction(d) {
  d.h1("Introduction");
  d.h2("Purpose");
  d.p("This Software Requirements Specification (SRS) defines what the GPS Processing Layer shall do, how well it shall do it, "
    + "and the constraints it shall satisfy. It is the agreed reference for design, implementation, verification and acceptance, "
    + "and it is written so that a developer can implement the system and an independent tester can verify it without "
    + "having to guess the author's intent.");
  d.p("The document covers two things at once. First, it records the **baseline**: the behaviour of release 0.1.x as implemented in "
    + "the repository SRMECOTECH/processing-layer (commit c92e25f, 29 October 2026). Second, it states the **proposed** "
    + "requirements that are needed before the platform carries real customer traffic. Each requirement is labelled "
    + "with its status so that the two are never confused (Section 1.6).");

  d.h2("Scope");
  d.p("The product is the **GPS Processing Layer**, a fleet-telemetry streaming platform for goods vehicles. It takes in raw "
    + "position frames from GPS tracking devices (in this release, a fleet simulator that replays real trips), validates "
    + "and normalises them, and then stores them. It keeps the live location of every vehicle, detects trips, stops and "
    + "driving events, and produces day and month reports with fuel-cost estimates. Operators use the PulseStream IoT "
    + "Console (a web application) to monitor the fleet and the pipeline.");
  d.table("Business objectives", [
    { header: "ID", width: 1000 }, { header: "Objective", width: 3000 }, { header: "Measure of success", width: 5026 },
  ], [
    ["OBJ-01", "Fleet visibility", "The latest valid position of every tracked vehicle can be seen on the live map and through the API."],
    ["OBJ-02", "Trustworthy data", "No reading that the platform has acknowledged is lost, stored twice or silently discarded."],
    ["OBJ-03", "Operational events", "Over-speed, stoppage, parking, idling, ignition and harsh-driving events are detected automatically."],
    ["OBJ-04", "Trip and stop history", "For every vehicle and period, trips and stops with distance and duration can be retrieved."],
    ["OBJ-05", "Cost reporting", "Day and month reports show distance, idling, fuel cost and achievable savings, each with its calculation."],
    ["OBJ-06", "Operability", "Every stage of the pipeline is observable, and failures can be diagnosed and recovered from the console."],
    ["OBJ-07", "Extensibility", "A new processing module can be attached as a new consumer group without changing the existing ones."],
    ["OBJ-08", "Security and compliance", "Access is controlled, secrets are protected and location data is handled lawfully."],
  ], { label: "objectives" });
  d.h3("In scope");
  d.bullets([
    "Ingestion of raw GPS frames (fleet simulator, single-frame test API), with a durable ingest journal in front of Apache Kafka.",
    "Validation, de-duplication, ordering, distance and movement calculation (the normaliser), and rejection with a reason.",
    "Storage of positions in PostgreSQL; live location in Redis; master data that resolves IMEI to vehicle and tenant.",
    "Trip and stop detection; the speed-violation, vehicle-state, stoppage and ignition stream consumers; vehicle events.",
    "The Kafka Streams normaliser (exactly-once) and analytics application (vehicle table, five-minute activity windows, shadow parity).",
    "Dead-letter queue and archives of dead letters and rejected frames; the data-safety report.",
    "Day, month and vehicle-day reports, fuel and savings, cost assumptions, and clearing the database while keeping the reports.",
    "Run-time switching of external connections (Redis, Valhalla); the engine dashboard and REST API; demo tools.",
    "The PulseStream IoT Console with sign-in.",
  ]);
  d.h3("Out of scope for this release");
  d.bullets([
    "Device protocol parsing (TCP/UDP listeners for vendor protocols). The interface to a future parser is specified (IFR-KAF-001).",
    "Geofencing, waypoints and ETA, route deviation, ClickHouse storage, and notification delivery (SMS, e-mail, push). The console "
      + "shows these modules as “Planned” with example data; their requirements are listed as “Future” for traceability only.",
    "Multiple user accounts, role-based access control and single sign-on (proposed in FR-AUTH-010).",
    "Driver management, maintenance scheduling, billing, mobile applications and map-tile hosting.",
    "Writing to the legacy smart_truck database, which is a read-only source.",
  ]);

  d.h2("Intended Audience and Reading Suggestions");
  d.table("Audience", [{ header: "Reader", width: 2600 }, { header: "Recommended sections", width: 6426 }], [
    ["Product owner, business stakeholders", "1, 2, 3.22 (business rules), 4.10 (regulations), 7.2 (acceptance)"],
    ["Developers and architects", "2, 3, 5, 6 and the companion Database Schema Specification [R4]"],
    ["Testers / quality assurance", "3, 4, 7 and the traceability matrix in Section 8"],
    ["Operations / DevOps", "2.4, 4 (reliability, availability, observability), 5.4, 6.2"],
    ["Security and compliance reviewers", "3.1, 4.5, 4.6, 4.10, 6.2"],
  ]);

  d.h2("Definitions, Acronyms and Abbreviations");
  d.table("Glossary", [{ header: "Term", width: 2300 }, { header: "Definition", width: 6726 }], [
    ["Accepted frame", "A raw frame that passed validation and became a processed position."],
    ["At-least-once", "Delivery guarantee under which a record is never lost but may be delivered more than once."],
    ["Changelog topic", "Compacted Kafka topic that backs up a Kafka Streams state store so that the store can be restored."],
    ["Consumer group", "Set of Kafka consumers that share the work of one purpose; each group receives every record of a topic."],
    ["Dead letter", "A record that a consumer could not handle and parked in topic gps.dead-letters with its error."],
    ["Design peak load", "150 vehicles replayed at 30 times real time: about 75 frames per second (Section 4.1)."],
    ["Device assignment", "Period during which a device is installed in a vehicle (valid_from to valid_to)."],
    ["Exactly-once (EOS)", "Kafka transactions: output records, state-store updates and input offsets commit together or not at all."],
    ["Frame / reading", "One position report from a device: IMEI, recorded time, latitude, longitude, speed, optional ignition and odometer."],
    ["IMEI", "International Mobile Equipment Identity; the identifier of a tracking device."],
    ["Ingest journal", "Local, fsynced, append-only file store in which frames are written before they are sent to Kafka."],
    ["Late / out-of-order reading", "A reading whose recorded time is earlier than that of the newest reading already processed for the device."],
    ["Market vehicle", "A vehicle not owned by any tenant on the platform (owner_tenant_id is null)."],
    ["Normal operating load", "150 vehicles reporting at their real interval (about one frame per minute each): about 2.5 frames per second."],
    ["Normaliser", "The stage that turns raw frames into processed positions or rejections."],
    ["Stale reading", "A reading more than 24 hours older than the newest reading of the same device."],
    ["Tenant", "A customer company; identified on telemetry by its short lower-case code."],
    ["ACK", "Acknowledgement"], ["API", "Application Programming Interface"], ["CO₂", "Carbon dioxide"],
    ["DLQ", "Dead-letter queue"], ["DPDP", "Digital Personal Data Protection (Act, 2023, India)"],
    ["EOS", "Exactly-once semantics"], ["GEO set", "Redis sorted set indexed by geohash for radius queries"],
    ["HA", "High availability"], ["IQ", "Kafka Streams Interactive Queries"], ["IST", "Indian Standard Time (UTC+05:30)"],
    ["JSON", "JavaScript Object Notation"], ["KRaft", "Kafka Raft metadata mode (no ZooKeeper)"], ["KTable", "Kafka Streams table abstraction"],
    ["MoSCoW", "Must, Should, Could, Won't (prioritisation)"], ["p95", "95th percentile"],
    ["RBAC", "Role-based access control"], ["RPO / RTO", "Recovery point objective / recovery time objective"],
    ["RTM", "Requirements Traceability Matrix"], ["SRS", "Software Requirements Specification"],
    ["TBD", "To be determined (recorded in the open-issues register, Section 8.3)"], ["UTC", "Coordinated Universal Time"],
  ]);

  d.h2("References");
  d.table("Referenced documents", [{ header: "Ref.", width: 700 }, { header: "Document", width: 8326 }], [
    ["[R1]", "ISO/IEC/IEEE 29148:2018, Systems and software engineering: Life cycle processes, Requirements engineering."],
    ["[R2]", "IEEE Std 830-1998, Recommended Practice for Software Requirements Specifications (superseded; historical reference only)."],
    ["[R3]", "SRM-GPS-SRS-RUL-001, SRS Rules and Regulations (companion document: how this SRS is written, reviewed and changed)."],
    ["[R4]", "SRM-GPS-DBS-001, Database Schema Specification for gps_tracking (companion document)."],
    ["[R5]", "Repository README.md and README_SIMPLE.md (project overview, design decisions)."],
    ["[R6]", "docs/DATA_SAFETY_GUIDE.md (no-loss design: journal, retention, dead letters, data-safety report)."],
    ["[R7]", "docs/KAFKA_STREAMS_GUIDE.md (normaliser and analytics topologies)."],
    ["[R8]", "docs/REPORTS_GUIDE.md (report formulas, summary job, clear database)."],
    ["[R9]", "docs/ENGINE_AND_DOCKER_GUIDE.md and docs/APPLICATIONS_GUIDE.md (operations)."],
    ["[R10]", "Apache Kafka 4.3 documentation: Kafka Streams architecture, transactions and consumer groups."],
    ["[R11]", "Traccar open-source GPS tracking server: motion, distance, filter, ignition and engine-hours algorithms ported in this product."],
    ["[R12]", "The Digital Personal Data Protection Act, 2023 (India), and the rules made under it."],
    ["[R13]", "The Information Technology Act, 2000 (India), section 43A, and the IT (Reasonable Security Practices and Procedures and Sensitive Personal Data or Information) Rules, 2011."],
    ["[R14]", "CERT-In Directions under section 70B(6) of the IT Act, 28 April 2022 (incident reporting and log retention)."],
    ["[R15]", "AIS-140, Intelligent Transportation Systems: requirements for public transport vehicle operation (vehicle location tracking devices), ARAI."],
    ["[R16]", "W3C Web Content Accessibility Guidelines (WCAG) 2.1."],
  ]);

  d.h2("Document Conventions");
  d.h3("Requirement language");
  d.bullets([
    "**shall**: a mandatory requirement. Every “shall” statement is verifiable and carries a unique identifier.",
    "**should**: a recommendation. Not meeting it needs a recorded justification.",
    "**may**: a permitted option.",
    "**will**: a statement of fact or intent about something outside the system (for example, an external system's behaviour). It is not a requirement on this product.",
  ]);
  d.h3("Requirement identifiers");
  d.table("Identifier scheme", [{ header: "Prefix", width: 1500 }, { header: "Meaning", width: 3000 }, { header: "Example", width: 4526 }], [
    ["FR-<area>-nnn", "Functional requirement", "FR-VAL-003 (validation), FR-TRP-002 (trip detection)"],
    ["NFR-<cat>-nnn", "Non-functional requirement", "NFR-PERF-001, NFR-REL-004, NFR-SEC-002"],
    ["IFR-<if>-nnn", "Interface requirement", "IFR-API-001 (REST), IFR-KAF-001 (Kafka), IFR-RDS-001 (Redis)"],
    ["DR-nnn", "Data requirement", "DR-004 (time zone of stored timestamps)"],
    ["BR-nnn", "Business rule", "BR-001 (one device in one vehicle at a time)"],
    ["REG-nnn", "Regulatory or legal requirement", "REG-001 (personal-data notice and purpose)"],
    ["CON-nnn", "Design or implementation constraint", "CON-004 (no third-party UI libraries)"],
    ["ASM-nnn / DEP-nnn", "Assumption / dependency", "ASM-002, DEP-001"],
    ["OBJ-nn", "Business objective (source of requirements)", "OBJ-02 (trustworthy data)"],
    ["AC-nn / TC-<req>", "Acceptance criterion / test case", "AC-03; TC-FR-VAL-003 verifies FR-VAL-003"],
  ]);
  d.p("Identifiers are never re-used. A deleted requirement keeps its row with status “Deleted” and the reason.");
  d.h3("Priority, verification method and status");
  d.table("Attribute values used in requirement tables", [{ header: "Attribute", width: 1700 }, { header: "Values", width: 7326 }], [
    ["Priority", "Must: required for acceptance of the release. Should: important, may move to the next release with stakeholder agreement. Could: desirable."],
    ["Verify", "T = Test (executed test with expected result). I = Inspection (review of code, configuration or documents). A = Analysis (calculation, model or log analysis). D = Demonstration (observed operation without measurement)."],
    ["Status", "Baseline: implemented in release 0.1.x; must be verified before approval. Proposed: not implemented; needs approval. Future: roadmap item kept for traceability; not part of this release's acceptance."],
  ]);
  d.h3("Units, time and configurable values");
  d.bullets([
    "Distances are in metres (m) or kilometres (km), speeds in km/h, durations in seconds (s) unless stated otherwise.",
    "Timestamps are instants in UTC, written in ISO-8601 (for example 2026-10-29T05:17:11Z). Report days use Asia/Kolkata (IST).",
    "Many thresholds can be configured. A configuration key is shown in code font with its default, for example `gps.trips.min-distance-meters` (default 200). Such a requirement applies to the **configured** value; the default is the value that is verified at acceptance.",
  ]);
}

// ------------------------------------------------------------------------------------------------ 2 Overall description
function overallDescription(d) {
  d.h1("Overall Description");
  d.h2("Product Perspective");
  d.p("The GPS Processing Layer is a new, self-contained system that sits between the tracking devices and the applications "
    + "that use fleet data. It replaces ad-hoc processing on the legacy smart_truck MySQL database, which it reads "
    + "(read-only) only as a source of real trips for the simulator. " + ref("fig-context") + " shows the system boundary and the external entities.");
  d.figure(FIG("context.png"), "System context diagram", 6.27, "fig-context");
  d.p("Inside the boundary, one Spring Boot service (gps-streaming-service) hosts the processing logic, the stream consumers, "
    + "two Kafka Streams applications, the REST API and the report jobs. Apache Kafka is the buffer and the 31-day record of all "
    + "traffic. PostgreSQL (database gps_tracking) is the system of record. Redis holds live state and the speed rules. "
    + "The PulseStream IoT Console is a separate Next.js application that proxies its /api calls to the service.");
  d.table("Software modules", [{ header: "Module", width: 2100 }, { header: "Responsibility", width: 6926 }], [
    ["gps-configuration", "Names of topics, consumer groups, stages and Redis keys; message records; JSON mapping. Plain Java."],
    ["gps-connectivity", "All connections to the outside: Kafka publishers and listeners, retry and dead letters, ingest journal, Redis stores, JPA entities and repositories, smart_truck reader, Valhalla client, metrics."],
    ["gps-processing", "Business logic as pure functions: validation, movement and distance, trip detection, speed violation, vehicle state (Kalman filter), stoppage, ignition, fuel estimation. Fully unit-tested."],
    ["gps-streams", "Kafka Streams topologies: the exactly-once normaliser and the analytics application (vehicle table, activity windows, parity)."],
    ["gps-application", "The Spring Boot service: Kafka and direct flows, simulator, master data, storage, live location, trips, events, reports, connections, data safety, REST API, Flyway migrations."],
    ["gps-dashboard-ui", "PulseStream IoT Console (Next.js, React). Sign-in, seven sections (Live, Processing, Alerts, Storage, Analytics, Notifications, Developer), Reports."],
  ], { label: "modules" });

  d.h2("Product Functions");
  d.p(ref("fig-pipeline") + " shows how a frame travels through the platform in the Kafka flow (the default). The functions are summarised in " + ref("functions") + " and specified in Section 3.");
  d.figure(FIG("pipeline.png"), "Processing pipeline (Kafka flow)", 6.27, "fig-pipeline");
  d.table("Summary of product functions", [{ header: "Function", width: 2400 }, { header: "Summary", width: 5126 }, { header: "Section", width: 1500 }], [
    ["Access control", "Sign-in to the console; signed session cookie; lock-out after repeated failures.", "3.1"],
    ["Master data", "Tenants, vehicles, devices and time-bounded device assignments; IMEI resolution.", "3.2"],
    ["Ingestion", "Accept frames, journal them durably, publish them to Kafka keyed by IMEI.", "3.3"],
    ["Validation and normalisation", "Reject malformed frames with a reason; de-duplicate; compute distance and movement.", "3.4, 3.5"],
    ["Storage and live location", "Store each position once; keep the newest position of each vehicle in Redis.", "3.6, 3.7"],
    ["Trips and stops", "Detect trips and stops as readings arrive.", "3.8"],
    ["Stream consumers and events", "Over-speed, sensor mismatch, vehicle state, stoppage, parking, ignition, idling, harsh driving.", "3.9 to 3.13"],
    ["Kafka Streams", "Exactly-once normaliser; vehicle table; activity windows; shadow parity.", "3.14"],
    ["No-data-loss tooling", "Dead letters and rejected-frame archives; replay and discard; data-safety report.", "3.15, 3.16"],
    ["Reports", "Day, month, vehicle-day, fuel and savings, cost assumptions, clear database.", "3.17"],
    ["Administration", "Connections, simulator, engine dashboard, demo reset, console navigation.", "3.18 to 3.21"],
  ], { label: "functions" });

  d.h2("User Classes and Characteristics");
  d.table("User classes", [
    { header: "User class", width: 1700 }, { header: "Description and goals", width: 3626 }, { header: "Frequency / expertise", width: 1900 }, { header: "Main functions", width: 1800 },
  ], [
    ["Fleet operator", "Watches vehicles and alerts during the shift; reacts to stoppages and over-speed.", "Daily, continuous / non-technical", "Live, Alerts"],
    ["Fleet manager", "Reviews day and month reports, fuel cost and savings; sets cost assumptions and speed limits.", "Daily to monthly / business user", "Reports, Alerts, Processing rules"],
    ["Platform engineer", "Develops and operates the pipeline; investigates lag, rejects and dead letters.", "Daily / expert", "Processing, Storage, Developer"],
    ["Administrator", "Manages connections, simulator, data clearing and credentials.", "Occasional / expert", "Developer, Reports → Clear database"],
    ["Demo presenter", "Runs demonstrations with the simulator and resets the data between them.", "Occasional / intermediate", "Developer → Demo tools"],
  ]);
  d.note("In this release all user classes share one console account (FR-AUTH-002). Separating them by role is proposed in FR-AUTH-010.");

  d.h2("Operating Environment");
  d.table("Operating environment (baseline)", [{ header: "Component", width: 2600 }, { header: "Version", width: 1700 }, { header: "Notes", width: 4726 }], [
    ["Java runtime", "21", "Service runs on a JRE in a non-root container (uid 1001)."],
    ["Spring Boot", "4.1.1", "With Spring for Apache Kafka, Spring Data JPA (Hibernate), Flyway, Actuator."],
    ["Apache Kafka", "4.3.1 (KRaft)", "One broker by default; three brokers with docker-compose.ha.yml."],
    ["Kafka Streams / RocksDB", "4.2.1", "Library inside the service; state directory on a volume."],
    ["PostgreSQL", "14 (14.22 native)", "Database gps_tracking; extension btree_gist."],
    ["Redis", "7.4 (Docker, Redis Stack) or 8.2 (native)", "Database 0 locally; database 1 on the HP home server."],
    ["MySQL (smart_truck)", "8", "Legacy, read-only source for the simulator."],
    ["Node.js / Next.js / React", "Next.js with React (see gps-dashboard-ui/package.json)", "Console; no third-party UI packages (CON-004)."],
    ["Docker Desktop / Compose", "29.x", "Recommended deployment (containers postgres, kafka, redis, gps-service, dashboard)."],
    ["Host operating system", "Windows 11 (development), any Docker host", "Native Windows scripts in infrastructure/windows."],
    ["Client browser", "Current Chrome, Edge or Firefox", "Desktop screens 1280 px wide or more (NFR-CMP-002)."],
  ]);

  d.h2("Design and Implementation Constraints");
  d.requirements("Constraints", [
    ["CON-001", "The schema of database gps_tracking shall be created and changed only by numbered Flyway migrations; Hibernate shall run with `ddl-auto: validate`, and an applied migration shall never be edited.", M, I, B],
    ["CON-002", "The legacy smart_truck database shall be accessed through a separate connection pool opened read-only, using SELECT statements only.", M, I, B],
    ["CON-003", "Passwords and keys shall not be stored in application.yml or in version control; they shall come from local-secrets.properties or .env (both git-ignored) or environment variables.", M, I, B],
    ["CON-004", "The console shall use no third-party UI packages: only next, react and react-dom, CSS Modules and in-house icons (client server policy).", M, I, B],
    ["CON-005", "Every Kafka topic of the platform shall be keyed by IMEI and have 3 partitions; the name of each topic, consumer group and Redis key shall be defined in exactly one class (KafkaTopicNames, KafkaConsumerGroupNames, RedisKeyNames).", M, I, B],
    ["CON-006", "Processing rules shall be implemented as plain Java without Spring, Kafka or database dependencies, so that the Kafka flow, the direct flow and Kafka Streams run the same code.", M, I, B],
    ["CON-007", "Normalisation shall have exactly one owner at a time (`gps.streams.normalization` = owner, shadow or off); the Kafka Streams normaliser shall use the listener's group id so that ownership can change without replay or gap.", M, I, B],
    ["CON-008", "The service shall start even when smart_truck, Valhalla, the HP ingestion API or the weather and traffic APIs are unreachable; only the features that need them shall fail.", M, T, B],
  ], { objective: "OBJ-07", component: "Architecture", tests: "Code review" });

  d.h2("Assumptions and Dependencies");
  d.table("Assumptions", [{ header: "ID", width: 1100 }, { header: "Assumption", width: 5426 }, { header: "Impact if false", width: 2500 }], [
    ["ASM-001", "Device clocks are synchronised to GNSS time within 5 minutes.", "Frames are rejected as “in the future”; trips are skewed."],
    ["ASM-002", "Each device reports at most one reading per second, and a vehicle's readings arrive mostly in order.", "More late readings; trip distances need the repair job (OI-07)."],
    ["ASM-003", "The fleet in scope is about 150 goods vehicles, fitted with speed governors (observed maximum 60 km/h).", "Capacity requirements (4.2) must be re-validated."],
    ["ASM-004", "A device is moved between vehicles only by recording a new device assignment.", "Readings are attributed to the wrong vehicle."],
    ["ASM-005", "Operators use the console from a trusted network (laptop, LAN or VPN) in this release.", "Proposed security requirements NFR-SEC-006 to 008 become mandatory."],
  ]);
  d.table("Dependencies", [{ header: "ID", width: 1100 }, { header: "Dependency", width: 5426 }, { header: "Used for", width: 2500 }], [
    ["DEP-001", "Apache Kafka cluster (1 or 3 brokers), 31-day retention", "Transport and 31-day replay"],
    ["DEP-002", "PostgreSQL 14 with btree_gist", "System of record"],
    ["DEP-003", "Redis 7.4+ (local or HP home server, database 1)", "Live state, rules, history"],
    ["DEP-004", "smart_truck MySQL 8 (read-only user)", "Simulator source trips"],
    ["DEP-005", "Valhalla routing engine (HP home server, all-India map)", "Route checks; route deviation (future)"],
    ["DEP-006", "Open-Meteo weather API; TomTom traffic API (optional key)", "Stoppage cause analysis"],
    ["DEP-007", "HP ingestion API (FastAPI), health endpoint only", "Connections page status"],
  ]);
}

// ------------------------------------------------------------------------------------------------ 3 Functional requirements
function functionalRequirements(d) {
  d.h1("Specific Requirements");
  d.p("This section states the functional requirements module by module. Each module gives its purpose, inputs and outputs, "
    + "followed by its requirements, including the behaviour for invalid input, duplicates, late data, missing permissions and "
    + "unavailable dependencies. Section 3.23 summarises the exceptional cases across modules.");

  d.h2("User Authentication and Session Management (AUTH)");
  d.p("Inputs: user name, password, “Keep me signed in”. Outputs: signed session cookie `pulsestream_session`; redirect to the requested page. Component: gps-dashboard-ui (session.ts, auth-actions.ts, proxy.ts).");
  d.requirements("Authentication requirements", [
    ["FR-AUTH-001", "The console shall serve no page and forward no /api/* request without a valid session. An unauthenticated page request shall be redirected to /login, with the requested path kept and restored after sign-in. An unauthenticated /api/* request shall receive HTTP 401.", M, T, B],
    ["FR-AUTH-002", "The console shall accept exactly one account, whose user name and password are read from DASHBOARD_USERNAME and DASHBOARD_PASSWORD.", M, T, B],
    ["FR-AUTH-003", "When DASHBOARD_USERNAME or DASHBOARD_PASSWORD is not set, the console shall use the development account “abc” / “abc” (see NFR-SEC-006).", M, T, B],
    ["FR-AUTH-004", "Credential comparison shall take the same time whichever part (user name or password) is wrong.", S, I, B],
    ["FR-AUTH-005", "After 5 failed sign-in attempts from the same client address, the console shall refuse further attempts from that address for 60 seconds and say so.", M, T, B],
    ["FR-AUTH-006", "On success, the console shall issue a session cookie signed with AUTH_SECRET (or, if unset, a key derived from the account with PBKDF2), valid for 12 hours, or 30 days when “Keep me signed in” is selected.", M, T, B],
    ["FR-AUTH-007", "A missing, altered or expired session cookie shall be treated as no session.", M, T, B],
    ["FR-AUTH-008", "Sign-out shall delete the session cookie and return the user to the sign-in page.", M, T, B],
    ["FR-AUTH-009", "Changing the account password or AUTH_SECRET shall invalidate every existing session.", S, T, B],
    ["FR-AUTH-010", "The system should support named user accounts with the roles Viewer, Operator and Administrator, and restrict every state-changing operation (speed rules, connections, cost assumptions, dead-letter replay or discard, simulator, clear database) to Operator or Administrator.", S, T, P],
  ], { objective: "OBJ-08", component: "gps-dashboard-ui: proxy.ts, session.ts, auth-actions.ts", tests: "Manual (no automated UI tests yet)" });

  d.h2("Master Data and Device Resolution (MD)");
  d.p("Inputs: device IMEI and the reading's recorded time. Outputs: tenant code and vehicle registration number valid at that time. Component: DeviceVehicleDirectory, SimulationMasterDataSeeder, TenantCodeFormatter; tables tenant, vehicle, device, device_assignment.");
  d.requirements("Master data requirements", [
    ["FR-MD-001", "The system shall store tenants, vehicles, devices and device assignments as specified in the Database Schema Specification [R4].", M, I, B],
    ["FR-MD-002", "The system shall resolve a reading to the vehicle and tenant of the device assignment whose period [valid_from, valid_to) contains the reading's recorded time, not the processing time.", M, T, B],
    ["FR-MD-003", "When no assignment covers the reading's time, the system shall still store the position, with tenant and vehicle empty, and log a WARN line naming the IMEI.", M, T, B],
    ["FR-MD-004", "The system shall cache each resolved assignment period and query the database again only when a reading falls outside the cached period.", S, T, B],
    ["FR-MD-005", "The simulator shall register the tenants, vehicles, devices and assignments of the devices it replays before sending their first frame.", M, T, B],
    ["FR-MD-006", "A tenant code shall be derived from the company name by lower-casing it, replacing every run of characters other than a–z and 0–9 with one hyphen, trimming hyphens at both ends and cutting it to 40 characters (for example “DEMO TRANSPORT, JSR” → “demo-transport-jsr”).", M, T, B],
    ["FR-MD-007", "The system should provide API and console functions to create, edit, activate, suspend and close tenants, register vehicles and devices, and open and close device assignments, with validation of BR-001 to BR-003.", S, T, P],
  ], { objective: "OBJ-01", component: "masterdata/, entity/, V1 migration", tests: "TenantCodeFormatterTest" });

  d.h2("GPS Frame Ingestion (ING)");
  d.p("Inputs: raw frame JSON (" + ref("raw-frame") + ") from the simulator, the single-frame API, or a future device parser. Outputs: a record in gps.raw-device-frames. Component: RawGpsFrameSender, KafkaRawGpsFrameSender, IngestJournal, DurableKafkaSender.");
  d.requirements("Ingestion requirements", [
    ["FR-ING-001", "The system shall publish every raw frame to topic gps.raw-device-frames with the frame's IMEI as the record key, so that all frames of a device are kept in order in one partition.", M, T, B],
    ["FR-ING-002", "Before sending a frame to Kafka, the system shall append it to the ingest journal and fsync it to disk (`gps.journal.fsync-every-record` = true).", M, T, B],
    ["FR-ING-003", "A journal entry shall be removed only after Kafka has acknowledged the record with acks=all.", M, T, B],
    ["FR-ING-004", "When Kafka is unavailable, the sender shall return without blocking the caller for more than 5 seconds (`max.block.ms`), keep the frame in the journal, and resend journalled frames in their original order when Kafka is available again.", M, T, B],
    ["FR-ING-005", "After a crash or restart, the system shall resend every journalled frame that Kafka had not acknowledged, in its original order.", M, T, B],
    ["FR-ING-006", "The system shall provide POST /api/simulator/send-single-frame that accepts one raw frame (valid or invalid) and processes it like any other frame.", M, T, B],
    ["FR-ING-007", "In the direct flow (`gps.processing-flow=direct`), frames shall be processed synchronously in arrival order, one at a time, without Kafka, the journal or the dead-letter queue.", S, T, B],
    ["FR-ING-008", "A device parser (TCP/UDP listener for vendor protocols, AIS-140 included) shall convert device messages into raw frames and use the same sender.", C, T, F],
  ], { objective: "OBJ-02", component: "flow/, journal/, kafka/RawGpsFramePublisher", tests: "IngestJournalTest, DurableKafkaSenderTest" });

  d.h2("Frame Validation (VAL)");
  d.p("Each rule is applied in the order listed. The first failing rule rejects the frame with the stated reason, and the frame is published to gps.rejected-frames with its original payload. Component: GpsFrameValidationRules, ValidateRawFrameStep.");
  d.requirements("Validation rules", [
    ["FR-VAL-001", "A frame that is not valid JSON shall be rejected with reason “Frame is not valid JSON”.", M, T, B],
    ["FR-VAL-002", "A frame with a missing or blank IMEI shall be rejected with reason “IMEI is missing”.", M, T, B],
    ["FR-VAL-003", "A frame without a recorded time shall be rejected with reason “Recording time is missing”.", M, T, B],
    ["FR-VAL-004", "A frame whose recorded time is more than 5 minutes after the processing time shall be rejected with reason “Recording time <t> is in the future”.", M, T, B],
    ["FR-VAL-005", "A frame without latitude or longitude shall be rejected with reason “Latitude or longitude is missing”.", M, T, B],
    ["FR-VAL-006", "A frame with latitude outside −90 to 90 shall be rejected with reason “Latitude <v> is outside -90..90”.", M, T, B],
    ["FR-VAL-007", "A frame with longitude outside −180 to 180 shall be rejected with reason “Longitude <v> is outside -180..180”.", M, T, B],
    ["FR-VAL-008", "A frame at exactly 0, 0 shall be rejected with reason “Position is 0,0 - the device has no GPS fix”.", M, T, B],
    ["FR-VAL-009", "A frame without speed shall be rejected with reason “Speed is missing”; a missing value shall never be read as 0.", M, T, B],
    ["FR-VAL-010", "A frame with speed outside 0 to 150 km/h shall be rejected with reason “Speed <v> km/h is outside 0..150”.", M, T, B],
    ["FR-VAL-011", "The Kafka Streams normaliser shall reject a frame whose record key differs from the frame's IMEI.", M, T, B],
  ], { objective: "OBJ-02", component: "gps-processing rule/GpsFrameValidationRules", tests: "GpsFrameValidationRulesTest, NormalizationTopologyTest" });

  d.h2("Normalisation, Distance and Movement (NRM)");
  d.p("Inputs: a valid frame and the previous reading of the device. Outputs: a processed position (" + ref("processed") + "). Component: CalculateMovementStep, DistanceCalculator, MovementRules, NormalizeFrameProcessor.");
  d.requirements("Normalisation requirements", [
    ["FR-NRM-001", "A reading with the same IMEI and recorded time as a reading seen in the last 24 hours (`gps.streams.seen-readings-retention`) shall be rejected with reason “Duplicate reading”.", M, T, B],
    ["FR-NRM-002", "A reading more than 24 hours older than the device's newest reading (`gps.streams.max-lateness`) shall be rejected with reason “Stale reading”.", M, T, B],
    ["FR-NRM-003", "A reading older than the device's newest reading (and not stale) shall be accepted with arrived_out_of_order = true, distance 0, seconds since previous 0 and distance source OUT_OF_ORDER, and shall not become the device's newest reading.", M, T, B],
    ["FR-NRM-004", "For an in-order reading, distance shall be the device odometer delta when the device reports a believable odometer (source DEVICE_ODOMETER), otherwise the great-circle distance from the previous position (source GPS).", M, T, B],
    ["FR-NRM-005", "When the position moved less than 10 m (`gps.distance.jitter-meters`) and the speed is below 5 km/h (`jitter-max-speed-kmph`), the position shall be clamped to the previous one and the distance shall be 0 (source JITTER_FILTERED).", M, T, B],
    ["FR-NRM-006", "When the implied speed exceeds 200 km/h (`max-implied-speed-kmph`) over a jump longer than 500 m (`min-jump-meters`), the distance shall not be counted (source JUMP_FILTERED) and the new position shall be kept.", M, T, B],
    ["FR-NRM-007", "The first reading of a device shall have distance 0 and source FIRST_READING.", M, T, B],
    ["FR-NRM-008", "total_distance_m shall equal the previous total plus the counted distance of the reading.", M, T, B],
    ["FR-NRM-009", "A reading shall be MOVING when its speed is at least 5 km/h or it is at least 50 m from the previous reading; otherwise STOPPED.", M, T, B],
    ["FR-NRM-010", "The previous reading shall be remembered only after the result was delivered; a reading whose result could not be delivered shall be processed normally on retry and not be treated as a duplicate.", M, T, B],
    ["FR-NRM-011", "After a restart, the previous reading of a device shall be recovered from the state store (Kafka Streams) or from PostgreSQL (listener), so that the first reading after a restart has the correct distance.", M, T, B],
    ["FR-NRM-012", "The normaliser shall set the output record timestamp to the reading's recorded time.", M, T, B],
  ], { objective: "OBJ-02", component: "gps-processing step/, rule/; gps-streams normalize/", tests: "CalculateMovementStepTest, MovementRulesTest, GpsFrameProcessorTest, NormalizationTopologyTest" });

  d.h2("Position Storage (STO)");
  d.requirements("Position storage requirements", [
    ["FR-STO-001", "Consumer group gps-position-storage-service shall store every processed position in table gps_position with the tenant code and vehicle registration number resolved per FR-MD-002.", M, T, B],
    ["FR-STO-002", "A position whose (imei, recorded_at) already exists shall not be stored again, and the delivery shall be treated as successful.", M, T, B],
    ["FR-STO-003", "The system shall provide GET /api/gps-positions/count, GET /api/gps-positions/recently-stored (newest 20) and GET /api/vehicles/{registrationNo}/latest-position.", M, T, B],
  ], { objective: "OBJ-02", component: "storage/GpsPositionStorageService", tests: "Integration (TC)" });

  d.h2("Live Location (LIVE)");
  d.requirements("Live location requirements", [
    ["FR-LIVE-001", "Consumer group gps-live-location-service shall keep, for each IMEI, the newest position in Redis hash `gps:live:vehicle:{imei}` (vehicle, tenant, latitude, longitude, speed, status, recordedAt, updatedAt) and in GEO set `gps:live:positions`.", M, T, B],
    ["FR-LIVE-002", "A reading older than the stored live position shall not overwrite it.", M, T, B],
    ["FR-LIVE-003", "The console shall show the live map and vehicle list from the live positions, refreshing every 2 seconds (default polling interval) while live updates are on.", M, D, B],
  ], { objective: "OBJ-01", component: "livelocation/, redis/LiveVehiclePositionStore, console Live", tests: "Integration (TC)" });

  d.h2("Trip and Stop Detection (TRP)");
  d.p("Consumer group gps-trip-service. Inputs: processed positions. Outputs: rows in gps_trip, gps_stop, gps_trip_late_reading and vehicle_motion_state. " + ref("fig-trip") + " shows the state machine.");
  d.figure(FIG("trip-state.png"), "Trip and stop detection state machine", 6.0, "fig-trip");
  d.requirements("Trip and stop requirements", [
    ["FR-TRP-001", "When a stationary vehicle has a reading at least 200 m (`gps.trips.min-distance-meters`) from the stop position, the system shall close the stop and open a trip whose start is back-dated to the reading at which the vehicle began to move.", M, T, B],
    ["FR-TRP-002", "When none of the readings of the last 180 s (`gps.trips.min-duration-seconds`) is 200 m or more from the newest reading, the system shall close the trip and open a stop.", M, T, B],
    ["FR-TRP-003", "When a device is silent for more than 3600 s (`gps.trips.stop-gap-seconds`), the open trip shall be closed at the last reading before the silence.", M, T, B],
    ["FR-TRP-004", "When the device assignment of a reading differs from the open trip's assignment, the open trip shall be closed; a trip shall never span two vehicles.", M, T, B],
    ["FR-TRP-005", "Trip distance shall be the sum of distance_from_previous_m of the trip's in-order readings and shall never be negative.", M, T, B],
    ["FR-TRP-006", "An open trip or stop shall be stored with status OPEN and updated with each reading; it shall be CLOSED when it ends.", M, T, B],
    ["FR-TRP-007", "A late reading (recorded at or before the last applied reading) shall not change state, distance or duration; it shall be recorded once in gps_trip_late_reading and increase the affected trip's late_reading_count by 1.", M, T, B],
    ["FR-TRP-008", "The state update and the trip and stop writes of one reading shall be one database transaction, guarded by “last_recorded_at < the reading's recorded time”; a redelivered reading shall change nothing.", M, T, B],
    ["FR-TRP-009", "Trips and stops shall be upserted on (imei, start_recorded_at).", M, T, B],
    ["FR-TRP-010", "The system shall provide GET /api/trips, GET /api/stops (filters imei, from, to; default the last 24 hours; overlap semantics) and GET /api/trips/count.", M, T, B],
    ["FR-TRP-011", "A repair job should recompute the trips listed in gps_trip_late_reading from gps_position.", C, T, F],
  ], { objective: "OBJ-04", component: "gps-processing trip/TripMotionProcessor; trip/TripDetectionService", tests: "TripMotionProcessorTest, MotionStateJsonTest" });

  d.h2("Speed Violation (SPD)");
  d.p("Consumer group gps-speed-violation-service. Rules are stored in Redis (`gps:config:speed-rule:{scope}`). The default rule is: limit 60 km/h, 3 consecutive readings, 60 s minimum duration, 5 km/h hysteresis, tolerance max(10 km/h, 15 %), 180 s maximum gap, 3-reading GPS window, 3 mismatching readings.");
  d.requirements("Speed violation requirements", [
    ["FR-SPD-001", "The rule applied to a reading shall be the vehicle rule (scope vehicle:{registration}) if present, else the tenant rule (tenant:{code}), else the default rule.", M, T, B],
    ["FR-SPD-002", "An over-speed episode shall start when at least minConsecutivePings consecutive readings exceed the limit and the excess has lasted at least minDurationSeconds; an OVERSPEED event shall then be raised once with status OPEN.", M, T, B],
    ["FR-SPD-003", "An episode shall end, and its event become CLOSED, only when the speed falls below the limit minus the hysteresis, or when the gap between readings exceeds maxGapSeconds.", M, T, B],
    ["FR-SPD-004", "For each reading, the system shall compare the reported speed with the GPS speed over the last windowPings readings (path ÷ time, or odometer when available) and classify it GPS_VERIFIED or SENSOR_SUSPECT using the tolerance max(toleranceKmph, tolerancePercent × speed).", M, T, B],
    ["FR-SPD-005", "After mismatchPings consecutive SENSOR_SUSPECT readings, the system shall raise a SPEED_SENSOR_MISMATCH event.", M, T, B],
    ["FR-SPD-006", "The system shall provide GET /api/speed/rules, PUT /api/speed/rules/{scope}, DELETE /api/speed/rules/{scope} (the default rule cannot be deleted) and GET /api/speed/active, and create the default rule on first start.", M, T, B],
  ], { objective: "OBJ-03", component: "gps-processing speed/; streaming/SpeedViolationService, SpeedRuleStore", tests: "SpeedViolationProcessorTest" });

  d.h2("Vehicle State and History (VST)");
  d.requirements("Vehicle state requirements", [
    ["FR-VST-001", "Consumer group gps-vehicle-state-service shall keep per vehicle in `gps:state:vehicle:{imei}`: heading, acceleration, smoothed (Kalman-filtered) position and distance, data-quality counters and moving and standing time.", M, T, B],
    ["FR-VST-002", "The Kalman filter shall use a constant-velocity model (GPS sigma 12 m, acceleration sigma 1.5), reject outliers by a Mahalanobis gate, and reset after 3 consecutive rejections.", M, T, B],
    ["FR-VST-003", "HARSH_ACCELERATION and HARSH_BRAKING events shall be computed only from consecutive readings less than 10 s apart.", M, T, B],
    ["FR-VST-004", "The history of each vehicle shall be appended to Redis stream `gps:history:{imei}`, trimmed to about 1000 entries (`gps.stream.history-length`).", M, T, B],
    ["FR-VST-005", "GET /api/vehicles/{imei}/state shall return all Redis keys of the vehicle, its history (`history` = number of entries), its events, the processing memory and the trip-detector state; GET /api/vehicles/states shall return every vehicle's state.", M, T, B],
  ], { objective: "OBJ-03", component: "gps-processing state/; streaming/VehicleStateService", tests: "VehicleStateProcessorTest, StateCodecsTest" });

  d.h2("Stoppage and Parking (STP)");
  d.requirements("Stoppage requirements", [
    ["FR-STP-001", "Consumer group gps-stoppage-service shall detect a stop when the vehicle stays within 100 m (`gps.stream.stoppage.radius-meters`) for at least 180 s, with reported speed at most 3 km/h and a low distance-over-time speed; the stop start shall be back-dated to arrival and its position shall be the centroid.", M, T, B],
    ["FR-STP-002", "A stop shall end only after 2 consecutive readings outside the radius (`exit-confirm-readings`); a silence longer than 1800 s shall end it; stops less than 180 s apart at the same place shall be merged.", M, T, B],
    ["FR-STP-003", "A stop with ignition ON shall raise a STOPPAGE event; a stop with ignition OFF shall raise a PARKED event without alarm. If the ignition changes during the stop, the event kind shall change accordingly.", M, T, B],
    ["FR-STP-004", "For each STOPPAGE the system shall run a traffic agent (fleet vehicles with engine on within 1 km, mostly slow, mean a jam; TomTom when a key is set) and a weather agent (Open-Meteo, 0.1° cell, cached 10 minutes), and set the verdict JUSTIFIED_TRAFFIC, JUSTIFIED_WEATHER or UNEXPLAINED (severity HIGH).", M, T, B],
    ["FR-STP-005", "If an agent's API does not answer within 3000 ms, the verdict shall be computed without it and the stoppage shall still be recorded.", M, T, B],
  ], { objective: "OBJ-03", component: "gps-processing stoppage/; streaming/StoppageService, StoppageContextAgents", tests: "StoppageDetectorTest" });

  d.h2("Ignition, Engine Hours and Idling (IGN)");
  d.requirements("Ignition requirements", [
    ["FR-IGN-001", "Consumer group gps-ignition-service shall use the device's ignition input when present, otherwise a virtual ignition: ON while moving, OFF after 300 s standing (`virtual-off-after-seconds`), debounced over 2 readings and back-dated.", M, T, B],
    ["FR-IGN-002", "The system shall raise IGNITION_ON and IGNITION_OFF events and accumulate engine hours, without crediting engine time across long silences.", M, T, B],
    ["FR-IGN-003", "The system shall raise an IDLING event when the engine is ON, speed is at most 3 km/h and the vehicle has been standing for more than 300 s.", M, T, B],
  ], { objective: "OBJ-03", component: "gps-processing ignition/; streaming/IgnitionService", tests: "IgnitionProcessorTest" });

  d.h2("Vehicle Events (EVT)");
  d.requirements("Vehicle event requirements", [
    ["FR-EVT-001", "Every event shall have an IMEI, vehicle, tenant, type (" + ref("event-types") + "), severity (INFO, LOW, MEDIUM, HIGH, CRITICAL), status (OPEN, CLOSED), start and end time, position, detecting consumer and details.", M, I, B],
    ["FR-EVT-002", "An episode shall be written when it starts (OPEN) and again when it ends (CLOSED) under the same event_key, as an upsert, so that a redelivery has no effect.", M, T, B],
    ["FR-EVT-003", "Events shall be written to table vehicle_event, to the vehicle's Redis list `gps:events:{imei}`, and to topic gps.vehicle-events (through the ingest journal).", M, T, B],
    ["FR-EVT-004", "The system shall provide GET /api/events (filters type, imei, status, limit ≤ 500) and GET /api/events/summary (per type: last 24 h and currently open).", M, T, B],
    ["FR-EVT-005", "Notification channels (SMS, e-mail, push), routing rules, escalations and quiet hours shall deliver events to people.", C, T, F],
  ], { objective: "OBJ-03", component: "events/VehicleEventRecorder, kafka/VehicleEventPublisher", tests: "Integration (TC)" });

  d.h2("Kafka Streams Normaliser and Analytics (KS)");
  d.requirements("Kafka Streams requirements", [
    ["FR-KS-001", "With `gps.streams.normalization=owner` (default), the Kafka Streams application gps-raw-frame-processing-service shall normalise raw frames with processing guarantee exactly_once_v2, and the listener for the same group shall not be created.", M, T, B],
    ["FR-KS-002", "In shadow mode, the listener shall normalise, Kafka Streams shall write the same results to gps.shadow.processed-positions and gps.shadow.rejected-frames, and GET /api/streams/parity shall compare both outputs reading by reading with verdicts MATCH, MISMATCH (with field), WARMUP, ONLY_LISTENER or ONLY_STREAMS.", M, T, B],
    ["FR-KS-003", "The analytics application gps-streams-analytics shall keep a table of each vehicle's latest state and five-minute event-time activity windows with a two-minute grace period, emit one final record per closed window to gps.vehicle-activity, and keep closed windows queryable for 6 hours.", M, T, B],
    ["FR-KS-004", "The system shall provide GET /api/streams/overview, /vehicles, /vehicles/{imei}, /fleet/activity?windows=n and /parity, and forward a query to the instance that owns the vehicle's partition (Interactive Queries).", M, T, B],
    ["FR-KS-005", "When a Kafka Streams task fails because a dependency (for example the database fallback) is unavailable, the record shall be retried by a replacement stream thread and shall not be dead-lettered.", M, T, B],
    ["FR-KS-006", "Health component `kafkaStreams` shall report DOWN when an application is in state ERROR (all its stream threads have died); REBALANCING shall count as UP. Interactive Queries shall answer HTTP 503 while an application is neither RUNNING nor REBALANCING.", S, T, B],
  ], { objective: "OBJ-02", component: "gps-streams; kafkastreams/", tests: "NormalizationTopologyTest, AnalyticsTopologyTest, DeadLetterTopologyTest, TopologyShapeTest" });

  d.h2("Dead Letters and Rejected-Frame Archive (DLQ)");
  d.figure(FIG("dead-letter.png"), "Dead-letter lifecycle", 5.8);
  d.requirements("Dead-letter requirements", [
    ["FR-DLQ-001", "A record that cannot be parsed, or that violates a database integrity rule, shall be parked in gps.dead-letters at once, with headers giving the original topic, partition, offset, consumer group and exception.", M, T, B],
    ["FR-DLQ-002", "A failure caused by an unavailable dependency (PostgreSQL, Redis, Kafka, network) shall be retried with back-off 1 s, 2 s, 4 s … capped at 60 s, without limit, and shall never be dead-lettered.", M, T, B],
    ["FR-DLQ-003", "Any other failure shall be tried 3 times and then dead-lettered.", M, T, B],
    ["FR-DLQ-004", "Consumer group gps-dead-letter-archive shall store every dead letter once in table dead_letter with status PARKED.", M, T, B],
    ["FR-DLQ-005", "Replay (POST /api/dead-letters/{id}/replay, or /replay?topic= for the oldest parked) shall send the record to its original topic and set status REPLAYED and replay_count + 1 only after Kafka confirms; otherwise the row shall stay PARKED. A DISCARDED row shall not be replayed.", M, T, B],
    ["FR-DLQ-006", "Discard (POST /api/dead-letters/{id}/discard) shall require a non-blank note, keep it in resolution_note and set status DISCARDED; only PARKED rows can be discarded.", M, T, B],
    ["FR-DLQ-007", "Consumer group gps-rejected-frame-logger shall store every rejected frame once in table rejected_frame (reason, original payload, payload hash) and log it with its reason.", M, T, B],
    ["FR-DLQ-008", "Rows of dead_letter and rejected_frame shall never be deleted, except by the demo reset (FR-ADM-002).", M, I, B],
  ], { objective: "OBJ-02", component: "kafka/KafkaRetrySettings, DeadLetter*; archive/", tests: "DeadLetterHandlingTest, DeadLetterTopologyTest" });

  d.h2("Data-Safety Report (SAFE)");
  d.requirements("Data-safety requirements", [
    ["FR-SAFE-001", "GET /api/data-safety shall report, with status OK, WARN or FAIL and an explanation, these checks: retention (a month of data in Kafka), no-loss (no consumer group behind the log start), stopped groups, replication, broker fsync, offsets retention, topic auto-creation disabled, producer acks, ingest journal backlog, and parked dead letters.", M, T, B],
    ["FR-SAFE-002", "When a consumer group's committed offset is below the log-start offset of any partition (data deleted before it was read), the no-loss check shall be FAIL.", M, T, B],
    ["FR-SAFE-003", "Health component `dataSafety` shall report DOWN while any check is FAIL.", M, T, B],
  ], { objective: "OBJ-02", component: "safety/DataSafetyChecks, DataSafetyService", tests: "DataSafetyChecksTest" });

  d.h2("Reports (RPT)");
  d.p("Reports read the summary table vehicle_day_summary (one row per vehicle per day), never raw positions, except the vehicle-day sheet of one vehicle. Fuel (litres) = distance ÷ mileage + idling hours × idle burn + (km above the economical speed ÷ mileage) × penalty %. Savings = (idling fuel + extra fuel for fast driving) × price per litre. CO₂ = litres × kg CO₂ per litre.");
  d.requirements("Report requirements", [
    ["FR-RPT-001", "A report day shall run from 00:00 to 24:00 Asia/Kolkata (`gps.reports.zone`).", M, T, B],
    ["FR-RPT-002", "The summary job shall rebuild today's and yesterday's rows every 5 minutes (`gps.reports.refresh-every`), summarise up to 62 earlier days that have positions once after start (`backfill-days`), and rebuild on POST /api/reports/refresh.", M, T, B],
    ["FR-RPT-003", "A silence longer than 600 s between moving readings, or 3600 s between standing readings, shall count neither as driving nor as standing time.", M, T, B],
    ["FR-RPT-004", "GET /api/reports/day?date&tenant&imei shall return every vehicle's day (distance, driving and standing time, stops, idling, maximum speed, over-speed and harsh events, fuel, cost, savings) and fleet totals.", M, T, B],
    ["FR-RPT-005", "GET /api/reports/month?month&tenant&imei shall return each vehicle's month and the fleet per day.", M, T, B],
    ["FR-RPT-006", "GET /api/reports/vehicle-day shall return one vehicle's day in time order: every trip (km, speed) and stop (duration, place, engine on or off, idling).", M, T, B],
    ["FR-RPT-007", "Every figure shown in a report shall have a “How is this calculated?” section giving the formula with that figure's own values.", M, D, B],
    ["FR-RPT-008", "Cost assumptions (fuel type, price per litre, mileage, idle burn, economical speed, fast-driving penalty, CO₂ factor) shall be read and changed through GET and PUT /api/reports/assumptions and stored in report_setting; prices shall be applied when a report is read, so that a change re-prices past reports immediately.", M, T, B],
    ["FR-RPT-009", "PUT /api/reports/assumptions shall reject a negative price, rate, percentage or CO₂ factor, a mileage of 0 or less, and an economical speed outside 10 to 150 km/h, with a message.", M, T, B],
    ["FR-RPT-010", "Clear database (POST /api/admin/reset?keepReports=true) shall first freeze the live summary rows into a new generation greater than 0 and then empty the raw tables; reports shall add up all generations of a day. With keepReports=false the summaries shall be emptied too.", M, T, B],
    ["FR-RPT-011", "Day and month reports shall be printable to PDF from the browser and downloadable as CSV.", S, D, B],
  ], { objective: "OBJ-05", component: "report/; gps-processing report/FuelEstimator; console Reports", tests: "FuelEstimatorTest" });

  d.h2("External Connections Management (CON)");
  d.requirements("Connections requirements", [
    ["FR-CON-001", "GET /api/connections shall show the Redis in use, the Valhalla URL and the HP ingestion API with reachability.", M, T, B],
    ["FR-CON-002", "POST /api/connections/redis/test shall report ping, memory, eviction policy and the keys present in the selected database; PUT /api/connections/redis shall switch the Redis in use without a restart and copy the configuration keys (`gps:config:*`).", M, T, B],
    ["FR-CON-003", "A switch to a Redis database that contains another program's keys (for example `gps:points`, `devices`) shall be refused with an explanation.", M, T, B],
    ["FR-CON-004", "The chosen Redis and Valhalla URLs shall be stored in connection_setting and win over application.yml defaults at the next start.", M, T, B],
    ["FR-CON-005", "POST /api/connections/valhalla/test shall compute a short sample route and show it; POST /api/connections/ingestion-api/test shall show the API's health.", S, T, B],
    ["FR-CON-006", "Redis calls shall time out after 5 s (connect 3 s) and HTTP calls after 5 s, so that an unreachable server never blocks the console.", M, T, B],
  ], { objective: "OBJ-06", component: "connections/, redis/SwitchableRedisConnectionFactory", tests: "RedisTargetTest, JsonHttpTest" });

  d.h2("Fleet Simulator (SIM)");
  d.requirements("Simulator requirements", [
    ["FR-SIM-001", "The simulator shall read the latest trip of up to 150 distinct devices (`vehicle-count`) that has at least 200 pings from smart_truck, and replay up to 500 pings per vehicle.", M, T, B],
    ["FR-SIM-002", "In faithful timing, each ping shall keep its real interval; the replay shall start 120 minutes in the past and run 30 times faster until it reaches the present, then in real time.", M, T, B],
    ["FR-SIM-003", "At the end of a trip a vehicle shall drive the trip back in reverse, repeating for ever when `repeat-forever` is true; a restarted fleet shall resume after the newest stored reading.", M, T, B],
    ["FR-SIM-004", "The simulator shall give an ignition input to 50 % of devices, an odometer to 50 %, and make 25 % of drivers keep the engine running while standing.", S, T, B],
    ["FR-SIM-005", "The system shall provide POST /api/simulator/start (vehicleCount, pingsPerVehicle), POST /api/simulator/stop and GET /api/simulator/status, and show a failure reason (for example Kafka unavailable or access denied) in the status.", M, T, B],
  ], { objective: "OBJ-06", component: "simulator/; smarttruck/SmartTruckGpsReader", tests: "Integration (TC)" });

  d.h2("Monitoring, Engine Dashboard and Administration (ADM)");
  d.requirements("Monitoring and administration requirements", [
    ["FR-ADM-001", "The engine dashboard (port 8080) and GET /api/engine/overview shall show, refreshed every 2 s: each producer's and consumer's messages per second (10-second average), 60-second history, totals and failures; each topic partition's offsets; each consumer group's state, partition assignment and lag.", M, T, B],
    ["FR-ADM-002", "Demo reset (POST /api/admin/reset) shall stop the fleet, pause consumers, delete the platform topics' records, truncate the telemetry and archive tables (keeping master data, speed rules and report settings), delete only the platform's telemetry keys in Redis, reset the Kafka Streams applications, resume consumers and optionally restart the fleet.", M, T, B],
    ["FR-ADM-003", "Demo reset shall be refused when `gps.admin.reset-enabled` is false.", M, T, B],
    ["FR-ADM-004", "Every log line written while a record is handled shall carry the stage, IMEI, partition and offset; an ENGINE summary line shall be written every 30 s.", M, I, B],
    ["FR-ADM-005", "GET /api/engine/devices?imei= shall filter devices by IMEI fragment, and GET /api/engine/devices/{imei} shall show the device's partition, per-stage counts and live position.", S, T, B],
  ], { objective: "OBJ-06", component: "engine/, admin/, monitoring/", tests: "StageCountersTest" });

  d.h2("Console Navigation and Presentation (UI)");
  d.requirements("Console requirements", [
    ["FR-UI-001", "The console shall offer the sections Live, Processing, Alerts, Storage, Analytics, Notifications and Developer in a top bar, plus Reports, each with its own left menu.", M, D, B],
    ["FR-UI-002", "Each card shall state its data source as “Live” (from the service) or “Example data” (module not built); a module that is not built shall be marked “Planned” in the menu.", M, I, B],
    ["FR-UI-003", "The console shall offer a command palette (Ctrl+K), light and dark appearance, five accent colours, density, and a switch to pause live updates; preferences shall persist in the browser.", S, D, B],
    ["FR-UI-004", "When the service is unreachable, each page shall show an error state naming the failed call instead of stale or empty data.", M, T, P],
  ], { objective: "OBJ-06", component: "gps-dashboard-ui", tests: "Manual (TC)" });

  d.h2("Business Rules");
  d.p("Business rules are policies of the business that the software enforces. They apply in every module.");
  d.requirements("Business rules", [
    ["BR-001", "A device shall be installed in at most one vehicle at any instant (enforced by an exclusion constraint on device_assignment).", M, T, B],
    ["BR-002", "A vehicle registration number shall be unique across the platform; a vehicle without an owner tenant is a market vehicle.", M, T, B],
    ["BR-003", "A tenant code shall match ^[a-z0-9][a-z0-9_-]*$, be at most 40 characters and never be reused; tenant status is ACTIVE, SUSPENDED or CLOSED.", M, T, B],
    ["BR-004", "A reading belongs to the vehicle and tenant assigned at the reading's recorded time.", M, T, B],
    ["BR-005", "A device has at most one reading per instant: (imei, recorded_at) is unique.", M, T, B],
    ["BR-006", "Nothing received is discarded silently: each frame ends as a stored position, an archived rejection, or an archived dead letter.", M, A, B],
    ["BR-007", "A goods vehicle's reported speed above 150 km/h is a device error, not an over-speed.", M, T, B],
    ["BR-008", "Speed-limit precedence is vehicle, then tenant, then the platform default.", M, T, B],
    ["BR-009", "A stop with the engine off is parking and raises no alarm; a stop with the engine on is a stoppage and is analysed for its cause.", M, T, B],
    ["BR-010", "Over-speed events are operational information for the fleet owner; the system shall not present them as legal traffic offences (speed limits differ by road, state and vehicle class).", M, I, B],
    ["BR-011", "Trip distance comes only from in-order readings and is never negative; late readings are counted but never change a trip's distance.", M, T, B],
    ["BR-012", "Configuration (speed rules, cost assumptions, connections) and master data survive Clear database and the demo reset.", M, T, B],
  ], { objective: "OBJ-02", component: "Database constraints and processing rules", tests: "Schema and unit tests" });

  d.h2("Exceptional Conditions Summary");
  d.p("The table below collects how the system behaves in exceptional conditions, with the requirements that govern each case.");
  d.table("Exceptional conditions and required behaviour", [
    { header: "Condition", width: 2600 }, { header: "Required behaviour", width: 4926 }, { header: "Requirements", width: 1500 },
  ], [
    ["Invalid input (bad JSON, missing or out-of-range field)", "Reject with a reason, archive in rejected_frame, continue with the next frame.", "FR-VAL-*, FR-DLQ-007"],
    ["Duplicate delivery from Kafka", "Rejected by the normaliser or ignored by idempotent writes; no second row.", "FR-NRM-001, FR-STO-002, FR-TRP-008, FR-EVT-002"],
    ["Out-of-order reading", "Accepted for history with zero distance; never becomes the newest; does not change trips or live position.", "FR-NRM-003, FR-LIVE-002, FR-TRP-007"],
    ["Reading older than 24 hours", "Rejected as stale with its payload.", "FR-NRM-002"],
    ["Device clock in the future", "Rejected when more than 5 minutes ahead.", "FR-VAL-004"],
    ["Kafka down", "Frames stay in the journal; the caller is not blocked; sent in order later.", "FR-ING-004"],
    ["PostgreSQL or Redis down", "Consumers retry with back-off without limit; records wait in Kafka.", "FR-DLQ-002"],
    ["Unprocessable record (bug)", "Three tries, then parked as a dead letter; later records are not blocked.", "FR-DLQ-003"],
    ["Service crash or restart", "Uncommitted records are delivered again; journal resent; state restored.", "FR-ING-005, FR-NRM-011, NFR-REL-003"],
    ["Consumer behind Kafka retention", "Data-safety report FAIL; health DOWN; loss is never hidden.", "FR-SAFE-002, FR-SAFE-003"],
    ["Device unassigned at reading time", "Position stored without vehicle and tenant; WARN log.", "FR-MD-003"],
    ["Device silent for a long time", "Trip closed at last reading; stop or episode ended.", "FR-TRP-003, FR-SPD-003, FR-STP-002"],
    ["External API slow or down (weather, traffic, Valhalla)", "Time out; continue without it.", "FR-STP-005, FR-CON-006, CON-008"],
    ["No permission (no session)", "Redirect to sign-in, or HTTP 401 for /api.", "FR-AUTH-001"],
    ["Repeated wrong passwords", "Lock the client address for 60 s.", "FR-AUTH-005"],
    ["Two operators change the same rule", "Last write wins; the change is logged (proposed audit).", "NFR-SEC-009 (Proposed)"],
    ["Capacity limit reached", "Lag grows but nothing is lost while within 31 days; data-safety report warns.", "NFR-CAP-002, FR-SAFE-001"],
  ], { size: 16 });
}

// ------------------------------------------------------------------------------------------------ 4 Non-functional
function nonFunctionalRequirements(d) {
  d.h1("Non-Functional Requirements");
  d.p("Unless stated otherwise, measurements are made on the reference deployment (docker-compose.yml on one host with at least 4 CPU cores, 16 GB RAM and an SSD) at the load defined below. Thresholds marked “Proposed” are engineering targets to be confirmed with stakeholders after a baseline test (OI-01).");
  d.keyValue("Load definitions", [
    ["Normal operating load", "150 vehicles in faithful timing, after catch-up: about 2.5 frames per second."],
    ["Design peak load", "150 vehicles during catch-up (30 times real time): about 75 frames per second."],
    ["Measurement interval", "15 minutes of steady load after a 5-minute warm-up, unless stated otherwise."],
  ]);

  d.h2("Performance");
  d.requirements("Performance requirements", [
    ["NFR-PERF-001", "At design peak load, the lag of every consumer group, sampled every second, shall be at most 225 records (3 seconds of input) at the 95th percentile over the measurement interval, with zero failures.", M, T, P],
    ["NFR-PERF-002", "At design peak load, for 95 % of stored positions, stored_at − processed_at shall be at most 1 second.", M, T, P],
    ["NFR-PERF-003", "At design peak load, for 95 % of frames, the vehicle's live position in Redis shall reflect the frame within 2 seconds of the frame being processed.", M, T, P],
    ["NFR-PERF-004", "GET /api/reports/day for 150 vehicles shall respond within 2 s and GET /api/reports/month within 3 s at the 95th percentile, with the summary rows already built.", M, T, P],
    ["NFR-PERF-005", "Every REST query endpoint other than reports shall respond within 1 s at the 95th percentile at normal operating load.", S, T, P],
    ["NFR-PERF-006", "The demo reset shall complete within 30 s (observed: about 6 s).", S, T, B],
  ], { objective: "OBJ-01", component: "Whole pipeline", tests: "Performance test (TC)" });

  d.h2("Capacity and Scalability");
  d.requirements("Capacity requirements", [
    ["NFR-CAP-001", "The platform shall support 150 concurrently reporting vehicles at design peak load on the reference deployment.", M, T, B],
    ["NFR-CAP-002", "Kafka shall retain 31 days of all platform topics, deleted by age only; disk shall be sized for 31 days at design peak load plus 30 % head-room.", M, A, B],
    ["NFR-CAP-003", "Throughput shall scale by adding partitions and service instances; one consumer per partition per group (3 by default) is the parallelism limit and shall be documented in the deployment guide.", S, A, B],
    ["NFR-CAP-004", "Reports shall read at most one summary row per vehicle per day (about 4,650 rows for a 150-vehicle month), however many positions exist.", M, I, B],
    ["NFR-CAP-005", "The platform should support 1,500 vehicles at the same per-vehicle rate on a 3-broker cluster with 12 partitions per topic.", C, T, F],
  ], { objective: "OBJ-07", component: "Kafka, report/", tests: "Analysis, performance test" });

  d.h2("Reliability and Data Integrity");
  d.requirements("Reliability requirements", [
    ["NFR-REL-001", "No frame acknowledged by the ingest journal shall be lost because of a Kafka outage, service crash, kill -9 or power cut (RPO = 0 for acknowledged frames), provided the host's disk survives.", M, T, B],
    ["NFR-REL-002", "Producers shall use acks=all and idempotence; consumers shall commit offsets only after their work is done (at-least-once) and read with isolation read_committed.", M, I, B],
    ["NFR-REL-003", "After a service restart, processing shall resume from the committed offsets within 2 minutes, without loss and without duplicate rows.", M, T, P],
    ["NFR-REL-004", "With docker-compose.ha.yml (3 brokers, replication factor 3, min.insync.replicas 2), the loss of one broker shall not stop ingestion or processing and shall lose no acknowledged record.", S, T, B],
    ["NFR-REL-005", "The database shall be recoverable to any point within the last 31 days by restoring a backup and re-consuming the Kafka topics from that point (see DR-008).", S, D, P],
  ], { objective: "OBJ-02", component: "journal/, kafka/, docker-compose", tests: "DurableKafkaSenderTest, IngestJournalTest, failure-injection test (TC)" });

  d.h2("Availability");
  d.requirements("Availability requirements", [
    ["NFR-AVL-001", "In a production deployment, the console and the ingestion path should each be available 99.5 % of each calendar month, excluding announced maintenance of at most 4 hours per month, measured by an external probe of /actuator/health every minute.", S, T, P],
    ["NFR-AVL-002", "All containers shall restart automatically (`restart: always`) and the service shall start only after PostgreSQL, Redis and Kafka report healthy.", M, I, B],
    ["NFR-AVL-003", "/actuator/health shall expose components db, redis, kafkaStreams and dataSafety.", M, T, B],
  ], { objective: "OBJ-06", component: "docker-compose.yml, Actuator", tests: "Inspection, health test" });

  d.h2("Security");
  d.requirements("Security requirements", [
    ["NFR-SEC-001", "No password, key or token shall be committed to the repository; .gitignore shall exclude local-secrets.properties and .env.", M, I, B],
    ["NFR-SEC-002", "The service container shall run as a non-root user (uid 1001) from a JRE-only image.", M, I, B],
    ["NFR-SEC-003", "The Actuator shall expose only health and info over HTTP.", M, I, B],
    ["NFR-SEC-004", "Session cookies shall be HttpOnly, SameSite=Lax, and Secure when served over HTTPS.", M, T, B],
    ["NFR-SEC-005", "The smart_truck connection shall use a read-only user and a read-only pool.", M, I, B],
    ["NFR-SEC-006", "Outside local development, the console shall refuse to start when DASHBOARD_PASSWORD or AUTH_SECRET is unset or equals the development default.", M, T, P],
    ["NFR-SEC-007", "The service API (port 8080) shall not be reachable from outside the host or private network, or shall require authentication (for example a service token issued to the console).", M, T, P],
    ["NFR-SEC-008", "All traffic between browser and console, and across hosts (HP home server), shall use TLS 1.2 or later in production.", M, T, P],
    ["NFR-SEC-009", "Every state-changing operation (rules, assumptions, connections, replay, discard, clear, reset, simulator) should be written to an audit log with user, time, old and new value, kept for at least 180 days.", S, T, P],
    ["NFR-SEC-010", "Demo reset shall be disabled (`gps.admin.reset-enabled=false`) in every non-demo deployment.", M, I, P],
    ["NFR-SEC-011", "Dependencies should be scanned for known vulnerabilities before each release; no release shall ship with a known critical vulnerability without a recorded exception.", S, I, P],
  ], { objective: "OBJ-08", component: "Deployment, console, service", tests: "Security test (TC), inspection" });

  d.h2("Privacy");
  d.requirements("Privacy requirements", [
    ["NFR-PRV-001", "Location data linked to an identifiable driver shall be treated as personal data (see REG-001); access shall be limited to signed-in users.", M, I, B],
    ["NFR-PRV-002", "The repository, documentation and examples shall contain only dummy IMEIs, registration numbers and company names.", M, I, B],
    ["NFR-PRV-003", "Retention periods for positions, events and archives shall be defined per tenant and enforced by a scheduled deletion job (OI-04).", S, T, P],
    ["NFR-PRV-004", "Personal data shall not be placed in URL query strings that are logged, other than device and vehicle identifiers.", S, I, P],
  ], { objective: "OBJ-08", component: "Whole system", tests: "Inspection" });

  d.h2("Usability and Accessibility");
  d.requirements("Usability requirements", [
    ["NFR-USE-001", "Every computed figure in Reports shall have an explanation of its formula with its own values (FR-RPT-007).", M, D, B],
    ["NFR-USE-002", "Every rejection, dead letter and failure shall be shown with a plain-English reason that names the field and value at fault.", M, I, B],
    ["NFR-USE-003", "A new operator should be able to find a named vehicle's live position, its trips for yesterday and its open alerts within 3 minutes without help, in a usability test with at least 5 users.", S, T, P],
    ["NFR-USE-004", "The console should meet WCAG 2.1 level AA for colour contrast, keyboard operation and text alternatives in both light and dark appearance.", S, T, P],
  ], { objective: "OBJ-05", component: "gps-dashboard-ui", tests: "Usability test, accessibility audit" });

  d.h2("Maintainability and Observability");
  d.requirements("Maintainability requirements", [
    ["NFR-MNT-001", "Every processing rule in gps-processing and every topology in gps-streams shall have unit tests that run without Kafka, a database or Spring (`mvnw test`).", M, I, B],
    ["NFR-MNT-002", "Each Maven module shall have one responsibility (" + ref("modules") + "); Kafka code and business logic shall meet only through handler interfaces.", M, I, B],
    ["NFR-MNT-003", "Every consumer stage shall publish counters (total, per second, failures, per IMEI), mirrored to Redis every 5 s.", M, T, B],
    ["NFR-MNT-004", "Line coverage of gps-processing and gps-streams should be at least 80 %, measured by JaCoCo in the build.", S, T, P],
    ["NFR-MNT-005", "Service logs should be shipped to a central store and kept for 180 days (see REG-003).", S, I, P],
  ], { objective: "OBJ-06", component: "Build, monitoring/", tests: "Inspection, CI" });

  d.h2("Compatibility and Portability");
  d.requirements("Compatibility requirements", [
    ["NFR-CMP-001", "The platform shall run with `docker compose up -d --build` on any Docker host, and natively on Windows 11 with the scripts in infrastructure/windows.", M, D, B],
    ["NFR-CMP-002", "The console shall work in the two most recent major versions of Chrome, Edge and Firefox at 1280 × 720 pixels or larger.", M, T, P],
    ["NFR-CMP-003", "All REST endpoints shall exchange JSON in UTF-8 with ISO-8601 UTC timestamps.", M, I, B],
  ], { objective: "OBJ-07", component: "Deployment, API", tests: "Demonstration" });

  d.h2("Regulatory and Compliance Requirements");
  d.p("The following regulations apply to a telematics platform that processes vehicle location in India. Applicability and the exact obligations shall be confirmed by legal counsel before production use (OI-05). These requirements do not replace legal advice.");
  d.requirements("Regulatory requirements", [
    ["REG-001", "Digital Personal Data Protection Act, 2023 [R12]: where positions can identify a driver, the operating tenant (data fiduciary) shall give notice of the purpose, obtain a lawful basis, allow access and erasure requests, and erase data when the purpose is served; the platform shall provide per-tenant export and deletion of a vehicle's or driver's data.", M, I, P],
    ["REG-002", "IT Act 2000, section 43A and SPDI Rules 2011 [R13]: the operator shall implement and document reasonable security practices (for example an ISO/IEC 27001-aligned control set) covering NFR-SEC-001 to NFR-SEC-011.", M, I, P],
    ["REG-003", "CERT-In Directions of 28 April 2022 [R14]: logs of the platform's ICT systems shall be kept securely for a rolling 180 days within Indian jurisdiction, and cyber-security incidents shall be reportable to CERT-In within 6 hours of being noticed.", M, I, P],
    ["REG-004", "AIS-140 [R15]: when devices fitted under the AIS-140 mandate are connected, the device parser shall accept their data format; the platform is not an emergency-response or state-backend system and shall not be presented as one.", S, I, F],
    ["REG-005", "Third-party terms: before commercial use, the operator shall hold a licence that permits it for the Open-Meteo API and the TomTom API, shall attribute OpenStreetMap data (ODbL) wherever Valhalla routes or map data are shown, and shall review the Redis licence (Redis 7.4 and later are not BSD-licensed) for hosted use.", M, I, P],
    ["REG-006", "Speed limits: default and per-tenant speed rules shall be configurable to the limits that apply to the fleet's vehicle class and roads; the default of 60 km/h is a company policy value, not a statutory limit (BR-010).", M, I, B],
  ], { objective: "OBJ-08", component: "Operations, legal", tests: "Compliance review" });
}

// ------------------------------------------------------------------------------------------------ 5 Interfaces
function interfaceRequirements(d) {
  d.h1("External Interface Requirements");
  d.h2("User Interfaces");
  d.table("Console sections", [{ header: "Section", width: 1700 }, { header: "Main pages", width: 7326 }], [
    ["Live", "Fleet overview, live map, vehicle list, vehicle inspector, throughput, event stream, fleet simulator."],
    ["Processing", "Pipeline overview, consumer groups, Kafka Streams (topology and tasks, vehicle table, activity windows, shadow parity), speed violation (live episodes, rules, distance cross-check, events), vehicle state, stoppage and ignition, geofencing*, waypoints*, cumulative distance."],
    ["Alerts", "All alerts, escalations*, over-speed (dual verification, device log audit, alert policy), trip detection (trips, master table lookup, stops), route deviation*."],
    ["Storage", "Ingestion overview, schema validation, rejected frames, PostgreSQL, ClickHouse*, workers and back-pressure, data safety, dead letters."],
    ["Analytics", "Live telemetry, latency, violations and anomalies, trip efficiency."],
    ["Notifications*", "Inbox, channels, routing rules, delivery log, quiet hours and digest."],
    ["Developer", "Engine overview, API reference, connections, ingestion, modules, processors, rules, settings, demo tools."],
    ["Reports", "Day report, month report, vehicle day sheet, fuel and savings, cost assumptions, clear database."],
  ]);
  d.p("* Planned module: pages show example data, labelled as such (FR-UI-002).");
  d.requirements("User interface requirements", [
    ["IFR-UI-001", "The console shall be served on port 3000 and forward /api/* to the service at GPS_API_URL (default http://localhost:8080), adding no-store caching headers.", M, T, B],
    ["IFR-UI-002", "Destructive actions (clear database, demo reset, discard, connection switch) shall state what will be lost or kept and ask for an explicit confirmation before they run.", M, D, P],
  ], { objective: "OBJ-06", component: "gps-dashboard-ui", tests: "Manual (TC)" });

  d.h2("Software Interfaces: REST API");
  d.p("Base URL: http://<service-host>:8080. Content type application/json; UTF-8. In the baseline, errors are returned as JSON {\"error\": message} with HTTP 400 (invalid report or connection parameter), 409 (operation not possible in the current state, for example discarding a dead letter that is not parked, or a refused Redis switch) or 503 (Kafka Streams state not yet available); the console proxy answers 502 when the service cannot be reached. Other failures fall back to the framework's default error response; uniform handling is required by IFR-API-001. The API is unversioned; a version prefix is proposed in OI-06.");
  d.table("REST endpoints", [
    { header: "Method", width: 800, align: "center" }, { header: "Path", width: 3600 }, { header: "Purpose", width: 4626 },
  ], [
    ["GET", "/api/engine/overview", "Producers, topics, consumers, lag (dashboard)"],
    ["GET", "/api/engine/devices?imei=", "Devices filtered by IMEI fragment"],
    ["GET", "/api/engine/devices/{imei}", "One device: partition, per-stage counts, live position"],
    ["POST", "/api/simulator/start?vehicleCount&pingsPerVehicle", "Start the fleet"],
    ["POST", "/api/simulator/stop", "Stop the fleet"],
    ["GET", "/api/simulator/status", "Simulator state and failure reason"],
    ["POST", "/api/simulator/send-single-frame", "Send one raw frame (body: raw frame JSON)"],
    ["GET", "/api/gps-positions/count", "Number of stored positions"],
    ["GET", "/api/gps-positions/recently-stored", "Newest 20 stored positions"],
    ["GET", "/api/vehicles/{registrationNo}/latest-position", "Latest stored position of a vehicle"],
    ["GET", "/api/trips?imei&from&to", "Trips overlapping the period (default last 24 h)"],
    ["GET", "/api/stops?imei&from&to", "Stops overlapping the period"],
    ["GET", "/api/trips/count", "Open and closed trips and stops"],
    ["GET", "/api/events?type&imei&status&limit", "Vehicle events"],
    ["GET", "/api/events/summary", "Events per type: last 24 h and open"],
    ["GET / PUT / DELETE", "/api/speed/rules, /api/speed/rules/{scope}", "Speed rules (default, tenant:{code}, vehicle:{reg})"],
    ["GET", "/api/speed/active", "Vehicles currently above their limit"],
    ["GET", "/api/vehicles/{imei}/state?history=n", "Full tracking state of a vehicle"],
    ["GET", "/api/vehicles/states", "Vehicle-state hash of every vehicle"],
    ["GET", "/api/streams/overview | vehicles | vehicles/{imei} | fleet/activity | parity", "Kafka Streams state (Interactive Queries)"],
    ["GET", "/api/data-safety", "Data-safety report"],
    ["GET", "/api/dead-letters?status&topic, /summary, /{id}", "Dead letters"],
    ["POST", "/api/dead-letters/{id}/replay, /replay?topic=", "Replay one or the oldest parked"],
    ["POST", "/api/dead-letters/{id}/discard?note=", "Discard with a note"],
    ["GET", "/api/rejected-frames?imei, /summary", "Rejected-frame archive"],
    ["GET / PUT / POST", "/api/connections, /redis, /redis/test, /valhalla, /valhalla/test, /ingestion-api/test", "External connections"],
    ["GET", "/api/reports/filters | day | month | vehicle-day", "Reports"],
    ["POST", "/api/reports/refresh", "Rebuild summaries"],
    ["GET / PUT", "/api/reports/assumptions", "Cost assumptions"],
    ["POST", "/api/admin/reset?keepReports&restartFleet", "Clear database / demo reset"],
    ["GET", "/actuator/health", "Health: db, redis, kafkaStreams, dataSafety"],
  ], { size: 16 });
  d.requirements("API requirements", [
    ["IFR-API-001", "Every endpoint shall validate its parameters and answer an invalid one with HTTP 400 and a JSON message naming the parameter; unexpected failures shall return HTTP 500 with a correlation id and no stack trace.", M, T, P],
    ["IFR-API-002", "Time parameters (from, to) shall be ISO-8601 instants; date and month parameters shall be ISO-8601 local dates in the report zone.", M, T, B],
    ["IFR-API-003", "List endpoints shall limit their result size (for example events: at most 500) to protect the service.", M, T, B],
    ["IFR-API-004", "Discard shall be safe to retry: repeating it on a row that is no longer PARKED shall change nothing and answer HTTP 409. Replay of a REPLAYED row sends the record again and increases replay_count, so a client shall read the row's status before retrying a replay whose outcome is unknown.", M, T, B],
    ["IFR-API-005", "A machine-readable API description (OpenAPI 3) should be published with each release.", S, I, P],
  ], { objective: "OBJ-06", component: "REST controllers", tests: "API tests (TC)" });

  d.h2("Software Interfaces: Kafka Topics and Messages");
  d.table("Kafka topics", [
    { header: "Topic", width: 2800 }, { header: "Value", width: 2200 }, { header: "Producers", width: 1900 }, { header: "Consumer groups", width: 2126 },
  ], [
    ["gps.raw-device-frames", "Raw frame JSON", "Simulator / parser via journal", "gps-raw-frame-processing-service"],
    ["gps.processed-positions", "Processed position JSON (EOS)", "Normaliser", "storage, live-location, trip, speed, state, stoppage, ignition, gps-streams-analytics"],
    ["gps.rejected-frames", "Rejected frame JSON (EOS)", "Normaliser", "gps-rejected-frame-logger"],
    ["gps.vehicle-events", "Vehicle event JSON", "Stream consumers via journal", "(downstream applications)"],
    ["gps.vehicle-activity", "Activity window JSON", "gps-streams-analytics", "(downstream applications)"],
    ["gps.dead-letters", "Original bytes + error headers", "All consumers and Streams apps", "gps-dead-letter-archive"],
    ["gps.shadow.processed-positions, gps.shadow.rejected-frames", "As above", "Shadow normaliser", "Parity join"],
  ], { size: 16 });
  d.p("All topics: key = IMEI (UTF-8 string), 3 partitions, retention 31 days by time only, compression zstd. Changelog topics of the Kafka Streams stores are compacted.");
  d.table("Raw frame (topic gps.raw-device-frames)", [
    { header: "Field", width: 1900 }, { header: "Type", width: 1500 }, { header: "Required", width: 1100, align: "center" }, { header: "Rule", width: 4526 },
  ], [
    ["imei", "string", "Yes", "Non-blank; equals the record key"],
    ["recordedAt", "ISO-8601 instant", "Yes", "Not more than 5 minutes in the future"],
    ["latitude", "number", "Yes", "−90 to 90; not 0 together with longitude 0"],
    ["longitude", "number", "Yes", "−180 to 180"],
    ["speedKmph", "integer", "Yes", "0 to 150"],
    ["ignition", "boolean", "No", "Device ignition input when wired"],
    ["odometerMeters", "integer", "No", "Device odometer; used for distance when believable"],
  ], { label: "raw-frame" });
  d.table("Processed position (topic gps.processed-positions)", [
    { header: "Field", width: 2700 }, { header: "Type", width: 1600 }, { header: "Meaning", width: 4726 },
  ], [
    ["imei, recordedAt, latitude, longitude, speedKmph, ignition, odometerMeters", "as raw frame", "Copied from the frame (position may be jitter-clamped)"],
    ["movementStatus", "MOVING | STOPPED", "FR-NRM-009"],
    ["distanceFromPreviousMeters", "number ≥ 0", "Counted distance of this reading"],
    ["secondsSincePreviousReading", "integer ≥ 0", "0 for first and out-of-order readings"],
    ["arrivedOutOfOrder", "boolean", "FR-NRM-003"],
    ["totalDistanceMeters", "number", "Running total per device"],
    ["distanceSource", "enum", "FIRST_READING, GPS, DEVICE_ODOMETER, JITTER_FILTERED, JUMP_FILTERED, OUT_OF_ORDER"],
    ["impliedSpeedKmph", "number", "Distance ÷ time to the previous reading"],
    ["processedAt", "ISO-8601 instant", "When the normaliser produced it"],
  ], { label: "processed" });
  d.table("Rejected frame (topic gps.rejected-frames)", [
    { header: "Field", width: 2700 }, { header: "Type", width: 1600 }, { header: "Meaning", width: 4726 },
  ], [
    ["imei", "string or null", "IMEI if it could be read"],
    ["originalPayload", "string", "The frame exactly as received"],
    ["rejectionReason", "string", "Reason text from Section 3.4 or 3.5"],
    ["rejectedAt", "ISO-8601 instant", "Time of rejection"],
  ]);
  d.table("Vehicle event types", [{ header: "Type", width: 2600 }, { header: "Raised by", width: 2800 }, { header: "Meaning", width: 3626 }], [
    ["OVERSPEED", "gps-speed-violation-service", "Sustained speed above the applicable limit"],
    ["SPEED_SENSOR_MISMATCH", "gps-speed-violation-service", "Speedometer disagrees with GPS speed repeatedly"],
    ["STOPPAGE", "gps-stoppage-service", "Stop with engine on; cause verdict attached"],
    ["PARKED", "gps-stoppage-service", "Stop with engine off"],
    ["IDLING", "gps-ignition-service", "Engine on while standing beyond the limit"],
    ["IGNITION_ON / IGNITION_OFF", "gps-ignition-service", "Engine state change (device or virtual)"],
    ["HARSH_ACCELERATION / HARSH_BRAKING", "gps-vehicle-state-service", "Acceleration beyond threshold between close readings"],
  ], { label: "event-types" });
  d.requirements("Kafka interface requirements", [
    ["IFR-KAF-001", "Any producer of raw frames (including a future device parser) shall publish the JSON of " + ref("raw-frame") + " to gps.raw-device-frames with key = IMEI, through the ingest journal.", M, T, B],
    ["IFR-KAF-002", "Topics shall be declared by the service at start-up; automatic topic creation on the broker shall be disabled.", M, I, B],
    ["IFR-KAF-003", "Message field names and types shall be changed only in a backward-compatible way (new optional fields); a breaking change requires a new topic name.", M, I, P],
  ], { objective: "OBJ-07", component: "gps-configuration message/, KafkaTopicDeclarations", tests: "GpsMessageJsonTest, StreamsNormalizationTest" });

  d.h2("Software Interfaces: Data Stores and Third-Party Services");
  d.table("Data stores and services", [
    { header: "Interface", width: 2100 }, { header: "Protocol", width: 1500 }, { header: "Use", width: 3326 }, { header: "Failure handling", width: 2100 },
  ], [
    ["PostgreSQL gps_tracking", "JDBC", "System of record (see [R4])", "Retry without limit"],
    ["Redis", "RESP", "Live positions, state, history, rules, engine counters", "Retry; switchable at run time"],
    ["smart_truck MySQL", "JDBC, read-only", "SELECT latest trips and pings", "Simulator status shows the error"],
    ["Valhalla", "HTTP JSON", "Route computation and test", "5 s timeout"],
    ["HP ingestion API", "HTTP JSON", "Health only (not part of the pipeline)", "5 s timeout"],
    ["Open-Meteo", "HTTPS JSON", "Weather at a stoppage", "3 s timeout; verdict without it"],
    ["TomTom (optional)", "HTTPS JSON", "Traffic at a stoppage (key TOMTOM_API_KEY)", "3 s timeout; verdict without it"],
  ]);
  d.table("Redis key space", [{ header: "Key", width: 3300 }, { header: "Type", width: 1100 }, { header: "Content", width: 4626 }], [
    ["gps:live:vehicle:{imei}", "hash", "Newest position of the vehicle"],
    ["gps:live:positions", "geo set", "One point per device, for radius queries"],
    ["gps:state:vehicle:{imei}", "hash", "Vehicle state (heading, acceleration, Kalman track)"],
    ["gps:state:speed:{imei} / ignition / stoppage", "hash", "Per-consumer working state"],
    ["gps:history:{imei}", "stream", "Recent history, about 1000 entries"],
    ["gps:events:{imei}", "list", "Recent events of the vehicle"],
    ["gps:context:weather:{cell}", "string", "Cached weather of a 0.1° cell (10 min)"],
    ["gps:config:speed-rule:{scope}, gps:config:speed-rules", "hash / set", "Speed rules and their scopes (kept by resets)"],
    ["gps:engine:stage:{stage}[:by-imei]", "hash", "Stage counters mirrored every 5 s"],
  ], { label: "redis-keys" });
  d.requirements("Store interface requirements", [
    ["IFR-RDS-001", "The platform shall write only keys under the prefixes listed in " + ref("redis-keys") + " and shall never delete keys outside them.", M, T, B],
    ["IFR-RDS-002", "On the HP home server the platform shall use Redis database 1, leaving database 0 to the ingestion API.", M, I, B],
  ], { objective: "OBJ-06", component: "redis/, RedisKeyNames", tests: "RedisTargetTest" });

  d.h2("Hardware and Communication Interfaces");
  d.bullets([
    "No hardware is accessed directly. GPS devices are reached through a future parser (IFR-KAF-001). The HP home server is reached over its Wi-Fi hotspot at 192.168.137.1 (Valhalla :8002, Redis :6379, FastAPI :8000).",
    "Ports in the Docker deployment: console 3000, service 8080, PostgreSQL 5433, Kafka 29092, Redis 6380, RedisInsight 8001 (host side). Containers address each other by service name.",
    "All timestamps on the wire are UTC ISO-8601; all text is UTF-8.",
  ]);
}

// ------------------------------------------------------------------------------------------------ 6 Data
function dataRequirements(d) {
  d.h1("Data and Information Requirements");
  d.p("The logical and physical data model is specified in the companion Database Schema Specification [R4]: entities, columns, types, keys, constraints, indexes, retention and the full DDL. This section states the requirements that the model satisfies.");
  d.h2("Data Entities");
  d.table("Data entities", [{ header: "Entity (table)", width: 2500 }, { header: "Owner (writer)", width: 2700 }, { header: "Purpose", width: 3826 }], [
    ["tenant, vehicle, device, device_assignment", "Master data (simulator seeder; future admin API)", "Who owns what, and which device was in which vehicle when"],
    ["gps_position", "gps-position-storage-service", "Every accepted reading"],
    ["vehicle_motion_state, gps_trip, gps_stop, gps_trip_late_reading", "gps-trip-service", "Trip detection state and results"],
    ["vehicle_event", "Stream consumers", "Driving and engine events"],
    ["vehicle_day_summary, report_setting", "Report job; Reports UI", "Reports and cost assumptions"],
    ["dead_letter, rejected_frame", "Archive consumers", "Permanent record of what did not become a position"],
    ["connection_setting", "Connections UI", "Run-time external addresses"],
  ]);
  d.h2("Data Requirements");
  d.requirements("Data requirements", [
    ["DR-001", "Each entity shall have a primary key; uniqueness rules BR-001, BR-002, BR-003 and BR-005 shall be enforced by database constraints, not only by application code.", M, I, B],
    ["DR-002", "Telemetry tables shall refer to master data by IMEI, tenant code and registration number (not foreign keys), so that telemetry can be written at high rate and kept when master data changes.", M, I, B],
    ["DR-003", "Coordinates shall be stored in WGS-84 decimal degrees (double precision) with range checks; speeds in km/h; distances in metres.", M, I, B],
    ["DR-004", "Every timestamp shall be stored as timestamptz in UTC; the JDBC session time zone shall be UTC.", M, I, B],
    ["DR-005", "Enumerated values (status, kind, movement status) shall be checked by database CHECK constraints.", M, I, B],
    ["DR-006", "A trip's distance shall satisfy distance_m ≥ 0 and end ≥ start (database CHECK constraints).", M, T, B],
    ["DR-007", "Kafka shall keep all platform topics for 31 days; PostgreSQL rows shall be kept until deleted by a defined retention policy (NFR-PRV-003); archives shall be kept indefinitely.", M, I, B],
    ["DR-008", "PostgreSQL should be backed up at least daily, with backups kept for 31 days and a restore tested at least each quarter; recovery may re-consume Kafka from the backup time (NFR-REL-005).", S, D, P],
    ["DR-009", "Concurrent updates of the same trip state shall be prevented by the guarded update (FR-TRP-008); concurrent edits of configuration shall be last-writer-wins and audited (NFR-SEC-009).", M, T, B],
    ["DR-010", "Report summaries frozen by Clear database (generation > 0) shall never be recomputed or altered.", M, T, B],
  ], { objective: "OBJ-02", component: "Database gps_tracking", tests: "Schema inspection, integration tests" });
}

// ------------------------------------------------------------------------------------------------ 7 Verification
function verification(d) {
  d.h1("Verification and Acceptance");
  d.h2("Verification Approach");
  d.bullets([
    "**Unit tests** (T): every rule in gps-processing and gps-streams, run by `mvnw test` without infrastructure. Existing test classes are listed in the RTM.",
    "**Integration tests** (T): the Docker deployment with the simulator, checking database rows, Redis keys and API answers.",
    "**Failure-injection tests** (T): stop Kafka, PostgreSQL or Redis, or kill the service, under load; then compare counts (sent = stored + rejected + dead-lettered).",
    "**Performance tests** (T): design peak load for 15 minutes; collect consumer lag every second and timestamps from gps_position.",
    "**Inspections** (I): code, configuration and document reviews against the requirement text.",
    "**Demonstrations** (D): console walkthroughs observed by the product owner.",
  ]);
  d.h2("Acceptance Criteria");
  d.table("Acceptance criteria", [
    { header: "ID", width: 800 }, { header: "Scenario and expected result", width: 6326 }, { header: "Covers", width: 1900 },
  ], [
    ["AC-01", "Start 150 vehicles. Within 10 minutes all consumer groups are STABLE, lag returns to 0 after catch-up, failures are 0, and Redis holds 150 live positions.", "NFR-CAP-001, NFR-PERF-001, FR-LIVE-001"],
    ["AC-02", "Send a frame with latitude 95.0. It is rejected with reason “Latitude 95.0 is outside -90..90”, appears in rejected_frame and in the log with its IMEI.", "FR-VAL-006, FR-DLQ-007"],
    ["AC-03", "Stop Kafka for 5 minutes while the fleet runs, then start it. The number of stored positions equals the number of accepted frames sent; no gap in any device's sequence.", "FR-ING-004, NFR-REL-001"],
    ["AC-04", "Kill the service (kill -9) under load and restart it. No acknowledged frame is lost and no (imei, recorded_at) is stored twice.", "FR-ING-005, FR-STO-002, NFR-REL-003"],
    ["AC-05", "Replay the reference drive (test resource osmand-sent-positions.jsonl). The trip starts at reading 10 and stops at reading 40, as in the unit test.", "FR-TRP-001, FR-TRP-002"],
    ["AC-06", "Deliver a 5-minute-late reading during a trip. Trip distance does not change and late_reading_count increases by 1.", "FR-TRP-007, BR-011"],
    ["AC-07", "Set a vehicle speed rule of 40 km/h. A vehicle driving above 40 km/h for 60 s raises exactly one OVERSPEED event, CLOSED after the speed drops below 35 km/h.", "FR-SPD-001 to 003"],
    ["AC-08", "Corrupt a record so that the storage consumer cannot parse it. It is parked within 1 s, archived as PARKED, later records are stored, and a replay after the fix stores it.", "FR-DLQ-001, 004, 005"],
    ["AC-09", "Open the day report for yesterday. Every figure's explanation reproduces the figure from the stated formula and cost assumptions; change the fuel price and the cost changes at once.", "FR-RPT-004, 007, 008"],
    ["AC-10", "Clear the database with “keep reports”. Positions are gone; yesterday's day report shows the same totals as before.", "FR-RPT-010, DR-010"],
    ["AC-11", "Try 5 wrong passwords. The sixth attempt within 60 s is refused; an /api call without a session returns 401.", "FR-AUTH-001, FR-AUTH-005"],
    ["AC-12", "Switch normalisation from owner to shadow and back under load. Parity shows MATCH for at least 99.9 % of readings; no reading is skipped or processed twice.", "FR-KS-001, FR-KS-002, CON-007"],
  ], { size: 16 });
  d.p("The release is accepted when all “Must” requirements with status Baseline pass, every acceptance criterion passes, and every failed “Should” has a recorded decision.");
}

// ------------------------------------------------------------------------------------------------ 8 Traceability
function traceability(d) {
  d.h1("Traceability");
  d.h2("Traceability Approach");
  d.p("Each requirement traces back to a business objective (" + ref("objectives") + ") and forward to the design component that implements it and the test that verifies it. Test case TC-<requirement ID> is the planned test for each requirement; where an automated test already exists it is named. The matrix is generated from the requirement tables of this document, so it cannot drift from them.");
  d.h2("Requirements Traceability Matrix");
  const rows = META.register.map((r) => [{ text: r.id, bold: true }, r.objective, r.component, `TC-${r.id}` + (r.tests ? `; ${r.tests}` : ""), r.status]);
  d.table("Requirements traceability matrix", [
    { header: "Requirement", width: 1350 }, { header: "Objective", width: 950 }, { header: "Design component", width: 2900 },
    { header: "Verification (test case; existing tests)", width: 2826 }, { header: "Status", width: 1000, align: "center" },
  ], rows, { size: 15 });
  const counts = {};
  for (const r of META.register) {
    counts[r.status] = (counts[r.status] || 0) + 1;
  }
  d.p(`Requirement count: ${META.register.length} (Baseline ${counts.Baseline || 0}, Proposed ${counts.Proposed || 0}, Future ${counts.Future || 0}).`);

  d.h2("Open Issues and Decisions Register");
  d.table("Open issues (TBD)", [
    { header: "ID", width: 800 }, { header: "Issue", width: 4826 }, { header: "Owner", width: 1700 }, { header: "Needed by", width: 1700 },
  ], [
    ["OI-01", "Confirm the performance and availability thresholds (NFR-PERF-001 to 005, NFR-AVL-001) after a baseline test on production-like hardware.", "Technical lead", "Before baseline 1.0"],
    ["OI-02", "Define user roles and permissions for multi-user access (FR-AUTH-010).", "Product owner", "Before external users"],
    ["OI-03", "Decide how the service API is protected: network isolation or service token (NFR-SEC-007).", "Security reviewer", "Before production"],
    ["OI-04", "Define retention per data type and tenant, and the deletion job (NFR-PRV-003, REG-001).", "Product owner, legal", "Before production"],
    ["OI-05", "Legal confirmation of REG-001 to REG-005 applicability and obligations.", "Legal counsel", "Before production"],
    ["OI-06", "API versioning scheme (/api/v1) and OpenAPI publication (IFR-API-005).", "Technical lead", "Release 0.2"],
    ["OI-07", "Repair job for trips with late readings (FR-TRP-011): trigger, schedule and audit.", "Technical lead", "Release 0.3"],
    ["OI-08", "Device protocols to support first in the device parser (FR-ING-008, REG-004).", "Product owner", "Release 0.3"],
  ]);
}

// ------------------------------------------------------------------------------------------------ Appendices
function appendices(d) {
  d.h1("Appendices");
  d.h2("Appendix A: Configuration Parameters");
  d.p("Defaults from application.yml. Requirements refer to these keys; changing a key changes the verified value accordingly.");
  d.table("Configuration parameters", [
    { header: "Key", width: 3900 }, { header: "Default", width: 1500 }, { header: "Used by", width: 3626 },
  ], [
    ["gps.processing-flow", "kafka", "FR-ING-007 (kafka | direct)"],
    ["gps.distance.jitter-meters / jitter-max-speed-kmph", "10 / 5", "FR-NRM-005"],
    ["gps.distance.max-implied-speed-kmph / min-jump-meters", "200 / 500", "FR-NRM-006"],
    ["gps.trips.min-distance-meters / min-duration-seconds / stop-gap-seconds", "200 / 180 / 3600", "FR-TRP-001 to 003"],
    ["gps.stream.history-length", "1000", "FR-VST-004"],
    ["gps.stream.stoppage.radius-meters / min-duration-seconds / stationary-speed-kmph", "100 / 180 / 3", "FR-STP-001"],
    ["gps.stream.stoppage.exit-confirm-readings / max-gap-seconds / merge-gap-seconds", "2 / 1800 / 180", "FR-STP-002"],
    ["gps.stream.ignition.virtual-off-after-seconds / idle-speed-kmph / idle-alert-seconds / virtual-debounce-readings", "300 / 3 / 300 / 2", "FR-IGN-001, FR-IGN-003"],
    ["gps.stream.vehicle-state.gps-sigma-meters / acceleration-sigma", "12 / 1.5", "FR-VST-002"],
    ["gps.stream.enrichment.weather-enabled / traffic-radius-meters / timeout-millis", "true / 1000 / 3000", "FR-STP-004, FR-STP-005"],
    ["gps.kafka.retention / replication-factor / min-insync-replicas", "31d / 1 / 1", "NFR-CAP-002, NFR-REL-004"],
    ["gps.journal.directory / records-per-segment / fsync-every-record / drain-batch-size", "./data/ingest-journal / 1000 / true / 5000", "FR-ING-002 to 005"],
    ["gps.streams.enabled / normalization / threads / standby-replicas", "true / owner / 3 / 0", "FR-KS-001, FR-KS-002"],
    ["gps.streams.max-lateness / seen-readings-retention", "24h / 24h", "FR-NRM-001, FR-NRM-002"],
    ["gps.streams.window-size / window-grace / window-retention / parity-grace", "5m / 2m / 6h / 2m", "FR-KS-002, FR-KS-003"],
    ["gps.streams.normalizer-guarantee / analytics-guarantee", "exactly_once_v2 / at_least_once", "FR-KS-001"],
    ["gps.reports.zone / refresh-every / max-gap-seconds / max-stopped-gap-seconds / backfill-days", "Asia/Kolkata / 5m / 600 / 3600 / 62", "FR-RPT-001 to 003"],
    ["gps.admin.reset-enabled", "true", "FR-ADM-003, NFR-SEC-010"],
    ["gps.simulator.vehicle-count / pings-per-vehicle / minimum-pings-per-trip", "150 / 500 / 200", "FR-SIM-001"],
    ["gps.simulator.timing / catch-up-minutes / catch-up-speed-factor", "faithful / 120 / 30", "FR-SIM-002"],
    ["gps.simulator.devices-with-ignition / devices-with-odometer / idling-devices", "0.5 / 0.5 / 0.25", "FR-SIM-004"],
    ["gps.connections.redis-command-timeout / redis-connect-timeout / http-timeout", "5s / 3s / 5s", "FR-CON-006"],
    ["Speed rule default (Redis)", "60 km/h, 3 pings, 60 s, hysteresis 5, tolerance max(10, 15 %), gap 180 s", "FR-SPD-001 to 005"],
    ["Fuel assumptions default (report_setting)", "diesel, ₹92/L, 4 km/L, 2.5 L/h idle, 60 km/h, 20 %, 2.68 kg CO₂/L", "FR-RPT-008"],
    ["Console session (dashboard env)", "12 h; 30 days remembered; 5 failures → 60 s lock", "FR-AUTH-005, FR-AUTH-006"],
  ], { size: 16 });

  d.h2("Appendix B: Requirement Statement Checklist");
  d.p("Every requirement in this document was checked against the following questions, taken from the companion SRS Rules and Regulations [R3]:");
  d.bullets([
    "Is it one obligation, stated with “shall”, and free of vague words (fast, user-friendly, real-time, secure) unless they are measured?",
    "Can it be verified by the stated method, and is the expected result unambiguous?",
    "Does it have a unique identifier, a priority, a status and a trace to an objective?",
    "Are the invalid-input, duplicate, late, missing-permission and dependency-failure cases covered for its module?",
    "Does it agree with every other requirement, figure and table in this document?",
  ]);
}

module.exports = { build, META };
