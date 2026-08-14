package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
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
import static org.hamcrest.Matchers.startsWith;
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
    private static final AtomicLong LEGACY_CONFIG_IDS = new AtomicLong(8_800_000L);
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
    private static Path envPrivateKeyPath;
    private static Path envPublicKeyPath;

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

    @Autowired
    private StorageProvider storageProvider;

    @BeforeAll
    static void writeEnvPaymentFiles() throws Exception {
        Path envDir = Files.createTempDirectory("shop-pay-admin-env");
        envPrivateKeyPath = envDir.resolve("merchant-private.pem");
        envPublicKeyPath = envDir.resolve("wechat-public.pem");
        Files.writeString(envPrivateKeyPath, PRIVATE_KEY_PEM, StandardCharsets.UTF_8);
        Files.writeString(envPublicKeyPath, PUBLIC_KEY_PEM, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void envPaymentProperties(DynamicPropertyRegistry registry) {
        registry.add("shop.pay.config-source", () -> "ENV");
        registry.add("shop.pay.app-id", () -> "wx_env_app_123456");
        registry.add("shop.pay.mch-id", () -> "mch_env_123456");
        registry.add("shop.pay.merchant-serial-no", () -> "serial_env_123456");
        registry.add("shop.pay.private-key-path", () -> envPrivateKeyPath.toString());
        registry.add("shop.pay.api-v3-key", () -> API_V3_KEY);
        registry.add("shop.pay.notify-url", () -> "https://pay.example.test/wxpay/pay/notify");
        registry.add("shop.pay.refund-notify-url", () -> "https://pay.example.test/wxpay/refund/notify");
        registry.add("shop.pay.verify-mode", () -> "PUBLIC_KEY");
        registry.add("shop.pay.public-key-id", () -> "pub_key_env_123456");
        registry.add("shop.pay.public-key-path", () -> envPublicKeyPath.toString());
    }

    @BeforeEach
    void clearPaymentConfigState() {
        clearLimitedAdmins();
        jdbcClient.sql("delete from payment_order").update();
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from payment_runtime_setting").update();
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
        assertThat(data.path("legacySecretFilesPendingImport").asBoolean()).isFalse();
        assertSecretMaterialIsAbsent(response);

        ConfigSecretRow row = configSecretRow(configId);
        assertThat(row.apiV3KeyCiphertext()).isNotBlank().doesNotContain(API_V3_KEY);
        assertThat(row.privateKeyPemCiphertext()).isNotBlank().doesNotContain("BEGIN PRIVATE KEY");
        assertThat(row.publicKeyPemCiphertext()).isNotBlank().doesNotContain("BEGIN PUBLIC KEY");
        assertThat(row.privateKeyFileId()).isNull();
        assertThat(row.merchantCertificateFileId()).isNull();
        assertThat(row.publicKeyFileId()).isNull();
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
        assertThat(replaced.privateKeyFileId()).isNull();
        assertThat(replaced.publicKeyFileId()).isNull();
    }

    @Test
    void authorizedLegacyImportPreservesHistoricalFingerprintAndReleasesAllOldFiles() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long configId = insertLegacyConfig(true);
        ResolvedPaymentConfig before = paymentConfigResolver.resolveForPaymentConfigId(configId);
        String fingerprint = paymentConfigResolver.fingerprint(before);
        insertHistoricalPaymentReference(configId, fingerprint);

        String response = mockMvc.perform(post(
                                "/admin/pay/configs/{configId}/import-legacy-secret-files", configId)
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.wechatPublicKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.legacySecretFilesPendingImport").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(response);

        ConfigSecretRow row = configSecretRow(configId);
        assertThat(row.privateKeyPemCiphertext()).isNotBlank();
        assertThat(row.publicKeyPemCiphertext()).isNotBlank();
        assertThat(row.privateKeyFileId()).isNull();
        assertThat(row.merchantCertificateFileId()).isNull();
        assertThat(row.publicKeyFileId()).isNull();
        assertThat(paymentConfigResolver.fingerprint(paymentConfigResolver.resolveForPaymentConfigId(configId)))
                .isEqualTo(fingerprint);
        assertThat(paymentConfigResolver.resolveForPayment(configId, fingerprint).privateKeyPem())
                .isEqualTo(PRIVATE_KEY_PEM);
        assertThat(jdbcClient.sql("""
                        select count(*) from storage_asset_usage
                        where owner_type = 'PAYMENT_CONFIG' and owner_id = :configId and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .query(Integer.class)
                .single()).isZero();
        List<LocalDateTime> releaseTimes = jdbcClient.sql("""
                        select expires_at from storage_asset where object_key like :prefix order by id
                        """)
                .param("prefix", "test/legacy-" + configId + "/%")
                .query(LocalDateTime.class)
                .list();
        assertThat(releaseTimes).hasSize(3).allSatisfy(releaseAt ->
                assertThat(releaseAt).isAfter(databaseNow().plusHours(23)));
    }

    @Test
    void environmentImportCreatesDisabledDbConfigWithoutChangingEffectiveSourceAndRejectsDuplicates() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String readToken = limitedAdminToken(List.of("payment:config:read"));

        String response = mockMvc.perform(post("/admin/pay/configs/import-environment")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DB"))
                .andExpect(jsonPath("$.data.configName").value("Environment"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.wechatPublicKeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(response);
        long configId = objectMapper.readTree(response).path("data").path("id").asLong();
        ConfigSecretRow row = configSecretRow(configId);
        assertThat(decrypt(PaymentConfigResolver.apiV3KeyContext(configId), row)).isEqualTo(API_V3_KEY);

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.config.source").value("ENV"))
                .andExpect(jsonPath("$.data.config.id").doesNotExist());

        mockMvc.perform(post("/admin/pay/configs/import-environment")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_STATE_CONFLICT.code()));
        assertThat(jdbcClient.sql("select count(*) from payment_config").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentEnvironmentImportsCreateExactlyOneConfig() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ImportResult> first = executor.submit(() -> importEnvironmentAfter(start, writeToken));
            Future<ImportResult> second = executor.submit(() -> importEnvironmentAfter(start, writeToken));
            start.countDown();

            List<ImportResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(ImportResult::status)
                    .containsExactlyInAnyOrder(200, 400);
            ImportResult conflict = results.stream()
                    .filter(result -> result.status() == 400)
                    .findFirst()
                    .orElseThrow();
            assertThat(objectMapper.readTree(conflict.body()).path("code").asInt())
                    .isEqualTo(ErrorCode.ORDER_STATE_CONFLICT.code());
        }

        assertThat(jdbcClient.sql("select count(*) from payment_config").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void concurrentFirstSourceWritesShareTheCheckpointLockWithoutDuplicateInsert() throws Exception {
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ImportResult> first = executor.submit(() -> updateSourceAfter(start, enableToken, "ENV"));
            Future<ImportResult> second = executor.submit(() -> updateSourceAfter(start, enableToken, "AUTO"));
            start.countDown();

            List<ImportResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(ImportResult::status)
                    .containsExactly(200, 200);
            assertThat(results).extracting(ImportResult::body)
                    .allSatisfy(body -> assertThat(body).contains("\"persisted\":true"));
        }

        assertThat(jdbcClient.sql("select count(*) from payment_runtime_setting where id = 1")
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select config_source from payment_runtime_setting where id = 1")
                .query(String.class)
                .single()).isIn("ENV", "AUTO");
    }

    @Test
    void missingEffectiveConfigReturnsExplicitUnavailableStateAndStillAllowsFirstDbConfigCreation() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        jdbcClient.sql("insert into payment_runtime_setting (id, config_source) values (1, 'DB')").update();

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
    void enableAndSourceSwitchUseDbCiphertextWithoutExposingBodies() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        long configId = createConfig(writeToken, "Switchable DB Pay");

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + enableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"DB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DB"));

        String response = mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.config.id").value(configId))
                .andExpect(jsonPath("$.data.config.source").value("DB"))
                .andExpect(jsonPath("$.data.config.privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.config.wechatPublicKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.config.legacySecretFilesPendingImport").value(false))
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
    void writeEndpointsRequireAuthorityAndTheOldUploadEndpointNoLongerExists() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));

        mockMvc.perform(post("/admin/pay/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload(
                                "Unauthorized", PRIVATE_KEY_PEM, PUBLIC_KEY_PEM))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/pay/configs/import-environment")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/pay/configs/{configId}/import-legacy-secret-files", 1)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/pay/configs/secret-files")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbcClient.sql("select count(*) from storage_asset").query(Integer.class).single()).isZero();
    }

    @Test
    void readResponsesRemainMaskedForEnvironmentAndDatabaseLists() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        createConfig(writeToken, "Masked DB Pay");

        String environment = mockMvc.perform(get("/admin/pay/configs/environment")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.config.appIdMasked").value(startsWith("wx_")))
                .andExpect(jsonPath("$.data.config.privateKeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String page = mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].apiV3KeyConfigured").value(true))
                .andExpect(jsonPath("$.data.records[0].privateKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.records[0].wechatPublicKeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertSecretMaterialIsAbsent(environment);
        assertSecretMaterialIsAbsent(page);
    }

    private long createConfig(String token, String configName) throws Exception {
        String response = createConfigResponse(token, configName, PRIVATE_KEY_PEM, PUBLIC_KEY_PEM);
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private ImportResult importEnvironmentAfter(CountDownLatch start, String token) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        var response = mockMvc.perform(post("/admin/pay/configs/import-environment")
                        .header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
        return new ImportResult(response.getStatus(), response.getContentAsString());
    }

    private ImportResult updateSourceAfter(CountDownLatch start, String token, String source) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        var response = mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"" + source + "\"}"))
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
                .andExpect(jsonPath("$.data.source").value("DB"))
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

    private long insertLegacyConfig(boolean enabled) {
        long configId = LEGACY_CONFIG_IDS.incrementAndGet();
        long privateKeyFileId = insertLegacyStorageAsset(configId, "merchant-private.pem", PRIVATE_KEY_PEM);
        long certificateFileId = insertLegacyStorageAsset(configId, "merchant-certificate.pem", "legacy-certificate");
        long publicKeyFileId = insertLegacyStorageAsset(configId, "wechat-public.pem", PUBLIC_KEY_PEM);
        PaymentSecretCipher.EncryptedSecret encryptedApiV3Key = paymentSecretCipher.encrypt(
                PaymentConfigResolver.apiV3KeyContext(configId), API_V3_KEY);
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no, api_v3_key_ciphertext,
                             private_key_pem_ciphertext, wechat_public_key_pem_ciphertext,
                             private_key_file_id, merchant_certificate_file_id, verify_mode,
                             wechat_public_key_id, wechat_public_key_file_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id, secret_revision)
                        values
                            (:id, :configName, 'wx_legacy_app', 'mch_legacy', 'serial_legacy', :apiV3, '', '',
                             :privateKeyFileId, :certificateFileId, 'PUBLIC_KEY', 'pub_key_legacy',
                             :publicKeyFileId, 'https://pay.example.test/wxpay/pay/notify',
                             'https://pay.example.test/wxpay/refund/notify', :enabled, 'ACTIVE',
                             :cipherVersion, :keyId, 1)
                        """)
                .param("id", configId)
                .param("configName", "Legacy Pay " + configId)
                .param("apiV3", encryptedApiV3Key.ciphertext())
                .param("privateKeyFileId", privateKeyFileId)
                .param("certificateFileId", certificateFileId)
                .param("publicKeyFileId", publicKeyFileId)
                .param("enabled", enabled)
                .param("cipherVersion", encryptedApiV3Key.version())
                .param("keyId", encryptedApiV3Key.keyId())
                .update();
        for (long fileId : List.of(privateKeyFileId, certificateFileId, publicKeyFileId)) {
            jdbcClient.sql("""
                            insert into storage_asset_usage
                                (asset_id, usage_type, owner_type, owner_id, owner_label, protected, status)
                            values
                                (:fileId, 'PAYMENT_CONFIG_CERT', 'PAYMENT_CONFIG', :configId,
                                 :ownerLabel, true, 'ACTIVE')
                            """)
                    .param("fileId", fileId)
                    .param("configId", configId)
                    .param("ownerLabel", "Legacy Pay " + configId)
                    .update();
        }
        jdbcClient.sql("""
                        update storage_asset set expires_at = null
                        where id in (:privateKeyFileId, :certificateFileId, :publicKeyFileId)
                        """)
                .param("privateKeyFileId", privateKeyFileId)
                .param("certificateFileId", certificateFileId)
                .param("publicKeyFileId", publicKeyFileId)
                .update();
        return configId;
    }

    private long insertLegacyStorageAsset(long configId, String filename, String content) {
        String objectKey = "test/legacy-" + configId + "/" + filename;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(objectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, status,
                             uploaded_by_type, uploaded_by_id, expires_at)
                        values
                            ('SECRET', 'DOCUMENT', 'PRIVATE', 'TENCENT_COS', '', :objectKey,
                             :filename, 'text/plain', 'pem', :sizeBytes, :sha256, 'ACTIVE',
                             'ADMIN', 1, :expiresAt)
                        """)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .param("sizeBytes", bytes.length)
                .param("sha256", sha256(bytes))
                .param("expiresAt", LocalDateTime.now().plusHours(2))
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private void insertHistoricalPaymentReference(long configId, String fingerprint) {
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint, out_trade_no,
                             payer_openid, status, amount_cent, currency, expires_at)
                        values
                            (:orderId, :configId, :fingerprint, :outTradeNo,
                             'openid-legacy', 'PAYING', 100, 'CNY', current_timestamp)
                        """)
                .param("orderId", configId)
                .param("configId", configId)
                .param("fingerprint", fingerprint)
                .param("outTradeNo", "LEGACY-" + configId)
                .update();
    }

    private ConfigSecretRow configSecretRow(long configId) {
        return jdbcClient.sql("""
                        select api_v3_key_ciphertext, private_key_pem_ciphertext,
                               wechat_public_key_pem_ciphertext, private_key_file_id,
                               merchant_certificate_file_id, wechat_public_key_file_id,
                               secret_cipher_version, secret_key_id
                        from payment_config where id = :configId
                        """)
                .param("configId", configId)
                .query((rs, rowNum) -> new ConfigSecretRow(
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getString("private_key_pem_ciphertext"),
                        rs.getString("wechat_public_key_pem_ciphertext"),
                        nullableLong(rs, "private_key_file_id"),
                        nullableLong(rs, "merchant_certificate_file_id"),
                        nullableLong(rs, "wechat_public_key_file_id"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")
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
                .doesNotContain("wechatPublicKeyPem\"")
                .doesNotContain("privateKeyFileId")
                .doesNotContain("merchantCertificateFileId")
                .doesNotContain("wechatPublicKeyFileId");
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

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp").query(LocalDateTime.class).single();
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

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record ConfigSecretRow(
            String apiV3KeyCiphertext,
            String privateKeyPemCiphertext,
            String publicKeyPemCiphertext,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            Long publicKeyFileId,
            int cipherVersion,
            String keyId
    ) {
    }

    private record ImportResult(int status, String body) {
    }
}
