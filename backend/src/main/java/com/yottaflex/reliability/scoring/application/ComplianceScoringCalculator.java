package com.yottaflex.reliability.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure policy evaluator. Database selection and historical version resolution are performed by ScoringService. */
public final class ComplianceScoringCalculator {
    public static final Set<String> CODES=Set.of("BUSINESS_LICENSE","TAX_REGISTRATION","QUALITY_CERT","ENVIRONMENT_CERT","WORKPLACE_SAFETY","DATA_SECURITY","ANTI_BRIBERY","SANCTIONS_SCREEN","INSURANCE_COVER","ORIGIN_DECLARATION","TRACEABILITY","CONTINUITY_PLAN");
    public static final Set<String> MANDATORY=Set.of("BUSINESS_LICENSE","TAX_REGISTRATION","SANCTIONS_SCREEN","INSURANCE_COVER");
    public static final Set<String> CRITICAL=Set.of("SANCTIONS_SCREEN","INSURANCE_COVER");
    public static final Set<String> SITE_SCOPED=Set.of("WORKPLACE_SAFETY","ORIGIN_DECLARATION","TRACEABILITY");

    public static void validatePolicy(String code, boolean mandatory, boolean critical, String scope) {
        if (!CODES.contains(code)) throw new IllegalArgumentException("Unsupported compliance requirement code: "+code);
        if (mandatory!=MANDATORY.contains(code) || critical!=CRITICAL.contains(code)) throw new IllegalArgumentException("Approved mandatory/critical mapping mismatch for "+code);
        String expected=SITE_SCOPED.contains(code)?"SITE":"SUPPLIER";
        if (!expected.equals(scope)) throw new IllegalArgumentException("Approved scope mapping mismatch for "+code+": expected "+expected);
    }

    public record Observation(long requirementId,String code,String scopeType,Long siteId,boolean active,
      LocalDate effectiveFrom,LocalDate effectiveTo,LocalDateTime policyRecordedAt,Long assessmentRecordId,
      String assessmentBusinessId,String assessmentStatus,LocalDateTime assessedAt,LocalDate effectiveAssessmentFrom,
      LocalDate expiresOn,LocalDateTime assessmentCreatedAt,LocalDateTime statusRecordedAt,Long checkId,
      String result,LocalDateTime checkedAt,LocalDateTime checkCreatedAt,String evidenceReference,
      LocalDateTime evidenceAvailableAt,LocalDateTime evidenceRevokedAt,boolean invalid,boolean mandatory,
      boolean critical,Long applicabilityRecordId,List<EvidenceItemState> evidenceItems) {
        public Observation(long requirementId,String code,String scopeType,Long siteId,boolean active,LocalDate effectiveFrom,LocalDate effectiveTo,LocalDateTime policyRecordedAt,Long assessmentRecordId,String assessmentBusinessId,String assessmentStatus,LocalDateTime assessedAt,LocalDate effectiveAssessmentFrom,LocalDate expiresOn,LocalDateTime assessmentCreatedAt,LocalDateTime statusRecordedAt,Long checkId,String result,LocalDateTime checkedAt,LocalDateTime checkCreatedAt,String evidenceReference,LocalDateTime evidenceAvailableAt,LocalDateTime evidenceRevokedAt,boolean invalid,boolean mandatory,boolean critical,Long applicabilityRecordId){this(requirementId,code,scopeType,siteId,active,effectiveFrom,effectiveTo,policyRecordedAt,assessmentRecordId,assessmentBusinessId,assessmentStatus,assessedAt,effectiveAssessmentFrom,expiresOn,assessmentCreatedAt,statusRecordedAt,checkId,result,checkedAt,checkCreatedAt,evidenceReference,evidenceAvailableAt,evidenceRevokedAt,invalid,mandatory,critical,applicabilityRecordId,List.of());}
        public Observation { evidenceItems=evidenceItems==null?List.of():List.copyOf(evidenceItems); }
    }
    public record EvidenceItemState(long itemId,String itemKey,Long eventId,String eventKey,String eventType,
                                    LocalDateTime recordedAt,String availability,boolean legacyUntracked,String reference) {}

