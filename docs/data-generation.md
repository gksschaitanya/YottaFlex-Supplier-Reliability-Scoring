# Synthetic Data Generation

## Scope

Phase 2 generates operational supplier reliability source data only. It does not create supplier scores, classifications, anomaly fixtures, ground-truth labels, or dashboard metrics.

The generator is a Spring service in the backend modular monolith. `GenerationRunService` validates requests, snapshots configuration, takes a PostgreSQL advisory lock, persists the run, and submits one background task. `GenerationPipeline` coordinates ordered domain writers. `DeterministicBatchWriter` lazily consumes rows, writes bounded JDBC batches, commits each batch separately, logs progress, and computes canonical SHA-256 digests. `GenerationIntegrityValidator` checks exact counts, ownership, shipment/receipt/inspection/invoice reconciliation, chronology, risk provenance, and catalog references before the run is marked completed.

## Generation Order

1. Global `ComplianceRequirement` catalog
2. Supplier
3. Site
4. Contract
5. Capacity commitment
6. Supplier status history
7. Purchase order
8. PO line
9. Shipment
10. Shipment line
11. Delivery event
12. Receipt
13. Receipt line
14. Inspection
15. Inspection line
16. Invoice
17. Invoice line
18. Risk signal
19. Compliance assessment
20. Compliance check

Compliance catalog rows are inserted only when the catalog is empty. If it already exists, IDs and requirement codes must exactly match the deterministic catalog; otherwise the run fails closed. Invalid anomaly records are not generated.

## Run a Starter Dataset

Start the backend after setting the PostgreSQL environment variables described in the root README. Then submit this request from PowerShell:

```powershell
$body = @{ scale = "STARTER" } | ConvertTo-Json
$run = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/generation/runs" `
  -ContentType "application/json" -Body $body
$run
```

The endpoint returns HTTP 202 and a run ID. It does not hold the request open for data generation. Poll status and counts:

```powershell
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)"
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)/counts"
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)/artifacts"
```

## Run a Full Dataset

Use the same endpoint with `scale = "FULL"`. FULL always uses the specified mandatory counts and cannot be reduced by configuration. The request returns immediately while a single bounded background executor handles the generation. The database-level named lock prevents concurrent runs against the same target.

## Safe Regeneration

Generation refuses to start if any operational target table already contains records. It never deletes or truncates data. A failed batch may leave committed rows; its run is marked `FAILED`, and the target remains blocked. Inspect the failed run and its partial counts. For another clean run, use a newly created, synthetic-only database/schema, or recreate a confirmed synthetic-only database manually after preserving any desired artifacts. There is intentionally no reset endpoint or implicit cleanup command.

## Reproducibility

Business records derive from the configured seed, fixed reference date, scale, generator version, and normalized configuration. Separate namespaced `SplittableRandom` streams make supplier, PO, shipment, risk, quality, and other data generation independent. Entity IDs are assigned by stable sequence before insertion, not by database auto-increment. Generated business timestamps and timestamps used in canonical row hashes derive from the fixed reference date. Run start/completion times, DB IDs, and generation-run foreign keys do not participate in artifact hashes.

A run persists `configuration`, `config_hash`, status, duration, and `GenerationArtifact` rows with artifact names, row counts, schema version, and SHA-256 checksum. Checksums are over stable ordered row representations.

## Limitations

Business values are deterministic synthetic records, not observations about real suppliers. A local PostgreSQL 18 migration/JPA verification is part of the repository's configured test workflow; no synthetic dataset is produced by merely starting the application.

## Initial Runtime Recovery Findings (superseded by the continuation below)

The saved Maven integration-test report records a successful connection to PostgreSQL 18.6 database `supplier_reliability`. It reports Flyway validating all five migrations (V1–V5), schema version 5 already current, 33 application tables, foreign keys including composite foreign keys, unique constraints and indexes, and successful JPA mappings and Actuator health. The test also observed zero rows in `data_generation_run`. It did not count operational-table rows, so this is not evidence that those tables are empty.

The approved database engine is PostgreSQL 18.6. The current application defaults to `jdbc:postgresql://localhost:5432/supplier_reliability` and uses `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`; no credentials are recorded here. The backend dependencies and V1–V5 migrations are PostgreSQL-specific.

