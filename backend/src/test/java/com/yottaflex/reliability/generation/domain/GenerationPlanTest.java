package com.yottaflex.reliability.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class GenerationPlanTest {

    @Test
    void fullPlanMatchesRequiredVolumesAndSupportingCounts() {
        GenerationCounts counts = GenerationPlan.full();

        assertThat(counts.suppliers()).isEqualTo(1_000);
        assertThat(counts.purchaseOrders()).isEqualTo(10_000);
        assertThat(counts.shipments()).isEqualTo(15_000);
        assertThat(counts.inspections()).isEqualTo(20_000);
        assertThat(counts.sites()).isEqualTo(1_750);
        assertThat(counts.contracts()).isEqualTo(2_000);
        assertThat(counts.poLines()).isEqualTo(25_000);
        assertThat(counts.shipmentLines()).isEqualTo(25_000);
        assertThat(counts.deliveryEvents()).isEqualTo(30_000);
        assertThat(counts.receipts()).isEqualTo(20_000);
        assertThat(counts.receiptLines()).isEqualTo(25_000);
        assertThat(counts.invoices()).isEqualTo(12_000);
        assertThat(counts.invoiceLines()).isEqualTo(15_000);
        assertThat(counts.riskSignals()).isEqualTo(4_000);
        assertThat(counts.complianceRequirements()).isEqualTo(12);
        assertThat(counts.complianceAssessments()).isEqualTo(3_000);
        assertThat(counts.complianceChecks()).isEqualTo(12_000);
        assertThat(counts.asOrderedMap().keySet()).containsExactly(
                "supplier", "site", "contract", "capacity_commitment", "purchase_order", "po_line",
                "shipment", "shipment_line", "delivery_event", "receipt", "receipt_line", "inspection",
                "inspection_line", "invoice", "invoice_line", "risk_signal", "compliance_requirement",
                "compliance_assessment", "compliance_check", "supplier_status_history");
    }

    @Test
    void starterPlanDerivesConsistentSmallerCounts() {
        GenerationCounts counts = GenerationPlan.starter(25, 120, 180);

        assertThat(counts.suppliers()).isEqualTo(25);
        assertThat(counts.purchaseOrders()).isEqualTo(120);
        assertThat(counts.shipments()).isEqualTo(180);
        assertThat(counts.sites()).isEqualTo(43);
        assertThat(counts.poLines()).isEqualTo(300);
        assertThat(counts.shipmentLines()).isEqualTo(300);
        assertThat(counts.receipts()).isEqualTo(240);
        assertThat(counts.inspections()).isEqualTo(counts.receipts());
        assertThat(counts.inspectionLines()).isEqualTo(counts.receiptLines());
    }

    @Test
    void starterPlanRejectsCountsThatCannotSatisfyParentRelationships() {
        assertThatIllegalArgumentException().isThrownBy(() -> GenerationPlan.starter(10, 9, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> GenerationPlan.starter(10, 12, 11));
    }

    @Test
    void generatedBusinessIdentifiersAreStableUniqueAndBounded() {
        List<String> supplierIds = LongStream.rangeClosed(1, 1_000)
                .mapToObj(BusinessIdentifiers::supplier)
                .toList();

        assertThat(new HashSet<>(supplierIds)).hasSize(1_000);
        assertThat(supplierIds.getFirst()).isEqualTo("SUP-000001");
        assertThat(BusinessIdentifiers.site("SUP-000001", 1)).isEqualTo("SITE-SUP-000001-01");
        assertThat(BusinessIdentifiers.purchaseOrder(2026, 1)).isEqualTo("PO-2026-000001");
        assertThat(BusinessIdentifiers.shipment(2026, 1)).isEqualTo("SHP-2026-000001");
        assertThat(BusinessIdentifiers.riskSignal("SUP-000001", 2026, 1)).isEqualTo("RSK-SUP-000001-2026-01");
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessIdentifiers.site("SUP-000001", 100));
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessIdentifiers.purchaseOrder(2026, 1_000_000));
    }

    @Test
    void randomStreamsAreStableNamespacedAndSeedSensitive() {
        SplittableRandom first = SeededRandomFactory.forRecord(20261022L, "supplier-profile", 14);
        SplittableRandom repeat = SeededRandomFactory.forRecord(20261022L, "supplier-profile", 14);
        SplittableRandom anotherNamespace = SeededRandomFactory.forRecord(20261022L, "risk-signal", 14);
        SplittableRandom anotherSeed = SeededRandomFactory.forRecord(20261023L, "supplier-profile", 14);

        assertThat(first.nextLong()).isEqualTo(repeat.nextLong());
        assertThat(SeededRandomFactory.forRecord(1L, "supplier-profile", 1).nextLong())
                .isNotEqualTo(SeededRandomFactory.forRecord(1L, "purchase-order", 1).nextLong());
        assertThat(anotherNamespace.nextLong()).isNotEqualTo(anotherSeed.nextLong());

        List<SupplierProfile> runOne = LongStream.rangeClosed(1, 1_000)
                .mapToObj(sequence -> SupplierProfileFactory.forSupplier(20261022L, sequence))
                .toList();
        List<SupplierProfile> sameRun = LongStream.rangeClosed(1, 1_000)
                .mapToObj(sequence -> SupplierProfileFactory.forSupplier(20261022L, sequence))
                .toList();
        List<SupplierProfile> differentSeed = LongStream.rangeClosed(1, 1_000)
                .mapToObj(sequence -> SupplierProfileFactory.forSupplier(20261023L, sequence))
                .toList();

        assertThat(runOne).isEqualTo(sameRun);
        assertThat(runOne).isNotEqualTo(differentSeed);
        assertThat(new HashSet<>(runOne)).containsAll(List.of(SupplierProfile.values()));
    }
}
