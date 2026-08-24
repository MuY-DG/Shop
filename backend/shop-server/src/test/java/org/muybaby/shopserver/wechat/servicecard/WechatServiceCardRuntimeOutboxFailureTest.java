package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class WechatServiceCardRuntimeOutboxFailureTest {

    private static final AtomicLong IDS = new AtomicLong(9_760_000L);

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    OrderStatusLogService orderStatusLogService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    WechatServiceCardRuntimeSettingService runtimeSettingService;

    @BeforeEach
    void failRuntimeReads() {
        clearInvocations(runtimeSettingService);
        doThrow(new DataAccessResourceFailureException("forced runtime read failure"))
                .when(runtimeSettingService).current();
    }

    @Test
    void runtimeReadFailureIsFailSoftForCommerceAndFailClosedForWorker() {
        assertThat(runtimeSettingService.captureEnabledFailSoft()).isFalse();
        assertThat(runtimeSettingService.workerReadyFailClosed()).isFalse();

        long orderId = IDS.incrementAndGet();
        LocalDateTime occurredAt = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            insert into shop_order
                                (id, order_no, user_id, status, source, idempotency_key,
                                 checkout_request_digest,
                                 payable_amount_cent, paid_amount_cent, paid_at,
                                 created_at, updated_at)
                            values
                                (:id, :orderNo, 1, 'PAID', 'DIRECT', :idempotencyKey,
                                 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                                 100, 100, :paidAt, :createdAt, :updatedAt)
                            """)
                    .param("id", orderId)
                    .param("orderNo", "RUNTIME-FAIL-SOFT-" + orderId)
                    .param("idempotencyKey", "runtime-fail-soft-" + orderId)
                    .param("paidAt", occurredAt)
                    .param("createdAt", occurredAt)
                    .param("updatedAt", occurredAt)
                    .update();
            orderStatusLogService.record(
                    orderId,
                    "PENDING_PAYMENT",
                    "PAID",
                    "RUNTIME_FAIL_SOFT_TEST",
                    "SYSTEM",
                    null,
                    "runtime setting read failure must not roll back commerce",
                    occurredAt
            );
        });

        verify(runtimeSettingService, atLeast(3)).current();

        assertThat(jdbcClient.sql("""
                        select count(*) from order_status_log
                        where order_id = :orderId
                          and event_type = 'RUNTIME_FAIL_SOFT_TEST'
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .single()).isZero();
    }
}
