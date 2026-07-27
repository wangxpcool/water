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
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_group_code
    ON asset_account_group (group_code);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_group_migrated_account
    ON asset_account_group (migrated_account_id)
    WHERE migrated_account_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_asset_account_group_category
    ON asset_account_group (category_group);

CREATE TABLE IF NOT EXISTS asset_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_code TEXT NOT NULL,
    account_name TEXT NOT NULL,
    category_group TEXT NOT NULL,
    account_type TEXT NOT NULL,
    group_id INTEGER,
    parent_account_id INTEGER,
    is_summary INTEGER NOT NULL DEFAULT 0,
    balance_direction TEXT NOT NULL,
    currency_code TEXT NOT NULL,
    institution_name TEXT,
    owner_name TEXT,
    remark TEXT,
    tags TEXT,
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

CREATE INDEX IF NOT EXISTS idx_asset_account_group_id
    ON asset_account (group_id);

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
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_group_detail_snapshot_group
    ON asset_snapshot_group_detail (snapshot_id, group_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_group_detail_snapshot
    ON asset_snapshot_group_detail (snapshot_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_group_detail_group
    ON asset_snapshot_group_detail (group_id);

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
