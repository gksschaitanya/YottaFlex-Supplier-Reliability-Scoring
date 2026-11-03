package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.CostScoringCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostScoringCalculatorTest {
    private final CostScoringCalculator calculator=new CostScoringCalculator();
    private final LocalDateTime cutoff=LocalDateTime.parse("2026-11-02T12:00:00");
    private final LocalDate day=LocalDate.parse("2026-11-01");
    private final LocalDate start=day.minusDays(365);

    @Test void varianceBelowAndExactlyTwoPercentScoreOneHundred(){
        assertScore(line(1,1,"I1",null,"1","101.99","101.99","101.99"),"100.00");
        assertScore(line(1,1,"I1",null,"1","102","102","102"),"100.00");
    }
    @Test void varianceBetweenTwoAndTwentyDeclinesLinearly(){assertScore(line(1,1,"I1",null,"1","111","111","111"),"50.00");}
    @Test void varianceExactlyAndAboveTwentyScoresZero(){
        assertScore(line(1,1,"I1",null,"1","120","120","120"),"0.00");
        assertScore(line(1,1,"I1",null,"1","125","125","125"),"0.00");
    }
    @Test void cheaperPriceUsesAbsoluteVariance(){assertScore(line(1,1,"I1",null,"1","90","90","90"),"55.56");}

    @Test void aggregatesSeveralInvoiceLinesForOnePoLineAndWeightsInsideThatLineByQuantity(){
        var a=line(1,8,"I1","REF-1","2","100","200","200");
        var b=line(2,8,"I2","REF-2","2","120","240","240");
        var result=calc(a,b);
        assertThat(result.eligibleObservations()).isEqualTo(1);
        assertThat(result.denominator()).isEqualByComparingTo("1");
        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(sumContributions(result)).isEqualByComparingTo(result.numerator());
    }
    @Test void partialInvoicingScoresTheObservedPoLineOnce(){
        var a=line(1,8,"I1","REF-1","3","100","300","300");
        assertThat(calc(a).eligibleObservations()).isEqualTo(1);
        assertThat(calc(a).score()).isEqualByComparingTo("100.00");
    }
    @Test void repeatedExternalInvoiceReferenceIsExcludedAsDuplicate(){
        var a=line(1,8,"I1","REF-DUP","5","100","500","500");
        var b=line(2,8,"I2","REF-DUP","5","100","500","500");
        var result=calc(a,b);
        assertThat(result.eligibleObservations()).isZero();
        assertThat(result.evidence()).allSatisfy(e->assertThat(e.exclusionReason()).isEqualTo("DUPLICATE_INVOICE"));
    }
    @Test void excessQuantityIsCappedAtOrderedQuantity(){
        var a=line(1,8,"I1","REF-1","12","110","1320","1320");
        var result=calc(a);
        assertThat(result.eligibleObservations()).isEqualTo(1);
        assertThat(result.evidence().getFirst().details()).containsEntry("eligibleInvoicedQuantity",new BigDecimal("10"));
        assertThat(result.evidence().getFirst().details()).containsEntry("excludedOverInvoicedQuantity",new BigDecimal("2"));
    }
    @Test void currencyMismatchIsExcluded(){assertExcluded(line(1,1,"I1",null,"1","100","100","100","EUR","USD","MATCHED"),"CURRENCY_MISMATCH");}
    @Test void changingAZeroScoreLineToAnIncomparableCurrencyDoesNotCreateAHigherScore(){
        var comparable=line(1,1,"I1",null,"1","120","120","120");
        var incomparable=copyCurrency(comparable,"EUR");
        assertThat(calc(comparable).score()).isEqualByComparingTo("0.00");
        assertThat(calc(incomparable).score()).isNull();
    }
    @Test void voidedAndUnsupportedStatusesAreExcluded(){
        assertExcluded(line(1,1,"I1",null,"1","100","100","100","USD","USD","VOIDED"),"VOIDED_INVOICE");
        assertExcluded(line(1,1,"I1",null,"1","100","100","100","USD","USD","RECEIVED"),"INVOICE_NOT_APPROVED");
    }
    @Test void supplierAndPurchaseOrderMismatchAreExcluded(){
        var supplier=line(1,1,"I1",null,"1","100","100","100");
        assertExcluded(copy(supplier,11,9,9),"SUPPLIER_MISMATCH");
        assertExcluded(copy(supplier,10,88,9),"PURCHASE_ORDER_MISMATCH");
    }
    @Test void invalidPoPriceAndLineReconciliationAreExcluded(){
        assertExcluded(copyPrice(line(1,1,"I1",null,"1","100","100","100"),"0"),"NONPOSITIVE_PO_UNIT_PRICE");
        assertExcluded(copyAmount(line(1,1,"I1",null,"1","100","100","100"),"99"),"INVOICE_LINE_AMOUNT_MISMATCH");
        assertExcluded(copyAmount(line(2,2,"I2",null,"1","100","100","100"),"-1"),"INVALID_INVOICE_LINE_VALUES");
    }
    @Test void invoiceSubtotalAndHeaderTotalReconciliationFailuresAreExcluded(){
        var line=line(1,1,"I1",null,"1","100","100","90");
        assertExcluded(line,"INVOICE_SUBTOTAL_MISMATCH");
        var header=withTotal(line(2,2,"I2",null,"1","100","100","100"),"200");
        assertExcluded(header,"INVOICE_HEADER_TOTAL_MISMATCH");
    }
    @Test void invoiceInvoiceLinePoAndPoLineCreatedAfterCutoffAreExcluded(){
        var base=line(1,1,"I1",null,"1","100","100","100");
        assertExcluded(copyTimes(base,cutoff.plusNanos(1),base.invoiceLineCreatedAt(),base.poCreatedAt(),base.poLineCreatedAt(),base.invoiceUpdatedAt()),"INVOICE_CREATED_AFTER_CUTOFF");
        assertExcluded(copyTimes(base,base.invoiceCreatedAt(),cutoff.plusNanos(1),base.poCreatedAt(),base.poLineCreatedAt(),base.invoiceUpdatedAt()),"INVOICE_LINE_CREATED_AFTER_CUTOFF");
        assertExcluded(copyTimes(base,base.invoiceCreatedAt(),base.invoiceLineCreatedAt(),cutoff.plusNanos(1),base.poLineCreatedAt(),base.invoiceUpdatedAt()),"PO_CREATED_AFTER_CUTOFF");
        assertExcluded(copyTimes(base,base.invoiceCreatedAt(),base.invoiceLineCreatedAt(),base.poCreatedAt(),cutoff.plusNanos(1),base.invoiceUpdatedAt()),"PO_LINE_CREATED_AFTER_CUTOFF");
    }
    @Test void postCutoffStatusUpdateIsNotTreatedAsHistoricalStatus(){
        var base=line(1,1,"I1",null,"1","100","100","100");
        assertExcluded(copyTimes(base,base.invoiceCreatedAt(),base.invoiceLineCreatedAt(),base.poCreatedAt(),base.poLineCreatedAt(),cutoff.plusNanos(1)),"INVOICE_STATUS_UNKNOWN_AT_CUTOFF");
    }
    @Test void exclusionCountsAreAtPoLineLevelAndEvidenceContributionMatchesNumerator(){
        var valid=line(1,1,"I1",null,"1","100","100","100");
        var bad=copyCurrency(line(2,2,"I2",null,"1","100","100","100"),"EUR");
        var result=calc(valid,bad);
        assertThat(result.observations()).isEqualTo(2);
        assertThat(result.eligibleObservations()).isEqualTo(1);
        assertThat(result.excludedObservations()).isEqualTo(1);
        assertThat(sumContributions(result)).isEqualByComparingTo(result.numerator());
    }
    @Test void equalWeightAcrossPoLinesDoesNotCombineIncompatiblePhysicalQuantities(){
        var tenUnits=line(1,1,"I1",null,"10","100","1000","1000");
        var oneKilogram=copyUom(line(2,2,"I2",null,"1","110","110","110"),"KG");
        var result=calc(tenUnits,oneKilogram);
        assertThat(result.denominator()).isEqualByComparingTo("2");
        assertThat(result.score()).isEqualByComparingTo("77.78");
    }
    @Test void worseningVarianceCannotImproveScore(){
        BigDecimal close=calc(line(1,1,"I1",null,"1","103","103","103")).score();
        BigDecimal worse=calc(line(1,1,"I1",null,"1","115","115","115")).score();
        assertThat(worse).isLessThan(close);
    }
    @Test void addingDuplicateExternalInvoiceCannotImproveScore(){
        var original=line(1,1,"I1","REF-X","5","110","550","550");
        var duplicate=line(2,1,"I2","REF-X","5","100","500","500");
        assertThat(calc(original,duplicate).score()).isNull();
    }
    @Test void invalidLineAmountCannotSilentlyIncreaseTheScore(){
        var scored=line(1,1,"I1",null,"1","120","120","120");
        var invalid=copyAmount(scored,"100");
        assertThat(calc(scored).score()).isEqualByComparingTo("0.00");
        assertThat(calc(invalid).score()).isNull();
    }

    private void assertScore(CostScoringCalculator.Line line,String expected){assertThat(calc(line).score()).isEqualByComparingTo(expected);}
    private void assertExcluded(CostScoringCalculator.Line line,String reason){
        var result=calc(line);assertThat(result.eligibleObservations()).isZero();
        assertThat(result.evidence()).singleElement().satisfies(e->assertThat(e.exclusionReason()).isEqualTo(reason));
    }
    private CostScoringCalculator.Result calc(CostScoringCalculator.Line... rows){return calculator.calculate(List.of(rows),start,day,cutoff,1);}
    private BigDecimal sumContributions(CostScoringCalculator.Result result){return result.evidence().stream().map(CostScoringCalculator.Evidence::contribution).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private CostScoringCalculator.Line line(long id,long poLine,String invoice,String source,String qty,String invoicePrice,String amount,String subtotal){
        return line(id,poLine,invoice,source,qty,invoicePrice,amount,subtotal,"USD","USD","APPROVED");
    }
    private CostScoringCalculator.Line line(long id,long poLine,String invoice,String source,String qty,String invoicePrice,String amount,String subtotal,String invoiceCurrency,String poCurrency,String status){
        BigDecimal quantity=new BigDecimal(qty), price=new BigDecimal(invoicePrice), lineAmount=new BigDecimal(amount);
        return new CostScoringCalculator.Line(id,invoice,source,9,10,day,invoiceCurrency,new BigDecimal(subtotal),BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal(subtotal),status,
                cutoff.minusDays(2),cutoff.minusDays(1),9,poLine,9,10,poCurrency,"P-1","EA",new BigDecimal("10"),new BigDecimal("100"),quantity,price,lineAmount,
                cutoff.minusDays(100),cutoff.minusDays(90),cutoff.minusDays(1));
    }
    private CostScoringCalculator.Line copy(CostScoringCalculator.Line l,long invoiceSupplier,long invoicePo,long linePo){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),invoicePo,invoiceSupplier,l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),linePo,l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
    private CostScoringCalculator.Line copyPrice(CostScoringCalculator.Line l,String price){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),new BigDecimal(price),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
    private CostScoringCalculator.Line copyAmount(CostScoringCalculator.Line l,String amount){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),new BigDecimal(amount),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
    private CostScoringCalculator.Line withTotal(CostScoringCalculator.Line l,String total){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),new BigDecimal(total),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
    private CostScoringCalculator.Line copyTimes(CostScoringCalculator.Line l,LocalDateTime invoiceCreated,LocalDateTime lineCreated,LocalDateTime poCreated,LocalDateTime poLineCreated,LocalDateTime updated){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),invoiceCreated,updated,l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),poCreated,poLineCreated,lineCreated);}
    private CostScoringCalculator.Line copyCurrency(CostScoringCalculator.Line l,String currency){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),currency,l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),l.unitOfMeasure(),l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
    private CostScoringCalculator.Line copyUom(CostScoringCalculator.Line l,String uom){return new CostScoringCalculator.Line(l.invoiceLineId(),l.invoiceId(),l.invoiceSourceReference(),l.invoicePurchaseOrderId(),l.invoiceSupplierId(),l.invoiceDate(),l.invoiceCurrency(),l.invoiceSubtotal(),l.invoiceTax(),l.invoiceFreight(),l.invoiceTotal(),l.invoiceStatus(),l.invoiceCreatedAt(),l.invoiceUpdatedAt(),l.linePurchaseOrderId(),l.poLineId(),l.poLinePurchaseOrderId(),l.poSupplierId(),l.poCurrency(),l.productCode(),uom,l.orderedQuantity(),l.poUnitPrice(),l.invoiceQuantity(),l.invoiceUnitPrice(),l.lineAmount(),l.poCreatedAt(),l.poLineCreatedAt(),l.invoiceLineCreatedAt());}
}
