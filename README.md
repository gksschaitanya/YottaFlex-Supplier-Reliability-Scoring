# Supplier Reliability Intelligence Platform

A Java 21 / Spring Boot 3.5.7 modular monolith with PostgreSQL 18 and a React/Vite shell. The repository contains the Phase 1 foundation, deterministic synthetic source-data generation from Phase 2, Phase 3 staging validation, anomaly fixtures, exceptions and quarantine workflows, and the in-progress Phase 4 scoring foundation. Phase 4 calculations and APIs have started but are not fully verified against all acceptance criteria; see the Phase 4 documentation for specific limits.

## Technology Stack

- Java 21, Spring Boot 3.5.7, Maven Wrapper
- Spring Web, Spring Data JPA, Hibernate, Jakarta Validation
- PostgreSQL JDBC, Flyway, MapStruct, Lombok
- Actuator, Springdoc OpenAPI, JUnit 5, Mockito, Testcontainers
- React, Vite, Redux Toolkit, React Router, Axios, Recharts, Bootstrap

## Architecture

This is one deployable application and database, organized as a modular monolith. Backend business boundaries live under `com.yottaflex.reliability`; each module separates API, application, domain, and infrastructure concerns. REST controllers use DTOs, service logic owns workflows, Spring Data repositories own persistence, and Flyway migrations define the schema. Synthetic data is generated in bounded JDBC batches with a deterministic seed and fixed reference date.

## Prerequisites

- JDK 21
- PostgreSQL 18 (PostgreSQL 17 is also compatible with the current Flyway release)
- Node.js 20+ and npm 10+ (the production build was executed with Node 20.16.0/npm 10.8.1)
- DBeaver Community Edition is optional
- Docker Desktop is optional for PostgreSQL Testcontainers integration tests

## Isolated Phase 4 Database

Use only the isolated `yottaflex_phase4_test` database for Phase 4 verification. Do not point application startup or tests at `supplier_reliability`, Phase 2, or Phase 3 databases. V8 captures its cutover instant with `transaction_timestamp()` inside its first Flyway transaction and captures the active-site baseline from that transaction's repeatable-read snapshot. Hibernate uses `ddl-auto: validate`, and Flyway clean is disabled. V8 remains unapplied because the local PostgreSQL service is unavailable; prepare/inspect the isolated target before starting the application.

Install PostgreSQL 18 (the previously recorded isolated test environment used 18.6) using the Windows installer or your managed PostgreSQL installation. Start the PostgreSQL service through Windows Services only when you are ready to run the database checks. Never point these instructions at a protected Phase 2/3 database. If `yottaflex_phase4_test` is absent, create it only after confirming it is absent and that the intended source template is the approved clean Phase 2 reproduction database. The helper in `scripts/phase4/ProvisionTestDatabase.java` supports `--inspect` and `--create-if-absent`; it refuses to overwrite an existing target. Follow [docs/phase4-test-environment.md](docs/phase4-test-environment.md). Do not run Flyway or the application against a database whose contents have not been inspected. V8 and the active-site baseline are not applied/captured in the current submission verification.

## DBeaver Connection

Create a PostgreSQL connection to the isolated Phase 4 test database with:

- Host: `localhost`
- Port: `5432`
- Database: `yottaflex_phase4_test`
- Username: `postgres`
- Password: your local PostgreSQL password, entered in DBeaver only

After a successful isolated startup, inspect the `public` schema and `flyway_schema_history` in this database only.

## Configure Windows Environment

`.env.example` is documentation only; Spring Boot does not load it automatically. Set variables in the same VS Code PowerShell terminal that will run Maven. The password prompt below is hidden and the password is not written to a project file:

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "5432"
$env:DB_NAME = "yottaflex_phase4_test"
$env:DB_USERNAME = "postgres"
$secret = Read-Host "PostgreSQL password" -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new("", $secret).Password
Remove-Variable secret
$env:SERVER_PORT = "8080"
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173"
$env:GENERATION_SEED = "20261022"
$env:GENERATION_REFERENCE_DATE = "2026-10-22"
$env:GENERATOR_VERSION = "1.0.0"
$env:GENERATION_SCALE = "STARTER"
$env:GENERATION_BATCH_SIZE = "500"
```

The database variables are `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`. Avoid committing `.env` files or secrets.

## Build and Test

From the repository root in PowerShell:

```powershell
.\mvnw.cmd -pl backend -am clean package
npm --prefix frontend run build
```

These are the submission build commands. The Phase 4 PostgreSQL test is explicitly gated and must target `yottaflex_phase4_test` only:

```powershell
$env:DB_NAME = "yottaflex_phase4_test"
.\mvnw.cmd -pl backend "-Dpostgres.phase4.integration=true" "-Dtest=Phase4PostgresIntegrationTest" test
```

The integration test guard rejects any other database. Do not set the gate until the isolated test database is confirmed safe and PostgreSQL is reachable.

For source-only offline verification without a PostgreSQL connection, exclude all DB integration classes explicitly:

```powershell
.\mvnw.cmd -o -pl backend -am clean package "-Dtest=*,!ApplicationIntegrationTest,!ConfiguredPostgresIntegrationTest,!Phase3PostgresIntegrationTest,!Phase4PostgresIntegrationTest"
npm --prefix frontend run build
```

The Maven wrapper may require its distribution already cached for `-o`; remove `-o` if it is not cached (this can download Maven, but does not connect to PostgreSQL).

## Start the Application

In the configured PowerShell terminal:

```powershell
.\mvnw.cmd -pl backend -am spring-boot:run
```

The application applies pending migrations at startup. Health is available at [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health), and Swagger UI is available at [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html).

## Start the Frontend

Open a second VS Code terminal:

```powershell
Set-Location frontend
Copy-Item .env.example .env.local
npm install
npm run dev
```

The `/scoring` route uses the `/api/v1/score-runs` endpoints for run history, rankings, supplier scorecards, component metrics, evidence exploration, and persisted report downloads. Set `VITE_API_BASE_URL` if the backend is not at `http://localhost:8080`. V8 migration and Phase 4 PostgreSQL acceptance remain outstanding; consult `docs/scoring-methodology.md` before using scores for supplier decisions.

