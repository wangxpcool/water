package com.water.server.snapshot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AssetSnapshotUpsertRequest(
        @NotNull LocalDate snapshotDate,
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
        @Valid List<AssetSnapshotDetailUpsertRequest> details
) {
}
