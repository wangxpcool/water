package com.water.server.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AssetAccountUpsertRequest(
        @NotBlank String accountCode,
        @NotBlank String accountName,
        @NotBlank String accountType,
        @NotBlank String balanceDirection,
        @NotBlank String currencyCode,
        String institutionName,
        String ownerName,
        String remark,
        @NotNull Integer sortOrder,
        @NotNull Boolean enabled
) {
}
