package com.water.server.snapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AssetSnapshotQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AssetSnapshotQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AssetSnapshotResponse> findAllSnapshots() {
        List<SnapshotRow> snapshotRows = jdbcTemplate.query("""
                SELECT id,
                       snapshot_date,
                       income,
                       fixed_expense,
                       cash_total,
                       investment_total,
                       liability_total,
                       gross_account_value,
                       profit_loss,
                       net_worth,
                       public_funds,
                       extra_amount,
                       balance,
                       note,
                       remark,
                       source_row_number
                FROM asset_snapshot
                ORDER BY snapshot_date DESC, id DESC
                """, (rs, rowNum) -> mapSnapshotRow(rs));

        Map<Long, List<AssetSnapshotDetailDto>> detailMap = findDetailsBySnapshotId();

        return snapshotRows.stream()
                .map(row -> new AssetSnapshotResponse(
                        row.id(),
                        row.snapshotDate(),
                        row.income(),
                        row.fixedExpense(),
                        row.cashTotal(),
                        row.investmentTotal(),
                        row.liabilityTotal(),
                        row.grossAccountValue(),
                        row.profitLoss(),
                        row.netWorth(),
                        row.publicFunds(),
                        row.extraAmount(),
                        row.balance(),
                        row.note(),
                        row.remark(),
                        row.sourceRowNumber(),
                        detailMap.getOrDefault(row.id(), List.of())
                ))
                .toList();
    }

    public AssetSnapshotResponse findSnapshotById(long id) {
        List<SnapshotRow> rows = jdbcTemplate.query("""
                SELECT id,
                       snapshot_date,
                       income,
                       fixed_expense,
                       cash_total,
                       investment_total,
                       liability_total,
                       gross_account_value,
                       profit_loss,
                       net_worth,
                       public_funds,
                       extra_amount,
                       balance,
                       note,
                       remark,
                       source_row_number
                FROM asset_snapshot
                WHERE id = ?
                """, (rs, rowNum) -> mapSnapshotRow(rs), id);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Snapshot not found: " + id);
        }

        SnapshotRow row = rows.get(0);
        Map<Long, List<AssetSnapshotDetailDto>> detailMap = findDetailsBySnapshotId(List.of(id));
        return new AssetSnapshotResponse(
                row.id(),
                row.snapshotDate(),
                row.income(),
                row.fixedExpense(),
                row.cashTotal(),
                row.investmentTotal(),
                row.liabilityTotal(),
                row.grossAccountValue(),
                row.profitLoss(),
                row.netWorth(),
                row.publicFunds(),
                row.extraAmount(),
                row.balance(),
                row.note(),
                row.remark(),
                row.sourceRowNumber(),
                detailMap.getOrDefault(id, List.of())
        );
    }

    public List<AssetAccountOptionDto> findEnabledAccounts() {
        return findAccounts(true);
    }

    public List<AssetAccountOptionDto> findAllAccounts() {
        return findAccounts(false);
    }

    public AssetAccountOptionDto findAccountById(long id) {
        List<AssetAccountOptionDto> accounts = jdbcTemplate.query("""
                SELECT id,
                       account_code,
                       account_name,
                       account_type,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account
                WHERE id = ?
                """, (rs, rowNum) -> mapAccountRow(rs), id);

        if (accounts.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Account not found: " + id);
        }
        return accounts.get(0);
    }

    private List<AssetAccountOptionDto> findAccounts(boolean enabledOnly) {
        String sql = """
                SELECT id,
                       account_code,
                       account_name,
                       account_type,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account
                """;
        if (enabledOnly) {
            sql += " WHERE enabled = 1";
        }
        sql += " ORDER BY sort_order ASC, id ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapAccountRow(rs));
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId() {
        return findDetailsBySnapshotId(null);
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId(List<Long> snapshotIds) {
        String sql = """
                SELECT d.snapshot_id,
                       a.account_code,
                       a.account_name,
                       a.account_type,
                       a.balance_direction,
                       d.currency_code,
                       d.amount,
                       d.original_amount,
                       d.remark
                FROM asset_snapshot_detail d
                JOIN asset_account a ON a.id = d.account_id
                """;
        Object[] args = new Object[0];
        if (snapshotIds != null && !snapshotIds.isEmpty()) {
            String placeholders = String.join(",", snapshotIds.stream().map(id -> "?").toList());
            sql += " WHERE d.snapshot_id IN (" + placeholders + ")";
            args = snapshotIds.toArray();
        }
        sql += " ORDER BY d.snapshot_id DESC, a.sort_order ASC, d.id ASC";

        List<DetailRow> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapDetailRow(rs), args);

        Map<Long, List<AssetSnapshotDetailDto>> detailMap = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            detailMap.computeIfAbsent(row.snapshotId(), ignored -> new java.util.ArrayList<>())
                    .add(new AssetSnapshotDetailDto(
                            row.accountCode(),
                            row.accountName(),
                            row.accountType(),
                            row.balanceDirection(),
                            row.currencyCode(),
                            row.amount(),
                            row.originalAmount(),
                            row.remark()
                    ));
        }
        return detailMap;
    }

    private SnapshotRow mapSnapshotRow(ResultSet rs) throws SQLException {
        return new SnapshotRow(
                rs.getLong("id"),
                LocalDate.parse(rs.getString("snapshot_date")),
                rs.getBigDecimal("income"),
                rs.getBigDecimal("fixed_expense"),
                rs.getBigDecimal("cash_total"),
                rs.getBigDecimal("investment_total"),
                rs.getBigDecimal("liability_total"),
                rs.getBigDecimal("gross_account_value"),
                rs.getBigDecimal("profit_loss"),
                rs.getBigDecimal("net_worth"),
                rs.getBigDecimal("public_funds"),
                rs.getBigDecimal("extra_amount"),
                rs.getBigDecimal("balance"),
                rs.getString("note"),
                rs.getString("remark"),
                (Integer) rs.getObject("source_row_number")
        );
    }

    private DetailRow mapDetailRow(ResultSet rs) throws SQLException {
        return new DetailRow(
                rs.getLong("snapshot_id"),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("account_type"),
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("original_amount"),
                rs.getString("remark")
        );
    }

    private AssetAccountOptionDto mapAccountRow(ResultSet rs) throws SQLException {
        return new AssetAccountOptionDto(
                rs.getLong("id"),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("account_type"),
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getString("institution_name"),
                rs.getString("owner_name"),
                rs.getString("remark"),
                rs.getInt("sort_order"),
                rs.getInt("enabled") == 1
        );
    }

    private record SnapshotRow(
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
            Integer sourceRowNumber
    ) {
    }

    private record DetailRow(
            Long snapshotId,
            String accountCode,
            String accountName,
            String accountType,
            String balanceDirection,
            String currencyCode,
            BigDecimal amount,
            BigDecimal originalAmount,
            String remark
    ) {
    }
}
