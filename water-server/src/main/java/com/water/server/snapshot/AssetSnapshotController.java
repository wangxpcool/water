package com.water.server.snapshot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import jakarta.validation.Valid;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/snapshots")
public class AssetSnapshotController {

    private final AssetSnapshotQueryService assetSnapshotQueryService;
    private final AssetSnapshotCommandService assetSnapshotCommandService;

    public AssetSnapshotController(
            AssetSnapshotQueryService assetSnapshotQueryService,
            AssetSnapshotCommandService assetSnapshotCommandService
    ) {
        this.assetSnapshotQueryService = assetSnapshotQueryService;
        this.assetSnapshotCommandService = assetSnapshotCommandService;
    }

    @GetMapping
    public List<AssetSnapshotResponse> listSnapshots() {
        return assetSnapshotQueryService.findAllSnapshots();
    }

    @GetMapping("/all")
    public List<AssetSnapshotResponse> listAllSnapshots() {
        return assetSnapshotQueryService.findAllSnapshots();
    }

    @GetMapping("/accounts")
    public List<AssetAccountOptionDto> listAccounts() {
        return assetSnapshotQueryService.findEnabledAccounts();
    }

    @PostMapping
    @ResponseStatus(CREATED)
    public AssetSnapshotResponse createSnapshot(@Valid @RequestBody AssetSnapshotUpsertRequest request) {
        return assetSnapshotCommandService.createSnapshot(request);
    }

    @PutMapping("/{id}")
    public AssetSnapshotResponse updateSnapshot(@PathVariable long id, @Valid @RequestBody AssetSnapshotUpsertRequest request) {
        return assetSnapshotCommandService.updateSnapshot(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    public void deleteSnapshot(@PathVariable long id) {
        assetSnapshotCommandService.deleteSnapshot(id);
    }
}
