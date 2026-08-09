package org.muybaby.shopserver.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminShipmentControllerTest {

    private static final AtomicLong LIMITED_ADMIN_ID = new AtomicLong(993_000L);

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private ShippingProperties shippingProperties;

    @Autowired
    private ControllableWechatShippingProvider wechatShippingProvider;

    @BeforeEach
    void clearShipmentState() {
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_callback_log").update();
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values ('SF', '顺丰速运', true, timestamp '2026-07-08 09:00:00')
                        """).update();
        shippingProperties.setUploadEnabled(false);
        wechatShippingProvider.reset();
    }

    @Test
    void onlyAdminWithShipPermissionCanShip() throws Exception {
        String appToken = appLogin("ship-app-auth").token();
        String adminWithoutShipPermission = adminTokenWithoutPermissions();
        long orderId = insertPaidOrder(appLogin("ship-permission-user"), "SHIP-PERMISSION", "wx-ship-permission");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminWithoutShipPermission)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
    }

    @Test
    void shippingRequiresPaidOrder() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertOrder(appLogin("ship-created-user"), "SHIP-CREATED", "CREATED", "", "");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void activeElectronicWaybillBlocksManualShipment() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(
                appLogin("ship-active-waybill-user"),
                "SHIP-ACTIVE-WAYBILL",
                "wx-active-waybill"
        );
        insertElectronicWaybill(orderId, "CREATED", "TEST-WAYBILL-001");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PAID");
    }

    @Test
    void electronicWaybillConfirmationRequiresManageAndShipPermissions() throws Exception {
        long orderId = insertPaidOrder(
                appLogin("confirm-waybill-permission-user"),
                "CONFIRM-WAYBILL-PERMISSION",
                "wx-confirm-waybill-permission"
        );
        long waybillRecordId = insertElectronicWaybill(
                orderId, "CREATED", "TEST-WAYBILL-PERMISSION"
        );

        for (List<String> permissions : List.of(
                List.of("order:waybill:manage"),
                List.of("order:ship")
        )) {
            mockMvc.perform(post(
                            "/admin/orders/{orderId}/waybills/{waybillRecordId}/confirm-shipment",
                            orderId, waybillRecordId
                    )
                            .header("Authorization", "Bearer " + adminToken(permissions)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        }

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void electronicWaybillOnlyShipsAfterExplicitIdempotentConfirmation() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(
                appLogin("confirm-waybill-user"),
                "CONFIRM-WAYBILL",
                "wx-confirm-waybill"
        );
        long waybillRecordId = insertElectronicWaybill(
                orderId, "CREATED", "TEST-WAYBILL-CONFIRM"
        );

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();

        for (int requestNumber = 0; requestNumber < 2; requestNumber++) {
            mockMvc.perform(post(
                            "/admin/orders/{orderId}/waybills/{waybillRecordId}/confirm-shipment",
                            orderId, waybillRecordId
                    )
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.orderId").value(orderId))
                    .andExpect(jsonPath("$.data.logisticsType").value(1))
                    .andExpect(jsonPath("$.data.expressCompanyCode").value("TEST"))
                    .andExpect(jsonPath("$.data.trackingNo").value("TEST-WAYBILL-CONFIRM"))
                    .andExpect(jsonPath("$.data.shipmentSource").value("WECHAT_WAYBILL"))
                    .andExpect(jsonPath("$.data.electronicWaybillId").value(waybillRecordId));
        }

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertThat(jdbcClient.sql("select status from order_electronic_waybill where id = :id")
                .param("id", waybillRecordId)
                .query(String.class)
                .single()).isEqualTo("CONFIRMED");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("select count(*) from order_status_log where order_id = :orderId and event_type = 'ORDER_SHIPPED'")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void electronicWaybillCannotBeConfirmedWhileUpstreamRefreshIsInFlight() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(
                appLogin("confirm-refreshing-waybill-user"),
                "CONFIRM-REFRESHING-WAYBILL",
                "wx-confirm-refreshing-waybill"
        );
        long waybillRecordId = insertElectronicWaybill(
                orderId, "CREATED", "TEST-WAYBILL-REFRESHING"
        );
        jdbcClient.sql("""
                        update order_electronic_waybill
                        set pending_operation = 'REFRESH',
                            last_attempt_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :id
                        """)
                .param("id", waybillRecordId)
                .update();

        mockMvc.perform(post(
                        "/admin/orders/{orderId}/waybills/{waybillRecordId}/confirm-shipment",
                        orderId, waybillRecordId
                )
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select status from order_electronic_waybill where id = :id")
                .param("id", waybillRecordId)
                .query(String.class)
                .single()).isEqualTo("CREATED");
    }

    @Test
    void activeAfterSaleBlocksShipmentUntilTheRequestIsRejected() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession session = appLogin("ship-after-sale-hold-user");
        long orderId = insertPaidOrder(session, "SHIP-AFTER-SALE-HOLD", "wx-ship-after-sale-hold");
        jdbcClient.sql("""
                        insert into after_sale_request
                            (after_sale_no, order_id, user_id, after_sale_type, status, reason, description,
                             requested_amount_cent, created_at, updated_at)
                        values
                            (concat('ASSHIP', :orderId), :orderId, :userId,
                             'REFUND_ONLY', 'REQUESTED', '整单退款', '',
                             3980, timestamp '2026-07-08 10:20:00', timestamp '2026-07-08 10:20:00')
                        """)
                .param("orderId", orderId)
                .param("userId", session.userId())
                .update();

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400001));

        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("PAID");

        jdbcClient.sql("""
                        update after_sale_request
                        set status = 'REJECTED', updated_at = timestamp '2026-07-08 10:30:00'
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId));
    }

    @Test
    void shippingStoresLocalFieldsAndReturnsThemInAdminAndAppDetails() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession session = appLogin("ship-detail-user");
        long orderId = insertPaidOrder(session, "SHIP-DETAIL", "wx-ship-detail");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.logisticsType").value(1))
                .andExpect(jsonPath("$.data.deliveryMode").value(1))
                .andExpect(jsonPath("$.data.itemDesc").value("Shipment Item x1"))
                .andExpect(jsonPath("$.data.expressCompanyCode").value("SF"))
                .andExpect(jsonPath("$.data.expressCompanyName").value("顺丰速运"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipmentNote").value("front desk pickup"))
                .andExpect(jsonPath("$.data.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatProviderMode").value("DISABLED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.data.shippedAt").isNotEmpty());

        assertThat(jdbcClient.sql("""
                        select status
                        from shop_order
                        where id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");

        assertShipmentDetail(adminToken, session.token(), orderId, "SKIPPED");
    }

    @Test
    void adminAndAppShipmentResponsesExposeExactlyTheirPublicContracts() throws Exception {
        shippingProperties.setUploadEnabled(true);
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession session = appLogin("ship-exact-contract-user");
        long orderId = insertPaidOrder(session, "SHIP-EXACT-CONTRACT", "wx-exact-contract");

        JsonNode createShipment = objectMapper.readTree(mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).path("data");
        assertExactAdminShipmentFields(createShipment);
        assertThat(createShipment.path("shipmentSource").asText()).isEqualTo("MANUAL");
        assertThat(createShipment.has("electronicWaybillId")).isFalse();

        JsonNode adminShipment = objectMapper.readTree(mockMvc.perform(get("/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).path("data").path("shipment");
        assertExactAdminShipmentFields(adminShipment);
        assertThat(adminShipment.path("shipmentSource").asText()).isEqualTo("MANUAL");
        assertThat(adminShipment.has("electronicWaybillId")).isFalse();

        JsonNode appShipment = objectMapper.readTree(mockMvc.perform(get("/app/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + session.token()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()).path("data").path("shipment");
        assertExactFields(appShipment,
                "shipmentId", "orderId", "logisticsType", "deliveryMode", "itemDesc",
                "expressCompanyCode", "expressCompanyName", "trackingNo", "shipmentSource",
                "localShipmentStatus",
                "wechatProviderMode", "wechatUploadStatus", "wechatUploadMessage", "shippedAt",
                "waybillTrackingSupported", "waybillRegistrationKind",
                "waybillRegistrationStatus", "waybillRegistrationMessage",
                "uploadTime", "wechatUploadedAt");
        assertThat(appShipment.path("shipmentSource").asText()).isEqualTo("MANUAL");
        assertThat(appShipment.has("electronicWaybillId")).isFalse();
    }

    @Test
    void uploadDisabledSkipsWechatUploadButKeepsLocalShipmentSuccessful() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-disabled-user"), "SHIP-DISABLED", "wx-ship-disabled");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("SKIPPED"));

        assertThat(wechatShippingProvider.uploadRequests()).isEmpty();
        assertShipmentRow(orderId, "SKIPPED", "", "", 0);
    }

    @Test
    void mockUploadEnabledUploadsAndCapturesProviderRequest() throws Exception {
        shippingProperties.setUploadEnabled(true);
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession session = appLogin("ship-upload-user");
        long orderId = insertPaidOrder(session, "SHIP-UPLOADED", "wx-ship-uploaded");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("UPLOADED"));

        assertThat(wechatShippingProvider.uploadRequests()).hasSize(1);
        assertThat(wechatShippingProvider.uploadRequests().getFirst().transactionId()).isEqualTo("wx-ship-uploaded");
        assertThat(wechatShippingProvider.uploadRequests().getFirst().openid()).isEqualTo(session.openid());
        assertThat(wechatShippingProvider.uploadRequests().getFirst().shippingList().getFirst().trackingNo())
                .isEqualTo("SF1234567890");
        assertThat(wechatShippingProvider.uploadRequests().getFirst().shippingList().getFirst().expressCompany())
                .isEqualTo("SF");
        assertThat(wechatShippingProvider.uploadRequests().getFirst().shippingList().getFirst().itemDesc())
                .isEqualTo("Shipment Item x1")
                .isNotEqualTo("front desk pickup");
        assertShipmentRow(orderId, "UPLOADED", "", "", 0);
    }

    @Test
    void providerBusinessExceptionWhenUploadEnabledKeepsShipmentAndRecordsFailedUpload() throws Exception {
        shippingProperties.setUploadEnabled(true);
        wechatShippingProvider.failWith(new BusinessException(ErrorCode.WECHAT_PHONE_FAILED));
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-provider-business-failure-user"), "SHIP-PROVIDER-BUSINESS", "wx-provider-business");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("UPLOAD_RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat shipping upload outcome is unknown"))
                .andExpect(jsonPath("$.data.retryCount").value(0));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertShipmentRow(orderId, "UNKNOWN", "UPLOAD_RESULT_UNKNOWN", "WeChat shipping upload outcome is unknown", 0);
    }

    @Test
    void providerRuntimeExceptionWhenUploadEnabledKeepsShipmentAndRecordsSafeFailure() throws Exception {
        shippingProperties.setUploadEnabled(true);
        AppLoginSession session = appLogin("ship-provider-runtime-failure-user");
        wechatShippingProvider.failWith(new IllegalStateException(
                "synthetic token=sensitive-token openid=" + session.openid() + " tracking=SF1234567890 payload={secret}"
        ));
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(session, "SHIP-PROVIDER-RUNTIME", "wx-provider-runtime");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("UPLOAD_RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat shipping upload outcome is unknown"))
                .andExpect(jsonPath("$.data.retryCount").value(0));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        String recordedMessage = jdbcClient.sql("""
                        select wechat_error_message
                        from order_shipment
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
        assertThat(recordedMessage)
                .isEqualTo("WeChat shipping upload outcome is unknown")
                .doesNotContain("sensitive-token")
                .doesNotContain(session.openid())
                .doesNotContain("SF1234567890")
                .doesNotContain("payload");
        assertShipmentRow(orderId, "UNKNOWN", "UPLOAD_RESULT_UNKNOWN", "WeChat shipping upload outcome is unknown", 0);
    }

    @Test
    void unavailableProviderResultIsNotCollapsedIntoFailed() throws Exception {
        shippingProperties.setUploadEnabled(true);
        wechatShippingProvider.respondWith(WechatShippingUploadResult.unavailable(
                "TRADE_NOT_MANAGED", "WeChat shipping capability is unavailable"
        ));
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-provider-unavailable-user"), "SHIP-UNAVAILABLE", "wx-unavailable");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("TRADE_NOT_MANAGED"));

        assertShipmentRow(
                orderId,
                "UNAVAILABLE",
                "TRADE_NOT_MANAGED",
                "WeChat shipping capability is unavailable",
                0
        );
    }

    @Test
    void unknownProviderResultIsNotCollapsedIntoFailedOrMarkedUploaded() throws Exception {
        shippingProperties.setUploadEnabled(true);
        wechatShippingProvider.respondWith(WechatShippingUploadResult.unknown(
                "REQUEST_AMBIGUOUS", "WeChat shipping upload result is unknown"
        ));
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-provider-unknown-user"), "SHIP-UNKNOWN", "wx-unknown");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("REQUEST_AMBIGUOUS"))
                .andExpect(jsonPath("$.data.wechatUploadedAt").doesNotExist());

        assertShipmentRow(
                orderId,
                "UNKNOWN",
                "REQUEST_AMBIGUOUS",
                "WeChat shipping upload result is unknown",
                0
        );
    }

    @Test
    void missingTransactionIdWhenUploadEnabledKeepsShipmentAndRecordsFailedUpload() throws Exception {
        shippingProperties.setUploadEnabled(true);
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-missing-transaction-user"), "SHIP-MISSING-TX", "");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("MISSING_TRANSACTION_ID"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat payment transaction id is required"))
                .andExpect(jsonPath("$.data.retryCount").value(0));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertShipmentRow(orderId, "FAILED", "MISSING_TRANSACTION_ID", "WeChat payment transaction id is required", 0);
    }

    @Test
    void retryIncrementsRetryCountAndDoesNotDuplicateShipmentRows() throws Exception {
        shippingProperties.setUploadEnabled(true);
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-retry-user"), "SHIP-RETRY", "");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.retryCount").value(0));

        mockMvc.perform(post("/admin/orders/{orderId}/shipping/retry-wechat-upload", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertShipmentRow(orderId, "FAILED", "MISSING_TRANSACTION_ID", "WeChat payment transaction id is required", 1);
    }

    @Test
    void requestValidationRejectsBlankItemDescriptionAndOverLimitOptionalFields() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-validation-user"), "SHIP-VALIDATION", "wx-validation");

        for (int logisticsType = 1; logisticsType <= 4; logisticsType++) {
            mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"logisticsType":%d,"itemDesc":"   "}
                                    """.formatted(logisticsType)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(100400));
        }

        List<String> invalidRequests = List.of(
                """
                {"logisticsType":1,"itemDesc":"item","expressCompanyCode":"%s","trackingNo":"T"}
                """.formatted("C".repeat(129)),
                """
                {"logisticsType":1,"itemDesc":"item","expressCompanyCode":"SF","trackingNo":"%s"}
                """.formatted("T".repeat(81)),
                """
                {"logisticsType":1,"itemDesc":"item","expressCompanyCode":"SF","trackingNo":"T","consignorContact":"%s"}
                """.formatted("1".repeat(129)),
                """
                {"logisticsType":4,"itemDesc":"item","shipmentNote":"%s"}
                """.formatted("N".repeat(256))
        );
        for (String invalidRequest : invalidRequests) {
            mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidRequest))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(100400));
        }
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id=:orderId")
                .param("orderId", orderId).query(Integer.class).single()).isZero();
    }

    @Test
    void clientCannotOverrideDeliveryModeOrDerivedReceiverContact() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long orderId = insertPaidOrder(appLogin("ship-derived-fields-user"), "SHIP-DERIVED", "wx-derived");

        mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "logisticsType": 1,
                                  "deliveryMode": 999,
                                  "itemDesc": "derived fields",
                                  "expressCompanyCode": "SF",
                                  "trackingNo": "SF-DERIVED",
                                  "receiverContact": "malicious-full-phone"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryMode").value(1));

        var row = jdbcClient.sql("""
                        select delivery_mode, receiver_contact from order_shipment where order_id=:orderId
                        """).param("orderId", orderId).query().singleRow();
        assertThat(row.get("delivery_mode")).isEqualTo(1);
        assertThat(row.get("receiver_contact")).isEqualTo("*******0000");
        assertThat(row.get("receiver_contact")).isNotEqualTo("malicious-full-phone");
    }

    @Test
    void nonExpressResponsesAndAppDetailsOmitEveryExpressOnlyField() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession session = appLogin("ship-non-express-user");
        for (int logisticsType = 2; logisticsType <= 4; logisticsType++) {
            long orderId = insertPaidOrder(
                    session, "SHIP-NON-EXPRESS-" + logisticsType, "wx-non-express-" + logisticsType
            );
            mockMvc.perform(post("/admin/orders/{orderId}/ship", orderId)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"logisticsType":%d,"itemDesc":"mode %d item"}
                                    """.formatted(logisticsType, logisticsType)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.logisticsType").value(logisticsType))
                    .andExpect(jsonPath("$.data.expressCompanyCode").doesNotExist())
                    .andExpect(jsonPath("$.data.expressCompanyName").doesNotExist())
                    .andExpect(jsonPath("$.data.trackingNo").doesNotExist());

            mockMvc.perform(get("/app/orders/{orderId}", orderId)
                            .header("Authorization", "Bearer " + session.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.shipment.logisticsType").value(logisticsType))
                    .andExpect(jsonPath("$.data.shipment.expressCompanyCode").doesNotExist())
                    .andExpect(jsonPath("$.data.shipment.expressCompanyName").doesNotExist())
                    .andExpect(jsonPath("$.data.shipment.trackingNo").doesNotExist())
                    .andExpect(jsonPath("$.data.shipment.wechatUploadMessage").value(
                            "Shipping information is pending platform upload"
                    ));
        }
    }

    @Test
    void adminAndAppDetailPreflightReconcileStaleUploadingWithoutProviderCall() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        AppLoginSession adminDetailUser = appLogin("ship-stale-admin-detail-user");
        long adminOrderId = insertPaidOrder(adminDetailUser, "SHIP-STALE-ADMIN", "wx-stale-admin");
        shippingProperties.setUploadEnabled(false);
        mockMvc.perform(post("/admin/orders/{orderId}/ship", adminOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk());
        markShipmentStaleUploading(adminOrderId);
        int callsBeforeAdminDetail = wechatShippingProvider.uploadRequests().size();

        mockMvc.perform(get("/admin/orders/{orderId}", adminOrderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.shipment.wechatErrorCode").value("ATTEMPT_OUTCOME_UNKNOWN"));
        assertThat(wechatShippingProvider.uploadRequests()).hasSize(callsBeforeAdminDetail);

        AppLoginSession appDetailUser = appLogin("ship-stale-app-detail-user");
        long appOrderId = insertPaidOrder(appDetailUser, "SHIP-STALE-APP", "wx-stale-app");
        mockMvc.perform(post("/admin/orders/{orderId}/ship", appOrderId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shipRequest()))
                .andExpect(status().isOk());
        markShipmentStaleUploading(appOrderId);
        int callsBeforeAppDetail = wechatShippingProvider.uploadRequests().size();

        mockMvc.perform(get("/app/orders/{orderId}", appOrderId)
                        .header("Authorization", "Bearer " + appDetailUser.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.shipment.wechatUploadMessage").value(
                        "Platform upload status is being confirmed"
                ))
                .andExpect(jsonPath("$.data.shipment.wechatErrorCode").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.wechatErrorMessage").doesNotExist());
        assertThat(wechatShippingProvider.uploadRequests()).hasSize(callsBeforeAppDetail);
    }

    private void markShipmentStaleUploading(long orderId) {
        jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status='UPLOADING',
                            last_attempt_at=:attempt,
                            updated_at=:attempt
                        where order_id=:orderId
                        """)
                .param("attempt", LocalDateTime.now().minusMinutes(11))
                .param("orderId", orderId)
                .update();
    }

    private void assertShipmentDetail(String adminToken, String appToken, long orderId, String uploadStatus) throws Exception {
        mockMvc.perform(get("/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.expressCompanyCode").value("SF"))
                .andExpect(jsonPath("$.data.shipment.expressCompanyName").value("顺丰速运"))
                .andExpect(jsonPath("$.data.shipment.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipment.shipmentNote").value("front desk pickup"))
                .andExpect(jsonPath("$.data.shipment.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value(uploadStatus))
                .andExpect(jsonPath("$.data.shipment.shippedAt").isNotEmpty());

        mockMvc.perform(get("/app/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.expressCompanyCode").value("SF"))
                .andExpect(jsonPath("$.data.shipment.expressCompanyName").value("顺丰速运"))
                .andExpect(jsonPath("$.data.shipment.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipment.shipmentNote").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.wechatErrorCode").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.wechatErrorMessage").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.retryCount").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.lastAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.data.shipment.localShipmentStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value(uploadStatus))
                .andExpect(jsonPath("$.data.shipment.shippedAt").isNotEmpty());
    }

    private void assertExactAdminShipmentFields(JsonNode shipment) {
        assertExactFields(shipment,
                "shipmentId", "orderId", "logisticsType", "deliveryMode", "itemDesc",
                "expressCompanyCode", "expressCompanyName", "trackingNo", "shipmentSource",
                "shipmentNote",
                "localShipmentStatus", "wechatProviderMode", "wechatUploadStatus", "retryCount",
                "waybillTrackingSupported", "waybillRegistrationKind",
                "waybillRegistrationStatus", "waybillRegistrationMessage",
                "shippedAt", "uploadTime", "wechatUploadedAt", "lastAttemptAt");
    }

    private void assertExactFields(JsonNode object, String... expected) {
        List<String> actual = new ArrayList<>();
        object.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(expected);
    }

    private void assertShipmentRow(long orderId, String uploadStatus, String errorCode, String errorMessage, int retryCount) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where order_id = :orderId
                          and express_company_name = '顺丰速运'
                          and express_company_code = 'SF'
                          and tracking_no = 'SF1234567890'
                          and shipment_note = 'front desk pickup'
                          and logistics_type = 1
                          and delivery_mode = 1
                          and item_desc = 'Shipment Item x1'
                          and status = 'SHIPPED'
                          and wechat_upload_status = :uploadStatus
                          and wechat_error_code = :errorCode
                          and wechat_error_message = :errorMessage
                          and retry_count = :retryCount
                          and shipped_at is not null
                        """)
                .param("orderId", orderId)
                .param("uploadStatus", uploadStatus)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("retryCount", retryCount)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private String adminTokenWithoutPermissions() {
        return adminToken(List.of());
    }

    private String adminToken(List<String> permissions) {
        long userId = LIMITED_ADMIN_ID.incrementAndGet();
        long roleId = userId;
        String username = "ShipmentLimited" + userId;
        insertLimitedAdmin(userId, roleId, username);

        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(userId, username, List.of(), permissions, Instant.now())
        ).accessToken();
    }

    private void insertLimitedAdmin(long userId, long roleId, String username) {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:userId, :username, :passwordHash, 'Shipment Limited Admin',
                             :email, 'ENABLED')
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("email", username.toLowerCase() + "@shop.local")
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, :code, 'Shipment Limited Role', '', true)
                        """)
                .param("roleId", roleId)
                .param("code", "R_SHIPMENT_LIMITED_" + roleId)
                .update();
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
    }

    private long insertPaidOrder(AppLoginSession session, String orderNo, String transactionId) {
        long orderId = insertOrder(session, orderNo, "PAID", orderNo + "-MCH", transactionId);
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, out_trade_no, prepay_id, transaction_id,
                             payer_openid, status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:orderId, null, :outTradeNo, :prepayId, :transactionId,
                             :openid, 'PAID', 3980, timestamp '2026-07-08 11:00:00',
                             timestamp '2026-07-08 10:10:00', timestamp '2026-07-08 10:00:00',
                             timestamp '2026-07-08 10:10:00')
                        """)
                .param("orderId", orderId)
                .param("outTradeNo", orderNo + "-MCH")
                .param("prepayId", "mock-prepay-" + orderNo)
                .param("transactionId", transactionId)
                .param("openid", session.openid())
                .update();
        return orderId;
    }

    private long insertOrder(AppLoginSession session, String orderNo, String status, String outTradeNo, String transactionId) {
        long orderId = Math.abs((orderNo + System.nanoTime()).hashCode()) + 100000L;
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_name,
                             coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address, payment_transaction_id,
                             merchant_trade_no, paid_at, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             3980, 3980, '', 0, 0, 3980, :paidAmount,
                             'Ship User', '13800000000', 'Ship Test Address', :transactionId,
                             :outTradeNo, :paidAt, timestamp '2026-07-08 10:00:00',
                             timestamp '2026-07-08 10:00:00')
                        """)
                .param("orderId", orderId)
                .param("orderNo", orderNo)
                .param("userId", session.userId())
                .param("status", status)
                .param("idempotencyKey", "ship-seed-" + orderNo)
                .param("paidAmount", "PAID".equals(status) ? 3980L : 0L)
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("paidAt", "PAID".equals(status) ? LocalDateTime.of(2026, 7, 8, 10, 10) : null)
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (order_id, sku_id, spu_id, product_title, product_subtitle, main_image,
                             sku_image, display_image, sku_code, spec_text, original_price_cent,
                             unit_price_cent, quantity, line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:orderId, 1, 1, 'Shipment Item', '', 'https://example.test/ship-main.jpg',
                             'https://example.test/ship-sku.jpg', 'https://example.test/ship-sku.jpg',
                             :skuCode, '300g', 3980, 3980, 1, 3980, 3980,
                             timestamp '2026-07-08 10:00:00')
                        """)
                .param("orderId", orderId)
                .param("skuCode", "SKU-" + orderNo)
                .update();
        return orderId;
    }

    private long insertElectronicWaybill(long orderId, String status, String waybillId) {
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
                            :orderId, 1, :idempotencyKey, :requestDigest, :providerOrderId,
                            'SANDBOX', 'TEST', '微信官方测试运力', 'test_biz_id', 1,
                            'test_service_name', :status, 'NONE', :waybillId, 1, 1.000,
                            20.00, 15.00, 10.00, '寄件人', '13800138000', '沐宝商城',
                            '广东省', '深圳市', '南山区', '测试路1号',
                            o.receiver_name, o.receiver_phone, '广东省', '深圳市', '南山区',
                            '测试路2号', po.id, po.payer_openid, 1
                        from shop_order o
                        join payment_order po on po.order_id = o.id
                        where o.id = :orderId
                        """)
                .param("orderId", orderId)
                .param("idempotencyKey", "active-waybill-" + orderId)
                .param("requestDigest", "a".repeat(64))
                .param("providerOrderId", "SHOPWB-" + orderId + "-1")
                .param("status", status)
                .param("waybillId", waybillId)
                .update();
        return jdbcClient.sql("""
                        select id
                        from order_electronic_waybill
                        where order_id = :orderId and waybill_id = :waybillId
                        """)
                .param("orderId", orderId)
                .param("waybillId", waybillId)
                .query(Long.class)
                .single();
    }

    private String shipRequest() {
        return """
                {
                  "logisticsType": 1,
                  "itemDesc": "Shipment Item x1",
                  "expressCompanyCode": "SF",
                  "trackingNo": "SF1234567890",
                  "shipmentNote": "front desk pickup"
                }
                """;
    }

    private AppLoginSession appLogin(String code) throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s"}
                                """.formatted(code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        long userId = json.path("data").path("user").path("userId").asLong();
        String openid = jdbcClient.sql("select openid from app_user where id = :userId")
                .param("userId", userId)
                .query(String.class)
                .single();
        return new AppLoginSession(json.path("data").path("token").asText(), userId, openid);
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private record AppLoginSession(String token, long userId, String openid) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WechatShippingProviderTestConfig {

        @Bean
        @Primary
        ControllableWechatShippingProvider controllableWechatShippingProvider() {
            return new ControllableWechatShippingProvider();
        }
    }

    static class ControllableWechatShippingProvider implements WechatShippingProvider {

        private final List<WechatShippingUploadRequest> uploadRequests = new ArrayList<>();
        private RuntimeException exception;
        private WechatShippingUploadResult result = WechatShippingUploadResult.uploaded();

        @Override
        public WechatProviderMode mode() {
            return WechatProviderMode.REAL;
        }

        @Override
        public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
            uploadRequests.add(request);
            if (exception != null) {
                throw exception;
            }
            return result;
        }

        @Override
        public WechatShippingCapabilityResult queryCapability() {
            return WechatShippingCapabilityResult.available();
        }

        @Override
        public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
            return List.of();
        }

        List<WechatShippingUploadRequest> uploadRequests() {
            return List.copyOf(uploadRequests);
        }

        void failWith(RuntimeException exception) {
            this.exception = exception;
        }

        void respondWith(WechatShippingUploadResult result) {
            this.result = result;
        }

        void reset() {
            uploadRequests.clear();
            exception = null;
            result = WechatShippingUploadResult.uploaded();
        }
    }
}
