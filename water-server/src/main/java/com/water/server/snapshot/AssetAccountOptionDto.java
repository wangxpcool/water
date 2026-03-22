package com.water.server.snapshot;

public record AssetAccountOptionDto(
        Long id,
        String accountCode,
        String accountName,
        String accountType,
        String balanceDirection,
        String currencyCode,
        String institutionName,
        String ownerName,
        String remark,
        Integer sortOrder,
        Boolean enabled
) {
}
