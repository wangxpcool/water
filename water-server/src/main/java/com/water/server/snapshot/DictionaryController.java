package com.water.server.snapshot;

import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {
    private final DictionaryService service;

    public DictionaryController(DictionaryService service) {
        this.service = service;
    }

    @GetMapping
    public List<DictionaryService.Entry> list() {
        return service.list();
    }

    @PutMapping
    public List<DictionaryService.Entry> save(@RequestBody List<DictionaryService.Input> entries) {
        return service.save(entries, false);
    }

    @PostMapping("/import-tag-adjustments")
    public List<DictionaryService.Entry> importAdjustments(@RequestBody Map<String, BigDecimal> values) {
        return service.save(values.entrySet().stream().map(e ->
                new DictionaryService.Input("TAG_ADJUSTMENT", e.getKey(), e.getValue(), e.getKey(), "Imported from browser storage")
        ).toList(), true);
    }
}
