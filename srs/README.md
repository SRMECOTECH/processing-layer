# Requirements documents (SRS)

Formal requirements for the GPS Processing Layer, written to ISO/IEC/IEEE 29148:2018. Each document is here as a
Word file (`.docx`, editable) and a PDF (`.pdf`, for reading and sharing).

| File | Document | ID | Version |
|---|---|---|---|
| [SRS_GPS_Processing_Layer.docx](SRS_GPS_Processing_Layer.docx) | Software Requirements Specification: scope, users, functional requirements per module, business rules, non-functional and regulatory requirements, interfaces, data, acceptance criteria, traceability matrix, open issues | SRM-GPS-SRS-001 | 0.9 (draft for review) |
| [DB_Schema_GPS_Tracking.docx](DB_Schema_GPS_Tracking.docx) | Database Schema Specification: ER diagram, data dictionary of all 15 tables (Flyway V1 to V8), constraints, indexes, idempotency keys, lifecycle, Redis keys, Kafka topics, state stores, full DDL | SRM-GPS-DBS-001 | 0.9 (draft for review) |
| [SRS_Rules_and_Regulations.docx](SRS_Rules_and_Regulations.docx) | The standard these documents follow: rules R1 to R12 for writing requirements, review and approval, change control, configuration management, legal and regulatory compliance, quality checklist, templates | SRM-GPS-SRS-RUL-001 | 1.0 |

## Changing the documents

The Word files are generated: edit the sources in [generator/](generator/), not the `.docx` files
(SRS Rules and Regulations, section 16). The traceability matrix is built from the requirement tables, and the DDL
appendix is read from `gps-application/src/main/resources/db/migration`, so they cannot drift.

```bash
cd srs/generator
npm install
npm run build
```

`npm run build` draws the figures (Python 3 with matplotlib) and writes the three `.docx` files. Then fill in the
tables of contents and page numbers with Microsoft Word (Windows), which also writes the `.pdf` copy of each document:

```bash
powershell -ExecutionPolicy Bypass -File render.ps1
```

Add `-NoPdf` to skip the PDFs.

A pull request that changes behaviour covered by a requirement updates the SRS in the same pull request; a pull request
that adds a Flyway migration updates the schema document in the same pull request.
