package org.muybaby.shopserver.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingCapabilityResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminWechatShippingControllerTest {

    private static final AtomicLong LIMITED_ADMIN_ID = new AtomicLong(992_000L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private AdminCatalogProvider provider;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("delete from wechat_shipping_runtime_audit").update();
        jdbcClient.sql("delete from wechat_shipping_runtime_setting").update();
        jdbcClient.sql("delete from wechat_delivery_company").update();
        provider.mode = WechatProviderMode.REAL;
        provider.capability = WechatShippingCapabilityResult.available();
        provider.companies = List.of(
                new WechatDeliveryCompanyResult("SF", "顺丰速运"),
                new WechatDeliveryCompanyResult("JD", "京东物流")
        );
    }

    @Test
    void allWechatShippingEndpointsRequireAuthenticationAndTheirAuthorities() throws Exception {
        String noPermissionToken = adminToken(List.of());

        assertProtected(() -> get("/admin/wechat-shipping/capability"), noPermissionToken);
        assertProtected(() -> get("/admin/wechat-shipping/carriers"), noPermissionToken);
        assertProtected(() -> post("/admin/wechat-shipping/carriers/sync"), noPermissionToken);
        assertProtected(() -> get("/admin/wechat-shipping/runtime"), noPermissionToken);
        assertProtected(
                () -> put("/admin/wechat-shipping/runtime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runtimeUpdateJson(0)),
                noPermissionToken
        );
    }

    @Test
    void authorizedAdminCanPersistAndReadVersionedRuntimeControl() throws Exception {
        String token = adminToken(List.of(
                "wechat-shipping:runtime:read",
                "wechat-shipping:runtime:write"
        ));

        mockMvc.perform(get("/admin/wechat-shipping/runtime")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimePersisted").value(false))
                .andExpect(jsonPath("$.data.version").value(0));

        mockMvc.perform(put("/admin/wechat-shipping/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runtimeUpdateJson(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimePersisted").value(true))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.uploadEnabled").value(true))
                .andExpect(jsonPath("$.data.deliveryEnabled").value(true))
                .andExpect(jsonPath("$.data.receiptReconciliationEnabled").value(true));

        mockMvc.perform(put("/admin/wechat-shipping/runtime")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runtimeUpdateJson(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(600009));
    }

    @Test
    void runtimeReaderCanCheckCapabilityAfterEnablingSynchronization() throws Exception {
        String token = adminToken(List.of("wechat-shipping:runtime:read"));
        jdbcClient.sql("""
                        insert into wechat_shipping_runtime_setting (
                            id, upload_enabled, delivery_enabled,
                            receipt_reconciliation_enabled, revision, change_reason
                        ) values (1, true, false, false, 1, 'TEST_ENABLE_UPLOAD')
                        """).update();

        mockMvc.perform(get("/admin/wechat-shipping/capability")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.tradeManaged").value(true));
    }

    @Test
    void authorizedAdminCanReadCapabilityAndCachedCarriersAndSyncOfficialDirectory() throws Exception {
        String token = adminToken(List.of("order:ship"));
        jdbcClient.sql("""
                        insert into wechat_shipping_runtime_setting (
                            id, upload_enabled, delivery_enabled,
                            receipt_reconciliation_enabled, revision, change_reason
                        ) values (1, true, false, false, 1, 'TEST_ENABLE_UPLOAD')
                        """).update();
        jdbcClient.sql("""
                        insert into wechat_delivery_company(delivery_id, delivery_name, enabled, synced_at)
                        values ('OLD', '旧物流', true, :syncedAt)
                        """)
                .param("syncedAt", LocalDateTime.of(2026, 7, 1, 10, 0))
                .update();

        mockMvc.perform(get("/admin/wechat-shipping/capability")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.uploadEnabled").value(true))
                .andExpect(jsonPath("$.data.providerMode").value("REAL"))
                .andExpect(jsonPath("$.data.state").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.tradeManaged").value(true))
                .andExpect(jsonPath("$.data.checkedAt").isNotEmpty());

        mockMvc.perform(get("/admin/wechat-shipping/carriers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deliveryId").value("OLD"))
                .andExpect(jsonPath("$.data[0].deliveryName").value("旧物流"));

        mockMvc.perform(post("/admin/wechat-shipping/carriers/sync")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deliveryId").value("JD"))
                .andExpect(jsonPath("$.data[0].deliveryName").value("京东物流"))
                .andExpect(jsonPath("$.data[1].deliveryId").value("SF"))
                .andExpect(jsonPath("$.data[1].deliveryName").value("顺丰速运"));
    }

    private void assertProtected(
            Supplier<MockHttpServletRequestBuilder> requestSupplier,
            String noPermissionToken
    ) throws Exception {
        mockMvc.perform(requestSupplier.get())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(requestSupplier.get().header("Authorization", "Bearer " + noPermissionToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
    }

    private String adminToken(List<String> permissions) {
        long userId = LIMITED_ADMIN_ID.incrementAndGet();
        long roleId = userId;
        String username = "ShippingCatalogAdmin" + userId;
        insertLimitedAdmin(userId, roleId, username, permissions);

        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(userId, username, List.of(), List.of(), Instant.now())
        ).accessToken();
    }

    private String runtimeUpdateJson(long version) {
        return """
                {
                  "uploadEnabled": true,
                  "deliveryEnabled": true,
                  "receiptReconciliationEnabled": true,
                  "version": %d
                }
                """.formatted(version);
    }

    private void insertLimitedAdmin(long userId, long roleId, String username, List<String> permissions) {
        String passwordHash = jdbcClient.sql("select password_hash from admin_user where id = 1")
                .query(String.class)
                .single();
        jdbcClient.sql("""
                        insert into admin_user
                            (id, username, password_hash, display_name, email, status)
                        values
                            (:userId, :username, :passwordHash, 'Shipping Catalog Admin',
                             :email, 'ENABLED')
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("passwordHash", passwordHash)
                .param("email", username.toLowerCase() + "@shop.local")
                .update();
        jdbcClient.sql("""
                        insert into admin_role (id, code, name, description, enabled)
                        values (:roleId, :code, 'Shipping Catalog Role', '', true)
                        """)
                .param("roleId", roleId)
                .param("code", "R_SHIPPING_CATALOG_" + roleId)
                .update();
        jdbcClient.sql("insert into admin_user_role (user_id, role_id) values (:userId, :roleId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        for (String permission : permissions) {
            int inserted = jdbcClient.sql("""
                            insert into admin_role_permission (role_id, permission_id)
                            select :roleId, id from admin_permission where auth_mark = :permission
                            """)
                    .param("roleId", roleId)
                    .param("permission", permission)
                    .update();
            if (inserted != 1) {
                throw new IllegalArgumentException("Unknown test permission: " + permission);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AdminCatalogProviderTestConfiguration {

        @Bean
        @Primary
        AdminCatalogProvider adminCatalogProvider() {
            return new AdminCatalogProvider();
        }
    }

    static class AdminCatalogProvider implements WechatShippingProvider {

        private WechatProviderMode mode = WechatProviderMode.REAL;
        private WechatShippingCapabilityResult capability = WechatShippingCapabilityResult.available();
        private List<WechatDeliveryCompanyResult> companies = List.of();

        @Override
        public WechatProviderMode mode() {
            return mode;
        }

        @Override
        public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
            return WechatShippingUploadResult.unavailable("TEST_ONLY", "Test provider does not upload");
        }

        @Override
        public WechatShippingCapabilityResult queryCapability() {
            return capability;
        }

        @Override
        public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
            return companies;
        }
    }
}
