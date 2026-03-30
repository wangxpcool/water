package com.water.server.snapshot;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class AssetAccountCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final AssetSnapshotQueryService assetSnapshotQueryService;

    public AssetAccountCommandService(JdbcTemplate jdbcTemplate, AssetSnapshotQueryService assetSnapshotQueryService) {
        this.jdbcTemplate = jdbcTemplate;
        this.assetSnapshotQueryService = assetSnapshotQueryService;
    }

    @Transactional
    public AssetAccountOptionDto createAccount(AssetAccountUpsertRequest request) {
        validateParentAccount(null, request);
        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO asset_account (
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
                        enabled,
                        updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, Statement.RETURN_GENERATED_KEYS);
                bindStatement(statement, request);
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateCodeException(request.accountCode(), exception);
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create account id");
        }
        return assetSnapshotQueryService.findAccountById(key.longValue());
    }

    @Transactional
    public AssetAccountOptionDto updateAccount(long id, AssetAccountUpsertRequest request) {
        assertAccountExists(id);
        validateParentAccount(id, request);
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE asset_account
                    SET account_code = ?,
                        account_name = ?,
                        category_group = ?,
                        account_type = ?,
                        parent_account_id = ?,
                        is_summary = ?,
                        balance_direction = ?,
                        currency_code = ?,
                        institution_name = ?,
                        owner_name = ?,
                        remark = ?,
                        sort_order = ?,
                        enabled = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, ps -> {
                bindStatement(ps, request);
                ps.setLong(14, id);
            });

            if (updated == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateCodeException(request.accountCode(), exception);
        }

        return assetSnapshotQueryService.findAccountById(id);
    }

    @Transactional
    public void deleteAccount(long id) {
        assertAccountExists(id);

        List<Integer> references = jdbcTemplate.query(
                "SELECT 1 FROM asset_snapshot_detail WHERE account_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getInt(1),
                id
        );
        if (!references.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Account is referenced by snapshot details and cannot be deleted");
        }

        jdbcTemplate.update("DELETE FROM asset_account WHERE id = ?", id);
    }

    private void assertAccountExists(long id) {
        List<Integer> exists = jdbcTemplate.query(
                "SELECT 1 FROM asset_account WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1),
                id
        );
        if (exists.isEmpty()) {
            throw notFound(id);
        }
    }

    private void validateParentAccount(Long currentId, AssetAccountUpsertRequest request) {
        if (request.parentAccountId() == null) {
            return;
        }
        if (currentId != null && currentId.equals(request.parentAccountId())) {
            throw new ResponseStatusException(CONFLICT, "Account cannot use itself as parent");
        }

        AssetAccountOptionDto parent = assetSnapshotQueryService.findAccountById(request.parentAccountId());
        if (!Boolean.TRUE.equals(parent.summaryAccount())) {
            throw new ResponseStatusException(CONFLICT, "Parent account must be a summary account");
        }
    }

    private void bindStatement(PreparedStatement statement, AssetAccountUpsertRequest request) throws java.sql.SQLException {
        statement.setString(1, normalizeCode(request.accountCode()));
        statement.setString(2, request.accountName().trim());
        String accountType = request.accountType().trim().toUpperCase(Locale.ROOT);
        statement.setString(3, normalizeCategoryGroup(request.categoryGroup()));
        statement.setString(4, accountType);
        if (request.parentAccountId() == null) {
            statement.setNull(5, Types.INTEGER);
        } else {
            statement.setLong(5, request.parentAccountId());
        }
        statement.setInt(6, request.summaryAccount() ? 1 : 0);
        statement.setString(7, request.balanceDirection().trim().toUpperCase(Locale.ROOT));
        statement.setString(8, request.currencyCode().trim().toUpperCase(Locale.ROOT));
        statement.setString(9, blankToNull(request.institutionName()));
        statement.setString(10, blankToNull(request.ownerName()));
        statement.setString(11, blankToNull(request.remark()));
        statement.setInt(12, request.sortOrder());
        statement.setInt(13, request.enabled() ? 1 : 0);
    }

    private String normalizeCode(String accountCode) {
        return accountCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeCategoryGroup(String categoryGroup) {
        try {
            return AccountCategoryGroup.fromCode(categoryGroup).code();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(CONFLICT, exception.getMessage(), exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseStatusException duplicateCodeException(String accountCode, Exception cause) {
        return new ResponseStatusException(CONFLICT, "Account code already exists: " + accountCode, cause);
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(NOT_FOUND, "Account not found: " + id);
    }
}
