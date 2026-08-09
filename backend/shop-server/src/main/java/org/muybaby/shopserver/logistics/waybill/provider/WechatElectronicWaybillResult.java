package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatElectronicWaybillResult(
        WechatProviderOutcome outcome,
        String providerOrderId,
        String deliveryId,
        String waybillId,
        Integer orderStatus,
        String printHtmlBase64,
        String errorCode,
        String errorMessage
) {

    public static WechatElectronicWaybillResult success(
            String providerOrderId,
            String deliveryId,
            String waybillId,
            Integer orderStatus,
            String printHtmlBase64
    ) {
        return new WechatElectronicWaybillResult(
                WechatProviderOutcome.SUCCESS,
                providerOrderId,
                deliveryId,
                waybillId,
                orderStatus,
                printHtmlBase64,
                null,
                null
        );
    }

    public static WechatElectronicWaybillResult failure(
            WechatProviderOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        return new WechatElectronicWaybillResult(
                outcome, null, null, null, null, null, errorCode, errorMessage
        );
    }
}
