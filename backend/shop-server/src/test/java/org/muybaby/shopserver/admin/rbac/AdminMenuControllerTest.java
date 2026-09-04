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
                        "/operations",
                        "/product",
                        "/marketing",
                        "/customers",
                        "/trade",
                        "/customer-service",
                        "/customer-service-management",
                        "/decoration",
                        "/development",
                        "/audit-log",
                        "/system",
                        "/compliance",
                        "/finance"
                )))
                .andExpect(jsonPath("$.data[0].path").value("/operations"))
                .andExpect(jsonPath("$.data[0].component").value("/index/index"))
                .andExpect(jsonPath("$.data[0].children[*].path", contains(
                        "overview",
                        "trade-statistics",
                        "product-statistics",
                        "user-statistics",
                        "traffic-statistics",
                        "marketing-statistics",
                        "service-statistics"
                )))
                .andExpect(jsonPath("$.data[0].children[*].component", contains(
                        "/operations/overview",
                        "/operations/trade-statistics",
                        "/operations/product-statistics",
                        "/operations/user-statistics",
                        "/operations/traffic-statistics",
                        "/operations/marketing-statistics",
                        "/operations/service-statistics"
                )))
                .andExpect(jsonPath("$.data[0].children[0].meta.authList[*].authMark", contains(
                        "operation:overview:read"
                )))
                .andExpect(jsonPath("$.data[0].children[1].meta.authList[*].authMark", contains(
                        "operation:trade:read"
                )))
                .andExpect(jsonPath("$.data[1].path").value("/product"))
                .andExpect(jsonPath("$.data[1].children[*].path", contains(
                        "category",
                        "spu",
                        "spec-template",
                        "guarantee-service",
                        "parameter",
                        "review"
                )))
                .andExpect(jsonPath("$.data[1].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "product:category:create",
                        "product:category:update",
                        "product:category:delete"
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
                .andExpect(jsonPath("$.data[1].children[4].meta.authList[*].authMark", containsInAnyOrder(
                        "product:parameter:read",
                        "product:parameter:write"
                )))
                .andExpect(jsonPath("$.data[1].children[5].meta.authList[*].authMark", containsInAnyOrder(
                        "product:review:read",
                        "product:review:moderate"
                )))
                .andExpect(jsonPath("$.data[2].path").value("/marketing"))
                .andExpect(jsonPath("$.data[2].children[*].path", contains("coupon")))
                .andExpect(jsonPath("$.data[2].children[0].component").value(""))
                .andExpect(jsonPath("$.data[2].children[0].children[*].path", contains(
                        "templates",
                        "claim-records"
                )))
                .andExpect(jsonPath("$.data[2].children[0].children[0].component").value("/marketing/coupon"))
                .andExpect(jsonPath("$.data[2].children[0].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "coupon:template:create",
                        "coupon:template:update",
                        "coupon:template:enable",
                        "coupon:template:disable"
                )))
                .andExpect(jsonPath("$.data[2].children[0].children[1].component").value("/marketing/coupon-claim"))
                .andExpect(jsonPath("$.data[2].children[0].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "coupon:claim:read"
                )))
                .andExpect(jsonPath("$.data[3].path").value("/customers"))
                .andExpect(jsonPath("$.data[3].component").value("/customer/user"))
                .andExpect(jsonPath("$.data[3].meta.authList[*].authMark", containsInAnyOrder(
                        "customer:user:read",
                        "customer:coupon:issue",
                        "customer:user:status"
                )))
                .andExpect(jsonPath("$.data[11].children[*].path", contains(
                        "merchant", "documents", "cancellations"
                )))
                .andExpect(jsonPath("$.data[11].children[2].component")
                        .value("/compliance/cancellations"))
                .andExpect(jsonPath("$.data[11].children[2].meta.authList[*].authMark", contains(
                        "compliance:cancellation:read"
                )))
                .andExpect(jsonPath("$.data[4].path").value("/trade"))
                .andExpect(jsonPath("$.data[4].children[*].path", contains(
                        "orders", "after-sales", "logistics-config"
                )))
                .andExpect(jsonPath("$.data[4].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "order:read",
                        "order:close",
                        "order:ship",
                        "order:shipping:retry",
                        "order:waybill:manage",
                        "order:waybill:print",
                        "order:waybill:test",
                        "order:shipping:registration:retry",
                        "order:shipping:tracking:sync"
                )))
                .andExpect(jsonPath("$.data[4].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "aftersale:read",
                        "aftersale:audit",
                        "aftersale:return-address:write"
                )))
                .andExpect(jsonPath("$.data[4].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "logistics:express:config:read",
                        "logistics:express:config:write",
                        "wechat-shipping:runtime:read",
                        "wechat-shipping:runtime:write"
                )))
                .andExpect(jsonPath("$.data[5].path").value("/customer-service"))
                .andExpect(jsonPath("$.data[5].component").value("/customer-service/index"))
                .andExpect(jsonPath("$.data[5].meta.authList[*].authMark", containsInAnyOrder(
                        "customer-service:conversation:read",
                        "customer-service:conversation:claim",
                        "customer-service:conversation:transfer",
                        "customer-service:conversation:close",
                        "customer-service:message:send",
                        "customer-service:order:link",
                        "customer-service:product:send",
                        "customer-service:agent:manage",
                        "customer-service:conversation:supervise",
                        "customer-service:quick-reply:read"
                )))
                .andExpect(jsonPath("$.data[5].meta.isFullPage").value(true))
                .andExpect(jsonPath("$.data[5].children[*].path", contains(
                        "overview",
                        "settings"
                )))
                .andExpect(jsonPath("$.data[5].children[0].component")
                        .value("/customer-service/overview"))
                .andExpect(jsonPath("$.data[5].children[1].component")
                        .value("/customer-service/settings"))
                .andExpect(jsonPath("$.data[6].path").value("/customer-service-management"))
                .andExpect(jsonPath("$.data[6].children[*].path", contains(
                        "members"
                )))
                .andExpect(jsonPath("$.data[6].children[0].component")
                        .value("/customer-service-management/members"))
                .andExpect(jsonPath("$.data[6].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "customer-service:agent:manage",
                        "customer-service:management:read",
                        "customer-service:identity:update"
                )))
                .andExpect(jsonPath("$.data[7].path").value("/decoration"))
                .andExpect(jsonPath("$.data[7].children[*].path", contains(
                        "home",
                        "contact",
                        "assets"
                )))
                .andExpect(jsonPath("$.data[7].children[0].component").value("/content/home-decoration"))
                .andExpect(jsonPath("$.data[7].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "content:banner:read",
                        "content:banner:create",
                        "content:banner:update",
                        "content:banner:publish",
                        "content:home-category:read",
                        "content:home-category:write",
                        "content:home-hot:read",
                        "content:home-hot:write",
                        "content:home-recommended:read",
                        "content:home-recommended:write"
                )))
                .andExpect(jsonPath("$.data[7].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "content:contact:read",
                        "content:contact:write"
                )))
                .andExpect(jsonPath("$.data[7].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "asset:upload",
                        "asset:read",
                        "asset:delete",
                        "asset:folder"
                )))
                .andExpect(jsonPath("$.data[8].path").value("/development"))
                .andExpect(jsonPath("$.data[8].component").value("/index/index"))
                .andExpect(jsonPath("$.data[8].children[*].path", contains(
                        "storage",
                        "payment",
                        "data-cleanup",
                        "wechat-platform"
                )))
                .andExpect(jsonPath("$.data[8].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "storage:config:read",
                        "storage:config:write"
                )))
                .andExpect(jsonPath("$.data[8].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "payment:config:read",
                        "payment:config:write",
                        "payment:config:enable",
                        "payment:config:delete"
                )))
                .andExpect(jsonPath("$.data[8].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "data-cleanup:config:read",
                        "data-cleanup:config:write"
                )))
                .andExpect(jsonPath("$.data[8].children[3].component")
                        .value("/configuration/wechat-platform"))
                .andExpect(jsonPath("$.data[8].children[3].meta.authList[*].authMark", containsInAnyOrder(
                        "wechat-platform:config:read",
                        "wechat-platform:config:write"
                )))
                .andExpect(jsonPath("$.data[9].path").value("/audit-log"))
                .andExpect(jsonPath("$.data[9].children[*].path", contains(
                        "operation",
                        "security",
                        "exceptions",
                        "requests",
                        "tasks"
                )))
                .andExpect(jsonPath("$.data[9].children[0].meta.authList[*].authMark", contains(
                        "system:log:read"
                )))
                .andExpect(jsonPath("$.data[9].children[4].meta.authList[*].authMark", containsInAnyOrder(
                        "system:log:read",
                        "data-cleanup:config:read"
                )))
                .andExpect(jsonPath("$.data[10].path").value("/system"))
                .andExpect(jsonPath("$.data[10].children[*].path", contains(
                        "user",
                        "role",
                        "menu"
                )))
                .andExpect(jsonPath("$.data[10].children[0].path").value("user"))
                .andExpect(jsonPath("$.data[10].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "system:user:read",
                        "system:user:create",
                        "system:user:update",
                        "system:user:disable",
                        "system:user:session:read",
                        "system:user:session:revoke"
                )))
                .andExpect(jsonPath("$.data[10].children[1].meta.authList[*].authMark", containsInAnyOrder(
                        "system:role:read",
                        "system:role:create",
                        "system:role:update",
                        "system:role:assign",
                        "system:role:delete"
                )))
                .andExpect(jsonPath("$.data[10].children[2].path").value("menu"))
                .andExpect(jsonPath("$.data[10].children[2].meta.authList[*].authMark", containsInAnyOrder(
                        "system:menu:read"
                )))
                .andExpect(jsonPath("$.data[12].path").value("/finance"))
                .andExpect(jsonPath("$.data[12].component").value("/index/index"))
                .andExpect(jsonPath("$.data[12].children[*].path", contains("reconciliation")))
                .andExpect(jsonPath("$.data[12].children[0].component")
                        .value("/finance/reconciliation/index"))
                .andExpect(jsonPath("$.data[12].children[0].meta.authList[*].authMark", containsInAnyOrder(
                        "finance:reconciliation:read",
                        "finance:reconciliation:run",
                        "finance:reconciliation:resolve",
                        "finance:reconciliation:source-download",
                        "finance:export",
                        "finance:reconciliation:runtime:write"
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
                        "/operations",
                        "/product",
                        "/marketing",
                        "/customers",
                        "/trade",
                        "/customer-service",
                        "/customer-service-management",
                        "/decoration",
                        "/development",
                        "/audit-log",
                        "/system",
                        "/compliance",
                        "/guest",
                        "/finance"
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
