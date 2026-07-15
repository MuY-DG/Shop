package org.muybaby.shopserver.storage;

/**
 * Server-selected upload policy. This value describes an upload operation and is
 * deliberately not persisted as the lifetime identity of an asset.
 */
public enum StorageUploadProfile {
    LIBRARY_IMAGE(StorageAssetScope.LIBRARY, StorageMediaKind.IMAGE, FileVisibility.PUBLIC),
    LIBRARY_VIDEO(StorageAssetScope.LIBRARY, StorageMediaKind.VIDEO, FileVisibility.PUBLIC),
    AFTER_SALE_EVIDENCE(StorageAssetScope.ATTACHMENT, StorageMediaKind.IMAGE, FileVisibility.PRIVATE),
    CUSTOMER_SERVICE_IMAGE(StorageAssetScope.ATTACHMENT, StorageMediaKind.IMAGE, FileVisibility.PRIVATE),
    PAYMENT_SECRET(StorageAssetScope.SECRET, StorageMediaKind.DOCUMENT, FileVisibility.PRIVATE);

    private final StorageAssetScope scope;
    private final StorageMediaKind mediaKind;
    private final FileVisibility visibility;

    StorageUploadProfile(
            StorageAssetScope scope,
            StorageMediaKind mediaKind,
            FileVisibility visibility
    ) {
        this.scope = scope;
        this.mediaKind = mediaKind;
        this.visibility = visibility;
    }

    public StorageAssetScope scope() {
        return scope;
    }

    public StorageMediaKind mediaKind() {
        return mediaKind;
    }

    public FileVisibility visibility() {
        return visibility;
    }
}
