package com.yottaflex.reliability.generation.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;

import com.yottaflex.reliability.generation.application.GeneratedRow;
import com.yottaflex.reliability.generation.application.GenerationArtifactSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DeterministicBatchWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeterministicBatchWriter.class);
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DeterministicBatchWriter(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public GenerationArtifactSummary write(
            String artifactName,
            String sql,
            Iterable<GeneratedRow> rows,
            long expectedCount,
            int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Batch size must be at least one");
        }
        MessageDigest digest = sha256();
        Iterator<GeneratedRow> iterator = rows.iterator();
        long rowCount = 0;
        long batchNumber = 0;

        while (iterator.hasNext()) {
            List<GeneratedRow> batch = new ArrayList<>(batchSize);
            while (iterator.hasNext() && batch.size() < batchSize) {
                batch.add(iterator.next());
            }
            List<GeneratedRow> committedBatch = List.copyOf(batch);
            transactionTemplate.executeWithoutResult(status -> jdbcTemplate.batchUpdate(
                    sql,
                    committedBatch,
                    committedBatch.size(),
                    bindValues()));
            committedBatch.forEach(row -> {
                digest.update(row.canonicalValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            rowCount += committedBatch.size();
            batchNumber++;
            LOGGER.info("Generated {} batch {} ({} rows so far)", artifactName, batchNumber, rowCount);
        }

        if (rowCount != expectedCount) {
            throw new IllegalStateException("Generated " + rowCount + " " + artifactName + " rows; expected " + expectedCount);
        }
        synchronizeIdentitySequence(artifactName);
        return new GenerationArtifactSummary(artifactName, rowCount, HexFormat.of().formatHex(digest.digest()));
    }

    public GenerationArtifactSummary fingerprintOnly(
            String artifactName,
            Iterable<GeneratedRow> rows,
            long expectedCount) {
        MessageDigest digest = sha256();
        long rowCount = 0;
        for (GeneratedRow row : rows) {
            digest.update(row.canonicalValue().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            rowCount++;
        }
        if (rowCount != expectedCount) {
            throw new IllegalStateException("Fingerprinted " + rowCount + " " + artifactName + " rows; expected " + expectedCount);
        }
        synchronizeIdentitySequence(artifactName);
        return new GenerationArtifactSummary(artifactName, rowCount, HexFormat.of().formatHex(digest.digest()));
    }

    private void synchronizeIdentitySequence(String tableName) {
        jdbcTemplate.queryForObject("""
                SELECT setval(pg_get_serial_sequence(?, 'id'), COALESCE(MAX(id), 1), COUNT(*) > 0)
                FROM %s
                """.formatted(tableName), Long.class, tableName);
    }

    private ParameterizedPreparedStatementSetter<GeneratedRow> bindValues() {
        return (preparedStatement, row) -> {
            Object[] values = row.values();
            for (int index = 0; index < values.length; index++) {
                preparedStatement.setObject(index + 1, values[index]);
            }
        };
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
