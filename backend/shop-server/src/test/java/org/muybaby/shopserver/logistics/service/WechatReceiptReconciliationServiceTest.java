package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.provider.WechatReceiptQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatReceiptReconciliationServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(181_000L);
    private static final LocalDateTime OLD_SHIPPED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WechatReceiptReconciliationService reconciliationService;

    @MockitoBean
    private WechatShippingProvider wechatShippingProvider;

    @BeforeEach
    void setUp() {
        reset(wechatShippingProvider);
        when(wechatShippingProvider.mode()).thenReturn(WechatProviderMode.REAL);
        jdbcClient.sql("delete from order_status_log").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from app_user").update();
    }

    @Test
    void confirmedWechatReceiptAutomaticallyCompletesUploadedLocalOrder() {
        long orderId = insertUploadedOrder("wx-auto-confirmed");
        when(wechatShippingProvider.queryReceiptStatus("wx-auto-confirmed"))
                .thenReturn(WechatReceiptQueryResult.confirmed(6));

        assertThat(reconciliationService.reconcilePendingReceipts(10)).isEqualTo(1);

        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_status_log
                        where order_id = :orderId
                          and event_type = 'ORDER_AUTO_COMPLETED'
                          and operator_type = 'SYSTEM'
                          and operator_id = 0
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        ReceiptCheckState state = receiptCheckState(orderId);
        assertThat(state.claimToken()).isNull();
        assertThat(state.claimedAt()).isNull();
        assertThat(state.checkedAt()).isNotNull();
        assertThat(state.orderState()).isEqualTo(6);
        assertThat(state.errorCode()).isEmpty();
    }

    @Test
    void unconfirmedReceiptStaysShippedAndIsNotRequeriedBeforeInterval() {
        long orderId = insertUploadedOrder("wx-still-shipped");
        when(wechatShippingProvider.queryReceiptStatus("wx-still-shipped"))
                .thenReturn(WechatReceiptQueryResult.notConfirmed(2));

        assertThat(reconciliationService.reconcilePendingReceipts(10)).isZero();
        assertThat(reconciliationService.reconcilePendingReceipts(10)).isZero();

        assertThat(orderStatus(orderId)).isEqualTo("SHIPPED");
        assertThat(receiptCheckState(orderId).orderState()).isEqualTo(2);
        verify(wechatShippingProvider, times(1)).queryReceiptStatus("wx-still-shipped");
    }

    @Test
    void activeAfterSaleAndNonUploadedShipmentAreNotQueried() {
        long blockedOrderId = insertUploadedOrder("wx-after-sale-blocked");
        insertBlockingAfterSale(blockedOrderId);
        long skippedOrderId = insertOrder("wx-not-uploaded");
        insertShipment(skippedOrderId, "REAL", "SKIPPED");

        assertThat(reconciliationService.reconcilePendingReceipts(10)).isZero();

        assertThat(orderStatus(blockedOrderId)).isEqualTo("SHIPPED");
        assertThat(orderStatus(skippedOrderId)).isEqualTo("SHIPPED");
        verify(wechatShippingProvider, times(0)).queryReceiptStatus("wx-after-sale-blocked");
        verify(wechatShippingProvider, times(0)).queryReceiptStatus("wx-not-uploaded");
    }

    @Test
    void ambiguousWechatResultReleasesClaimAndRecordsSafeError() {
        long orderId = insertUploadedOrder("wx-ambiguous");
        when(wechatShippingProvider.queryReceiptStatus("wx-ambiguous"))
                .thenReturn(WechatReceiptQueryResult.unknown(
                        "REQUEST_AMBIGUOUS",
                        "WeChat receipt status could not be confirmed"
                ));

        assertThat(reconciliationService.reconcilePendingReceipts(10)).isZero();

        ReceiptCheckState state = receiptCheckState(orderId);
        assertThat(orderStatus(orderId)).isEqualTo("SHIPPED");
        assertThat(state.claimToken()).isNull();
        assertThat(state.claimedAt()).isNull();
        assertThat(state.checkedAt()).isNotNull();
        assertThat(state.orderState()).isNull();
        assertThat(state.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
    }

    @Test
    void staleLeaseIsReclaimedAfterClaimTimeout() {
        long orderId = insertUploadedOrder("wx-stale-lease");
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_receipt_claim_token = 'interrupted-worker',
                            wechat_receipt_claimed_at = :claimedAt
                        where order_id = :orderId
                        """)
                .param("claimedAt", OLD_SHIPPED_AT)
                .param("orderId", orderId)
                .update();
        when(wechatShippingProvider.queryReceiptStatus("wx-stale-lease"))
                .thenReturn(WechatReceiptQueryResult.confirmed(4));

        assertThat(reconciliationService.reconcilePendingReceipts(10)).isEqualTo(1);

        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        assertThat(receiptCheckState(orderId).claimToken()).isNull();
        verify(wechatShippingProvider).queryReceiptStatus("wx-stale-lease");
    }

    @Test
    void concurrentScansLeaseOneShipmentAndCallWechatOnce() throws Exception {
        long orderId = insertUploadedOrder("wx-concurrent");
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerRelease = new CountDownLatch(1);
        when(wechatShippingProvider.queryReceiptStatus("wx-concurrent"))
                .thenAnswer(invocation -> {
                    providerEntered.countDown();
                    assertThat(providerRelease.await(10, TimeUnit.SECONDS)).isTrue();
                    return WechatReceiptQueryResult.confirmed(3);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(
                    () -> reconciliationService.reconcilePendingReceipts(1));
            assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(
                    () -> reconciliationService.reconcilePendingReceipts(1));

            assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
            providerRelease.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            providerRelease.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(orderStatus(orderId)).isEqualTo("COMPLETED");
        verify(wechatShippingProvider, times(1)).queryReceiptStatus("wx-concurrent");
    }

    private long insertUploadedOrder(String transactionId) {
        long orderId = insertOrder(transactionId);
        insertShipment(orderId, "REAL", "UPLOADED");
        return orderId;
    }

    private long insertOrder(String transactionId) {
        long userId = SEQUENCE.incrementAndGet();
        long orderId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user
                            (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values
                            (:userId, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("userId", userId)
                .param("openid", "receipt-reconcile-openid-" + userId)
                .param("unionid", "receipt-reconcile-unionid-" + userId)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address,
                             payment_transaction_id, shipped_at, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, 'SHIPPED', 'CART', :idempotencyKey,
                             1000, 1000, 0, 0, 1000, 1000,
                             'Receipt User', '13800138000', 'Receipt Address',
                             :transactionId, :shippedAt, :now, :now)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "RECONCILE-" + orderId)
                .param("userId", userId)
                .param("idempotencyKey", "receipt-reconcile-" + orderId)
                .param("transactionId", transactionId)
                .param("shippedAt", OLD_SHIPPED_AT)
                .param("now", now)
                .update();
        return orderId;
    }

    private void insertShipment(long orderId, String providerMode, String uploadStatus) {
        jdbcClient.sql("""
                        insert into order_shipment
                            (order_id, logistics_type, delivery_mode, item_desc,
                             express_company_name, tracking_no, shipment_note, status,
                             wechat_provider_mode, wechat_upload_status,
                             wechat_error_code, wechat_error_message, retry_count,
                             shipped_at, wechat_uploaded_at, created_at, updated_at)
                        values
                            (:orderId, 3, 1, '虚拟测试商品',
                             null, null, '', 'SHIPPED',
                             :providerMode, :uploadStatus,
                             '', '', 0,
                             :shippedAt, :uploadedAt, :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("providerMode", providerMode)
                .param("uploadStatus", uploadStatus)
                .param("shippedAt", OLD_SHIPPED_AT)
                .param("uploadedAt", "UPLOADED".equals(uploadStatus) ? OLD_SHIPPED_AT : null)
                .param("createdAt", OLD_SHIPPED_AT)
                .update();
    }

    private void insertBlockingAfterSale(long orderId) {
        long afterSaleId = SEQUENCE.incrementAndGet();
        long userId = jdbcClient.sql("select user_id from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status,
                             reason, description, requested_amount_cent, created_at, updated_at)
                        values
                            (:afterSaleId, :afterSaleNo, :orderId, :userId, 'REFUND_ONLY', 'REQUESTED',
                             '自动收货对账测试', '', 1000, :createdAt, :createdAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("afterSaleNo", "ASRECON" + afterSaleId)
                .param("orderId", orderId)
                .param("userId", userId)
                .param("createdAt", OLD_SHIPPED_AT)
                .update();
    }

    private String orderStatus(long orderId) {
        return jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    private ReceiptCheckState receiptCheckState(long orderId) {
        return jdbcClient.sql("""
                        select wechat_receipt_claim_token,
                               wechat_receipt_claimed_at,
                               wechat_receipt_last_checked_at,
                               wechat_receipt_order_state,
                               wechat_receipt_last_error_code
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ReceiptCheckState(
                        rs.getString("wechat_receipt_claim_token"),
                        rs.getObject("wechat_receipt_claimed_at", LocalDateTime.class),
                        rs.getObject("wechat_receipt_last_checked_at", LocalDateTime.class),
                        rs.getObject("wechat_receipt_order_state", Integer.class),
                        rs.getString("wechat_receipt_last_error_code")
                ))
                .single();
    }

    private record ReceiptCheckState(
            String claimToken,
            LocalDateTime claimedAt,
            LocalDateTime checkedAt,
            Integer orderState,
            String errorCode
    ) {
    }
}
