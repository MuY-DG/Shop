package org.muybaby.shopserver.payment.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigSource;
import org.muybaby.shopserver.payment.config.PaymentVerifyMode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentConfigVerifierTest {

    @Test
    void acceptsAnAuthenticatedQueryForTheSameMiniProgramWithoutCreatingAnOrder() {
        WechatPayProvider provider = mock(WechatPayProvider.class);
        WechatPlatformCredentialResolver resolver = mock(WechatPlatformCredentialResolver.class);
        ResolvedPaymentConfig config = config("wx-same-app");
        when(resolver.resolve()).thenReturn(credentials("wx-same-app"));
        when(provider.queryOrder(any(), anyString())).thenAnswer(invocation -> {
            String outTradeNo = invocation.getArgument(1);
            assertThat(outTradeNo).startsWith("CFGTEST").hasSize(32);
            return WechatPayOrderQueryResult.notPaid(outTradeNo, "NOT_FOUND");
        });

        new PaymentConfigVerifier(provider, resolver).requireUsable(config);

        verify(provider).queryOrder(any(), anyString());
    }

    @Test
    void rejectsAConfigForAnotherMiniProgramBeforeCallingWechatPay() {
        WechatPayProvider provider = mock(WechatPayProvider.class);
        WechatPlatformCredentialResolver resolver = mock(WechatPlatformCredentialResolver.class);
        when(resolver.resolve()).thenReturn(credentials("wx-platform-app"));

        assertThatThrownBy(() -> new PaymentConfigVerifier(provider, resolver)
                .requireUsable(config("wx-payment-app")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.PAYMENT_CONFIG_APP_ID_MISMATCH));

        verify(provider, never()).queryOrder(any(), anyString());
    }

    @Test
    void reportsTransportFailuresAsTemporarilyUnavailable() {
        WechatPayProvider provider = mock(WechatPayProvider.class);
        WechatPlatformCredentialResolver resolver = mock(WechatPlatformCredentialResolver.class);
        when(resolver.resolve()).thenReturn(credentials("wx-same-app"));
        when(provider.queryOrder(any(), anyString()))
                .thenThrow(new IllegalStateException("timed out"));

        assertThatThrownBy(() -> new PaymentConfigVerifier(provider, resolver)
                .requireUsable(config("wx-same-app")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.PAYMENT_CONFIG_VERIFICATION_UNAVAILABLE));
    }

    private ResolvedPaymentConfig config(String appId) {
        return new ResolvedPaymentConfig(
                PaymentConfigSource.DB,
                1L,
                "test",
                false,
                appId,
                "1900000001",
                "serial",
                "0123456789abcdef0123456789abcdef",
                "private-key",
                "https://example.test/wxpay/pay/notify",
                "https://example.test/wxpay/refund/notify",
                PaymentVerifyMode.PUBLIC_KEY,
                "PUB_KEY_ID",
                "public-key"
        );
    }

    private WechatPlatformCredentials credentials(String appId) {
        return new WechatPlatformCredentials(
                appId, "app-secret", WechatPlatformCredentials.Source.DATABASE);
    }
}
