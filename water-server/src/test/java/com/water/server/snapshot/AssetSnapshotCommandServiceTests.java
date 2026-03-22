package com.water.server.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class AssetSnapshotCommandServiceTests {

    private static final Path DB_PATH = createTempDbPath();

    @Autowired
    private AssetSnapshotCommandService assetSnapshotCommandService;

    @Autowired
    private AssetSnapshotQueryService assetSnapshotQueryService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Test
    void createsUpdatesAndDeletesSnapshot() {
        AssetSnapshotResponse created = assetSnapshotCommandService.createSnapshot(new AssetSnapshotUpsertRequest(
                LocalDate.of(2026, 3, 23),
                new BigDecimal("2.5"),
                new BigDecimal("1.2"),
                new BigDecimal("100.5"),
                new BigDecimal("200.4"),
                new BigDecimal("30"),
                new BigDecimal("300.9"),
                new BigDecimal("10"),
                new BigDecimal("270.9"),
                new BigDecimal("88"),
                new BigDecimal("5.5"),
                new BigDecimal("358.9"),
                "first note",
                "first remark",
                List.of(
                        new AssetSnapshotDetailUpsertRequest("ALIPAY", new BigDecimal("20.5"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest("US_STOCK_ACCOUNT", new BigDecimal("12.8"), null, "USD", "broker")
                )
        ));

        assertEquals(LocalDate.of(2026, 3, 23), created.snapshotDate());
        assertEquals(2, created.details().size());

        AssetSnapshotResponse updated = assetSnapshotCommandService.updateSnapshot(created.id(), new AssetSnapshotUpsertRequest(
                LocalDate.of(2026, 3, 24),
                new BigDecimal("3.5"),
                new BigDecimal("1.8"),
                new BigDecimal("110.5"),
                new BigDecimal("260.4"),
                new BigDecimal("22"),
                new BigDecimal("370.9"),
                new BigDecimal("16"),
                new BigDecimal("348.9"),
                new BigDecimal("98"),
                null,
                new BigDecimal("446.9"),
                "updated note",
                "updated remark",
                List.of(
                        new AssetSnapshotDetailUpsertRequest("WECHAT", new BigDecimal("8.6"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest("HOUSING_FUND", new BigDecimal("98"), null, "CNY", "reserve")
                )
        ));

        assertEquals(LocalDate.of(2026, 3, 24), updated.snapshotDate());
        assertEquals("updated note", updated.note());
        assertEquals(2, updated.details().size());
        assertEquals("WECHAT", updated.details().get(0).accountCode());

        assetSnapshotCommandService.deleteSnapshot(created.id());

        assertFalse(assetSnapshotQueryService.findAllSnapshots().stream()
                .anyMatch(snapshot -> snapshot.id().equals(created.id())));
    }

    private static Path createTempDbPath() {
        try {
            return Files.createTempFile("water-server-test", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test db", exception);
        }
    }
}
