package org.muybaby.shopserver.logistics.provider;

import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;

import java.util.Objects;

public record WechatShippingUploadResult(
        WechatShippingUploadStatus status,
        String errorCode,
        String errorMessage
) {
    public WechatShippingUploadResult {
        Objects.requireNonNull(status, "status");
        if (status != WechatShippingUploadStatus.UPLOADED
                && status != WechatShippingUploadStatus.FAILED
                && status != WechatShippingUploadStatus.UNAVAILABLE
                && status != WechatShippingUploadStatus.UNKNOWN) {
            throw new IllegalArgumentException("Unsupported WeChat provider upload status: " + status);
        }
    }

    public boolean success() {
        return status == WechatShippingUploadStatus.UPLOADED;
    }

    public static WechatShippingUploadResult uploaded() {
        return new WechatShippingUploadResult(WechatShippingUploadStatus.UPLOADED, null, null);
    }

    public static WechatShippingUploadResult failed(String errorCode, String errorMessage) {
        return new WechatShippingUploadResult(WechatShippingUploadStatus.FAILED, errorCode, errorMessage);
    }

    public static WechatShippingUploadResult unavailable(String errorCode, String errorMessage) {
        return new WechatShippingUploadResult(WechatShippingUploadStatus.UNAVAILABLE, errorCode, errorMessage);
    }

    public static WechatShippingUploadResult unknown(String errorCode, String errorMessage) {
        return new WechatShippingUploadResult(WechatShippingUploadStatus.UNKNOWN, errorCode, errorMessage);
    }
}
