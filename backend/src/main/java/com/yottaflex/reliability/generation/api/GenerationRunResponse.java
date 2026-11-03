package com.yottaflex.reliability.generation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.yottaflex.reliability.generation.domain.DatasetScale;
import com.yottaflex.reliability.generation.domain.GenerationRunStatus;

public record GenerationRunResponse(
        String runId,
        GenerationRunStatus status,
        DatasetScale scale,
        long seed,
        LocalDate referenceDate,
        String generatorVersion,
        String configHash,
        Instant startedAt,
        Instant completedAt,
        Long durationMillis,
        String failureReason,
        Map<String, Long> expectedRowCounts,
        Map<String, Long> rowCounts,
        List<GenerationArtifactResponse> artifacts) {
}
