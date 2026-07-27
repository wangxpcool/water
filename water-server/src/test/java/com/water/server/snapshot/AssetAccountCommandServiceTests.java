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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void createsUpdatesAndDeletesUnusedChildAccountWithTags() {
        AssetAccountOptionDto group = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "TEST_GROUP",
                "Test Group",
                "CASH",
                "BANK_CARD",
                null,
                true,
                "ASSET",
                "CNY",
                "Test Institution",
                null,
                "Temporary group",
                900,
                true,
                null
        ));

        AssetAccountOptionDto created = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "TEST_CASH",
                "Test Cash Account",
                "CASH",
                "EWALLET",
                group.id(),
                false,
                "ASSET",
                "CNY",
                "Test Institution",
                null,
                "Temporary account",
                999,
                true,
                List.of("daily", "rmb")
        ));

        assertEquals("TEST_CASH", created.accountCode());
        assertEquals(group.id(), created.parentAccountId());
        assertEquals(List.of("daily", "rmb"), created.tags());

        AssetAccountOptionDto updated = assetAccountCommandService.updateAccount(created.id(), new AssetAccountUpsertRequest(
                "TEST_CASH_2",
                "Test Cash Account 2",
                "CASH",
                "BANK_CARD",
                group.id(),
                false,
                "ASSET",
                "CNY",
                "Test Bank",
                "Self",
                "Updated",
                1001,
                false,
                List.of("bank", "backup", "bank")
        ));

        assertEquals("TEST_CASH_2", updated.accountCode());
        assertEquals(false, updated.enabled());
        assertEquals(List.of("bank", "backup"), updated.tags());

        assetAccountCommandService.deleteAccount(created.id());

        assertThrows(ResponseStatusException.class, () -> assetSnapshotQueryService.findAccountById(created.id()));
    }

    @Test
    void rejectsInvalidAccountKindConversions() {
        AssetAccountOptionDto group = assetAccountCommandService.createAccount(new AssetAccountUpsertRequest(
                "TEST_KIND_GROUP",
                "Test Kind Group",
                "CASH",
                "SUMMARY",
                null,
                true,
                "ASSET",
                "CNY",
                null,
                null,
                null,
                1200,
                true,
                null
        ));

        assertTrue(group.id() < 0);
        assertThrows(ResponseStatusException.class, () -> assetAccountCommandService.updateAccount(
                group.id(),
                new AssetAccountUpsertRequest(
                        "TEST_KIND_GROUP",
                        "Test Kind Group",
                        "CASH",
                        "SUMMARY",
                        null,
                        false,
                        "ASSET",
                        "CNY",
                        null,
                        null,
                        null,
                        1200,
                        true,
                        null
                )
        ));
    }

    private static Path createTempDbPath() {
        try {
            return Files.createTempFile("water-account-test", ".db");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create test db", exception);
        }
    }
}
