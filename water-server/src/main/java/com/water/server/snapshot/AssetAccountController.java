package com.water.server.snapshot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

@RestController
@RequestMapping("/api/accounts")
public class AssetAccountController {

    private final AssetSnapshotQueryService assetSnapshotQueryService;
    private final AssetAccountCommandService assetAccountCommandService;

    public AssetAccountController(
            AssetSnapshotQueryService assetSnapshotQueryService,
            AssetAccountCommandService assetAccountCommandService
    ) {
        this.assetSnapshotQueryService = assetSnapshotQueryService;
        this.assetAccountCommandService = assetAccountCommandService;
    }

    @GetMapping
    public List<AssetAccountOptionDto> listAccounts() {
        return assetSnapshotQueryService.findAllAccounts();
    }

    @PostMapping
    @ResponseStatus(CREATED)
    public AssetAccountOptionDto createAccount(@Valid @RequestBody AssetAccountUpsertRequest request) {
        return assetAccountCommandService.createAccount(request);
    }

    @PutMapping("/{id}")
    public AssetAccountOptionDto updateAccount(@PathVariable long id, @Valid @RequestBody AssetAccountUpsertRequest request) {
        return assetAccountCommandService.updateAccount(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(NO_CONTENT)
    public void deleteAccount(@PathVariable long id) {
        assetAccountCommandService.deleteAccount(id);
    }
}
