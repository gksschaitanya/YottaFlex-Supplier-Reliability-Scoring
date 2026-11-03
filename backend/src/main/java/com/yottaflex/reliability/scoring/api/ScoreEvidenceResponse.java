package com.yottaflex.reliability.scoring.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ScoreEvidenceResponse(
        String scoreRunId,
        String supplierId,
        String component,
        String sourceEntityType,
        String sourceBusinessId,
        long sourceRecordId,
        String disposition,
        BigDecimal contribution,
        String exclusionReason,
        OffsetDateTime evaluatedAt,
        JsonNode details) {

    public static ScoreEvidenceResponse from(String scoreRunId, String supplierId, String component,
                                             String sourceEntityType, String sourceBusinessId, long sourceRecordId,
                                             boolean included, BigDecimal contribution, String exclusionReason,
                                             OffsetDateTime evaluatedAt, JsonNode details) {
        return new ScoreEvidenceResponse(scoreRunId,supplierId,component,sourceEntityType,sourceBusinessId,
                sourceRecordId,included?"CONTRIBUTION":"EXCLUSION",contribution,exclusionReason,evaluatedAt,details);
    }
}
