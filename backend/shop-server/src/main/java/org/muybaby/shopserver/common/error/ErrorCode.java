package org.muybaby.shopserver.common.error;

public enum ErrorCode {
    AUTHENTICATION_REQUIRED(100001, "Authentication required"),
    PERMISSION_DENIED(100003, "Permission denied"),
    VALIDATION_FAILED(100400, "Validation failed"),
    PRODUCT_UNAVAILABLE(200001, "Product unavailable"),
    SKU_UNAVAILABLE(200002, "SKU unavailable"),
    STOCK_SHORTAGE(200100, "Stock shortage"),
    COUPON_UNAVAILABLE(300001, "Coupon unavailable"),
    ORDER_STATE_CONFLICT(400001, "Order state conflict"),
    PAYMENT_PENDING(500001, "Payment pending"),
    WECHAT_SHIPPING_UPLOAD_FAILED(600001, "WeChat shipping upload failed"),
    WECHAT_REFUND_FAILED(700001, "WeChat refund failed");

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
