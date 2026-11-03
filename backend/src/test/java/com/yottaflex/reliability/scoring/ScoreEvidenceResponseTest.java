package com.yottaflex.reliability.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.reliability.scoring.api.ScoreEvidenceResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreEvidenceResponseTest {
    @Test
    void mapsIncludedEvidenceToContributionAndRetainsStableReferences() throws Exception {
        var details = new ObjectMapper().readTree("{\"orderedQuantity\":12}");
        var evaluatedAt = OffsetDateTime.parse("2026-11-02T12:00:00Z");
        var response = ScoreEvidenceResponse.from("SR-test", "SUP-000001", "DELIVERY", "PO_LINE",
                "PO-2026-000001:LINE:1", 77L, true, BigDecimal.ONE, null, evaluatedAt, details);

        assertThat(response.disposition()).isEqualTo("CONTRIBUTION");
        assertThat(response.sourceBusinessId()).isEqualTo("PO-2026-000001:LINE:1");
        assertThat(response.sourceRecordId()).isEqualTo(77L);
        assertThat(response.details().path("orderedQuantity").asInt()).isEqualTo(12);
    }

    @Test
    void mapsExcludedEvidenceAndKeepsItsReason() {
        var response = ScoreEvidenceResponse.from("SR-test", "SUP-000001", "QUALITY", "INSPECTION_LINE",
                "INSP-0001:RECEIPT_LINE:3", 91L, false, null, "ZERO_INSPECTED_QUANTITY",
                OffsetDateTime.parse("2026-11-02T12:00:00Z"), new ObjectMapper().createObjectNode());

        assertThat(response.disposition()).isEqualTo("EXCLUSION");
        assertThat(response.exclusionReason()).isEqualTo("ZERO_INSPECTED_QUANTITY");
        assertThat(response.contribution()).isNull();
    }
}
