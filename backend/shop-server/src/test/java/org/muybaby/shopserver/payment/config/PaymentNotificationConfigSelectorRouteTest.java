package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentNotificationConfigSelectorRouteTest extends PaymentTestSupport {

    private static final String PAY_ROUTE_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String REFUND_ROUTE_TOKEN = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private PaymentNotificationConfigSelector selector;

    @Autowired
    private PaymentConfigResolver paymentConfigResolver;

    @Test
    void unknownRoutedTokenFailsBeforeTryingAnyConfiguration() {
        AtomicInteger parserCalls = new AtomicInteger();

        assertValidationFailure(() -> selector.parse(
                PAY_ROUTE_TOKEN,
                PaymentNotificationConfigSelector.NotificationKind.PAY,
                config -> {
                    parserCalls.incrementAndGet();
                    return "unreachable";
                },
                PaymentNotificationConfigSelector.NotificationRoute::payment
        ));

        assertThat(parserCalls).hasValue(0);
    }

    @Test
    void paymentRouteRejectsAValidlyParsedBodyForAnotherPayment() {
        seedEnabledPaymentConfig();
        insertRoutedPayment("EXPECTED-OUT-TRADE-NO", PAY_ROUTE_TOKEN);
        AtomicInteger parserCalls = new AtomicInteger();

        assertValidationFailure(() -> selector.parse(
                PAY_ROUTE_TOKEN,
                PaymentNotificationConfigSelector.NotificationKind.PAY,
                config -> {
                    parserCalls.incrementAndGet();
                    return "OTHER-OUT-TRADE-NO";
                },
                PaymentNotificationConfigSelector.NotificationRoute::payment
        ));

        assertThat(parserCalls).hasValue(1);
    }

    @Test
    void refundRouteRejectsAValidlyParsedBodyForAnotherRefund() {
        seedEnabledPaymentConfig();
        long paymentOrderId = insertRoutedPayment("EXPECTED-REFUND-TRADE", PAY_ROUTE_TOKEN);
        insertRoutedRefund(paymentOrderId, "EXPECTED-REFUND-NO", REFUND_ROUTE_TOKEN);
        AtomicInteger parserCalls = new AtomicInteger();

        assertValidationFailure(() -> selector.parse(
                REFUND_ROUTE_TOKEN,
                PaymentNotificationConfigSelector.NotificationKind.REFUND,
                config -> {
                    parserCalls.incrementAndGet();
                    return PaymentNotificationConfigSelector.NotificationRoute.refund(
                            "EXPECTED-REFUND-TRADE", "OTHER-REFUND-NO");
                },
                route -> route
        ));

        assertThat(parserCalls).hasValue(1);
    }

    @Test
    void routedSelectorResolvesThePersistedConfigurationAndParsesExactlyOnce() {
        seedEnabledPaymentConfig();
        insertRoutedPayment("ROUTED-PAYMENT", PAY_ROUTE_TOKEN);
        AtomicInteger parserCalls = new AtomicInteger();

        PaymentNotificationConfigSelector.ParsedNotification<String> parsed = selector.parse(
                PAY_ROUTE_TOKEN,
                PaymentNotificationConfigSelector.NotificationKind.PAY,
                config -> {
                    parserCalls.incrementAndGet();
                    assertThat(config.configId()).isEqualTo(91001L);
                    return "ROUTED-PAYMENT";
                },
                PaymentNotificationConfigSelector.NotificationRoute::payment
        );

        assertThat(parsed.notification()).isEqualTo("ROUTED-PAYMENT");
        assertThat(parserCalls).hasValue(1);
    }

    private long insertRoutedPayment(String outTradeNo, String routeToken) {
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String fingerprint = paymentConfigResolver.fingerprint(config);
        long paymentOrderId = System.nanoTime();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, payer_openid, status,
                             amount_cent, currency, expires_at)
                        values
                            (:paymentOrderId, :orderId, :paymentConfigId, :fingerprint,
                             :routeToken, :outTradeNo, 'openid-route-test', 'PAYING',
                             100, 'CNY', current_timestamp)
                        """)
                .param("paymentOrderId", paymentOrderId)
                .param("orderId", paymentOrderId + 1)
                .param("paymentConfigId", config.configId())
                .param("fingerprint", fingerprint)
                .param("routeToken", routeToken)
                .param("outTradeNo", outTradeNo)
                .update();
        return paymentOrderId;
    }

    private void insertRoutedRefund(long paymentOrderId, String outRefundNo, String routeToken) {
        long refundOrderId = System.nanoTime();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id,
                             notification_route_token, out_refund_no, refund_amount_cent,
                             status, callback_status, requested_at)
                        values
                            (:refundOrderId, :afterSaleId, :orderId, :paymentOrderId,
                             :routeToken, :outRefundNo, 100,
                             'PROCESSING', 'PROCESSING', current_timestamp)
                        """)
                .param("refundOrderId", refundOrderId)
                .param("afterSaleId", refundOrderId + 1)
                .param("orderId", paymentOrderId + 1)
                .param("paymentOrderId", paymentOrderId)
                .param("routeToken", routeToken)
                .param("outRefundNo", outRefundNo)
                .update();
    }

    private void assertValidationFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
