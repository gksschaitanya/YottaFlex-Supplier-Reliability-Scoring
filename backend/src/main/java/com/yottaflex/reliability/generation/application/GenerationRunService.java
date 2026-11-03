package com.yottaflex.reliability.generation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yottaflex.reliability.generation.api.GenerationRunRequest;
import com.yottaflex.reliability.generation.api.GenerationRunResponse;
import com.yottaflex.reliability.generation.domain.BusinessIdentifiers;
import com.yottaflex.reliability.generation.domain.DatasetScale;
import com.yottaflex.reliability.generation.domain.GenerationCounts;
import com.yottaflex.reliability.generation.domain.GenerationPlan;
import com.yottaflex.reliability.generation.domain.GenerationRunStatus;
import com.yottaflex.reliability.generation.infrastructure.GenerationRunLock;
import com.yottaflex.reliability.generation.infrastructure.GenerationTargetGuard;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunEntity;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GenerationRunService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationRunService.class);

    private final SyntheticGenerationProperties properties;
    private final DataGenerationRunRepository runRepository;
    private final GenerationRunLock generationRunLock;
    private final GenerationTargetGuard targetGuard;
    private final GenerationPipeline generationPipeline;
    private final GenerationRunQueryService queryService;
    private final ObjectMapper objectMapper;
    private final Executor generationExecutor;

    public GenerationRunService(
            SyntheticGenerationProperties properties,
            DataGenerationRunRepository runRepository,
            GenerationRunLock generationRunLock,
            GenerationTargetGuard targetGuard,
            GenerationPipeline generationPipeline,
            GenerationRunQueryService queryService,
            ObjectMapper objectMapper,
            @Qualifier("generationExecutor") Executor generationExecutor) {
        this.properties = properties;
        this.runRepository = runRepository;
        this.generationRunLock = generationRunLock;
        this.targetGuard = targetGuard;
        this.generationPipeline = generationPipeline;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
        this.generationExecutor = generationExecutor;
    }

    public GenerationRunResponse start(GenerationRunRequest request) {
        if (!properties.isEnabled()) {
            throw new GenerationConflictException("Synthetic data generation is disabled by configuration");
        }
        GenerationRunLock.Lease lease;
        try {
            lease = generationRunLock.tryAcquire();
        } catch (Exception exception) {
            throw new GenerationConflictException("Unable to acquire the PostgreSQL generation lock: " + exception.getMessage());
        }
        if (lease == null) {
            throw new GenerationConflictException("Another synthetic generation run is active for this database");
        }

        boolean handedOff = false;
        try {
            recoverAbandonedRuns();
            targetGuard.requireSyntheticOnlyEmptyTarget();
            GenerationContext context = createContext(request);
            DataGenerationRunEntity run = createRun(context);
            DataGenerationRunEntity savedRun = runRepository.saveAndFlush(run);
            try {
                generationExecutor.execute(() -> execute(savedRun.getRunId(), context, lease));
                handedOff = true;
            } catch (RuntimeException exception) {
                markFailed(savedRun.getRunId(), exception);
                throw new GenerationConflictException("Unable to schedule the generation run: " + exception.getMessage());
            }
            return queryService.get(savedRun.getRunId());
        } finally {
            if (!handedOff) {
                lease.close();
            }
        }
    }

    private GenerationContext createContext(GenerationRunRequest request) {
        DatasetScale scale = request.scale() == null ? properties.getDefaultScale() : request.scale();
        long seed = request.seed() == null ? properties.getSeed() : request.seed();
        LocalDate referenceDate = request.referenceDate() == null ? properties.getReferenceDate() : request.referenceDate();
        int batchSize = request.batchSize() == null ? properties.getBatchSize() : request.batchSize();
        if (batchSize > properties.getMaximumBatchSize()) {
            throw new IllegalArgumentException("Batch size cannot exceed " + properties.getMaximumBatchSize());
        }
        GenerationCounts counts = scale == DatasetScale.FULL
                ? GenerationPlan.full()
                : GenerationPlan.starter(properties.getStarter().getSuppliers(),
                        properties.getStarter().getPurchaseOrders(), properties.getStarter().getShipments());
        long runSequence = runRepository.count() + 1;
        if (runSequence > 999_999) {
            throw new IllegalStateException("Generation run identifier sequence limit exceeded");
        }
        String runId = BusinessIdentifiers.generationRun(referenceDate.getYear(), runSequence);
        return new GenerationContext(0L, runId, seed, referenceDate, scale, counts, batchSize);
    }

    private DataGenerationRunEntity createRun(GenerationContext context) {
        JsonNode configuration = configurationNode(context);
        DataGenerationRunEntity run = new DataGenerationRunEntity();
        run.setRunId(context.runId());
        run.setSeed(context.seed());
        run.setScale(context.scale());
        run.setGeneratorVersion(properties.getGeneratorVersion());
        run.setReferenceDate(context.referenceDate());
        run.setConfiguration(configuration);
        run.setConfigHash(sha256(json(configuration)));
        run.setStatus(GenerationRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        return run;
    }

    private JsonNode configurationNode(GenerationContext context) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("seed", context.seed());
        configuration.put("referenceDate", context.referenceDate());
        configuration.put("scale", context.scale());
        configuration.put("generatorVersion", properties.getGeneratorVersion());
        configuration.put("batchSize", context.batchSize());
        configuration.put("businessWindowDays", 365);
        configuration.put("counts", context.counts().asOrderedMap());
        return objectMapper.valueToTree(configuration);
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize generation configuration", exception);
        }
    }

    private void execute(String runId, GenerationContext context, GenerationRunLock.Lease lease) {
        try {
            DataGenerationRunEntity run = runRepository.findByRunId(runId)
                    .orElseThrow(() -> new GenerationRunNotFoundException(runId));
            GenerationContext persistedContext = new GenerationContext(run.getId(), context.runId(), context.seed(),
                    context.referenceDate(), context.scale(), context.counts(), context.batchSize());
            generationPipeline.generate(persistedContext, run);
            GenerationRunLifecycle.complete(run, Instant.now());
            runRepository.saveAndFlush(run);
            LOGGER.info("Completed generation run {} in {} ms", runId, run.getDurationMillis());
        } catch (RuntimeException exception) {
            markFailed(runId, exception);
        } finally {
            lease.close();
        }
    }

    private void markFailed(String runId, RuntimeException failure) {
        runRepository.findByRunId(runId).ifPresent(run -> {
            GenerationRunLifecycle.fail(run, Instant.now(), failure);
            runRepository.saveAndFlush(run);
        });
        LOGGER.error("Generation run {} failed", runId, failure);
    }

    private void recoverAbandonedRuns() {
        Instant recoveredAt = Instant.now();
        for (DataGenerationRunEntity staleRun : runRepository.findAllByStatus(GenerationRunStatus.RUNNING)) {
            GenerationRunLifecycle.fail(staleRun, recoveredAt,
                    new IllegalStateException("Recovered as abandoned after acquiring the database generation lock"));
        }
        runRepository.flush();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
