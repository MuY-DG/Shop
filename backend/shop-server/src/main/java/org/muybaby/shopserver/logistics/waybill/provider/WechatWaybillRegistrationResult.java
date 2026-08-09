package org.muybaby.shopserver.logistics.waybill.provider;

public record WechatWaybillRegistrationResult(
        WechatProviderOutcome outcome,
        String waybillToken,
        String errorCode,
        String errorMessage
) {

    public static WechatWaybillRegistrationResult success(String waybillToken) {
        return new WechatWaybillRegistrationResult(
                WechatProviderOutcome.SUCCESS, waybillToken, null, null
        );
    }

    public static WechatWaybillRegistrationResult failure(
            WechatProviderOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        return new WechatWaybillRegistrationResult(outcome, null, errorCode, errorMessage);
    }
}
