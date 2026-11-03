package com.yottaflex.reliability.generation.application;

import java.time.Duration;
import java.time.Instant;

import com.yottaflex.reliability.generation.domain.GenerationRunStatus;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunEntity;

public final class GenerationRunLifecycle {

    private GenerationRunLifecycle() {
    }

    public static void complete(DataGenerationRunEntity run, Instant completedAt) {
        run.setStatus(GenerationRunStatus.COMPLETED);
        run.setCompletedAt(completedAt);
        run.setDurationMillis(Duration.between(run.getStartedAt(), completedAt).toMillis());
        run.setFailureReason(null);
    }

    public static void fail(DataGenerationRunEntity run, Instant failedAt, Throwable failure) {
        run.setStatus(GenerationRunStatus.FAILED);
        run.setCompletedAt(failedAt);
        run.setDurationMillis(Duration.between(run.getStartedAt(), failedAt).toMillis());
        run.setFailureReason(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }
}
