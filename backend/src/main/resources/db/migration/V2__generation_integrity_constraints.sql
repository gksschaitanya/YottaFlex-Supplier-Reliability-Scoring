ALTER TABLE receipt_line
    ADD CONSTRAINT uk_receipt_line_receipt_shipment_line UNIQUE (receipt_id, shipment_line_id);

ALTER TABLE contract
    ADD CONSTRAINT ck_contract_id_format CHECK (contract_id ~ '^CON-[0-9]{4}-[0-9]{6}$');

ALTER TABLE receipt
    ADD CONSTRAINT ck_receipt_id_format CHECK (receipt_id ~ '^RCV-[0-9]{4}-[0-9]{6}$');

ALTER TABLE inspection
    ADD CONSTRAINT ck_inspection_id_format CHECK (inspection_id ~ '^INS-[0-9]{4}-[0-9]{6}$');

ALTER TABLE invoice
    ADD CONSTRAINT ck_invoice_id_format CHECK (invoice_id ~ '^INV-[0-9]{4}-[0-9]{6}$');

ALTER TABLE capacity_commitment
    ADD CONSTRAINT ck_capacity_commitment_id_format CHECK (commitment_id ~ '^CAP-[0-9]{4}-[0-9]{6}$');

ALTER TABLE compliance_assessment
    ADD CONSTRAINT ck_compliance_assessment_id_format CHECK (assessment_id ~ '^CMP-[0-9]{4}-[0-9]{6}$');

ALTER TABLE risk_signal
    ADD CONSTRAINT ck_risk_id_format CHECK (risk_id ~ '^RSK-SUP-[0-9]{6}-[0-9]{4}-[0-9]{2}$');

ALTER TABLE data_generation_run
    ADD CONSTRAINT ck_generation_run_id_format CHECK (run_id ~ '^GEN-[0-9]{4}-[0-9]{6}$');
