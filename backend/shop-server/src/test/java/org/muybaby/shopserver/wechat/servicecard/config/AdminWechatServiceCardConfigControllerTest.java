package org.muybaby.shopserver.wechat.servicecard.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.secret.SecretEncryptionProperties;
import org.muybaby.shopserver.payment.config.AesGcmPaymentSecretCipher;
import org.muybaby.shopserver.payment.config.PaymentSecretCipher;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardConfigUpdateRequest;
import org.muybaby.shopserver.wechat.servicecard.config.dto.AdminWechatServiceCardEnvironmentImportRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "shop.wechat.service-card-2001.account-template-record-id=legacy-template",
        "shop.wechat.service-card-2001.fallback-product-image=https://static.example.com/card.png",
        "shop.wechat.service-card-2001.allowed-image-hosts=static.example.com",
        "shop.wechat.service-card-2001.prefer-order-snapshot-images=false",
        "shop.wechat.service-card-2001.callback.enabled=true",
        "shop.wechat.service-card-2001.callback.token=LegacyToken2026",
        "shop.wechat.service-card-2001.callback.encoding-aes-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminWechatServiceCardConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private WechatServiceCardConfigService configService;

    @Autowired
    private WechatServiceCardConfigRepository repository;

    @Autowired
    private WechatServiceCardProperties legacyEnvironment;

    @BeforeEach
    void clearConfig() {
        jdbcClient.sql("delete from wechat_service_card_config_audit").update();
        jdbcClient.sql("delete from wechat_service_card_config").update();
    }

    @Test
    void explicitLegacyImportPreservesValidatedCallbackAndNeverReturnsSecrets() throws Exception {
        String readToken = token(List.of("wechat-service-card:config:read"));
        String writeToken = token(List.of("wechat-service-card:config:write"));

        mockMvc.perform(get("/admin/wechat-service-cards/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.data.callbackEnabled").value(true))
                .andExpect(jsonPath("$.data.callbackTokenMasked").value("********"))
                .andExpect(jsonPath("$.data.callbackEncodingAesKeyMasked").value("********"))
                .andExpect(jsonPath("$.data.callbackToken").doesNotExist())
                .andExpect(jsonPath("$.data.callbackEncodingAesKey").doesNotExist());

        mockMvc.perform(post("/admin/wechat-service-cards/config/legacy-env-import")
                        .header("Authorization", "Bearer " + readToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/wechat-service-cards/config/legacy-env-import")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DATABASE"))
                .andExpect(jsonPath("$.data.callbackEnabled").value(true))
                .andExpect(jsonPath("$.data.version").value(1));

        WechatServiceCardConfig resolved = configService.resolve();
        assertThat(resolved.source()).isEqualTo(WechatServiceCardConfig.Source.DATABASE);
        assertThat(resolved.callbackSecureReady()).isTrue();
        assertThat(resolved.toString()).doesNotContain("LegacyToken2026", "AAAAAAAAAA");
        assertThat(jdbcClient.sql("""
                        select callback_token_ciphertext
                        from wechat_service_card_config where id = 1
                        """).query(String.class).single())
                .doesNotContain("LegacyToken2026");
        assertThat(jdbcClient.sql("select action_type from wechat_service_card_config_audit")
                .query(String.class).single()).isEqualTo("LEGACY_IMPORT");
    }

    @Test
    void updateUsesCasRejectsMasksAndRetainsOmittedSecrets() {
        AdminWechatServiceCardConfigUpdateRequest create = request(
                "template-one", "TokenOne2026", "A".repeat(43), 0L);
        assertThat(configService.update(create, 1L).version()).isOne();

        assertThatThrownBy(() -> configService.update(request(
                "template-mask", "********", "", 1L), 1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThat(configService.update(request(
                "template-two", "", "", 1L), 1L).version()).isEqualTo(2);
        assertThat(configService.resolve().callbackToken()).isEqualTo("TokenOne2026");

        assertThatThrownBy(() -> configService.update(request(
                "template-stale", "TokenTwo2026", "B".repeat(43), 1L), 1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_SERVICE_CARD_CONFIG_CONFLICT));
        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_config_audit")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void updateStrictlyRejectsMalformedBusinessFieldsAndSecrets() {
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template with spaces", "https://static.example.com/card.png",
                List.of("static.example.com"), false, false, "", "", 0L));
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template-valid", "https://static.example.com",
                List.of("static.example.com"), false, false, "", "", 0L));
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template-valid", "https://static.example.com/card.png?secret=value",
                List.of("static.example.com"), false, false, "", "", 0L));
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template-valid", "https://-static.example.com/card.png",
                List.of("-static.example.com"), false, false, "", "", 0L));
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template-valid", "https://static.example.com/card.png",
                List.of("static.example.com"), false, true, "ab", "A".repeat(43), 0L));
        assertValidationRejected(new AdminWechatServiceCardConfigUpdateRequest(
                "template-valid", "https://static.example.com/card.png",
                List.of("static.example.com"), false, true, "Token2026", "A".repeat(42), 0L));

        assertThat(repository.find()).isEmpty();
    }

    @Test
    void persistedDamageFailsClosedWithoutEnvironmentFallback() {
        configService.importLegacyEnvironment(
                new AdminWechatServiceCardEnvironmentImportRequest(0L), 1L);
        jdbcClient.sql("""
                        update wechat_service_card_config
                        set callback_token_key_id = 'tampered',
                            callback_token_cipher_version = 2
                        where id = 1
                        """).update();

        assertThatThrownBy(configService::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_SERVICE_CARD_CONFIG_UNAVAILABLE));
        assertThat(configService.resolveFailClosed()).isEmpty();
    }

    @Test
    void fullSecretReplacementRepairsUnreadablePersistedEnvelopes() {
        configService.update(request(
                "template-before-repair", "OldToken2026", "A".repeat(43), 0L), 1L);
        jdbcClient.sql("""
                        update wechat_service_card_config
                        set callback_token_key_id = 'missing-token-key',
                            callback_token_cipher_version = 2,
                            callback_aes_key_key_id = 'missing-aes-key',
                            callback_aes_key_cipher_version = 2
                        where id = 1
                        """).update();

        assertThat(configService.resolveFailClosed()).isEmpty();
        assertThat(configService.update(request(
                "template-after-repair", "RecoveredToken2026", "B".repeat(43), 1L), 2L)
                .version()).isEqualTo(2);
        assertThat(configService.resolve().callbackToken()).isEqualTo("RecoveredToken2026");
        assertThat(configService.resolve().callbackEncodingAesKey()).isEqualTo("B".repeat(43));
    }

    @Test
    void fieldBoundAadRejectsSwappedCiphertexts() {
        configService.update(request(
                "template-one", "TokenOne2026", "A".repeat(43), 0L), 1L);
        jdbcClient.sql("""
                        update wechat_service_card_config
                        set callback_token_ciphertext = callback_aes_key_ciphertext,
                            callback_aes_key_ciphertext = callback_token_ciphertext
                        where id = 1
                        """).update();

        assertThatThrownBy(configService::resolve)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_SERVICE_CARD_CONFIG_UNAVAILABLE));
    }

    @Test
    void rotationRewrapsBothFieldsWithActiveV2KeyWithoutChangingPlaintextOrConfigVersion() {
        PaymentSecretCipher legacyCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        1, "", "", "0123456789abcdef0123456789abcdef",
                        false, Duration.ofMinutes(1), 50));
        WechatServiceCardConfigService legacyService = new WechatServiceCardConfigService(
                repository, legacyCipher, legacyEnvironment);
        legacyService.update(request(
                "template-rotate", "RotateToken2026", "A".repeat(43), 0L), 1L);

        PaymentSecretCipher activeCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        2,
                        "new-2026",
                        "new-2026=base64:bmV3LWtleS1tYXRlcmlhbC0zMi1ieXRlcy0wMDAwMDA=",
                        "0123456789abcdef0123456789abcdef",
                        false,
                        Duration.ofMinutes(1),
                        50));
        WechatServiceCardConfigService rotatingService = new WechatServiceCardConfigService(
                repository, activeCipher, legacyEnvironment);

        assertThat(rotatingService.rotateSecretsIfNeeded()).isEqualTo(2);
        assertThat(rotatingService.rotateSecretsIfNeeded()).isZero();
        WechatServiceCardConfig resolved = rotatingService.resolve();
        assertThat(resolved.callbackToken()).isEqualTo("RotateToken2026");
        assertThat(resolved.callbackEncodingAesKey()).isEqualTo("A".repeat(43));

        WechatServiceCardConfigEntity row = repository.find().orElseThrow();
        assertThat(row.revision()).isOne();
        assertThat(row.callbackTokenCipherVersion()).isEqualTo(2);
        assertThat(row.callbackAesKeyCipherVersion()).isEqualTo(2);
        assertThat(row.callbackTokenKeyId()).isEqualTo("new-2026");
        assertThat(row.callbackAesKeyKeyId()).isEqualTo("new-2026");
        assertThat(row.callbackTokenSecretRevision()).isEqualTo(2);
        assertThat(row.callbackAesKeySecretRevision()).isEqualTo(2);
        assertThat(row.callbackTokenReencryptedAt()).isNotNull();
        assertThat(row.callbackAesKeyReencryptedAt()).isNotNull();
        assertThat(rotatingService.current().toString())
                .doesNotContain("RotateToken2026", "AAAAAAAAAAAAAAAA");
    }

    @Test
    void staleAdminUpdateCannotRollbackRotatedSecretEnvelopes() {
        PaymentSecretCipher legacyCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        1, "", "", "0123456789abcdef0123456789abcdef",
                        false, Duration.ofMinutes(1), 50));
        WechatServiceCardConfigService legacyService = new WechatServiceCardConfigService(
                repository, legacyCipher, legacyEnvironment);
        legacyService.update(request(
                "template-before-rotation", "RotateToken2026", "A".repeat(43), 0L), 1L);
        WechatServiceCardConfigEntity staleRow = repository.find().orElseThrow();

        PaymentSecretCipher activeCipher = new AesGcmPaymentSecretCipher(
                new SecretEncryptionProperties(
                        2,
                        "new-2026",
                        "new-2026=base64:bmV3LWtleS1tYXRlcmlhbC0zMi1ieXRlcy0wMDAwMDA=",
                        "0123456789abcdef0123456789abcdef",
                        false,
                        Duration.ofMinutes(1),
                        50));
        WechatServiceCardConfigService rotatingService = new WechatServiceCardConfigService(
                repository, activeCipher, legacyEnvironment);
        assertThat(rotatingService.rotateSecretsIfNeeded()).isEqualTo(2);
        WechatServiceCardConfigEntity rotatedRow = repository.find().orElseThrow();

        WechatServiceCardConfigRepository.StoredConfig staleUpdate =
                new WechatServiceCardConfigRepository.StoredConfig(
                        "template-after-rotation",
                        "https://static.example.com/card.png",
                        "static.example.com",
                        false,
                        true,
                        new PaymentSecretCipher.EncryptedSecret(
                                staleRow.callbackTokenCiphertext(),
                                staleRow.callbackTokenCipherVersion(),
                                staleRow.callbackTokenKeyId()),
                        new PaymentSecretCipher.EncryptedSecret(
                                staleRow.callbackAesKeyCiphertext(),
                                staleRow.callbackAesKeyCipherVersion(),
                                staleRow.callbackAesKeyKeyId()));

        assertThat(repository.update(
                staleRow.revision(), staleRow.callbackTokenSecretRevision(),
                staleRow.callbackAesKeySecretRevision(), staleUpdate,
                false, false, 2L)).isFalse();
        assertThat(repository.update(
                rotatedRow.revision(), rotatedRow.callbackTokenSecretRevision(),
                rotatedRow.callbackAesKeySecretRevision(), staleUpdate,
                false, false, 2L)).isTrue();

        WechatServiceCardConfigEntity afterUpdate = repository.find().orElseThrow();
        assertThat(afterUpdate.callbackTokenKeyId()).isEqualTo("new-2026");
        assertThat(afterUpdate.callbackAesKeyKeyId()).isEqualTo("new-2026");
        assertThat(afterUpdate.callbackTokenSecretRevision()).isEqualTo(2);
        assertThat(afterUpdate.callbackAesKeySecretRevision()).isEqualTo(2);
        assertThat(rotatingService.resolve().callbackToken()).isEqualTo("RotateToken2026");
        assertThat(rotatingService.resolve().callbackEncodingAesKey()).isEqualTo("A".repeat(43));
    }

    @Test
    void controllerRequiresDedicatedPermissions() throws Exception {
        String runtimeWriter = token(List.of("wechat-service-card:runtime:write"));
        String configWriter = token(List.of("wechat-service-card:config:write"));
        String body = """
                {
                  "accountTemplateRecordId":"template-api",
                  "fallbackProductImage":"https://static.example.com/card.png",
                  "allowedImageHosts":["static.example.com"],
                  "preferOrderSnapshotImages":false,
                  "callbackEnabled":false,
                  "callbackToken":"",
                  "callbackEncodingAesKey":"",
                  "version":0
                }
                """;

        mockMvc.perform(put("/admin/wechat-service-cards/config")
                        .header("Authorization", "Bearer " + runtimeWriter)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/wechat-service-cards/config")
                        .header("Authorization", "Bearer " + configWriter)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.callbackTokenConfigured").value(false));
    }

    private AdminWechatServiceCardConfigUpdateRequest request(
            String template,
            String token,
            String aesKey,
            long version
    ) {
        return new AdminWechatServiceCardConfigUpdateRequest(
                template,
                "https://static.example.com/card.png",
                List.of("STATIC.EXAMPLE.COM", "static.example.com"),
                false,
                true,
                token,
                aesKey,
                version);
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private void assertValidationRejected(AdminWechatServiceCardConfigUpdateRequest request) {
        assertThatThrownBy(() -> configService.update(request, 1L))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
