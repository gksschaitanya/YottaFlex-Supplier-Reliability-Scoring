package com.yottaflex.reliability.generation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import com.yottaflex.reliability.generation.application.GenerationContext;
import org.junit.jupiter.api.Test;

class GenerationRelationshipTest {

    @Test
    void starterPlanHasCompleteDeterministicParentChildMappings() {
        assertPlanRelationships(DatasetScale.STARTER, GenerationPlan.starter(25, 120, 180));
    }

    @Test
    void fullPlanHasCompleteDeterministicParentChildMappings() {
        assertPlanRelationships(DatasetScale.FULL, GenerationPlan.full());
    }

    private void assertPlanRelationships(DatasetScale scale, GenerationCounts counts) {
        GenerationContext context = new GenerationContext(
                1L, "GEN-2026-000001", 20261022L, LocalDate.of(2026, 10, 22), scale, counts, 500);

        Set<Long> poLineSequences = new HashSet<>();
        for (long line = 1; line <= counts.poLines(); line++) {
            GenerationRelationships.PurchaseOrderLineReference reference =
                    GenerationRelationships.purchaseOrderLineForSequence(context, line);
            assertThat(reference.purchaseOrderSequence()).isBetween(1L, counts.purchaseOrders());
            assertThat(reference.lineNumber()).isBetween(1, (int) GenerationRelationships.purchaseOrderLineCount(context, reference.purchaseOrderSequence()));
            long expectedId = GenerationRelationships.purchaseOrderLineStart(context, reference.purchaseOrderSequence()) + reference.lineNumber() - 1;
            assertThat(expectedId).isEqualTo(line);
            assertThat(poLineSequences.add(expectedId)).isTrue();
        }
        assertThat(poLineSequences).hasSize(Math.toIntExact(counts.poLines()));

        Set<Long> shipmentLinePoLines = new HashSet<>();
        long calculatedShipmentLines = 0;
        for (long shipment = 1; shipment <= counts.shipments(); shipment++) {
            long order = GenerationRelationships.shipmentPurchaseOrder(context, shipment);
            assertThat(order).isBetween(1L, counts.purchaseOrders());
            int ordinal = GenerationRelationships.shipmentOrdinalForPurchaseOrder(context, shipment);
            assertThat(GenerationRelationships.shipmentSequenceForPurchaseOrder(context, order, ordinal)).isEqualTo(shipment);
            for (int lineOrdinal = 1; lineOrdinal <= GenerationRelationships.shipmentLineCount(context, shipment); lineOrdinal++) {
                long lineId = GenerationRelationships.shipmentLineStart(context, shipment) + lineOrdinal - 1;
                GenerationRelationships.ShipmentLineReference reverse =
                        GenerationRelationships.shipmentLineForSequence(context, lineId);
                assertThat(reverse.shipmentSequence()).isEqualTo(shipment);
                assertThat(reverse.lineOrdinal()).isEqualTo(lineOrdinal);
                int poLineOrdinal = GenerationRelationships.purchaseOrderLineOrdinalForShipmentLine(
                    context, shipment, reverse.lineOrdinal());
                long poLine = GenerationRelationships.purchaseOrderLineStart(context, order) + poLineOrdinal - 1;
                GenerationRelationships.PurchaseOrderLineReference poReference =
                        GenerationRelationships.purchaseOrderLineForSequence(context, poLine);
                assertThat(poReference.purchaseOrderSequence()).isEqualTo(order);
                assertThat(shipmentLinePoLines.add(poLine)).isTrue();
                calculatedShipmentLines++;
            }
        }
        assertThat(calculatedShipmentLines).isEqualTo(counts.shipmentLines());
        assertThat(shipmentLinePoLines).hasSize(Math.toIntExact(counts.poLines()));

        for (long receipt = 1; receipt <= counts.receipts(); receipt++) {
            long shipment = GenerationRelationships.receiptForShipment(context, receipt);
            assertThat(shipment).isBetween(1L, counts.shipments());
            if (receipt > counts.shipments()) {
                assertThat(GenerationRelationships.shipmentLineCount(context, shipment)).isGreaterThan(1);
            }
        }
        for (long shipmentLine = 1; shipmentLine <= counts.receiptLines(); shipmentLine++) {
            GenerationRelationships.ShipmentLineReference reference =
                    GenerationRelationships.shipmentLineForSequence(context, shipmentLine);
            long receipt = GenerationRelationships.receiptForShipmentLine(context, reference.shipmentSequence(), reference.lineOrdinal());
            assertThat(GenerationRelationships.receiptForShipment(context, receipt)).isEqualTo(reference.shipmentSequence());
        }

        for (long line = 1; line <= counts.invoiceLines(); line++) {
            InvoiceLineReference invoiceLine = invoiceLineReference(context, line);
            long order = GenerationRelationships.invoicePurchaseOrder(context, invoiceLine.invoiceSequence());
            long poLine = GenerationRelationships.invoiceLinePoLine(context, invoiceLine.invoiceSequence(), invoiceLine.lineOrdinal());
            GenerationRelationships.PurchaseOrderLineReference poReference =
                    GenerationRelationships.purchaseOrderLineForSequence(context, poLine);
            assertThat(order).isBetween(1L, counts.purchaseOrders());
            assertThat(poReference.purchaseOrderSequence()).isEqualTo(order);
        }
    }

    private InvoiceLineReference invoiceLineReference(GenerationContext context, long lineSequence) {
        long additionalLines = context.counts().invoiceLines() - context.counts().invoices();
        long linesForExpandedInvoices = additionalLines * 2;
        if (lineSequence <= linesForExpandedInvoices) {
            return new InvoiceLineReference(((lineSequence - 1) / 2) + 1, (int) (((lineSequence - 1) % 2) + 1));
        }
        return new InvoiceLineReference(additionalLines + (lineSequence - linesForExpandedInvoices), 1);
    }

    private record InvoiceLineReference(long invoiceSequence, int lineOrdinal) {
    }
}
