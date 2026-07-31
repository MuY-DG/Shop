package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
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
                        select cos_secret_id_ciphertext, cos_secret_key_ciphertext
                        from storage_runtime_setting
                        where id = 1
                        """)
                .query((rs, rowNum) -> new SecretRow(
                        rs.getString("cos_secret_id_ciphertext"),
                        rs.getString("cos_secret_key_ciphertext")
                ))
                .single();
        assertThat(secretRow.secretIdCiphertext())
                .startsWith("v1:")
                .doesNotContain("AKIDexample-secret-id");
        assertThat(secretRow.secretKeyCiphertext())
                .startsWith("v1:")
                .doesNotContain("example-secret-key");

        String updateWithoutSecrets = objectMapper.writeValueAsString(new ConfigUpdate(
                "https://assets.example.test",
                "ap-shanghai",
                "shop-assets-1250000000"
        ));
        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithoutSecrets))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://assets.example.test"))
                .andExpect(jsonPath("$.data.region").value("ap-shanghai"))
                .andExpect(jsonPath("$.data.secretKeyConfigured").value(true));

        mockMvc.perform(get("/admin/storage/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.publicBaseUrl").value("https://assets.example.test"))
                .andExpect(jsonPath("$.data.secretKey").doesNotExist());
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
    void rejectsPublicDomainContainingCredentials() throws Exception {
        String writeToken = token(List.of("storage:config:write"));

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "publicBaseUrl": "https://user:password@assets.example.test",
                                  "region": "ap-guangzhou",
                                  "bucket": "shop-assets-1250000000",
                                  "secretId": "AKIDexample-secret-id",
                                  "secretKey": "example-secret-key"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions);
    }

    private record SecretRow(String secretIdCiphertext, String secretKeyCiphertext) {
    }

    private record ConfigUpdate(
            String publicBaseUrl,
            String region,
            String bucket
    ) {
    }
}
