package org.muybaby.shopserver.aftersale.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.payment.PaymentTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AfterSaleReturnExpiryServiceTest extends PaymentTestSupport {

    @Autowired
    private AfterSaleReturnExpiryService expiryService;

    @Test
    void overdueReturnIsClosedOnceAndNoLongerBlocksAnotherApplication() throws Exception {
        seedEnabledPaymentConfig();
        AppLoginSession user = appLogin("return-expiry-user");
        SeedPaidOrder order = seedPaidOrder(user, 6980L, "PAID", "return-expiry-transaction");
        long afterSaleId = order.orderId() + 20;
        jdbcClient.sql("""
                        insert into after_sale_request (
                            id, after_sale_no, order_id, user_id, after_sale_type, status,
                            reason, description, requested_amount_cent, approved_amount_cent,
                            request_digest, source_order_status, return_deadline_at
                        ) values (
                            :id, :afterSaleNo, :orderId, :userId, 'RETURN_REFUND', 'WAITING_RETURN',
                            '退货退款', '', 6980, 6980, 'expiry-digest', 'PAID',
                            dateadd('MINUTE', -1, current_timestamp)
                        )
                        """)
                .param("id", afterSaleId)
                .param("afterSaleNo", "AS-EXPIRY-" + afterSaleId)
                .param("orderId", order.orderId())
                .param("userId", user.userId())
                .update();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return expiryService.expireDueForOrder(order.orderId());
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(futures.stream().map(future -> {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).filter(Boolean::booleanValue).count())
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcClient.sql("""
                        select status from after_sale_request where id = :id
                        """)
                .param("id", afterSaleId)
                .query(String.class)
                .single()).isEqualTo("CANCELLED");
        assertThat(jdbcClient.sql("""
                        select count(*) from after_sale_status_log
                        where after_sale_id = :id and event_type = 'RETURN_EXPIRED'
                        """)
                .param("id", afterSaleId)
                .query(Long.class)
                .single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        select count(*) from order_status_log
                        where after_sale_id = :id and event_type = 'RETURN_EXPIRED'
                        """)
                .param("id", afterSaleId)
                .query(Long.class)
                .single()).isEqualTo(1L);
    }
}
