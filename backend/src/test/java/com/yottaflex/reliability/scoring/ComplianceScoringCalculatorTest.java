package com.yottaflex.reliability.scoring;

import com.yottaflex.reliability.scoring.application.ComplianceScoringCalculator;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComplianceScoringCalculatorTest {
    private static final LocalDateTime AS_OF=LocalDateTime.of(2026,10,22,12,0);
    private static ComplianceScoringCalculator.Observation o(String code,String scope,Long site,String result,String status,String evidence) {
        return new ComplianceScoringCalculator.Observation(3,code,scope,site,true,LocalDate.of(2026,1,1),null,AS_OF.minusDays(2),
                9L,"ASM-9",status,AS_OF.minusDays(3),LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),AS_OF.minusDays(3),AS_OF.minusDays(2),
                11L,result,AS_OF.minusDays(3),AS_OF.minusDays(3),evidence,evidence==null?null:AS_OF.minusDays(3),null,false,ComplianceScoringCalculator.MANDATORY.contains(code),ComplianceScoringCalculator.CRITICAL.contains(code),13L);
    }
    private static ComplianceScoringCalculator.Observation replace(ComplianceScoringCalculator.Observation x,Long assessmentId,String status,String result,String evidence,LocalDate expiry,List<ComplianceScoringCalculator.EvidenceItemState> items) {
        return new ComplianceScoringCalculator.Observation(x.requirementId(),x.code(),x.scopeType(),x.siteId(),x.active(),x.effectiveFrom(),x.effectiveTo(),x.policyRecordedAt(),assessmentId,x.assessmentBusinessId(),status,x.assessedAt(),x.effectiveAssessmentFrom(),expiry,x.assessmentCreatedAt(),x.statusRecordedAt(),x.checkId(),result,x.checkedAt(),x.checkCreatedAt(),evidence,x.evidenceAvailableAt(),x.evidenceRevokedAt(),x.invalid(),x.mandatory(),x.critical(),x.applicabilityRecordId(),items);
    }
    @Test void approvedMappingsKeepMandatoryAndCriticalIndependent() {
        assertEquals(12,ComplianceScoringCalculator.CODES.size());
        assertEquals(4,ComplianceScoringCalculator.MANDATORY.size());
        assertEquals(2,ComplianceScoringCalculator.CRITICAL.size());
        assertEquals(3,ComplianceScoringCalculator.SITE_SCOPED.size());
        assertDoesNotThrow(()->ComplianceScoringCalculator.validatePolicy("QUALITY_CERT",false,false,"SUPPLIER"));
        assertThrows(IllegalArgumentException.class,()->ComplianceScoringCalculator.validatePolicy("QUALITY_CERT",true,false,"SUPPLIER"));
        assertThrows(IllegalArgumentException.class,()->ComplianceScoringCalculator.validatePolicy("TRACEABILITY",false,false,"SUPPLIER"));
    }
    @Test void passesOnlyWhenEvidenceAndCompletedHistoricalAssessmentAreAvailable() {
        var calc=new ComplianceScoringCalculator();
        var good=calc.calculate(List.of(o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc-1")),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals("AVAILABLE",good.status()); assertEquals(100,good.score().intValue()); assertEquals(1,good.eligible());
        var missing=calc.calculate(List.of(o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED",null)),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals("MISSING_DATA",missing.status()); assertNull(missing.score()); assertEquals("MISSING_EVIDENCE_AT_CUTOFF",missing.evidence().getFirst().exclusionReason());
    }
    @Test void onlyMandatoryCriticalFailureEscalatesAndSiteScopeIsRequired() {
        var calc=new ComplianceScoringCalculator();
        var mandatory=calc.calculate(List.of(o("SANCTIONS_SCREEN","SUPPLIER",null,"FAIL","COMPLETED","doc")),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertTrue(mandatory.criticalViolation());
        var optional=calc.calculate(List.of(o("QUALITY_CERT","SUPPLIER",null,"FAIL","COMPLETED","doc")),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertFalse(optional.criticalViolation());
        assertEquals("EXCLUDED_INVALID_DATA",calc.calculate(List.of(o("TRACEABILITY","SITE",null,"PASS","COMPLETED","doc")),AS_OF,AS_OF.minusDays(365).toLocalDate(),1).status());
    }
    @Test void postCutoffStatusEvidenceAndExpiredAssessmentDoNotPass() {
        var calc=new ComplianceScoringCalculator();
        var late=o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc");
        late=new ComplianceScoringCalculator.Observation(late.requirementId(),late.code(),late.scopeType(),late.siteId(),late.active(),late.effectiveFrom(),late.effectiveTo(),AS_OF.plusSeconds(1),late.assessmentRecordId(),late.assessmentBusinessId(),late.assessmentStatus(),late.assessedAt(),late.effectiveAssessmentFrom(),late.expiresOn(),late.assessmentCreatedAt(),late.statusRecordedAt(),late.checkId(),late.result(),late.checkedAt(),late.checkCreatedAt(),late.evidenceReference(),late.evidenceAvailableAt(),late.evidenceRevokedAt(),false,late.mandatory(),late.critical(),late.applicabilityRecordId());
        var result=calc.calculate(List.of(late),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals(0,result.observations());
        var expired=o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc");
        expired=new ComplianceScoringCalculator.Observation(expired.requirementId(),expired.code(),expired.scopeType(),expired.siteId(),expired.active(),expired.effectiveFrom(),expired.effectiveTo(),expired.policyRecordedAt(),expired.assessmentRecordId(),expired.assessmentBusinessId(),expired.assessmentStatus(),expired.assessedAt(),expired.effectiveAssessmentFrom(),LocalDate.of(2026,10,21),expired.assessmentCreatedAt(),expired.statusRecordedAt(),expired.checkId(),expired.result(),expired.checkedAt(),expired.checkCreatedAt(),expired.evidenceReference(),expired.evidenceAvailableAt(),expired.evidenceRevokedAt(),false,expired.mandatory(),expired.critical(),expired.applicabilityRecordId());
        assertEquals("ASSESSMENT_EXPIRED",calc.calculate(List.of(expired),AS_OF,AS_OF.minusDays(365).toLocalDate(),1).evidence().getFirst().exclusionReason());
    }
    @Test void revocationAfterCutoffDoesNotEraseHistoricalAvailabilityAndMinimumIsEnforced() {
        var calc=new ComplianceScoringCalculator();
        var row=o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc");
        row=new ComplianceScoringCalculator.Observation(row.requirementId(),row.code(),row.scopeType(),row.siteId(),row.active(),row.effectiveFrom(),row.effectiveTo(),row.policyRecordedAt(),row.assessmentRecordId(),row.assessmentBusinessId(),row.assessmentStatus(),row.assessedAt(),row.effectiveAssessmentFrom(),row.expiresOn(),row.assessmentCreatedAt(),row.statusRecordedAt(),row.checkId(),row.result(),row.checkedAt(),row.checkCreatedAt(),row.evidenceReference(),row.evidenceAvailableAt(),AS_OF.plusDays(1),row.invalid(),row.mandatory(),row.critical(),row.applicabilityRecordId());
        var result=calc.calculate(List.of(row),AS_OF,AS_OF.minusDays(365).toLocalDate(),2);
        assertEquals("INSUFFICIENT_OBSERVATIONS",result.status());assertNull(result.score());assertTrue(result.evidence().getFirst().included());
    }
    @Test void allInvalidApplicableRowsHaveExplicitInvalidStatus() {
        var row=o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc");
        row=new ComplianceScoringCalculator.Observation(row.requirementId(),row.code(),row.scopeType(),row.siteId(),row.active(),row.effectiveFrom(),row.effectiveTo(),row.policyRecordedAt(),row.assessmentRecordId(),row.assessmentBusinessId(),row.assessmentStatus(),row.assessedAt(),row.effectiveAssessmentFrom(),row.expiresOn(),row.assessmentCreatedAt(),row.statusRecordedAt(),row.checkId(),row.result(),row.checkedAt(),row.checkCreatedAt(),row.evidenceReference(),row.evidenceAvailableAt(),row.evidenceRevokedAt(),true,row.mandatory(),row.critical(),row.applicabilityRecordId());
        assertEquals("EXCLUDED_INVALID_DATA",new ComplianceScoringCalculator().calculate(List.of(row),AS_OF,AS_OF.minusDays(365).toLocalDate(),1).status());
    }
    @Test void missingPendingExpiredAndLegacyEvidenceOnMandatoryCriticalRequirementsEscalate() {
        var calc=new ComplianceScoringCalculator();
        var base=o("SANCTIONS_SCREEN","SUPPLIER",null,"PASS","COMPLETED","doc");
        var missing=calc.calculate(List.of(replace(base,null,null,null,null,null,List.of())),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertTrue(missing.criticalViolation()); assertEquals(1L,((Number)missing.metrics().get("mandatoryCriticalMissingCount")).longValue());
        var pending=calc.calculate(List.of(replace(base,base.assessmentRecordId(),"PENDING","PENDING","doc",base.expiresOn(),List.of())),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertTrue(pending.criticalViolation()); assertEquals(1L,((Number)pending.metrics().get("pendingRequirements")).longValue());
        var expired=calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",AS_OF.toLocalDate().minusDays(1),List.of())),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertTrue(expired.criticalViolation()); assertEquals(1L,((Number)expired.metrics().get("expiredRequirements")).longValue());
        var legacy=replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),List.of(new ComplianceScoringCalculator.EvidenceItemState(1,"item-1",null,null,null,null,"LEGACY_AVAILABILITY_UNKNOWN",true,"doc")));
        var unknown=calc.calculate(List.of(legacy),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertTrue(unknown.criticalViolation()); assertEquals(1L,((Number)unknown.metrics().get("legacyEvidenceUnknownCount")).longValue()); assertEquals(0L,((Number)unknown.metrics().get("missingEvidence")).longValue());
    }
    @Test void evidenceAvailabilityIsResolvedPerItemAndPerCutoff() {
        var calc=new ComplianceScoringCalculator(); var base=o("BUSINESS_LICENSE","SUPPLIER",null,"PASS","COMPLETED","doc");
        var states=List.of(
          new ComplianceScoringCalculator.EvidenceItemState(1L,"item-1",5L,"event-5","AVAILABLE",AS_OF.minusDays(2),"AVAILABLE",false,"doc-a"),
          new ComplianceScoringCalculator.EvidenceItemState(2L,"item-2",8L,"event-8","REVOKED",AS_OF.minusDays(1),"REVOKED",false,"doc-b"));
        var mixed=calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),states)),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals(1,mixed.eligible()); assertTrue(mixed.evidence().getFirst().included());
        var bothAvailable=List.of(states.getFirst(),new ComplianceScoringCalculator.EvidenceItemState(3L,"item-3",9L,"event-9","AVAILABLE",AS_OF.minusDays(1),"AVAILABLE",false,"doc-c"));
        assertEquals(1,calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),bothAvailable)),AS_OF,AS_OF.minusDays(365).toLocalDate(),1).eligible());
        var replacement=List.of(states.get(1),bothAvailable.get(1));
        assertEquals(1,calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),replacement)),AS_OF,AS_OF.minusDays(365).toLocalDate(),1).eligible());
        // The query supplies the latest event visible at the cutoff; a later revocation is absent from this state.
        assertEquals(1,calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),List.of(states.getFirst()))),AS_OF.minusDays(1),AS_OF.minusDays(366).toLocalDate(),1).eligible());
        var lateEvent=new ComplianceScoringCalculator.EvidenceItemState(4L,"item-4",10L,"event-10","AVAILABLE",AS_OF.plusSeconds(1),"AVAILABLE",false,"late-doc");
        var afterCutoff=calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),List.of(lateEvent))),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals(0,afterCutoff.eligible()); assertEquals(1L,((Number)afterCutoff.metrics().get("missingEvidence")).longValue());
        var revoked=List.of(states.get(1));
        var none=calc.calculate(List.of(replace(base,base.assessmentRecordId(),"COMPLETED","PASS","doc",base.expiresOn(),revoked)),AS_OF,AS_OF.minusDays(365).toLocalDate(),1);
        assertEquals("MISSING_DATA",none.status());
    }
}
