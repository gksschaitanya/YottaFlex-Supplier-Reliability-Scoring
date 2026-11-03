package com.yottaflex.reliability.generation.application;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.LongStream;

public final class GeneratedRows {

    private GeneratedRows() {
    }

    public static Iterable<GeneratedRow> range(long count, LongFunction<GeneratedRow> factory) {
        return () -> LongStream.rangeClosed(1, count).mapToObj(factory).iterator();
    }

    public static Date sqlDate(LocalDate date) {
        return Date.valueOf(date);
    }

    public static Timestamp sqlTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    public static GeneratedRow row(Object... values) {
        Object[] defensiveCopy = Arrays.copyOf(values, values.length);
        return new GeneratedRow(defensiveCopy, canonicalize(defensiveCopy, Set.of()));
    }

    public static GeneratedRow rowWithoutLast(Object... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("At least one value is required");
        }
        return rowOmitting(new int[]{values.length - 1}, values);
    }

    public static GeneratedRow rowOmitting(int[] omittedIndices, Object... values) {
        Object[] defensiveCopy = Arrays.copyOf(values, values.length);
        Set<Integer> omitted = new HashSet<>();
        for (int index : omittedIndices) {
            if (index < 0 || index >= defensiveCopy.length) {
                throw new IllegalArgumentException("Canonical exclusion index is outside the row");
            }
            omitted.add(index);
        }
        return new GeneratedRow(defensiveCopy, canonicalize(defensiveCopy, omitted));
    }

    private static String canonicalize(Object[] values, Set<Integer> omittedIndices) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (omittedIndices.contains(index)) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append('|');
            }
            canonical.append(canonicalField(values[index]));
        }
        return canonical.toString();
    }

    private static String canonicalField(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        return value.toString();
    }
}
