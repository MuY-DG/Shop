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
        insertExpiredPayingPayment(order, "PAYTIMEOUT" + order.orderId(), session.openid(), 6980L);

        int closedCount = paymentTimeoutCloseService.closeExpiredPayments();

        assertThat(closedCount).isEqualTo(1);
        assertThat(mockWechatPayProvider.closedOutTradeNos()).containsExactly("PAYTIMEOUT" + order.orderId());
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
    }
}
