package com.yottaflex.reliability.scoring;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs before application context refresh so Flyway cannot touch a non-test database. */
public final class Phase4TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern URL_DATABASE = Pattern.compile("jdbc:postgresql://[^/]+/([^?]+)(?:\\?.*)?");
    private static final String APPROVED_DATABASE = "yottaflex_phase4_test";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        String databaseName = environment.getProperty("DB_NAME", APPROVED_DATABASE);
        String url = environment.getProperty("spring.datasource.url", "");
        Matcher matcher = URL_DATABASE.matcher(url);
        if (!APPROVED_DATABASE.equals(databaseName) || !matcher.matches() || !APPROVED_DATABASE.equals(matcher.group(1))) {
            throw new IllegalStateException("Phase 4 PostgreSQL tests may connect only to " + APPROVED_DATABASE + "; refusing application startup.");
        }
    }
}
