package com.water.server.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AssetAccountCommandServiceTests {

    private static final Path DB_PATH = createTempDbPath();

    @Autowired
    private AssetAccountCommandService assetAccountCommandService;

    @Autowired
    private AssetSnapshotQueryService assetSnapshotQueryService;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH.toAbsolutePath());
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Test
    void createsUpdatesAndDeletesUnusedAccount() {
        AssetAccountOptionDto created = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "TEST_CASH",
                "测试现金账户",
                "EWALLET",
                "ASSET",
                "CNY",
                "测试机构",
                null,
                "临时账户",
                999,
                true
        ));

        assertEquals("TEST_CASH", created.accountCode());

        AssetAccountOptionDto updated = assetAccountCommandService.updateAccount(created.id(), new AssetAccountUpsertRequest(
                "TEST_CASH_2",
                "测试现金账户2",
                "BANK_CARD",
                "ASSET",
                "CNY",
                "测试银行",
                "自己",
                "已更新",
                1001,
                false
        ));

        assertEquals("TEST_CASH_2", updated.accountCode());
        assertEquals(false, updated.enabled());

        assetAccountCommandService.deleteAccount(created.id());

        assertThrows(ResponseStatusException.class, () -> assetSnapshotQueryService.findAccountById(created.id()));
    }

    private static Path createTempDbPath() {
        try {
            return Files.createTempFile("water-account-test", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test db", exception);
        }
    }
}
