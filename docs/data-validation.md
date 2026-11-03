# Phase 3 data validation

Phase 3 validates staged payloads independently of operational persistence. `staging_record.payload` is immutable source evidence. A correction is stored separately in `corrected_payload`; each correction increments `payload_version` and resets validation to `PENDING`. `validation_result` records every complete validation outcome by staging record and payload version. Only a passing result for the current version permits release.

The detector version is `phase3-v1`. Its five independent validators and expected results are defined in [`anomaly-fixtures.md`](anomaly-fixtures.md) and the ground-truth artifacts in `data/ground-truth/`. The future-evidence fixture applies an immutable cutoff check only; this phase does not implement or invoke supplier scoring.

## API

- `POST /api/v1/validation/runs` stages the deterministic fixture set and validates it.
- `GET /api/v1/validation/runs` returns validation run status and metadata.
- `GET /api/v1/exceptions` supports `status`, `severity`, `issueType`, `page`, and `size` filters.
- `GET /api/v1/exceptions/{exceptionId}` returns the finding and linked staging/quarantine detail.
- `GET /api/v1/quarantined-records` supports `disposition`, `page`, and `size`.
- `GET /api/v1/quarantined-records/{quarantineId}` returns source/corrected payload and quarantine state.
- `PUT /api/v1/quarantined-records/{quarantineId}/correction` stores a corrected JSON object without changing source payload.
- `POST /api/v1/quarantined-records/{quarantineId}/revalidate` validates the full fixture rule set against the current version.
- `POST /api/v1/exceptions/{exceptionId}/resolution` records `RESOLVED` or `ACCEPTED`, a required note, optional actor, and timestamp.
- `POST /api/v1/quarantined-records/{quarantineId}/release` releases only a current-version passing record with its linked exception resolved/accepted and a release note.

Validation, correction, exception, and quarantine data remain in staging and Phase 3 tables. There is no promotion into supplier, shipment, purchase-order, receipt, invoice, risk, or compliance operational tables. No automatic deletion or retry cleanup is performed. Terminal `RELEASED` and `REJECTED` quarantine records cannot be corrected.

## Database verification

V6 is forward-only and adds `corrected_payload`, payload versioning, validation run/result history, lookup indexes, and a resolution audit constraint. V1–V5 are unchanged. The PostgreSQL integration test is opt-in (`-Dpostgres.integration=true`) and was executed against the dedicated `yottaflex_synthetic` PostgreSQL 18.6 database. Its concrete results are recorded after the test execution below.

### Runtime results — 2026-11-02

The final opt-in integration run used PostgreSQL 18.6 database `yottaflex_phase3_test6`, a fresh clone of the verified Phase 2 reproducibility database. The initial fixture validation run ID was `VAL-F9267DFD7A1F4C2BAA82`; all seven validation runs completed. Flyway validated V1–V6 and applied V6 successfully; Hibernate `ddl-auto=validate` completed during Spring startup. Flyway emitted its existing compatibility warning because the bundled Flyway version formally supports PostgreSQL through 17; the migration itself passed against PostgreSQL 18.6.

The API-level integration test completed **1 test, 0 failures, 0 errors, 0 skipped**. It triggered fixture validation through `POST /api/v1/validation/runs`, read the returned run ID and findings, and confirmed the following first-pass results, all persisted at payload version 1:

| Fixture | Actual first-pass result | Actual issue | Severity |
|---|---|---|---|
| `ANOMALY_SHIPMENT_NO_PO` | Invalid | `PARENT_REFERENCE_MISSING` | HIGH |
| `ANOMALY_DELIVERY_BEFORE_DISPATCH` | Invalid | `EVENT_CHRONOLOGY_INVALID` | HIGH |
| `ANOMALY_FUTURE_SCORE_INPUT` | Invalid | `FUTURE_EVIDENCE_AFTER_CUTOFF` | HIGH |
| `ANOMALY_RISK_MISSING_SOURCE_OR_TIME` | Invalid | `REQUIRED_FIELD_MISSING` | HIGH |
| `ANOMALY_CROSS_SUPPLIER_SITE` | Invalid | `CROSS_SUPPLIER_SITE` | HIGH |

After correction, each fixture's new payload version passed a complete validator run. The original source payloads remained byte-for-byte semantically unchanged. Attempts to release before revalidation and before resolving the exception were rejected. All five resolution audits had a nonblank actor reference and timestamp; all five release events were recorded. A repeated fixture request returned five valid corrected records and did not create duplicate exceptions or quarantine rows.

Final PostgreSQL state in `yottaflex_phase3_test6`:

- Staging records: **5**.
- Exceptions: **5 total, all RESOLVED** (initially 5 OPEN).
- Quarantined records: **5 total, all RELEASED** (initially 5 HELD).
- Validation runs: **7 COMPLETED** (initial run, five full revalidations, one idempotent rerun).
- Validation results: **5 invalid original payload versions; 10 valid corrected-version results** (five revalidation results and five rerun results).
- Audit events: **5 corrections, 5 resolutions with actor/timestamp, 5 releases**.
- Exception/quarantine/validation list and detail GET API paths returned HTTP 200 with the expected persisted rows.
- Operational data remained at Phase 2 counts: suppliers 25, sites 43, contracts 50, capacity commitments 100, purchase orders 120, PO lines 300, shipments 180, shipment lines 300, delivery events 360, receipts 240, receipt lines 300, inspections 240, inspection lines 300, invoices 144, invoice lines 180, risk signals 100, compliance requirements 12, compliance assessments 75, compliance checks 300, supplier status history 25.
- PostgreSQL schema inspection returned 36 primary-key constraints, 63 validated foreign keys (18 composite), 57 unique constraints, 87 validated check constraints, and 142 indexes.

`yottaflex_synthetic` itself still has all expected Phase 2 operational counts, but it contains a partial Phase 3 verification attempt: 5 staging records, 4 exceptions, and 4 quarantines. The first run attempt rolled back before writing fixtures; the next surfaced that two selected sites could belong to one supplier. That run persisted the four actual anomalies and one valid staging record before the assertion stopped. No automatic cleanup was performed. The corrected complete run was executed on isolated clones instead. The isolated `yottaflex_phase3_test` database contains partial state; `...test2` through `...test6` contain successful or superseded full verification runs. These databases were retained rather than dropped.

The ground-truth artifacts remain outside application APIs at `data/ground-truth/phase3-anomaly-fixtures.csv` and `data/ground-truth/phase3-anomaly-fixtures.json`.
