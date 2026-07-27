package com.water.server.snapshot;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        jdbcTemplate.update("DELETE FROM asset_snapshot_group_detail WHERE snapshot_id = ?", id);
        saveDetails(id, aggregate.persistedDetails());
        return assetSnapshotQueryService.findSnapshotById(id);
    }

    @Transactional
    public void deleteSnapshot(long id) {
        jdbcTemplate.update("DELETE FROM asset_snapshot_detail WHERE snapshot_id = ?", id);
        jdbcTemplate.update("DELETE FROM asset_snapshot_group_detail WHERE snapshot_id = ?", id);
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
        Map<Long, AssetAccountOptionDto> accountsById = accounts.stream()
                .collect(Collectors.toMap(AssetAccountOptionDto::id, account -> account, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<AssetAccountOptionDto>> accountsByCode = accounts.stream()
                .collect(Collectors.groupingBy(
                        AssetAccountOptionDto::accountCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<Long, List<AssetAccountOptionDto>> childrenByParent = accounts.stream()
                .filter(account -> account.parentAccountId() != null)
                .collect(Collectors.groupingBy(
                        AssetAccountOptionDto::parentAccountId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<NormalizedInputDetail> normalizedInput = normalizeInputDetails(request.details(), accountsById, accountsByCode);
        Map<Long, PersistedDetail> persistedByAccountId = new LinkedHashMap<>();
        for (NormalizedInputDetail normalized : normalizedInput) {
            AssetSnapshotDetailUpsertRequest detail = normalized.detail();
            AssetAccountOptionDto account = normalized.account();
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
            if (account.parentAccountId() != null) {
                continue;
            }
            PersistedDetail detail = persistedByAccountId.get(account.id());
            if (detail == null || detail.amount() == null) {
                continue;
            }

            BigDecimal amount = amountInReportingCurrency(detail, account);
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

    private List<NormalizedInputDetail> normalizeInputDetails(
            List<AssetSnapshotDetailUpsertRequest> details,
            Map<Long, AssetAccountOptionDto> accountsById,
            Map<String, List<AssetAccountOptionDto>> accountsByCode
    ) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }

        List<AssetSnapshotDetailUpsertRequest> normalized = details.stream()
                .filter(Objects::nonNull)
                .filter(detail -> detail.amount() != null)
                .toList();

        List<NormalizedInputDetail> resolved = new ArrayList<>();
        for (AssetSnapshotDetailUpsertRequest detail : normalized) {
            resolved.add(new NormalizedInputDetail(detail, resolveInputAccount(detail, accountsById, accountsByCode)));
        }

        validateDuplicateAccounts(resolved);
        return resolved;
    }

    private AssetAccountOptionDto resolveInputAccount(
            AssetSnapshotDetailUpsertRequest detail,
            Map<Long, AssetAccountOptionDto> accountsById,
            Map<String, List<AssetAccountOptionDto>> accountsByCode
    ) {
        if (detail.accountId() != null) {
            AssetAccountOptionDto account = accountsById.get(detail.accountId());
            if (account == null) {
                throw new ResponseStatusException(CONFLICT, "Unknown accountId: " + detail.accountId());
            }
            return account;
        }

        String accountCode = blankToNull(detail.accountCode());
        if (accountCode == null) {
            throw new ResponseStatusException(CONFLICT, "detail.accountId or detail.accountCode is required");
        }

        List<AssetAccountOptionDto> matches = accountsByCode.getOrDefault(accountCode, List.of());
        if (matches.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Unknown accountCode: " + detail.accountCode());
        }
        if (matches.size() > 1) {
            throw new ResponseStatusException(CONFLICT, "Ambiguous accountCode, submit accountId: " + detail.accountCode());
        }
        return matches.get(0);
    }

    private PersistedDetail computeSummaryDetail(
            AssetAccountOptionDto account,
            Map<Long, List<AssetAccountOptionDto>> childrenByParent,
            Map<Long, PersistedDetail> persistedByAccountId
    ) {
        PersistedDetail existing = persistedByAccountId.get(account.id());
        if (!Boolean.TRUE.equals(account.summaryAccount())) {
            return existing;
        }

        List<AssetAccountOptionDto> children = rollupChildren(account, childrenByParent.getOrDefault(account.id(), List.of()));
        if (children.isEmpty()) {
            return existing;
        }

        BigDecimal sum = null;
        for (AssetAccountOptionDto child : children) {
            PersistedDetail childDetail = computeSummaryDetail(child, childrenByParent, persistedByAccountId);
            if (childDetail == null || childDetail.amount() == null) {
                continue;
            }
            BigDecimal childAmount = amountInReportingCurrency(childDetail, child);
            sum = sum == null ? childAmount : sum.add(childAmount);
        }

        if (sum == null) {
            return existing;
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

    private List<AssetAccountOptionDto> rollupChildren(AssetAccountOptionDto account, List<AssetAccountOptionDto> children) {
        if (children.size() <= 1) {
            return children;
        }

        List<AssetAccountOptionDto> withoutSameCodeMirror = children.stream()
                .filter(child -> !Objects.equals(child.accountCode(), account.accountCode()))
                .toList();
        return withoutSameCodeMirror.isEmpty() ? children : withoutSameCodeMirror;
    }

    private BigDecimal amountInReportingCurrency(PersistedDetail detail, AssetAccountOptionDto account) {
        String currencyCode = normalizeCurrencyCode(detail.currencyCode(), account.currencyCode());
        if ("HKD".equals(currencyCode)) {
            return detail.amount().multiply(new BigDecimal("0.87")).setScale(1, RoundingMode.HALF_UP);
        }
        return detail.amount();
    }

    private void saveDetails(long snapshotId, List<PersistedDetail> details) {
        List<PersistedDetail> accountDetails = details.stream()
                .filter(detail -> !isGroupDetail(detail))
                .toList();
        List<PersistedDetail> groupDetails = details.stream()
                .filter(this::isGroupDetail)
                .toList();

        if (!accountDetails.isEmpty()) {
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
                """, accountDetails, accountDetails.size(), (ps, detail) -> bindPersistedDetail(ps, snapshotId, detail, false));
        }

        if (!groupDetails.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                INSERT INTO asset_snapshot_group_detail (
                    snapshot_id,
                    group_id,
                    amount,
                    original_amount,
                    currency_code,
                    amount_source,
                    is_computed,
                    remark,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, groupDetails, groupDetails.size(), (ps, detail) -> bindPersistedDetail(ps, snapshotId, detail, true));
        }
    }

    private void bindPersistedDetail(
            PreparedStatement statement,
            long snapshotId,
            PersistedDetail detail,
            boolean groupDetail
    ) throws java.sql.SQLException {
        statement.setLong(1, snapshotId);
        statement.setLong(2, groupDetail ? Math.abs(detail.accountId()) : detail.accountId());
        statement.setBigDecimal(3, detail.amount());
        if (detail.originalAmount() == null) {
            statement.setNull(4, Types.NUMERIC);
        } else {
            statement.setBigDecimal(4, detail.originalAmount());
        }
        statement.setString(5, detail.currencyCode());
        statement.setString(6, detail.amountSource());
        statement.setInt(7, detail.computed() ? 1 : 0);
        statement.setString(8, detail.remark());
    }

    private boolean isGroupDetail(PersistedDetail detail) {
        return detail.accountId() < 0;
    }

    private void validateDuplicateAccounts(List<NormalizedInputDetail> details) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (NormalizedInputDetail detail : details) {
            Long accountId = detail.account().id();
            counts.merge(accountId, 1, Integer::sum);
            if (counts.get(accountId) > 1) {
                throw new ResponseStatusException(CONFLICT, "Duplicate accountId in details: " + accountId);
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

    private record NormalizedInputDetail(
            AssetSnapshotDetailUpsertRequest detail,
            AssetAccountOptionDto account
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
