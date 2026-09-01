package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.muybaby.shopserver.payment.service.PaymentTimeoutZSetProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CreatedOrderTimeoutCloseServiceTest extends PaymentTestSupport {

    @Autowired
    private CreatedOrderTimeoutCloseService service;

    @Autowired
    private PaymentTimeoutZSetProcessor zsetProcessor;

    @Test
    void expiredCreatedOrderClosesAndReleasesStockAndCouponExactlyOnce() throws Exception {
        AppLoginSession session = appLogin("created-timeout-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 6980L, true);
        expire(order.orderId());

        assertThat(service.closeExpiredCreatedOrders(10)).isEqualTo(1);

        assertThat(value("select status from shop_order where id = :id", order.orderId(), String.class))
                .isEqualTo("CLOSED");
        assertThat(value("select status from stock_lock where order_id = :id", order.orderId(), String.class))
                .isEqualTo("RELEASED");
        assertThat(value("select stock_available from product_sku where id = :id", order.skuId(), Integer.class))
                .isEqualTo(10);
        assertThat(jdbcClient.sql("""
                        select count(*) from user_coupon
                        where id = :id and status = 'CLAIMED' and locked_order_id is null
                        """)
                .param("id", order.userCouponId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*) from stock_log
                        where order_id = :orderId and change_type = 'ORDER_RELEASE'
                        """)
                .param("orderId", order.orderId())
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(service.closeExpiredCreatedOrders(10)).isZero();
        assertThat(value("select stock_available from product_sku where id = :id", order.skuId(), Integer.class))
                .isEqualTo(10);
    }

    @Test
    void targetedCloseOnlyClaimsTheRequestedExpiredOrder() throws Exception {
        AppLoginSession session = appLogin("created-timeout-targeted-user");
        SeedOrder first = seedCreatedOrder(session.userId(), 2100L, false);
        SeedOrder target = seedCreatedOrder(session.userId(), 2200L, false);
        expire(first.orderId());
        expire(target.orderId());

        assertThat(service.closeExpiredCreatedOrder(target.orderId())).isTrue();

        assertThat(value("select status from shop_order where id = :id", first.orderId(), String.class))
                .isEqualTo("CREATED");
        assertThat(value("select status from shop_order where id = :id", target.orderId(), String.class))
                .isEqualTo("CLOSED");
    }

    @Test
    void zsetProcessorAcknowledgesOnlyAfterMysqlBecomesTerminal() throws Exception {
        AppLoginSession session = appLogin("created-timeout-zset-processor-user");
        SeedOrder future = seedCreatedOrder(session.userId(), 2100L, false);
        SeedOrder expired = seedCreatedOrder(session.userId(), 2200L, false);
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = timestampadd(HOUR, 1, current_timestamp)
                        where id = :id
                        """)
                .param("id", future.orderId())
                .update();
        expire(expired.orderId());

        PaymentTimeoutZSetProcessor.Result futureResult = zsetProcessor.process(future.orderId());
        PaymentTimeoutZSetProcessor.Result expiredResult = zsetProcessor.process(expired.orderId());

        assertThat(futureResult.acknowledged()).isFalse();
        assertThat(futureResult.nextAttemptAt()).isNotNull();
        assertThat(value("select status from shop_order where id = :id", future.orderId(), String.class))
                .isEqualTo("CREATED");
        assertThat(expiredResult.acknowledged()).isTrue();
        assertThat(value("select status from shop_order where id = :id", expired.orderId(), String.class))
                .isEqualTo("CLOSED");
        assertThat(zsetProcessor.process(9_999_999L).acknowledged()).isTrue();
    }

    @Test
    void nonExpiredAndFreshlyClaimedOrdersAreNotClosed() throws Exception {
        AppLoginSession session = appLogin("created-timeout-fresh-user");
        SeedOrder nonExpired = seedCreatedOrder(session.userId(), 2100L, false);
        SeedOrder freshClaim = seedCreatedOrder(session.userId(), 2200L, false);
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = timestampadd(HOUR, 1, current_timestamp)
                        where id = :id
                        """)
                .param("id", nonExpired.orderId())
                .update();
        expire(freshClaim.orderId());
        jdbcClient.sql("""
                        update shop_order
                        set created_timeout_claim_token = 'fresh-created-timeout-claim',
                            created_timeout_claimed_at = current_timestamp
                        where id = :id
                        """)
                .param("id", freshClaim.orderId())
                .update();

        assertThat(service.closeExpiredCreatedOrders(10)).isZero();
        assertThat(value("select status from shop_order where id = :id", nonExpired.orderId(), String.class))
                .isEqualTo("CREATED");
        assertThat(value("select status from shop_order where id = :id", freshClaim.orderId(), String.class))
                .isEqualTo("CREATED");
    }

    @Test
    void staleClaimIsTakenOverAndClosed() throws Exception {
        AppLoginSession session = appLogin("created-timeout-stale-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 2100L, false);
        expire(order.orderId());
        jdbcClient.sql("""
                        update shop_order
                        set created_timeout_claim_token = 'stale-created-timeout-claim',
                            created_timeout_claimed_at = timestampadd(MINUTE, -10, current_timestamp)
                        where id = :id
                        """)
                .param("id", order.orderId())
                .update();

        assertThat(service.closeExpiredCreatedOrders(10)).isEqualTo(1);
        assertThat(value("select status from shop_order where id = :id", order.orderId(), String.class))
                .isEqualTo("CLOSED");
        assertThat(value("select created_timeout_attempts from shop_order where id = :id", order.orderId(), Integer.class))
                .isEqualTo(1);
    }

    @Test
    void inconsistentCreatedOrderWithPaymentRowIsQuarantinedWithoutReleasingStock() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession session = appLogin("created-timeout-payment-anomaly-user");
        SeedOrder order = seedCreatedOrder(session.userId(), 2100L, false);
        expire(order.orderId());
        insertExpiredPayingPayment(order, "CREATEDANOMALY" + order.orderId(), session.openid(), 2100L);
        jdbcClient.sql("update shop_order set status = 'CREATED' where id = :id")
                .param("id", order.orderId())
                .update();

        assertThat(service.closeExpiredCreatedOrders(1)).isZero();
        assertThat(value("select status from shop_order where id = :id", order.orderId(), String.class))
                .isEqualTo("CREATED");
        assertThat(value("select status from stock_lock where order_id = :id", order.orderId(), String.class))
                .isEqualTo("LOCKED");
        assertThat(value("select stock_available from product_sku where id = :id", order.skuId(), Integer.class))
                .isEqualTo(8);
    }

    private void expire(long orderId) {
        jdbcClient.sql("""
                        update shop_order
                        set payment_expires_at = timestampadd(SECOND, -1, current_timestamp)
                        where id = :id
                        """)
                .param("id", orderId)
                .update();
    }

    private <T> T value(String sql, long id, Class<T> type) {
        return jdbcClient.sql(sql).param("id", id).query(type).single();
    }
}
