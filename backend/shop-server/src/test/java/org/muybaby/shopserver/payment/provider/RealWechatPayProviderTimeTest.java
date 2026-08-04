package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealWechatPayProviderTimeTest {

    @Test
    void formatsPaymentExpiryWithSecondsAndUtcOffset() {
        assertThat(RealWechatPayProvider.formatTimeExpire(
                LocalDateTime.of(2026, 8, 5, 6, 16, 0)))
                .isEqualTo("2026-08-05T06:16:00+00:00");
        assertThat(RealWechatPayProvider.formatTimeExpire(
                LocalDateTime.of(2026, 8, 5, 6, 16, 37)))
                .isEqualTo("2026-08-05T06:16:37+00:00");
    }

    @Test
    void convertsProviderOffsetTimeToTheApplicationClockZoneAtTheSameInstant() {
        ZoneId applicationZone = ZoneId.of("America/Los_Angeles");
        RealWechatPayProvider provider = new RealWechatPayProvider(
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-19T12:00:00Z"), applicationZone)
        );

        assertThat(provider.toLocalDateTime("2026-07-19T20:00:00+08:00"))
                .isEqualTo(LocalDateTime.of(2026, 7, 19, 5, 0));
    }

    @Test
    void requiresPaymentNotificationMerchantAndAppToMatchVerifiedConfiguration() {
        RealWechatPayProvider provider = new RealWechatPayProvider(new ObjectMapper(), Clock.systemUTC());
        ResolvedPaymentConfig config = mock(ResolvedPaymentConfig.class);
        when(config.mchId()).thenReturn("merchant-1001");
        when(config.appId()).thenReturn("wx-app-1001");
        Transaction transaction = new Transaction();
        transaction.setMchid("merchant-1001");
        transaction.setAppid("wx-app-1001");

        assertThatCode(() -> provider.validatePayNotificationMerchant(config, transaction))
                .doesNotThrowAnyException();

        transaction.setMchid("merchant-attacker");
        assertThatThrownBy(() -> provider.validatePayNotificationMerchant(config, transaction))
                .isInstanceOf(BusinessException.class);
        transaction.setMchid("merchant-1001");
        transaction.setAppid("wx-app-attacker");
        assertThatThrownBy(() -> provider.validatePayNotificationMerchant(config, transaction))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOURCE_NOT_EXISTS", "REFUND_NOT_EXIST"})
    void mapsMissingRefundServiceErrorsToNotFound(String errorCode) {
        RefundService refundService = mock(RefundService.class);
        RealWechatPayProvider provider = new RealWechatPayProvider(
                new ObjectMapper(), Clock.systemUTC()) {
            @Override
            RefundService refundService(ResolvedPaymentConfig config) {
                return refundService;
            }
        };
        ServiceException missingRefund = new ServiceException(
                mock(HttpRequest.class),
                404,
                "{\"code\":\"" + errorCode + "\",\"message\":\"refund does not exist\"}"
        );
        when(refundService.queryByOutRefundNo(any())).thenThrow(missingRefund);

        WechatRefundQueryResult result = provider.queryRefund(mock(ResolvedPaymentConfig.class), "RF202607190001");

        assertThat(result).isEqualTo(new WechatRefundQueryResult(
                "RF202607190001", "", "", "NOT_FOUND", 0L, null));
    }
}
