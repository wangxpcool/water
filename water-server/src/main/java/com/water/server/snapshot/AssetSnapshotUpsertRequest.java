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
        BigDecimal profitLoss,
        BigDecimal publicFunds,
        BigDecimal extraAmount,
        BigDecimal balance,
        String note,
        String remark,
        @Valid List<AssetSnapshotDetailUpsertRequest> details,
        java.util.Map<String, BigDecimal> tagAdjustments,
        BigDecimal hkdToCnyRate
) {
    public AssetSnapshotUpsertRequest(LocalDate snapshotDate, BigDecimal income, BigDecimal fixedExpense,
            BigDecimal profitLoss, BigDecimal publicFunds, BigDecimal extraAmount, BigDecimal balance,
            String note, String remark, List<AssetSnapshotDetailUpsertRequest> details, java.util.Map<String, BigDecimal> tagAdjustments) {
        this(snapshotDate, income, fixedExpense, profitLoss, publicFunds, extraAmount, balance, note, remark, details, tagAdjustments, null);
    }

    public AssetSnapshotUpsertRequest(LocalDate snapshotDate, BigDecimal income, BigDecimal fixedExpense,
            BigDecimal profitLoss, BigDecimal publicFunds, BigDecimal extraAmount, BigDecimal balance,
            String note, String remark, List<AssetSnapshotDetailUpsertRequest> details) {
        this(snapshotDate, income, fixedExpense, profitLoss, publicFunds, extraAmount, balance, note, remark, details, null, null);
    }
}
