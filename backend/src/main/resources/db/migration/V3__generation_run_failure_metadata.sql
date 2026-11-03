ALTER TABLE data_generation_run
    ADD COLUMN failure_reason TEXT NULL,
    ADD COLUMN duration_millis BIGINT NULL CHECK (duration_millis IS NULL OR duration_millis >= 0);
