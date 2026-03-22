package com.water.server.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/import")
public class WaterCsvImportController {

    private final WaterCsvImportService waterCsvImportService;

    public WaterCsvImportController(WaterCsvImportService waterCsvImportService) {
        this.waterCsvImportService = waterCsvImportService;
    }

    @GetMapping("/preview")
    public List<AssetSnapshotImportRecord> previewImport(
            @RequestParam(defaultValue = "../water.csv") String path
    ) throws IOException {
        return waterCsvImportService.importFromPath(Path.of(path));
    }
}
