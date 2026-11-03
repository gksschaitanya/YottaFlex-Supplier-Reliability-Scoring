package com.yottaflex.reliability.generation.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class SyntheticReferenceData {

    private SyntheticReferenceData() {
    }

    public static final List<String> CATEGORIES = List.of("ELECTRONICS", "INDUSTRIAL", "PACKAGING", "CHEMICALS", "LOGISTICS");
    public static final List<Region> REGIONS = List.of(
            new Region("US-MW", "US", "America/Chicago", "Milwaukee", "Wisconsin"),
            new Region("US-SE", "US", "America/New_York", "Charlotte", "North Carolina"),
            new Region("CA-ON", "CA", "America/Toronto", "Toronto", "Ontario"),
            new Region("DE-BY", "DE", "Europe/Berlin", "Munich", "Bavaria"),
            new Region("NL-ZH", "NL", "Europe/Amsterdam", "Rotterdam", "South Holland"),
            new Region("JP-13", "JP", "Asia/Tokyo", "Tokyo", "Tokyo"));
    public static final List<String> REQUIREMENT_CODES = List.of(
            "BUSINESS_LICENSE", "TAX_REGISTRATION", "QUALITY_CERT", "ENVIRONMENT_CERT",
            "WORKPLACE_SAFETY", "DATA_SECURITY", "ANTI_BRIBERY", "SANCTIONS_SCREEN",
            "INSURANCE_COVER", "ORIGIN_DECLARATION", "TRACEABILITY", "CONTINUITY_PLAN");

    public record Region(String code, String country, String timezone, String city, String state) {
    }

    public static String requirementName(String code) {
        return Arrays.stream(code.toLowerCase(Locale.ROOT).split("_"))
            .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
            .collect(Collectors.joining(" "));
    }

    public static LocalDate catalogEffectiveDate() {
        return LocalDate.of(2020, 1, 1);
    }
}
