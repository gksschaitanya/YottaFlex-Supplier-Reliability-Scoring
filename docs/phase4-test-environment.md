# Phase 4 PostgreSQL test environment

## Isolation boundary

Automated Phase 4 tests use only `yottaflex_phase4_test`. This is a separately provisioned clone of `yottaflex_synthetic_repro`; tests do not connect to `yottaflex_phase4_dev`, the Phase 2 source/repro databases, or any Phase 3 database. The test profile is in `backend/src/test/resources/application-phase4-test.yml` and defaults to the approved database name.

`Phase4TestDatabaseGuard` runs as an application-context initializer before Flyway or Hibernate starts. It checks both `DB_NAME` and the database name in the resolved JDBC URL and refuses any other target. Keep the two settings aligned. Credentials are supplied only through environment variables.

## Inspect/provision

The non-destructive helper `scripts/phase4/ProvisionTestDatabase.java` supports `--inspect` and `--create-if-absent`. It does not drop, truncate, or overwrite anything. If the target exists, it reports counts and exits without changing it. When absent, it checks that `yottaflex_synthetic_repro` has the expected Phase 2 counts, exactly one completed generation run, no Phase 3 staging/quarantine rows, no score run, and no active sessions before using it as a PostgreSQL template.

Compile/run the helper from PowerShell with the PostgreSQL JDBC driver on the classpath. Provide `DB_HOST`, `DB_PORT`, `DB_USERNAME`, and `DB_PASSWORD` in the process environment; the helper never prints credentials. Review the target inventory before running tests.

## Run the Phase 4 PostgreSQL suite

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "5432"
$env:DB_NAME = "yottaflex_phase4_test"
$env:DB_USERNAME = "postgres"
# Set DB_PASSWORD securely in the current process environment.
.\mvnw.cmd -pl backend -am "-Dpostgres.phase4.integration=true" "-Dtest=Phase4PostgresIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

The integration test deliberately has no automatic database cleanup. It appends immutable score runs and checks that supplier and Phase 3 staging/exception/quarantine row counts do not change. The dedicated test database may therefore accumulate audit runs across repeated executions. Provision a different explicitly reviewed test database if a clean dataset is needed; never drop/recreate this target automatically.

## Test matrix

| Requirement | State | Current test coverage |
|---|---|---|
| Run creation, persistence, and async terminal status | Implemented | Isolated PostgreSQL API integration test |
| Supplier score persistence | Implemented | Row count matches suppliers |
| Component metric persistence | Implemented | Six component rows per supplier and source count rows |
| Evidence record persistence | Implemented for current calculators | PostgreSQL test verifies all six components, references, ownership, cutoff, and exclusions |
| Delivery correction | Implemented; unit verified | Focused Java tests cover delivery timing, quantity aggregation, statuses, exclusions, cutoff, and metrics/evidence consistency; PostgreSQL path pending fresh run |
| Scorecard API | Implemented | Integration request asserts successful response |
| Evidence API pagination and filters | Implemented | PostgreSQL test verifies nonempty DTO results, sorting, filters, validation, and stable repeat reads |
| Historical cutoff behavior | Partially verified | Integration asserts evidence source timestamps and evaluation time do not exceed each run cutoff; mutation-after-cutoff invariance remains pending |
| Concurrent run protection | Pending | Partial unique database index exists; no API race test |
| CSV and JSON exports | Source implemented; unit tested | `ScoringExportServiceTest` verifies deterministic export formatting and supported report/format behavior; live endpoint and database-backed export remain unverified in the current submission sprint |

The suite is opt-in. Docker/Testcontainers tests remain separately skipped when Docker is unavailable; the Phase 3 test remains opt-in and is not part of this Phase 4 suite.

## Execution record (2026-11-02)

`yottaflex_phase4_test` was absent before provisioning. The helper verified the source template had 25 suppliers, 120 purchase orders, 180 shipments, one completed generation run, no Phase 3 staging/exception/quarantine rows, no score runs, and no active sessions. The target was created from the verified template and inspected at Flyway V5 before application startup. The integration test validated V1–V7, applied V6–V7 to this clone, and JPA schema validation succeeded. PostgreSQL reports version 18.6; the bundled Flyway version warns that formal compatibility is through PostgreSQL 17 although these migrations completed successfully.

The opt-in focused run completed with **2 PostgreSQL integration tests and 3 unit tests; 0 failures, 0 errors, 0 skipped**. It verified completed run status, 25 supplier score rows, 150 component rows, 150 source-observation rows, evidence for all six component types, source row existence and supplier ownership, cutoff compliance, exclusion reasons, paginated/filterable DTO responses, and stable repeated reads. The test also injected a PostgreSQL evidence-write failure and verified zero score, component, observation-count, and evidence rows remained for that failed run. Supplier and Phase 3 staging/exception/quarantine row counts were unchanged. No operational data cleanup was performed; the isolated test DB retains append-only run history and should be retained as a test target.

The test asserts positive evidence for each of the six components and checks the selected supplier's API totals against persisted contribution/exclusion counts. It did not emit a run-wide per-component/per-supplier aggregate report, so those exact aggregate counts are not recorded here. On a subsequent package verification attempt the local PostgreSQL listener at `localhost:5432` was unreachable; no database restart, reset, or data modification was attempted. The focused database-independent evidence unit tests passed again (3 tests, 0 failures/errors/skips), `clean package -DskipTests` packaged successfully, and the frontend production build passed. A full clean package with the PostgreSQL suite must be rerun once the dedicated test server is available.

The delivery correction unit run completed with **14 tests, 0 failures, 0 errors, 0 skipped** (10 delivery calculator tests plus 4 evidence mapper/writer tests). `clean package -DskipTests` succeeded and `npm --prefix frontend run build` succeeded. PostgreSQL integration was not attempted because the local listener was unavailable; the updated integration assertions have therefore not been executed against `yottaflex_phase4_test`.

## Final submission check (2026-11-03)

The configured isolated target was `yottaflex_phase4_test`. A single TCP probe to `localhost:5432` returned `TcpTestSucceeded=False`, so database work stopped without retries. V8 was not applied; no cutover timestamp or actual active-site baseline count exists yet. The offline backend clean package executed 103 tests with 0 failures, 0 errors, and 0 skipped, excluding PostgreSQL integration classes. The frontend production build passed. Isolated PostgreSQL integration and runtime scoring/API/export checks remain unverified.

## V8 source correction (2026-11-03)

V8 remains unapplied. The migration captures the actual cutover timestamp in its first Flyway transaction and uses the same repeatable-read database snapshot for the active-site baseline. The offline backend clean package executed 103 tests with 0 failures, 0 errors, and 0 skipped, excluding all PostgreSQL integration classes. Vite production build succeeded. The new V8 integration assertions cover empty-catalog bootstrap, later requirement/supplier/site association, populated policy mapping, snapshot no-backfill, event identity, ordered multi-supplier locks, and rollback, but require isolated PostgreSQL verification before the implementation can be considered runtime verified. One TCP availability probe to localhost:5432 returned false; no PostgreSQL session, migration, SQL, or score run was attempted.
