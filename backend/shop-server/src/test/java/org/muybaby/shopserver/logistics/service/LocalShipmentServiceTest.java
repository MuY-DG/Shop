package org.muybaby.shopserver.logistics.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShippingProperties;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.logistics.dto.AdminShipOrderRequest;
import org.muybaby.shopserver.logistics.dto.OrderShipmentResponse;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalShipmentServiceTest {

    private static final AtomicLong IDS = new AtomicLong(910_000L);
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN, 1L, "shipping-admin", List.of("R_SUPER"), List.of("order:ship")
    );

    @Autowired
    private LocalShipmentService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ShippingProperties shippingProperties;

    @BeforeEach
    void clearShipmentState() {
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        insertCarrier("SF", "顺丰速运", true);
        insertCarrier("JD", "京东物流", true);
        insertCarrier("OFF", "停用物流", false);
        shippingProperties.setUploadEnabled(false);
    }

    @Test
    void contactMaskerNormalizesAndKeepsOnlyTheFinalFourDigits() {
        ShipmentContactMasker masker = new ShipmentContactMasker();

        assertThat(masker.mask(" 138-0000-8000 ")).isEqualTo("*******8000");
        assertThat(masker.mask("4321")).isEqualTo("*******4321");
        assertThat(masker.mask("not-a-phone")).isNull();
        assertThat(masker.mask("123")).isNull();
        assertThat(masker.mask(" ")).isNull();
        assertThat(masker.mask(null)).isNull();
    }

    @Test
    void errorSanitizerRedactsKnownSecretsControlsAndDatabaseOverflow() {
        WechatShippingErrorSanitizer sanitizer = new WechatShippingErrorSanitizer();
        String token = "synthetic-access-token";
        String openid = "openid-secret-value";
        String phone = "13800008000";
        String tracking = "SF1234567890";

        WechatShippingErrorSanitizer.SanitizedError sanitized = sanitizer.sanitize(
                "REMOTE\r\nERROR-" + token + "-" + "X".repeat(100),
                "echo " + token + "\n" + openid + " " + phone + " " + tracking + "\u0000",
                List.of(token, openid, phone, tracking)
        );

        assertThat(sanitized.code())
                .isEqualTo("WECHAT_SHIPPING_ERROR")
                .doesNotContain("\r", "\n", token);
        assertThat(sanitized.message())
                .doesNotContain("\n", "\u0000", token, openid, phone, tracking)
                .contains("[REDACTED]")
                .hasSizeLessThanOrEqualTo(255);
    }

    @Test
    void errorSanitizerUsesGenericValuesForBlankUnsafeInput() {
        WechatShippingErrorSanitizer.SanitizedError sanitized =
                new WechatShippingErrorSanitizer().sanitize(" / \r\n", "\u0000\r\n", List.of());

        assertThat(sanitized.code()).isEqualTo("WECHAT_SHIPPING_ERROR");
        assertThat(sanitized.message()).isEqualTo("WeChat shipping operation failed");
    }

    @Test
    void errorSanitizerPreservesSafeTokenSemanticCodesButGenericizesSerializedPayloads() {
        WechatShippingErrorSanitizer sanitizer = new WechatShippingErrorSanitizer();

        assertThat(sanitizer.sanitize(
                "ACCESS_TOKEN_UNAVAILABLE", "safe", List.of()
        ).code()).isEqualTo("ACCESS_TOKEN_UNAVAILABLE");
        assertThat(sanitizer.sanitize(
                "TOKEN_EXPIRED", "safe", List.of()
        ).code()).isEqualTo("TOKEN_EXPIRED");
        assertThat(sanitizer.sanitize(
                "WECHAT_10060001", "safe", List.of()
        ).code()).isEqualTo("WECHAT_10060001");

        String payload = "payload={\"shipping_list\":[{\"transaction_id\":\"tx-alpha\","
                + "\"item_desc\":\"private item\"}]}";
        WechatShippingErrorSanitizer.SanitizedError serialized = sanitizer.sanitize(
                "REMOTE_FAILED", payload, List.of("tx-alpha", "private item")
        );
        assertThat(serialized.message())
                .isEqualTo("WeChat shipping operation failed")
                .doesNotContain("payload", "shipping_list", "transaction_id", "tx-alpha", "private item");
    }

    @ParameterizedTest
    @EnumSource(LogisticsType.class)
    void localTransactionPersistsEveryModeAndMovesPaidOrderToShipped(LogisticsType logisticsType) {
        long orderId = insertPaidOrder("13800008000");
        AdminShipOrderRequest request = request(logisticsType, "商品😀 x1", "仓库备注");

        OrderShipmentResponse response = service.create(ADMIN, orderId, request);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.logisticsType()).isEqualTo(logisticsType);
        assertThat(response.deliveryMode().value()).isEqualTo(1);
        assertThat(response.itemDesc()).isEqualTo("商品😀 x1");
        assertThat(response.shipmentNote()).isEqualTo("仓库备注");
        assertThat(response.localShipmentStatus()).isEqualTo("SHIPPED");
        assertThat(response.wechatProviderMode()).isEqualTo(WechatProviderMode.DISABLED);
        assertThat(response.wechatUploadStatus()).isEqualTo(WechatShippingUploadStatus.SKIPPED);
        assertThat(response.retryCount()).isZero();
        assertThat(response.shippedAt()).isNotNull();

        Map<String, Object> row = jdbcClient.sql("""
                        select logistics_type, delivery_mode, item_desc,
                               express_company_code, express_company_name, tracking_no,
                               consignor_contact, receiver_contact, shipment_note,
                               status, wechat_provider_mode, wechat_upload_status, retry_count
                        from order_shipment where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query()
                .singleRow();
        assertThat(row.get("logistics_type")).isEqualTo(logisticsType.value());
        assertThat(row.get("delivery_mode")).isEqualTo(1);
        assertThat(row.get("item_desc")).isEqualTo("商品😀 x1");
        assertThat(row.get("shipment_note")).isEqualTo("仓库备注");
        if (logisticsType == LogisticsType.EXPRESS) {
            assertThat(row.get("express_company_code")).isEqualTo("SF");
            assertThat(row.get("express_company_name")).isEqualTo("顺丰速运");
            assertThat(row.get("tracking_no")).isEqualTo("SF1234567890");
            assertThat(row.get("receiver_contact")).isEqualTo("*******8000");
        } else {
            assertThat(row.get("express_company_code")).isNull();
            assertThat(row.get("express_company_name")).isNull();
            assertThat(row.get("tracking_no")).isNull();
            assertThat(row.get("consignor_contact")).isNull();
            assertThat(row.get("receiver_contact")).isNull();
        }
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(String.class)
                .single()).isEqualTo("SHIPPED");
    }

    @Test
    void itemDescriptionLimitCountsUnicodeCodePoints() {
        long acceptedOrder = insertPaidOrder("13800008000");
        String accepted = "😀".repeat(120);

        assertThat(service.create(
                ADMIN, acceptedOrder,
                new AdminShipOrderRequest(LogisticsType.PICKUP, accepted, null, null, null, null)
        ).itemDesc()).isEqualTo(accepted);

        long rejectedOrder = insertPaidOrder("13800008000");
        assertThatThrownBy(() -> service.create(
                ADMIN, rejectedOrder,
                new AdminShipOrderRequest(LogisticsType.PICKUP, "😀".repeat(121), null, null, null, null)
        )).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(jdbcClient.sql("select status from shop_order where id = :orderId")
                .param("orderId", rejectedOrder).query(String.class).single()).isEqualTo("PAID");
    }

    @Test
    void validatesCarrierCodeAndRejectsExpressOnlyFieldsForOtherModes() {
        assertValidationFailure(request(LogisticsType.EXPRESS, "商品", "note"), "", "SF123");
        assertValidationFailure(
                new AdminShipOrderRequest(LogisticsType.EXPRESS, "商品", "顺丰速运", "SF123", null, null)
        );
        assertValidationFailure(
                new AdminShipOrderRequest(LogisticsType.EXPRESS, "商品", "OFF", "OFF123", null, null)
        );
        assertValidationFailure(
                new AdminShipOrderRequest(LogisticsType.LOCAL_DELIVERY, "商品", "SF", null, null, null)
        );
        assertValidationFailure(
                new AdminShipOrderRequest(LogisticsType.VIRTUAL, "商品", null, "TRACK", null, null)
        );
        assertValidationFailure(
                new AdminShipOrderRequest(LogisticsType.PICKUP, "商品", null, null, "13800008000", null)
        );
    }

    @Test
    void expressRequiresTrackingAndSfRequiresAtLeastOneUsableMaskedContact() {
        long noContactOrder = insertPaidOrder("unknown");
        assertThatThrownBy(() -> service.create(
                ADMIN, noContactOrder,
                new AdminShipOrderRequest(LogisticsType.EXPRESS, "商品", "SF", "SF001", "bad", null)
        )).isInstanceOf(BusinessException.class);

        long consignorOrder = insertPaidOrder("unknown");
        OrderShipmentResponse response = service.create(
                ADMIN, consignorOrder,
                new AdminShipOrderRequest(LogisticsType.EXPRESS, "商品", "SF", "SF002", "021-5555-4321", null)
        );
        assertThat(response.expressCompanyCode()).isEqualTo("SF");
        assertThat(jdbcClient.sql("select consignor_contact from order_shipment where order_id=:orderId")
                .param("orderId", consignorOrder).query(String.class).single()).isEqualTo("*******4321");

        long invalidOptionalContactOrder = insertPaidOrder("13800008000");
        assertThatThrownBy(() -> service.create(
                ADMIN, invalidOptionalContactOrder,
                new AdminShipOrderRequest(LogisticsType.EXPRESS, "商品", "JD", "JD001", "invalid", null)
        )).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void firstShipmentAcceptsPaidOnlyAndDuplicateCreateRollsBack() {
        long orderId = insertPaidOrder("13800008000");
        service.create(ADMIN, orderId, request(LogisticsType.PICKUP, "商品", null));

        assertThatThrownBy(() -> service.create(ADMIN, orderId, request(LogisticsType.PICKUP, "商品", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ORDER_STATE_CONFLICT);
        assertThat(jdbcClient.sql("select count(*) from order_shipment where order_id=:orderId")
                .param("orderId", orderId).query(Integer.class).single()).isEqualTo(1);
    }

    private void assertValidationFailure(AdminShipOrderRequest request) {
        long orderId = insertPaidOrder("13800008000");
        assertThatThrownBy(() -> service.create(ADMIN, orderId, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private void assertValidationFailure(AdminShipOrderRequest original, String companyCode, String trackingNo) {
        assertValidationFailure(new AdminShipOrderRequest(
                original.logisticsType(), original.itemDesc(), companyCode, trackingNo,
                original.consignorContact(), original.shipmentNote()
        ));
    }

    private AdminShipOrderRequest request(LogisticsType type, String itemDesc, String note) {
        if (type == LogisticsType.EXPRESS) {
            return new AdminShipOrderRequest(type, itemDesc, "SF", "SF1234567890", null, note);
        }
        return new AdminShipOrderRequest(type, itemDesc, " ", "", null, note);
    }

    private long insertPaidOrder(String receiverPhone) {
        long id = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into app_user(id, openid, status)
                        values (:id, :openid, 'ENABLED')
                        """)
                .param("id", id)
                .param("openid", "openid-local-" + id)
                .update();
        jdbcClient.sql("""
                        insert into shop_order(
                            id, order_no, user_id, status, source, idempotency_key,
                            product_original_amount_cent, product_amount_cent, coupon_name,
                            coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                            receiver_name, receiver_phone, receiver_address,
                            payment_transaction_id, merchant_trade_no, paid_at, created_at, updated_at)
                        values (
                            :id, :orderNo, :id, 'PAID', 'CART', :idempotencyKey,
                            100, 100, '', 0, 0, 100, 100,
                            'Receiver', :receiverPhone, 'Address',
                            :transactionId, :outTradeNo, :now, :now, :now)
                        """)
                .param("id", id)
                .param("orderNo", "LOCAL" + id)
                .param("idempotencyKey", "local-" + id)
                .param("receiverPhone", receiverPhone)
                .param("transactionId", "wx-local-" + id)
                .param("outTradeNo", "mch-local-" + id)
                .param("now", LocalDateTime.of(2026, 7, 10, 10, 0))
                .update();
        return id;
    }

    private void insertCarrier(String id, String name, boolean enabled) {
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values (:id, :name, :enabled, :now)
                        """)
                .param("id", id)
                .param("name", name)
                .param("enabled", enabled)
                .param("now", LocalDateTime.of(2026, 7, 10, 9, 0))
                .update();
    }
}
