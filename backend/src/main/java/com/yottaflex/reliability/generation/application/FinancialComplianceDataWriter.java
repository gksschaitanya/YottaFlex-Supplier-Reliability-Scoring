package com.yottaflex.reliability.generation.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.SplittableRandom;

import com.yottaflex.reliability.generation.domain.BusinessIdentifiers;
import com.yottaflex.reliability.generation.domain.ComplianceCheckRequirementIds;
import com.yottaflex.reliability.generation.domain.GenerationRelationships;
import com.yottaflex.reliability.generation.domain.SeededRandomFactory;
import com.yottaflex.reliability.generation.domain.SupplierProfile;
import com.yottaflex.reliability.generation.domain.SupplierProfileFactory;
import com.yottaflex.reliability.generation.domain.SyntheticRiskSignal;
import com.yottaflex.reliability.generation.domain.SyntheticRiskSignalFactory;
import com.yottaflex.reliability.generation.infrastructure.DeterministicBatchWriter;
import org.springframework.stereotype.Component;

@Component
public class FinancialComplianceDataWriter {

    private static final String INVOICE_SQL = """
            INSERT INTO invoice
                (id, invoice_id, purchase_order_id, supplier_id, invoice_date, currency, subtotal, tax_amount, freight_amount, total_amount, status, source_reference, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INVOICE_LINE_SQL = """
            INSERT INTO invoice_line
                (id, invoice_id, purchase_order_id, po_line_id, quantity, unit_price, line_amount, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String RISK_SIGNAL_SQL = """
            INSERT INTO risk_signal
                (id, risk_id, supplier_id, site_id, risk_type, severity, source, source_reference, starts_at, ends_at, description, recorded_at, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String COMPLIANCE_ASSESSMENT_SQL = """
            INSERT INTO compliance_assessment
                (id, assessment_id, supplier_id, site_id, assessed_at, effective_from, expires_on, status, source, evidence_reference, generation_run_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String COMPLIANCE_CHECK_SQL = """
            INSERT INTO compliance_check
                (id, assessment_id, requirement_id, result, checked_at, evidence_reference, notes, generation_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DeterministicBatchWriter batchWriter;

    public FinancialComplianceDataWriter(DeterministicBatchWriter batchWriter) {
        this.batchWriter = batchWriter;
    }

    public GenerationArtifactSummary writeInvoices(GenerationContext context) {
        return batchWriter.write("invoice", INVOICE_SQL,
                GeneratedRows.range(context.counts().invoices(), sequence -> invoice(context, sequence)),
                context.counts().invoices(), context.batchSize());
    }

    public GenerationArtifactSummary writeInvoiceLines(GenerationContext context) {
        return batchWriter.write("invoice_line", INVOICE_LINE_SQL,
                GeneratedRows.range(context.counts().invoiceLines(), sequence -> invoiceLine(context, sequence)),
                context.counts().invoiceLines(), context.batchSize());
    }

    public GenerationArtifactSummary writeRiskSignals(GenerationContext context) {
        return batchWriter.write("risk_signal", RISK_SIGNAL_SQL,
                GeneratedRows.range(context.counts().riskSignals(), sequence -> riskSignal(context, sequence)),
                context.counts().riskSignals(), context.batchSize());
    }

    public GenerationArtifactSummary writeComplianceAssessments(GenerationContext context) {
        return batchWriter.write("compliance_assessment", COMPLIANCE_ASSESSMENT_SQL,
                GeneratedRows.range(context.counts().complianceAssessments(), sequence -> assessment(context, sequence)),
                context.counts().complianceAssessments(), context.batchSize());
    }

    public GenerationArtifactSummary writeComplianceChecks(GenerationContext context) {
        return batchWriter.write("compliance_check", COMPLIANCE_CHECK_SQL,
                GeneratedRows.range(context.counts().complianceChecks(), sequence -> complianceCheck(context, sequence)),
                context.counts().complianceChecks(), context.batchSize());
    }

    private GeneratedRow invoice(GenerationContext context, long sequence) {
        long purchaseOrder = GenerationRelationships.invoicePurchaseOrder(context, sequence);
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        int lineCount = GenerationRelationships.invoiceLineCount(context, sequence);
        BigDecimal subtotal = BigDecimal.ZERO.setScale(4);
        for (int lineOrdinal = 1; lineOrdinal <= lineCount; lineOrdinal++) {
            long poLine = GenerationRelationships.invoiceLinePoLine(context, sequence, lineOrdinal);
            subtotal = subtotal.add(invoiceLineAmount(context, sequence, lineOrdinal, supplier, poLine));
        }
        BigDecimal tax = BigDecimal.ZERO.setScale(4);
        BigDecimal freight = BigDecimal.ZERO.setScale(4);
        BigDecimal total = subtotal.add(tax).add(freight).setScale(4, RoundingMode.UNNECESSARY);
        long firstShipment = GenerationRelationships.shipmentSequenceForPurchaseOrder(context, purchaseOrder, 1);
        LocalDate invoiceDate = FulfillmentDataWriter.shipmentWindow(context, firstShipment).deliveredAt()
                .atZone(ZoneOffset.UTC).toLocalDate().plusDays(5);
        String currency = SupplierMasterDataWriter.currencyForSupplier(context, supplier);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{12}, sequence,
                BusinessIdentifiers.invoice(context.referenceDate().getYear(), sequence), purchaseOrder, supplier,
                GeneratedRows.sqlDate(invoiceDate), currency, subtotal, tax, freight, total, "MATCHED",
                "AP-" + String.format(java.util.Locale.ROOT, "%08d", sequence), context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow invoiceLine(GenerationContext context, long sequence) {
        InvoiceLineReference reference = invoiceLineReference(context, sequence);
        long invoice = reference.invoiceSequence();
        long purchaseOrder = GenerationRelationships.invoicePurchaseOrder(context, invoice);
        long supplier = GenerationRelationships.supplierForPurchaseOrder(context, purchaseOrder);
        long poLine = GenerationRelationships.invoiceLinePoLine(context, invoice, reference.lineOrdinal());
        BigDecimal quantity = ProcurementDataWriter.purchaseOrderLineQuantity(context, poLine);
        BigDecimal unitPrice = invoiceUnitPrice(context, invoice, reference.lineOrdinal(), supplier, poLine);
        BigDecimal lineAmount = quantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{7}, sequence, invoice, purchaseOrder, poLine, quantity,
                unitPrice, lineAmount, context.generationRunId(), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow riskSignal(GenerationContext context, long sequence) {
        long signalsPerSupplier = context.counts().riskSignals() / context.counts().suppliers();
        long supplier = ((sequence - 1) / signalsPerSupplier) + 1;
        long ordinal = ((sequence - 1) % signalsPerSupplier) + 1;
        SyntheticRiskSignal risk = SyntheticRiskSignalFactory.forRecord(context.seed(), sequence, context.referenceDate());
        Long site = ordinal % 2 == 0 ? GenerationRelationships.primarySiteId(context, supplier) : null;
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{12}, sequence,
                BusinessIdentifiers.riskSignal(BusinessIdentifiers.supplier(supplier), context.referenceDate().getYear(), ordinal),
                supplier, site, risk.riskType(), risk.severity(), risk.source(), risk.sourceReference(),
                GeneratedRows.sqlTimestamp(risk.startsAt()), risk.endsAt() == null ? null : GeneratedRows.sqlTimestamp(risk.endsAt()),
                risk.description(), GeneratedRows.sqlTimestamp(risk.recordedAt()), context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow assessment(GenerationContext context, long sequence) {
        long assessmentsPerSupplier = context.counts().complianceAssessments() / context.counts().suppliers();
        long supplier = ((sequence - 1) / assessmentsPerSupplier) + 1;
        long ordinal = ((sequence - 1) % assessmentsPerSupplier) + 1;
        AssessmentWindow window = assessmentWindow(context, sequence);
        Long site = ordinal == 2 ? GenerationRelationships.primarySiteId(context, supplier) : null;
        Instant assessedAt = window.assessedAt();
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{10}, sequence,
                BusinessIdentifiers.complianceAssessment(context.referenceDate().getYear(), sequence), supplier, site,
                GeneratedRows.sqlTimestamp(assessedAt), GeneratedRows.sqlDate(window.effectiveFrom()), GeneratedRows.sqlDate(window.expiresOn()),
                "COMPLETED", "SYNTHETIC_COMPLIANCE_FEED", "CMP-EVIDENCE-" + sequence, context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt), GeneratedRows.sqlTimestamp(generatedAt));
    }

    private GeneratedRow complianceCheck(GenerationContext context, long sequence) {
        int checksPerAssessment = Math.toIntExact(context.counts().complianceChecks() / context.counts().complianceAssessments());
        long assessment = ((sequence - 1) / checksPerAssessment) + 1;
        int ordinal = (int) (((sequence - 1) % checksPerAssessment) + 1);
        long supplier = ((assessment - 1) / (context.counts().complianceAssessments() / context.counts().suppliers())) + 1;
        long requirement = ComplianceCheckRequirementIds.forOrdinal(ordinal);
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), supplier);
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "compliance-check", sequence);
        boolean failed = profile == SupplierProfile.COMPLIANCE_RISK
                ? random.nextInt(100) < 55
                : random.nextInt(100) < 4;
        if (requirement == 8 && profile == SupplierProfile.COMPLIANCE_RISK) {
            failed = random.nextInt(100) < 75;
        }
        Instant checkedAt = assessmentWindow(context, assessment).assessedAt().plusSeconds(3_600);
        Instant generatedAt = context.referenceDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        return GeneratedRows.rowOmitting(new int[]{7}, sequence, assessment, requirement, failed ? "FAIL" : "PASS",
                GeneratedRows.sqlTimestamp(checkedAt), "CMP-CHECK-EVIDENCE-" + sequence,
                failed ? "Synthetic evidence requires follow-up" : null, context.generationRunId(),
                GeneratedRows.sqlTimestamp(generatedAt));
    }

    private BigDecimal invoiceLineAmount(GenerationContext context, long invoice, int lineOrdinal, long supplier, long poLine) {
        return ProcurementDataWriter.purchaseOrderLineQuantity(context, poLine)
                .multiply(invoiceUnitPrice(context, invoice, lineOrdinal, supplier, poLine))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal invoiceUnitPrice(GenerationContext context, long invoice, int lineOrdinal, long supplier, long poLine) {
        BigDecimal expectedPrice = ProcurementDataWriter.purchaseOrderLineUnitPrice(context, poLine);
        SupplierProfile profile = SupplierProfileFactory.forSupplier(context.seed(), supplier);
        long varianceSeed = invoice * 4 + lineOrdinal;
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "invoice-variance", varianceSeed);
        boolean variance = random.nextDouble() < profile.invoiceVarianceRate();
        if (!variance) {
            return expectedPrice;
        }
        BigDecimal multiplier = BigDecimal.valueOf(9_700 + random.nextInt(601), 4);
        return expectedPrice.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
    }

    private InvoiceLineReference invoiceLineReference(GenerationContext context, long lineSequence) {
        long additionalLines = context.counts().invoiceLines() - context.counts().invoices();
        long linesForExpandedInvoices = additionalLines * 2;
        if (lineSequence <= linesForExpandedInvoices) {
            return new InvoiceLineReference(((lineSequence - 1) / 2) + 1, (int) (((lineSequence - 1) % 2) + 1));
        }
        return new InvoiceLineReference(additionalLines + (lineSequence - linesForExpandedInvoices), 1);
    }

    private AssessmentWindow assessmentWindow(GenerationContext context, long sequence) {
        SplittableRandom random = SeededRandomFactory.forRecord(context.seed(), "compliance-assessment", sequence);
        LocalDate effectiveFrom = context.referenceDate().minusDays(60L + random.nextInt(306));
        LocalDate assessedOn = effectiveFrom.plusDays(random.nextInt(0, 30));
        LocalDate expiresOn = effectiveFrom.plusDays(365 + random.nextInt(366));
        Instant assessedAt = assessedOn.atTime(10, 0).toInstant(ZoneOffset.UTC);
        return new AssessmentWindow(effectiveFrom, assessedAt, expiresOn);
    }

    private record InvoiceLineReference(long invoiceSequence, int lineOrdinal) {
    }

    private record AssessmentWindow(LocalDate effectiveFrom, Instant assessedAt, LocalDate expiresOn) {
    }
}
