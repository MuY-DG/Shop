package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminPaymentConfigControllerTest {

    private static final AtomicLong LIMITED_ADMIN_IDS = new AtomicLong(9_910_000L);
    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef"; // gitleaks:allow
    private static final String REPLACEMENT_API_V3_KEY = "fedcba9876543210fedcba9876543210"; // gitleaks:allow
    private static final KeyPair PRIMARY_KEY_PAIR = rsaKeyPair(2048);
    private static final KeyPair REPLACEMENT_KEY_PAIR = rsaKeyPair(2048);
    private static final String PRIVATE_KEY_PEM = pem("PRIVATE KEY", PRIMARY_KEY_PAIR.getPrivate().getEncoded());
    private static final String PUBLIC_KEY_PEM = pem("PUBLIC KEY", PRIMARY_KEY_PAIR.getPublic().getEncoded());
    private static final String REPLACEMENT_PRIVATE_KEY_PEM =
            pem("PRIVATE KEY", REPLACEMENT_KEY_PAIR.getPrivate().getEncoded());
    private static final String REPLACEMENT_PUBLIC_KEY_PEM =
            pem("PUBLIC KEY", REPLACEMENT_KEY_PAIR.getPublic().getEncoded());
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private PaymentSecretCipher paymentSecretCipher;

    @Autowired
    private PaymentConfigResolver paymentConfigResolver;

    @BeforeEach
    void clearPaymentConfigState() {
        clearLimitedAdmins();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset").update();
    }

    @AfterEach
    void clearLimitedAdminState() {
        clearLimitedAdmins();
    }

    @Test
    void createEncryptsAllSecretBodiesWithoutCreatingStorageAssetsOrReturningSecretMaterial() throws Exception {
        String token = limitedAdminToken(List.of("payment:config:write"));

        String response = createConfigResponse(token, "Main DB Pay", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        JsonNode data = objectMapper.readTree(response).path("data");
        long configId = data.path("id").asLong();

        assertThat(data.path("apiV3KeyConfigured").asBoolean()).isTrue();
        assertThat(data.path("privateKeyConfigured").asBoolean()).isTrue();
        assertThat(data.path("wechatPublicKeyConfigured").asBoolean()).isTrue();
        assertSecretMaterialIsAbsent(response);

        ConfigSecretRow row = configSecretRow(configId);
        assertThat(row.apiV3KeyCiphertext()).isNotBlank().doesNotContain(API_V3_KEY);
        assertThat(row.privateKeyPemCiphertext()).isNotBlank().doesNotContain("BEGIN PRIVATE KEY");
        assertThat(row.publicKeyPemCiphertext()).isNotBlank().doesNotContain("BEGIN PUBLIC KEY");
        assertThat(decrypt(PaymentConfigResolver.apiV3KeyContext(configId), row)).isEqualTo(API_V3_KEY);
        assertThat(decrypt(PaymentConfigResolver.privateKeyPemContext(configId), row.privateKeyPemCiphertext(), row))
                .isEqualTo(PRIVATE_KEY_PEM);
        assertThat(decrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(configId), row.publicKeyPemCiphertext(), row))
                .isEqualTo(PUBLIC_KEY_PEM);
        assertThat(jdbcClient.sql("select count(*) from storage_asset").query(Integer.class).single()).isZero();
    }

    @Test
    void createRejectsNon32ByteApiV3KeyMalformedPemAndWeakRsaKeys() throws Exception {
        String token = limitedAdminToken(List.of("payment:config:write"));
        Map<String, Object> shortApiKey = validPayload("Bad API key", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        shortApiKey.put("apiV3Key", "31-bytes-is-not-an-api-v3-key!!");
        performCreate(token, shortApiKey)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        Map<String, Object> malformedPem = validPayload("Bad PEM", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        malformedPem.put("privateKeyPem", PRIVATE_KEY_PEM + "unexpected suffix");
        performCreate(token, malformedPem)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        KeyPair weakPair = rsaKeyPair(1024);
        Map<String, Object> weakRsa = validPayload(
                "Weak RSA",
                pem("PRIVATE KEY", weakPair.getPrivate().getEncoded()),
                pem("PUBLIC KEY", weakPair.getPublic().getEncoded())
        );
        performCreate(token, weakRsa)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        assertThat(jdbcClient.sql("select count(*) from payment_config").query(Integer.class).single()).isZero();
    }

    @Test
    void deleteSoftDeletesDisabledConfigPreservesHistoricalSecretsAndRejectsASecondDelete() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String deleteToken = limitedAdminToken(List.of("payment:config:delete"));
        long deletingAdminId = LIMITED_ADMIN_IDS.get();
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        long configId = createConfig(writeToken, "Delete Historical DB Pay");
        ResolvedPaymentConfig before = paymentConfigResolver.resolveForPaymentConfigId(configId);
        String fingerprint = paymentConfigResolver.fingerprint(before);
        insertHistoricalPaymentReference(configId, fingerprint);
        ConfigSecretRow secretBefore = configSecretRow(configId);

        String response = mockMvc.perform(delete("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + deleteToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(response);

        DeletedConfigRow deleted = deletedConfigRow(configId);
        assertThat(deleted.status()).isEqualTo("DELETED");
        assertThat(deleted.enabled()).isFalse();
        assertThat(deleted.deletedAt()).isNotNull();
        assertThat(deleted.deletedBy()).isEqualTo(deletingAdminId);
        assertThat(configSecretRow(configId)).isEqualTo(secretBefore);
        assertThat(paymentConfigResolver.fingerprint(
                paymentConfigResolver.resolveForPayment(configId, fingerprint))).isEqualTo(fingerprint);

        mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());

        mockMvc.perform(delete("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + deleteToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_CONFIG_UNAVAILABLE.code()));
    }

    @Test
    void deleteRequiresDedicatedAuthorityAndRejectsEnabledConfig() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        String deleteToken = limitedAdminToken(List.of("payment:config:delete"));
        long configId = createConfig(writeToken, "Enabled Delete Guard");

        mockMvc.perform(delete("/admin/pay/configs/{configId}", configId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(delete("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + deleteToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.PAYMENT_CONFIG_ENABLED_DELETE_FORBIDDEN.code()));

        DeletedConfigRow retained = deletedConfigRow(configId);
        assertThat(retained.status()).isEqualTo("ACTIVE");
        assertThat(retained.enabled()).isTrue();
        assertThat(retained.deletedAt()).isNull();
        assertThat(retained.deletedBy()).isNull();
    }

    @Test
    void updatePreservesOmittedSecretsAndReplacesSelectedPemBodiesWithoutFileIds() throws Exception {
        String token = limitedAdminToken(List.of("payment:config:write"));
        long configId = createConfig(token, "Editable DB Pay");

        Map<String, Object> preserve = validPayload("Editable DB Pay Updated", "", "");
        preserve.put("appId", "");
        preserve.put("mchId", "");
        preserve.put("merchantSerialNo", "");
        preserve.put("apiV3Key", "");
        preserve.put("wechatPublicKeyId", "");
        preserve.put("notifyUrl", "https://pay.example.test/wxpay/pay/notify-updated");
        updateConfig(token, configId, preserve)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.wechatPublicKeyConfigured").value(true));

        ConfigSecretRow preserved = configSecretRow(configId);
        assertThat(decrypt(PaymentConfigResolver.apiV3KeyContext(configId), preserved)).isEqualTo(API_V3_KEY);
        assertThat(decrypt(
                PaymentConfigResolver.privateKeyPemContext(configId), preserved.privateKeyPemCiphertext(), preserved))
                .isEqualTo(PRIVATE_KEY_PEM);
        assertThat(decrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(configId), preserved.publicKeyPemCiphertext(), preserved))
                .isEqualTo(PUBLIC_KEY_PEM);

        Map<String, Object> replacement = validPayload(
                "Editable DB Pay Updated",
                REPLACEMENT_PRIVATE_KEY_PEM,
                REPLACEMENT_PUBLIC_KEY_PEM
        );
        replacement.put("appId", "");
        replacement.put("mchId", "");
        replacement.put("merchantSerialNo", "");
        replacement.put("apiV3Key", REPLACEMENT_API_V3_KEY);
        replacement.put("wechatPublicKeyId", "");
        String response = updateConfig(token, configId, replacement)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(response);

        ConfigSecretRow replaced = configSecretRow(configId);
        assertThat(decrypt(PaymentConfigResolver.apiV3KeyContext(configId), replaced))
                .isEqualTo(REPLACEMENT_API_V3_KEY);
        assertThat(decrypt(
                PaymentConfigResolver.privateKeyPemContext(configId), replaced.privateKeyPemCiphertext(), replaced))
                .isEqualTo(REPLACEMENT_PRIVATE_KEY_PEM);
        assertThat(decrypt(
                PaymentConfigResolver.wechatPublicKeyPemContext(configId), replaced.publicKeyPemCiphertext(), replaced))
                .isEqualTo(REPLACEMENT_PUBLIC_KEY_PEM);
    }

    @Test
    void concurrentEnableAndDeleteLinearizeWithoutInvalidStateOrSecretLoss() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        String deleteToken = limitedAdminToken(List.of("payment:config:delete"));
        long configId = createConfig(writeToken, "Concurrent Enable Delete");
        ConfigSecretRow secretsBefore = configSecretRow(configId);
        CountDownLatch start = new CountDownLatch(1);

        List<ImportResult> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ImportResult> enable = executor.submit(() -> enableAfter(start, enableToken, configId));
            Future<ImportResult> delete = executor.submit(() -> deleteAfter(start, deleteToken, configId));
            start.countDown();
            results = List.of(
                    enable.get(10, TimeUnit.SECONDS),
                    delete.get(10, TimeUnit.SECONDS)
            );
        }

        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isIn(200, 400, 409);
            assertThat(result.status()).isLessThan(500);
            assertSecretMaterialIsAbsent(result.body());
        });
        assertThat(results.stream().filter(result -> result.status() == 200).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result.status() >= 400).count()).isEqualTo(1);

        DeletedConfigRow finalState = deletedConfigRow(configId);
        boolean enabledWon = finalState.status().equals("ACTIVE")
                && finalState.enabled()
                && finalState.deletedAt() == null
                && finalState.deletedBy() == null;
        boolean deleteWon = finalState.status().equals("DELETED")
                && !finalState.enabled()
                && finalState.deletedAt() != null
                && finalState.deletedBy() != null;
        assertThat(enabledWon || deleteWon).isTrue();
        assertThat(configSecretRow(configId)).isEqualTo(secretsBefore);
    }

    @Test
    void missingEffectiveConfigReturnsExplicitUnavailableStateAndStillAllowsFirstDbConfigCreation() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.config").doesNotExist());

        createConfigResponse(writeToken, "First DB Pay", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].configName").value("First DB Pay"));
    }

    @Test
    void enablingConfigMakesDatabaseCiphertextEffectiveWithoutExposingBodies() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        long configId = createConfig(writeToken, "Switchable DB Pay");

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        String response = mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.config.id").value(configId))
                .andExpect(jsonPath("$.data.config.privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.config.wechatPublicKeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(response);

        jdbcClient.sql("""
                        update payment_config set private_key_pem_ciphertext = 'damaged'
                        where id = :configId
                        """)
                .param("configId", configId)
                .update();
        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PAYMENT_CONFIGURATION_CHANGED.code()));
    }

    @Test
    void createEndpointRequiresWriteAuthority() throws Exception {
        mockMvc.perform(post("/admin/pay/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload(
                                "Unauthorized", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcClient.sql("select count(*) from storage_asset").query(Integer.class).single()).isZero();
    }

    @Test
    void databaseListResponsesRemainMasked() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        createConfig(writeToken, "Masked DB Pay");

        String page = mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].apiV3KeyConfigured").value(true))
                .andExpect(jsonPath("$.data.records[0].privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.records[0].wechatPublicKeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(page);
    }

    private long createConfig(String token, String configName) throws Exception {
        String response = createConfigResponse(token, configName, PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private ImportResult enableAfter(CountDownLatch start, String token, long configId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        var response = mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
        return new ImportResult(response.getStatus(), response.getContentAsString());
    }

    private ImportResult deleteAfter(CountDownLatch start, String token, long configId) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        var response = mockMvc.perform(delete("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
        return new ImportResult(response.getStatus(), response.getContentAsString());
    }

    private String createConfigResponse(
            String token,
            String configName,
            String privateKeyPem,
            String publicKeyPem
    ) throws Exception {
        return performCreate(token, validPayload(configName, privateKeyPem, publicKeyPem))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.source").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            String token,
            Map<String, Object> payload
    ) throws Exception {
        return mockMvc.perform(post("/admin/pay/configs")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private org.springframework.test.web.servlet.ResultActions updateConfig(
            String token,
            long configId,
            Map<String, Object> payload
    ) throws Exception {
        return mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private Map<String, Object> validPayload(String configName, String privateKeyPem, String publicKeyPem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("configName", configName);
        payload.put("appId", "wx_db_app_" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("mchId", "mch_db_123456");
        payload.put("merchantSerialNo", "serial_db_" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("apiV3Key", API_V3_KEY);
        payload.put("privateKeyPem", privateKeyPem);
        payload.put("verifyMode", "PUBLIC_KEY");
        payload.put("wechatPublicKeyId", "pub_key_db_123456");
        payload.put("wechatPublicKeyPem", publicKeyPem);
        payload.put("notifyUrl", "https://pay.example.test/wxpay/pay/notify");
        payload.put("refundNotifyUrl", "https://pay.example.test/wxpay/refund/notify");
        return payload;
    }

    private void insertHistoricalPaymentReference(long configId, String fingerprint) {
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no,
                             payer_openid, status, amount_cent, currency, expires_at)
                        values
                            (:orderId, :configId, :fingerprint, :routeToken, :outTradeNo,
                             'openid-history', 'PAYING', 100, 'CNY', current_timestamp)
                        """)
                .param("orderId", configId)
                .param("configId", configId)
                .param("fingerprint", fingerprint)
                .param("routeToken", org.muybaby.shopserver.support.PaymentFixtureIdentity.routeToken(configId))
                .param("outTradeNo", "HISTORY-" + configId)
                .update();
    }

    private ConfigSecretRow configSecretRow(long configId) {
        return jdbcClient.sql("""
                        select api_v3_key_ciphertext, private_key_pem_ciphertext,
                               wechat_public_key_pem_ciphertext,
                               secret_cipher_version, secret_key_id, secret_revision,
                               secret_reencrypted_at
                        from payment_config where id = :configId
                        """)
                .param("configId", configId)
                .query((rs, rowNum) -> new ConfigSecretRow(
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id"),
                        rs.getLong("secret_revision"),
                        rs.getObject("secret_reencrypted_at", LocalDateTime.class)
                ))
                .single();
    }

    private DeletedConfigRow deletedConfigRow(long configId) {
        return jdbcClient.sql("""
                        select status, enabled, deleted_at, deleted_by
                        from payment_config where id = :configId
                        """)
                .param("configId", configId)
                .query((rs, rowNum) -> new DeletedConfigRow(
                        rs.getString("status"),
                        rs.getBoolean("enabled"),
                        rs.getObject("deleted_at", LocalDateTime.class),
                        nullableLong(rs, "deleted_by")
                ))
                .single();
    }

    private String decrypt(PaymentSecretCipher.SecretContext context, ConfigSecretRow row) {
        return decrypt(context, row.apiV3KeyCiphertext(), row);
    }

    private String decrypt(
            PaymentSecretCipher.SecretContext context,
            String ciphertext,
            ConfigSecretRow row
    ) {
        PaymentSecretCipher.DecryptedSecret decrypted = paymentSecretCipher.decrypt(context, ciphertext);
        assertThat(decrypted.version()).isEqualTo(row.cipherVersion());
        assertThat(decrypted.keyId()).isEqualTo(row.keyId());
        return decrypted.plaintext();
    }

    private void assertSecretMaterialIsAbsent(String response) {
        assertThat(response)
                .doesNotContain(API_V3_KEY)
                .doesNotContain(REPLACEMENT_API_V3_KEY)
                .doesNotContain("BEGIN PRIVATE KEY")
                .doesNotContain("BEGIN PUBLIC KEY")
                .doesNotContain("apiV3KeyCiphertext")
                .doesNotContain("privateKeyPemCiphertext")
                .doesNotContain("wechatPublicKeyPemCiphertext")
                .doesNotContain("privateKeyPem\"")
                .doesNotContain("wechatPublicKeyPem\"");
    }

    private String limitedAdminToken(List<String> permissions) {
        long adminId = LIMITED_ADMIN_IDS.incrementAndGet();
        String username = "LimitedPaymentAdmin" + adminId;
        String roleCode = "R_PAYMENT_LIMITED_" + adminId;
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
                .param("email", "limited-payment-" + adminId + "@shop.test")
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
            jdbcClient.sql("insert into admin_role_permission (role_id, permission_id) values (:roleId, :id)")
                    .param("roleId", adminId)
                    .param("id", permissionId)
                    .update();
        }
    }

    private void clearLimitedAdmins() {
        jdbcClient.sql("delete from admin_role_permission where role_id between 9910001 and 9999999").update();
        jdbcClient.sql("delete from admin_user_role where role_id between 9910001 and 9999999").update();
        jdbcClient.sql("delete from admin_role where id between 9910001 and 9999999").update();
        jdbcClient.sql("delete from admin_user where id between 9910001 and 9999999").update();
    }

    private static KeyPair rsaKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String pem(String label, byte[] encoded) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record ConfigSecretRow(
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String publicKeyPemCiphertext,
            int cipherVersion,
            String keyId,
            long secretRevision,
            LocalDateTime secretReencryptedAt
    ) {
    }

    private record DeletedConfigRow(
            String status,
            boolean enabled,
            LocalDateTime deletedAt,
            Long deletedBy
    ) {
    }

    private record ImportResult(int status, String body) {
    }
}
