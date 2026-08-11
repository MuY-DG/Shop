package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.support.AdminTokenTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminWechatServiceCardRuntimeSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearRuntimeOverride() {
        jdbcClient.sql("delete from wechat_service_card_runtime_audit").update();
        jdbcClient.sql("delete from wechat_service_card_runtime_setting").update();
    }

    @Test
    void runtimeEndpointsEnforceAuthenticationAndDedicatedAuthorities() throws Exception {
        String readToken = token(List.of("wechat-service-card:read"));
        String writeToken = token(List.of("wechat-service-card:runtime:write"));
        String body = updateBody(0, "verified disabled baseline");

        mockMvc.perform(get("/admin/wechat-service-cards/status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/wechat-service-cards/status")
                        .header("Authorization", bearer(readToken)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                        .header("Authorization", bearer(readToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                        .header("Authorization", bearer(writeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimePersisted").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.captureEnabled").value(false))
                .andExpect(jsonPath("$.data.workerEnabled").value(false));
    }

    @Test
    void staleVersionReturnsConflictAndDoesNotAppendAnotherAudit() throws Exception {
        String writeToken = token(List.of("wechat-service-card:runtime:write"));

        mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                        .header("Authorization", bearer(writeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(0, "persist disabled baseline")))
                .andExpect(status().isOk());

        mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                        .header("Authorization", bearer(writeToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(0, "stale concurrent update")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.WECHAT_SERVICE_CARD_RUNTIME_CONFLICT.code()));

        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void unknownSecretAndMisspelledFieldsAreRejectedWithoutEchoOrPersistence() throws Exception {
        String writeToken = token(List.of("wechat-service-card:runtime:write"));
        for (String field : List.of("callbackToken", "encodingAesKey", "workerEnabeld")) {
            String marker = "must-not-echo-" + field;
            String body = """
                    {
                      "captureEnabled": false,
                      "workerEnabled": false,
                      "version": 0,
                      "reason": "reject unknown secret field",
                      "%s": "%s"
                    }
                    """.formatted(field, marker);

            String response = mockMvc.perform(put("/admin/wechat-service-cards/runtime")
                            .header("Authorization", bearer(writeToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertThat(response).doesNotContain(marker);
        }

        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_setting")
                .query(Long.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from wechat_service_card_runtime_audit")
                .query(Long.class)
                .single()).isZero();
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(
                jdbcClient, opaqueTokenService, permissions
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String updateBody(long version, String reason) {
        return """
                {
                  "captureEnabled": false,
                  "workerEnabled": false,
                  "version": %d,
                  "reason": "%s"
                }
                """.formatted(version, reason);
    }
}
