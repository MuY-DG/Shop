package org.muybaby.shopserver.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.common.error.ErrorCode;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            synthetic-private-key
            -----END PRIVATE KEY-----
            """;
    private static final String CERTIFICATE_PEM = """
            -----BEGIN CERTIFICATE-----
            synthetic-merchant-certificate
            -----END CERTIFICATE-----
            """;
    private static final String PUBLIC_KEY_PEM = """
            -----BEGIN PUBLIC KEY-----
            synthetic-wechat-public-key
            -----END PUBLIC KEY-----
            """;
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
        registry.add("shop.pay.api-v3-key", () -> "synthetic_env_api_v3_key");
        registry.add("shop.pay.notify-url", () -> "https://pay.example.test/wxpay/pay/notify");
        registry.add("shop.pay.refund-notify-url", () -> "https://pay.example.test/wxpay/refund/notify");
        registry.add("shop.pay.verify-mode", () -> "PUBLIC_KEY");
        registry.add("shop.pay.public-key-id", () -> "pub_key_env_123456");
        registry.add("shop.pay.public-key-path", () -> envPublicKeyPath.toString());
    }

    @BeforeEach
    void clearPaymentConfigState() {
        jdbcClient.sql("delete from storage_file_usage").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_file").update();
    }

    @Test
    void readEndpointsRequireAdminTokenAndPaymentConfigReadAuthority() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeOnlyToken = limitedAdminToken(List.of("payment:config:write"));
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/pay/configs/effective"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));

        mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + writeOnlyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/pay/configs")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void effectiveEndpointReturnsMaskedEnvConfigWhenEnvIsEffective() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));

        String response = mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENV"))
                .andExpect(jsonPath("$.data.configName").value("Environment"))
                .andExpect(jsonPath("$.data.appIdMasked").value(startsWith("wx_")))
                .andExpect(jsonPath("$.data.mchIdMasked").value(startsWith("mc")))
                .andExpect(jsonPath("$.data.merchantSerialNoMasked").value(startsWith("ser")))
                .andExpect(jsonPath("$.data.apiV3KeyConfigured").value(true))
                .andExpect(jsonPath("$.data.privateKeyFileId").doesNotExist())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSecretMaterialIsAbsent(response);
        assertThat(response).doesNotContain("wx_env_app_123456")
                .doesNotContain("mch_env_123456")
                .doesNotContain("serial_env_123456")
                .doesNotContain("pub_key_env_123456");
    }

    @Test
    void creatingDbConfigEncryptsApiV3KeyRegistersPrivateFileUsagesAndReturnsMaskedResponse() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

        String response = mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Main DB Pay",
                                "wx_db_app_123456",
                                "mch_db_123456",
                                "serial_db_123456",
                                "synthetic_db_api_v3_key",
                                privateKeyFileId,
                                certFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.source").value("DB"))
                .andExpect(jsonPath("$.data.configName").value("Main DB Pay"))
                .andExpect(jsonPath("$.data.appIdMasked").value(startsWith("wx_")))
                .andExpect(jsonPath("$.data.apiV3KeyConfigured").value(true))
                .andExpect(jsonPath("$.data.privateKeyFileId").value(privateKeyFileId))
                .andExpect(jsonPath("$.data.merchantCertificateFileId").value(certFileId))
                .andExpect(jsonPath("$.data.wechatPublicKeyFileId").value(publicKeyFileId))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSecretMaterialIsAbsent(response);
        long configId = objectMapper.readTree(response).path("data").path("id").asLong();
        String ciphertext = jdbcClient.sql("select api_v3_key_ciphertext from payment_config where id = :configId")
                .param("configId", configId)
                .query(String.class)
                .single();
        assertThat(ciphertext)
                .startsWith("v1:")
                .doesNotContain("synthetic_db_api_v3_key");

        Integer activeProtectedUsageCount = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where owner_type = 'PAYMENT_CONFIG'
                          and owner_id = :configId
                          and usage_type = 'PAYMENT_CONFIG_CERT'
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("configId", configId)
                .query(Integer.class)
                .single();
        assertThat(activeProtectedUsageCount).isEqualTo(3);
    }

    @Test
    void updatingDbConfigWithBlankSecretPreservesExistingCiphertext() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

        long configId = createConfig(writeToken, "Secret Preserve", privateKeyFileId, certFileId, publicKeyFileId);
        String beforeCiphertext = paymentCiphertext(configId);

        String response = mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Secret Preserve Updated",
                                "wx_db_app_updated",
                                "mch_db_updated",
                                "serial_db_updated",
                                "   ",
                                privateKeyFileId,
                                certFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_updated",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configName").value("Secret Preserve Updated"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSecretMaterialIsAbsent(response);
        assertThat(paymentCiphertext(configId)).isEqualTo(beforeCiphertext);
    }

    @Test
    void enablingOneDbConfigDisablesOtherDbConfigs() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        long privateKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        long firstConfigId = createConfig(writeToken, "First Pay", privateKeyFileId, certFileId, publicKeyFileId);
        long secondConfigId = createConfig(writeToken, "Second Pay", privateKeyFileId, certFileId, publicKeyFileId);

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", firstConfigId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", secondConfigId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        assertThat(enabledFlag(firstConfigId)).isFalse();
        assertThat(enabledFlag(secondConfigId)).isTrue();
    }

    @Test
    void publicFilesOrWrongPurposeFilesCannotBeUsedForPaymentConfig() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long publicPaymentFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PUBLIC", "public-private.pem", PRIVATE_KEY_PEM);
        long wrongPurposeFileId = insertStorageFile("AFTER_SALE_IMAGE", "PRIVATE", "after-sale-private.pem", PRIVATE_KEY_PEM);
        long validCertFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long validPublicKeyFileId = insertStorageFile("PAYMENT_CERTIFICATE", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

        mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Bad Public File",
                                "wx_db_app_123456",
                                "mch_db_123456",
                                "serial_db_123456",
                                "synthetic_db_api_v3_key",
                                publicPaymentFileId,
                                validCertFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                validPublicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));

        mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Bad Purpose File",
                                "wx_db_app_123456",
                                "mch_db_123456",
                                "serial_db_123456",
                                "synthetic_db_api_v3_key",
                                wrongPurposeFileId,
                                validCertFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                validPublicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    private long createConfig(String token, String configName, long privateKeyFileId, long certFileId, long publicKeyFileId) throws Exception {
        String response = mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                configName,
                                "wx_db_app_" + UUID.randomUUID().toString().substring(0, 8),
                                "mch_db_123456",
                                "serial_db_" + UUID.randomUUID().toString().substring(0, 8),
                                "synthetic_db_api_v3_key",
                                privateKeyFileId,
                                certFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private long insertStorageFile(String purpose, String visibility, String filename, String content) {
        String objectKey = "test/" + UUID.randomUUID() + "/" + filename;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(objectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:purpose, :visibility, 'LOCAL', '', :objectKey, :filename,
                             'text/plain', 'pem', :sizeBytes, '', 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("purpose", purpose)
                .param("visibility", visibility)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .param("sizeBytes", bytes.length)
                .update();
        return jdbcClient.sql("select id from storage_file where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private String paymentCiphertext(long configId) {
        return jdbcClient.sql("select api_v3_key_ciphertext from payment_config where id = :configId")
                .param("configId", configId)
                .query(String.class)
                .single();
    }

    private boolean enabledFlag(long configId) {
        return Boolean.TRUE.equals(jdbcClient.sql("select enabled from payment_config where id = :configId")
                .param("configId", configId)
                .query(Boolean.class)
                .single());
    }

    private void assertSecretMaterialIsAbsent(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        assertThat(root.toString())
                .doesNotContain("synthetic_env_api_v3_key")
                .doesNotContain("synthetic_db_api_v3_key")
                .doesNotContain("synthetic-private-key")
                .doesNotContain("synthetic-merchant-certificate")
                .doesNotContain("synthetic-wechat-public-key")
                .doesNotContain("apiV3KeyCiphertext")
                .doesNotContain("privateKeyPem")
                .doesNotContain("wechatPublicKeyPem")
                .doesNotContain("objectKey")
                .doesNotContain("token");
    }

    private String configJson(
            String configName,
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3Key,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            String verifyMode,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl
    ) {
        return """
                {"configName":"%s","appId":"%s","mchId":"%s","merchantSerialNo":"%s",
                 "apiV3Key":"%s","privateKeyFileId":%d,"merchantCertificateFileId":%d,
                 "verifyMode":"%s","wechatPublicKeyId":"%s","wechatPublicKeyFileId":%d,
                 "notifyUrl":"%s","refundNotifyUrl":"%s"}
                """.formatted(
                configName,
                appId,
                mchId,
                merchantSerialNo,
                apiV3Key,
                privateKeyFileId,
                merchantCertificateFileId,
                verifyMode,
                wechatPublicKeyId,
                wechatPublicKeyFileId,
                notifyUrl,
                refundNotifyUrl
        );
    }

    private String limitedAdminToken(List<String> permissions) {
        TokenSession session = TokenSession.admin(99L, "limited-payment-admin", List.of("R_PAYMENT_LIMITED"), permissions, Instant.now());
        return opaqueTokenService.issue(TokenKind.ADMIN, session).accessToken();
    }

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"payment-admin-controller-test"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
