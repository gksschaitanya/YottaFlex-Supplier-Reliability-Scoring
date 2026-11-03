package com.yottaflex.reliability.generation.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import com.yottaflex.reliability.generation.domain.GenerationRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataGenerationRunRepository extends JpaRepository<DataGenerationRunEntity, Long> {

    Optional<DataGenerationRunEntity> findByRunId(String runId);

    long countByStatus(GenerationRunStatus status);

    List<DataGenerationRunEntity> findAllByStatus(GenerationRunStatus status);
}
