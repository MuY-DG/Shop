package org.muybaby.shopserver.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AppAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void appLoginExchangesCodeAndIssuesAppToken() throws Exception {
        mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("apr_")))
                .andExpect(jsonPath("$.data.expiresIn").value(604800))
                .andExpect(jsonPath("$.data.user.openidMasked").value("test****code"))
                .andExpect(jsonPath("$.data.user.phoneAuthorized").value(false));
    }

    @Test
    void phoneAuthorizationRequiresAppTokenAndStoresMaskedPhone() throws Exception {
        String token = appLoginAndExtractToken();

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-phone-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phoneAuthorized").value(true))
                .andExpect(jsonPath("$.data.phoneNumberMasked").value("138****5678"));
    }

    @Test
    void adminTokenCannotAuthorizeAppPhoneApi() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(post("/app/auth/phone")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-phone-code"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
