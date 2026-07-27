package com.water.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest
class WaterServerApplicationTests {

    private static final Path DB_PATH = createTempDbPath();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Test
    void contextLoads() {
    }

    private static Path createTempDbPath() {
        try {
            return Files.createTempFile("water-context-test", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test db", exception);
        }
    }
}
