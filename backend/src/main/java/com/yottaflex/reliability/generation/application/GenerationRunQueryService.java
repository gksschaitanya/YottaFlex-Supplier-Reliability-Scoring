package com.yottaflex.reliability.generation.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.yottaflex.reliability.generation.api.GenerationArtifactResponse;
import com.yottaflex.reliability.generation.api.GenerationCountsResponse;
import com.yottaflex.reliability.generation.api.GenerationRunResponse;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunEntity;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunRepository;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactEntity;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GenerationRunQueryService {

    private final DataGenerationRunRepository runRepository;
    private final GenerationArtifactRepository artifactRepository;
    private final GenerationArtifactMapper artifactMapper;
    private final JdbcTemplate jdbcTemplate;

    public GenerationRunQueryService(
            DataGenerationRunRepository runRepository,
            GenerationArtifactRepository artifactRepository,
            GenerationArtifactMapper artifactMapper,
            JdbcTemplate jdbcTemplate) {
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.artifactMapper = artifactMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public GenerationRunResponse get(String runId) {
        DataGenerationRunEntity run = findRun(runId);
        List<GenerationArtifactEntity> artifacts = artifactRepository.findByGenerationRun_IdOrderByArtifactName(run.getId());
        Map<String, Long> persistedCounts = persistedCounts(run, artifacts);
        return new GenerationRunResponse(
                run.getRunId(), run.getStatus(), run.getScale(), run.getSeed(), run.getReferenceDate(),
                run.getGeneratorVersion(), run.getConfigHash(), run.getStartedAt(), run.getCompletedAt(),
                run.getDurationMillis(), run.getFailureReason(), expectedCounts(run), persistedCounts,
                artifacts.stream().map(artifactMapper::toResponse).toList());
    }

    public GenerationCountsResponse getCounts(String runId) {
        DataGenerationRunEntity run = findRun(runId);
        List<GenerationArtifactEntity> artifacts = artifactRepository.findByGenerationRun_IdOrderByArtifactName(run.getId());
        return new GenerationCountsResponse(runId, expectedCounts(run), persistedCounts(run, artifacts));
    }

    public List<GenerationArtifactResponse> getArtifacts(String runId) {
        DataGenerationRunEntity run = findRun(runId);
        return artifactRepository.findByGenerationRun_IdOrderByArtifactName(run.getId()).stream()
            .map(artifactMapper::toResponse)
                .toList();
    }

    private DataGenerationRunEntity findRun(String runId) {
        return runRepository.findByRunId(runId).orElseThrow(() -> new GenerationRunNotFoundException(runId));
    }

    private Map<String, Long> expectedCounts(DataGenerationRunEntity run) {
        Map<String, Long> counts = new LinkedHashMap<>();
        run.getConfiguration().path("counts").properties()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue().asLong()));
        if (counts.isEmpty()) {
            throw new IllegalStateException("Stored generation configuration is unreadable for run " + run.getRunId());
        }
        return counts;
    }

    private Map<String, Long> persistedCounts(DataGenerationRunEntity run, List<GenerationArtifactEntity> artifacts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : expectedCounts(run).keySet()) {
            if ("compliance_requirement".equals(table)) {
                counts.put(table, artifacts.stream()
                        .filter(artifact -> table.equals(artifact.getArtifactName()))
                        .mapToLong(GenerationArtifactEntity::getRowCount)
                        .findFirst().orElse(0));
            } else {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE generation_run_id = ?",
                        Long.class,
                        run.getId());
                counts.put(table, count == null ? 0L : count);
            }
        }
        return counts;
    }

}
