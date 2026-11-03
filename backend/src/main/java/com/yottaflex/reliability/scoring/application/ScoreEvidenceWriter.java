package com.yottaflex.reliability.scoring.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** Writes compact evidence references derived from the same operational records used by the current calculators. */
@Component
public class ScoreEvidenceWriter {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public ScoreEvidenceWriter(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json=json; }

    public int persistComponent(long run,long score,long componentPk,long supplier,String component,
            LocalDateTime asOf,LocalDateTime windowStart,
            List<DeliveryScoringCalculator.Evidence> delivery,
            List<CostScoringCalculator.Evidence> cost,
            List<CapacityScoringCalculator.Evidence> capacity,
            ComplianceScoringCalculator.Result compliance,
            List<DisruptionScoringCalculator.Evidence> disruption) {
        return switch (component) {
            case "DELIVERY" -> persistDelivery(run,score,componentPk,supplier,asOf,delivery);
            case "QUALITY" -> persistQuality(run,score,componentPk,supplier,asOf,windowStart);
            case "COST" -> persistCost(run,score,componentPk,supplier,asOf,cost);
            case "CAPACITY" -> persistCapacity(run,score,componentPk,supplier,asOf,capacity);
            case "COMPLIANCE" -> persistCompliance(run,score,componentPk,supplier,asOf,compliance);
            case "DISRUPTION" -> persistDisruption(run,score,componentPk,supplier,asOf,disruption);
            default -> throw new IllegalArgumentException("Unsupported score component: " + component);
        };
    }

    public int persist(long runPk, long scorePk, long componentPk, long supplierPk, String component,
                       LocalDateTime asOf, LocalDateTime windowStart) {
        return switch (component) {
            case "DELIVERY" -> throw new IllegalArgumentException("Delivery evidence must come from the delivery calculation result");
            case "QUALITY" -> persistQuality(runPk,scorePk,componentPk,supplierPk,asOf,windowStart);
            case "COST" -> throw new IllegalArgumentException("Cost evidence must come from the cost calculation result");
            case "CAPACITY" -> throw new IllegalArgumentException("Capacity evidence must come from the capacity allocation result");
            case "COMPLIANCE" -> throw new IllegalArgumentException("Compliance evidence must come from the shared compliance calculation result");
            case "DISRUPTION" -> throw new IllegalArgumentException("Disruption evidence must come from the shared calculation result");
            default -> throw new IllegalArgumentException("Unsupported score component: " + component);
        };
    }

    public int persistQuality(long run,long score,long componentPk,long supplier,
                              LocalDateTime asOf,LocalDateTime windowStart) {
        return quality(run,score,componentPk,supplier,asOf,windowStart);
    }

    public int persistDelivery(long run,long score,long comp,long supplier,LocalDateTime asOf,
                               List<DeliveryScoringCalculator.Evidence> evidence) {
        if (evidence.isEmpty()) return 0;
        List<Object[]> rows=evidence.stream().map(item->new Object[]{run,score,comp,supplier,"DELIVERY","PO_LINE",
                item.sourceBusinessId(),item.sourceRecordId(),item.included(),item.contribution(),item.exclusionReason(),
                ts(asOf),serialize(item.details())}).toList();
        int[] results=db.batchUpdate("""
                INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,
                  source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
                ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
                """,rows);
        int inserted=0;for(int result:results)if(result>0)inserted+=result;return inserted;
    }

    public int persistCost(long run,long score,long comp,long supplier,LocalDateTime asOf,
                           List<CostScoringCalculator.Evidence> evidence) {
        if (evidence.isEmpty()) return 0;
        List<Object[]> rows=evidence.stream().map(item->new Object[]{run,score,comp,supplier,"COST","INVOICE_LINE",
                item.sourceBusinessId(),item.sourceRecordId(),item.included(),item.contribution(),item.exclusionReason(),
                ts(asOf),serialize(item.details())}).toList();
        int[] results=db.batchUpdate("""
                INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,
                  source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
                ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
                """,rows);
        int inserted=0;for(int result:results)if(result>0)inserted+=result;return inserted;
    }

    public int persistCapacity(long run,long score,long comp,long supplier,LocalDateTime asOf,
                               List<CapacityScoringCalculator.Evidence> evidence) {
        if(evidence.isEmpty())return 0;
        List<Object[]> rows=evidence.stream().map(item->new Object[]{run,score,comp,supplier,"CAPACITY",item.sourceEntityType(),
                item.sourceBusinessId(),item.sourceRecordId(),item.included(),item.contribution(),item.exclusionReason(),ts(asOf),serialize(item.details())}).toList();
        int[] results=db.batchUpdate("""
                INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,
                  source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
                ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
                """,rows);
        int inserted=0;for(int result:results)if(result>0)inserted+=result;return inserted;
    }

