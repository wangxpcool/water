package com.water.server.snapshot;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AssetSnapshotCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final AssetSnapshotQueryService assetSnapshotQueryService;

    public AssetSnapshotCommandService(JdbcTemplate jdbcTemplate, AssetSnapshotQueryService assetSnapshotQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.assetSnapshotQueryService = assetSnapshotQueryService;
    }

    @Transactional
    public AssetSnapshotResponse createSnapshot(AssetSnapshotUpsertRequest request) {
        List<AssetAccountOptionDto> accounts = assetSnapshotQueryService.findEnabledAccounts();
        SnapshotAggregate aggregate = buildAggregate(request, accounts);
        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO asset_snapshot (
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
                            source_row_number,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, Statement.RETURN_GENERATED_KEYS);
                bindSnapshotStatement(statement, request, aggregate);
                statement.setNull(15, Types.INTEGER);
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateDateException(request.snapshotDate().toString(), exception);
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create snapshot id");
        }

        saveDetails(key.longValue(), aggregate.persistedDetails());
        return assetSnapshotQueryService.findSnapshotById(key.longValue());
    }

    @Transactional
    public AssetSnapshotResponse updateSnapshot(long id, AssetSnapshotUpsertRequest request) {
        assertSnapshotExists(id);
        List<AssetAccountOptionDto> accounts = assetSnapshotQueryService.findEnabledAccounts();
        SnapshotAggregate aggregate = buildAggregate(request, accounts);

        try {
            int updated = jdbcTemplate.update("""
                    UPDATE asset_snapshot
                    SET snapshot_date = ?,
                        income = ?,
                        fixed_expense = ?,
                        cash_total = ?,
                        investment_total = ?,
                        liability_total = ?,
                        gross_account_value = ?,
                        profit_loss = ?,
                        net_worth = ?,
                        public_funds = ?,
                        extra_amount = ?,
                        balance = ?,
                        note = ?,
                        remark = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, ps -> {
                bindSnapshotStatement(ps, request, aggregate);
                ps.setLong(15, id);
            });

            if (updated == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateDateException(request.snapshotDate().toString(), exception);
        }

        jdbcTemplate.update("DELETE FROM asset_snapshot_detail WHERE snapshot_id = ?", id);
        saveDetails(id, aggregate.persistedDetails());
        return assetSnapshotQueryService.findSnapshotById(id);
    }

    @Transactional
    public void deleteSnapshot(long id) {
        jdbcTemplate.update("DELETE FROM asset_snapshot_detail WHERE snapshot_id = ?", id);
        int deletedSnapshots = jdbcTemplate.update("DELETE FROM asset_snapshot WHERE id = ?", id);
        if (deletedSnapshots == 0) {
            throw notFound(id);
        }
    }

    private void assertSnapshotExists(long id) {
        List<Integer> exists = jdbcTemplate.query(
                "SELECT 1 FROM asset_snapshot WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1),
                id
        );
        if (exists.isEmpty()) {
            throw notFound(id);
        }
    }

    private SnapshotAggregate buildAggregate(AssetSnapshotUpsertRequest request, List<AssetAccountOptionDto> accounts) {
        Map<String, AssetAccountOptionDto> accountMap = accounts.stream()
                .collect(Collectors.toMap(
                        AssetAccountOptionDto::accountCode,
                        account -> account,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<Long, List<AssetAccountOptionDto>> childrenByParent = accounts.stream()
                .filter(account -> account.parentAccountId() != null)
                .collect(Collectors.groupingBy(
                        AssetAccountOptionDto::parentAccountId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<AssetSnapshotDetailUpsertRequest> normalizedInput = normalizeInputDetails(request.details(), accountMap);
        Map<Long, PersistedDetail> persistedByAccountId = new LinkedHashMap<>();
        for (AssetSnapshotDetailUpsertRequest detail : normalizedInput) {
            AssetAccountOptionDto account = accountMap.get(detail.accountCode());
            persistedByAccountId.put(account.id(), new PersistedDetail(
                    account.id(),
                    detail.amount(),
                    detail.originalAmount(),
                    normalizeCurrencyCode(detail.currencyCode(), account.currencyCode()),
                    "MANUAL",
                    false,
                    blankToNull(detail.remark())
            ));
        }

        for (AssetAccountOptionDto account : accounts) {
            computeSummaryDetail(account, childrenByParent, persistedByAccountId);
        }

        BigDecimal cashTotal = BigDecimal.ZERO;
        BigDecimal investmentTotal = BigDecimal.ZERO;
        BigDecimal liabilityTotal = BigDecimal.ZERO;
        BigDecimal grossAccountValue = BigDecimal.ZERO;

        for (AssetAccountOptionDto account : accounts) {
            if (Boolean.TRUE.equals(account.summaryAccount())) {
                continue;
            }
            PersistedDetail detail = persistedByAccountId.get(account.id());
            if (detail == null || detail.amount() == null) {
                continue;
            }

            BigDecimal amount = detail.amount();
            if ("DEBT".equals(account.balanceDirection())) {
                liabilityTotal = liabilityTotal.add(amount);
            } else {
                grossAccountValue = grossAccountValue.add(amount);
            }

            String categoryGroup = account.categoryGroup();
            if ("CASH".equals(categoryGroup) && "ASSET".equals(account.balanceDirection())) {
                cashTotal = cashTotal.add(amount);
            } else if ("INVESTMENT".equals(categoryGroup)) {
                investmentTotal = investmentTotal.add(
                        "DEBT".equals(account.balanceDirection()) ? amount.negate() : amount
                );
            }
        }

        return new SnapshotAggregate(
                persistedByAccountId.values().stream().toList(),
                cashTotal,
                investmentTotal,
                liabilityTotal,
                grossAccountValue,
                grossAccountValue.subtract(liabilityTotal)
        );
    }

    private List<AssetSnapshotDetailUpsertRequest> normalizeInputDetails(
            List<AssetSnapshotDetailUpsertRequest> details,
            Map<String, AssetAccountOptionDto> accountMap
    ) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }

        List<AssetSnapshotDetailUpsertRequest> normalized = details.stream()
                .filter(Objects::nonNull)
                .filter(detail -> detail.amount() != null)
                .toList();

        validateDuplicateAccounts(normalized);

        for (AssetSnapshotDetailUpsertRequest detail : normalized) {
            String accountCode = blankToNull(detail.accountCode());
            AssetAccountOptionDto account = accountMap.get(accountCode);
            if (account == null) {
                throw new ResponseStatusException(CONFLICT, "Unknown accountCode: " + detail.accountCode());
            }
            if (Boolean.TRUE.equals(account.summaryAccount())) {
                throw new ResponseStatusException(CONFLICT, "Summary account cannot accept manual amount: " + account.accountCode());
            }
        }

        return normalized;
    }

    private PersistedDetail computeSummaryDetail(
            AssetAccountOptionDto account,
            Map<Long, List<AssetAccountOptionDto>> childrenByParent,
            Map<Long, PersistedDetail> persistedByAccountId
    ) {
        PersistedDetail existing = persistedByAccountId.get(account.id());
        if (existing != null || !Boolean.TRUE.equals(account.summaryAccount())) {
            return existing;
        }

        List<AssetAccountOptionDto> children = childrenByParent.getOrDefault(account.id(), List.of());
        if (children.isEmpty()) {
            return null;
        }

        BigDecimal sum = null;
        for (AssetAccountOptionDto child : children) {
            PersistedDetail childDetail = computeSummaryDetail(child, childrenByParent, persistedByAccountId);
            if (childDetail == null || childDetail.amount() == null) {
                continue;
            }
            sum = sum == null ? childDetail.amount() : sum.add(childDetail.amount());
        }

        if (sum == null) {
            return null;
        }

        PersistedDetail summary = new PersistedDetail(
                account.id(),
                sum,
                null,
                account.currencyCode(),
                "ROLLED_UP",
                true,
                null
        );
        persistedByAccountId.put(account.id(), summary);
        return summary;
    }

    private void saveDetails(long snapshotId, List<PersistedDetail> details) {
        if (details.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO asset_snapshot_detail (
                    snapshot_id,
                    account_id,
                    amount,
                    original_amount,
                    currency_code,
                    amount_source,
                    is_computed,
                    remark,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, details, details.size(), (ps, detail) -> {
            ps.setLong(1, snapshotId);
            ps.setLong(2, detail.accountId());
            ps.setBigDecimal(3, detail.amount());
            if (detail.originalAmount() == null) {
                ps.setNull(4, Types.NUMERIC);
            } else {
                ps.setBigDecimal(4, detail.originalAmount());
            }
            ps.setString(5, detail.currencyCode());
            ps.setString(6, detail.amountSource());
            ps.setInt(7, detail.computed() ? 1 : 0);
            ps.setString(8, detail.remark());
        });
    }

    private void validateDuplicateAccounts(List<AssetSnapshotDetailUpsertRequest> details) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AssetSnapshotDetailUpsertRequest detail : details) {
            String accountCode = blankToNull(detail.accountCode());
            if (accountCode == null) {
                throw new ResponseStatusException(CONFLICT, "detail.accountCode is required");
            }
            counts.merge(accountCode, 1, Integer::sum);
            if (counts.get(accountCode) > 1) {
                throw new ResponseStatusException(CONFLICT, "Duplicate accountCode in details: " + accountCode);
            }
        }
    }

    private void bindSnapshotStatement(
            PreparedStatement statement,
            AssetSnapshotUpsertRequest request,
            SnapshotAggregate aggregate
    ) throws java.sql.SQLException {
        statement.setString(1, request.snapshotDate().toString());
        setBigDecimal(statement, 2, request.income());
        setBigDecimal(statement, 3, request.fixedExpense());
        setBigDecimal(statement, 4, aggregate.cashTotal());
        setBigDecimal(statement, 5, aggregate.investmentTotal());
        setBigDecimal(statement, 6, aggregate.liabilityTotal());
        setBigDecimal(statement, 7, aggregate.grossAccountValue());
        setBigDecimal(statement, 8, request.profitLoss());
        setBigDecimal(statement, 9, aggregate.netWorth());
        setBigDecimal(statement, 10, request.publicFunds());
        setBigDecimal(statement, 11, request.extraAmount());
        setBigDecimal(statement, 12, request.balance());
        statement.setString(13, blankToNull(request.note()));
        statement.setString(14, blankToNull(request.remark()));
    }

    private void setBigDecimal(PreparedStatement statement, int index, BigDecimal value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
            return;
        }
        statement.setBigDecimal(index, value);
    }

    private String normalizeCurrencyCode(String requested, String fallback) {
        return blankToNull(requested) == null ? fallback : requested.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException duplicateDateException(String snapshotDate, Exception cause) {
        return new ResponseStatusException(CONFLICT, "Snapshot date already exists: " + snapshotDate, cause);
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(NOT_FOUND, "Snapshot not found: " + id);
    }

    private record PersistedDetail(
            Long accountId,
            BigDecimal amount,
            BigDecimal originalAmount,
            String currencyCode,
            String amountSource,
            boolean computed,
            String remark
    ) {
    }

    private record SnapshotAggregate(
            List<PersistedDetail> persistedDetails,
            BigDecimal cashTotal,
            BigDecimal investmentTotal,
            BigDecimal liabilityTotal,
            BigDecimal grossAccountValue,
            BigDecimal netWorth
    ) {
    }
}
