package org.muybaby.shopserver.storage.provider;

import org.muybaby.shopserver.storage.StorageProviderKind;

/**
 * Immutable Tencent COS location captured when an object is uploaded.
 */
public record StorageObjectLocation(
        StorageProviderKind provider,
        String container,
        String region,
        String objectKey
) {
}
