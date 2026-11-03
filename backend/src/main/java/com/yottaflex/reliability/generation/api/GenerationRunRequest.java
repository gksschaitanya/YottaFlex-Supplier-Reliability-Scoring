package com.yottaflex.reliability.generation.api;

import java.time.LocalDate;

import com.yottaflex.reliability.generation.domain.DatasetScale;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record GenerationRunRequest(
        DatasetScale scale,
        Long seed,
        LocalDate referenceDate,
        @Min(1) @Max(10_000) Integer batchSize) {
}
