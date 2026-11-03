# Exception and quarantine management

Every invalid staging payload version produces an exception with a stable fingerprint based on detector version, fixture identity, canonical payload, and issue type. Database uniqueness on `(detector_version, fingerprint)` makes exception creation idempotent. Invalid staged records receive one quarantine record and remain held until corrected and fully revalidated.

## Lifecycle

1. Run validation; passing staged records are marked `VALID`, and findings are persisted with `OPEN` exceptions and `HELD` quarantine rows.
2. Submit a corrected JSON object. The original `payload` remains unchanged; the corrected object is saved in `corrected_payload`, and the payload version increments.
3. Run complete validation for the current payload version. A failed correction remains quarantined.
4. Resolve or accept the linked exception with a nonblank note. Actor reference is optional; status, note, actor (when supplied), and timestamp are written to `audit_event`.
5. Release is rejected unless the latest complete result for the current payload version is valid, the linked exception is resolved/accepted, and a release note is supplied. The release event is audited.

`REJECTED` and `RELEASED` are terminal quarantine dispositions. No operation deletes a staging payload or changes operational entity tables. This phase does not automatically promote corrected staging data into operational entities. Access control for actors is not yet available in the application, so actor references are audit metadata supplied by the caller, not authenticated identity claims.

The frontend at `/exceptions` loads exception and quarantine rows from backend APIs, provides detail and resolution/correction/revalidation/release actions, and renders loading, empty, and error states. It does not fabricate counts or records.

Runtime test findings and observed counts are recorded in `docs/data-validation.md`.

Runtime verification on `yottaflex_phase3_test6` created five initial OPEN exceptions and five HELD quarantine records. After correcting and revalidating all five fixture payloads, the database held five RESOLVED exceptions and five RELEASED quarantine records. The API-level integration test also confirmed that release is blocked before both revalidation and exception resolution. Five correction, five resolution (with actor and time), and five release audit entries were persisted. See [`data-validation.md`](data-validation.md) for the full run evidence.
