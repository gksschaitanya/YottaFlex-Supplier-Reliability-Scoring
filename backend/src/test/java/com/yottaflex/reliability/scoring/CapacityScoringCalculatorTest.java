package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.CapacityScoringCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityScoringCalculatorTest {
    private final CapacityScoringCalculator calculator=new CapacityScoringCalculator();
    private final Instant asOf=Instant.parse("2026-07-02T12:00:00Z");
    private final LocalDate start=LocalDate.of(2025,7,1);
    private final LocalDate periodStart=LocalDate.of(2026,6,1);
    private final LocalDate periodEnd=LocalDate.of(2026,6,30);

    @Test void oneCommitmentWithFullFulfilmentScoresOneHundred(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,100,"100",at("2026-06-15T10:00:00Z"))));
        assertThat(result.score()).isEqualByComparingTo("100.00");
        assertThat(result.numerator()).isEqualByComparingTo("100");
        assertThat(result.denominator()).isEqualByComparingTo("100");
    }
    @Test void oneCommitmentWithPartialFulfilmentScoresProportionally(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,100,"30",at("2026-06-15T10:00:00Z"))));
        assertThat(result.score()).isEqualByComparingTo("30.00");
        assertThat(result.metrics()).containsEntry("remainingQuantity",new BigDecimal("70"));
    }
    @Test void oneCommitmentWithNoFulfilmentScoresZero(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of());
        assertThat(result.score()).isEqualByComparingTo("0.00");
        assertThat(result.metrics()).containsEntry("totalAllocatedFulfilledQuantity",BigDecimal.ZERO);
    }
    @Test void minimumOfThreeEligibleCommitmentsIsEnforced(){
        var result=calculator.calculate(1,List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(),start,asOf,3);
        assertThat(result.status()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(result.eligibleObservations()).isEqualTo(1);
    }
    @Test void receiptQuantityAboveCommitmentIsNotCreditedBeyondCommitment(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,200,"150",at("2026-06-15T10:00:00Z"))));
        assertThat(result.numerator()).isEqualByComparingTo("100");
        assertThat(result.metrics()).containsEntry("unallocatedReceiptQuantity",new BigDecimal("50"));
    }
    @Test void twoOverlappingSameSiteCommitmentsCannotReuseAReceipt(){
        var commitments=List.of(commit(1,"CAP-A",10L,"A","EA","100"),commit(2,"CAP-B",10L,"A","EA","100"));
        var result=calculate(commitments,List.of(receipt(1,"R-1",100,100,"100",at("2026-06-15T10:00:00Z"))));
        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(result.metrics()).containsEntry("allocationCount",1L);
        assertThat(result.numerator()).isEqualByComparingTo("100");
    }
    @Test void siteSpecificCommitmentPrecedesSupplierWideCommitment(){
        var commitments=List.of(commit(1,"CAP-WIDE",null,"A","EA","100"),commit(2,"CAP-SITE",10L,"A","EA","100"));
        var result=calculate(commitments,List.of(receipt(1,"R-1",100,100,"100",at("2026-06-15T10:00:00Z"))));
        assertThat(allocationFor(result,"CAP-SITE")).isEqualByComparingTo("100");
        assertThat(allocationFor(result,"CAP-WIDE")).isEqualByComparingTo("0");
    }
    @Test void receiptsAtDifferentSitesMatchOnlyTheirOwnSiteCommitments(){
        var commitments=List.of(commit(1,"CAP-10",10L,"A","EA","100"),commit(2,"CAP-20",20L,"A","EA","100"));
        var r1=receipt(1,"R-1",100,100,"70",at("2026-06-15T10:00:00Z"));
        var r2=mutate(receipt(2,"R-2",200,100,"30",at("2026-06-16T10:00:00Z")),m->m.sites(20L,20L,20L));
        var result=calculate(commitments,List.of(r1,r2));
        assertThat(result.numerator()).isEqualByComparingTo("100");
        assertThat(result.denominator()).isEqualByComparingTo("200");
    }
    @Test void differentProductIsNotAllocated(){
        var r=mutate(receipt(1,"R-1",100,100,"20",at("2026-06-15T10:00:00Z")),m->m.product("B"));
        assertUnallocatedReason(commit(1,"CAP-1",10L,"A","EA","100"),r,"PRODUCT_MISMATCH");
    }
    @Test void differentUomIsNotAllocated(){
        var r=mutate(receipt(1,"R-1",100,100,"20",at("2026-06-15T10:00:00Z")),m->m.unit("KG"));
        assertUnallocatedReason(commit(1,"CAP-1",10L,"A","EA","100"),r,"UOM_MISMATCH");
    }
    @Test void duplicateReceiptLineIdentifierIsDeduplicated(){
        var r=receipt(1,"R-1",100,200,"100",at("2026-06-15T10:00:00Z"));
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","200")),List.of(r,r));
        assertThat(result.numerator()).isEqualByComparingTo("100");
        assertThat(result.metrics()).containsEntry("duplicateReceiptLineCount",1L);
    }
    @Test void multipleReceiptsAgainstOneShipmentLineAreCappedAtShippedQuantity(){
        var r1=receipt(1,"R-1",100,100,"60",at("2026-06-15T10:00:00Z"));
        var r2=receipt(2,"R-2",100,100,"60",at("2026-06-16T10:00:00Z"));
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","200")),List.of(r1,r2));
        assertThat(result.numerator()).isEqualByComparingTo("100");
        assertThat(result.metrics()).containsEntry("excessReceiptQuantity",new BigDecimal("20"));
    }
    @Test void receiptCanBeSplitAcrossCommitmentsWithoutReuse(){
        var commitments=List.of(commit(1,"CAP-A",10L,"A","EA","20"),commit(2,"CAP-B",10L,"A","EA","20"));
        var result=calculate(commitments,List.of(receipt(1,"R-1",100,100,"30",at("2026-06-15T10:00:00Z"))));
        assertThat(result.metrics()).containsEntry("allocationCount",2L);
        assertThat(result.numerator()).isEqualByComparingTo("30");
        assertThat(result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")&&e.included()).map(CapacityScoringCalculator.Evidence::contribution).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo(result.numerator());
    }
    @Test void commitmentWithoutMatchingReceiptRemainsInDenominator(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of());
        assertThat(result.denominator()).isEqualByComparingTo("100");
        assertThat(result.score()).isEqualByComparingTo("0.00");
    }
    @Test void receiptOutsideCommitmentPeriodRemainsUnallocated(){
        var c=commit(1,"CAP-1",10L,"A","EA","100");
        c=mutate(c,m->m.period(periodStart,LocalDate.of(2026,6,10)));
        var r=receipt(1,"R-1",100,100,"20",at("2026-06-15T10:00:00Z"));
        assertUnallocatedReason(c,r,"OUTSIDE_COMMITMENT_PERIOD");
    }
    @Test void receiptBeforeScoringWindowIsExcludedEvenWhenInsideCommitmentPeriod(){
        var c=commit(1,"CAP-1",10L,"A","EA","100");
        var old=receipt(1,"R-1",100,100,"20",at("2025-06-30T23:59:59Z"));
        assertReceiptExcluded(c,old,"OUTSIDE_EVALUATION_WINDOW");
    }
    @Test void receiptAtPeriodStartIsIncluded(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,100,"10",at("2026-06-01T00:00:00Z"))));
        assertThat(result.numerator()).isEqualByComparingTo("10");
    }
    @Test void receiptImmediatelyBeforeEndOfPeriodIsIncluded(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,100,"10",at("2026-06-30T23:59:59.999999Z"))));
        assertThat(result.numerator()).isEqualByComparingTo("10");
    }
    @Test void receiptAtMidnightAfterPeriodEndIsExcluded(){
        var result=calculate(List.of(commit(1,"CAP-1",10L,"A","EA","100")),List.of(receipt(1,"R-1",100,100,"10",at("2026-07-01T00:00:00Z"))));
        assertThat(result.numerator()).isEqualByComparingTo("0");
        assertThat(result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")).findFirst().orElseThrow().exclusionReason()).isEqualTo("OUTSIDE_COMMITMENT_PERIOD");
    }
    @Test void receiptsAndRelatedRecordsAfterCutoffAreExcluded(){
        var c=commit(1,"CAP-1",10L,"A","EA","100");
        var r=receipt(1,"R-1",100,100,"10",at("2026-06-15T10:00:00Z"));
        assertReceiptExcluded(c,mutate(r,m->m.receiptCreated(asOf.plusNanos(1))),"RECEIPT_CREATED_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.receivedAt(asOf.plusNanos(1))),"RECEIPT_TIMESTAMP_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.receiptLineCreated(asOf.plusNanos(1))),"RECEIPT_LINE_CREATED_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.shipmentCreated(asOf.plusNanos(1))),"SHIPMENT_CREATED_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.shipmentLineCreated(asOf.plusNanos(1))),"SHIPMENT_LINE_CREATED_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.poCreated(asOf.plusNanos(1))),"PURCHASE_ORDER_CREATED_AFTER_CUTOFF");
        assertReceiptExcluded(c,mutate(r,m->m.poLineCreated(asOf.plusNanos(1))),"PO_LINE_CREATED_AFTER_CUTOFF");
    }
    @Test void commitmentCreationAndRecordedCutoffsAreEnforced(){
        var c=mutate(commit(1,"CAP-1",10L,"A","EA","100"),m->m.created(asOf.plusNanos(1)));
        var result=calculate(List.of(c),List.of());
        assertThat(result.eligibleObservations()).isZero();
        assertThat(result.evidence().getFirst().exclusionReason()).isEqualTo("COMMITMENT_CREATED_AFTER_CUTOFF");
        c=mutate(commit(2,"CAP-2",10L,"A","EA","100"),m->m.recorded(asOf.plusNanos(1)));
        assertThat(calculate(List.of(c),List.of()).evidence().getFirst().exclusionReason()).isEqualTo("COMMITMENT_RECORDED_AFTER_CUTOFF");
    }
    @Test void supplierAndSiteOwnershipMismatchesAreExcluded(){
        var c=mutate(commit(1,"CAP-1",10L,"A","EA","100"),m->m.siteOwner(2L));
        var result=calculate(List.of(c),List.of());
        assertThat(result.evidence().getFirst().exclusionReason()).isEqualTo("COMMITMENT_SITE_OWNERSHIP_MISMATCH");
        var r=mutate(receipt(1,"R-1",100,100,"10",at("2026-06-15T10:00:00Z")),m->m.sites(20L,20L,20L));
        assertReceiptExcluded(commit(2,"CAP-2",10L,"A","EA","100"),r,"SITE_MISMATCH");
    }
    @Test void missingShipmentLineRelationshipIsExcludedExplicitly(){
        var receipt=mutate(receipt(1,"R-1",100,100,"10",at("2026-06-15T10:00:00Z")),m->m.shipmentLineId(null));
        assertReceiptExcluded(commit(1,"CAP-1",10L,"A","EA","100"),receipt,"MISSING_SHIPMENT_LINE");
    }
    @Test void evidenceReceiptAllocationsReconcileToCommitmentNumerator(){
        var commitments=List.of(commit(1,"CAP-A",10L,"A","EA","15"),commit(2,"CAP-B",10L,"A","EA","20"));
        var receipts=List.of(receipt(1,"R-1",100,100,"25",at("2026-06-15T10:00:00Z")));
        var result=calculate(commitments,receipts);
        BigDecimal receiptContribution=result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")&&e.included()).map(CapacityScoringCalculator.Evidence::contribution).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal receiptAllocation=result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")).map(e->(BigDecimal)e.details().get("allocatedQuantity")).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(receiptContribution).isEqualByComparingTo(result.numerator());
        assertThat(receiptAllocation).isEqualByComparingTo(result.numerator());
        var receiptEvidence=result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")).findFirst().orElseThrow();
        assertThat(receiptEvidence.details()).containsKeys("receiptId","shipmentId","shipmentLineId","purchaseOrderId","poLineId","supplierId","siteId","productCode","unitOfMeasure","evaluationTimestamp");
        assertThat((List<Map<String,Object>>)receiptEvidence.details().get("allocations")).allSatisfy(allocation ->
                assertThat(allocation).containsKeys("capacityCommitmentId","receiptId","receiptLineId","shipmentId","shipmentLineId","purchaseOrderId","poLineId","supplierId","siteId","productCode","unitOfMeasure","originalReceiptQuantity","allocatedQuantity","remainingCommitmentQuantity","evaluationTimestamp"));
        assertThat((List<Map<String,Object>>)receiptEvidence.details().get("allocations")).extracting(allocation->allocation.get("remainingCommitmentQuantity")).containsExactly(new BigDecimal("0"),new BigDecimal("10"));
    }
    @Test void addingDuplicateCommitmentCannotReuseReceiptQuantity(){
        var receipts=List.of(receipt(1,"R-1",100,100,"100",at("2026-06-15T10:00:00Z")));
        var one=calculate(List.of(commit(1,"CAP-A",10L,"A","EA","100")),receipts);
        var two=calculate(List.of(commit(1,"CAP-A",10L,"A","EA","100"),commit(2,"CAP-B",10L,"A","EA","100")),receipts);
        assertThat(one.numerator()).isEqualByComparingTo("100");
        assertThat(two.numerator()).isEqualByComparingTo("100");
        assertThat(two.score()).isEqualByComparingTo("50.00");
    }
    @Test void increasingValidAllocationCannotReduceScoreAndIncreasingCommitmentCannotImproveIt(){
        var c=commit(1,"CAP-A",10L,"A","EA","100");
        BigDecimal partial=calculate(List.of(c),List.of(receipt(1,"R-1",100,100,"20",at("2026-06-15T10:00:00Z")))).score();
        BigDecimal full=calculate(List.of(c),List.of(receipt(1,"R-1",100,100,"80",at("2026-06-15T10:00:00Z")))).score();
        BigDecimal largerCommit=calculate(List.of(commit(2,"CAP-B",10L,"A","EA","200")),List.of(receipt(2,"R-2",200,200,"80",at("2026-06-15T10:00:00Z")))).score();
        assertThat(full).isGreaterThan(partial);
        assertThat(largerCommit).isLessThan(full);
    }
    @Test void allocationIsDeterministic(){
        var commitments=List.of(commit(2,"CAP-B",10L,"A","EA","50"),commit(1,"CAP-A",10L,"A","EA","50"));
        var receipts=List.of(receipt(2,"R-2",100,100,"30",at("2026-06-16T10:00:00Z")),receipt(1,"R-1",100,100,"30",at("2026-06-15T10:00:00Z")));
        assertThat(calculate(commitments,receipts)).isEqualTo(calculate(List.of(commitments.get(1),commitments.get(0)),List.of(receipts.get(1),receipts.get(0))));
    }

    private CapacityScoringCalculator.Result calculate(List<CapacityScoringCalculator.Commitment> cs,List<CapacityScoringCalculator.ReceiptLine> rs){return calculator.calculate(1,cs,rs,start,asOf,1);}
    private void assertUnallocatedReason(CapacityScoringCalculator.Commitment c,CapacityScoringCalculator.ReceiptLine r,String reason){
        var result=calculate(List.of(c),List.of(r));var evidence=result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")).findFirst().orElseThrow();
        assertThat(evidence.included()).isFalse();assertThat(evidence.exclusionReason()).isEqualTo(reason);
    }
    private void assertReceiptExcluded(CapacityScoringCalculator.Commitment c,CapacityScoringCalculator.ReceiptLine r,String reason){
        var result=calculate(List.of(c),List.of(r));var evidence=result.evidence().stream().filter(e->e.sourceEntityType().equals("RECEIPT_LINE")).findFirst().orElseThrow();
        assertThat(evidence.included()).isFalse();assertThat(evidence.exclusionReason()).isEqualTo(reason);
    }
    private BigDecimal allocationFor(CapacityScoringCalculator.Result result,String commitmentId){return result.evidence().stream().filter(e->e.sourceEntityType().equals("CAPACITY_COMMITMENT")&&e.sourceBusinessId().equals(commitmentId)).map(e->(BigDecimal)e.details().get("allocatedFulfilledQuantity")).findFirst().orElse(BigDecimal.ZERO);}
    private CapacityScoringCalculator.Commitment commit(long id,String business,Long site,String product,String uom,String qty){return new CapacityScoringCalculator.Commitment(id,business,1,site,site==null?null:1L,product,uom,periodStart,periodEnd,new BigDecimal(qty),asOf.minus(Duration.ofDays(100)),asOf.minus(Duration.ofDays(100)));}
    private Instant at(String value){return Instant.parse(value);}
    private CapacityScoringCalculator.ReceiptLine receipt(long id,String receipt,long shipmentLine,long shipped,String qty,Instant received){
        long shipment=shipmentLine;return new CapacityScoringCalculator.ReceiptLine(id,receipt,1,10L,shipment,shipment,received,asOf.minus(Duration.ofDays(1)),
                shipment,1,10L,1000L,asOf.minus(Duration.ofDays(50)),shipmentLine,shipment,1000L,500L,new BigDecimal(Long.toString(shipped)),asOf.minus(Duration.ofDays(49)),
                "PO-1",1,10L,1000L,"A","EA",asOf.minus(Duration.ofDays(80)),asOf.minus(Duration.ofDays(79)),new BigDecimal(qty),asOf.minus(Duration.ofDays(1)));
    }
    private CapacityScoringCalculator.Commitment mutate(CapacityScoringCalculator.Commitment c,Consumer<MutableCommitment> edit){MutableCommitment m=new MutableCommitment(c);edit.accept(m);return m.build();}
    private CapacityScoringCalculator.ReceiptLine mutate(CapacityScoringCalculator.ReceiptLine r,Consumer<MutableReceipt> edit){MutableReceipt m=new MutableReceipt(r);edit.accept(m);return m.build();}
    private static final class MutableCommitment {
        long id,supplier;String business,product,uom;Long site,siteOwner;LocalDate from,to;BigDecimal qty;Instant created,recorded;
        MutableCommitment(CapacityScoringCalculator.Commitment c){id=c.id();business=c.businessId();supplier=c.supplierId();site=c.siteId();siteOwner=c.siteOwnerSupplierId();product=c.productCode();uom=c.unitOfMeasure();from=c.periodStart();to=c.periodEnd();qty=c.committedQuantity();created=c.createdAt();recorded=c.recordedAt();}
        void siteOwner(Long v){siteOwner=v;}void period(LocalDate a,LocalDate b){from=a;to=b;}void created(Instant v){created=v;}void recorded(Instant v){recorded=v;}
        CapacityScoringCalculator.Commitment build(){return new CapacityScoringCalculator.Commitment(id,business,supplier,site,siteOwner,product,uom,from,to,qty,created,recorded);}
    }
    private static final class MutableReceipt {
        long id,receiptSupplier,receiptShipment,receiptLineShipment,shipment,shipmentSupplier,poSupplier;String receiptId,poId,product,uom;
        Long receiptSite,shipmentSite,shipmentPo,shipmentLineId,shipmentLineShipment,shipmentLinePo,poSite,poLinePo,poLineId;
        BigDecimal shipped,received;Instant receivedAt,receiptCreated,shipmentCreated,shipmentLineCreated,poCreated,poLineCreated,lineCreated;
        MutableReceipt(CapacityScoringCalculator.ReceiptLine r){id=r.id();receiptId=r.receiptId();receiptSupplier=r.receiptSupplierId();receiptSite=r.receiptSiteId();receiptShipment=r.receiptShipmentId();receiptLineShipment=r.receiptLineShipmentId();receivedAt=r.receivedAt();receiptCreated=r.receiptCreatedAt();shipment=r.shipmentId();shipmentSupplier=r.shipmentSupplierId();shipmentSite=r.shipmentSiteId();shipmentPo=r.shipmentPurchaseOrderId();shipmentCreated=r.shipmentCreatedAt();shipmentLineId=r.resolvedShipmentLineId();shipmentLineShipment=r.shipmentLineShipmentId();shipmentLinePo=r.shipmentLinePurchaseOrderId();poLineId=r.poLineId();shipped=r.shippedQuantity();shipmentLineCreated=r.shipmentLineCreatedAt();poId=r.purchaseOrderId();poSupplier=r.poSupplierId();poSite=r.poSiteId();poLinePo=r.poLinePurchaseOrderId();product=r.productCode();uom=r.unitOfMeasure();poCreated=r.purchaseOrderCreatedAt();poLineCreated=r.poLineCreatedAt();received=r.receivedQuantity();lineCreated=r.receiptLineCreatedAt();}
        void sites(Long receiptValue,Long shipmentValue,Long poValue){receiptSite=receiptValue;shipmentSite=shipmentValue;poSite=poValue;}
        void shipmentLineId(Long value){shipmentLineId=value;}
        void product(String v){product=v;}void unit(String v){uom=v;}void receivedAt(Instant v){receivedAt=v;}void receiptCreated(Instant v){receiptCreated=v;}void receiptLineCreated(Instant v){lineCreated=v;}void shipmentCreated(Instant v){shipmentCreated=v;}void shipmentLineCreated(Instant v){shipmentLineCreated=v;}void poCreated(Instant v){poCreated=v;}void poLineCreated(Instant v){poLineCreated=v;}
        CapacityScoringCalculator.ReceiptLine build(){return new CapacityScoringCalculator.ReceiptLine(id,receiptId,receiptSupplier,receiptSite,receiptShipment,receiptLineShipment,receivedAt,receiptCreated,shipment,shipmentSupplier,shipmentSite,shipmentPo,shipmentCreated,shipmentLineId,shipmentLineShipment,shipmentLinePo,poLineId,shipped,shipmentLineCreated,poId,poSupplier,poSite,poLinePo,product,uom,poCreated,poLineCreated,received,lineCreated);}
    }
}
