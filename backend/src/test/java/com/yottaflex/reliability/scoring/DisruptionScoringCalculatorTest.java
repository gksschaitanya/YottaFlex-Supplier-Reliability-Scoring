package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.DisruptionScoringCalculator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisruptionScoringCalculatorTest {
    private final DisruptionScoringCalculator calculator = new DisruptionScoringCalculator();
    private final LocalDateTime asOf = LocalDateTime.parse("2026-11-01T00:00:00");
    private final LocalDateTime start = asOf.minusDays(365);
    private final BigDecimal scale = new BigDecimal("500");

    private DisruptionScoringCalculator.Signal signal(long id, String source, String ref, String severity,
                                                       LocalDateTime begins, LocalDateTime ends, LocalDateTime recorded) {
        return new DisruptionScoringCalculator.Signal(id,"RSK-"+id,source,ref,severity,begins,ends,recorded);
    }

    @Test void emptySourceIsMissingRatherThanZeroExposure() {
        var result=calculator.calculate(List.of(),start,asOf,scale,90);
        assertThat(result.status()).isEqualTo("MISSING_DATA");
        assertThat(result.score()).isNull();
    }

    @Test void appliesSeverityAndHalfLifeWithFractionalDuration() {
        var begins=asOf.minusDays(90);
        var signal=signal(1,"feed","incident-1","HIGH",begins,begins.plusHours(12),asOf.minusDays(89));
        var result=calculator.calculate(List.of(signal),start,asOf,scale,90);
        assertThat(result.eligibleObservations()).isEqualTo(1);
        assertThat(result.exposure()).isCloseTo(new BigDecimal("0.5"),org.assertj.core.data.Offset.offset(new BigDecimal("0.00000000000001")));
        assertThat(result.evidence().getFirst().contribution()).isEqualByComparingTo(result.exposure());
    }

    @Test void usesHalfOpenWindowAndExcludesZeroDuration() {
        var endsAtStart=signal(1,"feed","old","HIGH",start.minusDays(1),start,asOf);
        var zero=signal(2,"feed","instant","HIGH",asOf.minusDays(1),asOf.minusDays(1),asOf);
        var startsAtCutoff=signal(3,"feed","future","HIGH",asOf,null,asOf);
        var result=calculator.calculate(List.of(endsAtStart,zero,startsAtCutoff),start,asOf,scale,90);
        assertThat(result.eligibleObservations()).isZero();
        assertThat(result.evidence()).extracting(DisruptionScoringCalculator.Evidence::exclusionReason)
                .containsExactlyInAnyOrder("OUTSIDE_EVALUATION_WINDOW","ZERO_DURATION","ZERO_DURATION");
    }

    @Test void deduplicatesOnlySameSourceAndNonNullReference() {
        var signals=List.of(signal(1,"feed-a","same","LOW",asOf.minusDays(2),asOf.minusDays(1),asOf),
                signal(2,"feed-a","same","LOW",asOf.minusDays(2),asOf.minusDays(1),asOf),
                signal(3,"feed-b","same","LOW",asOf.minusDays(2),asOf.minusDays(1),asOf),
                signal(4,"feed-a",null,"LOW",asOf.minusDays(2),asOf.minusDays(1),asOf),
                signal(5,"feed-a",null,"LOW",asOf.minusDays(2),asOf.minusDays(1),asOf));
        var result=calculator.calculate(signals,start,asOf,scale,90);
        assertThat(result.observations()).isEqualTo(5);
        assertThat(result.eligibleObservations()).isEqualTo(4);
        assertThat(result.excludedObservations()).isEqualTo(1);
        assertThat(result.evidence().stream().filter(e->"DUPLICATE_SOURCE_REFERENCE".equals(e.exclusionReason()))).hasSize(1);
    }

    @Test void excludesLateRecordsFromHistoricalEvidenceAndMarksFutureEvents() {
        var knownFuture=signal(1,"feed","future","CRITICAL",asOf.plusDays(1),null,asOf);
        var late=signal(2,"feed","late","CRITICAL",asOf.minusDays(1),null,asOf.plusSeconds(1));
        var result=calculator.calculate(List.of(knownFuture,late),start,asOf,scale,90);
        assertThat(result.observations()).isEqualTo(1);
        assertThat(result.evidence().getFirst().exclusionReason()).isEqualTo("AFTER_AS_OF");
    }

    @Test void invalidSeverityIsExplicitlyExcludedAndScoreIsBounded() {
        var invalid=signal(1,"feed","invalid","UNKNOWN",asOf.minusDays(1),asOf,asOf);
        var result=calculator.calculate(List.of(invalid),start,asOf,scale,90);
        assertThat(result.status()).isEqualTo("EXCLUDED_INVALID_DATA");
        assertThat(result.score()).isNull();
        assertThat(result.evidence().getFirst().exclusionReason()).isEqualTo("INVALID_SEVERITY");
        var extreme=calculator.calculate(List.of(signal(2,"feed","huge","CRITICAL",start,null,asOf)),start,asOf,new BigDecimal("0.01"),90);
        assertThat(extreme.score()).isEqualByComparingTo(BigDecimal.ZERO.setScale(2));
    }
}
