// SRS Rules and Regulations: the project standard for writing, reviewing, approving and changing an SRS.
const { DocBuilder } = require("./lib");

const META = {
  organisation: "SRMECOTECH",
  title: "SRS Rules and Regulations",
  subtitle: "Standard for writing, reviewing, approving and changing Software Requirements Specifications in the GPS Processing Layer project",
  shortTitle: "SRS Rules and Regulations",
  docId: "SRM-GPS-SRS-RUL-001",
  version: "1.0",
  status: "Issued for use",
  date: "29 October 2026",
  author: "Sanjoy Chattopadhay, SRMECOTECH",
  classification: "Internal",
  standard: "ISO/IEC/IEEE 29148:2018",
  confidentialNote: "This standard is normative for every requirements document of the project. Where it conflicts with "
    + "an institution's or client's prescribed template, that template governs and the deviation is recorded in the SRS.",
};

function build() {
  const d = new DocBuilder(META);
  d.titlePage();
  d.documentControl(
    [["1.0", "29 Oct 2026", "S. Chattopadhay", "First issue: rules R1 to R12, review and approval, change control, configuration management, regulatory compliance, quality checklist and templates."]],
    [["Product owner", "", "", ""], ["Technical lead / architect", "", "", ""], ["Quality assurance lead", "", "", ""]],
  );
  d.toc();

  // ---------------------------------------------------------------------------------------------- 1
  d.h1("Introduction");
  d.h2("Purpose");
  d.p("This standard lays down the rules for writing a Software Requirements Specification (SRS) and the regulations for "
    + "reviewing, approving, changing and storing it. An SRS written to this standard is formal, structured, testable, "
    + "traceable and unambiguous: a developer can implement from it and an independent tester can verify the product "
    + "against it without guessing what the author meant.");
  d.p("An SRS states **what** the system must do, **how well** it must do it, and the **constraints** it must satisfy. It is not a "
    + "description of what the developers happen to intend to build. Architecture and technology appear in an SRS only as "
    + "constraints, and only when stakeholder needs, system boundaries or recorded project decisions justify them.");
  d.h2("Applicability");
  d.bullets([
    "SRM-GPS-SRS-001, Software Requirements Specification: GPS Processing Layer.",
    "SRM-GPS-DBS-001, Database Schema Specification (for its data requirements and change control).",
    "Every future SRS, addendum or module specification of the project, whether for an academic submission, a research prototype or an industry release.",
  ]);
  d.h2("Normative References");
  d.table("References", [{ header: "Ref.", width: 800 }, { header: "Document", width: 8226 }], [
    ["[N1]", "ISO/IEC/IEEE 29148:2018, Systems and software engineering: Life cycle processes, Requirements engineering. Primary reference. (The 2018 edition is current; a successor is in development.)"],
    ["[N2]", "IEEE Std 830-1998, Recommended Practice for Software Requirements Specifications. Historical; superseded by [N1]. Its outline may be used only where it agrees with [N1]."],
    ["[N3]", "ISO/IEC 25010, Systems and software quality models (vocabulary for non-functional categories)."],
    ["[N4]", "The institution's or client's prescribed template, formatting, citation style and evaluation rubric, when one exists."],
  ]);
  d.h2("Terms Used in This Standard");
  d.table("Normative keywords", [{ header: "Keyword", width: 1500 }, { header: "Meaning in this standard and in every SRS written to it", width: 7526 }], [
    ["shall", "Mandatory. A “shall” statement must be satisfied and must be verifiable."],
    ["shall not", "Prohibited."],
    ["should", "Recommended. Not following it requires a recorded justification."],
    ["may", "Permitted; optional."],
    ["will", "Statement of fact or of an external party's intent. Never used for a requirement on the product."],
  ]);

  // ---------------------------------------------------------------------------------------------- 2
  d.h1("Rule R1: Follow the Recognised Standard and Its Quality Characteristics");
  d.p("**R1.1** Every SRS shall follow ISO/IEC/IEEE 29148:2018 [N1] and shall state so on its title page.");
  d.p("**R1.2** Every requirement, and the SRS as a whole, shall have the characteristics below. Reviewers shall check them using the questions in the last column.");
  d.table("Quality characteristics", [
    { header: "Characteristic", width: 1600 }, { header: "Definition", width: 3600 }, { header: "Review question", width: 3826 },
  ], [
    ["Correct", "The requirement states a genuine stakeholder need.", "Which objective or stakeholder does it come from?"],
    ["Unambiguous", "It has exactly one reasonable interpretation.", "Would two testers write the same test from it?"],
    ["Complete", "All functions, constraints, interfaces and exceptional cases are covered.", "Are the invalid, duplicate, late, unauthorised and dependency-failure cases stated?"],
    ["Consistent", "No requirement contradicts another, a figure or a table.", "Do the numbers, names and states agree everywhere?"],
    ["Verifiable", "It can be tested, inspected, analysed or demonstrated.", "What is the pass/fail criterion and the method?"],
    ["Feasible", "It can be implemented within the project's constraints.", "Has the technical lead confirmed it?"],
    ["Traceable", "It has a unique ID, a source and a verification link.", "Does it appear in the RTM with objective, component and test?"],
    ["Modifiable", "It can be changed without side effects on unrelated text.", "Is it stated once, in one place, and referenced elsewhere by ID?"],
  ]);

  // ---------------------------------------------------------------------------------------------- 3
  d.h1("Rule R2: Use the Prescribed Document Structure");
  d.p("**R2.1** An SRS shall contain the following parts, in this order. Headings shall be numbered consistently.");
  d.table("Mandatory SRS structure", [
    { header: "Part", width: 2300 }, { header: "Content", width: 5426 }, { header: "Required", width: 1300, align: "center" },
  ], [
    ["Front matter", "Title page, document control (revision history, approvals, distribution), table of contents.", "Yes"],
    ["1 Introduction", "Purpose, scope (in and out), audience, definitions and acronyms, references, document conventions.", "Yes"],
    ["2 Overall description", "Product perspective (context diagram), functions, user classes, operating environment, constraints, assumptions and dependencies.", "Yes"],
    ["3 Specific requirements", "Functional requirements module by module, business rules, error handling and exceptional cases.", "Yes"],
    ["4 Non-functional requirements", "Performance, capacity, reliability, availability, security, privacy, usability, maintainability, compatibility, regulatory compliance.", "Yes"],
    ["5 External interfaces", "User interfaces, APIs, messages, data stores, hardware and communication interfaces.", "Yes"],
    ["6 Data requirements", "Entities, validation, retention, consistency, privacy, backup and recovery (may refer to a schema document).", "Yes"],
    ["7 Verification and acceptance", "Verification methods and measurable acceptance criteria.", "Yes"],
    ["8 Traceability", "RTM, open issues register.", "Yes"],
    ["Appendices", "Glossary extensions, models, configuration parameters, checklists.", "As needed"],
  ]);
  d.p("**R2.2** Headings that do not apply to a project may be kept with the text “Not applicable” and a reason, or removed when the institution's template allows. A heading shall never be left empty.");
  d.p("**R2.3** Where an institution or client prescribes its own template [N4], that template shall be used and every deviation from this structure shall be listed in Section 1 of the SRS.");

  // ---------------------------------------------------------------------------------------------- 4
  d.h1("Rule R3: Write Each Requirement in Precise Language");
  d.p("**R3.1** One requirement shall state one obligation. A statement joined by “and” or “or” that could pass for one part and fail for the other shall be split.");
  d.p("**R3.2** A requirement shall follow the pattern: **<ID>: The <system or component> shall <action> <object> [<condition>] [<measurable criterion>].**");
  d.p("**R3.3** The following words shall not appear in a requirement unless the same sentence or a referenced definition makes them measurable:");
  d.table("Vague words and how to make them measurable", [
    { header: "Vague word", width: 1800 }, { header: "Ask", width: 3200 }, { header: "Measurable form (example)", width: 4026 },
  ], [
    ["fast, quickly", "How long, at what percentile, under what load?", "within 1 s at the 95th percentile at 75 frames/s"],
    ["real-time", "What end-to-end delay is acceptable?", "visible within 2 s of processing"],
    ["user-friendly", "Which users, which task, what success rate?", "5 new operators each complete the task within 3 minutes"],
    ["efficient", "Which resource, what limit?", "at most 64 MB of off-heap memory per RocksDB store"],
    ["secure", "Against what threat, by what control?", "HTTP 401 for any /api call without a valid session"],
    ["reliable", "What failure, what tolerated loss?", "no acknowledged frame lost after kill -9 of the service"],
    ["sufficient, appropriate, adequate", "By what criterion?", "replace with the criterion itself"],
    ["etc., and so on, including but not limited to", "What exactly is included?", "list every item"],
  ]);
  d.p("**R3.4** Functional and non-functional requirements shall be kept apart. A functional requirement states a capability; a non-functional requirement states a measurable quality or constraint of a capability.");
  d.h2("Worked Example");
  d.table("Weak versus compliant requirements", [
    { header: "Weak (not acceptable)", width: 3400 }, { header: "Compliant", width: 5626 },
  ], [
    ["The system should quickly show where trucks are.", "FR-LIVE-001: The live-location consumer shall keep, for each IMEI, the newest position in Redis hash gps:live:vehicle:{imei}.\nNFR-PERF-003: At design peak load, for 95 % of frames, the vehicle's live position in Redis shall reflect the frame within 2 seconds of the frame being processed."],
    ["Bad GPS data must be handled properly.", "FR-VAL-006: A frame with latitude outside −90 to 90 shall be rejected with reason “Latitude <v> is outside -90..90”."],
    ["The system must never lose data.", "NFR-REL-001: No frame acknowledged by the ingest journal shall be lost because of a Kafka outage, service crash, kill -9 or power cut, provided the host's disk survives."],
  ]);
  d.note("Numerical thresholds are engineering targets, not universal truths. They shall be derived from stakeholder needs and validated against the actual architecture; until then they carry the status Proposed (R4.3).");

  // ---------------------------------------------------------------------------------------------- 5
  d.h1("Rule R4: Identify Every Requirement and Record Its Attributes");
  d.p("**R4.1** Every requirement shall have a unique, stable identifier. Identifiers shall never be re-used or renumbered; a deleted requirement keeps its row with status Deleted and the reason.");
  d.table("Identifier prefixes", [{ header: "Prefix", width: 1700 }, { header: "Kind", width: 3000 }, { header: "Format", width: 4326 }], [
    ["FR", "Functional requirement", "FR-<AREA>-nnn, AREA = 2 to 5 capital letters of the module"],
    ["NFR", "Non-functional requirement", "NFR-<CAT>-nnn, CAT = PERF, CAP, REL, AVL, SEC, PRV, USE, MNT, CMP"],
    ["IFR", "Interface requirement", "IFR-<IF>-nnn, IF = UI, API, KAF, RDS …"],
    ["DR", "Data requirement", "DR-nnn"],
    ["BR", "Business rule", "BR-nnn"],
    ["REG", "Regulatory or legal requirement", "REG-nnn"],
    ["CON", "Design or implementation constraint", "CON-nnn"],
    ["ASM / DEP", "Assumption / dependency", "ASM-nnn / DEP-nnn"],
    ["OBJ", "Business objective", "OBJ-nn"],
    ["AC / TC", "Acceptance criterion / test case", "AC-nn / TC-<requirement ID>"],
  ]);
  d.p("**R4.2** Requirements shall be presented in tables (a requirements register), not only as paragraphs. Each row shall carry these attributes:");
  d.table("Mandatory requirement attributes", [{ header: "Attribute", width: 1800 }, { header: "Values", width: 7226 }], [
    ["ID", "Per R4.1"],
    ["Statement", "Per R3"],
    ["Priority", "Must, Should, Could (MoSCoW). “Won't (this time)” items are moved to the out-of-scope list."],
    ["Verification", "T Test, I Inspection, A Analysis, D Demonstration"],
    ["Status", "Proposed, Approved, Baseline (implemented, awaiting verification), Verified, Future (roadmap only), Deleted. A document may use a subset and shall define it."],
    ["Source / objective", "The objective, stakeholder or regulation it comes from (in the RTM)."],
  ]);
  d.p("**R4.3** Approved requirements, proposed requirements and unresolved items shall never be mixed without their status. Unresolved items shall be marked TBD and listed in the open-issues register with an owner and a date.");

  // ---------------------------------------------------------------------------------------------- 6
  d.h1("Rule R5: Define Scope and System Boundaries");
  d.p("**R5.1** The SRS shall state the problem solved, the users and stakeholders, the main capabilities, the included modules and workflows, the excluded functionality, the external systems and what each is responsible for, the assumptions and dependencies, and the operational constraints.");
  d.p("**R5.2** Responsibilities at the boundary shall be explicit. For a fleet-tracking platform the SRS shall say, for each of the following, whether the system or an external party is responsible: receiving device data, parsing protocols, cleaning coordinates, calculating road distance, detecting stops and trips, predicting ETA, generating reports, issuing alerts, delivering notifications.");
  d.p("**R5.3** A system context diagram shall show the boundary, every external entity and every flow across the boundary.");

  // ---------------------------------------------------------------------------------------------- 7
  d.h1("Rule R6: Specify Functional Requirements Module by Module");
  d.p("**R6.1** Functional requirements shall be grouped by module. For each module the SRS shall state:");
  d.bullets([
    "the normal workflow and its trigger;",
    "preconditions;",
    "inputs (with their source and format) and outputs (with their destination);",
    "processing rules, including thresholds and their configuration keys;",
    "validation rules and the message for each failure;",
    "permissions (who may invoke it);",
    "failure behaviour (R7).",
  ]);
  d.p("**R6.2** Broad statements such as “The system shall manage vehicles” are not acceptable. A feature with several operations (register, edit, activate, assign device, deactivate, permissions) shall have one requirement per operation.");

  // ---------------------------------------------------------------------------------------------- 8
  d.h1("Rule R7: Cover Exceptional and Edge Cases");
  d.p("**R7.1** For every module, the author shall answer each question below with a requirement or with a written “not applicable” and reason. The SRS shall contain a summary table of exceptional conditions.");
  d.table("Mandatory exceptional-case questions", [{ header: "#", width: 600, align: "center" }, { header: "Question", width: 4200 }, { header: "Example of a required answer", width: 4226 }], [
    ["1", "What happens when input is missing or invalid?", "Reject with a named reason; archive the payload"],
    ["2", "What happens when the database or an external API is unavailable?", "Retry with back-off, never skip; or continue without the optional API"],
    ["3", "What happens when duplicate events arrive?", "Idempotency key; second copy changes nothing"],
    ["4", "What happens when events arrive out of order?", "Accept for history; never overwrite newer state"],
    ["5", "What happens when a user lacks permission?", "HTTP 401/403; redirect to sign-in"],
    ["6", "What happens when data is stale?", "Reject beyond a stated age"],
    ["7", "What happens when a service restarts?", "Resume from committed offsets; restore state"],
    ["8", "What happens when several users change the same resource?", "Last writer wins, audited; or optimistic locking"],
    ["9", "What happens at the capacity limit?", "Back-pressure, lag grows, no loss within retention, alarm"],
  ]);
  d.p("**R7.2** Different conditions shall not be merged into one requirement when they need different handling (for example an invalid coordinate, a duplicate, an out-of-order reading and an unavailable downstream service).");

  // ---------------------------------------------------------------------------------------------- 9
  d.h1("Rule R8: Make Non-Functional Requirements Measurable");
  d.p("**R8.1** Every non-functional requirement shall name the condition (workload, environment), the metric, the threshold and the measurement interval. The table lists what each category shall specify.");
  d.table("What each non-functional category shall specify", [{ header: "Category", width: 1900 }, { header: "Shall specify", width: 7126 }], [
    ["Performance", "Response-time or latency percentiles, throughput, the workload, the measurement interval."],
    ["Capacity / scalability", "Concurrent users, devices or event rates supported, and expected growth."],
    ["Availability", "Target percentage, measurement period, how it is measured, and exclusions (maintenance)."],
    ["Reliability", "Failure behaviour, tolerated data loss (RPO), recovery time (RTO)."],
    ["Security", "Authentication, authorisation, encryption, auditability, session controls, secret handling."],
    ["Privacy", "Personal data identified, purpose, retention, access, erasure."],
    ["Maintainability", "Test coverage, module rules, code-quality gates, where justified."],
    ["Usability / accessibility", "Task-completion criteria with the user group and sample size; accessibility level (for example WCAG 2.1 AA)."],
    ["Compatibility", "Supported browsers, operating systems, protocols, integrations and their versions."],
    ["Data integrity", "Consistency rules, duplicate handling, retention, recovery."],
  ]);
  d.p("**R8.2** An availability figure (for example 99.5 %) is acceptable only together with its measurement period, maintenance policy and exclusions.");
  d.p("**R8.3** Thresholds that have not yet been validated against the architecture shall carry status Proposed and an open issue to baseline them.");

  // ---------------------------------------------------------------------------------------------- 10
  d.h1("Rule R9: Document Interfaces and Data");
  d.p("**R9.1** For every API the SRS shall give: endpoint and method; request and response schema with required and optional fields; authentication and authorisation; validation and error responses; timeout and retry behaviour; rate limits and versioning; idempotency and duplicate-request behaviour.");
  d.p("**R9.2** For every message or event stream: name, key, schema, ordering guarantee, delivery guarantee, retention, producers and consumers.");
  d.p("**R9.3** For data: entities, identifiers, field types, constraints, relationships, timestamps and time zones, retention, ownership, backup and recovery. A data dictionary, ER diagram, API contract or state diagram may support the text but shall not replace a requirement that must be stated explicitly.");
  d.p("**R9.4** When a separate schema document exists, the SRS shall reference it, and the two shall be changed together (CC.4).");

  // ---------------------------------------------------------------------------------------------- 11
  d.h1("Rule R10: Maintain Traceability from Need to Test");
  d.p("**R10.1** The SRS shall contain a Requirements Traceability Matrix (RTM) with, for every requirement: its source objective, the design component that implements it, and the test case that verifies it.");
  d.p("**R10.2** The RTM shall be generated from the requirement tables (not typed separately), so that it cannot drift from them.");
  d.p("**R10.3** Before each baseline, the RTM shall be checked in both directions: every requirement has a test (no unverified requirement) and every test traces to a requirement (no unsupported feature or orphan test).");

  // ---------------------------------------------------------------------------------------------- 12
  d.h1("Rule R11: Use Diagrams Where They Add Precision");
  d.p("**R11.1** The SRS shall include a system context diagram. It should include, where they clarify behaviour: use-case, data-flow, ER, sequence, state-machine, activity and deployment diagrams.");
  d.p("**R11.2** Every figure shall have a number and a caption, shall be referred to in the text, and shall agree with the written requirements. Where a figure and the text disagree, the text governs and the figure shall be corrected.");
  d.p("**R11.3** A diagram shall never replace a measurable performance, security or data requirement.");

  // ---------------------------------------------------------------------------------------------- 13
  d.h1("Rule R12: Apply Professional Document Control");
  d.p("**R12.1** The title page shall show: title, document identifier, version, status, date, author or responsible team, classification and governing standard.");
  d.p("**R12.2** Versions: 0.x is a working draft; n.0 is a baseline approved under Section 14; n.m (m > 0) is an approved minor revision. The status field shall say which.");
  d.p("**R12.3** The document shall contain a revision history, a review-and-approval table, a table of contents, numbered headings, numbered and captioned tables and figures, a glossary and a references list. Acronyms shall be defined at first use.");
  d.p("**R12.4** Terminology shall be consistent: one term per concept throughout the document (for example “reading” and “frame” are defined, and not used for anything else).");

  // ---------------------------------------------------------------------------------------------- 14
  d.h1("Regulation: Review and Approval");
  d.h2("Roles");
  d.table("Roles and responsibilities", [{ header: "Role", width: 2200 }, { header: "Responsibility", width: 6826 }], [
    ["Author", "Writes the SRS to this standard; answers review comments; keeps the RTM and open issues current."],
    ["Technical reviewer", "Checks feasibility, consistency with the architecture and correctness of interfaces and data."],
    ["QA reviewer", "Checks verifiability, acceptance criteria and the RTM."],
    ["Security / compliance reviewer", "Checks security, privacy and regulatory requirements."],
    ["Product owner (approver)", "Confirms the requirements meet stakeholder needs; approves the baseline."],
    ["Change control board (CCB)", "Product owner, technical lead and QA lead; decides change requests after a baseline."],
  ]);
  d.h2("Review Procedure");
  d.numbered([
    "Entry: the draft is complete per R2, every requirement has the attributes of R4.2, and the author has completed the checklist in Section 18.",
    "Each reviewer records findings in the review record (Appendix C) with a severity: Major (wrong, ambiguous, unverifiable, missing or contradictory requirement) or Minor (wording, formatting).",
    "The author resolves every finding or records why not; the reviewer closes it.",
    "Exit: no open Major finding; every Minor finding resolved or accepted; open issues have owners and dates.",
    "The approver signs the approval table; the version becomes n.0 and the status “Baselined”.",
  ]);

  // ---------------------------------------------------------------------------------------------- 15
  d.h1("Regulation: Change Control");
  d.p("**CC.1** After a baseline, a requirement shall be changed only through an approved change request (CR, Appendix B). Before a baseline, the author may change the draft freely, recording changes in the revision history.");
  d.p("**CC.2** Every CR shall contain an impact analysis: affected requirements, design components, tests, schema, interfaces, effort and risk.");
  d.p("**CC.3** Requirement status shall change only as follows: Proposed → Approved → Baseline (implemented) → Verified; Proposed or Approved → Deleted (with reason); Future → Proposed when it is brought into a release.");
  d.p("**CC.4** Code and requirements change together. A pull request that changes behaviour covered by a requirement shall update the SRS in the same pull request; a pull request that adds a database migration shall update the Database Schema Specification in the same pull request.");
  d.p("**CC.5** Every approved change increases the version (minor for clarifications and additions, major for changes that alter acceptance) and adds a revision-history row naming the CR.");

  // ---------------------------------------------------------------------------------------------- 16
  d.h1("Regulation: Configuration Management and Storage");
  d.bullets([
    "The requirements documents live in the repository folder `srs/` and are versioned with the code.",
    "The Word files are generated from the sources in `srs/generator/` (npm run build, then render.ps1 to fill the table of contents). The generator sources are the master copy: an edit shall be made there and the documents rebuilt, so that the RTM and the DDL appendix stay in step with the requirement tables and the migrations.",
    "Review comments may be made in the Word files; accepted changes shall be ported to the generator sources before the next build.",
    "File names: SRS_<Product>.docx, DB_Schema_<Database>.docx, SRS_Rules_and_Regulations.docx. Document identifiers never change between versions.",
    "Superseded versions are kept in the repository history; they are not deleted.",
  ]);

  // ---------------------------------------------------------------------------------------------- 17
  d.h1("Regulation: Legal and Regulatory Compliance in an SRS");
  d.p("**LR.1** Every SRS shall contain a regulatory section that identifies the laws, standards and third-party terms that apply to the product, states the resulting requirements with REG identifiers, and records legal review as an open issue until it is done.");
  d.p("**LR.2** For products that process vehicle location in India, the review shall consider at least:");
  d.table("Regulations to consider", [{ header: "Instrument", width: 3200 }, { header: "Typical consequence for the SRS", width: 5826 }], [
    ["Digital Personal Data Protection Act, 2023", "Location linked to a driver is personal data: purpose, notice, lawful basis, retention, access and erasure requirements."],
    ["IT Act 2000, s. 43A, and SPDI Rules 2011", "Reasonable security practices; documented security controls."],
    ["CERT-In Directions, 28 April 2022", "ICT logs kept 180 days in India; incident reporting within 6 hours."],
    ["AIS-140 (ARAI)", "Device data formats and obligations where vehicle location tracking devices are mandated."],
    ["Motor-vehicle speed rules", "Speed limits differ by vehicle class, road and state: limits must be configurable; events are not legal findings."],
    ["Third-party licences and terms", "API terms (commercial use), map-data attribution (OpenStreetMap ODbL), open-source licences."],
  ]);
  d.p("**LR.3** The SRS shall state that regulatory requirements do not replace legal advice and shall name the person responsible for the legal review.");

  // ---------------------------------------------------------------------------------------------- 18
  d.h1("Final Quality Checklist");
  d.p("The author shall complete this checklist before a review (entry criterion of Section 14), giving evidence for each item. The last column records the self-assessment of SRM-GPS-SRS-001 version 0.9.");
  d.table("SRS review checklist", [
    { header: "#", width: 500, align: "center" }, { header: "Check", width: 3900 }, { header: "Evidence in SRM-GPS-SRS-001 v0.9", width: 3626 }, { header: "Result", width: 1000, align: "center" },
  ], [
    [{ text: "Structure", bold: true }, "", "", ""],
    ["1", "Purpose, scope, stakeholders, assumptions and constraints are documented.", "Sections 1.1 to 1.3, 2.3, 2.5, 2.6", "Pass"],
    ["2", "Functional, non-functional, interface and data requirements are covered.", "Sections 3, 4, 5, 6", "Pass"],
    ["3", "Figures, tables, references and terminology are consistent.", "Numbered captions; glossary 1.4; references 1.5", "Pass"],
    [{ text: "Requirement quality", bold: true }, "", "", ""],
    ["4", "Every requirement is clear, atomic, feasible and verifiable.", "Requirement tables; verification column", "Pass (review)"],
    ["5", "Every requirement has a unique and stable identifier.", "Identifier scheme 1.6; RTM", "Pass"],
    ["6", "Vague terms have been eliminated or precisely defined.", "Load definitions 4; thresholds with percentiles", "Pass (review)"],
    ["7", "Normal workflows, errors and important edge cases are specified.", "Per-module requirements; Section 3.23 summary", "Pass"],
    [{ text: "Engineering rigour", bold: true }, "", "", ""],
    ["8", "Performance and capacity requirements have measurable thresholds.", "NFR-PERF-001 to 006, NFR-CAP-001 to 005", "Pass; thresholds Proposed (OI-01)"],
    ["9", "Security, privacy, data integrity and recovery are addressed.", "4.5, 4.6, 4.3, 6, REG-001 to 006", "Pass"],
    ["10", "Requirements trace to stakeholder needs and verification methods.", "Objectives table; RTM in Section 8.2", "Pass"],
    ["11", "The requirements have been reviewed for contradictions and omissions.", "Review per Section 14 pending", "Open"],
    [{ text: "Release readiness", bold: true }, "", "", ""],
    ["12", "Acceptance criteria and verification approaches are documented.", "Section 7 (AC-01 to AC-12)", "Pass"],
    ["13", "Dependencies and unresolved decisions are explicitly recorded.", "DEP-001 to 007; open issues OI-01 to OI-08", "Pass"],
    ["14", "Document version, review status and change history are maintained.", "Document control; status “Draft for review”", "Pass"],
  ], { size: 16 });

  // ---------------------------------------------------------------------------------------------- Appendices
  d.h1("Appendices");
  d.h2("Appendix A: Requirement Templates");
  d.table("Templates", [{ header: "Kind", width: 1500 }, { header: "Template and example", width: 7526 }], [
    ["Functional", "FR-<AREA>-nnn: The <component> shall <action> <object> when <condition>.\nFR-TRP-003: When a device is silent for more than 3600 s, the open trip shall be closed at the last reading before the silence."],
    ["Non-functional", "NFR-<CAT>-nnn: Under <workload>, <metric> shall be <threshold> at <percentile>, measured over <interval>.\nNFR-PERF-002: At design peak load, for 95 % of stored positions, stored_at − processed_at shall be at most 1 second."],
    ["Business rule", "BR-nnn: <policy statement independent of any screen or API>.\nBR-001: A device shall be installed in at most one vehicle at any instant."],
    ["Interface", "IFR-<IF>-nnn: <producer/consumer> shall <exchange> <message> via <channel> with <key/format/guarantee>.\nIFR-KAF-001: Any producer of raw frames shall publish the raw-frame JSON to gps.raw-device-frames with key = IMEI."],
    ["Data", "DR-nnn: <entity/attribute> shall <constraint/retention/format>.\nDR-004: Every timestamp shall be stored as timestamptz in UTC."],
    ["Regulatory", "REG-nnn: <instrument>: <obligation> shall <be met by> <product capability or operator process>."],
  ]);
  d.h2("Appendix B: Change Request Form");
  d.keyValue("Change request", [
    ["CR number", "CR-yyyy-nnn"],
    ["Title", ""],
    ["Requested by / date", ""],
    ["Requirements affected", "IDs, with current and proposed text"],
    ["Reason", "Stakeholder need, defect, regulation …"],
    ["Impact analysis", "Design components, tests, schema, interfaces, documents, effort, risk"],
    ["Priority", "Must / Should / Could"],
    ["CCB decision", "Approved / Rejected / Deferred, with date and names"],
    ["Implemented in", "Pull request, SRS version"],
  ], 2600);
  d.h2("Appendix C: Review Record");
  d.table("Review record", [
    { header: "#", width: 500, align: "center" }, { header: "Location (section / ID)", width: 1800 }, { header: "Finding", width: 3226 },
    { header: "Severity", width: 1000, align: "center" }, { header: "Resolution", width: 1600 }, { header: "Closed by", width: 900 },
  ], [["1", "", "", "", "", ""], ["2", "", "", "", "", ""], ["3", "", "", "", "", ""]], { zebra: false });
  return d.build();
}

module.exports = { build, META };
