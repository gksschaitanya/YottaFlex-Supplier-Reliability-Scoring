package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.OverallScoringCalculator;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class OverallScoringCalculatorTest {
    private final OverallScoringCalculator calculator=new OverallScoringCalculator();
    private OverallScoringCalculator.Component c(String name,String score,String weight,String status){return new OverallScoringCalculator.Component(name,score==null?null:new BigDecimal(score),new BigDecimal(weight),status);}

    @Test void normalizesOnlyAvailableComponentWeights(){
        var result=calculator.calculate(List.of(c("DELIVERY","80","25","AVAILABLE"),c("QUALITY","100","20","AVAILABLE"),c("COST",null,"15","MISSING_DATA"),c("CAPACITY","50","15","AVAILABLE"),c("COMPLIANCE",null,"10","INSUFFICIENT_OBSERVATIONS"),c("DISRUPTION","0","15","AVAILABLE")),new BigDecimal("70"));
        assertThat(result.coverage()).isEqualByComparingTo("75.00");
        assertThat(result.eligibleWeight()).isEqualByComparingTo("75");
        assertThat(result.score()).isEqualByComparingTo("63.33");
        assertThat(result.classification()).isEqualTo("POOR");
    }

    @Test void acceptsCoverageExactlyAtThresholdAndRejectsCoverageBelowIt(){
        var exact=calculator.calculate(List.of(c("DELIVERY","100","25","AVAILABLE"),c("QUALITY","100","20","AVAILABLE"),c("COST","100","15","AVAILABLE"),c("COMPLIANCE","100","10","AVAILABLE"),c("CAPACITY",null,"15","MISSING_DATA"),c("DISRUPTION",null,"15","MISSING_DATA")),new BigDecimal("70"));
        assertThat(exact.coverage()).isEqualByComparingTo("70.00");assertThat(exact.score()).isEqualByComparingTo("100.00");
        var below=calculator.calculate(List.of(c("DELIVERY","100","25","AVAILABLE"),c("QUALITY","100","20","AVAILABLE"),c("COST","100","15","AVAILABLE"),c("CAPACITY",null,"15","MISSING_DATA"),c("COMPLIANCE",null,"10","MISSING_DATA"),c("DISRUPTION",null,"15","MISSING_DATA")),new BigDecimal("70"));
        assertThat(below.coverage()).isEqualByComparingTo("60.00");assertThat(below.score()).isNull();assertThat(below.classification()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test void appliesClassificationBoundaries(){
        assertThat(calculator.classification(new BigDecimal("90"))).isEqualTo("EXCELLENT");
        assertThat(calculator.classification(new BigDecimal("89.99"))).isEqualTo("GOOD");
        assertThat(calculator.classification(new BigDecimal("80"))).isEqualTo("GOOD");
        assertThat(calculator.classification(new BigDecimal("79.99"))).isEqualTo("MODERATE");
        assertThat(calculator.classification(new BigDecimal("65"))).isEqualTo("MODERATE");
        assertThat(calculator.classification(new BigDecimal("64.99"))).isEqualTo("POOR");
        assertThat(calculator.classification(new BigDecimal("50"))).isEqualTo("POOR");
        assertThat(calculator.classification(new BigDecimal("49.99"))).isEqualTo("CRITICAL");
        assertThat(calculator.classification(BigDecimal.ZERO)).isEqualTo("CRITICAL");
        assertThat(calculator.classification(BigDecimal.valueOf(101))).isEqualTo("EXCELLENT");
    }

    @Test void zeroAvailableWeightIsInsufficient(){
        var result=calculator.calculate(List.of(c("DELIVERY",null,"25","MISSING_DATA")),BigDecimal.ZERO);
        assertThat(result.score()).isNull();assertThat(result.classification()).isEqualTo("INSUFFICIENT_DATA");
    }
}