    public int persistDisruption(long run,long score,long comp,long supplier,LocalDateTime asOf,
                                 List<DisruptionScoringCalculator.Evidence> evidence) {
        if (evidence.isEmpty()) return 0;
        List<Object[]> rows=evidence.stream().map(item->new Object[]{run,score,comp,supplier,"DISRUPTION","RISK_SIGNAL",
                item.sourceBusinessId(),item.sourceRecordId(),item.included(),item.contribution(),item.exclusionReason(),
                ts(asOf),serialize(item.details())}).toList();
        int[] results=db.batchUpdate("""
                INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,
                  source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
                ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
                """,rows);
        int inserted=0;for(int result:results)if(result>0)inserted+=result;return inserted;
    }

    public int persistCompliance(long run,long score,long comp,long supplier,LocalDateTime asOf,
                                 ComplianceScoringCalculator.Result result) {
        if (result.evidence().isEmpty()) return 0;
        List<Object[]> rows=new ArrayList<>();
        for (ComplianceScoringCalculator.Evidence item : result.evidence()) {
            long sourceId=item.checkId()!=null?item.checkId():item.applicabilityRecordId()!=null?item.applicabilityRecordId():item.assessmentId()!=null?item.assessmentId():item.requirementId();
            String sourceType=item.checkId()!=null?"COMPLIANCE_CHECK":item.applicabilityRecordId()!=null?"COMPLIANCE_REQUIREMENT_SCOPE":item.assessmentId()!=null?"COMPLIANCE_ASSESSMENT":"COMPLIANCE_REQUIREMENT";
            Map<String,Object> details=new LinkedHashMap<>();
            details.put("requirementId",item.requirementId());details.put("requirementCode",item.requirementCode());
            details.put("mandatory",item.mandatory());details.put("critical",item.critical());details.put("scopeType",item.scopeType());details.put("siteId",item.siteId());
            details.put("assessmentId",item.assessmentId());details.put("assessmentStatus",item.assessmentStatus());details.put("checkId",item.checkId());details.put("result",item.result());
            details.put("evidenceAvailableAtCutoff",item.evidenceAvailable());details.put("escalationReason",item.escalationReason());
            details.put("applicabilityRecordId",item.applicabilityRecordId());
            details.putAll(item.details());
            rows.add(new Object[]{run,score,comp,supplier,"COMPLIANCE",sourceType,item.requirementCode()+(item.siteId()==null?"":":SITE:"+item.siteId()),sourceId,
                    item.included(),item.contribution(),item.exclusionReason(),ts(asOf),serialize(details)});
        }
        int[] results=db.batchUpdate("""
          INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb))
          ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
          """,rows);
        int inserted=0;for(int count:results)if(count>0)inserted+=count;return inserted;
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize score evidence details",e); }
    }

    private int quality(long run,long score,long comp,long supplier,LocalDateTime asOf,LocalDateTime windowStart) {
        return db.update("""
            INSERT INTO score_evidence(score_run_id,supplier_score_id,supplier_score_component_id,supplier_id,component,
              source_entity_type,source_business_id,source_record_id,included,contribution,exclusion_reason,evaluated_at,details)
            SELECT ?,?,?,?,'QUALITY','INSPECTION_LINE',i.inspection_id||':RECEIPT_LINE:'||il.receipt_line_id,il.id,
              (i.status='COMPLETED' AND i.inspected_at BETWEEN ? AND ? AND i.created_at<=? AND il.inspected_quantity>0),
              CASE WHEN i.status='COMPLETED' AND i.inspected_at BETWEEN ? AND ? AND i.created_at<=? AND il.inspected_quantity>0
                   THEN il.accepted_quantity END,
              CASE WHEN i.inspected_at<? THEN 'OUTSIDE_EVALUATION_WINDOW'
                   WHEN i.status<>'COMPLETED' THEN 'INSPECTION_NOT_COMPLETED'
                   WHEN il.inspected_quantity<=0 THEN 'ZERO_INSPECTED_QUANTITY' END,
              ?,jsonb_build_object('inspectionId',i.inspection_id,'inspectionStatus',i.status,
                'inspectedAt',i.inspected_at,'inspectedQuantity',il.inspected_quantity,
                'acceptedQuantity',il.accepted_quantity,'rejectedQuantity',il.rejected_quantity,'defectCode',il.defect_code)
            FROM inspection_line il JOIN inspection i ON i.id=il.inspection_id AND i.receipt_id=il.receipt_id
            JOIN receipt r ON r.id=il.receipt_id
            JOIN receipt_line rl ON rl.id=il.receipt_line_id AND rl.receipt_id=il.receipt_id
            WHERE r.supplier_id=? AND i.created_at<=? AND i.inspected_at<=?
              AND r.created_at<=? AND rl.created_at<=? AND il.created_at<=?
            ON CONFLICT (supplier_score_component_id,source_entity_type,source_record_id) DO NOTHING
            """,run,score,comp,supplier,ts(windowStart),ts(asOf),ts(asOf),ts(windowStart),ts(asOf),ts(asOf),ts(windowStart),ts(asOf),supplier,ts(asOf),ts(asOf),ts(asOf),ts(asOf),ts(asOf));
    }

    private Timestamp ts(LocalDateTime time) { return Timestamp.valueOf(time); }
}
