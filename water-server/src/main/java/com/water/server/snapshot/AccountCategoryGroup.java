package com.water.server.snapshot;

public enum AccountCategoryGroup {
    CASH("CASH", "现金"),
    INVESTMENT("INVESTMENT", "投资"),
    LIABILITY("LIABILITY", "借贷");

    private final String code;
    private final String displayName;

    AccountCategoryGroup(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public static AccountCategoryGroup fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("categoryGroup is required");
        }

        for (AccountCategoryGroup group : values()) {
            if (group.code.equalsIgnoreCase(code.trim())) {
                return group;
            }
        }

        throw new IllegalArgumentException("Unsupported categoryGroup: " + code);
    }

    public static AccountCategoryGroup resolve(String accountCode, String accountType) {
        if ("FUND_ACCOUNT".equals(accountCode)
                || "A_SHARE_ACCOUNT".equals(accountCode)
                || "US_STOCK_ACCOUNT".equals(accountCode)
                || "INVESTMENT_LOSS".equals(accountCode)
                || "INVESTMENT".equals(accountType)) {
            return INVESTMENT;
        }

        if ("CREDIT_CARD_DUE".equals(accountCode)
                || "RECEIVABLES".equals(accountCode)
                || "CREDIT_CARD".equals(accountType)
                || "RECEIVABLE".equals(accountType)
                || "LOSS".equals(accountType)) {
            return LIABILITY;
        }

        return CASH;
    }
}
