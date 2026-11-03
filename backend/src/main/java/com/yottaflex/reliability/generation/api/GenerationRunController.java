package com.yottaflex.reliability.generation.api;

import java.util.List;

import com.yottaflex.reliability.generation.application.GenerationRunQueryService;
import com.yottaflex.reliability.generation.application.GenerationRunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/generation/runs")
public class GenerationRunController {

    private final GenerationRunService generationRunService;
    private final GenerationRunQueryService queryService;

    public GenerationRunController(GenerationRunService generationRunService, GenerationRunQueryService queryService) {
        this.generationRunService = generationRunService;
        this.queryService = queryService;
    }

    @PostMapping
    public ResponseEntity<GenerationRunResponse> start(@Valid @RequestBody GenerationRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(generationRunService.start(request));
    }

    @GetMapping("/{runId}")
    public GenerationRunResponse get(@PathVariable String runId) {
        return queryService.get(runId);
    }

    @GetMapping("/{runId}/counts")
    public GenerationCountsResponse getCounts(@PathVariable String runId) {
        return queryService.getCounts(runId);
    }

    @GetMapping("/{runId}/artifacts")
    public List<GenerationArtifactResponse> getArtifacts(@PathVariable String runId) {
        return queryService.getArtifacts(runId);
    }
}
