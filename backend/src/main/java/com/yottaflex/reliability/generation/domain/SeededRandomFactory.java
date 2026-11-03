package com.yottaflex.reliability.generation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

public final class SeededRandomFactory {

    private SeededRandomFactory() {
    }

    public static SplittableRandom forRecord(long seed, String namespace, long recordSequence) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Random stream namespace is required");
        }
        if (recordSequence < 0) {
            throw new IllegalArgumentException("Record sequence cannot be negative");
        }
        return new SplittableRandom(mix(seed, namespace, recordSequence));
    }

    private static long mix(long seed, String namespace, long sequence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(namespace.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(sequence).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            long value = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | (hash[index] & 0xffL);
            }
            return value;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
