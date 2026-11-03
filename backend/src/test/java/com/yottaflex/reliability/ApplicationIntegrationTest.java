package com.yottaflex.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("supplier_reliability")
            .withUsername("postgres")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void startsConnectsToPostgresAppliesAllMigrationsAndServesHealthEndpoint() throws Exception {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);

        Integer applicationTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, Integer.class);
        assertThat(applicationTables).isGreaterThanOrEqualTo(33);

        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(appliedMigrations).isEqualTo(8);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compliance_requirement_policy_history", Integer.class)).isZero();
        jdbcTemplate.update("""
                INSERT INTO compliance_requirement(requirement_code,name,severity,applicability_code,critical,status,effective_from,effective_to)
                VALUES ('BUSINESS_LICENSE','Business license','HIGH','SUPPLIER',FALSE,'ACTIVE','2020-01-01','2020-12-31')
                """);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compliance_requirement_policy_history WHERE requirement_id=(SELECT id FROM compliance_requirement WHERE requirement_code='BUSINESS_LICENSE') AND effective_from='2020-01-01' AND effective_to='2020-12-31'", Integer.class)).isEqualTo(1);
        jdbcTemplate.update("""
                INSERT INTO supplier(supplier_id,name,category_code,region_code,country_code,status)
                VALUES ('SUP-999999','Bootstrap test supplier','TEST','TEST','US','ACTIVE')
                """);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compliance_scope_requirement sr JOIN compliance_scope_history h ON h.id=sr.scope_history_id JOIN compliance_requirement r ON r.id=sr.requirement_id WHERE h.supplier_id=(SELECT id FROM supplier WHERE supplier_id='SUP-999999') AND r.requirement_code='BUSINESS_LICENSE'", Integer.class)).isEqualTo(1);
        jdbcTemplate.update("""
                INSERT INTO site(site_id,supplier_id,site_code,name,address_line1,city,region_code,country_code,timezone,status,effective_from)
                VALUES ('SITE-SUP-999999-01',(SELECT id FROM supplier WHERE supplier_id='SUP-999999'),'01','Bootstrap site','1 Test Way','Test City','TEST','US','UTC','ACTIVE','2026-01-01')
                """);
        jdbcTemplate.update("""
                INSERT INTO compliance_requirement(requirement_code,name,severity,applicability_code,critical,status,effective_from)
                VALUES ('WORKPLACE_SAFETY','Workplace safety','HIGH','SUPPLIER_OR_SITE',FALSE,'ACTIVE','2020-01-01')
                """);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compliance_scope_requirement sr JOIN compliance_scope_history h ON h.id=sr.scope_history_id JOIN compliance_requirement r ON r.id=sr.requirement_id WHERE h.scope_type='SITE' AND h.site_id=(SELECT id FROM site WHERE site_id='SITE-SUP-999999-01') AND r.requirement_code='WORKPLACE_SAFETY'", Integer.class)).isEqualTo(1);

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
