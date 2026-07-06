package org.muybaby.shopserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
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
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appApisExceptHealthRequireAuthentication() throws Exception {
        mockMvc.perform(get("/app/probe"))
                .andExpect(status().isUnauthorized());
    }
}
