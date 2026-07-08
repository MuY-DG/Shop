package org.muybaby.shopserver.common.error;

public enum ErrorCode {
    AUTHENTICATION_REQUIRED(100001, "Authentication required"),
    INVALID_CREDENTIALS(100002, "Invalid username or password"),
    PERMISSION_DENIED(100003, "Permission denied"),
    TOKEN_EXPIRED(100004, "Token expired"),
    VALIDATION_FAILED(100400, "Validation failed"),
    WECHAT_LOGIN_FAILED(100101, "WeChat login failed"),
    WECHAT_PHONE_FAILED(100102, "WeChat phone authorization failed"),
    PRODUCT_CATEGORY_UNAVAILABLE(200000, "Product category unavailable"),
    PRODUCT_UNAVAILABLE(200001, "Product unavailable"),
    SKU_UNAVAILABLE(200002, "SKU unavailable"),
    STOCK_SHORTAGE(200100, "Stock shortage"),
    CART_ITEM_NOT_FOUND(250001, "Cart item not found"),
    COUPON_UNAVAILABLE(300001, "Coupon unavailable"),
    COUPON_CLAIM_LIMIT_REACHED(300002, "Coupon claim limit reached"),
    COUPON_ALREADY_USED(300003, "Coupon already used"),
    ORDER_STATE_CONFLICT(400001, "Order state conflict"),
    PAYMENT_PENDING(500001, "Payment pending"),
    WECHAT_SHIPPING_UPLOAD_FAILED(600001, "WeChat shipping upload failed"),
    WECHAT_REFUND_FAILED(700001, "WeChat refund failed"),
    STORAGE_FILE_UNAVAILABLE(800001, "Storage file unavailable"),
    STORAGE_UPLOAD_POLICY_REJECTED(800002, "Storage upload policy rejected");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
