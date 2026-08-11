package org.muybaby.shopserver.wechat.servicecard.provider;

import java.time.Instant;

public record WechatServiceCardQueryResult(
        Outcome outcome,
        Integer remoteStatus,
        Integer codeState,
        Instant expiresAt,
        Integer errorCode,
        String errorMessage
) {
    public enum Outcome {
        FOUND,
        NOT_FOUND,
        RETRYABLE,
        REJECTED
    }

    public static WechatServiceCardQueryResult found(
            Integer status, Integer codeState, Instant expiresAt
    ) {
        return new WechatServiceCardQueryResult(
                Outcome.FOUND, status, codeState, expiresAt, 0, ""
        );
    }

    public static WechatServiceCardQueryResult notFound(Integer code, String message) {
        return new WechatServiceCardQueryResult(
                Outcome.NOT_FOUND, null, null, null, code, message
        );
    }

    public static WechatServiceCardQueryResult retryable(Integer code, String message) {
        return new WechatServiceCardQueryResult(
                Outcome.RETRYABLE, null, null, null, code, message
        );
    }

    public static WechatServiceCardQueryResult rejected(Integer code, String message) {
        return new WechatServiceCardQueryResult(
                Outcome.REJECTED, null, null, null, code, message
        );
    }
}
