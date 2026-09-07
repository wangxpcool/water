package com.water.server.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DictionaryServiceTests {
    private static final Path DB;
    static { try { DB = Files.createTempFile("water-dictionary", ".db"); } catch (Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("spring.sql.init.mode", () -> "always");
    }
    @Autowired DictionaryService dictionary;
    @Autowired AssetSnapshotCommandService snapshots;
    @Autowired AssetSnapshotQueryService queries;
    @Autowired AssetAccountCommandService accounts;
    @Autowired AssetSchemaMigration migration;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void resetDictionary() {
        jdbc.update("DELETE FROM config_dictionary");
        dictionary.initialize();
    }

    @Test
    void importsCacheIdempotentlyWithoutOverwritingDatabase() throws Exception {
        mvc.perform(post("/api/dictionary/import-tag-adjustments").contentType("application/json")
                .content("{\"cash\":12.34,\"fixed\":-5}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/dictionary/import-tag-adjustments").contentType("application/json")
                .content("{\"cash\":99,\"new\":2}"))
                .andExpect(status().isOk());
        assertEquals(new BigDecimal("12.34"), dictionary.tagAdjustments().get("cash"));
        assertEquals(new BigDecimal("-5"), dictionary.tagAdjustments().get("fixed"));
        assertEquals(new BigDecimal("2"), dictionary.tagAdjustments().get("new"));
        mvc.perform(get("/api/dictionary")).andExpect(status().isOk());
    }

    @Test
    void validatesEntireBatchBeforeWriting() throws Exception {
        mvc.perform(put("/api/dictionary").contentType("application/json").content("""
                [{"dictType":"TAG_ADJUSTMENT","dictKey":"cash","value":5},
                 {"dictType":"EXCHANGE_RATE","dictKey":"HKD_CNY","value":0}]
                """)).andExpect(status().isBadRequest());
        assertTrue(dictionary.tagAdjustments().isEmpty());
        assertEquals(new BigDecimal("0.87"), dictionary.hkdToCnyRate());
        mvc.perform(put("/api/dictionary").contentType("application/json").content("""
                [{"dictType":"EXCHANGE_RATE","dictKey":"HKD_CNY","value":"NaN"}]
                """)).andExpect(status().isBadRequest());
    }

    @Test
    void usesConfiguredRateForNewSnapshotsAndPreservesHistoricalRate() {
        var group = accounts.createAccount(new AssetAccountUpsertRequest("FX_GROUP", "FX group", "CASH", "BANK_CARD",
                null, true, "ASSET", "HKD", null, null, null, 1, true, List.of()));
        var account = accounts.createAccount(new AssetAccountUpsertRequest("FX_HKD", "HKD account", "CASH", "BANK_CARD",
                group.id(), false, "ASSET", "HKD", null, null, null, 2, true, List.of()));
        dictionary.save(List.of(new DictionaryService.Input("EXCHANGE_RATE", "HKD_CNY", new BigDecimal("0.90"), "HKD", null),
                new DictionaryService.Input("TAG_ADJUSTMENT", "cash", new BigDecimal("5"), "cash", null)), false);
        var request = request(LocalDate.of(2031, 1, 1), account.id(), null);
        var old = snapshots.createSnapshot(request);
        assertEquals(0, new BigDecimal("90").compareTo(old.netWorth()));
        assertEquals(Map.of("cash", new BigDecimal("5")), old.tagAdjustments());
        dictionary.save(List.of(new DictionaryService.Input("EXCHANGE_RATE", "HKD_CNY", new BigDecimal("1.10"), "HKD", null)), false);
        migration.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(new BigDecimal("1.10"), dictionary.hkdToCnyRate());
        assertEquals(old, queries.findSnapshotById(old.id()));
        var edited = snapshots.updateSnapshot(old.id(), request);
        assertEquals(old.netWorth(), edited.netWorth());
        assertEquals(old.hkdToCnyRate(), edited.hkdToCnyRate());
        var next = snapshots.createSnapshot(request(LocalDate.of(2031, 1, 2), account.id(), null));
        assertEquals(0, new BigDecimal("110").compareTo(next.netWorth()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () ->
                snapshots.createSnapshot(request(LocalDate.of(2031, 1, 3), account.id(), new BigDecimal("0.90"))));
    }

    private AssetSnapshotUpsertRequest request(LocalDate date, long account, BigDecimal expectedRate) {
        return new AssetSnapshotUpsertRequest(date, null, null, null, null, null, null, null, null,
                List.of(new AssetSnapshotDetailUpsertRequest(account, null, new BigDecimal("100"), null, "HKD", null)), null, expectedRate);
    }
}
