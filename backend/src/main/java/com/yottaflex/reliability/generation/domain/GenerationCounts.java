package com.yottaflex.reliability.generation.domain;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public record GenerationCounts(
        long suppliers,
        long sites,
        long contracts,
        long capacityCommitments,
        long purchaseOrders,
        long poLines,
        long shipments,
        long shipmentLines,
        long deliveryEvents,
        long receipts,
        long receiptLines,
        long inspections,
        long inspectionLines,
        long invoices,
        long invoiceLines,
        long riskSignals,
        long complianceRequirements,
        long complianceAssessments,
        long complianceChecks,
        long supplierStatusHistory) {

    public Map<String, Long> asOrderedMap() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("supplier", suppliers);
        counts.put("site", sites);
        counts.put("contract", contracts);
        counts.put("capacity_commitment", capacityCommitments);
        counts.put("purchase_order", purchaseOrders);
        counts.put("po_line", poLines);
        counts.put("shipment", shipments);
        counts.put("shipment_line", shipmentLines);
        counts.put("delivery_event", deliveryEvents);
        counts.put("receipt", receipts);
        counts.put("receipt_line", receiptLines);
        counts.put("inspection", inspections);
        counts.put("inspection_line", inspectionLines);
        counts.put("invoice", invoices);
        counts.put("invoice_line", invoiceLines);
        counts.put("risk_signal", riskSignals);
        counts.put("compliance_requirement", complianceRequirements);
        counts.put("compliance_assessment", complianceAssessments);
        counts.put("compliance_check", complianceChecks);
        counts.put("supplier_status_history", supplierStatusHistory);
        return Collections.unmodifiableMap(counts);
    }
}
