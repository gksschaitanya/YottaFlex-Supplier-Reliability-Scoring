package com.yottaflex.reliability.generation.application;

public record GeneratedRow(Object[] values, String canonicalValue) {

    public GeneratedRow {
        if (values == null || canonicalValue == null) {
            throw new IllegalArgumentException("Generated row values and canonical representation are required");
        }
    }
}