In the recovery continuation, the current shell had no PostgreSQL or datasource credential variables. A read-only JDBC attempt using the configured defaults reached PostgreSQL, which rejected the empty password with SCRAM authentication. No password was printed or recorded. DBeaver contains a PostgreSQL connection named `postgres` with the same local URL, but its saved credential could not be used through the shell. As a result, this continuation did not query live table counts, verify whether `yottaflex_synthetic` exists, or run generation. The saved earlier integration report remains evidence for `supplier_reliability` at that earlier time; it does not establish the current operational-table state.

Recovery checklist:

| Task | Status | Evidence / next safe action |
|---|---|---|
| Phase 2 generator implementation | COMPLETED BUT NOT RUNTIME VERIFIED | Source includes deterministic writers, run tracking, batch artifacts, and validation. |
| PostgreSQL migrations and JPA | COMPLETED BUT NOT RUNTIME VERIFIED | The saved earlier report verified five migrations, 33 application tables, constraints/indexes, JPA, and health. Live verification was not repeated because the current process lacks the SCRAM password. |
| Existing PostgreSQL operational-table state | BLOCKED | Current read-only connection failed authentication. The earlier report asserted zero generation runs but did not inspect operational table counts. Do not generate against `supplier_reliability` or assume it is empty. |
| `yottaflex_synthetic` target existence and contents | BLOCKED | Could not query the server catalog without authenticated access. Do not create or use the target until existence and table contents are checked. |
| STARTER generation and persisted counts | PENDING | No verified run ID, run status, actual entity counts, or artifact checksums are available. |
| Integrity queries | PENDING | Application validation code exists, but no completed generation has exercised it against a persisted dataset. |
| Reproducibility comparison | PENDING | Requires two confirmed clean isolated targets and matching engine/configuration. |
| Failure and concurrency behavior | COMPLETED BUT NOT RUNTIME VERIFIED | Guard, run lock, and per-batch commits are implemented; partial-commit and interruption behavior have not been exercised in a runtime verification. |
| Backend clean package and tests | VERIFIED | `.\mvnw.cmd -pl backend -am clean package` completed with BUILD SUCCESS outside the sandbox: 19 tests, 0 failures/errors, 2 skipped, 17 passed. The skips were Docker-dependent Testcontainers and the opt-in configured PostgreSQL integration test. |
| ApplicationSmokeTest diagnosis | VERIFIED | The test passes independently outside the sandbox (1 passed). It stalls inside the sandbox when Mockito's inline mock-maker cannot open a JVM attach pipe; this is an execution restriction, not an application test failure. |
| Frontend production build | VERIFIED | `npm --prefix frontend run build` completed successfully. |
| Git history and change inventory | BLOCKED | This workspace copy has no `.git` directory, so commit history and a source-control diff are unavailable. |

The initial recovery did not create, migrate, clear, or generate data. The following continuation records the later authenticated PostgreSQL verification and supersedes the pending statuses above.

## PostgreSQL Runtime Verification Continuation (2026-11-02)

### Database and migrations

The approved runtime is PostgreSQL 18.6. The application default is `jdbc:postgresql://localhost:5432/supplier_reliability`; the isolated generation target uses the same host and port with `DB_NAME=yottaflex_synthetic`. Credentials are supplied through environment variables and are not stored in this document.

Before generation, read-only queries found `supplier_reliability` had all 33 application tables empty, including `data_generation_run` and `generation_artifact`. Its 34th public base table was `flyway_schema_history`, containing five successful migrations. The requested dedicated database did not exist, so it was created as UTF-8 with owner `postgres`. A second clean database, `yottaflex_synthetic_repro`, was created for reproducibility. Both new targets had 33 empty application tables and five successful Flyway rows before data generation.

On all three databases, schema inspection found 34 base tables including Flyway history, 34 primary keys, 60 foreign keys (18 composite), 55 unique constraints, 134 indexes, and 86 validated check constraints. Flyway applied V1–V5 to both new databases; Hibernate `ddl-auto: validate` succeeded on application startup. Actuator health returned `UP`. Flyway emits a compatibility warning because PostgreSQL 18.6 is newer than the latest version supported by the current Flyway release (17); migrations succeeded on the actual server.

### Lock defect found and fixed

The first STARTER request was rejected before creating a run because `GenerationRunLock` read PostgreSQL's boolean `pg_try_advisory_lock` result as an integer. The error was `Bad value for type int : t`. A read-only inventory after the failure confirmed zero run records, artifacts, and operational rows. The lock now reads the result with JDBC `getBoolean(1)`. The backend clean package and live generation both passed after this fix.

