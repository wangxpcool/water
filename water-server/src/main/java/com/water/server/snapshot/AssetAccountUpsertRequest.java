package com.water.server.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AssetAccountUpsertRequest(
        @NotBlank String accountCode,
        @NotBlank String accountName,
        @NotBlank String categoryGroup,
        @NotBlank String accountType,
        Long parentAccountId,
        @NotNull Boolean summaryAccount,
        @NotBlank String balanceDirection,
        @NotBlank String currencyCode,
        String institutionName,
        String ownerName,
        String remark,
        @NotNull Integer sortOrder,
        @NotNull Boolean enabled,
        List<String> tags
) {
}
