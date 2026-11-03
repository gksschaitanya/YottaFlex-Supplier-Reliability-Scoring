package com.yottaflex.reliability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import com.yottaflex.reliability.generation.infrastructure.persistence.DataGenerationRunRepository;
import com.yottaflex.reliability.generation.infrastructure.persistence.GenerationArtifactRepository;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "management.health.db.enabled=false"
})
@AutoConfigureMockMvc
class ApplicationSmokeTest {

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private DataGenerationRunRepository generationRunRepository;

    @MockitoBean
    private GenerationArtifactRepository generationArtifactRepository;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private PlatformTransactionManager platformTransactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void applicationStartsAndHealthEndpointResponds() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
