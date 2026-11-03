package com.yottaflex.reliability.generation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.SplittableRandom;

import com.yottaflex.reliability.generation.domain.BusinessIdentifiers;
import com.yottaflex.reliability.generation.domain.GenerationRelationships;
import com.yottaflex.reliability.generation.domain.SeededRandomFactory;
import com.yottaflex.reliability.generation.infrastructure.DeterministicBatchWriter;
import org.springframework.stereotype.Component;

@Component
public class ProcurementDataWriter {

    private static final String PURCHASE_ORDER_SQL = """
            INSERT INTO purchase_order
                (id, po_id, supplier_id, site_id, contract_id, ordered_on, required_delivery_on, currency, status, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String PO_LINE_SQL = """
            INSERT INTO po_line
                (id, purchase_order_id, line_number, product_code, description, ordered_quantity, unit_of_measure, unit_price, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DeterministicBatchWriter batchWriter;

    public ProcurementDataWriter(DeterministicBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public GenerationArtifactSummary writePurchaseOrders(GenerationContext context) {
        return batchWriter.write("purchase_order", PURCHASE_ORDER_SQL,
                GeneratedRows.range(context.counts().purchaseOrders(), sequence -> purchaseOrder(context, sequence)),
                context.counts().purchaseOrders(), context.batchSize());
    }

    public GenerationArtifactSummary writePurchaseOrderLines(GenerationContext context) {
        return batchWriter.write("po_line", PO_LINE_SQL,
                GeneratedRows.range(context.counts().poLines(), sequence -> purchaseOrderLine(context, sequence)),
                context.counts().poLines(), context.batchSize());
    }

    private GeneratedRow purchaseOrder(GenerationContext context, long sequence) {
        long supplierSequence = GenerationRelationships.supplierForPurchaseOrder(context, sequence);
        long siteId = GenerationRelationships.primarySiteId(context, supplierSequence);
        long contractId = GenerationRelationships.contractForPurchaseOrder(context, supplierSequence, sequence);
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "purchase-order", sequence);
        LocalDate orderDate = context.referenceDate().minusDays(70L + random.nextInt(295));
        LocalDate requiredDate = orderDate.plusDays(15L + random.nextInt(21));
        String currency = SupplierMasterDataWriter.currencyForSupplier(context, supplierSequence);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{9}, sequence,
                BusinessIdentifiers.purchaseOrder(context.referenceDate().getYear(), sequence),
                supplierSequence, siteId, contractId, GeneratedRows.sqlDate(orderDate), GeneratedRows.sqlDate(requiredDate),
                currency, "FULFILLED", context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow purchaseOrderLine(GenerationContext context, long sequence) {
        GenerationRelationships.PurchaseOrderLineReference reference =
                GenerationRelationships.purchaseOrderLineForSequence(context, sequence);
        long purchaseOrderSequence = reference.purchaseOrderSequence();
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "purchase-order-line", sequence);
        int productOrdinal = random.nextInt(1, 101);
        String productCode = productCode(productOrdinal);
        BigDecimal quantity = BigDecimal.valueOf(25 + random.nextInt(4_975)).setScale(4);
        BigDecimal unitPrice = BigDecimal.valueOf(125 + random.nextInt(199_875), 3).setScale(4);
        String unit = unitForProduct(productOrdinal);
        String description = productCode + " " + (unit.equals("EA") ? "industrial component" : "bulk material");
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{8}, sequence, purchaseOrderSequence, reference.lineNumber(),
                productCode, description, quantity, unit, unitPrice, context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    public static BigDecimal purchaseOrderLineQuantity(GenerationContext context, long lineSequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "purchase-order-line", lineSequence);
        random.nextInt(1, 101);
        return BigDecimal.valueOf(25 + random.nextInt(4_975)).setScale(4);
    }

    public static BigDecimal purchaseOrderLineUnitPrice(GenerationContext context, long lineSequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "purchase-order-line", lineSequence);
        random.nextInt(1, 101);
        random.nextInt(4_975);
        return BigDecimal.valueOf(125 + random.nextInt(199_875), 3).setScale(4);
    }

    public static String productCode(int productOrdinal) {
        if (productOrdinal < 1 || productOrdinal > 100) {
            throw new IllegalArgumentException("Synthetic product ordinal must be between 1 and 100");
        }
        return "SKU-" + String.format(java.util.Locale.ROOT, "%04d", productOrdinal);
    }

    public static String unitForProduct(int productOrdinal) {
        return productOrdinal % 5 == 0 ? "KG" : "EA";
    }
}
