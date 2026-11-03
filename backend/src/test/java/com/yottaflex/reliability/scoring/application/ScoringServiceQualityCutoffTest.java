package com.yottaflex.reliability.scoring.application;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceQualityCutoffTest {
    @Test
    void qualityAggregateBoundsInspectionAndEverySourceCreationTimestampAtRunCutoff() {
        assertThat(ScoringService.QUALITY_AGGREGATE_SQL)
                .contains("i.created_at<=?")
                .contains("r.created_at<=?")
                .contains("rl.created_at<=?")
                .contains("il.created_at<=?");
    }

    @Test
    void qualityAggregateBindsTheSameHistoricalCutoffForAllSourceRecords() {
        LocalDateTime cutoff = LocalDateTime.parse("2026-10-22T12:00:00");
        LocalDateTime windowStart = cutoff.minusDays(365);

        var args = ScoringService.qualityQueryArguments(17L, windowStart, cutoff);

        assertThat(args).containsExactly(17L, Timestamp.valueOf(windowStart), Timestamp.valueOf(cutoff),
                Timestamp.valueOf(cutoff), Timestamp.valueOf(cutoff), Timestamp.valueOf(cutoff), Timestamp.valueOf(cutoff));
    }
}
