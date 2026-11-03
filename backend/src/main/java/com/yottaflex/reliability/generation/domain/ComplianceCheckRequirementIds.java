package com.yottaflex.reliability.generation.domain;

public final class ComplianceCheckRequirementIds {

    private static final long[] IDS = {1, 3, 8, 11};

    private ComplianceCheckRequirementIds() {
    }

    public static long forOrdinal(int ordinal) {
        if (ordinal < 1) {
            throw new IllegalArgumentException("Compliance requirement ordinal must be positive");
        }
        return IDS[(ordinal - 1) % IDS.length];
    }

    public static int configuredCount() {
        return IDS.length;
    }
}
