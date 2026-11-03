package com.yottaflex.reliability.generation.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationArtifactRepository extends JpaRepository<GenerationArtifactEntity, Long> {

    List<GenerationArtifactEntity> findByGenerationRun_IdOrderByArtifactName(Long generationRunId);
}
