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
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @MockitoSpyBean
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
        clearLimitedAdmins();
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
    void paymentWriteAuthorityUploadsASecretDocumentOutsideTheAssetLibrary() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "merchant-private.pem",
                "text/plain",
                PRIVATE_KEY_PEM.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/admin/pay/configs/secret-files")
                        .file(file)
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        String response = mockMvc.perform(multipart("/admin/pay/configs/secret-files")
                        .file(file)
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope").value("SECRET"))
                .andExpect(jsonPath("$.data.mediaKind").value("DOCUMENT"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.uploadedByType").value("ADMIN"))
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.publicUrl").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long assetId = objectMapper.readTree(response).path("data").path("id").asLong();

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset
                        where id = :assetId
                          and scope = 'SECRET'
                          and media_kind = 'DOCUMENT'
                          and visibility = 'PRIVATE'
                          and uploaded_by_type = 'ADMIN'
                          and public_url is null
                        """)
                .param("assetId", assetId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        LocalDateTime expiresAt = jdbcClient.sql("select expires_at from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(LocalDateTime.class)
                .single();
        LocalDateTime databaseNow = databaseNow();
        assertThat(expiresAt)
                .isAfter(databaseNow.plusHours(1))
                .isBefore(databaseNow.plusHours(3));
    }

    @Test
    void readEndpointsRequireAdminTokenAndPaymentConfigReadAuthority() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeOnlyToken = limitedAdminToken(List.of("payment:config:write"));
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/pay/configs/effective"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));

        mockMvc.perform(get("/admin/pay/configs/environment"))
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

        mockMvc.perform(get("/admin/pay/configs/environment")
                        .header("Authorization", "Bearer " + writeOnlyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/pay/configs/environment")
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
    void environmentEndpointReturnsMaskedEnvConfigWhenDbIsEffective() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        long configId = createConfig(writeToken, "DB Pay", privateKeyFileId, certFileId, publicKeyFileId);

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + enableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DB"}
                                """))
                .andExpect(status().isOk());

        String response = mockMvc.perform(get("/admin/pay/configs/environment")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.config.source").value("ENV"))
                .andExpect(jsonPath("$.data.config.configName").value("Environment"))
                .andExpect(jsonPath("$.data.config.appIdMasked").value(startsWith("wx_")))
                .andExpect(jsonPath("$.data.config.apiV3KeyConfigured").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSecretMaterialIsAbsent(response);
        assertThat(response).doesNotContain("wx_env_app_123456")
                .doesNotContain("mch_env_123456")
                .doesNotContain("synthetic_env_api_v3_key");
    }

    @Test
    void sourceEndpointRequiresReadForViewAndEnableForSwitching() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));

        mockMvc.perform(get("/admin/pay/configs/source"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.AUTHENTICATION_REQUIRED.code()));

        mockMvc.perform(get("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        mockMvc.perform(get("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENV"))
                .andExpect(jsonPath("$.data.persisted").value(false));

        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"AUTO"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));

        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + enableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"ENV"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENV"))
                .andExpect(jsonPath("$.data.persisted").value(true));
    }

    @Test
    void switchingSourceToDbMakesEnabledDbConfigEffectiveWithoutRestart() throws Exception {
        String readToken = limitedAdminToken(List.of("payment:config:read"));
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        long configId = createConfig(writeToken, "Switchable DB Pay", privateKeyFileId, certFileId, publicKeyFileId);

        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENV"))
                .andExpect(jsonPath("$.data.configName").value("Environment"));

        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + enableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DB"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DB"))
                .andExpect(jsonPath("$.data.persisted").value(true));

        mockMvc.perform(get("/admin/pay/configs/effective")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(configId))
                .andExpect(jsonPath("$.data.source").value("DB"))
                .andExpect(jsonPath("$.data.configName").value("Switchable DB Pay"))
                .andExpect(jsonPath("$.data.privateKeyFileId").value(privateKeyFileId))
                .andExpect(jsonPath("$.data.wechatPublicKeyFileId").value(publicKeyFileId));
    }

    @Test
    void configCreateUpdateEnableAndSourceSwitchReadProviderOutsideTransactions() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        long privateKeyFileId = insertStorageAsset(
                "SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset(
                "SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset(
                "SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(storageProvider).open(any(StorageObjectLocation.class));

        long configId = createConfig(
                writeToken, "Outside Transaction Pay", privateKeyFileId, certFileId, publicKeyFileId);
        mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Outside Transaction Pay Updated",
                                " ",
                                " ",
                                " ",
                                " ",
                                privateKeyFileId,
                                certFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/pay/configs/{configId}/enable", configId)
                        .header("Authorization", "Bearer " + enableToken))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/pay/configs/source")
                        .header("Authorization", "Bearer " + enableToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DB"}
                                """))
                .andExpect(status().isOk());

        verify(storageProvider, atLeastOnce()).open(any(StorageObjectLocation.class));
    }

    @Test
    void creatingDbConfigEncryptsApiV3KeyRegistersPrivateFileUsagesAndReturnsMaskedResponse() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

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
                        from storage_asset_usage
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
        assertClaimed(privateKeyFileId, certFileId, publicKeyFileId);
    }

    @Test
    void updatingDbConfigWithBlankSensitiveFieldsPreservesExistingValuesAndAllowsCallbackAndFileChanges() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        long replacementPrivateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private-v2.pem", PRIVATE_KEY_PEM);
        long replacementCertFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert-v2.pem", CERTIFICATE_PEM);
        long replacementPublicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public-v2.pem", PUBLIC_KEY_PEM);

        long configId = createConfig(writeToken, "Secret Preserve", privateKeyFileId, certFileId, publicKeyFileId);
        PaymentConfigSnapshot before = paymentConfigSnapshot(configId);

        String response = mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Secret Preserve Updated",
                                "   ",
                                "",
                                " ",
                                "   ",
                                replacementPrivateKeyFileId,
                                replacementCertFileId,
                                "PUBLIC_KEY",
                                " ",
                                replacementPublicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify/v2",
                                "https://pay.example.test/wxpay/refund/notify/v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configName").value("Secret Preserve Updated"))
                .andExpect(jsonPath("$.data.privateKeyFileId").value(replacementPrivateKeyFileId))
                .andExpect(jsonPath("$.data.merchantCertificateFileId").value(replacementCertFileId))
                .andExpect(jsonPath("$.data.wechatPublicKeyFileId").value(replacementPublicKeyFileId))
                .andExpect(jsonPath("$.data.notifyUrl").value("https://pay.example.test/wxpay/pay/notify/v2"))
                .andExpect(jsonPath("$.data.refundNotifyUrl").value("https://pay.example.test/wxpay/refund/notify/v2"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertSecretMaterialIsAbsent(response);
        PaymentConfigSnapshot after = paymentConfigSnapshot(configId);
        assertThat(after.appId()).isEqualTo(before.appId());
        assertThat(after.mchId()).isEqualTo(before.mchId());
        assertThat(after.merchantSerialNo()).isEqualTo(before.merchantSerialNo());
        assertThat(after.wechatPublicKeyId()).isEqualTo(before.wechatPublicKeyId());
        assertThat(after.apiV3KeyCiphertext()).isEqualTo(before.apiV3KeyCiphertext());
        assertThat(after.privateKeyFileId()).isEqualTo(replacementPrivateKeyFileId);
        assertThat(after.merchantCertificateFileId()).isEqualTo(replacementCertFileId);
        assertThat(after.wechatPublicKeyFileId()).isEqualTo(replacementPublicKeyFileId);
        assertThat(after.notifyUrl()).isEqualTo("https://pay.example.test/wxpay/pay/notify/v2");
        assertThat(after.refundNotifyUrl()).isEqualTo("https://pay.example.test/wxpay/refund/notify/v2");
        assertClaimed(replacementPrivateKeyFileId, replacementCertFileId, replacementPublicKeyFileId);
        assertReleased(privateKeyFileId, certFileId, publicKeyFileId);
    }

    @Test
    void updatingDbConfigCanExplicitlyClearOptionalMerchantCertificateAndReleaseIt() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        long configId = createConfig(writeToken, "Clear Optional Cert", privateKeyFileId, certFileId, publicKeyFileId);

        mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Clear Optional Cert",
                                " ",
                                " ",
                                " ",
                                " ",
                                privateKeyFileId,
                                null,
                                "PUBLIC_KEY",
                                " ",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantCertificateFileId").doesNotExist());

        assertThat(jdbcClient.sql("select merchant_certificate_file_id from payment_config where id = :configId")
                .param("configId", configId)
                .query(Long.class)
                .optional()).isEmpty();
        assertReleased(certFileId);
        assertClaimed(privateKeyFileId, publicKeyFileId);
    }

    @Test
    void expiredStagedSecretCannotBeClaimedByPaymentConfig() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "expired-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
        jdbcClient.sql("update storage_asset set expires_at = :expiredAt where id = :assetId")
                .param("expiredAt", LocalDateTime.now().minusMinutes(1))
                .param("assetId", privateKeyFileId)
                .update();

        mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Expired Secret",
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));

        assertThat(jdbcClient.sql("select count(*) from payment_config where config_name = 'Expired Secret'")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void enablingOneDbConfigDisablesOtherDbConfigs() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        String enableToken = limitedAdminToken(List.of("payment:config:enable"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);
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
    void publicOrNonSecretAssetsCannotBeUsedForPaymentConfig() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long publicPaymentFileId = insertStorageAsset("LIBRARY", "DOCUMENT", "PUBLIC", "public-private.pem", PRIVATE_KEY_PEM);
        long wrongScopeFileId = insertStorageAsset("ATTACHMENT", "IMAGE", "PRIVATE", "after-sale-private.pem", PRIVATE_KEY_PEM);
        long validCertFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long validPublicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

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
                                "Bad Scope File",
                                "wx_db_app_123456",
                                "mch_db_123456",
                                "serial_db_123456",
                                "synthetic_db_api_v3_key",
                                wrongScopeFileId,
                                validCertFileId,
                                "PUBLIC_KEY",
                                "pub_key_db_123456",
                                validPublicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    @Test
    void certificateVerifyModeIsRejectedForCreateAndUpdate() throws Exception {
        String writeToken = limitedAdminToken(List.of("payment:config:write"));
        long privateKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-private.pem", PRIVATE_KEY_PEM);
        long certFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "merchant-cert.pem", CERTIFICATE_PEM);
        long publicKeyFileId = insertStorageAsset("SECRET", "DOCUMENT", "PRIVATE", "wechat-public.pem", PUBLIC_KEY_PEM);

        mockMvc.perform(post("/admin/pay/configs")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Unsupported Certificate Mode",
                                "wx_db_app_123456",
                                "mch_db_123456",
                                "serial_db_123456",
                                "synthetic_db_api_v3_key",
                                privateKeyFileId,
                                certFileId,
                                "CERTIFICATE",
                                "pub_key_db_123456",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
        assertThat(jdbcClient.sql("select count(*) from payment_config where verify_mode = 'CERTIFICATE'")
                .query(Integer.class)
                .single()).isZero();

        long configId = createConfig(writeToken, "Public Key Config", privateKeyFileId, certFileId, publicKeyFileId);
        mockMvc.perform(put("/admin/pay/configs/{configId}", configId)
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(
                                "Unsupported Certificate Update",
                                "   ",
                                "   ",
                                "   ",
                                "   ",
                                privateKeyFileId,
                                certFileId,
                                "CERTIFICATE",
                                "pub_key_db_123456",
                                publicKeyFileId,
                                "https://pay.example.test/wxpay/pay/notify",
                                "https://pay.example.test/wxpay/refund/notify")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        assertThat(jdbcClient.sql("select verify_mode from payment_config where id = :configId")
                .param("configId", configId)
                .query(String.class)
                .single()).isEqualTo("PUBLIC_KEY");
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

    private long insertStorageAsset(
            String scope,
            String mediaKind,
            String visibility,
            String filename,
            String content
    ) {
        String objectKey = "test/" + UUID.randomUUID() + "/" + filename;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storageProvider.put(objectKey, "text/plain", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, status, uploaded_by_type, uploaded_by_id,
                             expires_at)
                        values
                            (:scope, :mediaKind, :visibility, 'LOCAL', '', :objectKey, :filename,
                             'text/plain', 'pem', :sizeBytes, :sha256, 'ACTIVE', 'ADMIN', 1, :expiresAt)
                        """)
                .param("scope", scope)
                .param("mediaKind", mediaKind)
                .param("visibility", visibility)
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

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private PaymentConfigSnapshot paymentConfigSnapshot(long configId) {
        return jdbcClient.sql("""
                        select app_id,
                               mch_id,
                               merchant_serial_no,
                               api_v3_key_ciphertext,
                               private_key_file_id,
                               merchant_certificate_file_id,
                               wechat_public_key_id,
                               wechat_public_key_file_id,
                               notify_url,
                               refund_notify_url
                        from payment_config
                        where id = :configId
                        """)
                .param("configId", configId)
                .query((rs, rowNum) -> new PaymentConfigSnapshot(
                        rs.getString("app_id"),
                        rs.getString("mch_id"),
                        rs.getString("merchant_serial_no"),
                        rs.getString("api_v3_key_ciphertext"),
                        rs.getLong("private_key_file_id"),
                        rs.getLong("merchant_certificate_file_id"),
                        rs.getString("wechat_public_key_id"),
                        rs.getLong("wechat_public_key_file_id"),
                        rs.getString("notify_url"),
                        rs.getString("refund_notify_url")
                ))
                .single();
    }

    private void assertClaimed(long... assetIds) {
        for (long assetId : assetIds) {
            assertThat(jdbcClient.sql("select count(*) from storage_asset where id = :id and expires_at is null")
                    .param("id", assetId)
                    .query(Integer.class)
                    .single()).isEqualTo(1);
        }
    }

    private void assertReleased(long... assetIds) {
        LocalDateTime databaseNow = databaseNow();
        for (long assetId : assetIds) {
            LocalDateTime expiresAt = jdbcClient.sql("select expires_at from storage_asset where id = :id")
                    .param("id", assetId)
                    .query(LocalDateTime.class)
                    .single();
            assertThat(expiresAt)
                    .isAfter(databaseNow.plusHours(23))
                    .isBefore(databaseNow.plusHours(25));
        }
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
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
        jdbcClient.sql("delete from admin_role_permission where role_id between 9910001 and 9919999").update();
        jdbcClient.sql("delete from admin_user_role where role_id between 9910001 and 9919999").update();
        jdbcClient.sql("delete from admin_role where id between 9910001 and 9919999").update();
        jdbcClient.sql("delete from admin_user where id between 9910001 and 9919999").update();
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

    private record PaymentConfigSnapshot(
            String appId,
            String mchId,
            String merchantSerialNo,
            String apiV3KeyCiphertext,
            Long privateKeyFileId,
            Long merchantCertificateFileId,
            String wechatPublicKeyId,
            Long wechatPublicKeyFileId,
            String notifyUrl,
            String refundNotifyUrl
    ) {
    }
}
