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
                .andExpect(jsonPath("$.data[*].path", contains(
                        "/dashboard",
                        "/product",
                        "/marketing",
                        "/order",
                        "/storage/files",
                        "/content/banner",
                        "/payment",
                        "/aftersale",
                        "/system"
                )))
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
                .andExpect(jsonPath("$.data[3].path").value("/order"))
                .andExpect(jsonPath("$.data[3].children[*].path", contains("list")))
                .andExpect(jsonPath("$.data[3].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "order:read",
                        "order:close",
                        "order:ship",
                        "order:shipping:retry"
                )))
                .andExpect(jsonPath("$.data[4].path").value("/storage/files"))
                .andExpect(jsonPath("$.data[4].meta.authList[*].authMark", containsInAnyOrder(
                        "file:upload",
                        "file:read",
                        "file:delete",
                        "file:category"
                )))
                .andExpect(jsonPath("$.data[5].path").value("/content/banner"))
                .andExpect(jsonPath("$.data[5].meta.authList[*].authMark", containsInAnyOrder(
                        "content:banner:read",
                        "content:banner:create",
                        "content:banner:update",
                        "content:banner:publish"
                )))
                .andExpect(jsonPath("$.data[6].path").value("/payment"))
                .andExpect(jsonPath("$.data[6].children[*].path", contains("config")))
                .andExpect(jsonPath("$.data[6].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "payment:config:read",
                        "payment:config:write",
                        "payment:config:enable"
                )))
                .andExpect(jsonPath("$.data[7].path").value("/aftersale"))
                .andExpect(jsonPath("$.data[7].children[*].path", contains("list")))
                .andExpect(jsonPath("$.data[7].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "aftersale:read",
                        "aftersale:audit"
                )))
                .andExpect(jsonPath("$.data[8].path").value("/system"))
                .andExpect(jsonPath("$.data[8].children[0].path").value("user"))
                .andExpect(jsonPath("$.data[8].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "system:user:read",
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable"
                )))
                .andExpect(jsonPath("$.data[8].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "system:role:read",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:role:delete"
                )))
                .andExpect(jsonPath("$.data[8].children[2].path").value("menu"))
                .andExpect(jsonPath("$.data[8].children[2].meta.authList[*].authMark", containsInAnyOrder(
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
