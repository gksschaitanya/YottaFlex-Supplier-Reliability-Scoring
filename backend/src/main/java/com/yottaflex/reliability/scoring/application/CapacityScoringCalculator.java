package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/** Allocates gross received quantities once across matching capacity commitments. */
public final class CapacityScoringCalculator {
    private static final int SCALE=8;
    private static final BigDecimal HUNDRED=new BigDecimal("100");

    public record Commitment(long id,String businessId,long supplierId,Long siteId,Long siteOwnerSupplierId,
                             String productCode,String unitOfMeasure,LocalDate periodStart,LocalDate periodEnd,
                             BigDecimal committedQuantity,Instant createdAt,Instant recordedAt) { }

    public record ReceiptLine(long id,String receiptId,long receiptSupplierId,Long receiptSiteId,long receiptShipmentId,long receiptLineShipmentId,
                              Instant receivedAt,Instant receiptCreatedAt,
                              long shipmentId,long shipmentSupplierId,Long shipmentSiteId,Long shipmentPurchaseOrderId,
                              Instant shipmentCreatedAt,
                              Long resolvedShipmentLineId,Long shipmentLineShipmentId,Long shipmentLinePurchaseOrderId,
                              Long poLineId,BigDecimal shippedQuantity,Instant shipmentLineCreatedAt,
                              String purchaseOrderId,long poSupplierId,Long poSiteId,Long poLinePurchaseOrderId,
                              String productCode,String unitOfMeasure,Instant purchaseOrderCreatedAt,
                              Instant poLineCreatedAt,BigDecimal receivedQuantity,Instant receiptLineCreatedAt) { }

    public record Evidence(String sourceEntityType,long sourceRecordId,String sourceBusinessId,boolean included,
                           BigDecimal contribution,String exclusionReason,Map<String,Object> details) { }
    public record Result(BigDecimal score,BigDecimal numerator,BigDecimal denominator,long observations,
                         long eligibleObservations,long excludedObservations,String status,String reason,
                         Map<String,Object> metrics,List<Evidence> evidence) { }

    private record Allocation(Commitment commitment,ReceiptLine receipt,BigDecimal quantity,BigDecimal remainingAfter) { }

