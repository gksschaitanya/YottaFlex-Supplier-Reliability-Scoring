package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public final class OverallScoringCalculator {
    public record Component(String name,BigDecimal score,BigDecimal weight,String status) {}
    public record Result(BigDecimal score,BigDecimal coverage,String classification,BigDecimal eligibleWeight) {}

    public Result calculate(List<Component> components,BigDecimal minimumCoverage) {
        Objects.requireNonNull(components);Objects.requireNonNull(minimumCoverage);
        if(minimumCoverage.signum()<0||minimumCoverage.compareTo(BigDecimal.valueOf(100))>0)
            throw new IllegalArgumentException("minimumCoverage must be between 0 and 100");
        BigDecimal totalWeight=BigDecimal.ZERO,eligibleWeight=BigDecimal.ZERO,weightedScore=BigDecimal.ZERO;
        Set<String> seen=new HashSet<>();
        for(Component component:components){
            if(component==null||component.name()==null||component.weight()==null||component.weight().signum()<0)
                throw new IllegalArgumentException("component name and nonnegative weight are required");
            if(!seen.add(component.name()))throw new IllegalArgumentException("component names must be unique");
            totalWeight=totalWeight.add(component.weight());
            if("AVAILABLE".equals(component.status())){
                if(component.score()==null)throw new IllegalArgumentException("available component must have a score");
                BigDecimal score=component.score().max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
                eligibleWeight=eligibleWeight.add(component.weight());weightedScore=weightedScore.add(score.multiply(component.weight()));
            }
        }
        BigDecimal coverage=totalWeight.signum()==0?BigDecimal.ZERO:eligibleWeight.multiply(BigDecimal.valueOf(100))
                .divide(totalWeight,2,RoundingMode.HALF_UP);
        if(eligibleWeight.signum()==0||coverage.compareTo(minimumCoverage)<0)
            return new Result(null,coverage,"INSUFFICIENT_DATA",eligibleWeight);
        BigDecimal score=weightedScore.divide(eligibleWeight,2,RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.valueOf(100));
        return new Result(score,coverage,classification(score),eligibleWeight);
    }

    public String classification(BigDecimal score){
        if(score.compareTo(BigDecimal.valueOf(90))>=0)return "EXCELLENT";
        if(score.compareTo(BigDecimal.valueOf(80))>=0)return "GOOD";
        if(score.compareTo(BigDecimal.valueOf(65))>=0)return "MODERATE";
        if(score.compareTo(BigDecimal.valueOf(50))>=0)return "POOR";
        return "CRITICAL";
    }
}
