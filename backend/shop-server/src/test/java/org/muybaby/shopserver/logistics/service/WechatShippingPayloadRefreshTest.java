package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingDeliveryProperties;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatShippingPayloadRefreshTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private WechatShippingUploadStateStore stateStore;
    @Autowired
    private WechatShippingDeliveryProperties properties;

    private long orderId;
    private long shipmentId;
    private LocalDateTime now;

    @BeforeEach
    void insertPartialShipment() {
        orderId = System.nanoTime();
        shipmentId = orderId + 1;
        now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, idempotency_key, checkout_request_digest)
                        values (:id, :number, 1, 'PARTIALLY_SHIPPED', :number,
                                'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
                        """)
                .param("id", orderId).param("number", "REFRESH" + orderId).update();
        jdbc.sql("""
                        insert into order_shipment
                            (id, order_id, status, logistics_type, delivery_mode, item_desc, final_shipment,
                             wechat_provider_mode, wechat_upload_status, shipped_at)
                        values (:id, :orderId, 'SHIPPED', 4, 2, '待完成包裹', false, 'REAL', 'PENDING', :now)
                        """)
                .param("id", shipmentId).param("orderId", orderId).param("now", now).update();
    }

    @Test
    void skippedShipmentKeepsOperatorRetrySemanticsWhenFinalFlagChanges() {
        jdbc.sql("""
                        update order_shipment
                        set wechat_upload_status = 'SKIPPED', wechat_provider_mode = 'DISABLED'
                        where id = :id
                        """).param("id", shipmentId).update();

        requestRefresh();

        assertThat(row()).containsEntry("final_shipment", true)
                .containsEntry("wechat_upload_status", "SKIPPED")
                .containsEntry("wechat_upload_refresh_pending", false);
        assertThat(stateStore.claimScheduled(shipmentId, now)).isEmpty();
        assertThat(stateStore.claimInitial(shipmentId, now)).isEmpty();
        var claim = stateStore.claimOperatorRetry(orderId, shipmentId, true, now);
        assertThat(stateStore.prepareAttempt(claim, WechatProviderMode.REAL).allDelivered()).isTrue();
        assertThat(complete(claim, WechatShippingUploadStatus.UPLOADED)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED"})
    void uploadedPayloadGetsASeparateDeliveryBudgetAndNewFinalPayload(String orderStatus) {
        setOrderStatus(orderStatus);
        jdbc.sql("""
                        update order_shipment set wechat_upload_status = 'UPLOADED',
                            wechat_upload_attempt_count = :maxAttempts, wechat_uploaded_at = :now
                        where id = :id
                        """)
                .param("maxAttempts", properties.maxAttempts()).param("now", now)
                .param("id", shipmentId).update();

        requestRefresh();

        assertQueued();
        assertThat(stateStore.findDueUploadShipmentIds(now, 50)).contains(shipmentId);
        var claim = stateStore.claimScheduled(shipmentId, now).orElseThrow();
        assertThat(claim.attemptCount()).isEqualTo(1);
        assertThat(stateStore.prepareAttempt(claim, WechatProviderMode.REAL).allDelivered()).isTrue();
        assertThat(complete(claim, WechatShippingUploadStatus.UPLOADED)).isTrue();
        stateStore.requestFinalShipmentRefresh(orderId, shipmentId, now.plusSeconds(1));
        assertThat(row()).containsEntry("wechat_upload_status", "UPLOADED");
    }

    @ParameterizedTest
    @EnumSource(value = WechatShippingUploadStatus.class,
            names = {"UPLOADED", "UNKNOWN", "FAILED", "UNAVAILABLE"})
    void oldAttemptCannotAcknowledgeTheFinalPayload(WechatShippingUploadStatus oldResult) {
        var oldClaim = stateStore.claimInitial(shipmentId, now).orElseThrow();
        assertThat(stateStore.prepareAttempt(oldClaim, WechatProviderMode.REAL).allDelivered()).isFalse();

        requestRefresh();

        assertThat(row())
                .containsEntry("wechat_upload_status", "UPLOADING")
                .containsEntry("wechat_upload_refresh_pending", true)
                .containsEntry("wechat_upload_claim_token", oldClaim.claimToken());
        assertThat(stateStore.claimScheduled(shipmentId, now)).isEmpty();
        assertThat(complete(oldClaim, oldResult)).isFalse();
        assertQueued();
        var newClaim = stateStore.claimScheduled(shipmentId, now).orElseThrow();
        assertThat(newClaim.claimToken()).isNotEqualTo(oldClaim.claimToken());
        assertThat(stateStore.prepareAttempt(newClaim, WechatProviderMode.REAL).allDelivered()).isTrue();
        assertThat(complete(oldClaim, WechatShippingUploadStatus.UPLOADED)).isFalse();
        assertThat(row()).containsEntry("wechat_upload_claim_token", newClaim.claimToken());
        assertThat(complete(newClaim, WechatShippingUploadStatus.UPLOADED)).isTrue();
    }

    @Test
    void refreshBeforePayloadPreparationDoesNotRequireASecondDelivery() {
        var claim = stateStore.claimInitial(shipmentId, now).orElseThrow();
        requestRefresh();

        assertThat(stateStore.prepareAttempt(claim, WechatProviderMode.REAL).allDelivered()).isTrue();
        assertThat(complete(claim, WechatShippingUploadStatus.UPLOADED)).isTrue();
        assertThat(row()).containsEntry("wechat_upload_refresh_pending", false)
                .containsEntry("wechat_upload_status", "UPLOADED");
    }

    @Test
    void unknownClaimCannotConfirmThePreviousPayloadAfterFinalization() {
        var upload = stateStore.claimInitial(shipmentId, now).orElseThrow();
        stateStore.prepareAttempt(upload, WechatProviderMode.REAL);
        assertThat(complete(upload, WechatShippingUploadStatus.UNKNOWN)).isTrue();
        var unknown = stateStore.claimUnknownByShipment(shipmentId, true, now).orElseThrow();
        assertThat(unknown.allDelivered()).isFalse();

        requestRefresh();

        assertThat(stateStore.markReconciledUploaded(unknown, now)).isFalse();
        assertQueued();
        assertThat(stateStore.markReconciledUploaded(unknown, now)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED"})
    void staleUploadWithChangedPayloadRecoversAsPendingAndRejectsTheOldClaim(String orderStatus) {
        setOrderStatus(orderStatus);
        var claim = stateStore.claimInitial(shipmentId, now).orElseThrow();
        stateStore.prepareAttempt(claim, WechatProviderMode.REAL);
        requestRefresh();
        LocalDateTime later = now.plus(properties.claimTimeout()).plusSeconds(1);

        assertThat(stateStore.reconcileStaleByShipment(shipmentId, later)).isTrue();

        assertQueued();
        assertThat(complete(claim, WechatShippingUploadStatus.UPLOADED)).isFalse();
        assertThat(stateStore.claimScheduled(shipmentId, later)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED"})
    void expiredUnknownClaimWithChangedPayloadQueuesTheNewPayload(String orderStatus) {
        setOrderStatus(orderStatus);
        var upload = stateStore.claimInitial(shipmentId, now).orElseThrow();
        stateStore.prepareAttempt(upload, WechatProviderMode.REAL);
        complete(upload, WechatShippingUploadStatus.UNKNOWN);
        assertThat(stateStore.findDueUnknownShipmentIds(now.plus(properties.unknownRecheckInterval()), 50))
                .contains(shipmentId);
        var oldClaim = stateStore.claimUnknownByShipment(shipmentId, true, now).orElseThrow();
        requestRefresh();

        assertThat(stateStore.claimUnknownByShipment(
                shipmentId, true, now.plus(properties.claimTimeout()).plusSeconds(1))).isEmpty();

        assertQueued();
        assertThat(stateStore.recordDefinitiveNotUploaded(oldClaim, now)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPLOADING", "UNKNOWN"})
    void olderPackageInFlightRefreshesUsingTheCompletedOrderStatus(String inFlightStatus) {
        var oldUpload = stateStore.claimInitial(shipmentId, now).orElseThrow();
        assertThat(stateStore.prepareAttempt(oldUpload, WechatProviderMode.REAL).allDelivered()).isFalse();
        WechatShippingUploadStateStore.UnknownClaim oldUnknown = null;
        if ("UNKNOWN".equals(inFlightStatus)) {
            complete(oldUpload, WechatShippingUploadStatus.UNKNOWN);
            oldUnknown = stateStore.claimUnknownByShipment(shipmentId, true, now).orElseThrow();
            assertThat(oldUnknown.allDelivered()).isFalse();
        }
        long lastShipmentId = insertAnotherShipment(2, "UPLOADED");

        stateStore.requestFinalShipmentRefresh(orderId, lastShipmentId, now);
        setOrderStatus("SHIPPED");

        assertThat(row()).containsEntry("final_shipment", false)
                .containsEntry("wechat_upload_refresh_pending", true)
                .containsEntry("wechat_upload_status", inFlightStatus);
        if (oldUnknown == null) {
            assertThat(complete(oldUpload, WechatShippingUploadStatus.UPLOADED)).isFalse();
        } else {
            assertThat(stateStore.markReconciledUploaded(oldUnknown, now)).isFalse();
        }
        assertThat(row()).containsEntry("wechat_upload_status", "PENDING")
                .containsEntry("final_shipment", false);
        var current = stateStore.claimScheduled(shipmentId, now).orElseThrow();
        assertThat(stateStore.prepareAttempt(current, WechatProviderMode.REAL).allDelivered()).isTrue();
        assertThat(complete(current, WechatShippingUploadStatus.UPLOADED)).isTrue();
        assertThat(row()).containsEntry("final_shipment", false)
                .containsEntry("wechat_upload_status", "UPLOADED");
        assertThat(jdbc.sql("select final_shipment from order_shipment where id = :id")
                .param("id", lastShipmentId).query(Boolean.class).single()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "COMPLETED"})
    void olderUnknownPackageReconcilesAgainstTheWholeOrdersCompletion(String orderStatus) {
        var upload = stateStore.claimInitial(shipmentId, now).orElseThrow();
        assertThat(stateStore.prepareAttempt(upload, WechatProviderMode.REAL).allDelivered()).isFalse();
        complete(upload, WechatShippingUploadStatus.UNKNOWN);
        long lastShipmentId = insertAnotherShipment(2, "UPLOADED");
        stateStore.requestFinalShipmentRefresh(orderId, lastShipmentId, now);
        setOrderStatus(orderStatus);

        var unknown = stateStore.claimUnknownByShipment(shipmentId, true, now).orElseThrow();

        assertThat(unknown.allDelivered()).isTrue();
        assertThat(stateStore.markReconciledUploaded(unknown, now)).isTrue();
        assertThat(row()).containsEntry("wechat_upload_status", "UPLOADED")
                .containsEntry("final_shipment", false);
    }

    @Test
    void finalizingAnotherPackageDoesNotRequeueAnAlreadyStableUpload() {
        jdbc.sql("update order_shipment set wechat_upload_status = 'UPLOADED' where id = :id")
                .param("id", shipmentId).update();
        long lastShipmentId = insertAnotherShipment(2, "UPLOADED");

        stateStore.requestFinalShipmentRefresh(orderId, lastShipmentId, now);
        setOrderStatus("SHIPPED");

        assertThat(row()).containsEntry("wechat_upload_status", "UPLOADED")
                .containsEntry("wechat_upload_refresh_pending", false)
                .containsEntry("final_shipment", false);
        assertThat(stateStore.claimScheduled(shipmentId, now)).isEmpty();
    }

    private long insertAnotherShipment(int packageNo, String status) {
        long id = shipmentId + packageNo;
        jdbc.sql("""
                        insert into order_shipment
                            (id, order_id, status, package_no, logistics_type, delivery_mode, item_desc,
                             final_shipment, wechat_provider_mode, wechat_upload_status, shipped_at)
                        values (:id, :orderId, 'SHIPPED', :packageNo, 4, 2, '后续包裹', false, 'REAL', :status, :now)
                        """)
                .param("id", id).param("orderId", orderId).param("packageNo", packageNo)
                .param("status", status).param("now", now).update();
        return id;
    }

    private void requestRefresh() {
        stateStore.requestFinalShipmentRefresh(orderId, shipmentId, now);
        jdbc.sql("update shop_order set status = 'SHIPPED' where id = :id and status = 'PARTIALLY_SHIPPED'")
                .param("id", orderId).update();
    }

    private boolean complete(WechatShippingUploadStateStore.UploadClaim claim, WechatShippingUploadStatus status) {
        return stateStore.writeTerminal(claim, WechatProviderMode.REAL, status, "", "", now,
                status == WechatShippingUploadStatus.UPLOADED ? now : null);
    }

    private void setOrderStatus(String status) {
        jdbc.sql("update shop_order set status = :status where id = :id")
                .param("status", status).param("id", orderId).update();
    }

    private void assertQueued() {
        assertThat(row()).containsEntry("final_shipment", true)
                .containsEntry("wechat_upload_status", "PENDING")
                .containsEntry("wechat_upload_refresh_pending", false)
                .containsEntry("wechat_upload_claim_token", null)
                .containsEntry("wechat_uploaded_at", null)
                .containsEntry("wechat_upload_attempt_count", 0);
    }

    private Map<String, Object> row() {
        return jdbc.sql("""
                        select final_shipment, wechat_upload_status, wechat_upload_refresh_pending,
                               wechat_upload_claim_token, wechat_uploaded_at, wechat_upload_attempt_count
                        from order_shipment where id = :id
                        """)
                .param("id", shipmentId).query().singleRow();
    }
}
