package org.muybaby.shopserver.storage;

public enum StoragePurpose {
    PRODUCT_IMAGE("product", FileVisibility.PUBLIC, true, "PRODUCT_IMAGE"),
    PRODUCT_SKU_IMAGE("product-sku", FileVisibility.PUBLIC, true, "PRODUCT_IMAGE"),
    CATEGORY_ICON("icon", FileVisibility.PUBLIC, true, "CATEGORY_ICON"),
    HOME_BANNER("banner", FileVisibility.PUBLIC, true, "HOME_BANNER"),
    MARKETING_IMAGE("marketing", FileVisibility.PUBLIC, true, "MARKETING_IMAGE"),
    APP_ICON("app-icon", FileVisibility.PUBLIC, true, "APP_ICON"),
    RICH_TEXT_IMAGE("rich-text", FileVisibility.PUBLIC, true, "RICH_TEXT_IMAGE"),
    PAYMENT_CERTIFICATE("payment", FileVisibility.PRIVATE, false, "PAYMENT_CERTIFICATE"),
    AFTER_SALE_IMAGE("after-sale", FileVisibility.PRIVATE, true, "AFTER_SALE_IMAGE"),
    REFUND_EVIDENCE("refund", FileVisibility.PRIVATE, true, "AFTER_SALE_IMAGE");

    private final String keySegment;
    private final FileVisibility visibility;
    private final boolean image;
    private final String defaultAssetCategoryCode;

    StoragePurpose(String keySegment, FileVisibility visibility, boolean image, String defaultAssetCategoryCode) {
        this.keySegment = keySegment;
        this.visibility = visibility;
        this.image = image;
        this.defaultAssetCategoryCode = defaultAssetCategoryCode;
    }

    public String keySegment() {
        return keySegment;
    }

    public FileVisibility visibility() {
        return visibility;
    }

    public boolean image() {
        return image;
    }

    public String defaultAssetCategoryCode() {
        return defaultAssetCategoryCode;
    }
}
