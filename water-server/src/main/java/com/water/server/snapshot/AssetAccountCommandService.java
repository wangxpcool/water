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
        if (Boolean.TRUE.equals(request.summaryAccount())) {
            return createGroup(request);
        }
        return createRealAccount(request);
    }

    @Transactional
    public AssetAccountOptionDto updateAccount(long id, AssetAccountUpsertRequest request) {
        if (id < 0) {
            if (!Boolean.TRUE.equals(request.summaryAccount())) {
                throw new ResponseStatusException(CONFLICT, "Account group cannot be changed into a child account");
            }
            return updateGroup(id, request);
        }
        if (Boolean.TRUE.equals(request.summaryAccount())) {
            throw new ResponseStatusException(CONFLICT, "Child account cannot be changed into an account group");
        }
        return updateRealAccount(id, request);
    }

    @Transactional
    public void deleteAccount(long id) {
        if (id < 0) {
            deleteGroup(toGroupId(id));
            return;
        }
        deleteRealAccount(id);
    }

    private AssetAccountOptionDto createGroup(AssetAccountUpsertRequest request) {
        assertNoRealAccountCodeConflict(request.accountCode(), null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO asset_account_group (
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
                            enabled,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, Statement.RETURN_GENERATED_KEYS);
                bindGroupStatement(statement, request);
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateCodeException(request.accountCode(), exception);
        }

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create account group id");
        }
        return assetSnapshotQueryService.findAccountById(toVirtualGroupId(key.longValue()));
    }

    private AssetAccountOptionDto createRealAccount(AssetAccountUpsertRequest request) {
        validateGroup(request.parentAccountId());
        assertNoGroupCodeConflict(request.accountCode(), request.parentAccountId());
        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO asset_account (
                            account_code,
                            account_name,
                            category_group,
                            account_type,
                            group_id,
                            parent_account_id,
                            is_summary,
                            balance_direction,
                            currency_code,
                            institution_name,
                            owner_name,
                            remark,
                            tags,
                            sort_order,
                            enabled,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, Statement.RETURN_GENERATED_KEYS);
                bindRealAccountStatement(statement, request);
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

    private AssetAccountOptionDto updateGroup(long virtualGroupId, AssetAccountUpsertRequest request) {
        long groupId = toGroupId(virtualGroupId);
        assertGroupExists(groupId);
        assertNoRealAccountCodeConflict(request.accountCode(), groupId);
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE asset_account_group
                    SET group_code = ?,
                        group_name = ?,
                        category_group = ?,
                        account_type = ?,
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
                bindGroupStatement(ps, request);
                ps.setLong(12, groupId);
            });

            if (updated == 0) {
                throw notFound(virtualGroupId);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateCodeException(request.accountCode(), exception);
        }
        return assetSnapshotQueryService.findAccountById(virtualGroupId);
    }

    private AssetAccountOptionDto updateRealAccount(long id, AssetAccountUpsertRequest request) {
        assertRealAccountExists(id);
        validateGroup(request.parentAccountId());
        assertNoGroupCodeConflict(request.accountCode(), request.parentAccountId());
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE asset_account
                    SET account_code = ?,
                        account_name = ?,
                        category_group = ?,
                        account_type = ?,
                        group_id = ?,
                        parent_account_id = ?,
                        is_summary = 0,
                        balance_direction = ?,
                        currency_code = ?,
                        institution_name = ?,
                        owner_name = ?,
                        remark = ?,
                        tags = ?,
                        sort_order = ?,
                        enabled = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, ps -> {
                bindRealAccountStatement(ps, request);
                ps.setLong(15, id);
            });

            if (updated == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateCodeException(request.accountCode(), exception);
        }
        return assetSnapshotQueryService.findAccountById(id);
    }

    private void deleteGroup(long groupId) {
        assertGroupExists(groupId);

        List<Integer> childAccounts = jdbcTemplate.query(
                "SELECT 1 FROM asset_account WHERE group_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getInt(1),
                groupId
        );
        if (!childAccounts.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Account group has child accounts and cannot be deleted");
        }

        List<Integer> references = jdbcTemplate.query(
                "SELECT 1 FROM asset_snapshot_group_detail WHERE group_id = ? LIMIT 1",
                (rs, rowNum) -> rs.getInt(1),
                groupId
        );
        if (!references.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Account group is referenced by snapshot details and cannot be deleted");
        }

        jdbcTemplate.update("DELETE FROM asset_account_group WHERE id = ?", groupId);
    }

    private void deleteRealAccount(long id) {
        assertRealAccountExists(id);

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

    private void assertGroupExists(long groupId) {
        List<Integer> exists = jdbcTemplate.query(
                "SELECT 1 FROM asset_account_group WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1),
                groupId
        );
        if (exists.isEmpty()) {
            throw notFound(toVirtualGroupId(groupId));
        }
    }

    private void assertRealAccountExists(long id) {
        List<Integer> exists = jdbcTemplate.query(
                "SELECT 1 FROM asset_account WHERE id = ?",
                (rs, rowNum) -> rs.getInt(1),
                id
        );
        if (exists.isEmpty()) {
            throw notFound(id);
        }
    }

    private void validateGroup(Long virtualGroupId) {
        if (virtualGroupId == null) {
            return;
        }
        if (virtualGroupId >= 0) {
            throw new ResponseStatusException(CONFLICT, "Parent account must be an account group");
        }
        assertGroupExists(toGroupId(virtualGroupId));
    }

    private void assertNoRealAccountCodeConflict(String accountCode, Long allowedGroupId) {
        List<Integer> conflicts = jdbcTemplate.query(
                """
                SELECT 1
                FROM asset_account a
                WHERE a.account_code = ?
                  AND (? IS NULL OR a.group_id IS NULL OR a.group_id <> ?)
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getInt(1),
                normalizeCode(accountCode),
                allowedGroupId,
                allowedGroupId
        );
        if (!conflicts.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Account code already exists: " + accountCode);
        }
    }

    private void assertNoGroupCodeConflict(String accountCode, Long allowedVirtualGroupId) {
        Long allowedGroupId = allowedVirtualGroupId == null ? null : toGroupId(allowedVirtualGroupId);
        List<Integer> conflicts = jdbcTemplate.query(
                """
                SELECT 1
                FROM asset_account_group
                WHERE group_code = ?
                  AND (? IS NULL OR id <> ?)
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getInt(1),
                normalizeCode(accountCode),
                allowedGroupId,
                allowedGroupId
        );
        if (!conflicts.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, "Account code already exists: " + accountCode);
        }
    }

    private void bindGroupStatement(PreparedStatement statement, AssetAccountUpsertRequest request) throws java.sql.SQLException {
        statement.setString(1, normalizeCode(request.accountCode()));
        statement.setString(2, request.accountName().trim());
        statement.setString(3, normalizeCategoryGroup(request.categoryGroup()));
        statement.setString(4, request.accountType().trim().toUpperCase(Locale.ROOT));
        statement.setString(5, request.balanceDirection().trim().toUpperCase(Locale.ROOT));
        statement.setString(6, request.currencyCode().trim().toUpperCase(Locale.ROOT));
        statement.setString(7, blankToNull(request.institutionName()));
        statement.setString(8, blankToNull(request.ownerName()));
        statement.setString(9, blankToNull(request.remark()));
        statement.setInt(10, request.sortOrder());
        statement.setInt(11, request.enabled() ? 1 : 0);
    }

    private void bindRealAccountStatement(PreparedStatement statement, AssetAccountUpsertRequest request) throws java.sql.SQLException {
        Long groupId = request.parentAccountId() == null ? null : toGroupId(request.parentAccountId());
        statement.setString(1, normalizeCode(request.accountCode()));
        statement.setString(2, request.accountName().trim());
        statement.setString(3, normalizeCategoryGroup(request.categoryGroup()));
        statement.setString(4, request.accountType().trim().toUpperCase(Locale.ROOT));
        if (groupId == null) {
            statement.setNull(5, Types.INTEGER);
            statement.setNull(6, Types.INTEGER);
        } else {
            statement.setLong(5, groupId);
            statement.setLong(6, groupId);
        }
        statement.setString(7, request.balanceDirection().trim().toUpperCase(Locale.ROOT));
        statement.setString(8, request.currencyCode().trim().toUpperCase(Locale.ROOT));
        statement.setString(9, blankToNull(request.institutionName()));
        statement.setString(10, blankToNull(request.ownerName()));
        statement.setString(11, blankToNull(request.remark()));
        statement.setString(12, serializeTags(request.tags()));
        statement.setInt(13, request.sortOrder());
        statement.setInt(14, request.enabled() ? 1 : 0);
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

    private String serializeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        String serialized = tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return serialized.isBlank() ? null : serialized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private long toVirtualGroupId(long groupId) {
        return groupId * -1;
    }

    private long toGroupId(long virtualGroupId) {
        return Math.abs(virtualGroupId);
    }

    private ResponseStatusException duplicateCodeException(String accountCode, Exception cause) {
        return new ResponseStatusException(CONFLICT, "Account code already exists: " + accountCode, cause);
    }

    private ResponseStatusException notFound(long id) {
        return new ResponseStatusException(NOT_FOUND, "Account not found: " + id);
    }
}