    public record Evidence(long requirementId,String requirementCode,Long siteId,String scopeType,boolean mandatory,
      boolean critical,Long assessmentId,String assessmentStatus,Long checkId,String result,Long applicabilityRecordId,
      boolean included,BigDecimal contribution,String exclusionReason,boolean evidenceAvailable,
      String escalationReason,Map<String,Object> details) {}
    public record Result(BigDecimal score,BigDecimal numerator,BigDecimal denominator,long observations,long eligible,
      long excluded,String status,String reason,boolean criticalViolation,List<String> escalationReasons,
      Map<String,Object> metrics,List<Evidence> evidence) {}

    public Result calculate(List<Observation> input, LocalDateTime asOf, LocalDate windowStart, long minimum) {
        List<Observation> rows=input==null?List.of():input.stream().sorted(Comparator.comparingLong(Observation::requirementId).thenComparing(o->o.siteId()==null?-1:o.siteId())).toList();
        List<Evidence> evidence=new ArrayList<>(); List<String> escalations=new ArrayList<>();
        long eligible=0,excluded=0,passed=0,failed=0,pending=0,expired=0,missing=0,missingAssessment=0,missingCheck=0,missingEvidence=0,unknown=0,notApplicable=0,invalid=0,mandatoryCriticalMissing=0,observations=0;
        boolean anyApplicable=false; long invalidApplicable=0;
        for (Observation o:rows) {
            String exclusion=null, reason=null; boolean included=false, available=false; BigDecimal contribution=null;
            if (!CODES.contains(o.code()) || o.invalid() || !Objects.equals(o.scopeType(),SITE_SCOPED.contains(o.code())?"SITE":"SUPPLIER") || ("SITE".equals(o.scopeType())&&o.siteId()==null)) {
                exclusion="INVALID_POLICY_OR_SOURCE"; invalid++; excluded++; invalidApplicable++;
                } else if (!o.active() || o.policyRecordedAt()==null || o.policyRecordedAt().isAfter(asOf) || o.effectiveFrom()==null || o.effectiveFrom().isAfter(asOf.toLocalDate()) || (o.effectiveTo()!=null&&o.effectiveTo().isBefore(asOf.toLocalDate()))) {
                exclusion="POLICY_NOT_EFFECTIVE_AT_CUTOFF"; excluded++;
            } else {
                anyApplicable=true; observations++;
                boolean assessmentKnown=o.assessmentRecordId()!=null && o.assessmentCreatedAt()!=null && !o.assessmentCreatedAt().isAfter(asOf) && o.assessedAt()!=null && !o.assessedAt().isAfter(asOf) && o.statusRecordedAt()!=null && !o.statusRecordedAt().isAfter(asOf);
                String status=assessmentKnown?o.assessmentStatus():null;
                boolean datesValid=assessmentKnown && (o.effectiveAssessmentFrom()==null || !o.effectiveAssessmentFrom().isAfter(asOf.toLocalDate())) && (o.expiresOn()==null || !o.expiresOn().isBefore(asOf.toLocalDate()));
                boolean checkKnown=o.checkId()!=null && o.checkCreatedAt()!=null && !o.checkCreatedAt().isAfter(asOf) && o.checkedAt()!=null && !o.checkedAt().isAfter(asOf);
                boolean evidenceKnown=o.evidenceItems().isEmpty() ? o.evidenceAvailableAt()!=null && !o.evidenceAvailableAt().isAfter(asOf) && (o.evidenceRevokedAt()==null || o.evidenceRevokedAt().isAfter(asOf)) : o.evidenceItems().stream().anyMatch(e->"AVAILABLE".equals(e.availability())&&e.recordedAt()!=null&&!e.recordedAt().isAfter(asOf));
                long legacyUnknownItems=o.evidenceItems().stream().filter(e->"LEGACY_AVAILABILITY_UNKNOWN".equals(e.availability())).count();
                boolean legacyUnknown=legacyUnknownItems>0;
                unknown+=legacyUnknownItems;
                boolean hasEvidence=o.evidenceItems().isEmpty()?o.evidenceReference()!=null:!o.evidenceItems().isEmpty();
                if (!assessmentKnown) { exclusion="ASSESSMENT_MISSING_OR_UNKNOWN_AT_CUTOFF"; missing++; missingAssessment++; }
                else if (!"COMPLETED".equals(status)) { exclusion="ASSESSMENT_NOT_COMPLETED"; if("PENDING".equals(status)||"DRAFT".equals(status))pending++; else if("EXPIRED".equals(status))expired++; else missing++; }
                else if (!datesValid) { boolean isExpired=o.expiresOn()!=null&&o.expiresOn().isBefore(asOf.toLocalDate()); exclusion=isExpired?"ASSESSMENT_EXPIRED":"ASSESSMENT_NOT_EFFECTIVE"; if(isExpired)expired++; else missing++; }
                else if (!checkKnown) { exclusion="CHECK_MISSING_OR_UNKNOWN_AT_CUTOFF"; missing++; missingCheck++; }
                else if ("NOT_APPLICABLE".equals(o.result())) { exclusion="CHECK_NOT_APPLICABLE"; notApplicable++; }
                else if (!"PASS".equals(o.result()) && !"FAIL".equals(o.result())) { exclusion="CHECK_RESULT_INVALID_OR_PENDING"; if("PENDING".equals(o.result()))pending++; else {invalid++;} }
                else if (!evidenceKnown) {
                    exclusion=!hasEvidence?"MISSING_EVIDENCE_AT_CUTOFF":legacyUnknown?"LEGACY_AVAILABILITY_UNKNOWN":"EVIDENCE_UNAVAILABLE_AT_CUTOFF";
                    if(!hasEvidence){missing++;missingEvidence++;} else if(!legacyUnknown){missing++;missingEvidence++;}
                } else {
                    available=true; included=true; eligible++; contribution="PASS".equals(o.result())?BigDecimal.ONE:BigDecimal.ZERO;
                    if("PASS".equals(o.result()))passed++; else failed++;
                }
                boolean criticalMissing=o.mandatory()&&o.critical()&&!included;
                boolean criticalFail=o.mandatory()&&o.critical()&&included&&"FAIL".equals(o.result());
                if(criticalMissing||criticalFail){if(criticalMissing)mandatoryCriticalMissing++;reason="MANDATORY_CRITICAL_"+(criticalFail?"FAILURE":exclusion==null?"FAILURE":exclusion);escalations.add(o.code()+(o.siteId()==null?"":"@site:"+o.siteId())+":"+reason);}
            }
            Map<String,Object> details=new LinkedHashMap<>(); details.put("requirementId",o.requirementId());details.put("scopeType",o.scopeType());details.put("mandatory",o.mandatory());details.put("critical",o.critical());details.put("assessmentId",o.assessmentRecordId());details.put("checkId",o.checkId());details.put("evidenceReference",o.evidenceReference());details.put("evidenceItems",o.evidenceItems());details.put("legacyEvidenceUnknown",o.evidenceItems().stream().anyMatch(e->"LEGACY_AVAILABILITY_UNKNOWN".equals(e.availability())));details.put("evidenceAvailability",o.evidenceReference()==null&&o.evidenceItems().isEmpty()?"MISSING":unknownEvidence(o,asOf));details.put("policyRecordedAt",o.policyRecordedAt());details.put("assessmentStatusRecordedAt",o.statusRecordedAt());
            evidence.add(new Evidence(o.requirementId(),o.code(),o.siteId(),o.scopeType(),o.mandatory(),o.critical(),o.assessmentRecordId(),o.assessmentStatus(),o.checkId(),o.result(),o.applicabilityRecordId(),included,contribution,exclusion,available,reason,java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details))));
        }
        String status; String reason=null; BigDecimal score=null;
        if(!anyApplicable&&invalidApplicable>0){status="EXCLUDED_INVALID_DATA";reason="Compliance source or policy data failed validation";}
        else if(!anyApplicable){status="NOT_APPLICABLE";reason="No applicable compliance requirements at cutoff";}
        else if(eligible==0&&notApplicable==observations){status="NOT_APPLICABLE";reason="All completed applicable checks were explicitly not applicable";}
        else if(eligible<minimum){status=eligible==0&&invalid==observations?"EXCLUDED_INVALID_DATA":eligible==0&&missing+unknown>0?"MISSING_DATA":"INSUFFICIENT_OBSERVATIONS";reason="Eligible completed checks below configured minimum; missing="+missing+", legacyEvidenceUnknown="+unknown;}
        else {status="AVAILABLE";score=BigDecimal.valueOf(passed).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(eligible),8,RoundingMode.HALF_UP).setScale(2,RoundingMode.HALF_UP);}
        Map<String,Object> metrics=new LinkedHashMap<>();metrics.put("totalApplicableRequirements",observations);metrics.put("supplierScopes",rows.stream().filter(o->"SUPPLIER".equals(o.scopeType())).count());metrics.put("siteScopes",rows.stream().filter(o->"SITE".equals(o.scopeType())).count());metrics.put("mandatoryRequirements",rows.stream().filter(Observation::mandatory).count());metrics.put("criticalRequirements",rows.stream().filter(Observation::critical).count());metrics.put("passedRequirements",passed);metrics.put("failedRequirements",failed);metrics.put("pendingRequirements",pending);metrics.put("expiredRequirements",expired);metrics.put("missingEvidence",missingEvidence);metrics.put("missingAssessmentCount",missingAssessment);metrics.put("missingCheckCount",missingCheck);metrics.put("missingDataCount",missing);metrics.put("legacyEvidenceUnknownCount",unknown);metrics.put("notApplicableRequirements",notApplicable);metrics.put("mandatoryCriticalMissingCount",mandatoryCriticalMissing);metrics.put("eligibleObservations",eligible);metrics.put("excludedObservations",excluded);metrics.put("invalidObservations",invalid);metrics.put("coveragePercentage",observations==0?null:BigDecimal.valueOf(eligible).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(observations),2,RoundingMode.HALF_UP));metrics.put("criticalViolation",!escalations.isEmpty());metrics.put("escalationReasons",List.copyOf(escalations));
        return new Result(score,BigDecimal.valueOf(passed),BigDecimal.valueOf(eligible),observations,eligible,excluded,status,reason,!escalations.isEmpty(),List.copyOf(escalations),java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metrics)),List.copyOf(evidence));
    }
    private String unknownEvidence(Observation o,LocalDateTime asOf){if(!o.evidenceItems().isEmpty()){if(o.evidenceItems().stream().anyMatch(e->"AVAILABLE".equals(e.availability())))return "AVAILABLE";if(o.evidenceItems().stream().anyMatch(e->"LEGACY_AVAILABILITY_UNKNOWN".equals(e.availability())))return "LEGACY_AVAILABILITY_UNKNOWN";return "UNAVAILABLE";}if(o.evidenceAvailableAt()==null)return "LEGACY_AVAILABILITY_UNKNOWN";if(o.evidenceAvailableAt().isAfter(asOf))return "NOT_YET_AVAILABLE";if(o.evidenceRevokedAt()!=null&&!o.evidenceRevokedAt().isAfter(asOf))return "REVOKED";return "AVAILABLE";}
}
