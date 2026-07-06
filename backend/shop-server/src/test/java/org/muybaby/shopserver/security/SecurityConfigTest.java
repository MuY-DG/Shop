package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.security.user.password=test-password")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appHealthIsPublic() throws Exception {
        mockMvc.perform(get("/app/health"))
                .andExpect(status().isOk());
    }

    @Test
    void adminApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/admin/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void adminApisDoNotAcceptBasicAuthentication() throws Exception {
        mockMvc.perform(get("/admin/probe")
                        .with(httpBasic("user", "test-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is(100001)))
                .andExpect(jsonPath("$.msg", is("Authentication required")));
    }

    @Test
    void appApisExceptHealthRequireAuthentication() throws Exception {
        mockMvc.perform(get("/app/probe"))
                .andExpect(status().isUnauthorized());
    }
}
