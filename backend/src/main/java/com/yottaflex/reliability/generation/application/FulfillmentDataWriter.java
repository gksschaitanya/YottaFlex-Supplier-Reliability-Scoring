package com.yottaflex.reliability.generation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.SplittableRandom;

import com.yottaflex.reliability.generation.domain.BusinessIdentifiers;
import com.yottaflex.reliability.generation.domain.GenerationRelationships;
import com.yottaflex.reliability.generation.domain.InspectionQuantities;
import com.yottaflex.reliability.generation.domain.InspectionQuantityFactory;
import com.yottaflex.reliability.generation.domain.SeededRandomFactory;
import com.yottaflex.reliability.generation.domain.SupplierProfile;
import com.yottaflex.reliability.generation.domain.SupplierProfileFactory;
import com.yottaflex.reliability.generation.infrastructure.DeterministicBatchWriter;
import org.springframework.stereotype.Component;

@Component
public class FulfillmentDataWriter {

    private static final String SHIPMENT_SQL = """
            INSERT INTO shipment
                (id, shipment_id, purchase_order_id, supplier_id, site_id, carrier_code, tracking_reference, dispatched_at, expected_delivery_on, status, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SHIPMENT_LINE_SQL = """
            INSERT INTO shipment_line
                (id, shipment_id, purchase_order_id, po_line_id, shipped_quantity, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String DELIVERY_EVENT_SQL = """
            INSERT INTO delivery_event
                (id, event_id, shipment_id, event_type, occurred_at, recorded_at, source, source_reference, description, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String RECEIPT_SQL = """
            INSERT INTO receipt
                (id, receipt_id, shipment_id, supplier_id, site_id, received_at, source_reference, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String RECEIPT_LINE_SQL = """
            INSERT INTO receipt_line
                (id, receipt_id, shipment_id, shipment_line_id, received_quantity, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSPECTION_SQL = """
            INSERT INTO inspection
                (id, inspection_id, receipt_id, inspected_at, inspector_ref, status, notes, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSPECTION_LINE_SQL = """
            INSERT INTO inspection_line
                (id, inspection_id, receipt_id, receipt_line_id, inspected_quantity, accepted_quantity, rejected_quantity, defect_code, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DeterministicBatchWriter batchWriter;

    public FulfillmentDataWriter(DeterministicBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public GenerationArtifactSummary writeShipments(GenerationContext context) {
        return batchWriter.write("shipment", SHIPMENT_SQL,
                GeneratedRows.range(context.counts().shipments(), sequence -> shipment(context, sequence)),
                context.counts().shipments(), context.batchSize());
    }

    public GenerationArtifactSummary writeShipmentLines(GenerationContext context) {
        return batchWriter.write("shipment_line", SHIPMENT_LINE_SQL,
                GeneratedRows.range(context.counts().shipmentLines(), sequence -> shipmentLine(context, sequence)),
                context.counts().shipmentLines(), context.batchSize());
    }

    public GenerationArtifactSummary writeDeliveryEvents(GenerationContext context) {
        return batchWriter.write("delivery_event", DELIVERY_EVENT_SQL,
                GeneratedRows.range(context.counts().deliveryEvents(), sequence -> deliveryEvent(context, sequence)),
                context.counts().deliveryEvents(), context.batchSize());
    }

    public GenerationArtifactSummary writeReceipts(GenerationContext context) {
        return batchWriter.write("receipt", RECEIPT_SQL,
                GeneratedRows.range(context.counts().receipts(), sequence -> receipt(context, sequence)),
                context.counts().receipts(), context.batchSize());
    }

    public GenerationArtifactSummary writeReceiptLines(GenerationContext context) {
        return batchWriter.write("receipt_line", RECEIPT_LINE_SQL,
                GeneratedRows.range(context.counts().receiptLines(), sequence -> receiptLine(context, sequence)),
                context.counts().receiptLines(), context.batchSize());
    }

    public GenerationArtifactSummary writeInspections(GenerationContext context) {
        return batchWriter.write("inspection", INSPECTION_SQL,
                GeneratedRows.range(context.counts().inspections(), sequence -> inspection(context, sequence)),
                context.counts().inspections(), context.batchSize());
    }

    public GenerationArtifactSummary writeInspectionLines(GenerationContext context) {
        return batchWriter.write("inspection_line", INSPECTION_LINE_SQL,
                GeneratedRows.range(context.counts().inspectionLines(), sequence -> inspectionLine(context, sequence)),
                context.counts().inspectionLines(), context.batchSize());
    }

    private GeneratedRow shipment(GenerationContext context, long sequence) {
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, sequence);
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        long site = GenerationRelationships.primarySiteId(context, supplier);
        ShipmentWindow window = shipmentWindow(context, sequence);
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "shipment-carrier", sequence);
        String carrier = switch (random.nextInt(4)) {
            case 0 -> "NORTHSTAR_FREIGHT";
            case 1 -> "CONTINENTAL_CARGO";
            case 2 -> "PACIFIC_LOGISTICS";
            default -> "REGIONAL_TRANSPORT";
        };
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{10}, sequence, BusinessIdentifiers.shipment(context.referenceDate().getYear(), sequence),
                purchaseOrder, supplier, site, carrier, "TRK-" + String.format(java.util.Locale.ROOT, "%08d", sequence),
                GeneratedRows.sqlTimestamp(window.dispatchedAt()), GeneratedRows.sqlDate(window.expectedDate()), "DELIVERED",
                context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow shipmentLine(GenerationContext context, long sequence) {
        GenerationRelationships.ShipmentLineReference reference =
                GenerationRelationships.shipmentLineForSequence(context, sequence);
        long shipment = reference.shipmentSequence();
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, shipment);
        int poLineOrdinal = GenerationRelationships.purchaseOrderLineOrdinalForShipmentLine(context, shipment, reference.lineOrdinal());
        long poLine = GenerationRelationships.purchaseOrderLineStart(context, purchaseOrder) + poLineOrdinal - 1;
        BigDecimal quantity = ProcurementDataWriter.purchaseOrderLineQuantity(context, poLine);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{5}, sequence, shipment, purchaseOrder, poLine, quantity,
                context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow deliveryEvent(GenerationContext context, long sequence) {
        long shipment = ((sequence - 1) / 2) + 1;
        int eventOrdinal = (int) (((sequence - 1) % 2) + 1);
        ShipmentWindow window = shipmentWindow(context, shipment);
        boolean dispatchEvent = eventOrdinal == 1;
        Instant occurredAt = dispatchEvent ? window.dispatchedAt() : window.deliveredAt();
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        String eventType = dispatchEvent ? "DISPATCHED" : "DELIVERED";
        String sourceReference = "SYN-" + BusinessIdentifiers.shipment(context.referenceDate().getYear(), shipment) + "-" + eventOrdinal;
        String eventId = "EVT-" + BusinessIdentifiers.shipment(context.referenceDate().getYear(), shipment) + "-" + eventOrdinal;
        return GeneratedRows.rowOmitting(new int[]{9}, sequence, eventId, shipment, eventType,
                GeneratedRows.sqlTimestamp(occurredAt), GeneratedRows.sqlTimestamp(occurredAt), "SYNTHETIC_TMS",
                sourceReference, dispatchEvent ? "Carrier pickup recorded" : "Shipment delivered to receiving site",
                context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow receipt(GenerationContext context, long sequence) {
        long shipment = GenerationRelationships.receiptForShipment(context, sequence);
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, shipment);
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        long site = GenerationRelationships.primarySiteId(context, supplier);
        Instant receivedAt = shipmentWindow(context, shipment).deliveredAt().plusSeconds(3_600);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        String receiptId = BusinessIdentifiers.receipt(context.referenceDate().getYear(), sequence);
        return GeneratedRows.rowOmitting(new int[]{7}, sequence, receiptId, shipment, supplier, site,
                GeneratedRows.sqlTimestamp(receivedAt), "GRN-" + String.format(java.util.Locale.ROOT, "%08d", sequence),
                context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow receiptLine(GenerationContext context, long sequence) {
        GenerationRelationships.ShipmentLineReference reference =
                GenerationRelationships.shipmentLineForSequence(context, sequence);
        long shipment = reference.shipmentSequence();
        long receipt = GenerationRelationships.receiptForShipmentLine(context, shipment, reference.lineOrdinal());
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, shipment);
        int poLineOrdinal = GenerationRelationships.purchaseOrderLineOrdinalForShipmentLine(context, shipment, reference.lineOrdinal());
        long poLine = GenerationRelationships.purchaseOrderLineStart(context, purchaseOrder) + poLineOrdinal - 1;
        BigDecimal quantity = ProcurementDataWriter.purchaseOrderLineQuantity(context, poLine);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{5}, sequence, receipt, shipment, sequence, quantity,
                context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow inspection(GenerationContext context, long sequence) {
        long receipt = sequence;
        long shipment = GenerationRelationships.receiptForShipment(context, receipt);
        Instant inspectedAt = shipmentWindow(context, shipment).deliveredAt().plusSeconds(14_400);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{7}, sequence, BusinessIdentifiers.inspection(context.referenceDate().getYear(), sequence),
                receipt, GeneratedRows.sqlTimestamp(inspectedAt), "QC-" + String.format(java.util.Locale.ROOT, "%03d", (sequence % 40) + 1),
                "COMPLETED", null, context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow inspectionLine(GenerationContext context, long sequence) {
        GenerationRelationships.ShipmentLineReference reference =
                GenerationRelationships.shipmentLineForSequence(context, sequence);
        long shipment = reference.shipmentSequence();
        long receipt = GenerationRelationships.receiptForShipmentLine(context, shipment, reference.lineOrdinal());
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, shipment);
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        int poLineOrdinal = GenerationRelationships.purchaseOrderLineOrdinalForShipmentLine(context, shipment, reference.lineOrdinal());
        long poLine = GenerationRelationships.purchaseOrderLineStart(context, purchaseOrder) + poLineOrdinal - 1;
        BigDecimal inspected = ProcurementDataWriter.purchaseOrderLineQuantity(context, poLine);
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), supplier);
        InspectionQuantities quantities = InspectionQuantityFactory.forRecord(inspected, profile, context.seed(), sequence);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{8}, sequence, receipt, receipt, sequence, quantities.inspected(),
            quantities.accepted(), quantities.rejected(), quantities.defectCode(), context.generationRunId(),
            GeneratedRows.sqlTimestamp(generatedAt));
    }

    public static ShipmentWindow shipmentWindow(GenerationContext context, long shipmentSequence) {
        long purchaseOrder = GenerationRelationships.shipmentPurchaseOrder(context, shipmentSequence);
        SplittableRandom orderRandom = SeededRandomFactory.forRecord(context.seed(), "purchase-order", purchaseOrder);
        LocalDate orderDate = context.referenceDate().minusDays(70L + orderRandom.nextInt(295));
        LocalDate expectedDate = orderDate.plusDays(15L + orderRandom.nextInt(21));
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), supplier);
        SplittableRandom shipmentRandom = SeededRandomFactory.forRecord(context.seed(), "shipment-performance", shipmentSequence);
        boolean late = shipmentRandom.nextInt(100) < profile.lateDeliveryChance();
        int delayDays = late ? shipmentRandom.nextInt(1, 9) : -shipmentRandom.nextInt(0, 4);
        LocalDate deliveredDate = expectedDate.plusDays(delayDays);
        LocalDate dispatchedDate = orderDate.plusDays(2L + shipmentRandom.nextInt(6));
        if (!deliveredDate.isAfter(dispatchedDate)) {
            deliveredDate = dispatchedDate.plusDays(1);
        }
        Instant dispatchedAt = dispatchedDate.atTime(8 + shipmentRandom.nextInt(8), shipmentRandom.nextInt(60)).toInstant(ZoneOffset.UTC);
        Instant deliveredAt = deliveredDate.atTime(8 + shipmentRandom.nextInt(8), shipmentRandom.nextInt(60)).toInstant(ZoneOffset.UTC);
        if (!deliveredAt.isAfter(dispatchedAt)) {
            deliveredAt = dispatchedAt.plusSeconds(3_600);
        }
        return new ShipmentWindow(dispatchedAt, expectedDate, deliveredAt);
    }

    public record ShipmentWindow(Instant dispatchedAt, LocalDate expectedDate, Instant deliveredAt) {
    }
}
