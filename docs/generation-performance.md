# Generation Performance

## Bounded Work

Each entity writer emits rows lazily in deterministic sequence order. `DeterministicBatchWriter` holds at most the configured batch size of row objects, executes one JDBC batch in its own transaction, then releases that chunk. It does not create a transaction for the full dataset, one task/future per row, or an in-memory collection of all operational rows. Hibernate JDBC batching is configured for future entity writes; bulk synthetic rows use JDBC batch execution against PostgreSQL.

A one-thread bounded executor runs at most one generation at a time in this application instance. A PostgreSQL session-level advisory lock held on a dedicated connection protects the target across application instances. The batch writer logs entity, batch ordinal, and row count. Run metadata records elapsed duration; artifacts record exact row counts and checksums.

The generator uses calculated stable IDs and parent mappings rather than N+1 repository reads. It writes explicit generated IDs so later stages can reference parents without loading full parent entities. A post-run set of aggregate SQL checks validates exact counts and reconciliation.

## Memory and Database Considerations

Memory for row staging is O(batch size), plus small fixed configuration/catalog state. Database time, WAL usage, indexes, disk space, and lock duration scale with inserted volume. FULL creates roughly 250,000 operational/reference rows across 20 artifacts; ensure the PostgreSQL target has adequate disk and WAL capacity. Keep indexed FK and unique constraints enabled during generation for integrity.

Adjust `GENERATION_BATCH_SIZE` conservatively and keep it at or below `GENERATION_MAXIMUM_BATCH_SIZE`. Larger batches reduce round trips but use more memory per transaction and increase rollback work if a batch fails.

## Measurements

No full-dataset timing or memory benchmark has been run yet. Record actual STARTER and FULL durations, peak process memory, row rates, and PostgreSQL storage after the first validated generation run; do not infer those metrics from unit tests.