    public Result calculate(long supplierId,List<Commitment> sourceCommitments,List<ReceiptLine> sourceReceipts,
                            LocalDate windowStart,Instant asOf,long minimumCommitments) {
        LocalDate asOfDate=asOf.atZone(ZoneOffset.UTC).toLocalDate();
        Map<Long,Commitment> commitmentsById=new TreeMap<>();
        Map<Long,String> commitmentFailures=new HashMap<>();
        for(Commitment c:sourceCommitments.stream().sorted(Comparator.comparing(Commitment::businessId)).toList()) {
            String reason=validateCommitment(c,supplierId,windowStart,asOf,asOfDate);
            if(commitmentsById.putIfAbsent(c.id(),c)!=null)reason="DUPLICATE_COMMITMENT_ID";
            if(reason!=null)commitmentFailures.put(c.id(),reason);
        }
        List<Commitment> eligibleCommitments=commitmentsById.values().stream()
                .filter(c->!commitmentFailures.containsKey(c.id()))
                .sorted(Comparator.comparing(Commitment::businessId)).toList();
        Map<Long,BigDecimal> commitmentRemaining=new HashMap<>();
        eligibleCommitments.forEach(c->commitmentRemaining.put(c.id(),c.committedQuantity()));

        Map<Long,List<ReceiptLine>> duplicateGroups=new TreeMap<>();
        sourceReceipts.forEach(line->duplicateGroups.computeIfAbsent(line.id(),ignored->new ArrayList<>()).add(line));
        List<ReceiptLine> uniqueReceipts=new ArrayList<>();
        Map<Long,String> duplicateConflicts=new HashMap<>();
        long duplicateRows=0;
        for(List<ReceiptLine> group:duplicateGroups.values()) {
            group.sort(Comparator.comparing(ReceiptLine::receiptId,Comparator.nullsLast(Comparator.naturalOrder())));
            uniqueReceipts.add(group.getFirst());
            if(group.size()>1) {
                duplicateRows+=group.size()-1;
                if(group.stream().anyMatch(line->!line.equals(group.getFirst())))duplicateConflicts.put(group.getFirst().id(),"DUPLICATE_RECEIPT_LINE_CONFLICT");
            }
        }
        uniqueReceipts.sort(Comparator.comparing(ReceiptLine::receivedAt,Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReceiptLine::receiptId,Comparator.nullsLast(Comparator.naturalOrder())).thenComparingLong(ReceiptLine::id));

        Map<Long,String> receiptFailures=new HashMap<>();
        for(ReceiptLine line:uniqueReceipts) {
            String reason=duplicateConflicts.get(line.id());
            if(reason==null)reason=validateReceipt(line,supplierId,windowStart,asOf);
            if(reason!=null)receiptFailures.put(line.id(),reason);
        }

        Map<Long,BigDecimal> shippedRemaining=new HashMap<>();
        Map<Long,BigDecimal> excessByReceipt=new HashMap<>();
        Map<Long,BigDecimal> validByReceipt=new HashMap<>();
        for(ReceiptLine line:uniqueReceipts) {
            if(receiptFailures.containsKey(line.id()))continue;
            long shipmentLine=line.resolvedShipmentLineId();
            BigDecimal remaining=shippedRemaining.computeIfAbsent(shipmentLine,ignored->line.shippedQuantity());
            BigDecimal valid=line.receivedQuantity().min(remaining.max(BigDecimal.ZERO));
            BigDecimal excess=line.receivedQuantity().subtract(valid);
            shippedRemaining.put(shipmentLine,remaining.subtract(valid));
            validByReceipt.put(line.id(),valid);
            excessByReceipt.put(line.id(),excess);
        }

        Comparator<Commitment> priority=Comparator
                .comparing((Commitment c)->c.siteId()==null?1:0)
                .thenComparing(Commitment::periodEnd).thenComparing(Commitment::periodStart)
                .thenComparing(Commitment::businessId);
        Map<Long,List<Allocation>> allocationsByReceipt=new HashMap<>();
        Map<Long,List<Allocation>> allocationsByCommitment=new HashMap<>();
        Map<Long,String> unallocatedReasons=new HashMap<>();
        for(ReceiptLine receipt:uniqueReceipts) {
            BigDecimal remaining=validByReceipt.getOrDefault(receipt.id(),BigDecimal.ZERO);
            if(remaining.signum()==0)continue;
            List<Commitment> matching=eligibleCommitments.stream().filter(c->matches(c,receipt)).sorted(priority).toList();
            for(Commitment commitment:matching) {
                BigDecimal commitmentQty=commitmentRemaining.get(commitment.id());
                BigDecimal allocated=remaining.min(commitmentQty.max(BigDecimal.ZERO));
                if(allocated.signum()<=0)continue;
                Allocation allocation=new Allocation(commitment,receipt,allocated,commitmentQty.subtract(allocated));
                allocationsByReceipt.computeIfAbsent(receipt.id(),ignored->new ArrayList<>()).add(allocation);
                allocationsByCommitment.computeIfAbsent(commitment.id(),ignored->new ArrayList<>()).add(allocation);
                commitmentRemaining.put(commitment.id(),commitmentQty.subtract(allocated));
                remaining=remaining.subtract(allocated);
                if(remaining.signum()==0)break;
            }
            if(remaining.signum()>0)unallocatedReasons.put(receipt.id(),matching.isEmpty()?unmatchedReason(eligibleCommitments,receipt):"COMMITMENT_CAPACITY_EXHAUSTED");
        }

        BigDecimal totalCommitted=eligibleCommitments.stream().map(Commitment::committedQuantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal totalAllocated=allocationsByReceipt.values().stream().flatMap(Collection::stream).map(Allocation::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal totalRemaining=commitmentRemaining.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal receiptExcess=excessByReceipt.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal unallocated=validByReceipt.entrySet().stream().map(entry->entry.getValue().subtract(
                allocationsByReceipt.getOrDefault(entry.getKey(),List.of()).stream().map(Allocation::quantity).reduce(BigDecimal.ZERO,BigDecimal::add)))
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal denominator=totalCommitted;
        BigDecimal score=denominator.signum()==0?null:totalAllocated.multiply(HUNDRED).divide(denominator,SCALE,RoundingMode.HALF_UP)
                .min(HUNDRED).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);

        long invalidCommitments=commitmentFailures.values().stream().filter(reason->!"OUTSIDE_EVALUATION_WINDOW".equals(reason)).count();
        String status;
        String missingReason=null;
        if(eligibleCommitments.size()>=minimumCommitments&&denominator.signum()>0)status="AVAILABLE";
        else if(eligibleCommitments.isEmpty()&&sourceCommitments.isEmpty()){status="MISSING_DATA";missingReason="No capacity commitments are available";}
        else if(eligibleCommitments.isEmpty()&&invalidCommitments>0){status="EXCLUDED_INVALID_DATA";missingReason="All observed capacity commitments were invalid or unavailable at cutoff";}
        else {status="INSUFFICIENT_OBSERVATIONS";missingReason="Eligible commitments below configured minimum ("+minimumCommitments+")";}

        List<Evidence> evidence=new ArrayList<>();
        for(Commitment c:commitmentsById.values()) {
            List<Allocation> allocations=allocationsByCommitment.getOrDefault(c.id(),List.of());
            Map<String,Object> details=new LinkedHashMap<>();details.put("commitmentId",c.businessId());details.put("siteId",c.siteId());
            details.put("productCode",c.productCode());details.put("unitOfMeasure",c.unitOfMeasure());
            details.put("periodStart",c.periodStart());details.put("periodEnd",c.periodEnd());
            details.put("committedQuantity",c.committedQuantity());
            boolean eligible=commitmentRemaining.containsKey(c.id());
            BigDecimal remaining=eligible?commitmentRemaining.get(c.id()):BigDecimal.ZERO;
            details.put("eligible",eligible);details.put("exclusionReason",commitmentFailures.get(c.id()));
            details.put("allocatedFulfilledQuantity",eligible&&c.committedQuantity()!=null?c.committedQuantity().subtract(remaining):BigDecimal.ZERO);
            details.put("remainingCommitmentQuantity",remaining);
            details.put("allocations",allocations.stream().map(a->allocationDetails(a,asOf)).toList());
            String exclusion=commitmentFailures.get(c.id());
            evidence.add(new Evidence("CAPACITY_COMMITMENT",c.id(),c.businessId(),exclusion==null,null,exclusion,details));
        }
        for(ReceiptLine line:uniqueReceipts) {
            List<Allocation> allocations=allocationsByReceipt.getOrDefault(line.id(),List.of());
            BigDecimal allocated=allocations.stream().map(Allocation::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            String exclusion=receiptFailures.get(line.id());
            if(exclusion==null&&allocated.signum()==0)exclusion=unallocatedReasons.getOrDefault(line.id(),excessByReceipt.getOrDefault(line.id(),BigDecimal.ZERO).signum()>0?"EXCEEDS_SHIPPED_QUANTITY":"NO_MATCHING_COMMITMENT");
            Map<String,Object> details=receiptDetails(line);details.put("originalReceiptQuantity",line.receivedQuantity());
            details.put("eligibleReceivedQuantity",validByReceipt.getOrDefault(line.id(),BigDecimal.ZERO));
            details.put("allocatedQuantity",allocated);details.put("excessReceiptQuantity",excessByReceipt.getOrDefault(line.id(),BigDecimal.ZERO));
            if(excessByReceipt.getOrDefault(line.id(),BigDecimal.ZERO).signum()>0)details.put("excessQuantityReason","CUMULATIVE_RECEIPTS_EXCEED_SHIPPED_QUANTITY");
            details.put("unallocatedQuantity",validByReceipt.getOrDefault(line.id(),BigDecimal.ZERO).subtract(allocated));
            details.put("unallocatedReason",unallocatedReasons.get(line.id()));
            details.put("evaluationTimestamp",asOf);details.put("allocations",allocations.stream().map(a->allocationDetails(a,asOf)).toList());
            boolean included=receiptFailures.get(line.id())==null&&allocated.signum()>0;
            evidence.add(new Evidence("RECEIPT_LINE",line.id(),line.receiptId()+":LINE:"+line.id(),included,
                    included?allocated:null,included?null:Objects.requireNonNullElse(exclusion,"NO_MATCHING_COMMITMENT"),details));
        }
        long excludedCommitments=commitmentFailures.size();
        long eligibleReceiptLines=validByReceipt.size();
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("aggregationPolicy","SINGLE_ALLOCATION_SITE_SPECIFIC_THEN_EARLIEST_COMMITMENT_PERIOD");
        metrics.put("totalCommittedQuantity",totalCommitted);metrics.put("totalAllocatedFulfilledQuantity",totalAllocated);
        metrics.put("remainingQuantity",totalRemaining);metrics.put("utilizationPercentage",denominator.signum()==0?null:totalAllocated.multiply(HUNDRED).divide(denominator,SCALE,RoundingMode.HALF_UP).min(HUNDRED).setScale(2,RoundingMode.HALF_UP));
        metrics.put("unallocatedReceiptQuantity",unallocated);metrics.put("excessReceiptQuantity",receiptExcess);
        metrics.put("allocationCount",allocationsByReceipt.values().stream().mapToLong(List::size).sum());
        metrics.put("duplicateReceiptLineCount",duplicateRows);metrics.put("excludedReceiptLineCount",receiptFailures.size());
        metrics.put("excludedReceiptQuantity",uniqueReceipts.stream().filter(r->receiptFailures.containsKey(r.id())).map(ReceiptLine::receivedQuantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add));
        metrics.put("eligibleReceiptLineCount",eligibleReceiptLines);metrics.put("eligibleCommitmentCount",eligibleCommitments.size());
        metrics.put("excludedCommitmentCount",excludedCommitments);metrics.put("exclusionReasons",exclusionCounts(commitmentFailures,receiptFailures,unallocatedReasons));
        metrics.put("excludedAfterCutoffCommitmentCount",commitmentFailures.values().stream().filter(reason->reason.contains("AFTER_CUTOFF")).count());
        metrics.put("excludedInvalidCommitmentCount",invalidCommitments);
        return new Result(score,totalAllocated,denominator,sourceCommitments.size(),eligibleCommitments.size(),excludedCommitments,status,missingReason,metrics,List.copyOf(evidence));
    }

    private String validateCommitment(Commitment c,long supplier,LocalDate start,Instant asOf,LocalDate asOfDate) {
        if(c.createdAt()==null)return "COMMITMENT_CREATED_AT_MISSING";
        if(c.createdAt().isAfter(asOf))return "COMMITMENT_CREATED_AFTER_CUTOFF";
        if(c.recordedAt()==null)return "COMMITMENT_RECORDED_AT_MISSING";
        if(c.recordedAt().isAfter(asOf))return "COMMITMENT_RECORDED_AFTER_CUTOFF";
        if(c.supplierId()!=supplier)return "SUPPLIER_MISMATCH";
        if(c.siteId()!=null&&!Objects.equals(c.siteOwnerSupplierId(),c.supplierId()))return "COMMITMENT_SITE_OWNERSHIP_MISMATCH";
        if(c.productCode()==null||c.productCode().isBlank())return "INVALID_COMMITMENT_PRODUCT";
        if(c.unitOfMeasure()==null||c.unitOfMeasure().isBlank())return "INVALID_COMMITMENT_UOM";
        if(c.periodStart()==null||c.periodEnd()==null||c.periodEnd().isBefore(c.periodStart()))return "INVALID_COMMITMENT_PERIOD";
        if(c.committedQuantity()==null||c.committedQuantity().signum()<=0)return "INVALID_COMMITTED_QUANTITY";
        if(c.periodEnd().isBefore(start)||c.periodStart().isAfter(asOfDate))return "OUTSIDE_EVALUATION_WINDOW";
        return null;
    }

    private String validateReceipt(ReceiptLine r,long supplier,LocalDate windowStart,Instant asOf) {
        if(r.shipmentId()<=0||r.resolvedShipmentLineId()==null)return "MISSING_SHIPMENT_LINE";
        if(r.receiptShipmentId()!=r.shipmentId()||r.receiptLineShipmentId()!=r.shipmentId())return "SHIPMENT_MISMATCH";
        if(!Objects.equals(r.shipmentLineShipmentId(),r.shipmentId()))return "SHIPMENT_LINE_MISMATCH";
        if(!Objects.equals(r.shipmentLinePurchaseOrderId(),r.poLinePurchaseOrderId())||!Objects.equals(r.shipmentLinePurchaseOrderId(),r.shipmentPurchaseOrderId()))return "PURCHASE_ORDER_LINE_MISMATCH";
        if(r.poLineId()==null||r.purchaseOrderId()==null)return "MISSING_PO_LINE";
        if(r.receivedAt()==null)return "RECEIPT_TIMESTAMP_MISSING";
        if(r.receivedAt().isAfter(asOf))return "RECEIPT_TIMESTAMP_AFTER_CUTOFF";
        if(r.receiptCreatedAt()==null)return "RECEIPT_CREATED_AT_MISSING";
        if(r.receiptCreatedAt().isAfter(asOf))return "RECEIPT_CREATED_AFTER_CUTOFF";
        if(r.receiptLineCreatedAt()==null)return "RECEIPT_LINE_CREATED_AT_MISSING";
        if(r.receiptLineCreatedAt().isAfter(asOf))return "RECEIPT_LINE_CREATED_AFTER_CUTOFF";
        if(r.shipmentCreatedAt()==null)return "SHIPMENT_CREATED_AT_MISSING";
        if(r.shipmentCreatedAt().isAfter(asOf))return "SHIPMENT_CREATED_AFTER_CUTOFF";
        if(r.shipmentLineCreatedAt()==null)return "SHIPMENT_LINE_CREATED_AT_MISSING";
        if(r.shipmentLineCreatedAt().isAfter(asOf))return "SHIPMENT_LINE_CREATED_AFTER_CUTOFF";
        if(r.purchaseOrderCreatedAt()==null)return "PURCHASE_ORDER_CREATED_AT_MISSING";
        if(r.purchaseOrderCreatedAt().isAfter(asOf))return "PURCHASE_ORDER_CREATED_AFTER_CUTOFF";
        if(r.poLineCreatedAt()==null)return "PO_LINE_CREATED_AT_MISSING";
        if(r.poLineCreatedAt().isAfter(asOf))return "PO_LINE_CREATED_AFTER_CUTOFF";
        if(r.receivedAt().isBefore(windowStart.atStartOfDay(ZoneOffset.UTC).toInstant()))return "OUTSIDE_EVALUATION_WINDOW";
        if(r.receiptSupplierId()!=supplier||r.shipmentSupplierId()!=supplier||r.poSupplierId()!=supplier)return "SUPPLIER_MISMATCH";
        if(!Objects.equals(r.receiptSiteId(),r.shipmentSiteId())||!Objects.equals(r.receiptSiteId(),r.poSiteId()))return "SITE_MISMATCH";
        if(r.productCode()==null||r.productCode().isBlank())return "INVALID_PRODUCT";
        if(r.unitOfMeasure()==null||r.unitOfMeasure().isBlank())return "INVALID_UOM";
        if(r.shippedQuantity()==null||r.shippedQuantity().signum()<=0||r.receivedQuantity()==null||r.receivedQuantity().signum()<=0)return "INVALID_RECEIPT_QUANTITY";
        return null;
    }

    private boolean matches(Commitment c,ReceiptLine r) {
        return c.supplierId()==r.receiptSupplierId()&&(c.siteId()==null||Objects.equals(c.siteId(),r.receiptSiteId()))
                &&Objects.equals(c.productCode(),r.productCode())&&Objects.equals(c.unitOfMeasure(),r.unitOfMeasure())
                &&!r.receivedAt().isBefore(c.periodStart().atStartOfDay(ZoneOffset.UTC).toInstant())
                &&r.receivedAt().isBefore(c.periodEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
    private Map<String,Object> allocationDetails(Allocation a,Instant asOf) {
        Commitment c=a.commitment();ReceiptLine r=a.receipt();Map<String,Object> d=new LinkedHashMap<>();
        d.put("capacityCommitmentId",c.businessId());d.put("receiptId",r.receiptId());d.put("receiptLineId",r.id());
        d.put("shipmentId",r.shipmentId());d.put("shipmentLineId",r.resolvedShipmentLineId());d.put("purchaseOrderId",r.purchaseOrderId());d.put("poLineId",r.poLineId());
        d.put("supplierId",r.receiptSupplierId());d.put("siteId",r.receiptSiteId());d.put("productCode",r.productCode());d.put("unitOfMeasure",r.unitOfMeasure());
        d.put("receiptTimestamp",r.receivedAt());d.put("originalReceiptQuantity",r.receivedQuantity());d.put("allocatedQuantity",a.quantity());
        d.put("remainingCommitmentQuantity",a.remainingAfter());d.put("evaluationTimestamp",asOf);return d;
    }
    private String unmatchedReason(List<Commitment> commitments,ReceiptLine r) {
        List<Commitment> supplier=commitments.stream().filter(c->c.supplierId()==r.receiptSupplierId()).toList();
        if(supplier.isEmpty())return "NO_MATCHING_SUPPLIER_COMMITMENT";
        List<Commitment> site=supplier.stream().filter(c->c.siteId()==null||Objects.equals(c.siteId(),r.receiptSiteId())).toList();
        if(site.isEmpty())return "SITE_MISMATCH";
        List<Commitment> product=site.stream().filter(c->Objects.equals(c.productCode(),r.productCode())).toList();
        if(product.isEmpty())return "PRODUCT_MISMATCH";
        List<Commitment> uom=product.stream().filter(c->Objects.equals(c.unitOfMeasure(),r.unitOfMeasure())).toList();
        if(uom.isEmpty())return "UOM_MISMATCH";
        return "OUTSIDE_COMMITMENT_PERIOD";
    }
    private Map<String,Object> receiptDetails(ReceiptLine r) {
        Map<String,Object> d=new LinkedHashMap<>();d.put("receiptId",r.receiptId());d.put("shipmentId",r.shipmentId());
        d.put("shipmentLineId",r.resolvedShipmentLineId());d.put("purchaseOrderId",r.purchaseOrderId());d.put("poLineId",r.poLineId());
        d.put("supplierId",r.receiptSupplierId());d.put("siteId",r.receiptSiteId());d.put("productCode",r.productCode());d.put("unitOfMeasure",r.unitOfMeasure());
        d.put("receivedAt",r.receivedAt());return d;
    }
    private Map<String,Long> exclusionCounts(Map<Long,String> commitmentFailures,Map<Long,String> receiptFailures,Map<Long,String> unallocated) {
        Map<String,Long> counts=new TreeMap<>();
        commitmentFailures.values().forEach(reason->counts.merge(reason,1L,Long::sum));
        receiptFailures.values().forEach(reason->counts.merge(reason,1L,Long::sum));
        unallocated.values().forEach(reason->counts.merge(reason,1L,Long::sum));return counts;
    }
}
