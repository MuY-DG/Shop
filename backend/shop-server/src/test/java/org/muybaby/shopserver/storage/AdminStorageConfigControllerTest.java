package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
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

    @BeforeEach
    void clearStorageConfig() {
        jdbcClient.sql("delete from storage_runtime_setting").update();
    }

    @AfterEach
    void cleanupStorageConfig() {
        clearStorageConfig();
    }

    @Test
    void configEndpointsRequireTheirOwnAuthorities() throws Exception {
        String readToken = token(List.of("storage:config:read"));
        String writeToken = token(List.of("storage:config:write"));

        mockMvc.perform(get("/admin/storage/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/storage/config").header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/storage/config").header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("LOCAL"))
                .andExpect(jsonPath("$.data.persisted").value(false));
    }

    @Test
    void savesTencentCosConfigEncryptedAndPreservesMaskedSecrets() throws Exception {
        String writeToken = token(List.of("storage:config:write"));
        String readToken = token(List.of("storage:config:read"));
        String createJson = """
                {
                  "provider": "TENCENT_COS",
                  "publicBaseUrl": "",
                  "localRoot": "var/uploads",
                  "cosRegion": "ap-guangzhou",
                  "cosBucket": "shop-assets-1250000000",
                  "cosSecretId": "AKIDexample-secret-id",
                  "cosSecretKey": "example-secret-key"
                }
                """;

        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("TENCENT_COS"))
                .andExpect(jsonPath("$.data.persisted").value(true))
                .andExpect(jsonPath("$.data.publicBaseUrl")
                        .value("https://shop-assets-1250000000.cos.ap-guangzhou.myqcloud.com"))
                .andExpect(jsonPath("$.data.cosSecretIdMasked", stringContainsInOrder("AKID", "-id")))
                .andExpect(jsonPath("$.data.cosSecretIdMasked", not("AKIDexample-secret-id")))
                .andExpect(jsonPath("$.data.cosSecretKeyConfigured").value(true));

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
        assertThat(secretRow.secretIdCiphertext()).startsWith("v1:").doesNotContain("AKIDexample-secret-id");
        assertThat(secretRow.secretKeyCiphertext()).startsWith("v1:").doesNotContain("example-secret-key");

        String updateWithoutSecrets = objectMapper.writeValueAsString(new ConfigUpdate(
                "TENCENT_COS",
                "https://assets.example.test",
                "var/uploads",
                "ap-guangzhou",
                "shop-assets-1250000000"
        ));
        mockMvc.perform(put("/admin/storage/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithoutSecrets))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cosSecretKeyConfigured").value(true));

        mockMvc.perform(get("/admin/storage/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicBaseUrl").value("https://assets.example.test"))
                .andExpect(jsonPath("$.data.cosSecretKey").doesNotExist());
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }

    private record SecretRow(String secretIdCiphertext, String secretKeyCiphertext) {
    }

    private record ConfigUpdate(
            String provider,
            String publicBaseUrl,
            String localRoot,
            String cosRegion,
            String cosBucket
    ) {
    }
}
