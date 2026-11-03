# Scoring API

Base path is `/api/v1`. All responses are API DTO/maps, not JPA entities. Run management currently exposes:

- `POST /score-runs` (optional `Idempotency-Key` header; returns 202 and a run ID)
- `GET /score-runs?page=0&size=20`
- `GET /score-runs/{runId}`, `/status`, and `/summary`
- `GET /score-runs/{runId}/scores?page=0&size=20&classification=GOOD&minScore=65&maxScore=100&componentStatus=AVAILABLE&criticalComplianceViolation=false&supplier=Acme&sort=score`
- `GET /score-runs/{runId}/scores/{supplierId}`
- `GET /score-runs/{runId}/rankings?page=0&size=20`
- `GET /score-runs/{runId}/scores/{supplierId}/components`
- `GET /score-runs/{runId}/scores/{supplierId}/evidence?page=0&size=50&sort=evaluatedAt,desc&component=QUALITY&included=true&sourceEntityType=INSPECTION_LINE`
- `GET /score-runs/{runId}/scores/{supplierId}/components/{component}/evidence?page=0&size=50`
- `GET /suppliers/{supplierId}/score-history`

Evidence routes return `ScoreEvidenceResponse` DTOs, validate that the run and supplier exist and that the supplier has a score in that run, and support component, inclusion, source type, pagination, and sorting filters. Supported sort keys are `evaluatedAt`, `sourceBusinessId`, `contribution`, and `sourceEntityType`; direction is `asc` or `desc`. Page numbers start at zero and requested size is limited to 100. The default sort is `evaluatedAt,desc`.

Evidence is persisted in `score_evidence` within the same transaction as score results. Read endpoints do not write evidence. The export route is `GET /score-runs/{runId}/export?report=scores|components|metrics|evidence|exclusions|run&format=json|csv&page=0&size=1000`. Data exports are paginated (maximum 10,000 rows per page); use subsequent pages for the full report. `run` is JSON-only and contains run metadata, configuration snapshot, and summary counts. CSV uses stable snake_case columns, ISO timestamp strings, CRLF records, quoted cells, and formula-prefix escaping for text cells. `components` and `metrics` contain persisted component metrics; `evidence` and `exclusions` contain persisted evidence rows. Exports do not trigger recalculation. For evidence behavior and known scoring-policy limitations, see [`score-explainability.md`](score-explainability.md). Run options and endpoint-specific OpenAPI descriptions remain pending.

Score and ranking endpoints support classification, inclusive minimum/maximum overall score, component status, critical compliance indicator, supplier ID/name search, pagination, and sort keys `score`, `scoreAsc`, `supplierId`, `name`, `coverage`, or `classification`. Rankings default to minimum score zero so suppliers without valid overall scores remain available through the scores endpoint but are not ranked.
# Compliance scorecard fields

The existing supplier scorecard and paginated evidence endpoints expose the COMPLIANCE component's persisted `metrics`, `componentStatus`, numerator/denominator, and source evidence. Evidence details include scope, mandatory/critical policy, assessment/check identity, availability at cutoff, exclusions, and escalation. The frontend reads these API response fields; it does not synthesize compliance values. V8 is forward-only, unapplied, and captures its cutover timestamp in the first migration transaction. Compliance SQL and migration behavior are not runtime verified.
