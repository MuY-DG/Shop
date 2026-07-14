package org.muybaby.shopserver.storage;

public enum StoragePurpose {
    PRODUCT_IMAGE("product", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "PRODUCT_IMAGE"),
    PRODUCT_SKU_IMAGE("product-sku", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "PRODUCT_IMAGE"),
    SPEC_VALUE_IMAGE("product-spec-value", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "PRODUCT_IMAGE"),
    GUARANTEE_SERVICE_ICON("guarantee-service-icon", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "PRODUCT_IMAGE"),
    PRODUCT_VIDEO("product-video", FileVisibility.PUBLIC, StorageMediaKind.VIDEO, "PRODUCT_IMAGE"),
    CATEGORY_ICON("icon", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "CATEGORY_ICON"),
    HOME_BANNER("banner", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "HOME_BANNER"),
    MARKETING_IMAGE("marketing", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "MARKETING_IMAGE"),
    APP_ICON("app-icon", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "APP_ICON"),
    RICH_TEXT_IMAGE("rich-text", FileVisibility.PUBLIC, StorageMediaKind.IMAGE, "RICH_TEXT_IMAGE"),
    PAYMENT_CERTIFICATE("payment", FileVisibility.PRIVATE, StorageMediaKind.CERTIFICATE, "PAYMENT_CERTIFICATE"),
    AFTER_SALE_IMAGE("after-sale", FileVisibility.PRIVATE, StorageMediaKind.IMAGE, "AFTER_SALE_IMAGE"),
    REFUND_EVIDENCE("refund", FileVisibility.PRIVATE, StorageMediaKind.IMAGE, "AFTER_SALE_IMAGE");

    private final String keySegment;
    private final FileVisibility visibility;
    private final StorageMediaKind mediaKind;
    private final String defaultAssetCategoryCode;

    StoragePurpose(
            String keySegment,
            FileVisibility visibility,
            StorageMediaKind mediaKind,
            String defaultAssetCategoryCode
    ) {
        this.keySegment = keySegment;
        this.visibility = visibility;
        this.mediaKind = mediaKind;
        this.defaultAssetCategoryCode = defaultAssetCategoryCode;
    }

    public String keySegment() {
        return keySegment;
    }

    public FileVisibility visibility() {
        return visibility;
    }

    public boolean image() {
        return mediaKind == StorageMediaKind.IMAGE;
    }

    public StorageMediaKind mediaKind() {
        return mediaKind;
    }

    public String defaultAssetCategoryCode() {
        return defaultAssetCategoryCode;
    }
}
