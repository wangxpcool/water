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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        Map<String, AssetAccountOptionDto> accountMap = loadAccountMap();
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
                bindSnapshotStatement(statement, request);
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

        saveDetails(key.longValue(), request.details(), accountMap);
        return assetSnapshotQueryService.findSnapshotById(key.longValue());
    }

    @Transactional
    public AssetSnapshotResponse updateSnapshot(long id, AssetSnapshotUpsertRequest request) {
        assertSnapshotExists(id);
        Map<String, AssetAccountOptionDto> accountMap = loadAccountMap();

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
                bindSnapshotStatement(ps, request);
                ps.setLong(15, id);
            });

            if (updated == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateDateException(request.snapshotDate().toString(), exception);
        }

        jdbcTemplate.update("DELETE FROM asset_snapshot_detail WHERE snapshot_id = ?", id);
        saveDetails(id, request.details(), accountMap);
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

    private void saveDetails(
            long snapshotId,
            List<AssetSnapshotDetailUpsertRequest> details,
            Map<String, AssetAccountOptionDto> accountMap
    ) {
        if (details == null || details.isEmpty()) {
            return;
        }

        List<AssetSnapshotDetailUpsertRequest> normalized = details.stream()
                .filter(Objects::nonNull)
                .filter(detail -> detail.amount() != null)
                .toList();

        validateDuplicateAccounts(normalized);

        jdbcTemplate.batchUpdate("""
                INSERT INTO asset_snapshot_detail (
                    snapshot_id,
                    account_id,
                    amount,
                    original_amount,
                    currency_code,
                    remark,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, normalized, normalized.size(), (ps, detail) -> {
            AssetAccountOptionDto account = accountMap.get(detail.accountCode());
            if (account == null) {
                throw new ResponseStatusException(CONFLICT, "Unknown accountCode: " + detail.accountCode());
            }

            ps.setLong(1, snapshotId);
            ps.setLong(2, account.id());
            ps.setBigDecimal(3, detail.amount());
            if (detail.originalAmount() == null) {
                ps.setNull(4, Types.NUMERIC);
            } else {
                ps.setBigDecimal(4, detail.originalAmount());
            }
            ps.setString(5, normalizeCurrencyCode(detail.currencyCode(), account.currencyCode()));
            ps.setString(6, blankToNull(detail.remark()));
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

    private Map<String, AssetAccountOptionDto> loadAccountMap() {
        return assetSnapshotQueryService.findEnabledAccounts().stream()
                .collect(java.util.stream.Collectors.toMap(
                        AssetAccountOptionDto::accountCode,
                        account -> account,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private void bindSnapshotStatement(PreparedStatement statement, AssetSnapshotUpsertRequest request) throws java.sql.SQLException {
        statement.setString(1, request.snapshotDate().toString());
        setBigDecimal(statement, 2, request.income());
        setBigDecimal(statement, 3, request.fixedExpense());
        setBigDecimal(statement, 4, request.cashTotal());
        setBigDecimal(statement, 5, request.investmentTotal());
        setBigDecimal(statement, 6, request.liabilityTotal());
        setBigDecimal(statement, 7, request.grossAccountValue());
        setBigDecimal(statement, 8, request.profitLoss());
        setBigDecimal(statement, 9, request.netWorth());
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
}
