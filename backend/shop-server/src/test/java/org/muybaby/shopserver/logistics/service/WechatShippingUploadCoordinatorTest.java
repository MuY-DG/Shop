package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingCapabilityState;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingFact;
import org.muybaby.shopserver.logistics.provider.WechatShippingOrderQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingSummary;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "shop.wechat.shipping.delivery.enabled=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatShippingUploadCoordinatorTest {

    private static final AtomicLong IDS = new AtomicLong(920_000L);
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN, 1L, "shipping-admin", List.of("R_SUPER"),
            List.of("order:ship", "order:shipping:retry")
    );

    @Autowired
    private LocalShipmentService localShipmentService;

    @Autowired
    private WechatShippingUploadCoordinator coordinator;

    @Autowired
    private ShippingProperties shippingProperties;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RecordingProvider provider;

    @MockitoSpyBean
    private WechatShippingUploadStateStore stateStore;

    @BeforeEach
    void reset() {
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        insertCarrier("SF", "顺丰速运");
        provider.reset();
        clearInvocations(stateStore);
        shippingProperties.setUploadEnabled(true);
    }

    @ParameterizedTest
    @EnumSource(LogisticsType.class)
    void initialAttemptRebuildsEachModeOnlyFromCommittedRows(LogisticsType type) {
        long orderId = insertPaidOrder(true);
        AdminShipOrderRequest adminRequest = request(type, "持久化商品描述", "绝不能进入微信载荷");
        OrderShipmentResponse local = localShipmentService.create(ADMIN, orderId, adminRequest);

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse completed = localShipmentService.getForAdmin(orderId);
        assertThat(completed.wechatProviderMode()).isEqualTo(WechatProviderMode.REAL);
        assertThat(completed.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(completed.retryCount()).isZero();
        assertThat(completed.uploadTime()).isNotBlank();
        assertThat(OffsetDateTime.parse(completed.uploadTime(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)).isNotNull();
        assertThat(completed.lastAttemptAt()).isNotNull();
        assertThat(completed.wechatUploadedAt()).isNotNull();

        assertThat(provider.uploadRequests).hasSize(1);
        WechatShippingUploadRequest upload = provider.uploadRequests.getFirst();
        assertThat(upload.orderId()).isEqualTo(orderId);
        assertThat(upload.logisticsType()).isEqualTo(type);
        assertThat(upload.deliveryMode().value()).isEqualTo(1);
        assertThat(upload.transactionId()).isEqualTo("wx-" + orderId);
        assertThat(upload.openid()).isEqualTo("openid-" + orderId);
        assertThat(upload.shippingList()).hasSize(1);
        assertThat(upload.shippingList().getFirst().itemDesc()).isEqualTo("持久化商品描述")
                .isNotEqualTo(adminRequest.shipmentNote());
        if (type == LogisticsType.EXPRESS) {
            assertThat(upload.shippingList().getFirst().expressCompany()).isEqualTo("SF");
            assertThat(upload.shippingList().getFirst().trackingNo()).isEqualTo("SF" + orderId);
            assertThat(upload.shippingList().getFirst().receiverContact()).isEqualTo("*******8000");
        } else {
            assertThat(upload.shippingList().getFirst().expressCompany()).isNull();
            assertThat(upload.shippingList().getFirst().trackingNo()).isNull();
            assertThat(upload.shippingList().getFirst().consignorContact()).isNull();
            assertThat(upload.shippingList().getFirst().receiverContact()).isNull();
        }
        assertThat(provider.transactionActiveDuringCapability).isFalse();
        assertThat(provider.transactionActiveDuringUpload).isFalse();
        assertThat(provider.transactionActiveDuringMode).isFalse();
        assertThat(provider.sawCommittedShippedOrder).isTrue();
        assertThat(provider.sawCommittedShipment).isTrue();
    }

    @Test
    void initialAttemptUsesPaidPaymentSnapshotAfterAppUserIsDeleted() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "历史订单商品", null)
        );
        jdbcClient.sql("delete from app_user where id = :userId")
                .param("userId", orderId)
                .update();

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(provider.uploadRequests).singleElement().satisfies(upload -> {
            assertThat(upload.transactionId()).isEqualTo("wx-" + orderId);
            assertThat(upload.openid()).isEqualTo("openid-" + orderId);
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void successfulUploadLogsNoRequestFactsOrSecrets(CapturedOutput output) {
        long orderId = insertPaidOrder(true);
        String itemDesc = "success-private-item";
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN,
                orderId,
                request(
                        LogisticsType.EXPRESS,
                        itemDesc,
                        "Authorization: Bearer success-matrix-token payload={shipping_list:secret}"
                )
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertDiagnosticChannelsDoNotLeak(output, result,
                "success-matrix-token", "openid-" + orderId, "SF" + orderId,
                "*******8000", itemDesc, "shipping_list");
    }

    @Test
    void missingTransactionFailsSafelyWithoutCallingProviderAndKeepsLocalShipped() {
        long orderId = insertPaidOrder(false);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "自提商品", null)
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.localShipmentStatus()).isEqualTo("SHIPPED");
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(result.wechatErrorCode()).isEqualTo("MISSING_TRANSACTION_ID");
        assertThat(result.retryCount()).isZero();
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    void mockModeCannotClaimUploadedOrDispatchUpload() {
        provider.mode = WechatProviderMode.MOCK;
        provider.uploadResult = WechatShippingUploadResult.uploaded();
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.VIRTUAL, "虚拟商品", null)
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatProviderMode()).isEqualTo(WechatProviderMode.MOCK);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(result.wechatErrorCode()).isEqualTo("MOCK_PROVIDER");
        assertThat(result.wechatUploadedAt()).isNull();
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void capabilityUnknownAndExceptionsAreRetryableUnavailableBeforeDispatch(CapturedOutput output) {
        long unknownOrder = insertPaidOrder(true);
        String unknownPayload = sensitiveFailure("capability-result", unknownOrder, "自提");
        OrderShipmentResponse unknownLocal = localShipmentService.create(
                ADMIN, unknownOrder, request(LogisticsType.PICKUP, "自提", null)
        );
        provider.capabilityResult = WechatShippingCapabilityResult.unknown(
                "ACCESS_TOKEN_UNAVAILABLE", unknownPayload
        );

        coordinator.attemptInitial(unknownLocal.shipmentId());

        OrderShipmentResponse unknownResult = localShipmentService.getForAdmin(unknownOrder);
        assertThat(unknownResult.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(unknownResult.wechatErrorCode()).isEqualTo("ACCESS_TOKEN_UNAVAILABLE");
        assertThat(unknownResult.wechatErrorMessage())
                .isEqualTo(WechatShippingErrorSanitizer.GENERIC_MESSAGE);
        assertDiagnosticChannelsDoNotLeak(output, unknownResult,
                "capability-result-token", "openid-" + unknownOrder, "wx-" + unknownOrder,
                "*******8000", "shipping_list", "private-capability-result");
        assertThat(provider.uploadRequests).isEmpty();

        provider.reset();
        long exceptionOrder = insertPaidOrder(true);
        String exceptionMessage = sensitiveFailure("capability-exception", exceptionOrder, "同城");
        OrderShipmentResponse exceptionLocal = localShipmentService.create(
                ADMIN, exceptionOrder, request(LogisticsType.LOCAL_DELIVERY, "同城", null)
        );
        provider.capabilityFailure = new IllegalStateException(exceptionMessage);

        coordinator.attemptInitial(exceptionLocal.shipmentId());

        OrderShipmentResponse exceptionResult = localShipmentService.getForAdmin(exceptionOrder);
        assertThat(exceptionResult.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(exceptionResult.wechatErrorCode()).isEqualTo("CAPABILITY_UNKNOWN");
        assertDiagnosticChannelsDoNotLeak(output, exceptionResult,
                "capability-exception-token", "openid-" + exceptionOrder, "wx-" + exceptionOrder,
                "*******8000", "shipping_list", "private-capability-exception");
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    void contradictoryAvailableCapabilityWithoutManagedTradeDoesNotDispatch() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "contradictory capability", null)
        );
        provider.capabilityResult = new WechatShippingCapabilityResult(
                WechatShippingCapabilityState.AVAILABLE,
                false,
                "TRADE_NOT_MANAGED",
                "WeChat shipping capability is unavailable"
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(result.wechatErrorCode()).isEqualTo("TRADE_NOT_MANAGED");
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void uploadExceptionAfterDispatchBecomesUnknownAndCannotOrdinaryRetry(CapturedOutput output) {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.VIRTUAL, "虚拟", null)
        );
        provider.uploadFailure = new IllegalStateException(
                sensitiveFailure("upload-exception", orderId, "虚拟")
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.wechatErrorCode()).isEqualTo("UPLOAD_RESULT_UNKNOWN");
        assertThat(result.retryCount()).isZero();
        int calls = provider.uploadRequests.size();
        assertThatThrownBy(() -> coordinator.retry(ADMIN, orderId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);
        assertThat(provider.uploadRequests).hasSize(calls);
        assertThat(localShipmentService.getForAdmin(orderId).retryCount()).isZero();
        assertDiagnosticChannelsDoNotLeak(output, result,
                "upload-exception-token", "openid-" + orderId, "wx-" + orderId,
                "*******8000", "shipping_list", "private-upload-exception");
    }

    @ParameterizedTest
    @EnumSource(LogisticsType.class)
    void operatorRetryRebuildsPersistedModeIncrementsOnceAndUsesNewerUploadTime(LogisticsType type) {
        provider.uploadResult = WechatShippingUploadResult.failed("REMOTE_FAILED", "safe failure");
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(type, "retry item", "local-only note")
        );
        coordinator.attemptInitial(local.shipmentId());
        OrderShipmentResponse failed = localShipmentService.getForAdmin(orderId);
        String firstUploadTime = failed.uploadTime();
        assertThat(failed.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(failed.retryCount()).isZero();

        provider.uploadRequests.clear();
        provider.uploadResult = WechatShippingUploadResult.uploaded();
        coordinator.retry(ADMIN, orderId);

        OrderShipmentResponse uploaded = localShipmentService.getForAdmin(orderId);
        assertThat(uploaded.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(uploaded.retryCount()).isEqualTo(1);
        assertThat(OffsetDateTime.parse(uploaded.uploadTime()))
                .isAfter(OffsetDateTime.parse(firstUploadTime));
        assertThat(provider.uploadRequests).singleElement().satisfies(upload -> {
            assertThat(upload.logisticsType()).isEqualTo(type);
            assertThat(upload.shippingList().getFirst().itemDesc()).isEqualTo("retry item");
            if (type == LogisticsType.EXPRESS) {
                assertThat(upload.shippingList().getFirst().expressCompany()).isEqualTo("SF");
                assertThat(upload.shippingList().getFirst().trackingNo()).isEqualTo("SF" + orderId);
            } else {
                assertThat(upload.shippingList().getFirst().expressCompany()).isNull();
                assertThat(upload.shippingList().getFirst().trackingNo()).isNull();
            }
        });
    }

    @Test
    void uploadedAndFreshUploadingRejectRetryWithoutMutationOrProviderCalls() {
        long uploadedOrder = insertPaidOrder(true);
        OrderShipmentResponse uploadedLocal = localShipmentService.create(
                ADMIN, uploadedOrder, request(LogisticsType.PICKUP, "pickup", null)
        );
        jdbcClient.sql("""
                        update order_shipment set wechat_upload_status='UPLOADED', retry_count=4
                        where id=:id
                        """).param("id", uploadedLocal.shipmentId()).update();

        assertThatThrownBy(() -> coordinator.retry(ADMIN, uploadedOrder))
                .isInstanceOf(BusinessException.class);
        assertThat(localShipmentService.getForAdmin(uploadedOrder).retryCount()).isEqualTo(4);
        assertThat(provider.uploadRequests).isEmpty();

        long uploadingOrder = insertPaidOrder(true);
        OrderShipmentResponse uploadingLocal = localShipmentService.create(
                ADMIN, uploadingOrder, request(LogisticsType.PICKUP, "pickup", null)
        );
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status='UPLOADING', retry_count=2, last_attempt_at=:now
                        where id=:id
                        """)
                .param("now", LocalDateTime.now()).param("id", uploadingLocal.shipmentId()).update();

        assertThatThrownBy(() -> coordinator.retry(ADMIN, uploadingOrder))
                .isInstanceOf(BusinessException.class);
        assertThat(localShipmentService.getForAdmin(uploadingOrder).retryCount()).isEqualTo(2);
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    void disabledUploadRejectsSkippedOperatorRetryWithoutMutationOrProviderCalls() {
        shippingProperties.setUploadEnabled(false);
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "disabled", null)
        );

        assertThatThrownBy(() -> coordinator.retry(ADMIN, orderId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);

        OrderShipmentResponse unchanged = localShipmentService.getForAdmin(orderId);
        assertThat(unchanged.shipmentId()).isEqualTo(local.shipmentId());
        assertThat(unchanged.wechatProviderMode()).isEqualTo(WechatProviderMode.DISABLED);
        assertThat(unchanged.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.SKIPPED);
        assertThat(unchanged.retryCount()).isZero();
        assertThat(unchanged.uploadTime()).isNull();
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    void scheduledDeliveryClaimsPendingWithoutOperatorRetryIncrement() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "scheduled pending", null)
        );
        assertThat(local.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.PENDING);

        assertThat(coordinator.deliverDue(10)).isEqualTo(1);

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(result.retryCount()).isZero();
        assertThat(provider.uploadRequests).hasSize(1);
    }

    @Test
    void scheduledDeliveryStopsAfterConfiguredMaximumAttempts() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "bounded retry", null)
        );
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = 'UNAVAILABLE',
                            wechat_upload_attempt_count = 8,
                            wechat_upload_next_action_at = :due
                        where id = :shipmentId
                        """)
                .param("due", LocalDateTime.now().minusMinutes(1))
                .param("shipmentId", local.shipmentId())
                .update();

        assertThat(coordinator.deliverDue(10)).isZero();
        assertThat(provider.uploadRequests).isEmpty();
        assertThat(localShipmentService.getForAdmin(orderId).wechatUploadStatus())
                .isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
    }

    @Test
    void retryUploadTimeIsStrictlyNewerEvenWhenPersistedTimeIsAheadOfWallClock() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "clock rollback", null)
        );
        String futureUploadTime = "2099-01-01T00:00:00Z";
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status='FAILED', upload_time=:uploadTime
                        where id=:id
                        """)
                .param("uploadTime", futureUploadTime)
                .param("id", local.shipmentId())
                .update();

        coordinator.retry(ADMIN, orderId);

        assertThat(OffsetDateTime.parse(localShipmentService.getForAdmin(orderId).uploadTime()))
                .isAfter(OffsetDateTime.parse(futureUploadTime));
    }

    @Test
    void legacyExpressWithoutCarrierCodeRejectsBeforeClaimWithoutChangingEvidence() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.EXPRESS, "legacy", null)
        );
        LocalDateTime oldAttempt = LocalDateTime.of(2026, 7, 10, 8, 0);
        jdbcClient.sql("""
                        update order_shipment
                        set express_company_code=null, wechat_upload_status='FAILED', retry_count=3,
                            upload_time='2026-07-10T15:00:00Z', last_attempt_at=:attempt
                        where id=:id
                        """)
                .param("attempt", oldAttempt).param("id", local.shipmentId()).update();

        assertThatThrownBy(() -> coordinator.retry(ADMIN, orderId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);

        OrderShipmentResponse unchanged = localShipmentService.getForAdmin(orderId);
        assertThat(unchanged.expressCompanyCode()).isNull();
        assertThat(unchanged.expressCompanyName()).isEqualTo("顺丰速运");
        assertThat(unchanged.trackingNo()).isEqualTo("SF" + orderId);
        assertThat(unchanged.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(unchanged.retryCount()).isEqualTo(3);
        assertThat(unchanged.uploadTime()).isEqualTo("2026-07-10T15:00:00Z");
        assertThat(unchanged.lastAttemptAt()).isEqualTo(oldAttempt);
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "logistics_type = 999",
            "delivery_mode = 999",
            "item_desc = ' '",
            "item_desc = repeat('x', 121)"
    })
    void invalidPersistedRetryFactsRejectBeforeClaimWithoutChangingEvidence(String corruption) {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "valid retry item", null)
        );
        LocalDateTime oldAttempt = LocalDateTime.of(2026, 7, 10, 8, 30);
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status='FAILED', retry_count=3,
                            upload_time='2026-07-10T15:30:00Z', last_attempt_at=:attempt,
                            wechat_error_code='REMOTE_FAILED', wechat_error_message='safe failure',
                            %s
                        where id=:id
                        """.formatted(corruption))
                .param("attempt", oldAttempt)
                .param("id", local.shipmentId())
                .update();
        var before = retryEvidence(local.shipmentId());

        assertThatThrownBy(() -> coordinator.retry(ADMIN, orderId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);

        assertThat(retryEvidence(local.shipmentId())).isEqualTo(before);
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
    }

    @Test
    void concurrentRetryAllowsExactlyOneClaimProviderCallAndIncrement() throws Exception {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "concurrent", null)
        );
        jdbcClient.sql("""
                        update order_shipment set wechat_upload_status='FAILED', retry_count=0 where id=:id
                        """).param("id", local.shipmentId()).update();
        provider.blockUpload();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<Throwable> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return catchThrowable(() -> coordinator.retry(ADMIN, orderId));
            });
            Future<Throwable> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return catchThrowable(() -> coordinator.retry(ADMIN, orderId));
            });
            assertThat(provider.uploadEntered.await(10, TimeUnit.SECONDS)).isTrue();
            provider.releaseUpload.countDown();
            Throwable firstResult = first.get(10, TimeUnit.SECONDS);
            Throwable secondResult = second.get(10, TimeUnit.SECONDS);
            assertThat(Stream.of(firstResult, secondResult).filter(item -> item == null).count())
                    .isEqualTo(1);
            assertThat(Stream.of(firstResult, secondResult).filter(BusinessException.class::isInstance).count())
                    .isEqualTo(1);
        } finally {
            provider.releaseUpload.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(provider.uploadRequests).hasSize(1);
        assertThat(localShipmentService.getForAdmin(orderId).retryCount()).isEqualTo(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void terminalWriteFailureFallsBackToUnknownInFreshTransaction(CapturedOutput output) {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "fallback", null)
        );
        doThrow(new IllegalStateException(sensitiveFailure("terminal-fallback", orderId, "fallback")))
                .when(stateStore)
                .writeTerminal(any(), any(), any(), any(), any(), any(), any());

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.wechatErrorCode()).isEqualTo("ATTEMPT_OUTCOME_UNKNOWN");
        assertThat(result.lastAttemptAt()).isNotNull();
        assertDiagnosticChannelsDoNotLeak(output, result,
                "terminal-fallback-token", "openid-" + orderId, "wx-" + orderId,
                "*******8000", "shipping_list", "private-terminal-fallback");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reconstructionFailureLogsNoExceptionFactsOrSecrets(CapturedOutput output) {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.EXPRESS, "reconstruction", null)
        );
        doThrow(new IllegalStateException(
                sensitiveFailure("reconstruction", orderId, "reconstruction")
        )).when(stateStore).prepareAttempt(any(), any());

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(result.wechatErrorCode()).isEqualTo("PAYLOAD_RECONSTRUCTION_FAILED");
        assertThat(provider.capabilityCalls).isZero();
        assertThat(provider.uploadRequests).isEmpty();
        assertDiagnosticChannelsDoNotLeak(output, result,
                "reconstruction-token", "openid-" + orderId, "wx-" + orderId,
                "SF" + orderId, "*******8000", "shipping_list", "private-reconstruction");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void lateUploadedResultCannotOverwriteOrClaimSuccessAfterRecoveryWins(CapturedOutput output) throws Exception {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "recovery race", null)
        );
        provider.blockUpload();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> attempt = executor.submit(() -> coordinator.attemptInitial(local.shipmentId()));
            assertThat(provider.uploadEntered.await(10, TimeUnit.SECONDS)).isTrue();
            jdbcClient.sql("""
                            update order_shipment
                            set wechat_upload_claimed_at=:old,
                                last_attempt_at=:old
                            where id=:id
                            """)
                    .param("old", LocalDateTime.now().minusMinutes(11))
                    .param("id", local.shipmentId())
                    .update();
            assertThat(stateStore.reconcileStaleByOrder(orderId, LocalDateTime.now())).isTrue();
            provider.releaseUpload.countDown();
            attempt.get(10, TimeUnit.SECONDS);
        } finally {
            provider.releaseUpload.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.wechatErrorCode()).isEqualTo("ATTEMPT_OUTCOME_UNKNOWN");
        assertThat(result.wechatUploadedAt()).isNull();
        assertThat(output.getAll()).doesNotContain(
                "shipmentId=" + local.shipmentId() + ", mode=REAL, status=UPLOADED"
        );
    }

    @Test
    void staleUploadClaimIsTokenFencedFromOverwritingRecovery() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.PICKUP, "token fence", null)
        );
        WechatShippingUploadStateStore.UploadClaim staleClaim = stateStore.claimInitial(
                local.shipmentId(), LocalDateTime.now()
        ).orElseThrow();
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_claimed_at = :staleAt
                        where id = :shipmentId
                        """)
                .param("staleAt", LocalDateTime.now().minusMinutes(11))
                .param("shipmentId", local.shipmentId())
                .update();

        assertThat(stateStore.reconcileStaleByShipment(
                local.shipmentId(), LocalDateTime.now()
        )).isTrue();
        assertThat(stateStore.writeTerminal(
                staleClaim,
                WechatProviderMode.REAL,
                WechatShippingUploadStatus.UPLOADED,
                "",
                "",
                LocalDateTime.now(),
                LocalDateTime.now()
        )).isFalse();

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.wechatUploadedAt()).isNull();
    }

    @Test
    void unknownReconciliationMarksUploadedOnlyWhenRemoteShippingFactsMatch() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = createUnknownShipment(
                orderId, LogisticsType.EXPRESS, "matching unknown"
        );
        provider.orderQueryResult = WechatShippingOrderQueryResult.uploaded(
                "wx-" + orderId,
                2,
                new WechatShippingSummary(
                        LogisticsType.EXPRESS,
                        DeliveryMode.UNIFIED,
                        true,
                        List.of(new WechatShippingFact("SF" + orderId, "SF"))
                )
        );

        coordinator.reconcile(ADMIN, orderId);

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(result.wechatUploadedAt()).isNotNull();
        assertThat(provider.uploadRequests).hasSize(1);
        assertThat(provider.orderQueryTransactionIds).containsExactly("wx-" + orderId);
        assertThat(provider.transactionActiveDuringOrderQuery).isFalse();
        assertThat(result.shipmentId()).isEqualTo(local.shipmentId());
    }

    @Test
    void unknownReconciliationKeepsMismatchUnknownAndNeverBlindlyReuploads() {
        long orderId = insertPaidOrder(true);
        createUnknownShipment(orderId, LogisticsType.EXPRESS, "mismatched unknown");
        provider.orderQueryResult = WechatShippingOrderQueryResult.uploaded(
                "wx-" + orderId,
                2,
                new WechatShippingSummary(
                        LogisticsType.EXPRESS,
                        DeliveryMode.UNIFIED,
                        true,
                        List.of(new WechatShippingFact("different-tracking", "SF"))
                )
        );

        coordinator.reconcile(ADMIN, orderId);

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.wechatErrorCode()).isEqualTo("REMOTE_SHIPPING_MISMATCH");
        assertThat(provider.uploadRequests).hasSize(1);
        assertThat(provider.orderQueryTransactionIds).containsExactly("wx-" + orderId);
    }

    @Test
    void definitiveNotUploadedRequiresSpacedObservationsBeforePending() {
        long orderId = insertPaidOrder(true);
        OrderShipmentResponse local = createUnknownShipment(
                orderId, LogisticsType.PICKUP, "not uploaded unknown"
        );
        provider.orderQueryResult = WechatShippingOrderQueryResult.notUploaded(
                "wx-" + orderId, 1
        );

        coordinator.reconcile(ADMIN, orderId);
        assertUnknownObservation(local.shipmentId(), 1);

        coordinator.reconcile(ADMIN, orderId);
        assertUnknownObservation(local.shipmentId(), 1);

        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_last_reconciled_at = :old,
                            wechat_upload_next_action_at = :old
                        where id = :shipmentId
                        """)
                .param("old", LocalDateTime.now().minusMinutes(2))
                .param("shipmentId", local.shipmentId())
                .update();

        assertThat(coordinator.reconcileDueUnknown(10)).isEqualTo(1);

        var row = uploadDeliveryEvidence(local.shipmentId());
        assertThat(row.get("wechat_upload_status")).isEqualTo("PENDING");
        assertThat(row.get("wechat_upload_not_uploaded_observations")).isEqualTo(2);
        assertThat(provider.uploadRequests).hasSize(1);
        assertThat(provider.orderQueryTransactionIds).hasSize(3);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void echoedProviderSecretsAreSanitizedFromDatabaseAndLogs(CapturedOutput output) {
        long orderId = insertPaidOrder(true);
        String openid = "openid-" + orderId;
        String tracking = "SF" + orderId;
        String phone = "*******8000";
        String token = "synthetic-access-token-never-store";
        String itemDesc = "echoed-private-item";
        provider.uploadResult = WechatShippingUploadResult.failed(
                "ACCESS_TOKEN_UNAVAILABLE",
                "Authorization: Bearer " + token + " payload={\"shipping_list\":[{"
                        + "\"transaction_id\":\"wx-" + orderId + "\","
                        + "\"openid\":\"" + openid + "\","
                        + "\"receiver_contact\":\"" + phone + "\","
                        + "\"tracking_no\":\"" + tracking + "\","
                        + "\"item_desc\":\"" + itemDesc + "\"}]}"
        );
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(LogisticsType.EXPRESS, itemDesc, null)
        );

        coordinator.attemptInitial(local.shipmentId());

        OrderShipmentResponse result = localShipmentService.getForAdmin(orderId);
        assertThat(result.wechatErrorCode()).isEqualTo("ACCESS_TOKEN_UNAVAILABLE");
        assertThat(result.wechatErrorMessage()).isEqualTo(WechatShippingErrorSanitizer.GENERIC_MESSAGE);
        assertDiagnosticChannelsDoNotLeak(output, result,
                token, openid, "wx-" + orderId, phone, tracking, itemDesc,
                "Authorization: Bearer", "shipping_list", "transaction_id");
    }

    private OrderShipmentResponse createUnknownShipment(
            long orderId,
            LogisticsType logisticsType,
            String itemDesc
    ) {
        provider.uploadFailure = new IllegalStateException("ambiguous dispatch");
        OrderShipmentResponse local = localShipmentService.create(
                ADMIN, orderId, request(logisticsType, itemDesc, null)
        );
        coordinator.attemptInitial(local.shipmentId());
        provider.uploadFailure = null;
        assertThat(localShipmentService.getForAdmin(orderId).wechatUploadStatus())
                .isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        return local;
    }

    private void assertUnknownObservation(long shipmentId, int expectedObservations) {
        var row = uploadDeliveryEvidence(shipmentId);
        assertThat(row.get("wechat_upload_status")).isEqualTo("UNKNOWN");
        assertThat(row.get("wechat_upload_not_uploaded_observations"))
                .isEqualTo(expectedObservations);
        assertThat(row.get("wechat_upload_claim_token")).isNull();
    }

    private java.util.Map<String, Object> uploadDeliveryEvidence(long shipmentId) {
        return jdbcClient.sql("""
                        select wechat_upload_status,
                               wechat_upload_claim_token,
                               wechat_upload_not_uploaded_observations,
                               wechat_upload_last_reconciled_at,
                               wechat_upload_next_action_at
                        from order_shipment
                        where id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query()
                .singleRow();
    }

    private AdminShipOrderRequest request(LogisticsType type, String itemDesc, String note) {
        if (type == LogisticsType.EXPRESS) {
            return new AdminShipOrderRequest(type, itemDesc, "SF", "SF" + IDS.get(), null, note);
        }
        return new AdminShipOrderRequest(type, itemDesc, null, null, null, note);
    }

    private java.util.Map<String, Object> retryEvidence(long shipmentId) {
        return jdbcClient.sql("""
                        select wechat_upload_status, retry_count, upload_time, last_attempt_at,
                               wechat_error_code, wechat_error_message
                        from order_shipment
                        where id=:id
                        """)
                .param("id", shipmentId)
                .query()
                .singleRow();
    }

    private String sensitiveFailure(String prefix, long orderId, String itemDesc) {
        return "Authorization: Bearer " + prefix + "-token"
                + " openid=openid-" + orderId
                + " contact=*******8000"
                + " tracking=SF" + orderId
                + " payload={\"shipping_list\":[{\"transaction_id\":\"wx-" + orderId + "\","
                + "\"item_desc\":\"private-" + prefix + "\",\"source_item\":\"" + itemDesc + "\"}]}";
    }

    private void assertDiagnosticChannelsDoNotLeak(
            CapturedOutput output,
            OrderShipmentResponse response,
            String... secrets
    ) {
        String dtoDiagnostics = String.valueOf(response.wechatErrorCode())
                + " " + String.valueOf(response.wechatErrorMessage());
        var databaseDiagnostics = jdbcClient.sql("""
                        select wechat_error_code, wechat_error_message
                        from order_shipment
                        where id=:id
                        """)
                .param("id", response.shipmentId())
                .query()
                .singleRow()
                .toString();
        assertThat(output.getAll()).doesNotContain(secrets);
        assertThat(dtoDiagnostics).doesNotContain(secrets);
        assertThat(databaseDiagnostics).doesNotContain(secrets);
    }

    private long insertPaidOrder(boolean withTransaction) {
        long id = IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 10, 0);
        jdbcClient.sql("insert into app_user(id, openid, status) values (:id, :openid, 'ENABLED')")
                .param("id", id).param("openid", "openid-" + id).update();
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
                .param("id", id).param("orderNo", "UPLOAD" + id).param("key", "upload-" + id)
                .param("transactionId", withTransaction ? "wx-" + id : "")
                .param("outTradeNo", "mch-" + id).param("now", now).update();
        jdbcClient.sql("""
                        insert into payment_order(
                            order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                            payer_openid, status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values (
                            :id, null, :outTradeNo, :prepayId, :transactionId,
                            :openid, 'PAID', 100, :now, :now, :now, :now)
                        """)
                .param("id", id).param("outTradeNo", "mch-" + id).param("prepayId", "prepay-" + id)
                .param("transactionId", withTransaction ? "wx-" + id : "")
                .param("openid", "openid-" + id).param("now", now).update();
        return id;
    }

    private void insertCarrier(String id, String name) {
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values (:id, :name, true, :now)
                        """)
                .param("id", id).param("name", name)
                .param("now", LocalDateTime.of(2026, 7, 10, 9, 0)).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {

        @Bean
        @Primary
        RecordingProvider recordingProvider(JdbcClient jdbcClient) {
            return new RecordingProvider(jdbcClient);
        }
    }

    static class RecordingProvider implements WechatShippingProvider {

        private final JdbcClient jdbcClient;
        private final List<WechatShippingUploadRequest> uploadRequests = new CopyOnWriteArrayList<>();
        private final List<String> orderQueryTransactionIds = new CopyOnWriteArrayList<>();
        private WechatProviderMode mode = WechatProviderMode.REAL;
        private WechatShippingCapabilityResult capabilityResult = WechatShippingCapabilityResult.available();
        private WechatShippingUploadResult uploadResult = WechatShippingUploadResult.uploaded();
        private RuntimeException capabilityFailure;
        private RuntimeException uploadFailure;
        private WechatShippingOrderQueryResult orderQueryResult =
                WechatShippingOrderQueryResult.unknown(
                        "QUERY_NOT_CONFIGURED", "Shipping query is not configured"
                );
        private int capabilityCalls;
        private boolean transactionActiveDuringCapability;
        private boolean transactionActiveDuringUpload;
        private boolean transactionActiveDuringMode;
        private boolean transactionActiveDuringOrderQuery;
        private boolean sawCommittedShippedOrder;
        private boolean sawCommittedShipment;
        private CountDownLatch uploadEntered = new CountDownLatch(0);
        private CountDownLatch releaseUpload = new CountDownLatch(0);

        RecordingProvider(JdbcClient jdbcClient) {
            this.jdbcClient = jdbcClient;
        }

        void reset() {
            uploadRequests.clear();
            orderQueryTransactionIds.clear();
            mode = WechatProviderMode.REAL;
            capabilityResult = WechatShippingCapabilityResult.available();
            uploadResult = WechatShippingUploadResult.uploaded();
            capabilityFailure = null;
            uploadFailure = null;
            orderQueryResult = WechatShippingOrderQueryResult.unknown(
                    "QUERY_NOT_CONFIGURED", "Shipping query is not configured"
            );
            capabilityCalls = 0;
            transactionActiveDuringCapability = false;
            transactionActiveDuringUpload = false;
            transactionActiveDuringMode = false;
            transactionActiveDuringOrderQuery = false;
            sawCommittedShippedOrder = false;
            sawCommittedShipment = false;
            uploadEntered = new CountDownLatch(0);
            releaseUpload = new CountDownLatch(0);
        }

        void blockUpload() {
            uploadEntered = new CountDownLatch(1);
            releaseUpload = new CountDownLatch(1);
        }

        @Override
        public WechatProviderMode mode() {
            transactionActiveDuringMode |= TransactionSynchronizationManager.isActualTransactionActive();
            return mode;
        }

        @Override
        public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
            transactionActiveDuringUpload = TransactionSynchronizationManager.isActualTransactionActive();
            uploadRequests.add(request);
            sawCommittedShippedOrder = "SHIPPED".equals(jdbcClient.sql(
                            "select status from shop_order where id=:id")
                    .param("id", request.orderId()).query(String.class).single());
            sawCommittedShipment = jdbcClient.sql(
                            "select count(*) from order_shipment where order_id=:id")
                    .param("id", request.orderId()).query(Integer.class).single() == 1;
            uploadEntered.countDown();
            try {
                if (!releaseUpload.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("upload test barrier timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("upload test interrupted");
            }
            if (uploadFailure != null) {
                throw uploadFailure;
            }
            return uploadResult;
        }

        @Override
        public WechatShippingCapabilityResult queryCapability() {
            transactionActiveDuringCapability = TransactionSynchronizationManager.isActualTransactionActive();
            capabilityCalls++;
            if (capabilityFailure != null) {
                throw capabilityFailure;
            }
            return capabilityResult;
        }

        @Override
        public WechatShippingOrderQueryResult queryShippingOrder(String transactionId) {
            transactionActiveDuringOrderQuery =
                    TransactionSynchronizationManager.isActualTransactionActive();
            orderQueryTransactionIds.add(transactionId);
            return orderQueryResult;
        }

        @Override
        public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
            return List.of();
        }
    }
}
