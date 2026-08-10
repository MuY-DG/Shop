package org.muybaby.shopserver.finance.reconciliation.storage;

import org.muybaby.shopserver.storage.provider.StorageObjectLocation;

public record StoredTradeBillSource(
        StorageObjectLocation location,
        String contentType,
        long sizeBytes,
        String contentSha256
) {
}
