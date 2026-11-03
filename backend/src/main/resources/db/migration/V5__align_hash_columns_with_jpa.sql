ALTER TABLE data_generation_run
    ALTER COLUMN config_hash TYPE VARCHAR(64);

ALTER TABLE generation_artifact
    ALTER COLUMN sha256 TYPE VARCHAR(64);
