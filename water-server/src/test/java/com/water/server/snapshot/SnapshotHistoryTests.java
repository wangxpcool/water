package com.water.server.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SnapshotHistoryTests {
    static final Path DB;
    static { try { DB = Files.createTempFile("water-history", ".db"); } catch (Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("spring.sql.init.mode", () -> "always");
    }
    @Autowired AssetSnapshotCommandService commands;
    @Autowired AssetSnapshotQueryService queries;
    @Autowired AssetAccountCommandService accounts;
    @Autowired AssetSchemaMigration migration;
    @Autowired JdbcTemplate jdbc;

    private AssetAccountOptionDto account(String code, boolean group, Long parent) {
        return accounts.createAccount(new AssetAccountUpsertRequest(code, code, "INVESTMENT", "INVESTMENT",
                parent, group, "ASSET", "CNY", null, null, null, 1, true, List.of("original")));
    }
    private AssetSnapshotUpsertRequest request(LocalDate date, List<AssetSnapshotDetailUpsertRequest> details) {
        return new AssetSnapshotUpsertRequest(date, new BigDecimal("5"), null, null, null, null, null,
                "note", "remark", details, Map.of("cash", new BigDecimal("3")));
    }
    private AssetSnapshotDetailUpsertRequest detail(AssetAccountOptionDto a, String value) {
        return new AssetSnapshotDetailUpsertRequest(a.id(), a.accountCode(), new BigDecimal(value), null, "CNY", "kept");
    }

    @Test
    void newAccountDisabledAccountAndRestartDoNotRewriteHistory() {
        var group = account("HISTORY_GROUP", true, null);
        var old = account("HISTORY_GROUP", false, group.id());
        var date = LocalDate.of(2030, 1, 1);
        var original = commands.createSnapshot(request(date, List.of(detail(old, "100"))));
        var added = account("HISTORY_NEW_BROKER", false, group.id());
        jdbc.update("UPDATE asset_account SET enabled = 0, group_id = NULL, category_group = 'CASH', tags = 'changed' WHERE id = ?", old.id());
        jdbc.update("UPDATE asset_account_group SET category_group = 'CASH' WHERE id = ?", -group.id());
        var loaded = queries.findSnapshotById(original.id());
        assertEquals(original, loaded);
        assertFalse(loaded.details().stream().anyMatch(d -> d.accountId().equals(added.id())));

        var saved = commands.updateSnapshot(original.id(), request(date, List.of(detail(old, "100"))));
        assertEquals(0, new BigDecimal("100").compareTo(saved.netWorth()));
        assertEquals(0, new BigDecimal("100").compareTo(saved.investmentTotal()));
        assertTrue(saved.details().stream().anyMatch(d -> d.accountId().equals(old.id()) && d.parentAccountId().equals(group.id()) && d.tags().equals(List.of("original"))));
        assertEquals(Map.of("cash", new BigDecimal("3")), saved.tagAdjustments());
        migration.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(saved, queries.findSnapshotById(saved.id()));
    }

    @Test
    void newBlankChildDoesNotReplaceExistingMirrorBalance() {
        var group = account("BLANK_GROUP", true, null);
        var old = account("BLANK_GROUP", false, group.id());
        var historical = commands.createSnapshot(request(LocalDate.of(2030, 1, 31), List.of(detail(old, "100"))));
        var added = account("BLANK_NEW", false, group.id());
        var result = commands.createSnapshot(request(LocalDate.of(2030, 2, 1), List.of(detail(old, "100"))));
        assertEquals(0, new BigDecimal("100").compareTo(result.netWorth()));
        assertEquals(2, result.details().size());
        var next = commands.createSnapshot(request(LocalDate.of(2030, 2, 2), List.of(detail(old, "100"), detail(added, "20"))));
        assertEquals(0, new BigDecimal("120").compareTo(next.netWorth()));
        assertEquals(historical, queries.findSnapshotById(historical.id()));
    }

    @Test
    void failedDetailWriteRollsBackSnapshotAndContext() {
        var a = account("ROLLBACK_ACCOUNT", false, null);
        var date = LocalDate.of(2030, 3, 1);
        var original = commands.createSnapshot(request(date, List.of(detail(a, "100"))));
        jdbc.execute("CREATE TRIGGER reject_test_amount BEFORE INSERT ON asset_snapshot_detail WHEN NEW.amount = 999 BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        try {
            assertThrows(RuntimeException.class, () -> commands.updateSnapshot(original.id(), request(date, List.of(detail(a, "999")))));
            assertEquals(original, queries.findSnapshotById(original.id()));
        } finally { jdbc.execute("DROP TRIGGER reject_test_amount"); }
        assertEquals(original, queries.findSnapshotByDate(date));
        assertTrue(queries.findAllSnapshots(false).stream().allMatch(s -> s.details().isEmpty()));
    }
}
