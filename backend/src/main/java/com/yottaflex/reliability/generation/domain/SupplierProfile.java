package com.yottaflex.reliability.generation.domain;

public enum SupplierProfile {
    RELIABLE(35, 3, 0.995, 0.005),
    STEADY(25, 8, 0.985, 0.010),
    AVERAGE(22, 16, 0.965, 0.025),
    DELIVERY_RISK(7, 42, 0.980, 0.015),
    QUALITY_RISK(5, 12, 0.875, 0.125),
    COST_RISK(3, 10, 0.975, 0.025),
    CAPACITY_RISK(1, 18, 0.970, 0.030),
    COMPLIANCE_RISK(1, 14, 0.970, 0.030),
    DISRUPTION_RISK(1, 32, 0.970, 0.030);

    private final int allocation;
    private final int lateDeliveryChance;
    private final double expectedAcceptanceRate;
    private final double invoiceVarianceRate;

    SupplierProfile(int allocation, int lateDeliveryChance, double expectedAcceptanceRate, double invoiceVarianceRate) {
        this.allocation = allocation;
        this.lateDeliveryChance = lateDeliveryChance;
        this.expectedAcceptanceRate = expectedAcceptanceRate;
        this.invoiceVarianceRate = invoiceVarianceRate;
    }

    public int allocation() {
        return allocation;
    }

    public int lateDeliveryChance() {
        return lateDeliveryChance;
    }

    public double expectedAcceptanceRate() {
        return expectedAcceptanceRate;
    }

    public double invoiceVarianceRate() {
        return invoiceVarianceRate;
    }
}
