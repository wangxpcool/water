package com.water.server.snapshot;

import java.math.BigDecimal;

public record AssetSnapshotDetailUpsertRequest(
        Long accountId,
        String accountCode,
        BigDecimal amount,
        BigDecimal originalAmount,
        String currencyCode,
        String remark
) {
}
