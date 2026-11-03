package com.yottaflex.reliability.validation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StagingValidationService {
    private static final String DETECTOR = "phase3-v1";
    private final JdbcTemplate jdbc;
    private final FixtureValidators validators;
    private final ObjectMapper mapper;

    public StagingValidationService(JdbcTemplate jdbc, FixtureValidators validators, ObjectMapper mapper) { this.jdbc=jdbc; this.validators=validators; this.mapper=mapper; }

    @Transactional
    public Map<String,Object> runFixtures() {
        Long runId=jdbc.query("SELECT id FROM data_generation_run WHERE status='COMPLETED' ORDER BY completed_at DESC LIMIT 1", rs -> rs.next()?rs.getLong(1):null);
        if(runId==null) throw new IllegalStateException("Staging validation requires a completed generation run reference.");
        String runKey="VAL-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT); Instant now=Instant.now();
        jdbc.update("INSERT INTO validation_run(validation_run_id,generation_run_id,detector_version,run_status,started_at) VALUES (?,?,?,'RUNNING',?)",runKey,runId,DETECTOR,ts(now));
        long validationId=jdbc.queryForObject("SELECT id FROM validation_run WHERE validation_run_id=?",Long.class,runKey);
        List<Map<String,Object>> results=new ArrayList<>();
        try {
            for(RecordValidator v:validators.all()) {
                JsonNode payload=fixture(v.fixtureCode()); String key="PH3-"+v.fixtureCode();
                jdbc.update("INSERT INTO staging_record(staging_key,generation_run_id,entity_type,payload,fixture_code,staged_at,validation_status) VALUES (?,?,?,?::jsonb,?,?, 'PENDING') ON CONFLICT(staging_key) DO NOTHING",key,runId,v.entityType(),payload.toString(),v.fixtureCode(),ts(now));
                Map<String,Object> stored=jdbc.queryForMap("SELECT id,fixture_code,payload,corrected_payload,payload_version,validation_status FROM staging_record WHERE staging_key=?",key);
                if(!v.fixtureCode().equals(stored.get("fixture_code"))) throw new IllegalStateException("Existing staging key has a different fixture type: "+key);
                JsonNode current=mapper.readTree(Objects.toString(stored.get("corrected_payload"),Objects.toString(stored.get("payload"))));
                results.add(validateOne(validationId,v,((Number)stored.get("id")).longValue(),((Number)stored.get("payload_version")).intValue(),current,now));
            }
            jdbc.update("UPDATE validation_run SET run_status='COMPLETED',completed_at=? WHERE id=?",ts(Instant.now()),validationId);
            return Map.of("validationRunId",runKey,"status","COMPLETED","detectorVersion",DETECTOR,"results",results);
        } catch(Exception ex) {
            jdbc.update("UPDATE validation_run SET run_status='FAILED',completed_at=?,failure_reason=? WHERE id=?",ts(Instant.now()),safeMessage(ex),validationId);
            return Map.of("validationRunId",runKey,"status","FAILED","detectorVersion",DETECTOR,"failureReason",safeMessage(ex),"results",results);
        }
    }

    @Transactional
    public Map<String,Object> revalidate(long stagingId) {
        Map<String,Object> s=jdbc.queryForMap("SELECT id,fixture_code,generation_run_id,payload,corrected_payload,payload_version FROM staging_record WHERE id=?",stagingId);
        RecordValidator v=validators.get((String)s.get("fixture_code"));
        JsonNode payload=readJson(s.get("corrected_payload")!=null?s.get("corrected_payload"):s.get("payload"));
        String runKey="VAL-"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT); Instant now=Instant.now();
        jdbc.update("INSERT INTO validation_run(validation_run_id,generation_run_id,detector_version,run_status,started_at) VALUES (?,?,?,'RUNNING',?)",runKey,s.get("generation_run_id"),DETECTOR,ts(now));
        long validationId=jdbc.queryForObject("SELECT id FROM validation_run WHERE validation_run_id=?",Long.class,runKey);
        Map<String,Object> result=validateOne(validationId,v,stagingId,((Number)s.get("payload_version")).intValue(),payload,now);
        jdbc.update("UPDATE validation_run SET run_status='COMPLETED',completed_at=? WHERE id=?",ts(Instant.now()),validationId);
        return Map.of("validationRunId",runKey,"status","COMPLETED","result",result);
    }

    @Transactional
    public Map<String,Object> correct(long id, JsonNode corrected) {
        if(corrected==null||!corrected.isObject()) throw new IllegalArgumentException("Corrected payload must be a JSON object.");
        jdbc.queryForObject("SELECT id FROM staging_record WHERE id=? FOR UPDATE",Long.class,id);
        String disposition=jdbc.query("SELECT disposition FROM quarantined_record WHERE staging_record_id=?",rs->rs.next()?rs.getString(1):null,id);
        if("RELEASED".equals(disposition)||"REJECTED".equals(disposition)) throw new IllegalStateException("Terminal quarantine records cannot be corrected.");
        jdbc.update("UPDATE staging_record SET corrected_payload=?::jsonb,payload_version=payload_version+1,validation_status='PENDING' WHERE id=?",corrected.toString(),id);
        jdbc.update("UPDATE quarantined_record SET disposition='CORRECTED',disposition_note='Corrected payload submitted; revalidation required.' WHERE staging_record_id=? AND disposition<>'RELEASED'",id);
        jdbc.update("INSERT INTO audit_event(audit_id,entity_type,entity_business_id,event_type,occurred_at,change_summary,before_value,after_value) VALUES (?,?,?,?,?,?,(SELECT payload FROM staging_record WHERE id=?),(SELECT corrected_payload FROM staging_record WHERE id=?))",auditId("CORRECTION",id+":"+Instant.now()),"STAGING_RECORD",Long.toString(id),"CORRECTION_SUBMITTED",ts(Instant.now()),"Corrected payload stored separately; complete revalidation required.",id,id);
        return Map.of("stagingRecordId",id,"payloadVersion",jdbc.queryForObject("SELECT payload_version FROM staging_record WHERE id=?",Integer.class,id),"validationStatus","PENDING","quarantineDisposition","CORRECTED");
    }

    @Transactional
    public Map<String,Object> resolve(String exceptionId,String status,String note,String actor) {
        if(!Set.of("RESOLVED","ACCEPTED").contains(status)||note==null||note.isBlank()) throw new IllegalArgumentException("Resolution requires RESOLVED or ACCEPTED status and a nonblank resolution note.");
        Map<String,Object> row=jdbc.queryForMap("SELECT id,staging_record_id,resolution_status,resolution_note FROM exception_record WHERE exception_id=? FOR UPDATE",exceptionId);
        Instant now=Instant.now();
        jdbc.update("UPDATE exception_record SET resolution_status=?,resolution_note=?,resolved_at=? WHERE exception_id=?",status,note,ts(now),exceptionId);
        jdbc.update("INSERT INTO audit_event(audit_id,entity_type,entity_business_id,event_type,occurred_at,actor_ref,change_summary,before_value,after_value) VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb)",auditId("RESOLUTION",exceptionId+":"+now),"EXCEPTION",exceptionId,"EXCEPTION_RESOLVED",ts(now),actor,"Resolution note: "+note,mapper.valueToTree(row).toString(),mapper.createObjectNode().put("status",status).put("resolutionNote",note).put("actorRef",actor).put("resolvedAt",now.toString()).toString());
        return Map.of("exceptionId",exceptionId,"resolutionStatus",status,"resolutionNote",note,"resolvedAt",now,"actorRef",actor==null?"":actor);
    }

    @Transactional
    public Map<String,Object> release(long stagingId,String note,String actor) {
        Map<String,Object> s=jdbc.queryForMap("SELECT staging_key,payload_version,validation_status FROM staging_record WHERE id=? FOR UPDATE",stagingId);
        if(!"VALID".equals(s.get("validation_status"))) throw new IllegalStateException("Release denied: corrected payload has not passed complete revalidation.");
        Integer valid=jdbc.queryForObject("SELECT COUNT(*) FROM validation_result WHERE staging_record_id=? AND payload_version=? AND is_valid",Integer.class,stagingId,s.get("payload_version"));
        if(valid==null||valid!=1) throw new IllegalStateException("Release denied: no complete valid result exists for the current payload version.");
        Integer open=jdbc.queryForObject("SELECT COUNT(*) FROM exception_record WHERE staging_record_id=? AND resolution_status NOT IN ('RESOLVED','ACCEPTED')",Integer.class,stagingId);
        if(open!=null&&open>0) throw new IllegalStateException("Release denied: resolve the linked exception first.");
        if(note==null||note.isBlank()) throw new IllegalArgumentException("Release note is required.");
        Instant now=Instant.now();
        jdbc.update("UPDATE staging_record SET validation_status='VALID' WHERE id=?",stagingId);
        jdbc.update("UPDATE quarantined_record SET disposition='RELEASED',disposition_note=? WHERE staging_record_id=?",note,stagingId);
        jdbc.update("INSERT INTO audit_event(audit_id,entity_type,entity_business_id,event_type,occurred_at,actor_ref,change_summary) VALUES (?,?,?,?,?,?,?)",auditId("RELEASE",stagingId+":"+now),"STAGING_RECORD",Objects.toString(s.get("staging_key")),"QUARANTINE_RELEASED",ts(now),actor,"Released after current payload version passed complete validation: "+note);
        return Map.of("stagingRecordId",stagingId,"disposition","RELEASED","releasedAt",now,"actorRef",actor==null?"":actor);
    }

    private Map<String,Object> validateOne(long runId,RecordValidator v,long stagingId,int version,JsonNode payload,Instant now) {
        ValidationFinding f=v.validate(payload);
        jdbc.update("INSERT INTO validation_result(validation_run_id,staging_record_id,payload_version,is_valid,issue_type,severity,description,validated_at) VALUES (?,?,?,?,?,?,?,?)",runId,stagingId,version,f.valid(),f.issueType(),f.severity(),f.description(),ts(now));
        jdbc.update("UPDATE staging_record SET validation_status=? WHERE id=?",f.valid()?"VALID":"QUARANTINED",stagingId);
        Map<String,Object> out=new LinkedHashMap<>(); out.put("stagingRecordId",stagingId);out.put("fixtureCode",v.fixtureCode());out.put("payloadVersion",version);out.put("valid",f.valid());out.put("issueType",f.issueType());out.put("severity",f.severity());out.put("description",f.description());
        if(!f.valid()) {
            String fingerprint=sha(DETECTOR+":"+v.fixtureCode()+":"+canonical(payload)+":"+f.issueType()); String exceptionId="EX-"+fingerprint.substring(0,20).toUpperCase(Locale.ROOT);
            jdbc.update("INSERT INTO exception_record(exception_id,staging_record_id,entity_type,entity_business_id,issue_type,severity,detected_at,description,resolution_status,detector_version,fingerprint) VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?) ON CONFLICT(detector_version,fingerprint) DO NOTHING",exceptionId,stagingId,v.entityType(),text(payload,"entityBusinessId"),f.issueType(),f.severity(),ts(now),f.description(),DETECTOR,fingerprint);
            Long exceptionPk=jdbc.queryForObject("SELECT id FROM exception_record WHERE detector_version=? AND fingerprint=?",Long.class,DETECTOR,fingerprint);
            String quarantineId="Q-"+sha(v.fixtureCode()).substring(0,24).toUpperCase(Locale.ROOT);
            jdbc.update("INSERT INTO quarantined_record(quarantine_id,staging_record_id,quarantined_at,disposition,disposition_note) VALUES (?,?,?,'HELD',?) ON CONFLICT(staging_record_id) DO NOTHING",quarantineId,stagingId,ts(now),"Held by "+DETECTOR+" pending correction and revalidation.");
            out.put("exceptionId",jdbc.queryForObject("SELECT exception_id FROM exception_record WHERE id=?",String.class,exceptionPk));out.put("quarantineId",quarantineId);out.put("quarantineDisposition","HELD");
        }
        return out;
    }

    private JsonNode fixture(String code) {
        try {
            Map<String,Object> p=new LinkedHashMap<>(); p.put("entityBusinessId",code.replace("ANOMALY_","FIX-"));
            switch(code) {
                case "ANOMALY_SHIPMENT_NO_PO" -> {p.put("purchaseOrderId","PO-2099-999999");p.put("shipmentId","FIX-SHIP-NO-PO");}
                case "ANOMALY_DELIVERY_BEFORE_DISPATCH" -> {Map<String,String> sh=jdbc.queryForObject("SELECT shipment_id, dispatched_at FROM shipment WHERE dispatched_at IS NOT NULL ORDER BY shipment_id LIMIT 1",(rs,n)->Map.of("shipment_id",rs.getString(1),"dispatched_at",rs.getTimestamp(2).toInstant().toString()));p.put("shipmentId",sh.get("shipment_id"));Instant d=Instant.parse(sh.get("dispatched_at"));p.put("dispatchedAt",d.toString());p.put("occurredAt",d.minusSeconds(3600).toString());}
                case "ANOMALY_FUTURE_SCORE_INPUT" -> {p.put("scoreRunAsOf","2026-10-22T23:59:59Z");p.put("eventAt","2026-10-23T00:00:00Z");}
                case "ANOMALY_RISK_MISSING_SOURCE_OR_TIME" -> {p.put("source",null);p.put("startsAt",null);}
                case "ANOMALY_CROSS_SUPPLIER_SITE" -> {List<Map<String,Object>> pairs=jdbc.queryForList("SELECT DISTINCT ON (p.supplier_id) p.supplier_id,s.site_id FROM supplier p JOIN site s ON s.supplier_id=p.id ORDER BY p.supplier_id,s.site_id");if(pairs.size()<2)throw new IllegalStateException("Two supplier/site pairs are required for ownership fixture");p.put("supplierId",pairs.get(0).get("supplier_id"));p.put("siteId",pairs.get(1).get("site_id"));}
                default -> throw new IllegalArgumentException("Unknown fixture "+code);
            }
            return mapper.valueToTree(p);
        } catch(Exception e) { if(e instanceof IllegalStateException) throw (IllegalStateException)e; throw new IllegalStateException("Cannot build fixture "+code+": "+safeMessage(e),e); }
    }
    private JsonNode readJson(Object v){try{return mapper.readTree(Objects.toString(v));}catch(Exception e){throw new IllegalStateException("Stored staging payload is not valid JSON.",e);}}
    private String canonical(JsonNode n){try{return mapper.writeValueAsString(canonicalNode(n));}catch(Exception e){throw new IllegalStateException(e);}}
    private Object canonicalNode(JsonNode n){if(n.isObject()){Map<String,Object> sorted=new TreeMap<>();n.fields().forEachRemaining(e->sorted.put(e.getKey(),canonicalNode(e.getValue())));return sorted;}if(n.isArray()){List<Object> values=new ArrayList<>();n.forEach(v->values.add(canonicalNode(v)));return values;}return n;}
    private static String text(JsonNode n,String k){JsonNode v=n.get(k);return v==null||v.isNull()?null:v.asText();}
    private static String sha(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String auditId(String type,String value){return "AUD-"+sha(type+":"+value).substring(0,24).toUpperCase(Locale.ROOT);}
    private static Timestamp ts(Instant instant){return Timestamp.from(instant);}
    private static String safeMessage(Exception e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m.substring(0,Math.min(m.length(),900));}
}
