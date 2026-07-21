package org.muybaby.shopserver.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.location.config.AmapRuntimeConfigService;
import org.muybaby.shopserver.location.dto.AdminAmapConfigRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppLocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AmapRuntimeConfigService configService;

    @BeforeEach
    void clearConfig() {
        jdbcClient.sql("delete from amap_runtime_setting").update();
    }

    @Test
    void authenticatedAppUserReceivesEnabledMiniProgramConfig() throws Exception {
        configService.update(new AdminAmapConfigRequest(true, "amap-mini-program-key"));
        String token = appLoginToken();

        mockMvc.perform(get("/app/location/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.miniProgramKey").value("amap-mini-program-key"));
    }

    @Test
    void configEndpointRequiresAuthenticationAndHidesKeyWhenDisabled() throws Exception {
        mockMvc.perform(get("/app/location/config"))
                .andExpect(status().isUnauthorized());

        configService.update(new AdminAmapConfigRequest(true, "disabled-mini-program-key"));
        configService.update(new AdminAmapConfigRequest(false, null));
        String token = appLoginToken();
        mockMvc.perform(get("/app/location/config")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.miniProgramKey").value(""));
    }

    private String appLoginToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"amap-location-test\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/token")
                .asText();
    }
}
