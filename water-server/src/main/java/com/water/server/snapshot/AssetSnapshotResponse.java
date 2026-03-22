package com.water.server.snapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AssetSnapshotResponse(
        Long id,
        LocalDate snapshotDate,
        BigDecimal income,
        BigDecimal fixedExpense,
        BigDecimal cashTotal,
        BigDecimal investmentTotal,
        BigDecimal liabilityTotal,
        BigDecimal grossAccountValue,
        BigDecimal profitLoss,
        BigDecimal netWorth,
        BigDecimal publicFunds,
        BigDecimal extraAmount,
        BigDecimal balance,
        String note,
        String remark,
        Integer sourceRowNumber,
        List<AssetSnapshotDetailDto> details
) {
}
