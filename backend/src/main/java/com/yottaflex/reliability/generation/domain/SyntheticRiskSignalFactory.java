package com.yottaflex.reliability.generation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.SplittableRandom;

public final class SyntheticRiskSignalFactory {

    private static final String[] TYPES = {"WEATHER", "CAPACITY", "FINANCIAL", "REGULATORY", "SUPPLY"};
    private static final String[] SEVERITIES = {"LOW", "MEDIUM", "HIGH", "CRITICAL"};

    private SyntheticRiskSignalFactory() {
    }

    public static SyntheticRiskSignal forRecord(long seed, long sequence, LocalDate referenceDate) {
        SplittableRandom random = SeededRandomFactory.forRecord(seed, "risk-signal", sequence);
        LocalDate startDate = referenceDate.minusDays(random.nextInt(365));
        boolean active = random.nextInt(100) < 42;
        LocalDate endDate = active ? null : startDate.plusDays(random.nextInt(1, 31));
        if (endDate != null && endDate.isAfter(referenceDate)) {
            endDate = referenceDate;
        }
        String type = TYPES[random.nextInt(TYPES.length)];
        String severity = SEVERITIES[random.nextInt(SEVERITIES.length)];
        Instant startsAt = startDate.atTime(7 + random.nextInt(12), random.nextInt(60)).toInstant(ZoneOffset.UTC);
        Instant endsAt = endDate == null ? null : endDate.atTime(23, 0).toInstant(ZoneOffset.UTC);
        Instant recordedAt = startDate.atTime(20, 0).toInstant(ZoneOffset.UTC);
        return new SyntheticRiskSignal(type, severity, "SYNTHETIC_RISK_FEED", "RSK-SRC-" + sequence,
                startsAt, endsAt, "Synthetic " + type.toLowerCase() + " exposure affecting supplier operations.", recordedAt);
    }
}
