package com.water.server.snapshot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Account definitions belong to the snapshot, so later account edits cannot rewrite history. */
@Service
public class SnapshotContextService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SnapshotContextService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS asset_snapshot_context (
                    snapshot_id INTEGER PRIMARY KEY,
                    accounts_json TEXT NOT NULL,
                    tag_adjustments_json TEXT NOT NULL DEFAULT '{}',
                    hkd_to_cny_rate TEXT NOT NULL DEFAULT '0.87'
                )
                """);
        if (!jdbc.query("PRAGMA table_info(asset_snapshot_context)", (rs, n) -> rs.getString("name")).contains("hkd_to_cny_rate")) {
            jdbc.execute("ALTER TABLE asset_snapshot_context ADD COLUMN hkd_to_cny_rate TEXT NOT NULL DEFAULT '0.87'");
        }
        // Unknown historical USD rates must remain unknown; never backfill with today's rate.
        if (!jdbc.query("PRAGMA table_info(asset_snapshot_context)", (rs, n) -> rs.getString("name")).contains("usd_to_cny_rate")) {
            jdbc.execute("ALTER TABLE asset_snapshot_context ADD COLUMN usd_to_cny_rate TEXT");
        }
    }

    public boolean exists(long id) {
        return !jdbc.queryForList("SELECT snapshot_id FROM asset_snapshot_context WHERE snapshot_id = ?", id).isEmpty();
    }

    public BigDecimal hkdToCnyRate(long id) {
        List<BigDecimal> values = jdbc.query("SELECT hkd_to_cny_rate FROM asset_snapshot_context WHERE snapshot_id = ?",
                (rs, n) -> new BigDecimal(rs.getString(1)), id);
        if (values.isEmpty()) throw new IllegalStateException("Missing historical exchange rate for snapshot " + id);
        return values.get(0);
    }

    public void saveRate(long id, BigDecimal rate) {
        jdbc.update("UPDATE asset_snapshot_context SET hkd_to_cny_rate = ? WHERE snapshot_id = ?", rate.toPlainString(), id);
    }

    public BigDecimal usdToCnyRate(long id) {
        List<String> values = jdbc.query("SELECT usd_to_cny_rate FROM asset_snapshot_context WHERE snapshot_id = ?",
                (rs, n) -> rs.getString(1), id);
        return values.isEmpty() || values.get(0) == null ? null : new BigDecimal(values.get(0));
    }

    public void saveUsdRate(long id, BigDecimal rate) {
        jdbc.update("UPDATE asset_snapshot_context SET usd_to_cny_rate = ? WHERE snapshot_id = ?",
                rate == null ? null : rate.toPlainString(), id);
    }

    public List<AssetAccountOptionDto> accounts(long id) {
        return read(id, "accounts_json", new TypeReference<List<AssetAccountOptionDto>>() {}, List.of());
    }

    public Map<String, BigDecimal> adjustments(long id) {
        return read(id, "tag_adjustments_json", new TypeReference<Map<String, BigDecimal>>() {}, Map.of());
    }

    private <T> T read(long id, String column, TypeReference<T> type, T fallback) {
        List<String> rows = jdbc.query("SELECT " + column + " FROM asset_snapshot_context WHERE snapshot_id = ?",
                (rs, n) -> rs.getString(1), id);
        if (rows.isEmpty()) return fallback;
        try {
            return mapper.readValue(rows.get(0), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read snapshot context " + id, e);
        }
    }

    public void save(long id, List<AssetAccountOptionDto> accounts, Map<String, BigDecimal> adjustments) {
        Map<String, BigDecimal> values = adjustments == null ? adjustments(id) : adjustments;
        try {
            jdbc.update("""
                    INSERT INTO asset_snapshot_context (snapshot_id, accounts_json, tag_adjustments_json)
                    VALUES (?, ?, ?) ON CONFLICT(snapshot_id) DO UPDATE SET
                    accounts_json = excluded.accounts_json, tag_adjustments_json = excluded.tag_adjustments_json
                    """, id, mapper.writeValueAsString(accounts), mapper.writeValueAsString(values));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot save snapshot context", e);
        }
    }
}
