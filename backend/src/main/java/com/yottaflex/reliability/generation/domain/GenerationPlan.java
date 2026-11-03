package com.yottaflex.reliability.generation.domain;

public final class GenerationPlan {

    public static final int COMPLIANCE_REQUIREMENT_COUNT = 12;

    private GenerationPlan() {
    }

    public static GenerationCounts full() {
        return new GenerationCounts(
                1_000,
                1_750,
                2_000,
                4_000,
                10_000,
                25_000,
                15_000,
                25_000,
                30_000,
                20_000,
                25_000,
                20_000,
                25_000,
                12_000,
                15_000,
                4_000,
                COMPLIANCE_REQUIREMENT_COUNT,
                3_000,
                12_000,
                1_000);
    }

    public static GenerationCounts starter(int suppliers, int purchaseOrders, int shipments) {
        if (suppliers < 1 || suppliers > 99_999) {
            throw new IllegalArgumentException("Starter supplier count must be between 1 and 99999");
        }
        if (purchaseOrders < suppliers || purchaseOrders > 999_999) {
            throw new IllegalArgumentException("Starter purchase-order count must be at least the supplier count and at most 999999");
        }
        if (shipments < purchaseOrders || shipments > 999_999) {
            throw new IllegalArgumentException("Starter shipment count must be at least the purchase-order count and at most 999999");
        }

        long supplierCount = suppliers;
        long orderCount = purchaseOrders;
        long shipmentCount = shipments;
        long sites = supplierCount + supplierCount * 3 / 4;
        long contracts = supplierCount * 2;
        long commitments = supplierCount * 4;
        long poLines = orderCount * 2 + orderCount / 2;
        long shipmentLines = shipmentCount + shipmentCount * 2 / 3;
        long receipts = shipmentCount + shipmentCount / 3;
        long invoiceCount = orderCount + orderCount / 5;
        long invoiceLines = invoiceCount + invoiceCount / 4;
        long assessments = supplierCount * 3;

        if (receipts > 999_999 || shipmentLines > 999_999 || invoiceLines > 999_999) {
            throw new IllegalArgumentException("Starter configuration exceeds a supported child-record sequence limit");
        }

        return new GenerationCounts(
                supplierCount,
                sites,
                contracts,
                commitments,
                orderCount,
                poLines,
                shipmentCount,
                shipmentLines,
                shipmentCount * 2,
                receipts,
                shipmentLines,
                receipts,
                shipmentLines,
                invoiceCount,
                invoiceLines,
                supplierCount * 4,
                COMPLIANCE_REQUIREMENT_COUNT,
                assessments,
                assessments * 4,
                supplierCount);
    }
}
