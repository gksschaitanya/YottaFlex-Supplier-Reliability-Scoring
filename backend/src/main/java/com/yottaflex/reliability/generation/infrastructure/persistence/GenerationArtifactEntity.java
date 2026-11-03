package com.yottaflex.reliability.generation.infrastructure.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "generation_artifact", uniqueConstraints = {
        @UniqueConstraint(name = "uk_generation_artifact_run_name", columnNames = {"generation_run_id", "artifact_name"})
})
public class GenerationArtifactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false, unique = true, length = 120)
    private String artifactId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generation_run_id", nullable = false)
    private DataGenerationRunEntity generationRun;

    @Column(name = "artifact_name", nullable = false, length = 120)
    private String artifactName;

    @Column(name = "schema_version", nullable = false, length = 40)
    private String schemaVersion;

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
