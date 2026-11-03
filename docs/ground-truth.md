# Ground-truth isolation

Phase 3 expected labels are stored only in:

- `data/ground-truth/phase3-anomaly-fixtures.csv`
- `data/ground-truth/phase3-anomaly-fixtures.json`

These files define the fixture code, expected validity, expected issue type/severity, and rule. They are development/test artifacts and are not read by the Spring application, returned by any API, or inserted into `staging_record`, `exception_record`, `quarantined_record`, or other database tables. The runtime creates fixture inputs from code and validates them independently, so expected labels cannot shortcut the validation result.

The future evidence fixture checks only `eventAt <= scoreRunAsOf`. It neither runs scoring nor adds score data. Operational Phase 2 rows remain unchanged; the integration test records before/after supplier, purchase-order, and shipment row counts.

PostgreSQL verification compared the five runtime findings with this expected-label artifact: all five issue codes and invalid outcomes matched. Each corrected payload passed the runtime validators. Ground-truth files were not inserted into PostgreSQL or exposed by the API.
