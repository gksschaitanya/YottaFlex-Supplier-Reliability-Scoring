# Phase 3 anomaly fixtures

Fixtures are deterministic by `staging_key` and `fixture_code`; repeated fixture requests do not create duplicate staging records. They reference the latest completed generation run only to satisfy staging provenance. Anomaly-specific IDs and payloads are synthetic and isolated from operational tables.

| Fixture | Invalid condition | Expected validator result | Correction that can pass |
|---|---|---|---|
| `ANOMALY_SHIPMENT_NO_PO` | `purchaseOrderId` is absent from `purchase_order`. | Invalid, `PARENT_REFERENCE_MISSING`, HIGH. | Replace it with an existing PO business ID. |
| `ANOMALY_DELIVERY_BEFORE_DISPATCH` | Event is one hour before the persisted shipment dispatch. | Invalid, `EVENT_CHRONOLOGY_INVALID`, HIGH. | Preserve the actual shipment and dispatch timestamp; set event time at or after dispatch. |
| `ANOMALY_FUTURE_SCORE_INPUT` | Candidate event is one second after the fixed `scoreRunAsOf` cutoff. | Invalid, `FUTURE_EVIDENCE_AFTER_CUTOFF`, HIGH. | Set event time at or before the cutoff. This is not a scoring engine. |
| `ANOMALY_RISK_MISSING_SOURCE_OR_TIME` | Both source and start time are null. | Invalid, `REQUIRED_FIELD_MISSING`, HIGH. | Supply a nonblank source and valid timestamp. |
| `ANOMALY_CROSS_SUPPLIER_SITE` | A site from one supplier is paired with a different supplier ID. | Invalid, `CROSS_SUPPLIER_SITE`, HIGH. | Use the supplier that owns the referenced site. |

The expected results are duplicated in machine-readable, API-isolated ground-truth artifacts at `data/ground-truth/phase3-anomaly-fixtures.csv` and `.json`. Runtime results belong in `docs/data-validation.md` after a real PostgreSQL execution.

The PostgreSQL 18.6 runtime run recorded one invalid version-1 result for each fixture, with the expected issue code and HIGH severity. All five corrected version-2 payloads passed complete revalidation; the repeated fixture run also passed them without adding exceptions or quarantines. See the detailed counts and test evidence in [`data-validation.md`](data-validation.md).
