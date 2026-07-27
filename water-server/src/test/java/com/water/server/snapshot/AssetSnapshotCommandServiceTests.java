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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AssetSnapshotCommandServiceTests {

    private static final Path DB_PATH = createTempDbPath();

    @Autowired
    private AssetSnapshotCommandService assetSnapshotCommandService;

    @Autowired
    private AssetSnapshotQueryService assetSnapshotQueryService;

    @Autowired
    private AssetAccountCommandService assetAccountCommandService;

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
                new BigDecimal("10"),
                new BigDecimal("88"),
                new BigDecimal("5.5"),
                new BigDecimal("358.9"),
                "first note",
                "first remark",
                List.of(
                        new AssetSnapshotDetailUpsertRequest(null, "ALIPAY", new BigDecimal("20.5"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(null, "DEFAULT_BANK_CARD", new BigDecimal("80.0"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(null, "US_STOCK_ACCOUNT", new BigDecimal("12.8"), null, "USD", "broker"),
                        new AssetSnapshotDetailUpsertRequest(null, "CREDIT_CARD_DUE", new BigDecimal("30"), null, "CNY", null)
                )
        ));

        assertEquals(LocalDate.of(2026, 3, 23), created.snapshotDate());
        assertEquals(new BigDecimal("100.5"), created.cashTotal());
        assertEquals(new BigDecimal("12.8"), created.investmentTotal());
        assertEquals(new BigDecimal("30"), created.liabilityTotal());
        assertEquals(new BigDecimal("113.3"), created.grossAccountValue());
        assertEquals(new BigDecimal("83.3"), created.netWorth());
        assertTrue(created.details().stream().anyMatch(detail ->
                detail.accountCode().equals("DEFAULT_BANK_CARD")
                        && Boolean.FALSE.equals(detail.computed())
                        && "MANUAL".equals(detail.amountSource())
        ));
        assertFalse(created.details().stream().anyMatch(detail -> Boolean.TRUE.equals(detail.computed())));

        AssetSnapshotResponse updated = assetSnapshotCommandService.updateSnapshot(created.id(), new AssetSnapshotUpsertRequest(
                LocalDate.of(2026, 3, 24),
                new BigDecimal("3.5"),
                new BigDecimal("1.8"),
                new BigDecimal("16"),
                new BigDecimal("98"),
                null,
                new BigDecimal("446.9"),
                "updated note",
                "updated remark",
                List.of(
                        new AssetSnapshotDetailUpsertRequest(null, "WECHAT", new BigDecimal("8.6"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(null, "HOUSING_FUND", new BigDecimal("98"), null, "CNY", "reserve"),
                        new AssetSnapshotDetailUpsertRequest(null, "INVESTMENT_LOSS", new BigDecimal("4"), null, "CNY", null)
                )
        ));

        assertEquals(LocalDate.of(2026, 3, 24), updated.snapshotDate());
        assertEquals("updated note", updated.note());
        assertEquals(new BigDecimal("106.6"), updated.cashTotal());
        assertEquals(new BigDecimal("-4"), updated.investmentTotal());
        assertEquals(new BigDecimal("4"), updated.liabilityTotal());
        assertEquals(new BigDecimal("106.6"), updated.grossAccountValue());
        assertEquals(new BigDecimal("102.6"), updated.netWorth());
        assertTrue(updated.details().stream().anyMatch(detail ->
                detail.accountCode().equals("HOUSING_FUND")
                        && Boolean.FALSE.equals(detail.computed())
                        && "MANUAL".equals(detail.amountSource())
                        && "reserve".equals(detail.remark())
        ));

        assetSnapshotCommandService.deleteSnapshot(created.id());

        assertFalse(assetSnapshotQueryService.findAllSnapshots().stream()
                .anyMatch(snapshot -> snapshot.id().equals(created.id())));
    }

    @Test
    void savesSameCodeGroupAndChildByAccountId() {
        AssetAccountOptionDto group = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "MIRROR_PAY",
                "Mirror Pay",
                "CASH",
                "EWALLET",
                null,
                true,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1600,
                true,
                null
        ));
        AssetAccountOptionDto child = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "MIRROR_PAY",
                "Mirror Pay",
                "CASH",
                "EWALLET",
                group.id(),
                false,
                "ASSET",
                "HKD",
                null,
                null,
                null,
                1601,
                true,
                List.of("same-code")
        ));

        AssetSnapshotResponse created = assetSnapshotCommandService.createSnapshot(new AssetSnapshotUpsertRequest(
                LocalDate.of(2026, 4, 1),
                null,
                null,
                null,
                null,
                null,
                null,
                "same code",
                null,
                List.of(
                        new AssetSnapshotDetailUpsertRequest(group.id(), group.accountCode(), new BigDecimal("12.3"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(child.id(), child.accountCode(), new BigDecimal("12.3"), null, "CNY", null)
                )
        ));

        assertEquals(new BigDecimal("12.3"), created.cashTotal());
        assertEquals(new BigDecimal("12.3"), created.grossAccountValue());
        assertTrue(created.details().stream().anyMatch(detail ->
                detail.accountId().equals(group.id())
                        && detail.accountCode().equals("MIRROR_PAY")
                        && Boolean.TRUE.equals(detail.summaryAccount())
        ));
        assertTrue(created.details().stream().anyMatch(detail ->
                detail.accountId().equals(child.id())
                        && detail.accountCode().equals("MIRROR_PAY")
                        && Boolean.FALSE.equals(detail.summaryAccount())
                        && detail.parentAccountId().equals(group.id())
        ));
    }

    @Test
    void rollsUpChildBreakdownInsteadOfUsingStaleGroupAmount() {
        AssetAccountOptionDto group = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "ROLLUP_GROUP",
                "Rollup Group",
                "CASH",
                "BANK_CARD",
                null,
                true,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1700,
                true,
                null
        ));
        AssetAccountOptionDto sameCodeMirror = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "ROLLUP_GROUP",
                "Rollup Group Mirror",
                "CASH",
                "BANK_CARD",
                group.id(),
                false,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1701,
                true,
                null
        ));
        AssetAccountOptionDto childA = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "ROLLUP_CHILD_A",
                "Rollup Child A",
                "CASH",
                "BANK_CARD",
                group.id(),
                false,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1702,
                true,
                null
        ));
        AssetAccountOptionDto childB = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "ROLLUP_CHILD_B",
                "Rollup Child B",
                "CASH",
                "BANK_CARD",
                group.id(),
                false,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1703,
                true,
                null
        ));

        AssetSnapshotResponse created = assetSnapshotCommandService.createSnapshot(new AssetSnapshotUpsertRequest(
                LocalDate.of(2026, 4, 2),
                null,
                null,
                null,
                null,
                null,
                null,
                "rollup",
                null,
                List.of(
                        new AssetSnapshotDetailUpsertRequest(group.id(), group.accountCode(), new BigDecimal("100"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(sameCodeMirror.id(), sameCodeMirror.accountCode(), new BigDecimal("100"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(childA.id(), childA.accountCode(), new BigDecimal("2"), null, "CNY", null),
                        new AssetSnapshotDetailUpsertRequest(childB.id(), childB.accountCode(), new BigDecimal("10"), null, "HKD", null)
                )
        ));

        assertEquals(new BigDecimal("10.7"), created.cashTotal());
        assertEquals(new BigDecimal("10.7"), created.grossAccountValue());
        assertEquals(new BigDecimal("10.7"), created.netWorth());
        assertTrue(created.details().stream().anyMatch(detail ->
                detail.accountId().equals(group.id())
                        && new BigDecimal("10.7").compareTo(detail.amount()) == 0
                        && Boolean.TRUE.equals(detail.computed())
                        && "ROLLED_UP".equals(detail.amountSource())
        ));
    }

    private static Path createTempDbPath() {
        try {
            return Files.createTempFile("water-server-test", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test db", exception);
        }
    }
}
