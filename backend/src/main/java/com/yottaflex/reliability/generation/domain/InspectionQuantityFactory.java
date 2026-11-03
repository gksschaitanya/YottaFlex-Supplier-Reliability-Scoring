package com.yottaflex.reliability.generation.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.SplittableRandom;

public final class InspectionQuantityFactory {

    private InspectionQuantityFactory() {
    }

    public static InspectionQuantities forRecord(BigDecimal inspected, SupplierProfile profile, long seed, long sequence) {
        if (inspected == null || inspected.signum() <= 0) {
            throw new IllegalArgumentException("Inspected quantity must be positive");
        }
        SplittableRandom random = SeededRandomFactory.forRecord(seed, "inspection-result", sequence);
        boolean defectObserved = random.nextDouble() > profile.expectedAcceptanceRate();
        BigDecimal acceptance = defectObserved
                ? BigDecimal.valueOf(85 + random.nextInt(14), 2)
                : BigDecimal.ONE;
        BigDecimal accepted = inspected.multiply(acceptance).setScale(4, RoundingMode.HALF_UP);
        BigDecimal rejected = inspected.subtract(accepted).setScale(4, RoundingMode.UNNECESSARY);
        String defectCode = rejected.signum() > 0
                ? "DEFECT-" + String.format(java.util.Locale.ROOT, "%02d", random.nextInt(1, 25))
                : null;
        return new InspectionQuantities(inspected, accepted, rejected, defectCode);
    }
}
