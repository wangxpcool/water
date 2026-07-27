package com.water.server.snapshot;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration
public class SqliteDataSourceConfig {

    private static final String SQLITE_URL_PREFIX = "jdbc:sqlite:";

    @Bean
    public DataSource dataSource(Environment environment) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setJdbcUrl(resolveJdbcUrl(environment));
        return dataSource;
    }

    private String resolveJdbcUrl(Environment environment) {
        String configuredUrl = blankToNull(environment.getProperty("spring.datasource.url"));
        if (configuredUrl != null) {
            return configuredUrl;
        }

        String configuredPath = blankToNull(System.getenv("WATER_DB_PATH"));
        if (configuredPath != null) {
            return SQLITE_URL_PREFIX + Path.of(configuredPath).toAbsolutePath().normalize();
        }

        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                workingDirectory.resolve("water.db"),
                workingDirectory.resolve("..").normalize().resolve("water.db")
        );

        return SQLITE_URL_PREFIX + candidates.stream()
                .filter(Files::exists)
                .findFirst()
                .orElse(workingDirectory.resolve("water.db"))
                .toAbsolutePath()
                .normalize();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
