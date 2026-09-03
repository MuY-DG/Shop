package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.WechatShippingDeliveryProperties;
import org.muybaby.shopserver.logistics.provider.MockWechatShippingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatShippingUploadRecoveryTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private WechatShippingUploadStateStore stateStore;

    @Autowired
    private WechatShippingUploadRecovery recovery;

    @Autowired
    private WechatShippingDeliveryProperties deliveryProperties;

    @Autowired
    private MockWechatShippingProvider provider;

    @BeforeEach
    void clearShipments() {
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from app_user").update();
        provider.reset();
    }

    @Test
    void singlePreflightReconcilesOnlyClaimsOlderThanTenMinutesWithoutProviderCalls() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 12, 0);
        long stale = insertUploadingShipment(1_010_001L, now.minusMinutes(10).minusSeconds(1));
        long exactBoundary = insertUploadingShipment(1_010_002L, now.minusMinutes(10));
        long fresh = insertUploadingShipment(1_010_003L, now.minusMinutes(9));

        assertThat(stateStore.reconcileStaleByOrder(1_010_001L, now)).isTrue();
        assertThat(stateStore.reconcileStaleByOrder(1_010_002L, now)).isFalse();
        assertThat(stateStore.reconcileStaleByOrder(1_010_003L, now)).isFalse();

        assertUnknown(stale);
        assertUploading(exactBoundary);
        assertUploading(fresh);
        assertThat(provider.uploadRequests()).isEmpty();
    }

    @Test
    void startupRecoveryProcessesAtMostConfiguredOldestBatchInStableOrder() {
        LocalDateTime oldest = LocalDateTime.of(2020, 1, 1, 0, 0);
        long lastShipmentId = 0;
        int batchSize = deliveryProperties.batchSize();
        for (int index = 0; index < batchSize + 1; index++) {
            lastShipmentId = insertUploadingShipment(1_020_000L + index, oldest.plusSeconds(index));
        }

        recovery.recoverStaleClaimsOnStartup();

        assertThat(jdbcClient.sql("""
                        select count(*) from order_shipment where wechat_upload_status='UNKNOWN'
                        """).query(Integer.class).single()).isEqualTo(batchSize);
        assertThat(jdbcClient.sql("""
                        select count(*) from order_shipment where wechat_upload_status='UPLOADING'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertUploading(lastShipmentId);
        assertThat(provider.uploadRequests()).isEmpty();
    }

    @Test
    void staleRowsWithNullAttemptTimeUsePersistedUpdateTimeForRecovery() {
        long shipmentId = insertUploadingShipment(
                1_030_001L, LocalDateTime.of(2020, 1, 1, 0, 0)
        );
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_claimed_at=null,
                            last_attempt_at=null
                        where id=:id
                        """)
                .param("id", shipmentId).update();

        assertThat(stateStore.reconcileStaleByShipment(shipmentId, LocalDateTime.now())).isTrue();

        assertUnknown(shipmentId);
        assertThat(provider.uploadRequests()).isEmpty();
    }

    private long insertUploadingShipment(long orderId, LocalDateTime attemptTime) {
        jdbcClient.sql("""
                        insert into app_user(id, openid, status)
                        values (:id, :openid, 'ENABLED')
                        """)
                .param("id", orderId)
                .param("openid", "openid-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                            product_original_amount_cent, product_amount_cent, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent,
                            paid_amount_cent, receiver_name, receiver_phone, receiver_address,
                            created_at, updated_at)
                        values (
                            :id, :orderNo, :id, 'SHIPPED', 'CART', :idempotencyKey,
                            'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                            100, 100, '', 0, 0, 100,
                            100, 'Receiver', '13800008000', 'Address',
                            :createdAt, :createdAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "RECOVERY" + orderId)
                .param("idempotencyKey", "recovery-" + orderId)
                .param("createdAt", attemptTime)
                .update();
        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, logistics_type, delivery_mode, item_desc,
                            express_company_name, tracking_no, shipment_note, status,
                            wechat_provider_mode, wechat_upload_status,
                            wechat_error_code, wechat_error_message, retry_count,
                            wechat_upload_claim_token, wechat_upload_claimed_at,
                            shipped_at, last_attempt_at, created_at, updated_at)
                        values (
                            :orderId, 4, 1, 'recovery item',
                            null, null, '', 'SHIPPED',
                            'REAL', 'UPLOADING',
                            '', '', 0,
                            :claimToken, :attempt,
                            :attempt, :attempt, :attempt, :attempt)
                        """)
                .param("orderId", orderId)
                .param("claimToken", "claim-" + orderId)
                .param("attempt", attemptTime)
                .update();
        return jdbcClient.sql("select id from order_shipment where order_id=:orderId")
                .param("orderId", orderId).query(Long.class).single();
    }

    private void assertUnknown(long shipmentId) {
        var row = jdbcClient.sql("""
                        select wechat_upload_status, wechat_error_code, wechat_error_message,
                               wechat_uploaded_at
                        from order_shipment where id=:id
                        """)
                .param("id", shipmentId).query().singleRow();
        assertThat(row.get("wechat_upload_status")).isEqualTo("UNKNOWN");
        assertThat(row.get("wechat_error_code")).isEqualTo("ATTEMPT_OUTCOME_UNKNOWN");
        assertThat(row.get("wechat_error_message")).isEqualTo(
                "Previous WeChat shipping attempt outcome is unknown"
        );
        assertThat(row.get("wechat_uploaded_at")).isNull();
    }

    private void assertUploading(long shipmentId) {
        assertThat(jdbcClient.sql("""
                        select wechat_upload_status from order_shipment where id=:id
                        """).param("id", shipmentId).query(String.class).single())
                .isEqualTo("UPLOADING");
    }
}
