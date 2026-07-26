package org.muybaby.shopserver.health;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogRecorder;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.analytics.AppUserDailyActivityService;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.security.PathTokenKindResolver;
import org.muybaby.shopserver.security.web.ClientIpResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PathTokenKindResolver pathTokenKindResolver;

    @MockitoBean
    private OpaqueTokenService opaqueTokenService;

    @MockitoBean
    private AdminRbacService adminRbacService;

    @MockitoBean
    private AppUserDailyActivityService appUserDailyActivityService;

    @MockitoBean
    private AdminSystemLogRecorder adminSystemLogRecorder;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void appHealthReturnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/app/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("success")))
                .andExpect(jsonPath("$.data.status", is("UP")))
                .andExpect(jsonPath("$.data.service", is("shop-server")));
    }
}