### STARTER run and actual persisted counts

Run `GEN-2026-000001` completed in `yottaflex_synthetic` with status `COMPLETED`, seed `20261022`, reference date `2026-10-22`, generator version `1.0.0`, batch size `500`, and configuration hash `ab6154cc10d87a11151fd7ff072d4190365d0e19ba6b807d96a460736ad32995`. All actual counts matched the configured expected counts:

| Entity/table | Expected | Actual |
|---|---:|---:|
| Suppliers | 25 | 25 |
| Sites | 43 | 43 |
| Contracts | 50 | 50 |
| Capacity commitments | 100 | 100 |
| Purchase orders | 120 | 120 |
| PO lines | 300 | 300 |
| Shipments | 180 | 180 |
| Shipment lines | 300 | 300 |
| Delivery events | 360 | 360 |
| Receipts | 240 | 240 |
| Receipt lines | 300 | 300 |
| Inspections | 240 | 240 |
| Inspection lines | 300 | 300 |
| Invoices | 144 | 144 |
| Invoice lines | 180 | 180 |
| Risk signals | 100 | 100 |
| Compliance requirements | 12 | 12 |
| Compliance assessments | 75 | 75 |
| Compliance checks | 300 | 300 |
| Supplier status history | 25 | 25 |

### Artifact checksums

All artifacts use schema version `phase-2-v1`. These are the actual SHA-256 values returned by the completed run:

| Artifact | Rows | SHA-256 |
|---|---:|---|
| capacity_commitment | 100 | `ee649c5030df9f41cdc9e43bac15b1f72922e133399cc10d36c0abc81faf45cc` |
| compliance_assessment | 75 | `a1d68f21ddc0a101fef1dcacacacb1338a3b768b88c8468a764b779b7f3fcb1c` |
| compliance_check | 300 | `189994bd3e9ada768651a3380e9b774bcf65cad353c96e3780b0267fa3e7eb5d` |
| compliance_requirement | 12 | `c66e1cea221c205140713464fa92ae10fa0f9158f5060dccbb0f4c578910272e` |
| contract | 50 | `4da3f1c6a6a74cc4934c4586ac016a7df2f2cedf634f02ebbb545d1c0d6581a1` |
| delivery_event | 360 | `68d63b03858b62e2bbfe975ac87c1915bf624785aaf80b6c6fb6941bc69ffd93` |
| inspection | 240 | `859079930ca58e644749808604d674f7a3a1bcb4926d7fb118d82eb68164b706` |
| inspection_line | 300 | `22b0da73bb3d7e1ec28a9a3a6f595cf9f23f6782351b33880641bb52c59b0ef3` |
| invoice | 144 | `6c9c234c1bdb4b1b2335a8683207017f857a00a4becc90789768b3a9aeb483cd` |
| invoice_line | 180 | `56895a23eb2052c22e18d734a4ec0a851ef57f5d7119412c4206ff3764942f96` |
| po_line | 300 | `1b967cb023f365b84e52015aafa356325bef2d069aac5d1029ca5c082e250cea` |
| purchase_order | 120 | `455f51a3492ab5ad0b8e07ff861db702c1a7d011f3b2d95f8f50798bf72ffe24` |
| receipt | 240 | `b16b776b17897830a20cc483a98a9ca1dd751a1184d8044e6bf8388332ae75b9` |
| receipt_line | 300 | `b73a15bb24dfbab93e04642315f0a6575bdd49695706d6db7234f4c674e68fcc` |
| risk_signal | 100 | `20036ec3a6acff3d2588a729b8cce5c3ee3ea053da48bc76541693dd1997bfde` |
| shipment | 180 | `798fec037c08149e0ed8d41dc06c4ec7a68f05c53ffd0b9df8b6140438089397` |
| shipment_line | 300 | `b97249be3bcb5615402e24c8b58a588ebd2e2a8d3efbd9da498c2470414423cb` |
| site | 43 | `19f1e38ca424449b5c8fd578f35ae7a4f7a83fd1b5769072efbe5916d1354754` |
| supplier | 25 | `b29e0d7be007aac3ae3e06b4d843a430a3671594cd0157cd1b33e19cc506f7cc` |
| supplier_status_history | 25 | `315545f045ed6729e71186d624ee85608e77fd80c8871a5b722acb3f0441860a` |

