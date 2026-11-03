package com.yottaflex.reliability.scoring.api;

import com.yottaflex.reliability.scoring.application.ScoringService;
import com.yottaflex.reliability.scoring.application.ScoringExportService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1")
public class ScoringController {
    private final ScoringService service;
    private final ScoringExportService exports;
    public ScoringController(ScoringService service, ScoringExportService exports) { this.service = service; this.exports=exports; }

    @PostMapping("/score-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> start(@RequestHeader(value="Idempotency-Key", required=false) String key) { return service.start(key); }
    @GetMapping("/score-runs") public Map<String,Object> runs(@RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) int size) { return service.runs(page,size); }
    @GetMapping("/score-runs/{runId}") public Map<String,Object> run(@PathVariable String runId) { return service.run(runId); }
    @GetMapping("/score-runs/{runId}/status") public Map<String,Object> status(@PathVariable String runId) { return service.run(runId); }
    @GetMapping("/score-runs/{runId}/summary") public Map<String,Object> summary(@PathVariable String runId) { return service.summary(runId); }
    @GetMapping("/score-runs/{runId}/scores") public Map<String,Object> scores(@PathVariable String runId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String classification,@RequestParam(required=false) java.math.BigDecimal minScore,@RequestParam(required=false) java.math.BigDecimal maxScore,@RequestParam(required=false) String componentStatus,@RequestParam(required=false) Boolean criticalComplianceViolation,@RequestParam(required=false) String supplier,@RequestParam(defaultValue="score") String sort) { return service.scores(runId,page,size,classification,minScore,maxScore,componentStatus,criticalComplianceViolation,supplier,sort); }
    @GetMapping("/score-runs/{runId}/scores/{supplierId}") public Map<String,Object> score(@PathVariable String runId,@PathVariable String supplierId) { return service.score(runId,supplierId); }
    @GetMapping("/score-runs/{runId}/rankings") public Map<String,Object> rankings(@PathVariable String runId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String classification,@RequestParam(required=false) java.math.BigDecimal minScore,@RequestParam(required=false) java.math.BigDecimal maxScore,@RequestParam(required=false) String componentStatus,@RequestParam(required=false) Boolean criticalComplianceViolation,@RequestParam(required=false) String supplier,@RequestParam(defaultValue="score") String sort) { return service.scores(runId,page,size,classification,minScore==null?BigDecimal.ZERO:minScore,maxScore,componentStatus,criticalComplianceViolation,supplier,sort); }
    @GetMapping("/score-runs/{runId}/scores/{supplierId}/components") public Object components(@PathVariable String runId,@PathVariable String supplierId) { return service.components(runId,supplierId); }
    @GetMapping("/score-runs/{runId}/scores/{supplierId}/evidence") public Map<String,Object> evidence(@PathVariable String runId,@PathVariable String supplierId,@RequestParam(required=false) String component,@RequestParam(required=false) Boolean included,@RequestParam(required=false) String sourceEntityType,@RequestParam(defaultValue="evaluatedAt,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) { return service.evidence(runId,supplierId,component,included,sourceEntityType,sort,page,size); }
    @GetMapping("/score-runs/{runId}/scores/{supplierId}/components/{componentName}/evidence") public Map<String,Object> componentEvidence(@PathVariable String runId,@PathVariable String supplierId,@PathVariable String componentName,@RequestParam(required=false) Boolean included,@RequestParam(required=false) String sourceEntityType,@RequestParam(defaultValue="evaluatedAt,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) { return service.evidence(runId,supplierId,componentName,included,sourceEntityType,sort,page,size); }
    @GetMapping("/suppliers/{supplierId}/score-history") public Map<String,Object> history(@PathVariable String supplierId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.history(supplierId,page,size); }
    @GetMapping("/score-runs/{runId}/export") public ResponseEntity<?> export(@PathVariable String runId,
            @RequestParam(defaultValue="scores") String report,@RequestParam(defaultValue="json") String format,
            @RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="1000") @Min(1) int size) {
        ScoringExportService.Export result=exports.export(runId,report,format,page,size);
        MediaType media=MediaType.parseMediaType(result.contentType());
        return ResponseEntity.ok().contentType(media).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+result.filename()+"\"").body(result.body());
    }
}
