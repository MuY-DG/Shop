package org.muybaby.shopserver.admin.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void menuApiReturnsArtDesignProRouteTreeWithAuthList() throws Exception {
        String token = loginAndExtractToken();

        mockMvc.perform(get("/admin/system/menus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].path", contains("/dashboard", "/product", "/marketing", "/system")))
                .andExpect(jsonPath("$.data[0].path").value("/dashboard"))
                .andExpect(jsonPath("$.data[0].component").value("/index/index"))
                .andExpect(jsonPath("$.data[1].path").value("/product"))
                .andExpect(jsonPath("$.data[1].children[*].path", contains("category", "spu")))
                .andExpect(jsonPath("$.data[1].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "product:category:create",
                        "product:category:update"
                )))
                .andExpect(jsonPath("$.data[1].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "product:spu:create",
                        "product:spu:update",
                        "product:spu:publish",
                        "product:sku:stock"
                )))
                .andExpect(jsonPath("$.data[2].path").value("/marketing"))
                .andExpect(jsonPath("$.data[2].children[*].path", contains("coupon")))
                .andExpect(jsonPath("$.data[2].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "coupon:template:create",
                        "coupon:template:update",
                        "coupon:template:enable",
                        "coupon:template:disable"
                )))
                .andExpect(jsonPath("$.data[3].path").value("/system"))
                .andExpect(jsonPath("$.data[3].children[0].path").value("user"))
                .andExpect(jsonPath("$.data[3].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable"
                )))
                .andExpect(jsonPath("$.data[3].children[2].path").value("menu"))
                .andExpect(jsonPath("$.data[3].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "system:menu:update",
                        "add"
                )));
    }

    private String loginAndExtractToken() throws Exception {
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
