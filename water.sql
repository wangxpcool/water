-- Water database schema

CREATE TABLE IF NOT EXISTS currency_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    currency_code TEXT NOT NULL,
    currency_name TEXT NOT NULL,
    currency_symbol TEXT,
    decimal_scale INTEGER NOT NULL DEFAULT 2,
    remark TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_currency_config_code
    ON currency_config (currency_code);

CREATE TABLE IF NOT EXISTS asset_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_code TEXT NOT NULL,
    account_name TEXT NOT NULL,
    category_group TEXT NOT NULL,
    account_type TEXT NOT NULL,
    parent_account_id INTEGER,
    is_summary INTEGER NOT NULL DEFAULT 0,
    balance_direction TEXT NOT NULL,
    currency_code TEXT NOT NULL,
    institution_name TEXT,
    owner_name TEXT,
    remark TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_code
    ON asset_account (account_code);

CREATE INDEX IF NOT EXISTS idx_asset_account_type
    ON asset_account (account_type);

CREATE INDEX IF NOT EXISTS idx_asset_account_direction
    ON asset_account (balance_direction);

CREATE INDEX IF NOT EXISTS idx_asset_account_currency
    ON asset_account (currency_code);

CREATE INDEX IF NOT EXISTS idx_asset_account_parent
    ON asset_account (parent_account_id);

CREATE TABLE IF NOT EXISTS asset_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_date TEXT NOT NULL,
    income NUMERIC,
    fixed_expense NUMERIC,
    cash_total NUMERIC,
    investment_total NUMERIC,
    liability_total NUMERIC,
    gross_account_value NUMERIC,
    profit_loss NUMERIC,
    net_worth NUMERIC,
    public_funds NUMERIC,
    extra_amount NUMERIC,
    balance NUMERIC,
    note TEXT,
    remark TEXT,
    source_row_number INTEGER,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_date
    ON asset_snapshot (snapshot_date);

CREATE TABLE IF NOT EXISTS asset_snapshot_detail (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    snapshot_id INTEGER NOT NULL,
    account_id INTEGER NOT NULL,
    amount NUMERIC NOT NULL,
    original_amount NUMERIC,
    currency_code TEXT NOT NULL,
    amount_source TEXT NOT NULL DEFAULT 'MANUAL',
    is_computed INTEGER NOT NULL DEFAULT 0,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_detail_snapshot_account
    ON asset_snapshot_detail (snapshot_id, account_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_snapshot
    ON asset_snapshot_detail (snapshot_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_account
    ON asset_snapshot_detail (account_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_currency
    ON asset_snapshot_detail (currency_code);

INSERT OR IGNORE INTO currency_config (
    currency_code,
    currency_name,
    currency_symbol,
    decimal_scale,
    remark,
    enabled
) VALUES
    ('CNY', 'Chinese Yuan', 'CNY', 2, 'Default RMB currency', 1),
    ('HKD', 'Hong Kong Dollar', 'HK$', 2, 'Hong Kong dollar currency', 1),
    ('USD', 'US Dollar', '$', 2, 'US dollar currency', 1);

INSERT OR IGNORE INTO asset_account (
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
) VALUES
    ('ALIPAY', 'Alipay', 'CASH', 'EWALLET', NULL, 0, 'ASSET', 'CNY', 'Alipay', NULL, 'Daily payment account', 10, 1),
    ('WECHAT', 'WeChat', 'CASH', 'EWALLET', NULL, 0, 'ASSET', 'CNY', 'WeChat Pay', NULL, 'Daily payment account', 20, 1),
    ('BANK_CARDS', 'Bank Cards', 'CASH', 'BANK_CARD_GROUP', NULL, 1, 'ASSET', 'CNY', 'Bank Card Summary', NULL, 'Auto-summed bank card parent account', 25, 1),
    ('DEFAULT_BANK_CARD', 'Default Bank Card', 'CASH', 'BANK_CARD', NULL, 0, 'ASSET', 'CNY', 'Default Bank Card', NULL, 'Primary bank card account', 30, 1),
    ('FUND_ACCOUNT', 'Fund Account', 'INVESTMENT', 'INVESTMENT', NULL, 0, 'ASSET', 'CNY', 'Fund Platform', NULL, 'Fund holding account', 40, 1),
    ('A_SHARE_ACCOUNT', 'A Share Account', 'INVESTMENT', 'INVESTMENT', NULL, 0, 'ASSET', 'CNY', 'Broker', NULL, 'A-share investment account', 50, 1),
    ('US_STOCK_ACCOUNT', 'US Stock Account', 'INVESTMENT', 'INVESTMENT', NULL, 0, 'ASSET', 'USD', 'Broker', NULL, 'US stock investment account', 60, 1),
    ('CREDIT_CARD_DUE', 'Credit Card Due', 'LIABILITY', 'CREDIT_CARD', NULL, 0, 'DEBT', 'CNY', 'Credit Card', NULL, 'Outstanding credit card balance', 70, 1),
    ('RECEIVABLES', 'Receivables', 'LIABILITY', 'RECEIVABLE', NULL, 0, 'ASSET', 'CNY', 'Personal', NULL, 'External receivables', 80, 1),
    ('INVESTMENT_LOSS', 'Investment Loss', 'INVESTMENT', 'LOSS', NULL, 0, 'DEBT', 'CNY', 'Investment Summary', NULL, 'Accumulated investment loss account', 90, 1),
    ('HOUSING_FUND', 'Housing Fund', 'CASH', 'HOUSING_FUND', NULL, 0, 'ASSET', 'CNY', 'Housing Fund Center', NULL, 'Housing fund account', 100, 1);
