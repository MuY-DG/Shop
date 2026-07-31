package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminProductGuaranteeServiceControllerTest {

    private static final long ICON_FILE_ID = 99_001L;
    private static final String ICON_URL = "https://cdn.test/guarantee-icon.png";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void createUpdateFilterVisibilityAndReferencedDeleteKeepHistory() throws Exception {
        insertStorageFile();
        String token = adminLoginAndExtractToken();
        long serviceId = createService(token, serviceRequest("七天无理由", "退货保障", ICON_URL, ICON_FILE_ID, 10, true));
        long otherServiceId = createService(token, serviceRequest("正品保障", "正品承诺", "/static/authentic.png", null, 1, true));

        assertThat(activeIconUsageCount(serviceId)).isEqualTo(1);
        mockMvc.perform(get("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + token)
                        .param("current", "1")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.records[0].id").value(otherServiceId))
                .andExpect(jsonPath("$.data.records[1].id").value(serviceId));

        String updateWithoutRepeatedFileId = """
                {"termsName":"七天无理由退换","contentDescription":"签收七天内可申请退换",
                 "icon":"%s","sortOrder":3,"visible":true}
                """.formatted(ICON_URL);
        mockMvc.perform(put("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithoutRepeatedFileId))
                .andExpect(status().isOk());
        assertThat(activeIconUsageCount(serviceId)).isEqualTo(1);
        assertThat(totalIconUsageCount(serviceId)).isEqualTo(1);
        assertThat(iconFileId(serviceId)).isEqualTo(ICON_FILE_ID);

        mockMvc.perform(post("/admin/product/guarantee-services/{serviceId}/visibility", serviceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + token)
                        .param("name", "七天")
                        .param("visible", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(serviceId))
                .andExpect(jsonPath("$.data.records[0].termsName").value("七天无理由退换"))
                .andExpect(jsonPath("$.data.records[0].visible").value(false));

        insertProductAssociation(serviceId);
        insertProtectedOrderSnapshotUsage();
        mockMvc.perform(delete("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(productAssociationCount(serviceId)).isZero();
        assertThat(serviceRowCount(serviceId)).isEqualTo(1);
        assertThat(serviceDeletedAt(serviceId)).isNotNull();
        assertThat(serviceVisible(serviceId)).isFalse();
        assertThat(activeIconUsageCount(serviceId)).isZero();
        assertThat(removedIconUsageCount(serviceId)).isEqualTo(1);
        assertThat(activeOrderSnapshotUsageCount()).isEqualTo(1);
        mockMvc.perform(get("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + token)
                        .param("name", "七天"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void guaranteeEndpointsRequireTheirMethodLevelAuthorities() throws Exception {
        String unrelatedToken = limitedAdminToken(List.of("product:sku:stock"));
        String spuUpdateToken = limitedAdminToken(List.of("product:spu:update"));
        String createToken = limitedAdminToken(List.of("product:guarantee:create"));
        String updateToken = limitedAdminToken(List.of("product:guarantee:update"));
        String visibilityToken = limitedAdminToken(List.of("product:guarantee:visibility"));
        String deleteToken = limitedAdminToken(List.of("product:guarantee:delete"));
        String body = serviceRequest("权限保障", "权限测试", "/static/permission.png", null, 0, true);

        mockMvc.perform(get("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + unrelatedToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        mockMvc.perform(get("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + spuUpdateToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.PERMISSION_DENIED.code()));
        long serviceId = createService(createToken, body);

        mockMvc.perform(put("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + createToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("权限保障", "权限保障更新")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/product/guarantee-services/{serviceId}/visibility", serviceId)
                        .header("Authorization", "Bearer " + updateToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/product/guarantee-services/{serviceId}/visibility", serviceId)
                        .header("Authorization", "Bearer " + visibilityToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visible\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + visibilityToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/product/guarantee-services/{serviceId}", serviceId)
                        .header("Authorization", "Bearer " + deleteToken))
                .andExpect(status().isOk());
    }

    private long createService(String token, String body) throws Exception {
        String response = mockMvc.perform(post("/admin/product/guarantee-services")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String serviceRequest(
            String termsName,
            String description,
            String icon,
            Long iconFileId,
            int sortOrder,
            boolean visible
    ) {
        String fileIdField = iconFileId == null ? "" : ",\"iconFileId\":" + iconFileId;
        return """
                {"termsName":"%s","contentDescription":"%s","icon":"%s"%s,
                 "sortOrder":%d,"visible":%s}
                """.formatted(termsName, description, icon, fileIdField, sortOrder, visible);
    }

    private void insertStorageFile() {
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, public_url, status,
                             uploaded_by_type, uploaded_by_id)
                        values
                            (:id, 'LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS', '', 'guarantee/test-icon.png',
                             'test-icon.png', 'image/png', 'png', 128, '', :url, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("id", ICON_FILE_ID)
                .param("url", ICON_URL)
                .update();
    }

    private void insertProductAssociation(long serviceId) {
        jdbcClient.sql("""
                        insert into product_category
                            (id, parent_id, name, icon, sort_order, status)
                        values
                            (99011, 0, '保障测试分类', '', 0, 'ENABLED')
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, subtitle, main_image, selling_points, detail_html,
                             sort_order, status, spec_type, freight_template_id, virtual_sales)
                        values
                            (99012, 99011, '保障测试商品', '', '/product.png', '', '',
                             0, 'DRAFT', 'SINGLE', 1, 0)
                        """)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_guarantee_service (spu_id, service_id, sort_order)
                        values (99012, :serviceId, 0)
                        """)
                .param("serviceId", serviceId)
                .update();
    }

    private void insertProtectedOrderSnapshotUsage() {
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (asset_id, usage_type, owner_type, owner_id, owner_label, snapshot_url,
                             sort_order, protected, status)
                        values
                            (:fileId, 'ORDER_ITEM_SNAPSHOT', 'ORDER_ITEM', 99021, '历史订单保障快照', :url,
                             0, true, 'ACTIVE')
                        """)
                .param("fileId", ICON_FILE_ID)
                .param("url", ICON_URL)
                .update();
    }

    private int activeIconUsageCount(long serviceId) {
        return usageCount(serviceId, "GUARANTEE_SERVICE_ICON", "GUARANTEE_SERVICE", "ACTIVE");
    }

    private int removedIconUsageCount(long serviceId) {
        return usageCount(serviceId, "GUARANTEE_SERVICE_ICON", "GUARANTEE_SERVICE", "REMOVED");
    }

    private int totalIconUsageCount(long serviceId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'GUARANTEE_SERVICE'
                          and owner_id = :serviceId
                          and usage_type = 'GUARANTEE_SERVICE_ICON'
                        """)
                .param("serviceId", serviceId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private int usageCount(long serviceId, String usageType, String ownerType, String status) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = :ownerType
                          and owner_id = :serviceId
                          and usage_type = :usageType
                          and status = :status
                        """)
                .param("ownerType", ownerType)
                .param("serviceId", serviceId)
                .param("usageType", usageType)
                .param("status", status)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private long iconFileId(long serviceId) {
        return jdbcClient.sql("""
                        select icon_file_id
                        from product_guarantee_service
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .query(Long.class)
                .single();
    }

    private int productAssociationCount(long serviceId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from product_spu_guarantee_service
                        where service_id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private int serviceRowCount(long serviceId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from product_guarantee_service
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private LocalDateTime serviceDeletedAt(long serviceId) {
        return jdbcClient.sql("""
                        select deleted_at
                        from product_guarantee_service
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .query((rs, rowNum) -> rs.getObject("deleted_at", LocalDateTime.class))
                .single();
    }

    private boolean serviceVisible(long serviceId) {
        return jdbcClient.sql("""
                        select visible
                        from product_guarantee_service
                        where id = :serviceId
                        """)
                .param("serviceId", serviceId)
                .query(Boolean.class)
                .single();
    }

    private int activeOrderSnapshotUsageCount() {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'ORDER_ITEM'
                          and owner_id = 99021
                          and usage_type = 'ORDER_ITEM_SNAPSHOT'
                          and status = 'ACTIVE'
                        """)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
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

    private String limitedAdminToken(List<String> permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }
}
