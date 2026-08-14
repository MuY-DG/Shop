package org.muybaby.shopserver.wechat.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformConfigUpdateRequest;
import org.muybaby.shopserver.wechat.platform.dto.AdminWechatPlatformEnvironmentImportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shop.wechat.mini-program.app-id=wx-legacy-env",
        "shop.wechat.mini-program.app-secret=legacy-env-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminWechatPlatformConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private WechatPlatformConfigService configService;

    @Autowired
    private WechatPlatformConfigRepository repository;

    @BeforeEach
    void clearConfig() {
        jdbcClient.sql("delete from wechat_platform_config").update();
    }

    @Test
    void endpointsProtectLegacyImportAndNeverReturnTheSecret() throws Exception {
        String readToken = token(List.of("wechat-platform:config:read"));
        String writeToken = token(List.of("wechat-platform:config:write"));

        mockMvc.perform(get("/admin/wechat/platform-config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/wechat/platform-config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.data.appId").value("wx-legacy-env"))
                .andExpect(jsonPath("$.data.appSecretMasked").value("********"))
                .andExpect(jsonPath("$.data.appSecretConfigured").value(true))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist())
                .andExpect(jsonPath("$.data.legacyEnvironmentImportAvailable").value(true))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(post("/admin/wechat/platform-config/legacy-env-import")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/wechat/platform-config/legacy-env-import")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DATABASE"))
                .andExpect(jsonPath("$.data.appSecretMasked").value("********"))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist())
                .andExpect(jsonPath("$.data.legacyEnvironmentImportAvailable").value(false))
                .andExpect(jsonPath("$.data.version").value(1));

        SecretRow row = secretRow();
        assertThat(row.ciphertext())
                .startsWith("v1:")
                .doesNotContain("legacy-env-secret");
        assertThat(row.cipherVersion()).isOne();
        assertThat(row.keyId()).isEmpty();
        WechatPlatformCredentials resolved = configService.resolve();
        assertThat(resolved)
                .isEqualTo(new WechatPlatformCredentials(
                        "wx-legacy-env",
                        "legacy-env-secret",
                        WechatPlatformCredentials.Source.DATABASE));
        assertThat(resolved.toString()).doesNotContain("legacy-env-secret");

        mockMvc.perform(post("/admin/wechat/platform-config/legacy-env-import")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void persistedDamageFailsClosedWithoutFallingBackToLegacyEnvironment() {
        configService.importLegacyEnvironment(
                new AdminWechatPlatformEnvironmentImportRequest(0L),
                1L);
        jdbcClient.sql("""
                        update wechat_platform_config
                        set secret_cipher_version = 2, secret_key_id = 'tampered'
                        where id = 1
                        """)
                .update();

        assertThatThrownBy(configService::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_PLATFORM_CONFIG_UNAVAILABLE));

        jdbcClient.sql("""
                        update wechat_platform_config
                        set app_secret_ciphertext = 'damaged-envelope',
                            secret_cipher_version = 1,
                            secret_key_id = ''
                        where id = 1
                        """)
                .update();

        assertThatThrownBy(configService::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_PLATFORM_CONFIG_UNAVAILABLE));
    }

    @Test
    void updateEndpointRequiresWriteAndNeverReturnsTheSubmittedSecret() throws Exception {
        String readToken = token(List.of("wechat-platform:config:read"));
        String writeToken = token(List.of("wechat-platform:config:write"));
        String body = """
                {"appId":"wx-api-created","appSecret":"api-created-secret","version":0}
                """;

        mockMvc.perform(put("/admin/wechat/platform-config")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/wechat/platform-config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DATABASE"))
                .andExpect(jsonPath("$.data.appId").value("wx-api-created"))
                .andExpect(jsonPath("$.data.appSecretMasked").value("********"))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist());

        assertThat(secretRow().ciphertext()).doesNotContain("api-created-secret");
    }

    @Test
    void updateRejectsTheResponseMaskInsteadOfPersistingItAsASecret() throws Exception {
        String writeToken = token(List.of("wechat-platform:config:write"));

        mockMvc.perform(put("/admin/wechat/platform-config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId":"wx-api-created","appSecret":"********","version":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        assertThat(jdbcClient.sql("select count(*) from wechat_platform_config")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void updateUsesCasAndRetainsAnExistingSecretOnlyForTheDatabaseRow() {
        assertThatThrownBy(() -> configService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-created", "", 0L),
                1L)).isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));

        configService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-created", "created-secret", 0L),
                1L);

        assertThat(configService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-updated", "", 1L),
                1L).version()).isEqualTo(2);
        assertThat(configService.resolve().appSecret()).isEqualTo("created-secret");

        assertThatThrownBy(() -> configService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-stale", "replacement-secret", 1L),
                1L)).isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_PLATFORM_CONFIG_CONFLICT));
    }

    @Test
    void updateRejectsSecretsThatExceedTheUtf8StorageBoundary() {
        String oversizedMultibyteSecret = "密".repeat(86);

        assertThatThrownBy(() -> configService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-created", oversizedMultibyteSecret, 0L),
                1L)).isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(jdbcClient.sql("select count(*) from wechat_platform_config")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void legacyAndMasterEncryptionPropertiesRedactSecretsFromToString() {
        WechatMiniProgramProperties miniProgram = new WechatMiniProgramProperties(
                "wx-sensitive-id", "sensitive-app-secret", false);
        SecretEncryptionProperties encryption = new SecretEncryptionProperties(
                2,
                "active-key-id",
                "active-key-id=base64:sensitive-key-ring-material",
                "sensitive-legacy-key",
                true,
                Duration.ofMinutes(1),
                50);

        assertThat(miniProgram.toString())
                .doesNotContain("wx-sensitive-id", "sensitive-app-secret");
        assertThat(encryption.toString())
                .doesNotContain("sensitive-key-ring-material", "sensitive-legacy-key");
    }

    @Test
    void rotationRewrapsTheSecretWithTheActiveV2KeyAndKeepsConfigVersionStable() {
        WechatMiniProgramProperties noEnvironment =
                new WechatMiniProgramProperties("", "", false);
        PaymentSecretCipher legacyCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        1, "", "", "0123456789abcdef0123456789abcdef",
                        false, Duration.ofMinutes(1), 50));
        WechatPlatformConfigService legacyService = new WechatPlatformConfigService(
                repository, legacyCipher, noEnvironment);
        legacyService.update(
                new AdminWechatPlatformConfigUpdateRequest(
                        "wx-rotate", "rotate-secret", 0L),
                1L);

        PaymentSecretCipher activeCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        2,
                        "new-2026",
                        "new-2026=base64:bmV3LWtleS1tYXRlcmlhbC0zMi1ieXRlcy0wMDAwMDA=",
                        "0123456789abcdef0123456789abcdef",
                        false,
                        Duration.ofMinutes(1),
                        50));
        WechatPlatformConfigService rotatingService = new WechatPlatformConfigService(
                repository, activeCipher, noEnvironment);

        assertThat(rotatingService.rotateSecretIfNeeded()).isOne();
        assertThat(rotatingService.rotateSecretIfNeeded()).isZero();
        assertThat(rotatingService.resolve().appSecret()).isEqualTo("rotate-secret");

        SecretRow row = secretRow();
        assertThat(row.ciphertext()).startsWith("v2:new-2026:");
        assertThat(row.cipherVersion()).isEqualTo(2);
        assertThat(row.keyId()).isEqualTo("new-2026");
        assertThat(jdbcClient.sql(
                        "select revision from wechat_platform_config where id = 1")
                .query(Long.class)
                .single()).isOne();
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private SecretRow secretRow() {
        return jdbcClient.sql("""
                        select app_secret_ciphertext, secret_cipher_version, secret_key_id
                        from wechat_platform_config
                        where id = 1
                        """)
                .query((rs, rowNum) -> new SecretRow(
                        rs.getString("app_secret_ciphertext"),
                        rs.getInt("secret_cipher_version"),
                        rs.getString("secret_key_id")
                ))
                .single();
    }

    private record SecretRow(String ciphertext, int cipherVersion, String keyId) {
    }
}
