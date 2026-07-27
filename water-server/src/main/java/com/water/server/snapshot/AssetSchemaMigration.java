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

    public AssetSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureAccountGroupTable();
        ensureSnapshotGroupDetailTable();

        ensureAccountColumn("category_group", "ALTER TABLE asset_account ADD COLUMN category_group TEXT");
        ensureAccountColumn("parent_account_id", "ALTER TABLE asset_account ADD COLUMN parent_account_id INTEGER");
        ensureAccountColumn("is_summary", "ALTER TABLE asset_account ADD COLUMN is_summary INTEGER NOT NULL DEFAULT 0");
        ensureAccountColumn("group_id", "ALTER TABLE asset_account ADD COLUMN group_id INTEGER");
        ensureAccountColumn("tags", "ALTER TABLE asset_account ADD COLUMN tags TEXT");
        ensureSnapshotDetailColumn(
                "amount_source",
                "ALTER TABLE asset_snapshot_detail ADD COLUMN amount_source TEXT NOT NULL DEFAULT 'MANUAL'"
        );
        ensureSnapshotDetailColumn(
                "is_computed",
                "ALTER TABLE asset_snapshot_detail ADD COLUMN is_computed INTEGER NOT NULL DEFAULT 0"
        );

        backfillAccountCategoryGroup();
        migrateLegacyAccountGroups();
        ensureDefaultAccountGroups();
        migrateLegacyGroupSnapshotDetails();
        recalculateGroupDetailsFromBreakdowns();
        recalculateSnapshotAggregates();

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

    private void recalculateGroupDetailsFromBreakdowns() {
        jdbcTemplate.update("""
                INSERT INTO asset_snapshot_group_detail (
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
                       ROUND(SUM(ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1)), 1),
                       NULL,
                       g.currency_code,
                       'ROLLED_UP',
                       1,
                       NULL,
                       CURRENT_TIMESTAMP,
                       CURRENT_TIMESTAMP
                FROM asset_snapshot_detail d
                JOIN asset_account a ON a.id = d.account_id
                JOIN asset_account_group g ON g.id = a.group_id
                WHERE (
                    (
                        SELECT COUNT(*)
                        FROM asset_account child
                        WHERE child.group_id = g.id
                    ) <= 1
                    OR a.account_code <> g.group_code
                )
                GROUP BY d.snapshot_id, g.id
                ON CONFLICT(snapshot_id, group_id) DO UPDATE SET
                    amount = excluded.amount,
                    original_amount = NULL,
                    currency_code = excluded.currency_code,
                    amount_source = 'ROLLED_UP',
                    is_computed = 1,
                    remark = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """);
    }

    private void recalculateSnapshotAggregates() {
        jdbcTemplate.update("""
                UPDATE asset_snapshot
                SET cash_total = ROUND(COALESCE((
                        SELECT SUM(amount)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE category_group = 'CASH'
                          AND balance_direction = 'ASSET'
                    ), 0), 1),
                    investment_total = ROUND(COALESCE((
                        SELECT SUM(CASE WHEN balance_direction = 'DEBT' THEN amount * -1 ELSE amount END)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE category_group = 'INVESTMENT'
                    ), 0), 1),
                    liability_total = ROUND(COALESCE((
                        SELECT SUM(amount)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE balance_direction = 'DEBT'
                    ), 0), 1),
                    gross_account_value = ROUND(COALESCE((
                        SELECT SUM(amount)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE balance_direction <> 'DEBT'
                    ), 0), 1),
                    net_worth = ROUND(COALESCE((
                        SELECT SUM(amount)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE balance_direction <> 'DEBT'
                    ), 0) - COALESCE((
                        SELECT SUM(amount)
                        FROM (
                            SELECT gd.amount, g.category_group, g.balance_direction
                            FROM asset_snapshot_group_detail gd
                            JOIN asset_account_group g ON g.id = gd.group_id
                            WHERE gd.snapshot_id = asset_snapshot.id
                            UNION ALL
                            SELECT ROUND(CASE WHEN d.currency_code = 'HKD' THEN d.amount * 0.87 ELSE d.amount END, 1), a.category_group, a.balance_direction
                            FROM asset_snapshot_detail d
                            JOIN asset_account a ON a.id = d.account_id
                            WHERE d.snapshot_id = asset_snapshot.id
                              AND a.group_id IS NULL
                        ) top_details
                        WHERE balance_direction = 'DEBT'
                    ), 0), 1),
                    updated_at = CURRENT_TIMESTAMP
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
                new GroupTemplate("US_STOCK_ACCOUNT", "US Stock Account", "INVESTMENT", "INVESTMENT", "ASSET", "USD", "Broker", null, "US stock investment account", 60, 1),
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
