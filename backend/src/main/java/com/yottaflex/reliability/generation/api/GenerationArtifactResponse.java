package com.yottaflex.reliability.generation.api;

public record GenerationArtifactResponse(
        String artifactName,
        long rowCount,
        String sha256,
        String schemaVersion) {
}
