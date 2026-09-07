package com.water.server.snapshot;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DictionaryService {
    private final JdbcTemplate jdbc;

    public DictionaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("dictionary-schema.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(jdbc.getDataSource());
    }

    public List<Entry> list() {
        return jdbc.query("SELECT * FROM config_dictionary ORDER BY dict_type, dict_key", (rs, n) ->
                new Entry(rs.getString("dict_type"), rs.getString("dict_key"), new BigDecimal(rs.getString("dict_value")),
                        rs.getString("display_name"), rs.getString("remark"), rs.getString("updated_at")));
    }

    public BigDecimal hkdToCnyRate() {
        return list().stream().filter(e -> e.dictType().equals("EXCHANGE_RATE") && e.dictKey().equals("HKD_CNY"))
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "HKD_CNY rate is not configured")).value();
    }

    public Map<String, BigDecimal> tagAdjustments() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        list().stream().filter(e -> e.dictType().equals("TAG_ADJUSTMENT")).forEach(e -> result.put(e.dictKey(), e.value()));
        return result;
    }

    @Transactional
    public List<Entry> save(List<Input> entries, boolean missingOnly) {
        if (entries == null || entries.size() > 100) throw badRequest("Provide at most 100 dictionary entries");
        entries.forEach(this::validate);
        for (Input entry : entries) {
            String conflict = missingOnly ? "DO NOTHING" : """
                    DO UPDATE SET dict_value = excluded.dict_value, display_name = excluded.display_name,
                    remark = excluded.remark, updated_at = CURRENT_TIMESTAMP
                    """;
            jdbc.update("""
                    INSERT INTO config_dictionary (dict_type, dict_key, dict_value, display_name, remark)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT(dict_type, dict_key)
                    """ + conflict, entry.dictType(), entry.dictKey(), entry.value().toPlainString(),
                    entry.displayName() == null || entry.displayName().isBlank() ? entry.dictKey() : entry.displayName(), entry.remark());
        }
        return list();
    }

    private void validate(Input entry) {
        if (entry == null || entry.dictKey() == null || entry.dictKey().isBlank() || entry.dictKey().length() > 100
                || !entry.dictKey().equals(entry.dictKey().trim()) || entry.value() == null
                || entry.value().precision() > 24 || Math.abs(entry.value().scale()) > 8
                || entry.displayName() != null && entry.displayName().length() > 100
                || entry.remark() != null && entry.remark().length() > 1000) {
            throw badRequest("Invalid dictionary entry; use a decimal with up to 24 digits and 8 decimal places");
        }
        if ("EXCHANGE_RATE".equals(entry.dictType())) {
            if (!"HKD_CNY".equals(entry.dictKey()) || entry.value().signum() <= 0) {
                throw badRequest("HKD_CNY exchange rate must be positive");
            }
        } else if (!"TAG_ADJUSTMENT".equals(entry.dictType())) {
            throw badRequest("Unsupported dictionary type");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(BAD_REQUEST, message);
    }

    public record Input(String dictType, String dictKey, BigDecimal value, String displayName, String remark) {}
    public record Entry(String dictType, String dictKey, BigDecimal value, String displayName, String remark, String updatedAt) {}
}
