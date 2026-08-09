package org.muybaby.shopserver.logistics.tracking;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathItem;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathResult;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryResult;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ShipmentTrackingStateStoreTest {

    private static final long ORDER_ID = 9_884_001L;
    private static final long USER_ID = 9_884_002L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ShipmentTrackingStateStore stateStore;

    @BeforeEach
    void setUp() {
        cleanRows();
        seedElectronicWaybillShipment();
    }

    @AfterEach
    void tearDown() {
        cleanRows();
    }

    @Test
    void storesIndependentSummaryAndPathResultsWithoutChangingOrderStatus() {
        ShipmentTrackingClaim claim = stateStore.claimForOwner(ORDER_ID, USER_ID, true)
                .orElseThrow();

        boolean completed = stateStore.complete(claim, new ShipmentTrackingSyncResult(
                WechatTrackingQueryResult.success(4),
                WechatTrackingPathResult.success(List.of(
                        new WechatTrackingPathItem(
                                1_786_000_100L, 100001, "快递员 13800138000 已揽件"
                        ),
                        new WechatTrackingPathItem(
                                1_786_000_200L, 200001, "快件运输中"
                        )
                ))
        ));

        assertThat(completed).isTrue();
        var response = stateStore.snapshotForOwner(ORDER_ID, USER_ID);
        assertThat(response.querySyncStatus()).isEqualTo(WechatTrackingSyncStatus.SYNCED);
        assertThat(response.logisticsStatus()).isEqualTo(WechatLogisticsStatus.SIGNED);
        assertThat(response.logisticsStatusText()).isEqualTo("已签收");
        assertThat(response.pathSyncStatus()).isEqualTo(WechatTrackingSyncStatus.SYNCED);
        assertThat(response.pathItems()).extracting("actionTime")
                .containsExactly(1_786_000_200L, 1_786_000_100L);
        assertThat(response.pathItems().get(1).actionMessage())
                .isEqualTo("快递员 138****8000 已揽件");
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", ORDER_ID)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertThat(jdbcClient.sql("select count(*) from order_status_log where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void failedRefreshKeepsTheLastSuccessfulPathAndReportsEachSourceSeparately() {
        ShipmentTrackingClaim initialClaim = stateStore.claimForOwner(ORDER_ID, USER_ID, true)
                .orElseThrow();
        stateStore.complete(initialClaim, new ShipmentTrackingSyncResult(
                WechatTrackingQueryResult.success(2),
                WechatTrackingPathResult.success(List.of(
                        new WechatTrackingPathItem(1_786_000_100L, 100001, "快递公司已揽件")
                ))
        ));

        ShipmentTrackingClaim retryClaim = stateStore.claimForOwner(ORDER_ID, USER_ID, true)
                .orElseThrow();
        stateStore.complete(retryClaim, new ShipmentTrackingSyncResult(
                WechatTrackingQueryResult.failure(
                        WechatProviderOutcome.UNKNOWN, "REQUEST_AMBIGUOUS", "摘要状态暂不可用"
                ),
                WechatTrackingPathResult.failure(
                        WechatProviderOutcome.REJECTED, "WECHAT_930001", "轨迹查询失败"
                )
        ));

        var response = stateStore.snapshotForOwner(ORDER_ID, USER_ID);
        assertThat(response.querySyncStatus()).isEqualTo(WechatTrackingSyncStatus.UNKNOWN);
        assertThat(response.queryErrorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertThat(response.logisticsStatus()).isEqualTo(WechatLogisticsStatus.IN_TRANSIT);
        assertThat(response.pathSyncStatus()).isEqualTo(WechatTrackingSyncStatus.FAILED);
        assertThat(response.pathErrorCode()).isEqualTo("WECHAT_930001");
        assertThat(response.pathItems()).hasSize(1);
        assertThat(response.pathItems().getFirst().actionMessage()).isEqualTo("快递公司已揽件");
    }

    @Test
    void ownerBoundaryUsesResourceNotFound() {
        assertThatThrownBy(() -> stateStore.snapshotForOwner(ORDER_ID, USER_ID + 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void seedElectronicWaybillShipment() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            receiver_name, receiver_phone, receiver_address,
                            payment_transaction_id, merchant_trade_no, paid_at, shipped_at,
                            created_at, updated_at)
                        values (
                            :orderId, :orderNo, :userId, 'SHIPPED', 'CART', :key,
                            '轨迹测试用户', '13800138000', '广东省深圳市南山区测试路2号',
                            :transactionId, :outTradeNo, :now, :now, :now, :now)
                        """)
                .param("orderId", ORDER_ID)
                .param("orderNo", "TRACKING-" + ORDER_ID)
                .param("userId", USER_ID)
                .param("key", "tracking-" + ORDER_ID)
                .param("transactionId", "WX-TRACKING-" + ORDER_ID)
                .param("outTradeNo", "MCH-TRACKING-" + ORDER_ID)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into payment_order(
                            order_id, out_trade_no, transaction_id, payer_openid,
                            status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values (
                            :orderId, :outTradeNo, :transactionId, :openid,
                            'PAID', 100, :now, :now, :now, :now)
                        """)
                .param("orderId", ORDER_ID)
                .param("outTradeNo", "MCH-TRACKING-" + ORDER_ID)
                .param("transactionId", "WX-TRACKING-" + ORDER_ID)
                .param("openid", "tracking-openid-" + ORDER_ID)
                .param("now", now)
                .update();
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
                            payer_openid, created_by, created_at, updated_at)
                        select
                            :orderId, 1, :key, :digest, :providerOrderId,
                            'PRODUCTION', 'SF', '顺丰速运', 'biz-id', 1, '标准快递',
                            'CONFIRMED', 'NONE', :waybillId,
                            1, 1.000, 20.00, 15.00, 10.00,
                            '沐宝仓库', '13900139000', '沐宝', '广东省',
                            '深圳市', '南山区', '测试路1号',
                            o.receiver_name, o.receiver_phone, '广东省', '深圳市',
                            '南山区', '测试路2号', po.id, po.payer_openid, 1, :now, :now
                        from shop_order o
                        join payment_order po on po.order_id = o.id
                        where o.id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .param("key", "tracking-waybill-" + ORDER_ID)
                .param("digest", "a".repeat(64))
                .param("providerOrderId", "SHOP-WB-" + ORDER_ID + "-1")
                .param("waybillId", "SF-" + ORDER_ID)
                .param("now", now)
                .update();
        Long waybillId = jdbcClient.sql("""
                        select id from order_electronic_waybill where order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into order_shipment(
                            order_id, logistics_type, delivery_mode, item_desc,
                            express_company_code, express_company_name, tracking_no,
                            shipment_source, electronic_waybill_id, status,
                            wechat_provider_mode, wechat_upload_status,
                            shipped_at, created_at, updated_at)
                        values (
                            :orderId, 1, 1, '测试商品 x1', 'SF', '顺丰速运', :trackingNo,
                            'WECHAT_WAYBILL', :waybillId, 'SHIPPED',
                            'DISABLED', 'SKIPPED', :now, :now, :now)
                        """)
                .param("orderId", ORDER_ID)
                .param("trackingNo", "SF-" + ORDER_ID)
                .param("waybillId", waybillId)
                .param("now", now)
                .update();
        Long shipmentId = jdbcClient.sql("select id from order_shipment where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into shipment_waybill_registration(
                            shipment_id, registration_kind, status, waybill_token,
                            attempt_count, last_attempt_at, registered_at, created_at, updated_at)
                        values (
                            :shipmentId, 'TRACE', 'REGISTERED', :token,
                            1, :now, :now, :now, :now)
                        """)
                .param("shipmentId", shipmentId)
                .param("token", "tracking-waybill-token")
                .param("now", now)
                .update();
    }

    private void cleanRows() {
        jdbcClient.sql("""
                        delete from shipment_tracking_event
                        where shipment_id in (select id from order_shipment where order_id = :orderId)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        delete from shipment_tracking_snapshot
                        where shipment_id in (select id from order_shipment where order_id = :orderId)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        delete from shipment_waybill_registration
                        where shipment_id in (select id from order_shipment where order_id = :orderId)
                        """)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("delete from order_shipment where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("delete from order_electronic_waybill where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("delete from payment_order where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("delete from order_status_log where order_id = :orderId")
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("delete from shop_order where id = :orderId")
                .param("orderId", ORDER_ID)
                .update();
    }
}
