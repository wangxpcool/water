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
import java.util.stream.Collectors;

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
        return findAccounts(true, false);
    }

    public List<AssetAccountOptionDto> findEnabledLeafAccounts() {
        return findAccounts(true, true);
    }

    public List<AssetAccountOptionDto> findAllAccounts() {
        return findAccounts(false, false);
    }

    public AssetAccountOptionDto findAccountById(long id) {
        Map<Long, BigDecimal> latestAmountMap = findLatestAmountByAccountId();
        List<AssetAccountOptionDto> accounts = jdbcTemplate.query("""
                SELECT id,
                       account_code,
                       account_name,
                       category_group,
                       account_type,
                       parent_account_id,
                       is_summary,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account
                WHERE id = ?
                """, (rs, rowNum) -> mapAccountRow(rs, latestAmountMap), id);

        if (accounts.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Account not found: " + id);
        }
        return accounts.get(0);
    }

    private List<AssetAccountOptionDto> findAccounts(boolean enabledOnly, boolean leafOnly) {
        String sql = """
                SELECT id,
                       account_code,
                       account_name,
                       category_group,
                       account_type,
                       parent_account_id,
                       is_summary,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account
                """;

        List<String> predicates = new java.util.ArrayList<>();
        if (enabledOnly) {
            predicates.add("enabled = 1");
        }
        if (leafOnly) {
            predicates.add("is_summary = 0");
        }
        if (!predicates.isEmpty()) {
            sql += " WHERE " + String.join(" AND ", predicates);
        }
        sql += " ORDER BY sort_order ASC, id ASC";

        Map<Long, BigDecimal> latestAmountMap = findLatestAmountByAccountId();
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapAccountRow(rs, latestAmountMap));
    }

    private Map<Long, BigDecimal> findLatestAmountByAccountId() {
        List<Long> latestSnapshotIds = jdbcTemplate.query("""
                SELECT id
                FROM asset_snapshot
                ORDER BY snapshot_date DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"));
        if (latestSnapshotIds.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.query("""
                SELECT account_id, amount
                FROM asset_snapshot_detail
                WHERE snapshot_id = ?
                """, (rs, rowNum) -> Map.entry(rs.getLong("account_id"), rs.getBigDecimal("amount")), latestSnapshotIds.get(0))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId() {
        return findDetailsBySnapshotId(null);
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId(List<Long> snapshotIds) {
        String sql = """
                SELECT d.snapshot_id,
                       a.account_code,
                       a.account_name,
                       a.category_group,
                       a.account_type,
                       a.parent_account_id,
                       a.is_summary,
                       a.balance_direction,
                       d.currency_code,
                       d.amount,
                       d.original_amount,
                       d.amount_source,
                       d.is_computed,
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
                            row.categoryGroup(),
                            row.parentAccountId(),
                            row.summaryAccount(),
                            row.balanceDirection(),
                            row.currencyCode(),
                            row.amount(),
                            row.originalAmount(),
                            row.amountSource(),
                            row.computed(),
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
                rs.getString("category_group"),
                rs.getString("account_type"),
                toNullableLong(rs.getObject("parent_account_id")),
                rs.getInt("is_summary") == 1,
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("original_amount"),
                rs.getString("amount_source"),
                rs.getInt("is_computed") == 1,
                rs.getString("remark")
        );
    }

    private AssetAccountOptionDto mapAccountRow(ResultSet rs, Map<Long, BigDecimal> latestAmountMap) throws SQLException {
        long id = rs.getLong("id");
        return new AssetAccountOptionDto(
                id,
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("account_type"),
                resolveCategoryGroup(rs),
                toNullableLong(rs.getObject("parent_account_id")),
                rs.getInt("is_summary") == 1,
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getString("institution_name"),
                rs.getString("owner_name"),
                rs.getString("remark"),
                rs.getInt("sort_order"),
                rs.getInt("enabled") == 1,
                latestAmountMap.get(id)
        );
    }

    private Long toNullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return null;
            }
            return Long.parseLong(stringValue);
        }
        return ((Number) value).longValue();
    }

    private String resolveCategoryGroup(ResultSet rs) throws SQLException {
        String categoryGroup = rs.getString("category_group");
        if (categoryGroup != null && !categoryGroup.isBlank()) {
            return categoryGroup;
        }
        return AccountCategoryGroup.resolve(rs.getString("account_code"), rs.getString("account_type")).code();
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
            String categoryGroup,
            String accountType,
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
}
