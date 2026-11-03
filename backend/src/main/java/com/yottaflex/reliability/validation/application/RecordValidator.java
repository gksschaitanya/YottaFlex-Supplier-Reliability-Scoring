package com.yottaflex.reliability.validation.application;

import com.fasterxml.jackson.databind.JsonNode;

public interface RecordValidator {
    String fixtureCode();
    String entityType();
    String issueType();
    String description();
    ValidationFinding validate(JsonNode payload);
}
