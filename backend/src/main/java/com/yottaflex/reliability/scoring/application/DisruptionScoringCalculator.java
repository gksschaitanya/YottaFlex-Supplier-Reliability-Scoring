package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** Pure, deterministic disruption calculation. Only records known by the run cutoff are supplied. */
public final class DisruptionScoringCalculator {
    private static final Map<String, BigDecimal> SEVERITY = Map.of(
            "INFO", new BigDecimal("0.25"), "LOW", new BigDecimal("0.5"),
            "MEDIUM", BigDecimal.ONE, "HIGH", new BigDecimal("2"), "CRITICAL", new BigDecimal("4"));

    public record Signal(long id, String riskId, String source, String sourceReference, String severity,
                         LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime recordedAt) {}
    public record Evidence(long sourceRecordId, String sourceBusinessId, boolean included,
                           BigDecimal contribution, String exclusionReason, Map<String, Object> details) {}
    public record Result(BigDecimal score, BigDecimal exposure, BigDecimal exposureScale,
                         long observations, long eligibleObservations, long excludedObservations,
                         String status, String reason, List<Evidence> evidence) {}

    private static double daysBetween(LocalDateTime from, LocalDateTime to) {
        Duration duration=Duration.between(from,to);
        return duration.getSeconds()/86400d+duration.getNano()/86_400_000_000_000d;
    }
    private static boolean eligibleCandidate(Signal signal,LocalDateTime windowStart,LocalDateTime asOf) {
        if(signal.recordedAt()==null||signal.recordedAt().isAfter(asOf)||signal.startsAt()==null||signal.startsAt().isAfter(asOf)
                ||signal.source()==null||signal.source().isBlank()||signal.severity()==null||!SEVERITY.containsKey(signal.severity())
                ||(signal.endsAt()!=null&&signal.endsAt().isBefore(signal.startsAt())))return false;
        LocalDateTime from=signal.startsAt().isBefore(windowStart)?windowStart:signal.startsAt();
        LocalDateTime to=signal.endsAt()==null||signal.endsAt().isAfter(asOf)?asOf:signal.endsAt();
        return to.isAfter(from);
    }

    public Result calculate(Collection<Signal> input, LocalDateTime windowStart, LocalDateTime asOf,
                            BigDecimal exposureScale, int halfLifeDays) {
        Objects.requireNonNull(input); Objects.requireNonNull(windowStart); Objects.requireNonNull(asOf);
        if (windowStart.isAfter(asOf)) throw new IllegalArgumentException("windowStart must not be after asOf");
        if (exposureScale == null || exposureScale.signum() <= 0) throw new IllegalArgumentException("exposureScale must be positive");
        if (halfLifeDays <= 0) throw new IllegalArgumentException("halfLifeDays must be positive");

        List<Signal> signals = input.stream().filter(Objects::nonNull)
                .filter(s -> s.recordedAt() == null || !s.recordedAt().isAfter(asOf))
                .sorted(Comparator.comparing(Signal::source, Comparator.nullsFirst(String::compareTo))
                        .thenComparing(Signal::sourceReference, Comparator.nullsFirst(String::compareTo))
                        .thenComparing((Signal s) -> !eligibleCandidate(s,windowStart,asOf))
                        .thenComparing(Signal::riskId, Comparator.nullsFirst(String::compareTo))
                        .thenComparingLong(Signal::id))
                .toList();

        Set<String> seenReferences = new HashSet<>();
        List<Evidence> evidence = new ArrayList<>(signals.size());
        BigDecimal totalExposure = BigDecimal.ZERO;
        long eligible = 0;
        long invalid = 0;
        long inWindow = 0;

        for (Signal signal : signals) {
            String exclusion = null;
            BigDecimal contribution = BigDecimal.ZERO;
            LocalDateTime effectiveEnd = signal.endsAt() == null || signal.endsAt().isAfter(asOf) ? asOf : signal.endsAt();
            LocalDateTime effectiveStart = signal.startsAt() == null || signal.startsAt().isBefore(windowStart) ? windowStart : signal.startsAt();

            if (signal.recordedAt() == null) {
                exclusion = "MISSING_RECORDED_TIMESTAMP"; invalid++;
            } else if (signal.startsAt() == null) {
                exclusion = "MISSING_START_TIMESTAMP"; invalid++;
            } else if (signal.startsAt().isAfter(asOf)) {
                exclusion = "AFTER_AS_OF";
            } else if (signal.severity() == null || !SEVERITY.containsKey(signal.severity())) {
                exclusion = "INVALID_SEVERITY"; invalid++;
            } else if (signal.endsAt() != null && signal.endsAt().isBefore(signal.startsAt())) {
                exclusion = "INVALID_CHRONOLOGY"; invalid++;
            } else if (signal.source() == null || signal.source().isBlank()) {
                exclusion = "MISSING_SOURCE"; invalid++;
            } else if (signal.sourceReference() != null && !signal.sourceReference().isBlank()
                    && !seenReferences.add(signal.source() + "\u0000" + signal.sourceReference())) {
                exclusion = "DUPLICATE_SOURCE_REFERENCE";
            } else if (!effectiveEnd.isAfter(effectiveStart)) {
                exclusion = signal.endsAt() != null && !signal.endsAt().isAfter(windowStart)
                        ? "OUTSIDE_EVALUATION_WINDOW" : "ZERO_DURATION";
            } else {
                inWindow++;
                BigDecimal activeDays = BigDecimal.valueOf(daysBetween(effectiveStart,effectiveEnd));
                double ageDays = daysBetween(signal.startsAt(),asOf);
                BigDecimal recency = BigDecimal.valueOf(Math.pow(0.5d, ageDays / halfLifeDays));
                contribution = SEVERITY.get(signal.severity()).multiply(activeDays).multiply(recency);
                totalExposure = totalExposure.add(contribution);
                eligible++;
            }

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("source", signal.source()); details.put("sourceReference", signal.sourceReference());
            details.put("severity", signal.severity()); details.put("startsAt", signal.startsAt());
            details.put("endsAt", signal.endsAt()); details.put("recordedAt", signal.recordedAt());
            if (exclusion == null) {
                details.put("activeDurationDays", daysBetween(effectiveStart,effectiveEnd));
                details.put("recencyFactor", Math.pow(0.5d,daysBetween(signal.startsAt(),asOf)/halfLifeDays));
            }
            evidence.add(new Evidence(signal.id(), signal.riskId(), exclusion == null, contribution,
                    exclusion, Collections.unmodifiableMap(details)));
        }

        BigDecimal score = BigDecimal.valueOf(100).multiply(BigDecimal.ONE.subtract(
                totalExposure.divide(exposureScale, 16, RoundingMode.HALF_UP)))
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        String status = "AVAILABLE", reason = null;
        if (signals.isEmpty()) {
            status = "MISSING_DATA"; score = null; reason = "No risk-signal records are available by the scoring cutoff";
        } else if (eligible == 0 && invalid > 0) {
            status = "EXCLUDED_INVALID_DATA"; score = null; reason = "All candidate risk signals were invalid or excluded";
        } else if (eligible == 0 && inWindow == 0) {
            status = "NOT_APPLICABLE"; score = null; reason = "No eligible risk signals overlap the scoring window";
        }
        return new Result(score, totalExposure, exposureScale, signals.size(), eligible,
                signals.size() - eligible, status, reason, List.copyOf(evidence));
    }
}
