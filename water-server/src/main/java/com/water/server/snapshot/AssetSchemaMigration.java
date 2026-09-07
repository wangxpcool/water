package com.water.server.snapshot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AssetSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final SnapshotContextService context;
    private final DictionaryService dictionary;
    private final AssetSnapshotQueryService queries;

    public AssetSchemaMigration(JdbcTemplate jdbcTemplate, SnapshotContextService context, AssetSnapshotQueryService queries, DictionaryService dictionary) {
        this.jdbcTemplate = jdbcTemplate;
        this.context = context;
        this.dictionary = dictionary;
        this.queries = queries;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        dictionary.initialize();
        ensureAccountGroupTable();
        ensureSnapshotGroupDetailTable();

        ensureAccountColumn("category_group", "ALTER TABLE asset_account ADD COLUMN category_group TEXT");
        ensureAccountColumn("parent_account_id", "ALTER TABLE asset_account ADD COLUMN parent_account_id INTEGER");
        ensureAccountColumn("is_summary", "ALTER TABLE asset_account ADD COLUMN is_summary INTEGER NOT NULL DEFAULT 0");
        ensureAccountColumn("group_id", "ALTER TABLE asset_account ADD COLUMN group_id INTEGER");
        ensureAccountColumn("is_subtotal", "ALTER TABLE asset_account ADD COLUMN is_subtotal INTEGER NOT NULL DEFAULT 0");
        ensureAccountColumn("tags", "ALTER TABLE asset_account ADD COLUMN tags TEXT");
        ensureSnapshotDetailColumn(
                "amount_source",
                "ALTER TABLE asset_snapshot_detail ADD COLUMN amount_source TEXT NOT NULL DEFAULT 'MANUAL'"
        );
        ensureSnapshotDetailColumn(
                "is_computed",
                "ALTER TABLE asset_snapshot_detail ADD COLUMN is_computed INTEGER NOT NULL DEFAULT 0"
        );

        context.ensureTable();
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS water_schema_version (version TEXT PRIMARY KEY)");
        boolean migrated = !jdbcTemplate.queryForList(
                "SELECT version FROM water_schema_version WHERE version = 'snapshot-history-v1'").isEmpty();
        if (migrated) return;

        backfillAccountCategoryGroup();
        migrateLegacyAccountGroups();
        ensureDefaultAccountGroups();
        migrateLegacyGroupSnapshotDetails();
        jdbcTemplate.update("""
                UPDATE asset_account SET is_subtotal = 1
                WHERE account_code = (SELECT group_code FROM asset_account_group g WHERE g.id = asset_account.group_id)
                  AND EXISTS (SELECT 1 FROM asset_account child WHERE child.group_id = asset_account.group_id AND child.id <> asset_account.id)
                """);
        // Legacy migration is structural. Stored historical totals must not be rewritten on startup.
        for (AssetSnapshotResponse snapshot : queries.findAllSnapshots()) {
            var ids = snapshot.details().stream().map(AssetSnapshotDetailDto::accountId)
                    .collect(java.util.stream.Collectors.toSet());
            context.save(snapshot.id(), queries.findAllAccounts().stream().filter(a -> ids.contains(a.id())).toList(), null);
        }
        jdbcTemplate.update("INSERT INTO water_schema_version(version) VALUES ('snapshot-history-v1')");

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_group ON asset_account (category_group)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_parent ON asset_account (parent_account_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_group_id ON asset_account (group_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_group_category ON asset_account_group (category_group)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_snapshot_group_detail_snapshot ON asset_snapshot_group_detail (snapshot_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_snapshot_group_detail_group ON asset_snapshot_group_detail (group_id)");
    }

    private void ensureAccountGroupTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS asset_account_group (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    group_code TEXT NOT NULL,
                    group_name TEXT NOT NULL,
                    category_group TEXT NOT NULL,
                    account_type TEXT NOT NULL,
                    balance_direction TEXT NOT NULL,
                    currency_code TEXT NOT NULL,
                    institution_name TEXT,
                    owner_name TEXT,
                    remark TEXT,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    migrated_account_id INTEGER,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_group_code ON asset_account_group (group_code)");
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_group_migrated_account
                ON asset_account_group (migrated_account_id)
                WHERE migrated_account_id IS NOT NULL
                """);
    }

    private void ensureSnapshotGroupDetailTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS asset_snapshot_group_detail (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    snapshot_id INTEGER NOT NULL,
                    group_id INTEGER NOT NULL,
                    amount NUMERIC NOT NULL,
                    original_amount NUMERIC,
                    currency_code TEXT NOT NULL,
                    amount_source TEXT NOT NULL DEFAULT 'MANUAL',
                    is_computed INTEGER NOT NULL DEFAULT 0,
                    remark TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_group_detail_snapshot_group
                ON asset_snapshot_group_detail (snapshot_id, group_id)
                """);
    }

    private void ensureAccountColumn(String columnName, String ddl) {
        if (!hasColumn("asset_account", columnName)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private void ensureSnapshotDetailColumn(String columnName, String ddl) {
        if (!hasColumn("asset_snapshot_detail", columnName)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(" + tableName + ")",
                (rs, rowNum) -> rs.getString("name")
        );
        return columns.contains(columnName);
    }

    private void backfillAccountCategoryGroup() {
        List<AccountSeed> accounts = jdbcTemplate.query(
                """
                SELECT id, account_code, account_type
                FROM asset_account
                WHERE category_group IS NULL OR TRIM(category_group) = ''
                """,
                (rs, rowNum) -> new AccountSeed(
                        rs.getLong("id"),
                        rs.getString("account_code"),
                        rs.getString("account_type")
                )
        );

        for (AccountSeed account : accounts) {
            jdbcTemplate.update(
                    """
                    UPDATE asset_account
                    SET category_group = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    AccountCategoryGroup.resolve(account.accountCode(), account.accountType()).code(),
                    account.id()
            );
        }
    }

    private void migrateLegacyAccountGroups() {
        Set<Long> parentIds = new HashSet<>(jdbcTemplate.query(
                """
                SELECT DISTINCT parent_account_id
                FROM asset_account
                WHERE parent_account_id IS NOT NULL
                  AND TRIM(CAST(parent_account_id AS TEXT)) <> ''
                """,
                (rs, rowNum) -> toNullableLong(rs.getObject("parent_account_id"))
        ).stream().filter(id -> id != null).toList());

        List<LegacyAccountRow> accounts = jdbcTemplate.query(
                """
                SELECT id,
                       account_code,
                       account_name,
                       category_group,
                       account_type,
                       parent_account_id,
                       is_summary,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account
                ORDER BY sort_order ASC, id ASC
                """,
                (rs, rowNum) -> new LegacyAccountRow(
                        rs.getLong("id"),
                        rs.getString("account_code"),
                        rs.getString("account_name"),
                        rs.getString("category_group"),
                        rs.getString("account_type"),
                        toNullableLong(rs.getObject("parent_account_id")),
                        rs.getInt("is_summary") == 1,
                        rs.getString("balance_direction"),
                        rs.getString("currency_code"),
                        rs.getString("institution_name"),
                        rs.getString("owner_name"),
                        rs.getString("remark"),
                        rs.getInt("sort_order"),
                        rs.getInt("enabled")
                )
        );

        for (LegacyAccountRow account : accounts) {
            if (account.summaryAccount() || parentIds.contains(account.id())) {
                insertLegacyGroup(account);
            }
        }

        for (LegacyAccountRow account : accounts) {
            if (account.parentAccountId() != null && parentIds.contains(account.parentAccountId())) {
                jdbcTemplate.update(
                        """
                        UPDATE asset_account
                        SET group_id = ?,
                            is_summary = 0,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                          AND (group_id IS NULL OR group_id <> ?)
                        """,
                        account.parentAccountId(),
                        account.id(),
                        account.parentAccountId()
                );
            }
        }

        jdbcTemplate.update("""
                UPDATE asset_account
                SET group_id = (
                        SELECT g.id
                        FROM asset_account_group g
                        WHERE g.migrated_account_id = asset_account.id
                    ),
                    parent_account_id = (
                        SELECT g.id
                        FROM asset_account_group g
                        WHERE g.migrated_account_id = asset_account.id
                    ),
                    is_summary = 0,
                    updated_at = CURRENT_TIMESTAMP
                WHERE EXISTS (
                    SELECT 1
                    FROM asset_account_group g
                    WHERE g.migrated_account_id = asset_account.id
                )
                  AND (
                    group_id IS NULL
                    OR group_id <> (
                        SELECT g.id
                        FROM asset_account_group g
                        WHERE g.migrated_account_id = asset_account.id
                    )
                  )
                """);
    }

    private void insertLegacyGroup(LegacyAccountRow account) {
        jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO asset_account_group (
                    id,
                    group_code,
                    group_name,
                    category_group,
                    account_type,
                    balance_direction,
                    currency_code,
                    institution_name,
                    owner_name,
                    remark,
                    sort_order,
                    enabled,
                    migrated_account_id,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                account.id(),
                account.accountCode(),
                account.accountName(),
                account.categoryGroup(),
                account.accountType(),
                account.balanceDirection(),
                account.currencyCode(),
                account.institutionName(),
                account.ownerName(),
                account.remark(),
                account.sortOrder(),
                account.enabled(),
                account.id()
        );
    }

    private void migrateLegacyGroupSnapshotDetails() {
        jdbcTemplate.update("""
                INSERT OR IGNORE INTO asset_snapshot_group_detail (
                    snapshot_id,
                    group_id,
                    amount,
                    original_amount,
                    currency_code,
                    amount_source,
                    is_computed,
                    remark,
                    created_at,
                    updated_at
                )
                SELECT d.snapshot_id,
                       g.id,
                       d.amount,
                       d.original_amount,
                       d.currency_code,
                       d.amount_source,
                       d.is_computed,
                       d.remark,
                       d.created_at,
                       CURRENT_TIMESTAMP
                FROM asset_snapshot_detail d
                JOIN asset_account_group g ON g.migrated_account_id = d.account_id
                """);
    }

    private void ensureDefaultAccountGroups() {
        for (GroupTemplate group : defaultGroups()) {
            jdbcTemplate.update(
                    """
                    INSERT OR IGNORE INTO asset_account_group (
                        group_code,
                        group_name,
                        category_group,
                        account_type,
                        balance_direction,
                        currency_code,
                        institution_name,
                        owner_name,
                        remark,
                        sort_order,
                        enabled,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    group.groupCode(),
                    group.groupName(),
                    group.categoryGroup(),
                    group.accountType(),
                    group.balanceDirection(),
                    group.currencyCode(),
                    group.institutionName(),
                    group.ownerName(),
                    group.remark(),
                    group.sortOrder(),
                    group.enabled()
            );
        }
    }

    private List<GroupTemplate> defaultGroups() {
        return List.of(
                new GroupTemplate("ALIPAY", "Alipay", "CASH", "EWALLET", "ASSET", "CNY", "Alipay", null, "Daily payment account", 10, 1),
                new GroupTemplate("WECHAT", "WeChat", "CASH", "EWALLET", "ASSET", "CNY", "WeChat Pay", null, "Daily payment account", 20, 1),
                new GroupTemplate("DEFAULT_BANK_CARD", "Default Bank Card", "CASH", "BANK_CARD", "ASSET", "CNY", "Default Bank Card", null, "Primary bank card account", 30, 1),
                new GroupTemplate("FUND_ACCOUNT", "Fund Account", "INVESTMENT", "INVESTMENT", "ASSET", "CNY", "Fund Platform", null, "Fund holding account", 40, 1),
                new GroupTemplate("A_SHARE_ACCOUNT", "A Share Account", "INVESTMENT", "INVESTMENT", "ASSET", "CNY", "Broker", null, "A-share investment account", 50, 1),
                new GroupTemplate("US_STOCK_ACCOUNT", "US Stock Account", "INVESTMENT", "INVESTMENT", "ASSET", "CNY", "Broker", null, "US stock investment account", 60, 1),
                new GroupTemplate("CREDIT_CARD_DUE", "Credit Card Due", "LIABILITY", "CREDIT_CARD", "DEBT", "CNY", "Credit Card", null, "Outstanding credit card balance", 70, 1),
                new GroupTemplate("RECEIVABLES", "Receivables", "LIABILITY", "RECEIVABLE", "ASSET", "CNY", "Personal", null, "External receivables", 80, 1),
                new GroupTemplate("INVESTMENT_LOSS", "Investment Loss", "INVESTMENT", "LOSS", "DEBT", "CNY", "Investment Summary", null, "Accumulated investment loss account", 90, 1),
                new GroupTemplate("HOUSING_FUND", "Housing Fund", "CASH", "HOUSING_FUND", "ASSET", "CNY", "Housing Fund Center", null, "Housing fund account", 100, 1)
        );
    }

    private Long toNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return null;
            }
            return Long.parseLong(stringValue);
        }
        return ((Number) value).longValue();
    }

    private record AccountSeed(Long id, String accountCode, String accountType) {
    }

    private record LegacyAccountRow(
            Long id,
            String accountCode,
            String accountName,
            String categoryGroup,
            String accountType,
            Long parentAccountId,
            boolean summaryAccount,
            String balanceDirection,
            String currencyCode,
            String institutionName,
            String ownerName,
            String remark,
            Integer sortOrder,
            Integer enabled
    ) {
    }

    private record GroupTemplate(
            String groupCode,
            String groupName,
            String categoryGroup,
            String accountType,
            String balanceDirection,
            String currencyCode,
            String institutionName,
            String ownerName,
            String remark,
            Integer sortOrder,
            Integer enabled
    ) {
    }
}
