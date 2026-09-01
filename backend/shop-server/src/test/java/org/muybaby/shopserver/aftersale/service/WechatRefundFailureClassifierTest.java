package org.muybaby.shopserver.aftersale.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

class WechatRefundFailureClassifierTest {

    @Test
    void classifiesDocumentedMerchantActionFailures() {
        assertClassification(
                "NOT_ENOUGH",
                WechatRefundFailureClassifier.Disposition.BALANCE_REQUIRED,
                ErrorCode.WECHAT_REFUND_BALANCE_INSUFFICIENT);
        assertClassification(
                "SIGN_ERROR",
                WechatRefundFailureClassifier.Disposition.CONFIGURATION_FAILURE,
                ErrorCode.WECHAT_REFUND_CONFIGURATION_INVALID);
        assertClassification(
                "MCH_NOT_EXISTS",
                WechatRefundFailureClassifier.Disposition.CONFIGURATION_FAILURE,
                ErrorCode.WECHAT_REFUND_CONFIGURATION_INVALID);
        assertClassification(
                "USER_ACCOUNT_ABNORMAL",
                WechatRefundFailureClassifier.Disposition.USER_ACCOUNT_ABNORMAL,
                ErrorCode.WECHAT_REFUND_USER_ACCOUNT_ABNORMAL);
        assertClassification(
                "INVALID_REQUEST",
                WechatRefundFailureClassifier.Disposition.BUSINESS_REJECTION,
                ErrorCode.WECHAT_REFUND_REQUEST_REJECTED);
        assertClassification(
                "RESOURCE_NOT_EXISTS",
                WechatRefundFailureClassifier.Disposition.BUSINESS_REJECTION,
                ErrorCode.WECHAT_REFUND_REQUEST_REJECTED);
    }

    @Test
    void retryableAndUnknownFailuresAlwaysRequireReconciliation() {
        for (String code : new String[]{"FREQUENCY_LIMITED", "SYSTEM_ERROR", "future_code", null}) {
            WechatRefundFailureClassifier.Classification classification =
                    WechatRefundFailureClassifier.classify(code);
            assertThat(classification.disposition())
                    .isEqualTo(WechatRefundFailureClassifier.Disposition.RECONCILE_AND_RETRY);
            assertThat(classification.errorCode())
                    .isEqualTo(ErrorCode.WECHAT_REFUND_RECONCILIATION_PENDING);
            assertThat(classification.disposition().requiresMerchantAction()).isFalse();
        }
    }

    private void assertClassification(
            String code,
            WechatRefundFailureClassifier.Disposition disposition,
            ErrorCode errorCode
    ) {
        WechatRefundFailureClassifier.Classification classification =
                WechatRefundFailureClassifier.classify(code.toLowerCase());
        assertThat(classification.providerErrorCode()).isEqualTo(code);
        assertThat(classification.disposition()).isEqualTo(disposition);
        assertThat(classification.errorCode()).isEqualTo(errorCode);
        assertThat(classification.disposition().requiresMerchantAction()).isTrue();
    }
}
