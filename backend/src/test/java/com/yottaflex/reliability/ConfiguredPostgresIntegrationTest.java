package com.yottaflex.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.flywaydb.core.Flyway;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunRepository;

@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class ConfiguredPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Flyway flyway;

        @Autowired
        private DataGenerationRunRepository generationRunRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredPostgresConnectionMigrationsMappingsRepositoriesAndHealthAreValid() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("supplier_reliability");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo(System.getenv().getOrDefault("DB_USERNAME", "postgres"));
        assertThat(flyway.info().applied()).hasSize(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class)).isEqualTo(33);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = 'public' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE contype = 'f' AND cardinality(conkey) > 1
                """, Integer.class)).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE schemaname = 'public'
                """, Integer.class)).isGreaterThan(33);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = 'public' AND constraint_type = 'UNIQUE'
                """, Integer.class)).isPositive();
        assertThat(generationRunRepository.count()).isZero();
        assertThat(entityManager.getMetamodel().getEntities()).hasSize(2);

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
