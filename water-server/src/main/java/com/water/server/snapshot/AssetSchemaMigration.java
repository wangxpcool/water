package com.water.server.snapshot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AssetSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public AssetSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureAccountColumn("category_group", "ALTER TABLE asset_account ADD COLUMN category_group TEXT");
        ensureAccountColumn("parent_account_id", "ALTER TABLE asset_account ADD COLUMN parent_account_id INTEGER");
        ensureAccountColumn("is_summary", "ALTER TABLE asset_account ADD COLUMN is_summary INTEGER NOT NULL DEFAULT 0");

        ensureDefaultAccounts();
        backfillAccountCategoryGroup();

        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_group ON asset_account (category_group)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_asset_account_parent ON asset_account (parent_account_id)");

        ensureBankCardSummaryAccount();
        attachDefaultBankCardToSummary();
    }

    private void ensureAccountColumn(String columnName, String ddl) {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(asset_account)",
                (rs, rowNum) -> rs.getString("name")
        );
        if (!columns.contains(columnName)) {
            jdbcTemplate.execute(ddl);
        }
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

    private void ensureDefaultAccounts() {
        for (AccountTemplate account : defaultAccounts()) {
            jdbcTemplate.update(
                    """
                    INSERT OR IGNORE INTO asset_account (
                        account_code,
                        account_name,
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
                    account.enabled()
            );
        }
    }

    private void ensureBankCardSummaryAccount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM asset_account WHERE account_code = 'BANK_CARDS'",
                Integer.class
        );
        if (count != null && count > 0) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO asset_account (
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
                    enabled,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                "BANK_CARDS",
                "银行卡",
                "CASH",
                "BANK_CARD_GROUP",
                null,
                1,
                "ASSET",
                "CNY",
                "银行卡汇总",
                null,
                "银行卡汇总账户",
                25,
                1
        );
    }

    private void attachDefaultBankCardToSummary() {
        Long bankCardsId = jdbcTemplate.query(
                "SELECT id FROM asset_account WHERE account_code = 'BANK_CARDS'",
                (rs, rowNum) -> rs.getLong("id")
        ).stream().findFirst().orElse(null);
        if (bankCardsId == null) {
            return;
        }

        jdbcTemplate.update(
                """
                UPDATE asset_account
                SET parent_account_id = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE account_code = 'DEFAULT_BANK_CARD'
                  AND (parent_account_id IS NULL OR parent_account_id = 0)
                """,
                bankCardsId
        );
    }

    private List<AccountTemplate> defaultAccounts() {
        return List.of(
                new AccountTemplate("ALIPAY", "支付宝", "CASH", "EWALLET", "ASSET", "CNY", "支付宝", null, "日常支付账户", 10, 1),
                new AccountTemplate("WECHAT", "微信", "CASH", "EWALLET", "ASSET", "CNY", "微信支付", null, "日常支付账户", 20, 1),
                new AccountTemplate("DEFAULT_BANK_CARD", "默认银行卡", "CASH", "BANK_CARD", "ASSET", "CNY", "默认银行卡", null, "主要银行卡账户", 30, 1),
                new AccountTemplate("FUND_ACCOUNT", "基金账户", "INVESTMENT", "INVESTMENT", "ASSET", "CNY", "基金平台", null, "基金持仓账户", 40, 1),
                new AccountTemplate("A_SHARE_ACCOUNT", "A股账户", "INVESTMENT", "INVESTMENT", "ASSET", "CNY", "A股券商", null, "A股投资账户", 50, 1),
                new AccountTemplate("US_STOCK_ACCOUNT", "美股账户", "INVESTMENT", "INVESTMENT", "ASSET", "USD", "美股券商", null, "美股投资账户", 60, 1),
                new AccountTemplate("CREDIT_CARD_DUE", "待还信用卡", "LIABILITY", "CREDIT_CARD", "DEBT", "CNY", "信用卡账户", null, "待还信用卡余额", 70, 1),
                new AccountTemplate("RECEIVABLES", "未收款项", "LIABILITY", "RECEIVABLE", "ASSET", "CNY", "个人往来", null, "外部应收款项", 80, 1),
                new AccountTemplate("INVESTMENT_LOSS", "投资总损失", "INVESTMENT", "LOSS", "DEBT", "CNY", "投资汇总", null, "累计投资亏损记录账户", 90, 1),
                new AccountTemplate("HOUSING_FUND", "公积金账户", "CASH", "HOUSING_FUND", "ASSET", "CNY", "公积金中心", null, "住房公积金账户", 100, 1)
        );
    }

    private record AccountSeed(Long id, String accountCode, String accountType) {
    }

    private record AccountTemplate(
            String accountCode,
            String accountName,
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
