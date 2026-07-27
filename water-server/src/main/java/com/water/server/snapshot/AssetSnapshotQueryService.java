package com.water.server.snapshot;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
        return findAccounts(true, false);
    }

    public List<AssetAccountOptionDto> findAllAccounts() {
        return findAccounts(false, false);
    }

    public AssetAccountOptionDto findAccountById(long id) {
        Map<Long, BigDecimal> latestAmountMap = findLatestAmountByAccountId();
        List<AssetAccountOptionDto> accounts = id < 0
                ? findGroupById(toGroupId(id), latestAmountMap)
                : findRealAccountById(id, latestAmountMap);

        if (accounts.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Account not found: " + id);
        }
        return accounts.get(0);
    }

    private List<AssetAccountOptionDto> findAccounts(boolean enabledOnly, boolean realAccountsOnly) {
        Map<Long, BigDecimal> latestAmountMap = findLatestAmountByAccountId();
        List<AssetAccountOptionDto> accounts = new ArrayList<>();
        if (!realAccountsOnly) {
            accounts.addAll(findGroups(enabledOnly, latestAmountMap));
        }
        accounts.addAll(findRealAccounts(enabledOnly, latestAmountMap));
        accounts.sort(accountComparator());
        return accounts;
    }

    private List<AssetAccountOptionDto> findGroups(boolean enabledOnly, Map<Long, BigDecimal> latestAmountMap) {
        String sql = """
                SELECT id,
                       group_code,
                       group_name,
                       category_group,
                       account_type,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account_group
                """;
        if (enabledOnly) {
            sql += " WHERE enabled = 1";
        }
        sql += " ORDER BY sort_order ASC, id ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapGroupRow(rs, latestAmountMap));
    }

    private List<AssetAccountOptionDto> findGroupById(long groupId, Map<Long, BigDecimal> latestAmountMap) {
        return jdbcTemplate.query("""
                SELECT id,
                       group_code,
                       group_name,
                       category_group,
                       account_type,
                       balance_direction,
                       currency_code,
                       institution_name,
                       owner_name,
                       remark,
                       sort_order,
                       enabled
                FROM asset_account_group
                WHERE id = ?
                """, (rs, rowNum) -> mapGroupRow(rs, latestAmountMap), groupId);
    }

    private List<AssetAccountOptionDto> findRealAccounts(boolean enabledOnly, Map<Long, BigDecimal> latestAmountMap) {
        String sql = """
                SELECT a.id,
                       a.account_code,
                       a.account_name,
                       COALESCE(g.category_group, a.category_group) AS category_group,
                       a.account_type,
                       a.group_id,
                       a.balance_direction,
                       a.currency_code,
                       a.institution_name,
                       a.owner_name,
                       a.remark,
                       a.tags,
                       a.sort_order,
                       a.enabled
                FROM asset_account a
                LEFT JOIN asset_account_group g ON g.id = a.group_id
                WHERE 1 = 1
                """;
        if (enabledOnly) {
            sql += " AND a.enabled = 1";
        }
        sql += " ORDER BY a.sort_order ASC, a.id ASC";

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRealAccountRow(rs, latestAmountMap));
    }

    private List<AssetAccountOptionDto> findRealAccountById(long id, Map<Long, BigDecimal> latestAmountMap) {
        return jdbcTemplate.query("""
                SELECT a.id,
                       a.account_code,
                       a.account_name,
                       COALESCE(g.category_group, a.category_group) AS category_group,
                       a.account_type,
                       a.group_id,
                       a.balance_direction,
                       a.currency_code,
                       a.institution_name,
                       a.owner_name,
                       a.remark,
                       a.tags,
                       a.sort_order,
                       a.enabled
                FROM asset_account a
                LEFT JOIN asset_account_group g ON g.id = a.group_id
                WHERE a.id = ?
                """, (rs, rowNum) -> mapRealAccountRow(rs, latestAmountMap), id);
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

        long snapshotId = latestSnapshotIds.get(0);
        Map<Long, BigDecimal> latestAmounts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT d.account_id, d.amount
                FROM asset_snapshot_detail d
                JOIN asset_account a ON a.id = d.account_id
                WHERE d.snapshot_id = ?
                """, (rs, rowNum) -> latestAmounts.put(rs.getLong("account_id"), rs.getBigDecimal("amount")), snapshotId);
        jdbcTemplate.query("""
                SELECT group_id, amount
                FROM asset_snapshot_group_detail
                WHERE snapshot_id = ?
                """, (rs, rowNum) -> latestAmounts.put(toVirtualGroupId(rs.getLong("group_id")), rs.getBigDecimal("amount")), snapshotId);
        return latestAmounts;
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId() {
        return findDetailsBySnapshotId(null);
    }

    private Map<Long, List<AssetSnapshotDetailDto>> findDetailsBySnapshotId(List<Long> snapshotIds) {
        String snapshotPredicate = "";
        Object[] args = new Object[0];
        if (snapshotIds != null && !snapshotIds.isEmpty()) {
            String placeholders = String.join(",", snapshotIds.stream().map(id -> "?").toList());
            snapshotPredicate = " AND snapshot_id IN (" + placeholders + ")";
            args = snapshotIds.toArray();
        }

        List<DetailRow> rows = new ArrayList<>();
        rows.addAll(jdbcTemplate.query("""
                SELECT gd.snapshot_id,
                       g.group_code AS account_code,
                       g.group_name AS account_name,
                       g.category_group,
                       g.account_type,
                       NULL AS parent_account_id,
                       1 AS is_summary,
                       g.balance_direction,
                       gd.currency_code,
                       gd.amount,
                       gd.original_amount,
                       gd.amount_source,
                       gd.is_computed,
                       gd.remark,
                       NULL AS tags,
                       g.sort_order,
                       -g.id AS account_id,
                       g.id AS display_id
                FROM asset_snapshot_group_detail gd
                JOIN asset_account_group g ON g.id = gd.group_id
                WHERE 1 = 1
                """ + snapshotPredicate, (rs, rowNum) -> mapDetailRow(rs), args));

        rows.addAll(jdbcTemplate.query("""
                SELECT d.snapshot_id,
                       a.account_code,
                       a.account_name,
                       COALESCE(g.category_group, a.category_group) AS category_group,
                       a.account_type,
                       CASE WHEN a.group_id IS NULL THEN NULL ELSE -a.group_id END AS parent_account_id,
                       0 AS is_summary,
                       a.balance_direction,
                       d.currency_code,
                       d.amount,
                       d.original_amount,
                       d.amount_source,
                       d.is_computed,
                       d.remark,
                       a.tags,
                       a.sort_order,
                       a.id AS account_id,
                       a.id AS display_id
                FROM asset_snapshot_detail d
                JOIN asset_account a ON a.id = d.account_id
                LEFT JOIN asset_account_group g ON g.id = a.group_id
                WHERE 1 = 1
                """ + snapshotPredicate.replace("snapshot_id", "d.snapshot_id"), (rs, rowNum) -> mapDetailRow(rs), args));

        rows.sort(Comparator
                .comparing(DetailRow::snapshotId, Comparator.reverseOrder())
                .thenComparing(DetailRow::sortOrder)
                .thenComparing(row -> Boolean.TRUE.equals(row.summaryAccount()) ? 0 : 1)
                .thenComparing(DetailRow::displayId));

        Map<Long, List<AssetSnapshotDetailDto>> detailMap = new LinkedHashMap<>();
        for (DetailRow row : rows) {
            detailMap.computeIfAbsent(row.snapshotId(), ignored -> new ArrayList<>())
                    .add(new AssetSnapshotDetailDto(
                            row.accountId(),
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
                            row.remark(),
                            row.tags()
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
                rs.getLong("account_id"),
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
                rs.getString("remark"),
                parseTags(rs.getString("tags")),
                rs.getInt("sort_order"),
                rs.getLong("display_id")
        );
    }

    private AssetAccountOptionDto mapGroupRow(ResultSet rs, Map<Long, BigDecimal> latestAmountMap) throws SQLException {
        long groupId = rs.getLong("id");
        long virtualId = toVirtualGroupId(groupId);
        return new AssetAccountOptionDto(
                virtualId,
                rs.getString("group_code"),
                rs.getString("group_name"),
                rs.getString("account_type"),
                rs.getString("category_group"),
                null,
                true,
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getString("institution_name"),
                rs.getString("owner_name"),
                rs.getString("remark"),
                rs.getInt("sort_order"),
                rs.getInt("enabled") == 1,
                List.of(),
                latestAmountMap.get(virtualId)
        );
    }

    private AssetAccountOptionDto mapRealAccountRow(ResultSet rs, Map<Long, BigDecimal> latestAmountMap) throws SQLException {
        long id = rs.getLong("id");
        Long groupId = toNullableLong(rs.getObject("group_id"));
        return new AssetAccountOptionDto(
                id,
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("account_type"),
                resolveCategoryGroup(rs),
                groupId == null ? null : toVirtualGroupId(groupId),
                false,
                rs.getString("balance_direction"),
                rs.getString("currency_code"),
                rs.getString("institution_name"),
                rs.getString("owner_name"),
                rs.getString("remark"),
                rs.getInt("sort_order"),
                rs.getInt("enabled") == 1,
                parseTags(rs.getString("tags")),
                latestAmountMap.get(id)
        );
    }

    private Comparator<AssetAccountOptionDto> accountComparator() {
        return Comparator
                .comparingInt((AssetAccountOptionDto account) -> categoryOrder(account.categoryGroup()))
                .thenComparing(account -> account.sortOrder() == null ? 0 : account.sortOrder())
                .thenComparing(account -> account.parentAccountId() == null ? 0 : 1)
                .thenComparing(AssetAccountOptionDto::id);
    }

    private int categoryOrder(String categoryGroup) {
        return switch (categoryGroup) {
            case "CASH" -> 0;
            case "INVESTMENT" -> 1;
            case "LIABILITY" -> 2;
            default -> 99;
        };
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

    private long toVirtualGroupId(long groupId) {
        return groupId * -1;
    }

    private long toGroupId(long virtualGroupId) {
        return Math.abs(virtualGroupId);
    }

    private List<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(rawTags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
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
            Long accountId,
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
            String remark,
            List<String> tags,
            Integer sortOrder,
            Long displayId
    ) {
    }
}
