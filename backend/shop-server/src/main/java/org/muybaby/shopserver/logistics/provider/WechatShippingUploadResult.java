package org.muybaby.shopserver.logistics.provider;

public record WechatShippingUploadResult(
        boolean success,
        String errorCode,
        String errorMessage
) {

    public static WechatShippingUploadResult uploaded() {
        return new WechatShippingUploadResult(true, "", "");
    }

    public static WechatShippingUploadResult failed(String errorCode, String errorMessage) {
        return new WechatShippingUploadResult(false, errorCode, errorMessage);
    }
}
