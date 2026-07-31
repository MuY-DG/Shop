package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.config.CosCustomDomainVerifier;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigRequest;
import org.muybaby.shopserver.storage.dto.AdminStorageConfigResponse;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminStorageConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageRuntimeConfigService storageRuntimeConfigService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private CosCustomDomainVerifier customDomainVerifier;

    @BeforeEach
    void clearStorageConfig() {
        jdbcClient.sql("delete from storage_runtime_setting").update();
    }

    @AfterEach
    void cleanupStorageConfig() {
        clearStorageConfig();
    }

    @Test
    void configEndpointsRequireTheirOwnAuthoritiesAndExposeUnconfiguredState() throws Exception {
        String readToken = token(List.of("storage:config:read"));
        String writeToken = token(List.of("storage:config:write"));

        mockMvc.perform(get("/admin/storage/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/storage/config").header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/storage/config").header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.publicBaseUrl").value(""))
                .andExpect(jsonPath("$.data.region").value(""))
                .andExpect(jsonPath("$.data.bucket").value(""))
                .andExpect(jsonPath("$.data.provider").doesNotExist())
                .andExpect(jsonPath("$.data.localRoot").doesNotExist());

        assertThatThrownBy(storageRuntimeConfigService::effective)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_NOT_CONFIGURED));
    }

    @Test
    void savesCosConfigEncryptedAndPreservesMaskedSecrets() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        String readToken = token(List.of("storage:config:read"));
        String createJson = """
                {
                  "publicBaseUrl": "",
                  "region": "ap-guangzhou",
                  "bucket": "shop-assets-1250000000",
                  "secretId": "AKIDexample-secret-id",
                  "secretKey": "example-secret-key"
                }
                """;

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://shop-assets-1250000000.cos.ap-guangzhou.myqcloud.com"))
                .andExpect(jsonPath("$.data.region").value("ap-guangzhou"))
                .andExpect(jsonPath("$.data.bucket").value("shop-assets-1250000000"))
                .andExpect(jsonPath("$.data.secretIdMasked", stringContainsInOrder("AKID", "-id")))
                .andExpect(jsonPath("$.data.secretIdMasked", not("AKIDexample-secret-id")))
                .andExpect(jsonPath("$.data.secretKeyConfigured").value(true));

        SecretRow secretRow = jdbcClient.sql("""
                        select cos_secret_id_ciphertext, cos_secret_key_ciphertext,
                               cos_custom_domain_verification_fingerprint
                        from storage_runtime_setting
                        where id = 1
                        """)
                .query((rs, rowNum) -> new SecretRow(
                        rs.getString("cos_secret_id_ciphertext"),
                        rs.getString("cos_secret_key_ciphertext"),
                        rs.getString("cos_custom_domain_verification_fingerprint")
                ))
                .single();
        assertThat(secretRow.secretIdCiphertext())
                .startsWith("v1:")
                .doesNotContain("AKIDexample-secret-id");
        assertThat(secretRow.secretKeyCiphertext())
                .startsWith("v1:")
                .doesNotContain("example-secret-key");
        assertThat(secretRow.customDomainVerificationFingerprint()).isNull();
        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo("https://shop-assets-1250000000.cos.ap-guangzhou.myqcloud.com");
        verifyNoInteractions(customDomainVerifier);

        String updateWithoutSecrets = objectMapper.writeValueAsString(new ConfigUpdate(
                "https://oss.example.test/",
                "ap-shanghai",
                "shop-assets-1250000000"
        ));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(customDomainVerifier).requireEnabledRestDomain(
                "oss.example.test",
                "ap-shanghai",
                "shop-assets-1250000000",
                "AKIDexample-secret-id",
                "example-secret-key"
        );
        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithoutSecrets))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://oss.example.test"))
                .andExpect(jsonPath("$.data.region").value("ap-shanghai"))
                .andExpect(jsonPath("$.data.secretKeyConfigured").value(true));

        mockMvc.perform(get("/admin/storage/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://oss.example.test"))
                .andExpect(jsonPath("$.data.secretKey").doesNotExist());
        verify(customDomainVerifier).requireEnabledRestDomain(
                "oss.example.test",
                "ap-shanghai",
                "shop-assets-1250000000",
                "AKIDexample-secret-id",
                "example-secret-key"
        );
        assertThat(jdbcClient.sql("""
                        select cos_custom_domain_verification_fingerprint
                        from storage_runtime_setting
                        where id = 1
                        """)
                .query(String.class)
                .single()).matches("[0-9a-f]{64}");
        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo("https://oss.example.test");
    }

    @Test
    void syntacticallyValidLegacyCustomDomainRequiresSuccessfulRevalidation() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        String readToken = token(List.of("storage:config:read"));
        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "region", "ap-guangzhou",
                                "bucket", "shop-assets-1250000000",
                                "secretId", "AKIDexample-secret-id",
                                "secretKey", "example-secret-key"
                        ))))
                .andExpect(status().isOk());
        jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_public_base_url = 'https://legacy.example.test',
                            cos_custom_domain_verification_fingerprint = null
                        where id = 1
                        """)
                .update();

        mockMvc.perform(get("/admin/storage/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://legacy.example.test"));
        assertThatThrownBy(storageRuntimeConfigService::effective)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.STORAGE_NOT_CONFIGURED));

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfigUpdate(
                                "https://legacy.example.test",
                                "ap-guangzhou",
                                "shop-assets-1250000000"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true));
        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo("https://legacy.example.test");

        jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_region = 'ap-shanghai'
                        where id = 1
                        """)
                .update();
        assertThat(storageRuntimeConfigService.current().configured()).isFalse();
        assertThatThrownBy(storageRuntimeConfigService::effective)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.STORAGE_NOT_CONFIGURED));
    }

    @Test
    void customDomainVerificationIsSuspendedFromAnExistingTransaction() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(customDomainVerifier).requireEnabledRestDomain(
                "oss.example.test",
                "ap-guangzhou",
                "shop-assets-1250000000",
                "AKIDexample-secret-id",
                "example-secret-key"
        );
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            AdminStorageConfigResponse response = storageRuntimeConfigService.update(
                    new AdminStorageConfigRequest(
                            "https://oss.example.test",
                            "ap-guangzhou",
                            "shop-assets-1250000000",
                            "AKIDexample-secret-id",
                            "example-secret-key"
                    ));
            assertThat(response.configured()).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(storageRuntimeConfigService.effective().publicBaseUrl())
                .isEqualTo("https://oss.example.test");
    }

    @Test
    void rejectsIncompleteCosConfiguration() throws Exception {
        String writeToken = token(List.of("storage:config:write"));

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "region": "ap-guangzhou",
                                  "bucket": "shop-assets-1250000000"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

        assertThat(jdbcClient.sql("select count(*) from storage_runtime_setting")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void rejectsCustomDomainThatIsNotAnEnabledRestOriginForTheBucket() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        doThrow(new BusinessException(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND))
                .when(customDomainVerifier)
                .requireEnabledRestDomain(
                        "unbound.example.test",
                        "ap-guangzhou",
                        "shop-assets-1250000000",
                        "AKIDexample-secret-id",
                        "example-secret-key"
                );

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "publicBaseUrl", "https://unbound.example.test",
                                "region", "ap-guangzhou",
                                "bucket", "shop-assets-1250000000",
                                "secretId", "AKIDexample-secret-id",
                                "secretKey", "example-secret-key"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.STORAGE_CUSTOM_DOMAIN_NOT_BOUND.code()));

        assertThat(jdbcClient.sql("select count(*) from storage_runtime_setting")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void returnsServiceUnavailableWhenCosCannotVerifyTheCustomDomain() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        doThrow(new BusinessException(
                ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE))
                .when(customDomainVerifier)
                .requireEnabledRestDomain(
                        "oss.example.test",
                        "ap-guangzhou",
                        "shop-assets-1250000000",
                        "AKIDexample-secret-id",
                        "example-secret-key"
                );

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "publicBaseUrl", "https://oss.example.test",
                                "region", "ap-guangzhou",
                                "bucket", "shop-assets-1250000000",
                                "secretId", "AKIDexample-secret-id",
                                "secretKey", "example-secret-key"
                        ))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(
                        ErrorCode.STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE.code()));

        assertThat(jdbcClient.sql("select count(*) from storage_runtime_setting")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void legacyInvalidPublicDomainCanBeReadAndCorrected() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        String readToken = token(List.of("storage:config:read"));
        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "region", "ap-guangzhou",
                                "bucket", "shop-assets-1250000000",
                                "secretId", "AKIDexample-secret-id",
                                "secretKey", "example-secret-key"
                        ))))
                .andExpect(status().isOk());
        jdbcClient.sql("""
                        update storage_runtime_setting
                        set cos_public_base_url = 'http://legacy.example.test/files'
                        where id = 1
                        """)
                .update();

        mockMvc.perform(get("/admin/storage/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("http://legacy.example.test/files"));
        assertThatThrownBy(storageRuntimeConfigService::effective)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.VALIDATION_FAILED));

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfigUpdate(
                                "https://oss.example.test",
                                "ap-guangzhou",
                                "shop-assets-1250000000"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://oss.example.test"));
    }

    @Test
    void rejectsPublicDomainThatIsNotAnHttpsRootOrigin() throws Exception {
        String writeToken = token(List.of("storage:config:write"));

        for (String invalidOrigin : List.of(
                "http://assets.example.test",
                "https://user:password@assets.example.test",
                "https://assets.example.test:8443",
                "https://assets.example.test/upload",
                "https://assets.example.test//",
                "https://assets.example.test/%2F",
                "https://127.0.0.1"
        )) {
            mockMvc.perform(put("/admin/storage/config")
                            .header("Authorization", "Bearer " + writeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "publicBaseUrl", invalidOrigin,
                                    "region", "ap-guangzhou",
                                    "bucket", "shop-assets-1250000000",
                                    "secretId", "AKIDexample-secret-id",
                                    "secretKey", "example-secret-key"
                            ))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value(ErrorCode.VALIDATION_FAILED.code()));
        }
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private record SecretRow(
            String secretIdCiphertext,
            String secretKeyCiphertext,
            String customDomainVerificationFingerprint
    ) {
    }

    private record ConfigUpdate(
            String publicBaseUrl,
            String region,
            String bucket
    ) {
    }
}
