package org.muybaby.shopserver.admin.rbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.support.AdminTokenTestSupport;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminMenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

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
                        "/trade",
                        "/decoration",
                        "/development",
                        "/system"
                )))
                .andExpect(jsonPath("$.data[0].path").value("/dashboard"))
                .andExpect(jsonPath("$.data[0].component").value("/index/index"))
                .andExpect(jsonPath("$.data[1].path").value("/product"))
                .andExpect(jsonPath("$.data[1].children[*].path", contains(
                        "category",
                        "spu",
                        "spec-template",
                        "guarantee-service"
                )))
                .andExpect(jsonPath("$.data[1].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "product:category:create",
                        "product:category:update"
                )))
                .andExpect(jsonPath("$.data[1].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "product:spu:create",
                        "product:spu:update",
                        "product:spu:publish",
                        "product:sku:stock",
                        "product:spu:delete",
                        "product:spu:restore",
                        "product:spu:purge",
                        "product:freight:create",
                        "product:freight:update",
                        "product:coupon:bind",
                        "product:coupon:create"
                )))
                .andExpect(jsonPath("$.data[1].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "product:spec-template:create",
                        "product:spec-template:update"
                )))
                .andExpect(jsonPath("$.data[1].children[3].meta.authList[*].authMark", containsInAnyOrder(
                        "product:guarantee:create",
                        "product:guarantee:update",
                        "product:guarantee:delete",
                        "product:guarantee:visibility"
                )))
                .andExpect(jsonPath("$.data[2].path").value("/marketing"))
                .andExpect(jsonPath("$.data[2].children[*].path", contains("coupon")))
                .andExpect(jsonPath("$.data[2].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "coupon:template:create",
                        "coupon:template:update",
                        "coupon:template:enable",
                        "coupon:template:disable"
                )))
                .andExpect(jsonPath("$.data[3].path").value("/trade"))
                .andExpect(jsonPath("$.data[3].children[*].path", contains(
                        "orders", "after-sales", "customer-service"
                )))
                .andExpect(jsonPath("$.data[3].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "order:read",
                        "order:close",
                        "order:ship",
                        "order:shipping:retry"
                )))
                .andExpect(jsonPath("$.data[3].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "aftersale:read",
                        "aftersale:audit"
                )))
                .andExpect(jsonPath("$.data[3].children[2].component").value("/customer-service/index"))
                .andExpect(jsonPath("$.data[3].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "customer-service:conversation:read",
                        "customer-service:conversation:claim",
                        "customer-service:conversation:transfer",
                        "customer-service:conversation:close",
                        "customer-service:message:send",
                        "customer-service:order:link",
                        "customer-service:product:send",
                        "customer-service:agent:manage"
                )))
                .andExpect(jsonPath("$.data[4].path").value("/decoration"))
                .andExpect(jsonPath("$.data[4].children[*].path", contains(
                        "banner",
                        "category",
                        "hot-products",
                        "recommended-products",
                        "contact",
                        "assets"
                )))
                .andExpect(jsonPath("$.data[4].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "content:banner:read",
                        "content:banner:create",
                        "content:banner:update",
                        "content:banner:publish"
                )))
                .andExpect(jsonPath("$.data[4].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "content:home-category:read",
                        "content:home-category:write"
                )))
                .andExpect(jsonPath("$.data[4].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "content:home-hot:read",
                        "content:home-hot:write"
                )))
                .andExpect(jsonPath("$.data[4].children[3].meta.authList[*].authMark", containsInAnyOrder(
                        "content:home-recommended:read",
                        "content:home-recommended:write"
                )))
                .andExpect(jsonPath("$.data[4].children[4].meta.authList[*].authMark", containsInAnyOrder(
                        "content:contact:read",
                        "content:contact:write"
                )))
                .andExpect(jsonPath("$.data[4].children[5].meta.authList[*].authMark", containsInAnyOrder(
                        "asset:upload",
                        "asset:read",
                        "asset:delete",
                        "asset:folder"
                )))
                .andExpect(jsonPath("$.data[5].path").value("/development"))
                .andExpect(jsonPath("$.data[5].component").value("/index/index"))
                .andExpect(jsonPath("$.data[5].children[*].path", contains("storage", "payment")))
                .andExpect(jsonPath("$.data[5].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "storage:config:read",
                        "storage:config:write"
                )))
                .andExpect(jsonPath("$.data[5].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "payment:config:read",
                        "payment:config:write",
                        "payment:config:enable"
                )))
                .andExpect(jsonPath("$.data[6].path").value("/system"))
                .andExpect(jsonPath("$.data[6].children[0].path").value("user"))
                .andExpect(jsonPath("$.data[6].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "system:user:read",
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable"
                )))
                .andExpect(jsonPath("$.data[6].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "system:role:read",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:role:delete"
                )))
                .andExpect(jsonPath("$.data[6].children[2].path").value("menu"))
                .andExpect(jsonPath("$.data[6].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "system:menu:read"
                )));
    }

    @Test
    void menuReadPermissionCanLoadTheFullAccessCatalog() throws Exception {
        String token = AdminTokenTestSupport.issueAdminToken(
                jdbcClient,
                opaqueTokenService,
                List.of("system:menu:read")
        );

        mockMvc.perform(get("/admin/system/access-catalog")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].path", contains(
                        "/dashboard",
                        "/product",
                        "/marketing",
                        "/trade",
                        "/decoration",
                        "/development",
                        "/system"
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
