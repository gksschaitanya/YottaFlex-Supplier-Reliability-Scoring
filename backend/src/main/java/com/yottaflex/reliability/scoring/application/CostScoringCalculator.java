package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Deterministic, database-independent cost calculation. All quantities are kept within one PO line/UOM. */
public final class CostScoringCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO_PERCENT = new BigDecimal("0.02");
    private static final BigDecimal EIGHTEEN_PERCENT = new BigDecimal("0.18");
    private static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.0001");
    private static final int CALCULATION_SCALE = 12;

    public record Line(long invoiceLineId, String invoiceId, String invoiceSourceReference,
                       long invoicePurchaseOrderId, long invoiceSupplierId, LocalDate invoiceDate,
                       String invoiceCurrency, BigDecimal invoiceSubtotal, BigDecimal invoiceTax,
                       BigDecimal invoiceFreight, BigDecimal invoiceTotal, String invoiceStatus,
                       LocalDateTime invoiceCreatedAt, LocalDateTime invoiceUpdatedAt,
                       long linePurchaseOrderId, long poLineId, long poLinePurchaseOrderId,
                       long poSupplierId, String poCurrency, String productCode, String unitOfMeasure,
                       BigDecimal orderedQuantity, BigDecimal poUnitPrice,
                       BigDecimal invoiceQuantity, BigDecimal invoiceUnitPrice, BigDecimal lineAmount,
                       LocalDateTime poCreatedAt, LocalDateTime poLineCreatedAt,
                       LocalDateTime invoiceLineCreatedAt) { }

    public record Evidence(long sourceRecordId, String sourceBusinessId, boolean included,
                           BigDecimal contribution, String exclusionReason, Map<String,Object> details) { }

    public record Result(BigDecimal score, BigDecimal numerator, BigDecimal denominator,
                         long observations, long eligibleObservations, long excludedObservations,
                         String status, String reason, Map<String,Object> metrics, List<Evidence> evidence) { }

    private record LineResult(Line line, boolean included, BigDecimal contribution,
                              String reason, Map<String,Object> details) { }

    public Result calculate(List<Line> source, LocalDate windowStart, LocalDate asOfDate,
                            LocalDateTime asOf, long minimumLines) {
        Map<String,List<Line>> invoices = new TreeMap<>();
        for (Line line : source) invoices.computeIfAbsent(line.invoiceId(), ignored -> new ArrayList<>()).add(line);
        Map<String,String> invoiceFailures = new HashMap<>();
        Map<Long,String> lineFailures = new HashMap<>();
        for (List<Line> rows : invoices.values()) {
            Line h = rows.getFirst();
            if(rows.stream().anyMatch(row->row.invoiceLineCreatedAt()==null||row.invoiceLineCreatedAt().isAfter(asOf)))
                invoiceFailures.put(h.invoiceId(),"INVOICE_CONTENT_UNKNOWN_AT_CUTOFF");
            for(Line row:rows) {
                if(row.invoiceQuantity()==null||row.invoiceQuantity().signum()<=0||row.invoiceUnitPrice()==null||row.invoiceUnitPrice().signum()<0
                        ||row.lineAmount()==null||row.lineAmount().signum()<0) {
                    lineFailures.put(row.invoiceLineId(),"INVALID_INVOICE_LINE_VALUES");
                } else if(row.invoiceUnitPrice().multiply(row.invoiceQuantity()).setScale(4,RoundingMode.HALF_UP)
                        .subtract(row.lineAmount()).abs().compareTo(MONEY_TOLERANCE)>0) {
                    lineFailures.put(row.invoiceLineId(),"INVOICE_LINE_AMOUNT_MISMATCH");
                }
            }
            BigDecimal lineTotal = rows.stream().map(Line::lineAmount).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            String failure = null;
            if (h.invoiceSubtotal() == null || h.invoiceTax() == null || h.invoiceTax().signum()<0
                    || h.invoiceFreight() == null || h.invoiceFreight().signum()<0
                    || h.invoiceTotal() == null || h.invoiceTotal().signum()<0 || h.invoiceSubtotal().signum()<0)
                failure = "INVALID_INVOICE_HEADER_AMOUNTS";
            else if (lineTotal.subtract(h.invoiceSubtotal()).abs().compareTo(MONEY_TOLERANCE) > 0)
                failure = "INVOICE_SUBTOTAL_MISMATCH";
            else if (h.invoiceTotal().subtract(h.invoiceSubtotal().add(h.invoiceTax()).add(h.invoiceFreight())).abs().compareTo(MONEY_TOLERANCE) > 0)
                failure = "INVOICE_HEADER_TOTAL_MISMATCH";
            if (failure != null) {
                invoiceFailures.putIfAbsent(h.invoiceId(), failure);
            }
        }

        List<LineResult> preliminary = new ArrayList<>();
        for (Line line : source) preliminary.add(validate(line, windowStart, asOfDate, asOf, invoiceFailures,lineFailures));

        // An external source reference is the only schema-supported duplicate-invoice identity.
        Map<String,Set<String>> invoiceIdsByReference = new HashMap<>();
        Map<String,List<Integer>> byReference = new HashMap<>();
        for (int i=0;i<preliminary.size();i++) {
            Line line=preliminary.get(i).line();
            if (line.invoiceSourceReference()!=null && !line.invoiceSourceReference().isBlank()) {
                byReference.computeIfAbsent(line.invoiceSourceReference(), ignored -> new ArrayList<>()).add(i);
                invoiceIdsByReference.computeIfAbsent(line.invoiceSourceReference(), ignored -> new HashSet<>()).add(line.invoiceId());
            }
        }
        for (Map.Entry<String,List<Integer>> entry : byReference.entrySet()) if (invoiceIdsByReference.get(entry.getKey()).size()>1) {
            for (int index : entry.getValue()) preliminary.set(index, exclude(preliminary.get(index), "DUPLICATE_INVOICE"));
        }

        Map<Long,List<Integer>> byPoLine = new TreeMap<>();
        for (int i=0;i<preliminary.size();i++) byPoLine.computeIfAbsent(preliminary.get(i).line().poLineId(), ignored -> new ArrayList<>()).add(i);
        BigDecimal numerator=BigDecimal.ZERO; long eligible=0;
        List<LineResult> finalRows=new ArrayList<>(preliminary);
        for (List<Integer> indices : byPoLine.values()) {
            indices.sort(Comparator.comparing((Integer i)->preliminary.get(i).line().invoiceDate(),Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(i->preliminary.get(i).line().invoiceId(),Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(i->preliminary.get(i).line().invoiceLineId()));
            BigDecimal remaining=preliminary.get(indices.getFirst()).line().orderedQuantity();
            BigDecimal scoredQty=BigDecimal.ZERO,weightedLineScores=BigDecimal.ZERO;
            List<Integer> accepted=new ArrayList<>();
            for (int index : indices) {
                LineResult row=preliminary.get(index);
                if (!row.included()) continue;
                BigDecimal acceptedQty=row.line().invoiceQuantity().min(remaining.max(BigDecimal.ZERO));
                if (acceptedQty.signum()==0) {
                    finalRows.set(index,exclude(row,"OVER_INVOICED_QUANTITY"));
                    continue;
                }
                if (row.line().invoiceQuantity().compareTo(acceptedQty)>0) {
                    Map<String,Object> details=new LinkedHashMap<>(row.details());
                    details.put("eligibleInvoicedQuantity",acceptedQty);
                    details.put("excludedOverInvoicedQuantity",row.line().invoiceQuantity().subtract(acceptedQty));
                    row=new LineResult(row.line(),true,null,"OVER_INVOICED_QUANTITY_CAPPED",details);
                    finalRows.set(index,row);
                }
                BigDecimal lineScore=varianceScore(row.line().poUnitPrice(),row.line().invoiceUnitPrice());
                weightedLineScores=weightedLineScores.add(lineScore.multiply(acceptedQty));
                scoredQty=scoredQty.add(acceptedQty);remaining=remaining.subtract(acceptedQty);accepted.add(index);
            }
            if (scoredQty.signum()==0) continue;
            BigDecimal poLineScore=weightedLineScores.divide(scoredQty,CALCULATION_SCALE,RoundingMode.HALF_UP);
            numerator=numerator.add(poLineScore); eligible++;
            BigDecimal allocated=BigDecimal.ZERO;
            for (int acceptedOrdinal=0;acceptedOrdinal<accepted.size();acceptedOrdinal++) {
                int index=accepted.get(acceptedOrdinal);
                LineResult row=finalRows.get(index);
                BigDecimal qty=(BigDecimal)row.details().getOrDefault("eligibleInvoicedQuantity",row.line().invoiceQuantity());
                BigDecimal contribution=acceptedOrdinal==accepted.size()-1?poLineScore.subtract(allocated):
                        poLineScore.multiply(qty).divide(scoredQty,CALCULATION_SCALE,RoundingMode.HALF_UP);
                allocated=allocated.add(contribution);
                Map<String,Object> details=new LinkedHashMap<>(row.details());
                details.put("poLineScore",poLineScore);
                details.put("eligibleInvoicedQuantity",qty);
                finalRows.set(index,new LineResult(row.line(),true,contribution,row.reason(),details));
            }
        }
        long observations=byPoLine.size(), excluded=Math.max(0,observations-eligible);
        BigDecimal denominator=BigDecimal.valueOf(eligible);
        BigDecimal score=eligible==0?null:numerator.divide(denominator,CALCULATION_SCALE,RoundingMode.HALF_UP).setScale(2,RoundingMode.HALF_UP);
        String status=eligible<minimumLines?(observations==0?"MISSING_DATA":"INSUFFICIENT_OBSERVATIONS"):"AVAILABLE";
        String reason=status.equals("AVAILABLE")?null:"Eligible PO lines below configured minimum ("+minimumLines+")";
        List<Evidence> evidence=finalRows.stream().map(this::toEvidence).toList();
        Set<String> cutoffReasons=Set.of("AFTER_AS_OF_DATE","INVOICE_CREATED_AFTER_CUTOFF","INVOICE_STATUS_UNKNOWN_AT_CUTOFF",
                "INVOICE_LINE_CREATED_AFTER_CUTOFF","PO_CREATED_AFTER_CUTOFF","PO_LINE_CREATED_AFTER_CUTOFF","INVOICE_CONTENT_UNKNOWN_AT_CUTOFF");
        long cutoffExcluded=0,invalidExcluded=0;
        for(List<Integer> indices:byPoLine.values()) {
            List<LineResult> rows=indices.stream().map(finalRows::get).toList();
            if(rows.stream().anyMatch(LineResult::included))continue;
            Set<String> reasons=new HashSet<>();rows.forEach(row->reasons.add(row.reason()));
            if(!reasons.isEmpty()&&cutoffReasons.containsAll(reasons))cutoffExcluded++;
            else if(reasons.stream().anyMatch(exclusion->!"OUTSIDE_EVALUATION_WINDOW".equals(exclusion)))invalidExcluded++;
        }
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("aggregationPolicy","EQUAL_WEIGHT_PER_PO_LINE");
        metrics.put("numerator",numerator);metrics.put("denominator",denominator);
        metrics.put("eligibleObservationCount",eligible);metrics.put("excludedObservationCount",excluded);
        metrics.put("invoiceLineObservationCount",source.size());
        metrics.put("eligibleInvoiceLineCount",finalRows.stream().filter(LineResult::included).count());
        metrics.put("excludedInvoiceLineCount",finalRows.stream().filter(r->!r.included()).count());
        metrics.put("excludedAfterCutoffObservationCount",cutoffExcluded);
        metrics.put("excludedInvalidObservationCount",invalidExcluded);
        metrics.put("roundingPolicy","variance percent and line scores HALF_UP to 12 decimals; component score HALF_UP to 2 decimals; amounts HALF_UP to 4 decimals");
        return new Result(score,numerator,denominator,observations,eligible,excluded,status,reason,metrics,evidence);
    }

    private LineResult validate(Line l,LocalDate start,LocalDate end,LocalDateTime cutoff,Map<String,String> invoiceFailures,Map<Long,String> lineFailures) {
        String reason=null;
        if(l.invoiceDate()==null||l.invoiceDate().isAfter(end))reason="AFTER_AS_OF_DATE";
        else if(l.invoiceCreatedAt()==null||l.invoiceCreatedAt().isAfter(cutoff))reason="INVOICE_CREATED_AFTER_CUTOFF";
        else if(l.invoiceUpdatedAt()==null||l.invoiceUpdatedAt().isAfter(cutoff))reason="INVOICE_STATUS_UNKNOWN_AT_CUTOFF";
        else if(l.invoiceLineCreatedAt()==null||l.invoiceLineCreatedAt().isAfter(cutoff))reason="INVOICE_LINE_CREATED_AFTER_CUTOFF";
        else if(l.poCreatedAt()==null||l.poCreatedAt().isAfter(cutoff))reason="PO_CREATED_AFTER_CUTOFF";
        else if(l.poLineCreatedAt()==null||l.poLineCreatedAt().isAfter(cutoff))reason="PO_LINE_CREATED_AFTER_CUTOFF";
        else if(l.invoiceDate().isBefore(start))reason="OUTSIDE_EVALUATION_WINDOW";
        else if(l.invoiceSupplierId()!=l.poSupplierId())reason="SUPPLIER_MISMATCH";
        else if(l.invoicePurchaseOrderId()!=l.linePurchaseOrderId()||l.linePurchaseOrderId()!=l.poLinePurchaseOrderId())reason="PURCHASE_ORDER_MISMATCH";
        else if(!Objects.equals(l.invoiceCurrency(),l.poCurrency()))reason="CURRENCY_MISMATCH";
        else if("VOIDED".equals(l.invoiceStatus()))reason="VOIDED_INVOICE";
        else if(!Set.of("MATCHED","APPROVED").contains(l.invoiceStatus()))reason="INVOICE_NOT_APPROVED";
        else if(lineFailures.containsKey(l.invoiceLineId()))reason=lineFailures.get(l.invoiceLineId());
        else if(invoiceFailures.containsKey(l.invoiceId()))reason=invoiceFailures.get(l.invoiceId());
        else if(l.poUnitPrice()==null||l.poUnitPrice().signum()<=0)reason="NONPOSITIVE_PO_UNIT_PRICE";
        else if(l.productCode()==null||l.productCode().isBlank()||l.unitOfMeasure()==null||l.unitOfMeasure().isBlank())reason="MISSING_PO_LINE_PRODUCT_OR_UOM";
        else if(l.invoiceUnitPrice()==null||l.invoiceUnitPrice().signum()<0||l.invoiceQuantity()==null||l.invoiceQuantity().signum()<=0||l.lineAmount()==null||l.lineAmount().signum()<0||l.orderedQuantity()==null||l.orderedQuantity().signum()<=0)reason="INVALID_INVOICE_LINE_VALUES";
        else if(l.invoiceUnitPrice().multiply(l.invoiceQuantity()).setScale(4,RoundingMode.HALF_UP).subtract(l.lineAmount()).abs().compareTo(MONEY_TOLERANCE)>0)reason="INVOICE_LINE_AMOUNT_MISMATCH";
        if(reason!=null)return new LineResult(l,false,null,reason,details(l));
        if(!l.invoiceDate().isAfter(end)&&!l.invoiceDate().isBefore(start))return new LineResult(l,true,null,null,details(l));
        return new LineResult(l,false,null,"OUTSIDE_EVALUATION_WINDOW",details(l));
    }

    private LineResult exclude(LineResult row,String reason){return new LineResult(row.line(),false,null,reason,row.details());}
    private BigDecimal varianceScore(BigDecimal po,BigDecimal invoice){
        BigDecimal variance=invoice.subtract(po).abs().divide(po,CALCULATION_SCALE,RoundingMode.HALF_UP);
        BigDecimal score=HUNDRED.multiply(BigDecimal.ONE.subtract(variance.subtract(TWO_PERCENT).max(BigDecimal.ZERO)
                .divide(EIGHTEEN_PERCENT,CALCULATION_SCALE,RoundingMode.HALF_UP)));
        return score.max(BigDecimal.ZERO).min(HUNDRED).setScale(CALCULATION_SCALE,RoundingMode.HALF_UP);
    }
    private Map<String,Object> details(Line l){
        Map<String,Object> d=new LinkedHashMap<>();d.put("invoiceId",l.invoiceId());d.put("invoiceStatus",l.invoiceStatus());
        d.put("poLineId",l.poLineId());d.put("productCode",l.productCode());d.put("unitOfMeasure",l.unitOfMeasure());
        d.put("poUnitPrice",l.poUnitPrice());d.put("invoiceUnitPrice",l.invoiceUnitPrice());d.put("poCurrency",l.poCurrency());d.put("invoiceCurrency",l.invoiceCurrency());
        d.put("invoicedQuantity",l.invoiceQuantity());d.put("orderedQuantity",l.orderedQuantity());
        d.put("expectedLineAmount",l.poUnitPrice()==null||l.invoiceQuantity()==null?null:l.poUnitPrice().multiply(l.invoiceQuantity()).setScale(4,RoundingMode.HALF_UP));
        d.put("actualLineAmount",l.lineAmount());
        d.put("varianceAmount",l.poUnitPrice()==null||l.invoiceUnitPrice()==null||l.invoiceQuantity()==null?null:l.invoiceUnitPrice().subtract(l.poUnitPrice()).multiply(l.invoiceQuantity()).setScale(4,RoundingMode.HALF_UP));
        d.put("variancePercentage",l.poUnitPrice()==null||l.poUnitPrice().signum()<=0||l.invoiceUnitPrice()==null?null:l.invoiceUnitPrice().subtract(l.poUnitPrice()).abs().multiply(HUNDRED).divide(l.poUnitPrice(),8,RoundingMode.HALF_UP));
        return d;
    }
    private Evidence toEvidence(LineResult r){return new Evidence(r.line().invoiceLineId(),r.line().invoiceId()+":LINE:"+r.line().invoiceLineId(),r.included(),r.contribution(),r.included()?null:r.reason(),r.details());}
}
