package com.water.server.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class UsdSnapshotTests {
    static final Path DB;
    static {
        try { DB = Files.createTempFile("water-usd", ".db"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired AssetSnapshotCommandService commands;
    @Autowired AssetSnapshotQueryService queries;
    @Autowired AssetAccountCommandService accounts;
    @Autowired DictionaryService dictionary;
    @Autowired AssetSchemaMigration migration;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetRate() {
        jdbc.update("DELETE FROM config_dictionary WHERE dict_key = 'USD_CNY'");
    }

    private void rate(String value) {
        dictionary.save(List.of(new DictionaryService.Input("EXCHANGE_RATE", "USD_CNY",
                new BigDecimal(value), "USD", null)), false);
    }

    private AssetAccountUpsertRequest definition(String code, String currency, Long group, boolean summary) {
        return new AssetAccountUpsertRequest(code, code, "INVESTMENT", "INVESTMENT",
                group, summary, "ASSET", currency, null, null, null, 1, true, List.of());
    }

    private AssetSnapshotDetailUpsertRequest detail(long id, String amount, String currency) {
        return new AssetSnapshotDetailUpsertRequest(id, null, new BigDecimal(amount), null, currency, null);
    }

    private AssetSnapshotUpsertRequest request(int day, String rate, AssetSnapshotDetailUpsertRequest... details) {
        return new AssetSnapshotUpsertRequest(LocalDate.of(2032, 1, day), null, null, null, null, null, null,
                null, null, List.of(details), null, new BigDecimal(".87"), rate == null ? null : new BigDecimal(rate));
    }

    @Test
    void reusesAccountWithoutRelabelingHistoricalCnyAndRollsUpUsdWithTransit() {
        var group = accounts.createAccount(definition("USD_HISTORY_GROUP", "CNY", null, true));
        var ibkr = accounts.createAccount(definition("USD_HISTORY_OLD", "CNY", group.id(), false));
        var old = commands.createSnapshot(request(1, null, detail(ibkr.id(), "52.1", "CNY")));
        assertNull(old.usdToCnyRate());

        accounts.updateAccount(ibkr.id(), definition("IBKR_HISTORY", "USD", group.id(), false));
        assertEquals(old, queries.findSnapshotById(old.id()));
        assertNull(queries.findAccountById(ibkr.id()).latestAmount(),
                "A historical CNY amount must not be advertised as the current account's USD balance");
        rate("6.82783496");
        var schwab = accounts.createAccount(definition("SCHWAB_HISTORY", "USD", group.id(), false));
        var transit = accounts.createAccount(definition("TRANSIT_HISTORY", "CNY", group.id(), false));
        var current = commands.createSnapshot(request(2, "6.82783496",
                detail(ibkr.id(), "16.5445138", "USD"), detail(schwab.id(), "0", "USD"), detail(transit.id(), "20", "CNY")));
        assertEquals(0, new BigDecimal("133").compareTo(current.netWorth()));
        assertEquals(0, new BigDecimal("16.5445138").compareTo(current.details().stream()
                .filter(d -> d.accountId().equals(ibkr.id())).findFirst().orElseThrow().amount()));
        assertEquals("CNY", current.details().stream().filter(d -> d.accountId().equals(group.id())).findFirst().orElseThrow().currencyCode());
        assertEquals(old, commands.updateSnapshot(old.id(), request(1, null, detail(ibkr.id(), "52.1", "CNY"))));
        migration.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(old, queries.findSnapshotById(old.id()));
        assertEquals(current, queries.findSnapshotById(current.id()));
    }

    @Test
    void freezesUsdRateAndRejectsStaleRateWithoutChangingBalances() {
        rate("7");
        var account = accounts.createAccount(definition("FROZEN_USD", "USD", null, false));
        var original = commands.createSnapshot(request(3, "7", detail(account.id(), "10", "USD")));
        rate("8");
        assertEquals(0, new BigDecimal("70").compareTo(
                commands.updateSnapshot(original.id(), request(3, "7", detail(account.id(), "10", "USD"))).netWorth()));
        assertThrows(ResponseStatusException.class,
                () -> commands.updateSnapshot(original.id(), request(3, "8", detail(account.id(), "99", "USD"))));
        assertEquals(original, queries.findSnapshotById(original.id()));
        assertThrows(ResponseStatusException.class,
                () -> commands.createSnapshot(request(4, "7", detail(account.id(), "10", "USD"))));
        var next = commands.createSnapshot(request(4, "8", detail(account.id(), "10", "USD")));
        assertEquals(0, new BigDecimal("80").compareTo(next.netWorth()));
    }

    @Test
    void requiresRateBeforeAddingUsdAndPreservesExistingCnyOnFailure() {
        var account = accounts.createAccount(definition("FIRST_USD", "CNY", null, false));
        var old = commands.createSnapshot(request(5, null, detail(account.id(), "52.1", "CNY")));
        assertThrows(ResponseStatusException.class,
                () -> commands.updateSnapshot(old.id(), request(5, null, detail(account.id(), "7.8", "USD"))));
        assertEquals(old, queries.findSnapshotById(old.id()));
        rate("6.8");
        // Even with a dictionary rate, an existing snapshot needs explicit acknowledgement of its first USD rate.
        assertThrows(ResponseStatusException.class,
                () -> commands.updateSnapshot(old.id(), request(5, null, detail(account.id(), "7.8", "USD"))));
        var converted = commands.updateSnapshot(old.id(), request(5, "6.8", detail(account.id(), "7.8", "USD")));
        assertEquals(0, new BigDecimal("53").compareTo(converted.netWorth()));
        assertEquals(new BigDecimal("6.8"), converted.usdToCnyRate());
        assertThrows(ResponseStatusException.class,
                () -> commands.createSnapshot(request(6, null, detail(account.id(), "10", "EUR"))));
        assertThrows(ResponseStatusException.class, () -> rate("0"));
    }
}
