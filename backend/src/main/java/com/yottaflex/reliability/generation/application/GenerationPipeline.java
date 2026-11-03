package com.yottaflex.reliability.generation.application;

import java.util.ArrayList;
import java.util.List;

import com.yottaflex.reliability.generation.infrastructure.GenerationTargetGuard;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunEntity;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactEntity;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactRepository;
import org.springframework.stereotype.Component;

@Component
public class GenerationPipeline {

    private static final String SCHEMA_VERSION = "phase-2-v1";

    private final GenerationTargetGuard targetGuard;
    private final SupplierMasterDataWriter masterDataWriter;
    private final ProcurementDataWriter procurementDataWriter;
    private final FulfillmentDataWriter fulfillmentDataWriter;
    private final FinancialComplianceDataWriter financialComplianceDataWriter;
    private final GenerationArtifactRepository artifactRepository;
    private final GenerationIntegrityValidator integrityValidator;

    public GenerationPipeline(
            GenerationTargetGuard targetGuard,
            SupplierMasterDataWriter masterDataWriter,
            ProcurementDataWriter procurementDataWriter,
            FulfillmentDataWriter fulfillmentDataWriter,
            FinancialComplianceDataWriter financialComplianceDataWriter,
            GenerationArtifactRepository artifactRepository,
            GenerationIntegrityValidator integrityValidator) {
        this.targetGuard = targetGuard;
        this.masterDataWriter = masterDataWriter;
        this.procurementDataWriter = procurementDataWriter;
        this.fulfillmentDataWriter = fulfillmentDataWriter;
        this.financialComplianceDataWriter = financialComplianceDataWriter;
        this.artifactRepository = artifactRepository;
        this.integrityValidator = integrityValidator;
    }

    public List<GenerationArtifactSummary> generate(GenerationContext context, DataGenerationRunEntity run) {
        targetGuard.requireSyntheticOnlyEmptyTarget();
        List<GenerationArtifactSummary> artifacts = new ArrayList<>();
        record(artifacts, masterDataWriter.writeComplianceCatalog(context), run);
        record(artifacts, masterDataWriter.writeSuppliers(context), run);
        record(artifacts, masterDataWriter.writeSites(context), run);
        record(artifacts, masterDataWriter.writeContracts(context), run);
        record(artifacts, masterDataWriter.writeCapacityCommitments(context), run);
        record(artifacts, masterDataWriter.writeSupplierStatusHistory(context), run);
        record(artifacts, procurementDataWriter.writePurchaseOrders(context), run);
        record(artifacts, procurementDataWriter.writePurchaseOrderLines(context), run);
        record(artifacts, fulfillmentDataWriter.writeShipments(context), run);
        record(artifacts, fulfillmentDataWriter.writeShipmentLines(context), run);
        record(artifacts, fulfillmentDataWriter.writeDeliveryEvents(context), run);
        record(artifacts, fulfillmentDataWriter.writeReceipts(context), run);
        record(artifacts, fulfillmentDataWriter.writeReceiptLines(context), run);
        record(artifacts, fulfillmentDataWriter.writeInspections(context), run);
        record(artifacts, fulfillmentDataWriter.writeInspectionLines(context), run);
        record(artifacts, financialComplianceDataWriter.writeInvoices(context), run);
        record(artifacts, financialComplianceDataWriter.writeInvoiceLines(context), run);
        record(artifacts, financialComplianceDataWriter.writeRiskSignals(context), run);
        record(artifacts, financialComplianceDataWriter.writeComplianceAssessments(context), run);
        record(artifacts, financialComplianceDataWriter.writeComplianceChecks(context), run);
        integrityValidator.validate(context);
        return List.copyOf(artifacts);
    }

    private void record(List<GenerationArtifactSummary> summaries, GenerationArtifactSummary summary, DataGenerationRunEntity run) {
        GenerationArtifactEntity artifact = new GenerationArtifactEntity();
        artifact.setArtifactId(run.getRunId() + ":" + summary.artifactName());
        artifact.setGenerationRun(run);
        artifact.setArtifactName(summary.artifactName());
        artifact.setSchemaVersion(SCHEMA_VERSION);
        artifact.setRowCount(summary.rowCount());
        artifact.setSha256(summary.sha256());
        artifactRepository.save(artifact);
        summaries.add(summary);
    }
}
