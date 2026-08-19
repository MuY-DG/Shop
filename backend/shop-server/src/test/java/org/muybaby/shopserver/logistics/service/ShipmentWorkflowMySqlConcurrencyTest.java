package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@Import(ShipmentWorkflowMySqlConcurrencyTest.ProviderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShipmentWorkflowMySqlConcurrencyTest {

    private static final AtomicLong IDS = new AtomicLong(1_100_000L);
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN, 1L, "mysql-shipping-admin", List.of("R_SUPER"),
            List.of("order:ship", "order:shipping:retry", "order:waybill:manage")
    );

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("shipment_workflow")
            .withUsername("shop_test")
            .withPassword("shop_test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private LocalShipmentService localShipmentService;

    @Autowired
    private WechatShippingUploadCoordinator coordinator;

    @Autowired
    private ShippingProperties shippingProperties;

    @Autowired
    private BlockingProvider provider;

    @BeforeEach
    void reset() {
        jdbcClient.sql("delete from shipment_waybill_registration").update();
        jdbcClient.sql("delete from order_shipment_item").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from order_electronic_waybill_item").update();
        jdbcClient.sql("delete from order_electronic_waybill").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        provider.reset();
        shippingProperties.setUploadEnabled(true);
    }

    @Test
    void concurrentFirstShipmentHasOneWinnerOneRowAndShippedOrder() throws Exception {
        long orderId = insertPaidOrder();
        AdminShipOrderRequest request = new AdminShipOrderRequest(
                LogisticsType.PICKUP, "mysql pickup", null, null, null, null
        );

        List<Throwable> outcomes = race(
                () -> localShipmentService.create(ADMIN, orderId, request),
                () -> localShipmentService.create(ADMIN, orderId, request)
        );

        assertThat(outcomes.stream().filter(item -> item == null)).hasSize(1);
        assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                .singleElement()
                .satisfies(failure -> assertThat(((BusinessException) failure).errorCode())
                        .isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id=:id")
                .param("id", orderId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from shop_order where id=:id")
                .param("id", orderId).query(String.class).single()).isEqualTo("SHIPPED");
    }

    @Test
    void concurrentElectronicConfirmationIsIdempotentAndCreatesOneShipment() throws Exception {
        long orderId = insertPaidOrder();
        long waybillRecordId = insertCreatedWaybill(orderId);

        List<Throwable> outcomes = race(
                () -> localShipmentService.confirmElectronicWaybill(
                        ADMIN, orderId, waybillRecordId
                ),
                () -> localShipmentService.confirmElectronicWaybill(
                        ADMIN, orderId, waybillRecordId
                )
        );

        assertThat(outcomes).containsOnlyNulls();
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id=:id")
                .param("id", orderId).query(Integer.class).single()).isOne();
        assertThat(jdbcClient.sql("select status from shop_order where id=:id")
                .param("id", orderId).query(String.class).single()).isEqualTo("SHIPPED");
        assertThat(jdbcClient.sql("select status from order_electronic_waybill where id=:id")
                .param("id", waybillRecordId).query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbcClient.sql("""
                        select count(*) from order_status_log
                        where order_id=:id and event_type='ORDER_SHIPPED'
                        """)
                .param("id", orderId).query(Integer.class).single()).isOne();
    }

    @Test
    void concurrentOperatorRetryClaimsOnceCallsProviderOnceAndIncrementsOnce() throws Exception {
        long orderId = insertPaidOrder();
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId,
                new AdminShipOrderRequest(LogisticsType.PICKUP, "mysql retry", null, null, null, null)
        );
        jdbcClient.sql("""
                        update order_shipment set wechat_upload_status='FAILED', retry_count=0 where id=:id
                        """).param("id", local.shipmentId()).update();
        provider.block();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Throwable> first = executor.submit(() -> retryAfterBarrier(start, orderId));
            Future<Throwable> second = executor.submit(() -> retryAfterBarrier(start, orderId));
            assertThat(provider.entered.await(15, TimeUnit.SECONDS)).isTrue();
            provider.release.countDown();
            List<Throwable> outcomes = java.util.Arrays.asList(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
            );
            assertThat(outcomes.stream().filter(item -> item == null)).hasSize(1);
            assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
                    .singleElement()
                    .satisfies(failure -> assertThat(((BusinessException) failure).errorCode())
                            .isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));
        } finally {
            provider.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(provider.uploadCalls.get()).isEqualTo(1);
        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.retryCount()).isEqualTo(1);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
    }

    private List<Throwable> race(ThrowingAction first, ThrowingAction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Throwable> firstFuture = executor.submit(() -> runAfterBarrier(start, first));
            Future<Throwable> secondFuture = executor.submit(() -> runAfterBarrier(start, second));
            return java.util.Arrays.asList(
                    firstFuture.get(15, TimeUnit.SECONDS), secondFuture.get(15, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable runAfterBarrier(CyclicBarrier barrier, ThrowingAction action) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
            return catchThrowable(action::run);
        } catch (Exception ex) {
            return ex;
        }
    }

    private Throwable retryAfterBarrier(CyclicBarrier barrier, long orderId) {
        return runAfterBarrier(barrier, () -> coordinator.retry(ADMIN, orderId));
    }

    private long insertPaidOrder() {
        long id = IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        jdbcClient.sql("insert into app_user(id, openid, status) values (:id, :openid, 'ENABLED')")
                .param("id", id).param("openid", "mysql-openid-" + id).update();
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            receiver_name, receiver_phone, receiver_address,
                            payment_transaction_id, merchant_trade_no, paid_at, created_at, updated_at)
                        values (
                            :id, :orderNo, :id, 'PAID', 'CART', :key,
                            100, 100, '', 0, 0, 100, 100,
                            'Receiver', '13800008000', 'Address',
                            :transactionId, :outTradeNo, :now, :now, :now)
                        """)
                .param("id", id).param("orderNo", "MYSQL" + id).param("key", "mysql-" + id)
                .param("transactionId", "wx-mysql-" + id).param("outTradeNo", "mch-mysql-" + id)
                .param("now", now).update();
        jdbcClient.sql("""
                        insert into payment_order(
                            order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                            payer_openid, status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values (
                            :id, null, :outTradeNo, :prepayId, :transactionId,
                            :openid, 'PAID', 100, :now, :now, :now, :now)
                        """)
                .param("id", id).param("outTradeNo", "mch-mysql-" + id)
                .param("prepayId", "prepay-mysql-" + id).param("transactionId", "wx-mysql-" + id)
                .param("openid", "mysql-openid-" + id).param("now", now).update();
        jdbcClient.sql("""
                        insert into order_item(
                            order_id, sku_id, spu_id, product_title, product_subtitle,
                            main_image, sku_image, display_image, sku_code, spec_text,
                            original_price_cent, unit_price_cent, quantity,
                            line_original_amount_cent, line_amount_cent, created_at)
                        values (
                            :id, 1, 1, 'MySQL shipment item', '',
                            'https://example.test/item.jpg', 'https://example.test/item.jpg',
                            'https://example.test/item.jpg', :skuCode, '',
                            100, 100, 1, 100, 100, :now)
                        """)
                .param("id", id)
                .param("skuCode", "MYSQL-SKU-" + id)
                .param("now", now)
                .update();
        return id;
    }

    private long insertCreatedWaybill(long orderId) {
        jdbcClient.sql("""
                        insert into order_electronic_waybill(
                            order_id, attempt_no, idempotency_key, request_digest,
                            provider_order_id, mode, delivery_id, delivery_name, biz_id,
                            service_type, service_name, status, pending_operation, waybill_id,
                            parcel_count, weight_kg, length_cm, width_cm, height_cm,
                            sender_name, sender_mobile, sender_company, sender_province,
                            sender_city, sender_district, sender_detail_address,
                            receiver_name, receiver_phone, receiver_province, receiver_city,
                            receiver_district, receiver_detail_address, payment_order_id,
                            payer_openid, created_by)
                        select
                            :orderId, 1, :idempotencyKey, :requestDigest, :providerOrderId,
                            'SANDBOX', 'TEST', '微信官方测试运力', 'test_biz_id', 1,
                            'test_service_name', 'CREATED', 'NONE', :waybillId, 1,
                            1.000, 20.00, 15.00, 10.00, 'Sender', '13800138000',
                            'Shop', '广东省', '深圳市', '南山区', '测试路1号',
                            o.receiver_name, o.receiver_phone, '广东省', '深圳市', '南山区',
                            '测试路2号', po.id, po.payer_openid, 1
                        from shop_order o
                        join payment_order po on po.order_id = o.id
                        where o.id = :orderId
                        """)
                .param("orderId", orderId)
                .param("idempotencyKey", "mysql-waybill-" + orderId)
                .param("requestDigest", "d".repeat(64))
                .param("providerOrderId", "SHOPWB-" + orderId + "-1")
                .param("waybillId", "TEST-MYSQL-" + orderId)
                .update();
        long waybillRecordId = jdbcClient.sql(
                        "select id from order_electronic_waybill where order_id=:orderId"
                )
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into order_electronic_waybill_item(
                            electronic_waybill_id, order_item_id, quantity
                        )
                        select :waybillRecordId, item.id, item.quantity - item.refunded_quantity
                        from order_item item
                        where item.order_id = :orderId
                        """)
                .param("waybillRecordId", waybillRecordId)
                .param("orderId", orderId)
                .update();
        return waybillRecordId;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        BlockingProvider blockingProvider() {
            return new BlockingProvider();
        }
    }

    static class BlockingProvider implements WechatShippingProvider {

        private final AtomicInteger uploadCalls = new AtomicInteger();
        private CountDownLatch entered = new CountDownLatch(0);
        private CountDownLatch release = new CountDownLatch(0);

        void reset() {
            uploadCalls.set(0);
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
        }

        void block() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        @Override
        public WechatProviderMode mode() {
            return WechatProviderMode.REAL;
        }

        @Override
        public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
            uploadCalls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("provider barrier timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider interrupted");
            }
            return WechatShippingUploadResult.uploaded();
        }

        @Override
        public WechatShippingCapabilityResult queryCapability() {
            return WechatShippingCapabilityResult.available();
        }

        @Override
        public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
            return List.of();
        }
    }
}
