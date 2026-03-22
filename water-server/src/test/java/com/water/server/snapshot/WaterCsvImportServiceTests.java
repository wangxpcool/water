package com.water.server.snapshot;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WaterCsvImportServiceTests {

    private final WaterCsvImportService service = new WaterCsvImportService();

    @Test
    void parsesSupportedDateFormats() {
        assertEquals(LocalDate.of(2020, 3, 5), service.parseDate("2020/3/5"));
        assertEquals(LocalDate.of(2026, 2, 22), service.parseDate("20260222"));
    }

    @Test
    void parsesBreakdownExpressions() {
        List<AssetSnapshotImportItem> liabilities = service.parseBreakdown(
                "a10+10+3+5-6.6b2.4",
                AssetCategoryType.LIABILITY,
                java.util.Map.of("a", "consumerLoan", "b", "creditCard")
        );

        assertEquals(2, liabilities.size());
        assertEquals(new BigDecimal("21.4"), liabilities.get(0).amount());
        assertEquals(new BigDecimal("2.4"), liabilities.get(1).amount());
    }

    @Test
    void parsesBreakdownWithDecimalCommaInsideQuotedCsvField() {
        List<AssetSnapshotImportItem> investments = service.parseBreakdown(
                "a0b34,4c31.1",
                AssetCategoryType.INVESTMENT,
                java.util.Map.of("a", "fund", "b", "aShare", "c", "usShare")
        );

        assertEquals(3, investments.size());
        assertEquals(new BigDecimal("34.4"), investments.get(1).amount());
        assertEquals(new BigDecimal("31.1"), investments.get(2).amount());
    }

    @Test
    void parsesSingleRowIntoNormalizedRecord() throws IOException {
        String row = "20240508,2,1.5,a0b2c54.9,56.9,a20b78c15,113,a10+10+3+5+3.5,31.5,201.4,0,201.4,五一消费2，额外消费3，希望年底到400,,80,加上公积金=281.4,,";
        CSVRecord record = parseSingleRecord(row);

        AssetSnapshotImportRecord result = service.toImportRecord(record, 2);

        assertEquals(LocalDate.of(2024, 5, 8), result.snapshotDate());
        assertEquals(new BigDecimal("56.9"), result.cashTotal());
        assertEquals(new BigDecimal("113"), result.investmentTotal());
        assertEquals(new BigDecimal("31.5"), result.liabilityTotal());
        assertEquals(new BigDecimal("80"), result.publicFunds());
        assertNull(result.balance());
        assertEquals(3, result.cashItems().size());
        assertEquals(new BigDecimal("54.9"), result.cashItems().get(2).amount());
    }

    @Test
    void skipsBlankTemplateRowsWhenImportingFile() throws IOException {
        Path csv = Files.createTempFile("water-import", ".csv");
        Files.writeString(csv, String.join(System.lineSeparator(),
                "time,income,red,cash,total1,asset,total2,loan,total3,account,win,actual,log,remark,publicFunds,extra,balance,,",
                "2020/3/5,11.5,1.5,a2b0c10.6,12.6,a23.3b12.6c0,35.9,a10+10+3+5b2.4,30.4,78.9,0,78.9,初始化,准备交房租,11,,,",
                ",21,1.7,,,,,a10+10+3+5,28,28,,28,,,,,28,,"
        ));

        List<AssetSnapshotImportRecord> records = service.importFromPath(csv);

        assertEquals(1, records.size());
        assertEquals(LocalDate.of(2020, 3, 5), records.get(0).snapshotDate());
        Files.deleteIfExists(csv);
    }

    private CSVRecord parseSingleRecord(String row) throws IOException {
        try (CSVParser parser = CSVFormat.DEFAULT.parse(new StringReader(row))) {
            return parser.getRecords().get(0);
        }
    }
}
