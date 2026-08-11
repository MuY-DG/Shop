package org.muybaby.shopserver.wechat.servicecard.provider;

public record WechatServiceCardSetResult(
        Outcome outcome,
        Integer errorCode,
        String errorMessage
) {
    public enum Outcome {
        APPLIED,
        RETRYABLE,
        UNKNOWN,
        REJECTED
    }

    public static WechatServiceCardSetResult applied() {
        return new WechatServiceCardSetResult(Outcome.APPLIED, 0, "");
    }

    public static WechatServiceCardSetResult retryable(Integer code, String message) {
        return new WechatServiceCardSetResult(Outcome.RETRYABLE, code, message);
    }

    public static WechatServiceCardSetResult unknown(Integer code, String message) {
        return new WechatServiceCardSetResult(Outcome.UNKNOWN, code, message);
    }

    public static WechatServiceCardSetResult rejected(Integer code, String message) {
        return new WechatServiceCardSetResult(Outcome.REJECTED, code, message);
    }
}
