package com.yottaflex.reliability.generation.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GenerationTargetGuard {

    private static final String[] OPERATIONAL_TABLES = {
            "supplier", "site", "supplier_status_history", "contract", "purchase_order", "po_line",
            "shipment", "shipment_line", "delivery_event", "receipt", "receipt_line", "inspection",
            "inspection_line", "invoice", "invoice_line", "capacity_commitment", "compliance_assessment",
            "compliance_check", "risk_signal", "supplier_score", "score_run", "staging_record"
    };

    private final JdbcTemplate jdbcTemplate;

    public GenerationTargetGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void requireSyntheticOnlyEmptyTarget() {
        for (String table : OPERATIONAL_TABLES) {
            Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            if (rowCount != null && rowCount > 0) {
                throw new IllegalStateException("Refusing generation: target table '" + table + "' already contains records");
            }
        }
    }
}
