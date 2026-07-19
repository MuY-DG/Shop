package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminProductCategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanStorageTables() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();
    }

    @Test
    void adminCanCreateUpdateAndListCategoriesWithIconFileUsage() throws Exception {
        String token = loginAndExtractToken();
        StoredFile iconFile = insertStorageFile("category-icon-create.png");
        StoredFile replacementIconFile = insertStorageFile("category-icon-update.png");

        String createResponse = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category","icon":"%s","iconFileId":%d,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl(), iconFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).path("data").asLong();

        assertThat(activeUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);

        mockMvc.perform(put("/admin/product/categories/" + categoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category Updated","icon":"%s","iconFileId":%d,"sortOrder":2,"status":"ENABLED"}
                                """.formatted(replacementIconFile.publicUrl(), replacementIconFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String listResponse = mockMvc.perform(get("/admin/product/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItem("Controller Category Updated")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(findCategoryNode(listResponse, "Controller Category Updated").path("iconFileId").asLong()).isEqualTo(replacementIconFile.id());

        assertThat(activeUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isZero();
        assertThat(removedUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);
        assertThat(activeUsageCount(replacementIconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);
    }

    @Test
    void legacyCategoryUpdatePreservesIconFileIdWhenUrlIsUnchanged() throws Exception {
        String token = loginAndExtractToken();
        StoredFile iconFile = insertStorageFile("category-icon-legacy.png");

        String createResponse = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Legacy Category","icon":"%s","iconFileId":%d,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl(), iconFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(put("/admin/product/categories/" + categoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Legacy Category Updated","icon":"%s","sortOrder":2,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Long iconFileId = jdbcClient.sql("""
                        select icon_file_id
                        from product_category
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> rs.getObject("icon_file_id", Long.class))
                .optional()
                .orElse(null);
        assertThat(iconFileId).isEqualTo(iconFile.id());
        assertThat(activeUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);
    }

    @Test
    void explicitNullCategoryUpdateClearsIconFileIdWhenUrlIsUnchanged() throws Exception {
        String token = loginAndExtractToken();
        StoredFile iconFile = insertStorageFile("category-icon-clear.png");

        String createResponse = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Clear Category","icon":"%s","iconFileId":%d,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl(), iconFile.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(put("/admin/product/categories/" + categoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Clear Category","icon":"%s","iconFileId":null,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Long iconFileId = jdbcClient.sql("""
                        select icon_file_id
                        from product_category
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query((rs, rowNum) -> rs.getObject("icon_file_id", Long.class))
                .optional()
                .orElse(null);
        assertThat(iconFileId).isNull();
        assertThat(activeUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isZero();
        assertThat(removedUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);
    }

    @Test
    void adminCategoryUpdateWithoutFileIdRemovesActiveIconUsage() throws Exception {
        String token = loginAndExtractToken();
        StoredFile iconFile = insertStorageFile("category-icon-remove.png");

        String createResponse = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category Remove","icon":"%s","iconFileId":%d,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(iconFile.publicUrl(), iconFile.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long categoryId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(put("/admin/product/categories/" + categoryId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"Controller Category Removed Icon","icon":"","iconFileId":null,"sortOrder":2,"status":"ENABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(activeUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isZero();
        assertThat(removedUsageCount(iconFile.id(), "PRODUCT_CATEGORY_ICON", "PRODUCT_CATEGORY", categoryId)).isEqualTo(1);
    }

    @Test
    void categoriesAppendAtSiblingEndAndCanMoveAcrossLevels() throws Exception {
        String token = loginAndExtractToken();
        long firstRootId = createCategory(token, 0, "排序根分类一", 0);
        long secondRootId = createCategory(token, 0, "排序根分类二", 0);
        long firstChildId = createCategory(token, firstRootId, "排序子分类一", 0);
        long secondChildId = createCategory(token, firstRootId, "排序子分类二", 0);
        long targetChildId = createCategory(token, secondRootId, "目标子分类", 0);

        assertThat(categoryIds(firstRootId)).containsExactly(firstChildId, secondChildId);
        assertThat(categorySortOrders(firstRootId)).containsExactly(0, 1);

        mockMvc.perform(put("/admin/product/categories/{categoryId}/position", secondChildId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + firstRootId + ",\"index\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(categoryIds(firstRootId)).containsExactly(secondChildId, firstChildId);
        assertThat(categorySortOrders(firstRootId)).containsExactly(0, 1);

        mockMvc.perform(put("/admin/product/categories/{categoryId}/position", firstChildId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + secondRootId + ",\"index\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(categoryIds(firstRootId)).containsExactly(secondChildId);
        assertThat(categorySortOrders(firstRootId)).containsExactly(0);
        assertThat(categoryIds(secondRootId)).containsExactly(targetChildId, firstChildId);
        assertThat(categorySortOrders(secondRootId)).containsExactly(0, 1);

        mockMvc.perform(put("/admin/product/categories/{categoryId}/position", secondRootId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":" + firstChildId + ",\"index\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_CATEGORY_CYCLE.code()));

        assertThat(categoryParentId(secondRootId)).isZero();
    }

    @Test
    void appTokenCannotCallAdminCategoryApi() throws Exception {
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/product/categories")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));
    }

    private String loginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("adm_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"test-login-code"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", startsWith("app_")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private long createCategory(String token, long parentId, String name, int sortOrder) throws Exception {
        String response = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":%d,"name":"%s","icon":"","sortOrder":%d,"status":"ENABLED"}
                                """.formatted(parentId, name, sortOrder)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
    }

    private List<Long> categoryIds(long parentId) {
        return jdbcClient.sql("""
                        select id
                        from product_category
                        where parent_id = :parentId
                        order by sort_order, id
                        """)
                .param("parentId", parentId)
                .query(Long.class)
                .list();
    }

    private List<Integer> categorySortOrders(long parentId) {
        return jdbcClient.sql("""
                        select sort_order
                        from product_category
                        where parent_id = :parentId
                        order by sort_order, id
                        """)
                .param("parentId", parentId)
                .query(Integer.class)
                .list();
    }

    private long categoryParentId(long categoryId) {
        return jdbcClient.sql("select parent_id from product_category where id = :categoryId")
                .param("categoryId", categoryId)
                .query(Long.class)
                .single();
    }

    private int activeUsageCount(long fileId, String usageType, String ownerType, long ownerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = :usageType
                          and owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", fileId)
                .param("usageType", usageType)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private int removedUsageCount(long fileId, String usageType, String ownerType, long ownerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = :usageType
                          and owner_type = :ownerType
                          and owner_id = :ownerId
                          and status = 'REMOVED'
                        """)
                .param("fileId", fileId)
                .param("usageType", usageType)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private StoredFile insertStorageFile(String originalFilename) {
        String objectKey = "public/test/category/" + System.nanoTime() + "-" + originalFilename;
        String publicUrl = "http://localhost:8080/files/public/test/" + originalFilename;
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('LIBRARY', 'IMAGE', null, 'PUBLIC', 'LOCAL', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, :sha256, 1, 1, '', null,
                             :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("sha256", "sha-" + objectKey)
                .param("publicUrl", publicUrl)
                .update();
        Long fileId = jdbcClient.sql("""
                        select id
                        from storage_asset
                        where object_key = :objectKey
                        """)
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        assertThat(fileId).isNotNull();
        return new StoredFile(fileId, publicUrl);
    }

    private com.fasterxml.jackson.databind.JsonNode findCategoryNode(String response, String name) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode node : objectMapper.readTree(response).path("data")) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("Category not found in response: " + name);
    }

    private record StoredFile(Long id, String publicUrl) {
    }
}
