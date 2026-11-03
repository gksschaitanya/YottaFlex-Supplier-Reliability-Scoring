ALTER TABLE data_generation_run
    ADD COLUMN configuration JSONB NULL;

UPDATE data_generation_run
SET configuration = jsonb_build_object('legacyRun', TRUE)
WHERE configuration IS NULL;

ALTER TABLE data_generation_run
    ALTER COLUMN configuration SET NOT NULL;
