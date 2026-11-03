package com.yottaflex.reliability.generation.application;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.Map;

import com.yottaflex.reliability.generation.domain.SyntheticReferenceData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GenerationIntegrityValidator {

    private final JdbcTemplate jdbcTemplate;

    public GenerationIntegrityValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void validate(GenerationContext context) {
        for (Map.Entry<String, Long> count : context.counts().asOrderedMap().entrySet()) {
            if ("compliance_requirement".equals(count.getKey())) {
                validateCatalog(count.getValue());
            } else {
                assertCount(count.getKey(), context.generationRunId(), count.getValue());
            }
        }

        Timestamp cutoff = Timestamp.from(context.referenceDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertNoRows("purchase_order site ownership", """
                SELECT COUNT(*) FROM purchase_order po
                LEFT JOIN site s ON s.id = po.site_id
                WHERE po.generation_run_id = ? AND (s.id IS NULL OR s.supplier_id <> po.supplier_id)
                """, context.generationRunId());
        assertNoRows("purchase-order contract ownership", """
                SELECT COUNT(*) FROM purchase_order po
                JOIN contract c ON c.id = po.contract_id
                WHERE po.generation_run_id = ?
                                    AND (c.supplier_id <> po.supplier_id OR (c.site_id IS NOT NULL AND c.site_id <> po.site_id)
                                             OR po.ordered_on NOT BETWEEN c.effective_from AND c.expires_on OR c.status <> 'ACTIVE')
                """, context.generationRunId());
        assertNoRows("shipment ownership", """
                SELECT COUNT(*) FROM shipment sh
                JOIN purchase_order po ON po.id = sh.purchase_order_id
                WHERE sh.generation_run_id = ?
                  AND (sh.supplier_id <> po.supplier_id OR sh.site_id <> po.site_id)
                """, context.generationRunId());
        assertNoRows("shipment line parent", """
                SELECT COUNT(*) FROM shipment_line sl
                JOIN shipment sh ON sh.id = sl.shipment_id
                JOIN po_line pl ON pl.id = sl.po_line_id
                WHERE sl.generation_run_id = ?
                  AND (sl.purchase_order_id <> sh.purchase_order_id OR pl.purchase_order_id <> sh.purchase_order_id)
                """, context.generationRunId());
        assertNoRows("shipment quantity reconciliation", """
                SELECT COUNT(*) FROM (
                    SELECT pl.id
                    FROM po_line pl
                    LEFT JOIN shipment_line sl ON sl.po_line_id = pl.id
                    WHERE pl.generation_run_id = ?
                    GROUP BY pl.id, pl.ordered_quantity
                    HAVING COALESCE(SUM(sl.shipped_quantity), 0) <> pl.ordered_quantity
                ) invalid_rows
                """, context.generationRunId());
        assertNoRows("delivery chronology", """
                SELECT COUNT(*) FROM delivery_event de
                JOIN shipment sh ON sh.id = de.shipment_id
                WHERE de.generation_run_id = ?
                  AND (de.occurred_at < sh.dispatched_at OR de.occurred_at >= ?)
                """, context.generationRunId(), cutoff);
        assertNoRows("receipt ownership", """
                SELECT COUNT(*) FROM receipt r
                JOIN shipment sh ON sh.id = r.shipment_id
                WHERE r.generation_run_id = ?
                  AND (r.supplier_id <> sh.supplier_id OR r.site_id <> sh.site_id OR r.received_at < sh.dispatched_at)
                """, context.generationRunId());
        assertNoRows("receipt quantity reconciliation", """
                SELECT COUNT(*) FROM (
                    SELECT sl.id
                    FROM shipment_line sl
                    LEFT JOIN receipt_line rl ON rl.shipment_line_id = sl.id
                    WHERE sl.generation_run_id = ?
                    GROUP BY sl.id, sl.shipped_quantity
                    HAVING COALESCE(SUM(rl.received_quantity), 0) <> sl.shipped_quantity
                ) invalid_rows
                """, context.generationRunId());
        assertNoRows("inspection quantity reconciliation", """
                SELECT COUNT(*) FROM (
                    SELECT rl.id
                    FROM receipt_line rl
                    LEFT JOIN inspection_line il ON il.receipt_line_id = rl.id
                    WHERE rl.generation_run_id = ?
                    GROUP BY rl.id, rl.received_quantity
                    HAVING COALESCE(SUM(il.inspected_quantity), 0) <> rl.received_quantity
                        OR COALESCE(SUM(il.accepted_quantity + il.rejected_quantity), 0) <> rl.received_quantity
                ) invalid_rows
                """, context.generationRunId());
        assertNoRows("invoice supplier and currency", """
                SELECT COUNT(*) FROM invoice i
                JOIN purchase_order po ON po.id = i.purchase_order_id
                WHERE i.generation_run_id = ? AND (i.supplier_id <> po.supplier_id OR i.currency <> po.currency)
                """, context.generationRunId());
        assertNoRows("invoice subtotal reconciliation", """
                SELECT COUNT(*) FROM (
                    SELECT i.id
                    FROM invoice i
                    LEFT JOIN invoice_line il ON il.invoice_id = i.id
                    WHERE i.generation_run_id = ?
                    GROUP BY i.id, i.subtotal
                    HAVING COALESCE(SUM(il.line_amount), 0) <> i.subtotal
                ) invalid_rows
                """, context.generationRunId());
        assertNoRows("risk provenance", """
                SELECT COUNT(*) FROM risk_signal
                WHERE generation_run_id = ?
                  AND (source IS NULL OR CHAR_LENGTH(TRIM(source)) = 0 OR starts_at IS NULL OR starts_at >= ?)
                """, context.generationRunId(), cutoff);
        assertNoRows("compliance check chronology", """
                SELECT COUNT(*) FROM compliance_check cc
                JOIN compliance_assessment ca ON ca.id = cc.assessment_id
                WHERE cc.generation_run_id = ?
                  AND (cc.checked_at < ca.assessed_at OR cc.requirement_id NOT BETWEEN 1 AND ?)
                """, context.generationRunId(), context.counts().complianceRequirements());
    }

    private void validateCatalog(long expectedCount) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compliance_requirement", Long.class);
        if (count == null || count != expectedCount) {
            throw new IllegalStateException("Compliance requirement catalog count mismatch: expected " + expectedCount + ", found " + count);
        }
        for (int index = 0; index < SyntheticReferenceData.REQUIREMENT_CODES.size(); index++) {
            String actualCode = jdbcTemplate.queryForObject(
                    "SELECT requirement_code FROM compliance_requirement WHERE id = ?", String.class, index + 1L);
            if (!SyntheticReferenceData.REQUIREMENT_CODES.get(index).equals(actualCode)) {
                throw new IllegalStateException("Compliance catalog code or stable ID mismatch at ID " + (index + 1));
            }
        }
    }

    private void assertCount(String table, long generationRunId, long expectedCount) {
        Long actualCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE generation_run_id = ?", Long.class, generationRunId);
        if (actualCount == null || actualCount != expectedCount) {
            throw new IllegalStateException("Generated " + actualCount + " " + table + " rows; expected " + expectedCount);
        }
    }

    private void assertNoRows(String label, String sql, Object... parameters) {
        Long invalidCount = jdbcTemplate.queryForObject(sql, Long.class, parameters);
        if (invalidCount != null && invalidCount > 0) {
            throw new IllegalStateException("Generation integrity check failed for " + label + ": " + invalidCount + " invalid rows");
        }
    }
}
