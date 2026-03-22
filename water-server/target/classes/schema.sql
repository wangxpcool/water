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
    account_type TEXT NOT NULL,
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
    ('CNY', '人民币', '¥', 2, '中国大陆常用币种', 1),
    ('HKD', '港币', 'HK$', 2, '香港常用币种', 1),
    ('USD', '美元', '$', 2, '美股账户常用币种', 1);

INSERT OR IGNORE INTO asset_account (
    account_code,
    account_name,
    account_type,
    balance_direction,
    currency_code,
    institution_name,
    owner_name,
    remark,
    sort_order,
    enabled
) VALUES
    ('ALIPAY', '支付宝', 'EWALLET', 'ASSET', 'CNY', '支付宝', NULL, '日常支付账户', 10, 1),
    ('WECHAT', '微信', 'EWALLET', 'ASSET', 'CNY', '微信支付', NULL, '日常支付账户', 20, 1),
    ('DEFAULT_BANK_CARD', '默认银行卡', 'BANK_CARD', 'ASSET', 'CNY', '默认银行卡', NULL, '主要银行卡账户', 30, 1),
    ('FUND_ACCOUNT', '基金账户', 'INVESTMENT', 'ASSET', 'CNY', '基金平台', NULL, '基金持仓账户', 40, 1),
    ('A_SHARE_ACCOUNT', 'A股账户', 'INVESTMENT', 'ASSET', 'CNY', 'A股券商', NULL, 'A股投资账户', 50, 1),
    ('US_STOCK_ACCOUNT', '美股账户', 'INVESTMENT', 'ASSET', 'USD', '美股券商', NULL, '美股投资账户', 60, 1),
    ('CREDIT_CARD_DUE', '待还信用卡', 'CREDIT_CARD', 'DEBT', 'CNY', '信用卡账户', NULL, '待还信用卡余额', 70, 1),
    ('RECEIVABLES', '未收欠款', 'RECEIVABLE', 'ASSET', 'CNY', '个人往来', NULL, '外部应收款项', 80, 1),
    ('INVESTMENT_LOSS', '投资总损失', 'LOSS', 'DEBT', 'CNY', '投资汇总', NULL, '累计投资亏损记录账户', 90, 1),
    ('HOUSING_FUND', '公积金账户', 'HOUSING_FUND', 'ASSET', 'CNY', '公积金中心', NULL, '住房公积金账户', 100, 1);
