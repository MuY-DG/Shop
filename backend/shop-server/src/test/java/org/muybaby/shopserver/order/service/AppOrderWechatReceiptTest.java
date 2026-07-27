package org.muybaby.shopserver.order.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.provider.WechatReceiptQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.order.dto.OrderReceiptResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppOrderWechatReceiptTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(97_000L);
    private static final String TRANSACTION_ID = "4200000000000000097";

    @Autowired
    private AppOrderService appOrderService;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoBean
    private WechatShippingProvider wechatShippingProvider;

    @BeforeEach
    void setUp() {
        reset(wechatShippingProvider);
        when(wechatShippingProvider.mode()).thenReturn(WechatProviderMode.REAL);
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from app_user").update();
    }

    @Test
    void confirmedWechatOrderCompletesOwnedLocalOrderUsingStoredTransactionId() {
        long userId = insertUser();
        long orderId = insertOrder(userId, "SHIPPED", TRANSACTION_ID);
        when(wechatShippingProvider.queryReceiptStatus(TRANSACTION_ID))
                .thenReturn(WechatReceiptQueryResult.confirmed(3));

        OrderReceiptResponse response = appOrderService.confirmReceipt(principal(userId), orderId);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.completedAt()).isNotNull();
        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        verify(wechatShippingProvider).queryReceiptStatus(TRANSACTION_ID);
    }

    @Test
    void unconfirmedWechatOrderFailsClosedAndLeavesLocalOrderShipped() {
        long userId = insertUser();
        long orderId = insertOrder(userId, "SHIPPED", TRANSACTION_ID);
        when(wechatShippingProvider.queryReceiptStatus(TRANSACTION_ID))
                .thenReturn(WechatReceiptQueryResult.notConfirmed(2));

        BusinessException exception = catchThrowableOfType(
                () -> appOrderService.confirmReceipt(principal(userId), orderId),
                BusinessException.class
        );

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.WECHAT_RECEIPT_NOT_CONFIRMED);
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPED");
    }

    @Test
    void missingOrUnavailableWechatStateNeverCompletesLocalOrder() {
        long userId = insertUser();
        long missingTransactionOrder = insertOrder(userId, "SHIPPED", "");

        BusinessException missing = catchThrowableOfType(
                () -> appOrderService.confirmReceipt(principal(userId), missingTransactionOrder),
                BusinessException.class
        );

        assertThat(missing).isNotNull();
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        verify(wechatShippingProvider, never()).queryReceiptStatus(TRANSACTION_ID);
        assertThat(orderStatus(missingTransactionOrder)).isEqualTo("SHIPPED");

        long unavailableOrder = insertOrder(userId, "SHIPPED", TRANSACTION_ID);
        when(wechatShippingProvider.queryReceiptStatus(TRANSACTION_ID))
                .thenReturn(WechatReceiptQueryResult.unknown(
                        "REQUEST_AMBIGUOUS",
                        "WeChat receipt status could not be confirmed"
                ));

        BusinessException unavailable = catchThrowableOfType(
                () -> appOrderService.confirmReceipt(principal(userId), unavailableOrder),
                BusinessException.class
        );

        assertThat(unavailable).isNotNull();
        assertThat(unavailable.errorCode()).isEqualTo(ErrorCode.WECHAT_RECEIPT_STATUS_UNAVAILABLE);
        assertThat(orderStatus(unavailableOrder)).isEqualTo("SHIPPED");
    }

    @Test
    void completedReplayDoesNotDependOnWechatAvailability() {
        long userId = insertUser();
        long orderId = insertOrder(userId, "COMPLETED", TRANSACTION_ID);
        LocalDateTime completedAt = LocalDateTime.of(2026, 7, 26, 18, 30);
        jdbcClient.sql("""
                        update shop_order
                        set completed_at = :completedAt
                        where id = :orderId
                        """)
                .param("completedAt", completedAt)
                .param("orderId", orderId)
                .update();

        OrderReceiptResponse response = appOrderService.confirmReceipt(principal(userId), orderId);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.completedAt()).isEqualTo(completedAt);
        verify(wechatShippingProvider, never()).queryReceiptStatus(TRANSACTION_ID);
    }

    @Test
    void concurrentConfirmedRequestsPersistOneCompletionTime() throws Exception {
        long userId = insertUser();
        long orderId = insertOrder(userId, "SHIPPED", TRANSACTION_ID);
        when(wechatShippingProvider.queryReceiptStatus(TRANSACTION_ID))
                .thenReturn(WechatReceiptQueryResult.confirmed(3));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<OrderReceiptResponse> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return appOrderService.confirmReceipt(principal(userId), orderId);
            });
            Future<OrderReceiptResponse> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return appOrderService.confirmReceipt(principal(userId), orderId);
            });

            OrderReceiptResponse firstResult = first.get(10, TimeUnit.SECONDS);
            OrderReceiptResponse secondResult = second.get(10, TimeUnit.SECONDS);
            LocalDateTime stored = jdbcClient.sql("""
                            select completed_at
                            from shop_order
                            where id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query(LocalDateTime.class)
                    .single();

            assertThat(firstResult.completedAt()).isEqualTo(stored);
            assertThat(secondResult.completedAt()).isEqualTo(stored);
            assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long insertUser() {
        long userId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values
                            (:userId, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("userId", userId)
                .param("openid", "receipt-openid-" + userId)
                .param("unionid", "receipt-unionid-" + userId)
                .param("now", now)
                .update();
        return userId;
    }

    private long insertOrder(long userId, String status, String transactionId) {
        long orderId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address,
                             payment_transaction_id, shipped_at, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             1000, 1000, 0, 0, 1000, 1000,
                             'Receipt User', '13800138000', 'Receipt Address',
                             :transactionId, :shippedAt, :now, :now)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "RECEIPT-" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "receipt-" + orderId)
                .param("transactionId", transactionId)
                .param("shippedAt", now.minusHours(1))
                .param("now", now)
                .update();
        return orderId;
    }

    private String orderStatus(long orderId) {
        return jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private AuthenticatedPrincipal principal(long userId) {
        return new AuthenticatedPrincipal(
                TokenKind.APP, userId, "receipt-user-" + userId, List.of(), List.of()
        );
    }
}