### Integrity results

The pipeline's PostgreSQL validation ran successfully before marking the run complete. Additional read-only queries returned **zero violations** for duplicate business identifiers, unvalidated foreign keys, supplier/site ownership, PO/contract/supplier ownership and applicability dates, shipment/PO/line consistency, shipped and received quantity reconciliation, delivery chronology, receipt and inspection reconciliation, accepted plus rejected quantities, invoice supplier/currency and subtotal reconciliation, risk source/timestamps, compliance catalog references and chronology, and contract/capacity/risk/compliance site ownership. The database has 60 validated foreign keys, including 18 composite foreign keys.

### Reproducibility

`yottaflex_synthetic_repro` was separately created, migrated, and confirmed empty before generation. The same default configuration produced a second completed `GEN-2026-000001` run with the same configuration hash. All 20 artifact row counts and SHA-256 values matched (zero artifact differences). All 20 business-key sets matched across suppliers, sites, contracts, capacity commitments, status history, purchase orders and lines, shipments and lines, events, receipts and lines, inspections and lines, invoices and lines, risks, assessments, requirements, and compliance checks. Three representative supplier, site, purchase order, shipment, and invoice records also matched. Run IDs and execution timestamps were not part of the canonical artifact checksums.

### Failure and safety review

Runtime checks confirmed that a request while the PostgreSQL advisory lock was held returned HTTP 409, and a repeat request against the populated reproducibility target also returned HTTP 409. After both checks, the database still had one run, 25 suppliers, and 20 artifacts. The initial type-conversion failure happened before run creation and left zero records. The application does not delete or truncate generated data.

Code review confirms batches commit independently; a mid-run failure can leave committed rows, and retry is blocked while operational records remain. On a later request after it obtains the advisory lock, a stale `RUNNING` record is marked failed. Forced interruption during a batch and stale-run recovery were not injected in this verification; those behaviors remain **COMPLETED BUT NOT RUNTIME VERIFIED**.

### Build and checklist

| Task | Status | Evidence |
|---|---|---|
| Approved PostgreSQL configuration and migrations | VERIFIED | PostgreSQL 18.6; V1–V5 applied on both synthetic databases; JPA validation passed. |
| Pre-generation database safety | VERIFIED | `supplier_reliability` and both new targets were inspected; all operational tables were empty before generation. |
| STARTER generation and persisted counts | VERIFIED | `GEN-2026-000001` completed; all 20 actual counts matched expected values. |
| Integrity queries | VERIFIED | All listed duplicate, ownership, chronology, quantity, provenance, and reference checks returned zero violations. |
| Reproducibility comparison | VERIFIED | Counts, all artifact SHA-256 values, all 20 business-key sets, and representative records matched. |
| Lock contention and populated-target protection | VERIFIED | Both test requests returned HTTP 409 and wrote no additional run or artifacts. |
| Interrupted/partial batch and stale-run injection | COMPLETED BUT NOT RUNTIME VERIFIED | Code reviewed; no partial-run injection was performed. |
| Backend clean package | VERIFIED | `.\mvnw.cmd -pl backend -am clean package`: BUILD SUCCESS, 19 tests, 17 passed, 0 failed, 2 skipped (Docker Testcontainers and opt-in configured-PostgreSQL test). The opt-in configured-PostgreSQL test was then run separately against `supplier_reliability` and passed (1 test, 0 failures/skips). |
| ApplicationSmokeTest | VERIFIED | Passed independently and in the full package outside the sandbox. The earlier sandbox stall was caused by restricted JVM attach-pipe access for Mockito's inline mock-maker. |
| Frontend production build | VERIFIED | `npm --prefix frontend run build` succeeded. |
| Git history and change inventory | BLOCKED | Workspace copy has no `.git` directory; commit history and a Git diff are unavailable. |

The temporary backend process was stopped after verification. No password is recorded here. The two Phase 2 synthetic databases remain intact for inspection. Phase 3 recovery and its separate validation results are documented in [`data-validation.md`](data-validation.md), [`anomaly-fixtures.md`](anomaly-fixtures.md), [`exception-management.md`](exception-management.md), and [`ground-truth.md`](ground-truth.md). The Phase 2 operational rows were preserved.
