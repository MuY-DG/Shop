package org.muybaby.shopserver.payment;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.service.PaymentTimeoutCloseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PaymentTimeoutCloseServiceTest extends PaymentTestSupport {

    @Autowired
    private PaymentTimeoutCloseService paymentTimeoutCloseService;

    @Test
    void expiredPayingPaymentIsClosedThroughProviderAndReleasesOrderLocks() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("payment-timeout-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        String outTradeNo = "PAYTIMEOUT" + order.orderId();
        insertExpiredPayingPayment(order, outTradeNo, session.openid(), 6980L);
        jdbcClient.sql("""
                        insert into payment_attempt
                            (order_id, out_trade_no, status, amount_cent, error_code, error_message,
                             started_at, created_at, updated_at)
                        values
                            (:orderId, :outTradeNo, 'PREPAY_FAILED', 6980, 'PROVIDER_ERROR', 'previous failure',
                             timestamp '2026-07-07 08:40:00', timestamp '2026-07-07 08:40:00',
                             timestamp '2026-07-07 08:40:01')
                        """)
                .param("orderId", order.orderId())
                .param("outTradeNo", outTradeNo)
                .update();

        int closedCount = paymentTimeoutCloseService.closeExpiredPayments();

        assertThat(closedCount).isEqualTo(1);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly(outTradeNo);
        assertThat(jdbcClient.sql("select status from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", "PAYTIMEOUT" + order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("CLOSED");
        assertThat(jdbcClient.sql("select status from stock_lock where order_id = :orderId")
                .param("orderId", order.orderId())
                .query(String.class)
                .single()).isEqualTo("RELEASED");
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", order.skuId())
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from user_coupon
                        where id = :userCouponId
                          and status = 'CLAIMED'
                          and locked_order_id is null
                          and locked_at is null
                          and released_at is not null
                        """)
                .param("userCouponId", order.userCouponId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt a
                        join payment_order p on p.id = a.payment_order_id
                        where a.out_trade_no = :outTradeNo
                          and a.status = 'CLOSED'
                          and p.status = 'CLOSED'
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_attempt
                        where out_trade_no = :outTradeNo
                          and status = 'PREPAY_FAILED'
                          and payment_order_id is null
                        """)
                .param("outTradeNo", outTradeNo)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }
}
