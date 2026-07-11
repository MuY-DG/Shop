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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminWechatShippingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ShippingProperties shippingProperties;

    @Autowired
    private AdminCatalogProvider provider;

    @BeforeEach
    void resetState() {
        jdbcClient.sql("delete from wechat_delivery_company").update();
        shippingProperties.setUploadEnabled(true);
        provider.mode = WechatProviderMode.REAL;
        provider.capability = WechatShippingCapabilityResult.available();
        provider.companies = List.of(
                new WechatDeliveryCompanyResult("SF", "顺丰速运"),
                new WechatDeliveryCompanyResult("JD", "京东物流")
        );
    }

    @Test
    void allCatalogEndpointsRequireAuthenticationAndOrderShipAuthority() throws Exception {
        String noPermissionToken = adminToken(List.of());

        assertProtected(() -> get("/admin/wechat-shipping/capability"), noPermissionToken);
        assertProtected(() -> get("/admin/wechat-shipping/carriers"), noPermissionToken);
        assertProtected(() -> post("/admin/wechat-shipping/carriers/sync"), noPermissionToken);
    }

    @Test
    void authorizedAdminCanReadCapabilityAndCachedCarriersAndSyncOfficialDirectory() throws Exception {
        String token = adminToken(List.of("order:ship"));
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
        return opaqueTokenService.issue(
                TokenKind.ADMIN,
                TokenSession.admin(1L, "shipping-admin", List.of("R_SUPER"), permissions, Instant.now())
        ).accessToken();
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
