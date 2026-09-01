package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.ErrorCode;

import java.util.Locale;
import java.util.Set;

public final class WechatRefundFailureClassifier {

    private static final Set<String> CONFIGURATION_CODES = Set.of(
            "SIGN_ERROR", "MCH_NOT_EXISTS", "NO_AUTH");
    private static final Set<String> BUSINESS_REJECTION_CODES = Set.of(
            "INVALID_REQUEST", "RESOURCE_NOT_EXISTS", "PARAM_ERROR",
            "INVALID_TRANSACTION_ID", "TRANSACTION_ID_MISMATCH", "ORDER_NOT_EXIST");
    private static final Set<String> RETRYABLE_UNKNOWN_CODES = Set.of(
            "FREQUENCY_LIMITED", "SYSTEM_ERROR");

    private WechatRefundFailureClassifier() {
    }

    public static Classification classify(String providerErrorCode) {
        String code = normalize(providerErrorCode);
        if ("NOT_ENOUGH".equals(code)) {
            return new Classification(code, Disposition.BALANCE_REQUIRED,
                    ErrorCode.WECHAT_REFUND_BALANCE_INSUFFICIENT);
        }
        if (CONFIGURATION_CODES.contains(code)) {
            return new Classification(code, Disposition.CONFIGURATION_FAILURE,
                    ErrorCode.WECHAT_REFUND_CONFIGURATION_INVALID);
        }
        if ("USER_ACCOUNT_ABNORMAL".equals(code)) {
            return new Classification(code, Disposition.USER_ACCOUNT_ABNORMAL,
                    ErrorCode.WECHAT_REFUND_USER_ACCOUNT_ABNORMAL);
        }
        if (BUSINESS_REJECTION_CODES.contains(code)) {
            return new Classification(code, Disposition.BUSINESS_REJECTION,
                    ErrorCode.WECHAT_REFUND_REQUEST_REJECTED);
        }
        if (RETRYABLE_UNKNOWN_CODES.contains(code)) {
            return new Classification(code, Disposition.RECONCILE_AND_RETRY,
                    ErrorCode.WECHAT_REFUND_RECONCILIATION_PENDING);
        }
        return new Classification(code, Disposition.RECONCILE_AND_RETRY,
                ErrorCode.WECHAT_REFUND_RECONCILIATION_PENDING);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? "WECHAT_PAY_ERROR"
                : value.trim().toUpperCase(Locale.ROOT);
    }

    public enum Disposition {
        BALANCE_REQUIRED,
        CONFIGURATION_FAILURE,
        BUSINESS_REJECTION,
        USER_ACCOUNT_ABNORMAL,
        RECONCILE_AND_RETRY;

        public boolean requiresMerchantAction() {
            return this != RECONCILE_AND_RETRY;
        }
    }

    public record Classification(
            String providerErrorCode,
            Disposition disposition,
            ErrorCode errorCode
    ) {
    }
}
