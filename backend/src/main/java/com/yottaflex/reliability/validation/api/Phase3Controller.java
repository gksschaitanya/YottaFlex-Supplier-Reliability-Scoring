package com.yottaflex.reliability.validation.api;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.reliability.validation.application.StagingValidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class Phase3Controller {
    private final StagingValidationService service;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public Phase3Controller(StagingValidationService service, JdbcTemplate jdbc,ObjectMapper mapper){this.service=service;this.jdbc=jdbc;this.mapper=mapper;}

    @PostMapping("/validation/runs") public Map<String,Object> runFixtures(){return service.runFixtures();}
    @GetMapping("/validation/runs") public List<Map<String,Object>> validationRuns(){return jdbc.queryForList("SELECT validation_run_id AS \"validationRunId\",run_status AS status,detector_version AS \"detectorVersion\",started_at AS \"startedAt\",completed_at AS \"completedAt\",failure_reason AS \"failureReason\" FROM validation_run ORDER BY started_at DESC");}

    @GetMapping("/exceptions") public Map<String,Object> exceptions(@RequestParam(required=false) String status,@RequestParam(required=false) String severity,@RequestParam(required=false) String issueType,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){
        int safePage=Math.max(0,page),safeSize=Math.min(100,Math.max(1,size));String where=" WHERE (?::varchar IS NULL OR e.resolution_status=?) AND (?::varchar IS NULL OR e.severity=?) AND (?::varchar IS NULL OR e.issue_type=?)";
        Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM exception_record e"+where,Integer.class,status,status,severity,severity,issueType,issueType);
        List<Map<String,Object>> content=jdbc.queryForList("SELECT e.exception_id AS \"exceptionId\",e.entity_type AS \"entityType\",e.entity_business_id AS \"entityBusinessId\",e.issue_type AS \"issueType\",e.severity,e.detected_at AS \"detectedAt\",e.description,e.resolution_status AS \"resolutionStatus\",q.quarantine_id AS \"quarantineId\",q.disposition AS \"quarantineDisposition\",e.staging_record_id AS \"stagingRecordId\" FROM exception_record e LEFT JOIN quarantined_record q ON q.staging_record_id=e.staging_record_id"+where+" ORDER BY e.detected_at DESC,e.exception_id LIMIT ? OFFSET ?",status,status,severity,severity,issueType,issueType,safeSize,(long)safePage*safeSize);
        return Map.of("content",content,"page",safePage,"size",safeSize,"totalElements",total==null?0:total,"totalPages",total==null?0:(int)Math.ceil(total/(double)safeSize));
    }
    @GetMapping("/exceptions/{id}") public Map<String,Object> exception(@PathVariable String id){return payloads(jdbc.queryForMap("SELECT e.exception_id AS \"exceptionId\",e.entity_type AS \"entityType\",e.entity_business_id AS \"entityBusinessId\",e.issue_type AS \"issueType\",e.severity,e.detected_at AS \"detectedAt\",e.description,e.resolution_status AS \"resolutionStatus\",e.detector_version AS \"detectorVersion\",e.fingerprint,e.resolved_at AS \"resolvedAt\",e.resolution_note AS \"resolutionNote\",(SELECT actor_ref FROM audit_event a WHERE a.entity_type='EXCEPTION' AND a.entity_business_id=e.exception_id AND a.event_type='EXCEPTION_RESOLVED' ORDER BY a.occurred_at DESC LIMIT 1) AS \"resolutionActorRef\",(SELECT occurred_at FROM audit_event a WHERE a.entity_type='EXCEPTION' AND a.entity_business_id=e.exception_id AND a.event_type='EXCEPTION_RESOLVED' ORDER BY a.occurred_at DESC LIMIT 1) AS \"resolutionAuditedAt\",e.staging_record_id AS \"stagingRecordId\",q.quarantine_id AS \"quarantineId\",q.disposition AS \"quarantineDisposition\",q.disposition_note AS \"quarantineNote\",s.payload AS \"originalPayload\",s.corrected_payload AS \"correctedPayload\",s.payload_version AS \"payloadVersion\",s.validation_status AS \"validationStatus\" FROM exception_record e LEFT JOIN quarantined_record q ON q.staging_record_id=e.staging_record_id LEFT JOIN staging_record s ON s.id=e.staging_record_id WHERE e.exception_id=?",id));}
    @PostMapping("/exceptions/{id}/resolution") public Map<String,Object> resolve(@PathVariable String id,@Valid @RequestBody ResolutionRequest request){return service.resolve(id,request.status(),request.note(),request.actorRef());}

    @GetMapping("/quarantined-records") public Map<String,Object> quarantine(@RequestParam(required=false) String disposition,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){int p=Math.max(0,page),n=Math.min(100,Math.max(1,size));Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM quarantined_record WHERE (?::varchar IS NULL OR disposition=?)",Integer.class,disposition,disposition);List<Map<String,Object>> rows=jdbc.queryForList("SELECT q.quarantine_id AS \"quarantineId\",q.staging_record_id AS \"stagingRecordId\",s.staging_key AS \"stagingKey\",s.fixture_code AS \"fixtureCode\",s.entity_type AS \"entityType\",s.validation_status AS \"validationStatus\",s.payload_version AS \"payloadVersion\",q.quarantined_at AS \"quarantinedAt\",q.disposition,q.disposition_note AS \"dispositionNote\" FROM quarantined_record q JOIN staging_record s ON s.id=q.staging_record_id WHERE (?::varchar IS NULL OR q.disposition=?) ORDER BY q.quarantined_at DESC LIMIT ? OFFSET ?",disposition,disposition,n,(long)p*n);return Map.of("content",rows,"page",p,"size",n,"totalElements",total==null?0:total);}
    @GetMapping("/quarantined-records/{id}") public Map<String,Object> quarantineDetail(@PathVariable String id){return payloads(jdbc.queryForMap("SELECT q.quarantine_id AS \"quarantineId\",q.staging_record_id AS \"stagingRecordId\",q.quarantined_at AS \"quarantinedAt\",q.disposition,q.disposition_note AS \"dispositionNote\",s.staging_key AS \"stagingKey\",s.fixture_code AS \"fixtureCode\",s.entity_type AS \"entityType\",s.payload AS \"originalPayload\",s.corrected_payload AS \"correctedPayload\",s.payload_version AS \"payloadVersion\",s.validation_status AS \"validationStatus\" FROM quarantined_record q JOIN staging_record s ON s.id=q.staging_record_id WHERE q.quarantine_id=?",id));}
    @PutMapping("/quarantined-records/{id}/correction") public Map<String,Object> correct(@PathVariable String id,@RequestBody JsonNode body){long stagingId=jdbc.queryForObject("SELECT staging_record_id FROM quarantined_record WHERE quarantine_id=?",Long.class,id);return service.correct(stagingId,body);}
    @PostMapping("/quarantined-records/{id}/revalidate") public Map<String,Object> revalidate(@PathVariable String id){long stagingId=jdbc.queryForObject("SELECT staging_record_id FROM quarantined_record WHERE quarantine_id=?",Long.class,id);return service.revalidate(stagingId);}
    @PostMapping("/quarantined-records/{id}/release") public Map<String,Object> release(@PathVariable String id,@RequestBody ReleaseRequest request){long stagingId=jdbc.queryForObject("SELECT staging_record_id FROM quarantined_record WHERE quarantine_id=?",Long.class,id);return service.release(stagingId,request.note(),request.actorRef());}

    private Map<String,Object> payloads(Map<String,Object> row){for(String key:List.of("originalPayload","correctedPayload")){Object value=row.get(key);if(value!=null)try{row.put(key,mapper.readTree(value.toString()));}catch(Exception e){throw new IllegalStateException("Stored staged payload is invalid JSON.",e);}}return row;}

    public record ResolutionRequest(@NotBlank String status,@NotBlank String note,String actorRef){}
    public record ReleaseRequest(@NotBlank String note,String actorRef){}
}
