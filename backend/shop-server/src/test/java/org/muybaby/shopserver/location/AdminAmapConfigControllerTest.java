package org.muybaby.shopserver.location;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminAmapConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @BeforeEach
    void clearConfig() {
        jdbcClient.sql("delete from amap_runtime_setting").update();
    }

    @AfterEach
    void cleanupConfig() {
        clearConfig();
    }

    @Test
    void configEndpointsRequireDedicatedAuthorities() throws Exception {
        String readToken = token(List.of("amap:config:read"));
        String writeToken = token(List.of("amap:config:write"));

        mockMvc.perform(get("/admin/amap/config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/amap/config")
                        .header("Authorization", "Bearer " + writeToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/amap/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.keyConfigured").value(false));
    }

    @Test
    void savesEncryptedMiniProgramKeyAndPreservesItWhenLaterPayloadOmitsKey() throws Exception {
        String writeToken = token(List.of("amap:config:write"));
        String readToken = token(List.of("amap:config:read"));

        mockMvc.perform(put("/admin/amap/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"miniProgramKey":"amap-mini-program-key-1234"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.keyConfigured").value(true))
                .andExpect(jsonPath("$.data.miniProgramKeyMasked").value("amap******1234"));

        String ciphertext = jdbcClient.sql("""
                        select mini_program_key_ciphertext
                        from amap_runtime_setting
                        where id = 1
                        """)
                .query(String.class)
                .single();
        assertThat(ciphertext)
                .startsWith("v1:")
                .doesNotContain("amap-mini-program-key-1234");

        mockMvc.perform(put("/admin/amap/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.keyConfigured").value(true));

        mockMvc.perform(get("/admin/amap/config")
                        .header("Authorization", "Bearer " + readToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.miniProgramKey").doesNotExist());
    }

    @Test
    void cannotEnableLocationWithoutAMiniProgramKey() throws Exception {
        String writeToken = token(List.of("amap:config:write"));

        mockMvc.perform(put("/admin/amap/config")
                        .header("Authorization", "Bearer " + writeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private String token(List<String> permissions) {
        return AdminTokenTestSupport.issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }
}
