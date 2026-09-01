package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.MockWechatPayProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class PaymentTestSupport {

    private static final AtomicLong LIMITED_ADMIN_IDS = new AtomicLong(9_920_000L);

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcClient jdbcClient;

    @Autowired
    protected PaymentSecretCipher paymentSecretCipher;

    @Autowired
    protected PaymentConfigResolver paymentConfigResolver;

    @Autowired
    protected PaymentNotificationRouteService paymentNotificationRouteService;

    @Autowired
    protected MockWechatPayProvider mockWechatPayProvider;

    @Autowired
    protected OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearPaymentFlowState() {
        clearLimitedAdmins();
        clearSeededPaymentFlowState();
    }

    @AfterEach
    void cleanupPaymentFlowState() {
        clearSeededPaymentFlowState();
        clearLimitedAdmins();
    }

    private void clearSeededPaymentFlowState() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from refund_inventory_restock_item").update();
        jdbcClient.sql("delete from refund_provider_attempt").update();
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_status_log").update();
        jdbcClient.sql("delete from after_sale_return").update();
        jdbcClient.sql("delete from after_sale_item").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from merchant_return_address").update();
        jdbcClient.sql("delete from payment_callback_log").update();
        jdbcClient.sql("delete from order_shipment").update();
        jdbcClient.sql("delete from payment_attempt").update();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from stock_lock").update();
        jdbcClient.sql("delete from order_item").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from stock_log").update();
        jdbcClient.sql("delete from user_coupon").update();
        jdbcClient.sql("delete from coupon_claim_record").update();
        jdbcClient.sql("delete from coupon_template").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset where object_key like 'private/payment-flow/%'").update();
        jdbcClient.sql("delete from storage_asset where object_key like 'private/after-sale-flow/%'").update();
        jdbcClient.sql("delete from product_sku").update();
        jdbcClient.sql("delete from product_spu_image").update();
        jdbcClient.sql("delete from product_spu").update();
        jdbcClient.sql("delete from product_category").update();
        mockWechatPayProvider.reset();
    }

    protected AppLoginSession appLogin(String code) throws Exception {
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

    protected String currentWechatpayTimestamp() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    protected void seedEnabledPaymentConfig() {
        seedEnabledPaymentConfig("""
                -----BEGIN PRIVATE KEY-----
                test-private-key-material
                -----END PRIVATE KEY-----
                """, """
                -----BEGIN PUBLIC KEY-----
                test-public-key-material
                -----END PUBLIC KEY-----
                """);
    }

    protected void seedEnabledPaymentConfig(String privateKeyPem, String publicKeyPem) {
        long configId = 91001L;
        PaymentSecretCipher.EncryptedSecret apiV3Key = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.apiV3KeyContext(configId),
                "api_v3_secret_test"
        );
        PaymentSecretCipher.EncryptedSecret privateKey = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.privateKeyPemContext(configId),
                privateKeyPem
        );
        PaymentSecretCipher.EncryptedSecret publicKey = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.wechatPublicKeyPemContext(configId),
                publicKeyPem
        );
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id, secret_revision)
                        values
                            (91001, 'Payment Flow Test', 'wx_payment_test_app', 'mch_payment_test',
                             'serial_payment_test', :apiV3Key, :privateKey, :publicKey, 'PUBLIC_KEY',
                             'pub_key_payment_test', 'https://pay.test/wxpay/pay/notify',
                             'https://pay.test/wxpay/refund/notify', true, 'ACTIVE',
                             :cipherVersion, :keyId, 1)
                        """)
                .param("apiV3Key", apiV3Key.ciphertext())
                .param("privateKey", privateKey.ciphertext())
                .param("publicKey", publicKey.ciphertext())
                .param("cipherVersion", apiV3Key.version())
                .param("keyId", apiV3Key.keyId())
                .update();
    }

    protected void switchToClonedPaymentConfig(long replacementConfigId) {
        PaymentCiphertextRow original = jdbcClient.sql("""
                        select api_v3_key_ciphertext, private_key_pem_ciphertext,
                               wechat_public_key_pem_ciphertext
                        from payment_config where id = 91001
                        """)
                .query((rs, rowNum) -> new PaymentCiphertextRow(
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext")
                ))
                .single();
        PaymentSecretCipher.EncryptedSecret apiV3Key = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.apiV3KeyContext(replacementConfigId),
                paymentSecretCipher.decrypt(
                        org.muybaby.shopserver.payment.config.PaymentConfigResolver.apiV3KeyContext(91001L),
                        original.apiV3KeyCiphertext()
                ).plaintext()
        );
        PaymentSecretCipher.EncryptedSecret privateKey = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.privateKeyPemContext(replacementConfigId),
                paymentSecretCipher.decrypt(
                        org.muybaby.shopserver.payment.config.PaymentConfigResolver.privateKeyPemContext(91001L),
                        original.privateKeyPemCiphertext()
                ).plaintext()
        );
        PaymentSecretCipher.EncryptedSecret publicKey = paymentSecretCipher.encrypt(
                org.muybaby.shopserver.payment.config.PaymentConfigResolver.wechatPublicKeyPemContext(
                        replacementConfigId),
                paymentSecretCipher.decrypt(
                        org.muybaby.shopserver.payment.config.PaymentConfigResolver.wechatPublicKeyPemContext(91001L),
                        original.wechatPublicKeyPemCiphertext()
                ).plaintext()
        );
        jdbcClient.sql("update payment_config set enabled = false where enabled = true").update();
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id, secret_revision)
                        select :replacementConfigId, 'Replacement Payment Flow Test', app_id,
                               concat(mch_id, '_replacement'), merchant_serial_no, :apiV3Key,
                               :privateKey, :publicKey,
                               verify_mode, wechat_public_key_id, notify_url, refund_notify_url,
                               true, status, :cipherVersion, :keyId, secret_revision
                        from payment_config
                        where id = 91001
                        """)
                .param("replacementConfigId", replacementConfigId)
                .param("apiV3Key", apiV3Key.ciphertext())
                .param("privateKey", privateKey.ciphertext())
                .param("publicKey", publicKey.ciphertext())
                .param("cipherVersion", apiV3Key.version())
                .param("keyId", apiV3Key.keyId())
                .update();
    }

    protected SeedOrder seedCreatedOrder(long userId, long payableAmountCent, boolean withCoupon) {
        long suffix = System.nanoTime();
        long categoryId = insertCategory("Payment Category " + suffix);
        long spuId = insertSpu(categoryId, "Payment SPU " + suffix);
        long skuId = insertSku(spuId, "PAY-SKU-" + suffix);
        Long userCouponId = withCoupon ? insertLockedCoupon(userId) : null;

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key, checkout_request_digest,
                             product_original_amount_cent, product_amount_cent, user_coupon_id, coupon_name,
                             coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, 'CREATED', 'CART', :idempotencyKey,
                             'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
                             :originalAmount, :productAmount, :userCouponId, :couponName,
                             :couponDiscount, 0, :payableAmount, 0,
                             'Pay User', '13800000000', 'Pay Test Address',
                             timestamp '2026-07-08 10:00:00', timestamp '2026-07-08 10:00:00')
                        """)
                .param("orderId", suffix)
                .param("orderNo", "PAY" + suffix)
                .param("userId", userId)
                .param("idempotencyKey", "pay-seed-" + suffix)
                .param("originalAmount", payableAmountCent + (withCoupon ? 500L : 0L))
                .param("productAmount", payableAmountCent + (withCoupon ? 500L : 0L))
                .param("userCouponId", userCouponId)
                .param("couponName", withCoupon ? "Pay Coupon" : "")
                .param("couponDiscount", withCoupon ? 500L : 0L)
                .param("payableAmount", payableAmountCent)
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, product_subtitle, main_image,
                             sku_image, display_image, sku_code, spec_text, original_price_cent,
                             unit_price_cent, quantity, line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:orderItemId, :orderId, :skuId, :spuId, 'Payment Item', '',
                             'https://example.test/pay-main.jpg', 'https://example.test/pay-sku.jpg',
                             'https://example.test/pay-sku.jpg', :skuCode, '300g',
                             :lineAmount, :lineAmount, 2, :lineAmount, :lineAmount,
                             timestamp '2026-07-08 10:00:00')
                        """)
                .param("orderItemId", suffix + 1)
                .param("orderId", suffix)
                .param("skuId", skuId)
                .param("spuId", spuId)
                .param("skuCode", "PAY-SKU-" + suffix)
                .param("lineAmount", payableAmountCent + (withCoupon ? 500L : 0L))
                .update();
        jdbcClient.sql("""
                        insert into stock_lock
                            (order_id, order_item_id, sku_id, quantity, status, locked_at, created_at, updated_at)
                        values
                            (:orderId, :orderItemId, :skuId, 2, 'LOCKED',
                             timestamp '2026-07-08 10:00:00', timestamp '2026-07-08 10:00:00',
                             timestamp '2026-07-08 10:00:00')
                        """)
                .param("orderId", suffix)
                .param("orderItemId", suffix + 1)
                .param("skuId", skuId)
                .update();
        if (userCouponId != null) {
            jdbcClient.sql("""
                            update user_coupon
                            set locked_order_id = :orderId, locked_at = timestamp '2026-07-08 10:00:00'
                            where id = :userCouponId
                            """)
                    .param("orderId", suffix)
                    .param("userCouponId", userCouponId)
                    .update();
        }
        return new SeedOrder(suffix, "PAY" + suffix, skuId, userCouponId);
    }

    protected SeedPaidOrder seedPaidOrder(AppLoginSession session, long paidAmountCent, String status, String transactionId) {
        SeedOrder order = seedCreatedOrder(session.userId(), paidAmountCent, false);
        String outTradeNo = "MCH" + order.orderId();
        LocalDateTime paidAt = LocalDateTime.of(2026, 7, 8, 12, 0);
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String configFingerprint = paymentConfigResolver.fingerprint(config);
        String notificationRouteToken = paymentNotificationRouteService.issueToken();
        jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            paid_amount_cent = :paidAmountCent,
                            paid_at = :paidAt,
                            shipped_at = :shippedAt,
                            payment_transaction_id = :transactionId,
                            merchant_trade_no = :outTradeNo,
                            updated_at = current_timestamp
                        where id = :orderId
                        """)
                .param("status", status)
                .param("paidAmountCent", paidAmountCent)
                .param("paidAt", paidAt)
                .param("shippedAt", "SHIPPED".equals(status) ? paidAt.plusHours(2) : null)
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        update stock_lock
                        set status = 'CONFIRMED',
                            updated_at = current_timestamp
                        where order_id = :orderId
                        """)
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, prepay_id, transaction_id,
                             payer_openid, status, amount_cent, request_digest, callback_digest,
                             expires_at, paid_at, created_at, updated_at)
                        values
                            (:orderId, :paymentConfigId, :paymentConfigFingerprint,
                             :notificationRouteToken, :outTradeNo, :prepayId, :transactionId,
                             :openid, 'PAID', :paidAmountCent, 'seed-refund-request-digest',
                             'seed-refund-callback-digest', timestamp '2026-07-08 12:15:00',
                             :paidAt, :paidAt, :paidAt)
                        """)
                .param("orderId", order.orderId())
                .param("paymentConfigId", config.configId())
                .param("paymentConfigFingerprint", configFingerprint)
                .param("notificationRouteToken", notificationRouteToken)
                .param("outTradeNo", outTradeNo)
                .param("prepayId", "mock-prepay-" + outTradeNo)
                .param("transactionId", transactionId)
                .param("openid", session.openid())
                .param("paidAmountCent", paidAmountCent)
                .param("paidAt", paidAt)
                .update();
        mockWechatPayProvider.markOrderPaid(outTradeNo, paidAmountCent, transactionId);
        return new SeedPaidOrder(order.orderId(), order.orderNo(), outTradeNo, transactionId, paidAmountCent);
    }

    protected long insertAppEvidenceFile(long userId, long orderId) {
        return insertStorageAsset(userId, "ATTACHMENT", "IMAGE", "PRIVATE", "ACTIVE", orderId);
    }

    protected long insertStorageAsset(
            long uploadedById,
            String scope,
            String mediaKind,
            String visibility,
            String status,
            Long contextOrderId
    ) {
        long fileId = System.nanoTime();
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id,
                             upload_context_type, upload_context_id, expires_at)
                        values
                            (:fileId, :scope, :mediaKind, :visibility, 'TENCENT_COS', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, :sha256, 1, 1, '', null, null, :status, 'APP', :uploadedById,
                             :uploadContextType, :uploadContextId, :expiresAt)
                        """)
                .param("fileId", fileId)
                .param("scope", scope)
                .param("mediaKind", mediaKind)
                .param("visibility", visibility)
                .param("objectKey", "private/after-sale-flow/" + fileId + ".png")
                .param("originalFilename", "after-sale-" + fileId + ".png")
                .param("sha256", "after-sale-sha256-" + fileId)
                .param("status", status)
                .param("uploadedById", uploadedById)
                .param("uploadContextType", contextOrderId == null ? null : "ORDER")
                .param("uploadContextId", contextOrderId)
                .param("expiresAt", contextOrderId == null ? null : LocalDateTime.now().plusHours(1))
                .update();
        return fileId;
    }

    protected String adminLogin() throws Exception {
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

    protected String limitedAdminToken(List<String> permissions) {
        long adminId = LIMITED_ADMIN_IDS.incrementAndGet();
        String username = "LimitedAfterSaleAdmin" + adminId;
        String roleCode = "R_AFTER_SALE_LIMITED_" + adminId;
        insertLimitedAdmin(adminId, username, roleCode, permissions);
        TokenSession session = TokenSession.admin(adminId, username, List.of(roleCode), permissions, Instant.now());
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private void insertLimitedAdmin(long adminId, String username, String roleCode, List<String> permissions) {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:adminId, :username, :passwordHash, :username, :email, 'ENABLED')
                        """)
                .param("adminId", adminId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("email", "limited-after-sale-" + adminId + "@shop.test")
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, :roleCode, :roleCode, '', true)
                        """)
                .param("roleId", adminId)
                .param("roleCode", roleCode)
                .update();
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:adminId, :roleId)")
                .param("adminId", adminId)
                .param("roleId", adminId)
                .update();
        for (String permission : permissions) {
            Long permissionId = jdbcClient.sql("select id from admin_permission where auth_mark = :permission")
                    .param("permission", permission)
                    .query(Long.class)
                    .single();
            jdbcClient.sql("""
                            insert into admin_role_permission (role_id, permission_id)
                            values (:roleId, :permissionId)
                            """)
                    .param("roleId", adminId)
                    .param("permissionId", permissionId)
                    .update();
        }
    }

    private void clearLimitedAdmins() {
        jdbcClient.sql("delete from admin_role_permission where role_id between 9920001 and 9929999").update();
        jdbcClient.sql("delete from admin_user_role where role_id between 9920001 and 9929999").update();
        jdbcClient.sql("delete from admin_role where id between 9920001 and 9929999").update();
        jdbcClient.sql("delete from admin_user where id between 9920001 and 9929999").update();
    }

    protected String pay(String token, long orderId) throws Exception {
        return mockMvc.perform(post("/app/orders/{orderId}/pay", orderId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    protected String activeOutTradeNo(long orderId) {
        return jdbcClient.sql("""
                        select out_trade_no
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAYING'
                        order by id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .single();
    }

    protected void insertExpiredPayingPayment(SeedOrder order, String outTradeNo, String openid, long amountCent) {
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String configFingerprint = paymentConfigResolver.fingerprint(config);
        String notificationRouteToken = paymentNotificationRouteService.issueToken();
        jdbcClient.sql("""
                        update shop_order
                        set status = 'PAYING', merchant_trade_no = :outTradeNo, updated_at = current_timestamp
                        where id = :orderId
                        """)
                .param("outTradeNo", outTradeNo)
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, prepay_id, payer_openid, status,
                             amount_cent, request_digest, expires_at, created_at, updated_at)
                        values
                            (:orderId, :paymentConfigId, :paymentConfigFingerprint,
                             :notificationRouteToken, :outTradeNo, :prepayId, :openid, 'PAYING',
                             :amountCent, 'seed-digest', timestamp '2026-07-07 09:00:00',
                             timestamp '2026-07-07 08:45:00', timestamp '2026-07-07 08:45:00')
                        """)
                .param("orderId", order.orderId())
                .param("paymentConfigId", config.configId())
                .param("paymentConfigFingerprint", configFingerprint)
                .param("notificationRouteToken", notificationRouteToken)
                .param("outTradeNo", outTradeNo)
                .param("prepayId", "mock-prepay-" + outTradeNo)
                .param("openid", openid)
                .param("amountCent", amountCent)
                .update();
        Long paymentOrderId = jdbcClient.sql("select id from payment_order where out_trade_no = :outTradeNo")
                .param("outTradeNo", outTradeNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into payment_attempt
                            (order_id, payment_order_id, out_trade_no, status, amount_cent,
                             started_at, prepay_succeeded_at, created_at, updated_at)
                        values
                            (:orderId, :paymentOrderId, :outTradeNo, 'PREPAY_SUCCEEDED', :amountCent,
                             timestamp '2026-07-07 08:44:00', timestamp '2026-07-07 08:45:00',
                             timestamp '2026-07-07 08:44:00', timestamp '2026-07-07 08:45:00')
                        """)
                .param("orderId", order.orderId())
                .param("paymentOrderId", paymentOrderId)
                .param("outTradeNo", outTradeNo)
                .param("amountCent", amountCent)
                .update();
    }

    private long insertCategory(String name) {
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 1, 'ENABLED')
                        """)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from product_category where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertSpu(long categoryId, String title) {
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html, sort_order, status)
                        values
                            (:categoryId, :title, '', 'https://example.test/pay-main.jpg', 'pay', '<p>pay</p>', 1, 'ON_SALE')
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .update();
        return jdbcClient.sql("select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private long insertSku(long spuId, String skuCode) {
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order, combination_key)
                        values
                            (:spuId, :skuCode, '{"规格":"300g"}', :specText, 3990, 3990,
                             8, 300, 'https://example.test/pay-sku.jpg', 'ENABLED', 1, :skuCode)
                        """)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("specText", "300g-" + skuCode)
                .update();
        return jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();
    }

    private long insertLockedCoupon(long userId) {
        String templateName = "Pay Coupon " + System.nanoTime();
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, description, coupon_type, discount_type, threshold_cent, discount_cent,
                             scope_type, scope_value, strategy_key, total_stock, claimed_count, per_user_limit,
                             valid_start_at, valid_end_at, status, sort_order)
                        values
                            (:name, 'pay coupon', 'NO_THRESHOLD', 'AMOUNT_OFF', 0, 500,
                             'ALL', '', 'coupon.amount-off.v1', 10, 1, 1,
                             timestamp '2026-07-01 00:00:00', timestamp '2026-08-01 23:59:59',
                             'ENABLED', 1)
                        """)
                .param("name", templateName)
                .update();
        long templateId = jdbcClient.sql("select id from coupon_template where name = :name")
                .param("name", templateName)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into user_coupon
                            (user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value, valid_start_at,
                             valid_end_at, status, claimed_at)
                        values
                            (:userId, :templateId, :templateName, 'NO_THRESHOLD', 'AMOUNT_OFF',
                             0, 500, 'ALL', '', timestamp '2026-07-01 00:00:00',
                             timestamp '2026-08-01 23:59:59', 'LOCKED', timestamp '2026-07-08 09:00:00')
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .param("templateName", templateName)
                .update();
        return jdbcClient.sql("""
                        select id
                        from user_coupon
                        where user_id = :userId and template_id = :templateId
                        """)
                .param("userId", userId)
                .param("templateId", templateId)
                .query(Long.class)
                .single();
    }

    protected record AppLoginSession(String token, long userId, String openid) {
    }

    protected record SeedOrder(long orderId, String orderNo, long skuId, Long userCouponId) {
    }

    protected record SeedPaidOrder(long orderId, String orderNo, String outTradeNo, String transactionId, long paidAmountCent) {
    }

    private record PaymentCiphertextRow(
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String wechatPublicKeyPemCiphertext
    ) {
    }
}
