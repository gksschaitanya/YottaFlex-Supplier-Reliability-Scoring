package com.yottaflex.reliability.validation.application;

public record ValidationFinding(boolean valid, String issueType, String severity, String description) {
    public static ValidationFinding valid(String description) { return new ValidationFinding(true, null, null, description); }
    public static ValidationFinding invalid(String issue, String severity, String description) { return new ValidationFinding(false, issue, severity, description); }
}
