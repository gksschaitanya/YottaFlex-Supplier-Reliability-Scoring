package com.yottaflex.reliability.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import com.yottaflex.reliability.generation.application.GeneratedRows;
import com.yottaflex.reliability.generation.application.GenerationRunLifecycle;
import com.yottaflex.reliability.generation.application.GenerationContext;
import com.yottaflex.reliability.generation.application.ProcurementDataWriter;
import com.yottaflex.reliability.generation.application.FulfillmentDataWriter;
import com.yottaflex.reliability.generation.application.FulfillmentDataWriter.ShipmentWindow;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunEntity;
import org.junit.jupiter.api.Test;

class GenerationRulesTest {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 10, 22);

    @Test
    void generatedDeliveryWindowsAreDeterministicChronologicalAndWithinReferenceWindow() {
        GenerationCounts counts = GenerationPlan.full();
        GenerationContext context = new GenerationContext(4L, "GEN-2026-000001", 20261022L,
                REFERENCE_DATE, DatasetScale.FULL, counts, 500);
        Set<LocalDate> deliveredDates = new HashSet<>();

        for (long shipment = 1; shipment <= counts.shipments(); shipment++) {
            ShipmentWindow window = FulfillmentDataWriter.shipmentWindow(context, shipment);
            ShipmentWindow sameWindow = FulfillmentDataWriter.shipmentWindow(context, shipment);
            assertThat(window).isEqualTo(sameWindow);
            assertThat(window.dispatchedAt()).isBefore(window.deliveredAt());
            assertThat(window.deliveredAt()).isBefore(REFERENCE_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            assertThat(window.dispatchedAt()).isAfter(REFERENCE_DATE.minusDays(366).atStartOfDay().toInstant(ZoneOffset.UTC));
            deliveredDates.add(window.deliveredAt().atZone(ZoneOffset.UTC).toLocalDate());
        }
        assertThat(deliveredDates).hasSizeGreaterThan(100);

        GenerationContext anotherSeed = new GenerationContext(4L, "GEN-2026-000001", 20261023L,
                REFERENCE_DATE, DatasetScale.FULL, counts, 500);
        assertThat(FulfillmentDataWriter.shipmentWindow(context, 1))
                .isNotEqualTo(FulfillmentDataWriter.shipmentWindow(anotherSeed, 1));
    }

    @Test
    void generatedPurchaseOrderQuantitiesAndPricesAreStableAndValid() {
        GenerationContext context = new GenerationContext(1L, "GEN-2026-000001", 20261022L,
                REFERENCE_DATE, DatasetScale.FULL, GenerationPlan.full(), 500);

        for (long line = 1; line <= context.counts().poLines(); line++) {
            BigDecimal quantity = ProcurementDataWriter.purchaseOrderLineQuantity(context, line);
            BigDecimal unitPrice = ProcurementDataWriter.purchaseOrderLineUnitPrice(context, line);
            assertThat(quantity).isPositive();
            assertThat(unitPrice).isPositive();
            assertThat(quantity).isEqualTo(ProcurementDataWriter.purchaseOrderLineQuantity(context, line));
            assertThat(unitPrice).isEqualTo(ProcurementDataWriter.purchaseOrderLineUnitPrice(context, line));
        }
    }

    @Test
    void inspectionQuantitiesReconcileAcrossSupplierQualityProfiles() {
        BigDecimal inspected = new BigDecimal("234.5678");
        Set<BigDecimal> acceptanceRates = new HashSet<>();
        long sequence = 1;
        for (SupplierProfile profile : SupplierProfile.values()) {
            for (int observation = 0; observation < 500; observation++) {
                InspectionQuantities quantities = InspectionQuantityFactory.forRecord(inspected, profile, 20261022L, sequence++);
                assertThat(quantities.inspected()).isEqualTo(inspected);
                assertThat(quantities.accepted().add(quantities.rejected())).isEqualTo(inspected);
                if (quantities.rejected().signum() > 0) {
                    assertThat(quantities.defectCode()).startsWith("DEFECT-");
                } else {
                    assertThat(quantities.defectCode()).isNull();
                }
                acceptanceRates.add(quantities.accepted().divide(inspected, 4, java.math.RoundingMode.HALF_UP));
            }
        }
        assertThat(acceptanceRates).hasSizeGreaterThan(1);
        assertThatIllegalArgumentException().isThrownBy(() -> InspectionQuantityFactory.forRecord(BigDecimal.ZERO,
                SupplierProfile.RELIABLE, 20261022L, 1));
    }

    @Test
    void riskSignalsHaveDeterministicRequiredProvenanceAndValidDates() {
        for (long sequence = 1; sequence <= GenerationPlan.full().riskSignals(); sequence++) {
            SyntheticRiskSignal signal = SyntheticRiskSignalFactory.forRecord(20261022L, sequence, REFERENCE_DATE);
            SyntheticRiskSignal repeat = SyntheticRiskSignalFactory.forRecord(20261022L, sequence, REFERENCE_DATE);
            assertThat(signal).isEqualTo(repeat);
            assertThat(signal.source()).isNotBlank();
            assertThat(signal.startsAt()).isBefore(REFERENCE_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            assertThat(signal.recordedAt()).isAfterOrEqualTo(signal.startsAt());
            if (signal.endsAt() != null) {
                assertThat(signal.endsAt()).isAfterOrEqualTo(signal.startsAt());
                assertThat(signal.endsAt()).isBefore(REFERENCE_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            }
        }
    }

    @Test
    void generatedComplianceChecksUseOnlyExistingGlobalCatalogIds() {
        Set<Long> referencedIds = new HashSet<>();
        for (int ordinal = 1; ordinal <= 20; ordinal++) {
            long requirementId = ComplianceCheckRequirementIds.forOrdinal(ordinal);
            assertThat(requirementId).isBetween(1L, (long) GenerationPlan.COMPLIANCE_REQUIREMENT_COUNT);
            referencedIds.add(requirementId);
        }
        assertThat(referencedIds).containsExactlyInAnyOrder(1L, 3L, 8L, 11L);
        assertThat(ComplianceCheckRequirementIds.configuredCount()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> ComplianceCheckRequirementIds.forOrdinal(0));
    }

    @Test
    void canonicalArtifactRowsExcludeRunSpecificMetadata() {
        var firstRun = GeneratedRows.rowOmitting(new int[]{2}, 17L, 1L, "GEN-2026-000001", "SKU-A", new BigDecimal("7.5000"));
        var secondRun = GeneratedRows.rowOmitting(new int[]{2}, 17L, 1L, "GEN-2026-000002", "SKU-A", new BigDecimal("7.5000"));

        assertThat(firstRun.values()).isNotEqualTo(secondRun.values());
        assertThat(firstRun.canonicalValue()).isEqualTo(secondRun.canonicalValue());
    }

    @Test
    void failedGenerationLifecyclePersistsFailureAndDuration() {
        DataGenerationRunEntity run = new DataGenerationRunEntity();
        Instant started = Instant.parse("2026-10-22T10:00:00Z");
        Instant failed = Instant.parse("2026-10-22T10:00:04Z");
        run.setStartedAt(started);

        GenerationRunLifecycle.fail(run, failed, new IllegalStateException("synthetic failure"));

        assertThat(run.getStatus()).isEqualTo(com.yottaflex.reliability.generation.domain.GenerationRunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo("synthetic failure");
        assertThat(run.getDurationMillis()).isEqualTo(4_000L);
    }
}
