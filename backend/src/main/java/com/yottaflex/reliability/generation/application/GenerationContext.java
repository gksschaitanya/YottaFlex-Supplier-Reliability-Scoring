package com.yottaflex.reliability.generation.application;

import java.time.LocalDate;

import com.yottaflex.reliability.generation.domain.DatasetScale;
import com.yottaflex.reliability.generation.domain.GenerationCounts;

public record GenerationContext(
        long generationRunId,
        String runId,
        long seed,
        LocalDate referenceDate,
        DatasetScale scale,
        GenerationCounts counts,
        int batchSize) {
}
