package com.yottaflex.reliability.generation.api;

import java.util.Map;

public record GenerationCountsResponse(
        String runId,
        Map<String, Long> expectedCounts,
        Map<String, Long> persistedCounts) {
}
