package com.yottaflex.reliability.generation.domain;

import java.util.SplittableRandom;

public final class SupplierProfileFactory {

    private SupplierProfileFactory() {
    }

    public static SupplierProfile forSupplier(long seed, long supplierSequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(seed, "supplier-profile", supplierSequence);
        int selection = random.nextInt(100);
        int cumulative = 0;
        for (SupplierProfile profile : SupplierProfile.values()) {
            cumulative += profile.allocation();
            if (selection < cumulative) {
                return profile;
            }
        }
        throw new IllegalStateException("Supplier profile allocation must total 100");
    }
}
