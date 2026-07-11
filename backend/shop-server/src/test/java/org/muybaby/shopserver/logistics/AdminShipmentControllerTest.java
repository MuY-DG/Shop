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
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from coupon_template").update();
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
                .andExpect(jsonPath("$.data.expressCompany").value("顺丰速运"))
                .andExpect(jsonPath("$.data.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipmentNote").value("front desk pickup"))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
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
                .isNull();
        assertThat(wechatShippingProvider.uploadRequests().getFirst().shippingList().getFirst().itemDesc())
                .isEqualTo("历史订单商品")
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
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("WECHAT_SHIPPING_UPLOAD_FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat shipping upload failed"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertShipmentRow(orderId, "FAILED", "WECHAT_SHIPPING_UPLOAD_FAILED", "WeChat shipping upload failed", 1);
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
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("WECHAT_SHIPPING_UPLOAD_FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat shipping upload failed"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

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
                .isEqualTo("WeChat shipping upload failed")
                .doesNotContain("sensitive-token")
                .doesNotContain(session.openid())
                .doesNotContain("SF1234567890")
                .doesNotContain("payload");
        assertShipmentRow(orderId, "FAILED", "WECHAT_SHIPPING_UPLOAD_FAILED", "WeChat shipping upload failed", 1);
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
                1
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
                1
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
                .andExpect(jsonPath("$.data.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.wechatErrorCode").value("MISSING_TRANSACTION_ID"))
                .andExpect(jsonPath("$.data.wechatErrorMessage").value("WeChat payment transaction id is required"))
                .andExpect(jsonPath("$.data.retryCount").value(1));

        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
        assertShipmentRow(orderId, "FAILED", "MISSING_TRANSACTION_ID", "WeChat payment transaction id is required", 1);
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
                .andExpect(jsonPath("$.data.retryCount").value(1));

        mockMvc.perform(post("/admin/orders/{orderId}/shipping/retry-wechat-upload", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatUploadStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.retryCount").value(2));

        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertShipmentRow(orderId, "FAILED", "MISSING_TRANSACTION_ID", "WeChat payment transaction id is required", 2);
    }

    private void assertShipmentDetail(String adminToken, String appToken, long orderId, String uploadStatus) throws Exception {
        mockMvc.perform(get("/admin/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.expressCompany").value("顺丰速运"))
                .andExpect(jsonPath("$.data.shipment.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipment.shipmentNote").value("front desk pickup"))
                .andExpect(jsonPath("$.data.shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value(uploadStatus))
                .andExpect(jsonPath("$.data.shipment.shippedAt").isNotEmpty());

        mockMvc.perform(get("/app/orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipment.expressCompany").value("顺丰速运"))
                .andExpect(jsonPath("$.data.shipment.trackingNo").value("SF1234567890"))
                .andExpect(jsonPath("$.data.shipment.shipmentNote").value("front desk pickup"))
                .andExpect(jsonPath("$.data.shipment.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.shipment.wechatUploadStatus").value(uploadStatus))
                .andExpect(jsonPath("$.data.shipment.shippedAt").isNotEmpty());
    }

    private void assertShipmentRow(long orderId, String uploadStatus, String errorCode, String errorMessage, int retryCount) {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from order_shipment
                        where order_id = :orderId
                          and express_company_name = '顺丰速运'
                          and tracking_no = 'SF1234567890'
                          and shipment_note = 'front desk pickup'
                          and logistics_type = 1
                          and delivery_mode = 1
                          and item_desc = '历史订单商品'
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
        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(1L, "Super", List.of("R_SUPER"), List.of(), Instant.now())
        ).accessToken();
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

    private String shipRequest() {
        return """
                {
                  "expressCompany": "顺丰速运",
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
