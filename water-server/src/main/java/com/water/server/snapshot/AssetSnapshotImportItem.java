package com.water.server.snapshot;

import java.math.BigDecimal;

public record AssetSnapshotImportItem(
        AssetCategoryType categoryType,
        String itemCode,
        String itemName,
        BigDecimal amount,
        String rawExpression
) {
}
