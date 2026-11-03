# Generation Configuration

All settings are Spring Boot properties under `platform.generation` and may be overridden with environment variables. Defaults are fixed and do not read the system clock for business data.

| Property | Environment variable | Default | Purpose |
|---|---|---:|---|
| `seed` | `GENERATION_SEED` | `20261022` | Seed for namespaced deterministic streams |
| `reference-date` | `GENERATION_REFERENCE_DATE` | `2026-10-22` | Fixed date anchoring the 365-day business window |
| `generator-version` | `GENERATOR_VERSION` | `1.0.0` | Version recorded with each run/configuration hash |
| `default-scale` | `GENERATION_SCALE` | `STARTER` | Scale selected when the request omits `scale` |
| `batch-size` | `GENERATION_BATCH_SIZE` | `500` | JDBC rows per bounded insert transaction |
| `maximum-batch-size` | `GENERATION_MAXIMUM_BATCH_SIZE` | `2000` | Hard request limit for batch size |
| `enabled` | `GENERATION_ENABLED` | `true` | Enables/disables generation endpoint |
| `starter.suppliers` | `GENERATION_STARTER_SUPPLIERS` | `25` | STARTER supplier count |
| `starter.purchase-orders` | `GENERATION_STARTER_PURCHASE_ORDERS` | `120` | STARTER purchase-order count |
| `starter.shipments` | `GENERATION_STARTER_SHIPMENTS` | `180` | STARTER shipment count |

Request fields `scale`, `seed`, `referenceDate`, and `batchSize` override their configured defaults for that run. Invalid starter relationships, excessive batch sizes, sequence overflow, a mismatched compliance catalog, or a non-empty operational target cause a fail-closed response. FULL counts are fixed by the baseline and are not adjustable.

Each `DataGenerationRun.configuration` JSON snapshot stores the effective seed, date, scale, generator version, batch size, business-window length, and expected entity counts. `config_hash` is SHA-256 over this normalized configuration. Execution timestamps are recorded separately and excluded from canonical dataset checksums.
