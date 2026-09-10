package org.muybaby.shopserver.content;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisplayConfigControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private OpaqueTokenService tokenService;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void resetName() {
        jdbcClient.sql("UPDATE app_display_config SET display_name = '蜀香序', revision = 1, updated_by = NULL")
                .update();
    }

    @Test
    void anonymousClientsReceiveOnlyTheDisplayName() throws Exception {
        var response = mockMvc.perform(get("/app/display-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.displayName").value("蜀香序"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).path("data").size()).isOne();
        mockMvc.perform(put("/app/display-config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"未经授权\",\"version\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminReadAndWriteRequireTheExistingPlatformPermissions() throws Exception {
        String reader = token("wechat-platform:config:read");
        mockMvc.perform(get("/admin/display-config")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/display-config").header("Authorization", "Bearer " + token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/display-config").header("Authorization", "Bearer " + reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(put("/admin/display-config").header("Authorization", "Bearer " + reader)
                        .contentType(MediaType.APPLICATION_JSON).content(body("新名称", 1)))
                .andExpect(status().isForbidden());
    }

    @Test
    void savingNameIsIndependentOfCredentialsAndImmediatelyVisibleToAnonymousClients() throws Exception {
        String writer = token("wechat-platform:config:write");
        mockMvc.perform(put("/admin/display-config").header("Authorization", "Bearer " + writer)
                        .contentType(MediaType.APPLICATION_JSON).content(body("  蜀香序甄选  ", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("蜀香序甄选"))
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(get("/app/display-config"))
                .andExpect(jsonPath("$.data.displayName").value("蜀香序甄选"));
        assertThat(jdbcClient.sql("SELECT updated_by FROM app_display_config WHERE id = 1")
                .query(Long.class).single()).isPositive();

        mockMvc.perform(put("/admin/display-config").header("Authorization", "Bearer " + writer)
                        .contentType(MediaType.APPLICATION_JSON).content(body("过期覆盖", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DISPLAY_CONFIG_CONFLICT.code()));
        mockMvc.perform(get("/app/display-config"))
                .andExpect(jsonPath("$.data.displayName").value("蜀香序甄选"));
    }

    @Test
    void rejectsBlankOversizedAndControlCharacterNamesWithoutChangingTheConfig() throws Exception {
        String writer = token("wechat-platform:config:write");
        for (String name : List.of("", "   ", "名称".repeat(17), "蜀\n香序")) {
            mockMvc.perform(put("/admin/display-config").header("Authorization", "Bearer " + writer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(name, 1)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
        }
        mockMvc.perform(get("/app/display-config"))
                .andExpect(jsonPath("$.data.displayName").value("蜀香序"));
    }

    private String token(String... permissions) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, tokenService, List.of(permissions));
    }

    private String body(String name, long version) throws Exception {
        return objectMapper.writeValueAsString(Map.of("displayName", name, "version", version));
    }
}
