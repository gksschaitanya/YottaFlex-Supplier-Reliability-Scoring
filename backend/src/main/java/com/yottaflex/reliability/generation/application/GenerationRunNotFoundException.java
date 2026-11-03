package com.yottaflex.reliability.generation.application;

public class GenerationRunNotFoundException extends RuntimeException {

    public GenerationRunNotFoundException(String runId) {
        super("Generation run not found: " + runId);
    }
}
