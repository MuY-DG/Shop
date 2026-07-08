package org.muybaby.shopserver.storage;

public enum StoragePurpose {
    PRODUCT_IMAGE("product", FileVisibility.PUBLIC, true),
    PRODUCT_SKU_IMAGE("product-sku", FileVisibility.PUBLIC, true),
    CATEGORY_ICON("icon", FileVisibility.PUBLIC, true),
    HOME_BANNER("banner", FileVisibility.PUBLIC, true),
    MARKETING_IMAGE("marketing", FileVisibility.PUBLIC, true),
    APP_ICON("app-icon", FileVisibility.PUBLIC, true),
    RICH_TEXT_IMAGE("rich-text", FileVisibility.PUBLIC, true),
    PAYMENT_CERTIFICATE("payment", FileVisibility.PRIVATE, false),
    AFTER_SALE_IMAGE("after-sale", FileVisibility.PRIVATE, true),
    REFUND_EVIDENCE("refund", FileVisibility.PRIVATE, true);

    private final String keySegment;
    private final FileVisibility visibility;
    private final boolean image;

    StoragePurpose(String keySegment, FileVisibility visibility, boolean image) {
        this.keySegment = keySegment;
        this.visibility = visibility;
        this.image = image;
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
}
