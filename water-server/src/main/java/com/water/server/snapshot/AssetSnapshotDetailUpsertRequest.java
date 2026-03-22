package com.water.server.snapshot;

import java.math.BigDecimal;

public record AssetSnapshotDetailUpsertRequest(
        String accountCode,
        BigDecimal amount,
        BigDecimal originalAmount,
        String currencyCode,
        String remark
) {
}
