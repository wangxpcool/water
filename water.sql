-- Water 资产库表结构
-- 设计目标：
-- 1. 不使用外键。
-- 2. 资产账户和负债账户统一放在同一张账户表中。
-- 3. 支持多币种，例如人民币、港币、美元。
-- 4. 兼容 water.csv 这种按天记录资产快照的结构。

-- 币种配置表。
-- 用于存放系统支持的币种及其展示信息。
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

-- 一个币种编码只允许出现一次。
CREATE UNIQUE INDEX IF NOT EXISTS uk_currency_config_code
    ON currency_config (currency_code);

-- 资产账户主表。
-- 这张表存放具体账户，例如支付宝、微信、银行卡、信用卡待还、
-- 基金账户、A 股账户、美股账户、公积金账户等。
--
-- account_type 示例：
-- EWALLET、BANK_CARD、INVESTMENT、CREDIT_CARD、RECEIVABLE、LOSS、HOUSING_FUND
--
-- balance_direction 示例：
-- ASSET 表示资产账户
-- DEBT 表示负债账户，例如待还信用卡
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

-- 账户业务编码，便于程序查询和幂等初始化。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_account_code
    ON asset_account (account_code);

-- 用于账户分类和筛选查询的索引。
CREATE INDEX IF NOT EXISTS idx_asset_account_type
    ON asset_account (account_type);

CREATE INDEX IF NOT EXISTS idx_asset_account_direction
    ON asset_account (balance_direction);

CREATE INDEX IF NOT EXISTS idx_asset_account_currency
    ON asset_account (currency_code);

-- 每日资产快照汇总表。
-- 归一化后，一行大致对应 water.csv 中的一行。
--
-- snapshot_date：快照业务日期
-- income：月收入基线
-- fixed_expense：月固定支出基线
-- cash_total / investment_total / liability_total：现金、投资、负债汇总
-- gross_account_value：账户总值，未扣除负债
-- profit_loss：浮动盈亏
-- net_worth：净资产
-- public_funds：公共资金或预留资金
-- extra_amount：源 csv 中的扩展金额字段
-- balance：后期 csv 中出现的派生余额字段
-- note / remark：自由备注
-- source_row_number：导入 csv 时的原始行号，方便追踪
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

-- 通常一天只保留一条快照。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_date
    ON asset_snapshot (snapshot_date);

-- 资产快照明细表。
-- 用于存放某一天、某个账户对应的金额明细。
--
-- snapshot_id：逻辑上对应 asset_snapshot.id，但不加外键
-- account_id：逻辑上对应 asset_account.id，但不加外键
-- amount：系统使用的标准金额
-- original_amount：原始金额，可用于保留换算前或调整前数值
-- currency_code：该条明细的币种，冗余保存，方便查询
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

-- 防止同一天同一账户出现重复明细。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asset_snapshot_detail_snapshot_account
    ON asset_snapshot_detail (snapshot_id, account_id);

-- 用于快照明细查询的索引。
CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_snapshot
    ON asset_snapshot_detail (snapshot_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_account
    ON asset_snapshot_detail (account_id);

CREATE INDEX IF NOT EXISTS idx_asset_snapshot_detail_currency
    ON asset_snapshot_detail (currency_code);

-- 初始化系统支持的币种。
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

-- 初始化基础账户。
-- 这里先放与当前 csv 语义接近的账户，后续可以继续维护。
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