The Vite development server defaults to `http://localhost:5173`. Set `VITE_API_BASE_URL` in the ignored `frontend/.env.local` if the backend URL differs.

## Scoring API walkthrough

After backend startup and only against a reviewed isolated dataset, start one asynchronous run and poll its returned ID:

```powershell
$run = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/score-runs" -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
$runId = $run.runId
Invoke-RestMethod "http://localhost:8080/api/v1/score-runs/$runId/status"
Invoke-RestMethod "http://localhost:8080/api/v1/score-runs/$runId/summary"
Invoke-RestMethod "http://localhost:8080/api/v1/score-runs/$runId/scores?page=0&size=20"
Invoke-RestMethod "http://localhost:8080/api/v1/score-runs/$runId/export?report=scores&format=csv"
```

The create endpoint does not accept a body; the run uses the persisted default configuration. The scoring UI is available at `/scoring`. See [docs/scoring-api.md](docs/scoring-api.md) for filters, evidence pagination, and report types. Database-backed execution, response contents, and generated exports are not verified in this submission sprint.

## Generate Synthetic Data

Generation is asynchronous and only runs against an empty operational target. Submit STARTER from PowerShell after the backend is running:

```powershell
$body = @{ scale = "STARTER" } | ConvertTo-Json
$run = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/v1/generation/runs" `
  -ContentType "application/json" -Body $body
$run
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)"
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)/counts"
Invoke-RestMethod "http://localhost:8080/api/v1/generation/runs/$($run.runId)/artifacts"
```

FULL uses fixed baseline volumes: 1,000 suppliers, 10,000 purchase orders, 15,000 shipments, and 20,000 inspections. Supporting counts are documented in [docs/data-volume-reference.md](docs/data-volume-reference.md). See [docs/data-generation.md](docs/data-generation.md), [docs/generation-configuration.md](docs/generation-configuration.md), and [docs/generation-performance.md](docs/generation-performance.md) for reproducibility and safe regeneration.

Generation refuses populated operational targets and never truncates data. A failed run may leave committed batches; inspect it and use a separate, clean synthetic-only database for another run.

## PostgreSQL Verification Queries

List public application tables:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_type = 'BASE TABLE'
  AND table_name <> 'flyway_schema_history'
ORDER BY table_name;
```

Inspect migration history:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

## Phase 3 Validation and Exceptions

The `/exceptions` workspace displays persisted exceptions and quarantined staging records from the backend. The API supports fixture validation, exception filtering and detail, correction storage, complete revalidation, audited resolution, and release after validation. Source staging payloads are retained unchanged. Phase 3 does not write the fixtures or ground-truth labels into operational supplier data and does not implement supplier scoring.

See [`docs/data-validation.md`](docs/data-validation.md), [`docs/anomaly-fixtures.md`](docs/anomaly-fixtures.md), [`docs/exception-management.md`](docs/exception-management.md), and [`docs/ground-truth.md`](docs/ground-truth.md) for rules and previously recorded Phase 3 runtime evidence. Current Phase 4 PostgreSQL runtime verification and V8 migration application have not been performed. Full-dataset throughput and memory benchmarks have not yet been recorded. The current Flyway release may log a PostgreSQL 18 compatibility warning; verify against the isolated test target before submission sign-off.
# Compliance scoring status

Compliance scoring uses an explicit 12-code mandatory, critical, supplier-scope, and site-scope policy. V8 source adds prospective policy/scope, assessment/check snapshot, status, and per-item evidence-event history with a repeatable-read active-site baseline. V8 captures the actual transaction timestamp in the first migration transaction and uses its repeatable-read snapshot for the active-site baseline; migration execution and PostgreSQL verification remain pending. No legacy availability events are fabricated. See `docs/scoring-methodology.md` and `docs/scoring-reproducibility.md`.
