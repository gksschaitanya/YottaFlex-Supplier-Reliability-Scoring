package com.yottaflex.reliability.generation.domain;

import java.time.Instant;

public record SyntheticRiskSignal(
        String riskType,
        String severity,
        String source,
        String sourceReference,
        Instant startsAt,
        Instant endsAt,
        String description,
        Instant recordedAt) {

    public SyntheticRiskSignal {
        if (riskType == null || severity == null || source == null || source.isBlank()
                || startsAt == null || recordedAt == null || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Risk signal requires type, severity, source, timestamps, and description");
        }
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw new IllegalArgumentException("Risk signal end cannot precede its start");
        }
    }
}
