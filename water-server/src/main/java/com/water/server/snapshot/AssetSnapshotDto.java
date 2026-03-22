package com.water.server.snapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record AssetSnapshotDto(
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
        BigDecimal balance,
        Map<String, BigDecimal> cashBreakdown,
        Map<String, BigDecimal> investmentBreakdown,
        Map<String, BigDecimal> liabilityBreakdown,
        String note,
        String remark
) {
}
