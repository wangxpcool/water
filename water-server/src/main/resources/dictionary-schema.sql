CREATE TABLE IF NOT EXISTS config_dictionary (
    dict_type TEXT NOT NULL,
    dict_key TEXT NOT NULL,
    dict_value TEXT NOT NULL,
    display_name TEXT NOT NULL,
    remark TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (dict_type, dict_key)
);

INSERT OR IGNORE INTO config_dictionary (dict_type, dict_key, dict_value, display_name, remark)
VALUES ('EXCHANGE_RATE', 'HKD_CNY', '0.87', '港币兑人民币', '1 HKD 对应的 CNY 金额；新快照采用当前配置，历史快照保留当期汇率');
