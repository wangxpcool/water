package com.water.server.snapshot;

import java.math.BigDecimal;

public record AssetSnapshotDetailDto(
        String accountCode,
        String accountName,
        String accountType,
        String categoryGroup,
        Long parentAccountId,
        Boolean summaryAccount,
        String balanceDirection,
        String currencyCode,
        BigDecimal amount,
        BigDecimal originalAmount,
        String amountSource,
        Boolean computed,
        String remark
) {
}
