package com.yottaflex.reliability.generation.application;

import com.yottaflex.reliability.generation.api.GenerationArtifactResponse;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface GenerationArtifactMapper {

    GenerationArtifactResponse toResponse(GenerationArtifactEntity entity);
}
