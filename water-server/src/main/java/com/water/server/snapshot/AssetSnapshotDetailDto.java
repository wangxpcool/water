package com.water.server.snapshot;

import java.math.BigDecimal;
import java.util.List;

public record AssetSnapshotDetailDto(
        Long accountId,
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
        String remark,
        List<String> tags
) {
}
