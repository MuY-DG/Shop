package org.muybaby.shopserver.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginReturnsAdminTokenPair() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andExpect(jsonPath("$.data.refreshToken", startsWith("adr_")))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    void loginRejectsBadPassword() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"bad"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100002));
    }

    @Test
    void currentUserReturnsRolesAndButtons() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/auth/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.userName").value("Super"))
                .andExpect(jsonPath("$.data.roles", containsInAnyOrder("R_SUPER")))
                .andExpect(jsonPath("$.data.buttons", containsInAnyOrder(
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:menu:update"
                )));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.substring(response.indexOf("adm_"), response.indexOf("\",\"refreshToken"));
    }
}
