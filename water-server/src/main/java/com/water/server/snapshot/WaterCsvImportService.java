package com.water.server.snapshot;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WaterCsvImportService {

    private static final DateTimeFormatter SLASH_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/M/d");
    private static final DateTimeFormatter COMPACT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("([a-zA-Z])([^a-zA-Z]*)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("([+-]?)(\\d+(?:\\.\\d+)?)");

    private static final Map<String, String> CASH_ITEM_NAMES = Map.of(
            "a", "alipay",
            "b", "wechat",
            "c", "bankCard"
    );

    private static final Map<String, String> INVESTMENT_ITEM_NAMES = Map.of(
            "a", "fund",
            "b", "aShare",
            "c", "usShare"
    );

    private static final Map<String, String> LIABILITY_ITEM_NAMES = Map.of(
            "a", "consumerLoan",
            "b", "creditCard"
    );

    public List<AssetSnapshotImportRecord> importFromPath(Path csvPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(csvPath);
             CSVParser parser = CSVFormat.DEFAULT.parse(reader)) {
            List<CSVRecord> rows = parser.getRecords();
            List<AssetSnapshotImportRecord> records = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                CSVRecord row = rows.get(i);
                if (isBlankTemplateRow(row)) {
                    continue;
                }
                records.add(toImportRecord(row, i + 1));
            }
            return records;
        }
    }

    AssetSnapshotImportRecord toImportRecord(CSVRecord row, int sourceRowNumber) {
        String snapshotText = valueAt(row, 0);
        LocalDate snapshotDate = parseDate(snapshotText);

        List<AssetSnapshotImportItem> cashItems = parseBreakdown(valueAt(row, 3), AssetCategoryType.CASH, CASH_ITEM_NAMES);
        List<AssetSnapshotImportItem> investmentItems = parseBreakdown(valueAt(row, 5), AssetCategoryType.INVESTMENT, INVESTMENT_ITEM_NAMES);
        List<AssetSnapshotImportItem> liabilityItems = parseBreakdown(valueAt(row, 7), AssetCategoryType.LIABILITY, LIABILITY_ITEM_NAMES);

        return new AssetSnapshotImportRecord(
                sourceRowNumber,
                snapshotDate,
                parseDecimal(valueAt(row, 1)),
                parseDecimal(valueAt(row, 2)),
                parseDecimal(valueAt(row, 4)),
                parseDecimal(valueAt(row, 6)),
                parseDecimal(valueAt(row, 8)),
                parseDecimal(valueAt(row, 9)),
                parseDecimal(valueAt(row, 10)),
                parseDecimal(valueAt(row, 11)),
                parseDecimal(valueAt(row, 14)),
                parseDecimal(valueAt(row, 15)),
                parseDecimal(valueAt(row, 16)),
                valueAt(row, 12),
                valueAt(row, 13),
                valueAt(row, 3),
                valueAt(row, 5),
                valueAt(row, 7),
                cashItems,
                investmentItems,
                liabilityItems
        );
    }

    boolean isBlankTemplateRow(CSVRecord row) {
        return valueAt(row, 0).isBlank();
    }

    LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.contains("/")) {
                return LocalDate.parse(value, SLASH_DATE_FORMATTER);
            }
            return LocalDate.parse(value, COMPACT_DATE_FORMATTER);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Unsupported date value: " + raw, exception);
        }
    }

    BigDecimal parseDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        String numericCandidate = normalized
                .replace(",", ".")
                .replaceAll("[^0-9+\\-.]", "");

        if (numericCandidate.isBlank() || numericCandidate.equals("-") || numericCandidate.equals("+") || numericCandidate.equals(".")) {
            return null;
        }

        if (numericCandidate.matches("[+-]?\\d+(?:\\.\\d+)?")) {
            return new BigDecimal(numericCandidate);
        }

        return evaluateExpression(numericCandidate);
    }

    List<AssetSnapshotImportItem> parseBreakdown(String raw, AssetCategoryType categoryType, Map<String, String> itemNames) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String normalized = raw.replace(",", ".").trim();
        Matcher matcher = SEGMENT_PATTERN.matcher(normalized);
        Map<String, AssetSnapshotImportItem> items = new LinkedHashMap<>();

        while (matcher.find()) {
            String itemCode = matcher.group(1).toLowerCase(Locale.ROOT);
            String expression = matcher.group(2).trim();
            if (expression.isBlank()) {
                continue;
            }
            items.put(itemCode, new AssetSnapshotImportItem(
                    categoryType,
                    itemCode,
                    itemNames.getOrDefault(itemCode, "unknown"),
                    evaluateExpression(expression),
                    expression
            ));
        }

        return List.copyOf(items.values());
    }

    BigDecimal evaluateExpression(String rawExpression) {
        String expression = rawExpression.replace(" ", "").replace(",", ".");
        Matcher matcher = TOKEN_PATTERN.matcher(expression);
        BigDecimal result = BigDecimal.ZERO;
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String sign = matcher.group(1);
            BigDecimal value = new BigDecimal(matcher.group(2));
            if ("-".equals(sign)) {
                result = result.subtract(value);
            } else {
                result = result.add(value);
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Unsupported expression: " + rawExpression);
        }

        return result;
    }

    private String valueAt(CSVRecord row, int index) {
        if (index >= row.size()) {
            return "";
        }
        return row.get(index).trim();
    }
}
