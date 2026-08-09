package org.muybaby.shopserver.logistics.waybill.registration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.service.AdminShipmentService;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationProvider;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WechatWaybillRegistrationCoordinatorTest.ProviderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatWaybillRegistrationCoordinatorTest {

    private static final AtomicLong IDS = new AtomicLong(1_983_000L);

    @Autowired
    private WechatWaybillRegistrationCoordinator coordinator;

    @Autowired
    private AdminShipmentService adminShipmentService;

    @Autowired
    private RecordingRegistrationProvider provider;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void reset() {
        jdbcClient.sql("delete from shipment_waybill_registration").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from order_electronic_waybill").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        jdbcClient.sql("""
                        update wechat_express_setting
                        set message_enabled = false,
                            sender_mobile = '13900139000'
                        where id = 1
                        """).update();
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values ('SF', '顺丰速运', true, current_timestamp)
                        """).update();
        provider.reset();
    }

    @Test
    void reconstructsHistoricalPaymentIdentityAndAppliesSourcePolicyWithoutAppUser() {
        jdbcClient.sql("update wechat_express_setting set message_enabled = true where id = 1").update();
        ShipmentSeed manual = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);

        coordinator.attemptInitial(manual.shipmentId());

        assertThat(provider.followRequests).singleElement().satisfies(request -> {
            assertThat(request.shipmentId()).isEqualTo(manual.shipmentId());
            assertThat(request.openid()).isEqualTo("paid-openid-" + manual.orderId());
            assertThat(request.transactionId()).isEqualTo("paid-transaction-" + manual.orderId());
            assertThat(request.senderPhone()).isEqualTo("13900139000");
            assertThat(request.receiverPhone()).isEqualTo("13800138000");
            assertThat(request.deliveryId()).isEqualTo("SF");
            assertThat(request.waybillId()).isEqualTo("SF" + manual.orderId());
            assertThat(request.orderDetailPath())
                    .isEqualTo("pages/order/detail/detail?order_id=" + manual.orderId());
            assertThat(request.goods()).singleElement().satisfies(goods -> {
                assertThat(goods.goodsName()).isEqualTo("菌汤锅底");
                assertThat(goods.goodsImageUrl()).isEqualTo("https://img.example.test/goods.webp");
            });
        });
        assertRegistration(manual.shipmentId(), "FOLLOW", "REGISTERED", "token-" + manual.shipmentId(), 1);
        assertThat(provider.transactionActive).isFalse();

        provider.reset();
        ShipmentSeed electronic = insertExistingShipment(
                LogisticsType.EXPRESS, ShipmentSource.WECHAT_WAYBILL, true, true
        );
        coordinator.attemptInitial(electronic.shipmentId());

        assertThat(provider.traceRequests).hasSize(1);
        assertThat(provider.followRequests).isEmpty();
        assertRegistration(electronic.shipmentId(), "TRACE", "REGISTERED", "token-" + electronic.shipmentId(), 1);
    }

    @Test
    void failedRegistrationNeverChangesStaticShipmentOrShippedOrder() {
        ShipmentSeed seed = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);
        provider.nextResult = WechatWaybillRegistrationResult.failure(
                WechatProviderOutcome.REJECTED, "WECHAT_930561", "safe rejection"
        );

        coordinator.attemptInitial(seed.shipmentId());

        assertRegistration(seed.shipmentId(), "TRACE", "FAILED", "", 1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where id = :shipmentId
                          and order_id = :orderId
                          and status = 'SHIPPED'
                          and express_company_code = 'SF'
                          and tracking_no = :trackingNo
                        """)
                .param("shipmentId", seed.shipmentId())
                .param("orderId", seed.orderId())
                .param("trackingNo", "SF" + seed.orderId())
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", seed.orderId())
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
    }

    @Test
    void oneRowClaimCasPreventsConcurrentDuplicateRegistration() throws Exception {
        ShipmentSeed seed = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);
        provider.blockNextCall();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> coordinator.attemptInitial(seed.shipmentId()));
            assertThat(provider.awaitEntered()).isTrue();

            coordinator.attemptInitial(seed.shipmentId());
            provider.releaseBlockedCall();
            first.get(5, TimeUnit.SECONDS);
        }

        assertThat(provider.traceRequests).hasSize(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from shipment_waybill_registration
                        where shipment_id = :shipmentId
                        """)
                .param("shipmentId", seed.shipmentId())
                .query(Integer.class)
                .single()).isOne();
        assertRegistration(seed.shipmentId(), "TRACE", "REGISTERED", "token-" + seed.shipmentId(), 1);
    }

    @Test
    void cleanupWinningOrderLockCannotLeaveOrphanRegistration() throws Exception {
        ShipmentSeed seed = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);
        CountDownLatch cleanupLockedOrder = new CountDownLatch(1);
        CountDownLatch allowCleanupDelete = new CountDownLatch(1);
        TransactionTemplate cleanupTransaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(() -> cleanupTransaction.executeWithoutResult(status -> {
                jdbcClient.sql("select id from shop_order where id = :orderId for update")
                        .param("orderId", seed.orderId())
                        .query(Long.class)
                        .single();
                cleanupLockedOrder.countDown();
                awaitLatch(allowCleanupDelete);
                jdbcClient.sql("delete from shipment_waybill_registration where shipment_id = :shipmentId")
                        .param("shipmentId", seed.shipmentId())
                        .update();
                jdbcClient.sql("delete from order_shipment where order_id = :orderId")
                        .param("orderId", seed.orderId())
                        .update();
                jdbcClient.sql("delete from payment_order where order_id = :orderId")
                        .param("orderId", seed.orderId())
                        .update();
                jdbcClient.sql("delete from order_item where order_id = :orderId")
                        .param("orderId", seed.orderId())
                        .update();
                jdbcClient.sql("delete from shop_order where id = :orderId")
                        .param("orderId", seed.orderId())
                        .update();
            }));
            assertThat(cleanupLockedOrder.await(5, TimeUnit.SECONDS)).isTrue();

            var registration = executor.submit(() -> catchThrowable(
                    () -> coordinator.attemptInitial(seed.shipmentId())
            ));
            assertThat(awaitRegistrationOrderLockWait()).isTrue();
            allowCleanupDelete.countDown();

            cleanup.get(5, TimeUnit.SECONDS);
            assertThat(registration.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(BusinessException.class, failure ->
                            assertThat(failure.errorCode()).isEqualTo(ErrorCode.ORDER_STATE_CONFLICT));
        } finally {
            allowCleanupDelete.countDown();
        }

        assertThat(jdbcClient.sql("select count(*) from shipment_waybill_registration")
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from order_shipment where id = :shipmentId")
                .param("shipmentId", seed.shipmentId())
                .query(Integer.class)
                .single()).isZero();
        assertThat(provider.traceRequests).isEmpty();
        assertThat(provider.followRequests).isEmpty();
    }

    @Test
    void ownerOnlyTokenEndpointIsNoStoreAndOrdinaryDetailNeverContainsToken() throws Exception {
        ShipmentSeed seed = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);
        String ownerToken = appToken(seed.userId());
        String foreignToken = appToken(seed.userId() + 1);

        mockMvc.perform(post("/app/orders/{orderId}/logistics/waybill-token", seed.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreignToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.code()));

        mockMvc.perform(post("/app/orders/{orderId}/logistics/waybill-token", seed.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.data.waybillToken").value("token-" + seed.shipmentId()));

        mockMvc.perform(get("/app/orders/{orderId}", seed.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.waybillTrackingSupported").value(true))
                .andExpect(jsonPath("$.data.shipment.waybillRegistrationKind").value("TRACE"))
                .andExpect(jsonPath("$.data.shipment.waybillRegistrationStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.data.shipment.waybillToken").doesNotExist());
    }

    @Test
    void tokenEndpointRejectsMissingNonExpressAndIncompleteShipments() throws Exception {
        long missingShipmentOrder = insertOrder(true, true);
        ShipmentSeed nonExpress = insertExistingShipment(
                LogisticsType.PICKUP, ShipmentSource.MANUAL, true, true
        );
        ShipmentSeed incomplete = insertExistingShipment(
                LogisticsType.EXPRESS, ShipmentSource.MANUAL, false, true
        );

        mockMvc.perform(post("/app/orders/{orderId}/logistics/waybill-token", missingShipmentOrder)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken(missingShipmentOrder)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));
        mockMvc.perform(post("/app/orders/{orderId}/logistics/waybill-token", nonExpress.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken(nonExpress.userId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));
        mockMvc.perform(post("/app/orders/{orderId}/logistics/waybill-token", incomplete.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + appToken(incomplete.userId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.WECHAT_WAYBILL_REGISTRATION_UNAVAILABLE.code()));
    }

    @Test
    void adminRetryRequiresPermissionAndSafelyRetriesFailedRegistration() throws Exception {
        ShipmentSeed seed = insertExistingShipment(LogisticsType.EXPRESS, ShipmentSource.MANUAL, true, true);
        provider.nextResult = WechatWaybillRegistrationResult.failure(
                WechatProviderOutcome.UNKNOWN, "REQUEST_AMBIGUOUS", "unknown"
        );
        coordinator.attemptInitial(seed.shipmentId());
        provider.reset();

        String denied = adminToken(List.of());
        mockMvc.perform(post("/admin/orders/{orderId}/shipping/retry-waybill-registration", seed.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + denied))
                .andExpect(status().isForbidden());

        String permitted = adminToken(List.of("order:shipping:registration:retry"));
        mockMvc.perform(post("/admin/orders/{orderId}/shipping/retry-waybill-registration", seed.orderId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + permitted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waybillRegistrationKind").value("TRACE"))
                .andExpect(jsonPath("$.data.waybillRegistrationStatus").value("REGISTERED"));

        assertRegistration(seed.shipmentId(), "TRACE", "REGISTERED", "token-" + seed.shipmentId(), 2);
    }

    @Test
    void manualAndElectronicShipmentCommitBeforeIndependentRegistrationAttempt() {
        long manualOrderId = insertPaidOrder();
        provider.nextResult = WechatWaybillRegistrationResult.failure(
                WechatProviderOutcome.UNKNOWN, "REQUEST_AMBIGUOUS", "unknown"
        );
        var manual = adminShipmentService.ship(
                adminPrincipal(),
                manualOrderId,
                new AdminShipOrderRequest(
                        LogisticsType.EXPRESS, "菌汤锅底 x1", "SF", "SF" + manualOrderId,
                        "13900139000", ""
                )
        );

        assertThat(manual.localShipmentStatus()).isEqualTo("SHIPPED");
        assertThat(manual.waybillRegistrationStatus()).isEqualTo(WaybillRegistrationStatus.UNKNOWN);
        assertThat(provider.transactionActive).isFalse();

        provider.reset();
        long electronicOrderId = insertPaidOrder();
        long waybillRecordId = insertElectronicWaybill(electronicOrderId);
        var electronic = adminShipmentService.confirmElectronicWaybill(
                adminPrincipal(), electronicOrderId, waybillRecordId
        );

        assertThat(electronic.localShipmentStatus()).isEqualTo("SHIPPED");
        assertThat(electronic.shipmentSource()).isEqualTo(ShipmentSource.WECHAT_WAYBILL);
        assertThat(electronic.waybillRegistrationStatus()).isEqualTo(WaybillRegistrationStatus.REGISTERED);
        assertThat(provider.traceRequests).hasSize(1);
        assertThat(provider.followRequests).isEmpty();
        assertThat(provider.transactionActive).isFalse();
    }

    private ShipmentSeed insertExistingShipment(
            LogisticsType logisticsType,
            ShipmentSource shipmentSource,
            boolean paymentComplete,
            boolean goodsComplete
    ) {
        long orderId = insertOrder(paymentComplete, goodsComplete);
        String expressCode = logisticsType == LogisticsType.EXPRESS ? "SF" : null;
        String trackingNo = logisticsType == LogisticsType.EXPRESS ? "SF" + orderId : null;
        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, logistics_type, delivery_mode, item_desc,
                            express_company_code, express_company_name, tracking_no,
                            consignor_contact, receiver_contact, shipment_note,
                            shipment_source, electronic_waybill_id,
                            status, wechat_provider_mode, wechat_upload_status,
                            wechat_error_code, wechat_error_message, retry_count,
                            shipped_at, created_at, updated_at)
                        values (
                            :orderId, :logisticsType, 1, '菌汤锅底 x1',
                            :expressCode, :expressName, :trackingNo,
                            null, null, '', :shipmentSource, null,
                            'SHIPPED', 'DISABLED', 'SKIPPED', '', '', 0,
                            current_timestamp, current_timestamp, current_timestamp)
                        """)
                .param("orderId", orderId)
                .param("logisticsType", logisticsType.value())
                .param("expressCode", expressCode)
                .param("expressName", expressCode == null ? null : "顺丰速运")
                .param("trackingNo", trackingNo)
                .param("shipmentSource", shipmentSource.name())
                .update();
        long shipmentId = jdbcClient.sql("select id from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        return new ShipmentSeed(orderId, orderId, shipmentId);
    }

    private long insertOrder(boolean paymentComplete, boolean goodsComplete) {
        long orderId = IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.of(2026, 8, 8, 12, 0);
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            receiver_name, receiver_phone, receiver_address,
                            payment_transaction_id, merchant_trade_no, paid_at, shipped_at,
                            created_at, updated_at)
                        values (
                            :id, :orderNo, :id, :status, 'CART', :key,
                            100, 100, '', 0, 0, 100, 100,
                            '测试买家', '13800138000', '广东省深圳市南山区测试路2号',
                            :transactionId, :outTradeNo, :now, :shippedAt, :now, :now)
                        """)
                .param("id", orderId)
                .param("orderNo", "REG" + orderId)
                .param("status", "SHIPPED")
                .param("key", "registration-" + orderId)
                .param("transactionId", paymentComplete ? "paid-transaction-" + orderId : "")
                .param("outTradeNo", "merchant-" + orderId)
                .param("now", now)
                .param("shippedAt", now)
                .update();
        jdbcClient.sql("""
                        insert into order_item(
                            order_id, sku_id, spu_id, product_title, product_subtitle,
                            main_image, sku_image, display_image, sku_code, spec_text,
                            original_price_cent, unit_price_cent, quantity,
                            line_original_amount_cent, line_amount_cent, created_at)
                        values (
                            :orderId, :orderId, :orderId, '菌汤锅底', '',
                            :image, '', '', :skuCode, '默认规格',
                            100, 100, 1, 100, 100, :now)
                        """)
                .param("orderId", orderId)
                .param("image", goodsComplete ? "https://img.example.test/goods.webp" : "")
                .param("skuCode", "SKU-" + orderId)
                .param("now", now)
                .update();
        if (paymentComplete) {
            jdbcClient.sql("""
                            insert into payment_order(
                                order_id, payment_config_id, out_trade_no, prepay_id,
                                transaction_id, payer_openid, status, amount_cent,
                                expires_at, paid_at, created_at, updated_at)
                            values (
                                :orderId, null, :outTradeNo, :prepayId,
                                :transactionId, :openid, 'PAID', 100,
                                :now, :now, :now, :now)
                            """)
                    .param("orderId", orderId)
                    .param("outTradeNo", "merchant-" + orderId)
                    .param("prepayId", "prepay-" + orderId)
                    .param("transactionId", "paid-transaction-" + orderId)
                    .param("openid", "paid-openid-" + orderId)
                    .param("now", now)
                    .update();
        }
        return orderId;
    }

    private long insertPaidOrder() {
        long orderId = insertOrder(true, true);
        jdbcClient.sql("""
                        update shop_order
                        set status = 'PAID', shipped_at = null
                        where id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        return orderId;
    }

    private long insertElectronicWaybill(long orderId) {
        jdbcClient.sql("""
                        insert into order_electronic_waybill(
                            order_id, attempt_no, idempotency_key, request_digest, provider_order_id,
                            mode, delivery_id, delivery_name, biz_id, service_type, service_name,
                            status, pending_operation, waybill_id, parcel_count, weight_kg,
                            length_cm, width_cm, height_cm, sender_name, sender_mobile,
                            sender_company, sender_province, sender_city, sender_district,
                            sender_detail_address, receiver_name, receiver_phone, receiver_province,
                            receiver_city, receiver_district, receiver_detail_address,
                            payment_order_id, payer_openid, created_by)
                        select
                            :orderId, 1, :key, :digest, :providerOrderId,
                            'SANDBOX', 'SF', '顺丰速运', 'test_biz_id', 1, 'test_service_name',
                            'CREATED', 'NONE', :waybillId, 1, 1.000,
                            20.00, 15.00, 10.00, '寄件人', '13900139000', '沐宝商城',
                            '广东省', '深圳市', '南山区', '测试路1号',
                            o.receiver_name, o.receiver_phone, '广东省', '深圳市', '南山区',
                            '测试路2号', po.id, po.payer_openid, 1
                        from shop_order o
                        join payment_order po on po.order_id = o.id
                        where o.id = :orderId
                        """)
                .param("orderId", orderId)
                .param("key", "confirm-" + orderId)
                .param("digest", "a".repeat(64))
                .param("providerOrderId", "PROVIDER-" + orderId)
                .param("waybillId", "SF" + orderId)
                .update();
        return jdbcClient.sql("select id from order_electronic_waybill where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private void assertRegistration(
            long shipmentId,
            String kind,
            String status,
            String token,
            int attempts
    ) {
        assertThat(jdbcClient.sql("""
                        select registration_kind, status, waybill_token, attempt_count
                        from shipment_waybill_registration
                        where shipment_id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> List.of(
                        rs.getString("registration_kind"),
                        rs.getString("status"),
                        rs.getString("waybill_token"),
                        Integer.toString(rs.getInt("attempt_count"))
                ))
                .single()).containsExactly(kind, status, token, Integer.toString(attempts));
    }

    private boolean awaitRegistrationOrderLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int waiting = jdbcClient.sql("""
                            select count(*)
                            from information_schema.sessions
                            where lower(coalesce(executing_statement, '')) like '%from shop_order%'
                              and lower(coalesce(executing_statement, '')) like '%for update%'
                            """)
                    .query(Integer.class)
                    .single();
            if (waiting > 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for cleanup race test latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cleanup race test was interrupted", ex);
        }
    }

    private String appToken(long userId) {
        return opaqueTokenService.issue(
                TokenKind.APP,
                TokenSession.app(userId, "openid***", Instant.now())
        ).accessToken();
    }

    private String adminToken(List<String> permissions) {
        long adminId = IDS.incrementAndGet();
        long roleId = adminId;
        String username = "RegistrationAdmin" + adminId;
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user(
                            id, username, password_hash, display_name, email, status)
                        values (
                            :id, :username, :passwordHash, 'Registration Admin', :email, 'ENABLED')
                        """)
                .param("id", adminId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("email", username.toLowerCase() + "@shop.local")
                .update();
        jdbcClient.sql("""
                        insert into admin_role(id, code, name, description, enabled)
                        values (:id, :code, 'Registration Role', '', true)
                        """)
                .param("id", roleId)
                .param("code", "R_REGISTRATION_" + roleId)
                .update();
        jdbcClient.sql("insert into admin_user_role(user_id, role_id) values (:userId, :roleId)")
                .param("userId", adminId)
                .param("roleId", roleId)
                .update();
        if (permissions.contains("order:shipping:registration:retry")) {
            jdbcClient.sql("""
                            insert into admin_role_permission(role_id, permission_id)
                            values (:roleId, 8306)
                            """)
                    .param("roleId", roleId)
                    .update();
        }
        long authVersion = jdbcClient.sql("select auth_version from admin_user where id = :id")
                .param("id", adminId)
                .query(Long.class)
                .single();
        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(
                        adminId, username, List.of("R_REGISTRATION_" + roleId), permissions,
                        authVersion, Instant.now()
                )
        ).accessToken();
    }

    private AuthenticatedPrincipal adminPrincipal() {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                1L,
                "admin",
                List.of("R_SUPER"),
                List.of("order:ship", "order:waybill:manage")
        );
    }

    private record ShipmentSeed(long userId, long orderId, long shipmentId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        RecordingRegistrationProvider recordingRegistrationProvider() {
            return new RecordingRegistrationProvider();
        }
    }

    static final class RecordingRegistrationProvider implements WechatWaybillRegistrationProvider {

        private final List<WechatWaybillRegistrationRequest> traceRequests = new CopyOnWriteArrayList<>();
        private final List<WechatWaybillRegistrationRequest> followRequests = new CopyOnWriteArrayList<>();
        private volatile WechatWaybillRegistrationResult nextResult;
        private volatile boolean transactionActive;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        @Override
        public WechatWaybillRegistrationResult trace(WechatWaybillRegistrationRequest request) {
            traceRequests.add(request);
            return result(request);
        }

        @Override
        public WechatWaybillRegistrationResult follow(WechatWaybillRegistrationRequest request) {
            followRequests.add(request);
            return result(request);
        }

        private WechatWaybillRegistrationResult result(WechatWaybillRegistrationRequest request) {
            transactionActive |= TransactionSynchronizationManager.isActualTransactionActive();
            CountDownLatch enteredSnapshot = entered;
            CountDownLatch releaseSnapshot = release;
            if (enteredSnapshot != null && releaseSnapshot != null) {
                enteredSnapshot.countDown();
                try {
                    if (!releaseSnapshot.await(5, TimeUnit.SECONDS)) {
                        return WechatWaybillRegistrationResult.failure(
                                WechatProviderOutcome.UNKNOWN, "TEST_TIMEOUT", "test timeout"
                        );
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return WechatWaybillRegistrationResult.failure(
                            WechatProviderOutcome.UNKNOWN, "TEST_INTERRUPTED", "test interrupted"
                    );
                }
            }
            if (request == null
                    || request.shipmentId() == null
                    || blank(request.openid())
                    || blank(request.receiverPhone())
                    || blank(request.waybillId())
                    || blank(request.deliveryId())
                    || blank(request.transactionId())
                    || blank(request.orderDetailPath())
                    || request.goods() == null
                    || request.goods().isEmpty()
                    || request.goods().stream().anyMatch(
                    goods -> goods == null || blank(goods.goodsName()) || blank(goods.goodsImageUrl())
            )) {
                return WechatWaybillRegistrationResult.failure(
                        WechatProviderOutcome.UNAVAILABLE, "INVALID_REQUEST", "invalid request"
                );
            }
            return nextResult == null
                    ? WechatWaybillRegistrationResult.success("token-" + request.shipmentId())
                    : nextResult;
        }

        void blockNextCall() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        boolean awaitEntered() throws InterruptedException {
            return entered != null && entered.await(5, TimeUnit.SECONDS);
        }

        void releaseBlockedCall() {
            if (release != null) {
                release.countDown();
            }
        }

        void reset() {
            traceRequests.clear();
            followRequests.clear();
            nextResult = null;
            transactionActive = false;
            entered = null;
            release = null;
        }

        private boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
