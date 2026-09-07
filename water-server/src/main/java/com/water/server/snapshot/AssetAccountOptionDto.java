package com.water.server.snapshot;

import java.math.BigDecimal;
import java.util.List;

public record AssetAccountOptionDto(
        Long id,
        String accountCode,
        String accountName,
        String accountType,
        String categoryGroup,
        Long parentAccountId,
        Boolean summaryAccount,
        String balanceDirection,
        String currencyCode,
        String institutionName,
        String ownerName,
        String remark,
        Integer sortOrder,
        Boolean enabled,
        List<String> tags,
        BigDecimal latestAmount,
        Boolean subtotalAccount
) {
    public AssetAccountOptionDto(Long id, String accountCode, String accountName, String accountType,
            String categoryGroup, Long parentAccountId, Boolean summaryAccount, String balanceDirection,
            String currencyCode, String institutionName, String ownerName, String remark, Integer sortOrder,
            Boolean enabled, List<String> tags, BigDecimal latestAmount) {
        this(id, accountCode, accountName, accountType, categoryGroup, parentAccountId, summaryAccount,
                balanceDirection, currencyCode, institutionName, ownerName, remark, sortOrder, enabled, tags, latestAmount, false);
    }
}
