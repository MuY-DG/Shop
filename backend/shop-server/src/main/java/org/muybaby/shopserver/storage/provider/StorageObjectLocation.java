package org.muybaby.shopserver.storage.provider;

import org.muybaby.shopserver.storage.StorageProviderKind;

/**
 * Immutable provider location captured when an object is uploaded.
 *
 * <p>The container is the normalized local root for LOCAL storage and the
 * bucket name for Tencent COS. The region is empty for LOCAL storage.</p>
 */
public record StorageObjectLocation(
        StorageProviderKind provider,
        String container,
        String region,
        String objectKey
) {
}
