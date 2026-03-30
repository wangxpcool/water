package com.water.server.snapshot;

import java.math.BigDecimal;

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
        BigDecimal latestAmount
) {
}
