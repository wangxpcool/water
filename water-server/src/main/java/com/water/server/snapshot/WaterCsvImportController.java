package com.water.server.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

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
        return waterCsvImportService.importFromPath(resolveImportPath(path));
    }

    private Path resolveImportPath(String path) {
        Path workingDirectory = Path.of(".").toAbsolutePath().normalize();
        Path importRoot = workingDirectory.getParent() == null ? workingDirectory : workingDirectory.getParent();
        Path requestedPath = workingDirectory.resolve(path).normalize();

        if (!requestedPath.startsWith(importRoot)) {
            throw new ResponseStatusException(BAD_REQUEST, "Import path must stay under project directory");
        }
        return requestedPath;
    }
}
