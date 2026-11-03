package com.yottaflex.reliability.generation.domain;

import java.math.BigDecimal;

public record InspectionQuantities(BigDecimal inspected, BigDecimal accepted, BigDecimal rejected, String defectCode) {

    public InspectionQuantities {
        if (inspected.signum() < 0 || accepted.signum() < 0 || rejected.signum() < 0
                || accepted.add(rejected).compareTo(inspected) != 0) {
            throw new IllegalArgumentException("Inspection quantities must be nonnegative and reconcile exactly");
        }
    }
}
