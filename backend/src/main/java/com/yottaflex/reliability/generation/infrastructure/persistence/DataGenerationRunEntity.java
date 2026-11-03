package com.yottaflex.reliability.generation.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.JsonNode;
import com.yottaflex.reliability.generation.domain.DatasetScale;
import com.yottaflex.reliability.generation.domain.GenerationRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "data_generation_run")
public class DataGenerationRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 80)
    private String runId;

    @Column(nullable = false)
    private long seed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private DatasetScale scale;

    @Column(name = "generator_version", nullable = false, length = 40)
    private String generatorVersion;

    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode configuration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GenerationRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
