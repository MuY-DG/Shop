package org.muybaby.shopserver.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StorageControllerTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminUploadListDetailUsagesAndMoveFlow() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        long categoryId = createCategory(adminToken, """
                {"parentId":9,"name":"测试素材","code":"TEST_ASSET_LIBRARY","description":"test","sortOrder":5,"status":"ENABLED"}
                """);

        String uploadResponse = mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "hotpot.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.url").value(startsWith("http://localhost:8080/files/public/")))
                .andExpect(jsonPath("$.data.publicUrl").value(startsWith("http://localhost:8080/files/public/")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asLong();

        jdbcClient.sql("""
                        insert into storage_file_usage
                            (file_id, usage_type, owner_type, owner_id, owner_label, snapshot_url, sort_order, protected, status)
                        values
                            (:fileId, 'PRODUCT_SPU_MAIN', 'PRODUCT_SPU', 101, '红汤锅底', 'http://localhost:8080/files/public/snapshot.png', 1, false, 'ACTIVE')
                        """)
                .param("fileId", fileId)
                .update();

        mockMvc.perform(get("/admin/files")
                        .param("current", "1")
                        .param("size", "20")
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(fileId))
                .andExpect(jsonPath("$.data.records[0].url").value(startsWith("http://localhost:8080/files/public/")));

        mockMvc.perform(get("/admin/files/{fileId}", fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fileId))
                .andExpect(jsonPath("$.data.usages[0].ownerType").value("PRODUCT_SPU"))
                .andExpect(jsonPath("$.data.usages[0].ownerLabel").value("红汤锅底"));

        mockMvc.perform(get("/admin/files/{fileId}/usages", fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].usageType").value("PRODUCT_SPU_MAIN"))
                .andExpect(jsonPath("$.data[0].protected").value(false));

        mockMvc.perform(post("/admin/files/{fileId}/move", fileId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetCategoryId\":" + categoryId + "}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Long movedCategoryId = jdbcClient.sql("select asset_category_id from storage_file where id = :fileId")
                .param("fileId", fileId)
                .query(Long.class)
                .single();
        assertThat(movedCategoryId).isEqualTo(categoryId);
    }

    @Test
    void appUploadRequiresAppTokenAndRestrictsPurposeToAfterSaleEvidence() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(multipart("/app/files/upload")
                        .file(new MockMultipartFile("file", "after-sale.png", "image/png", TINY_PNG))
                        .param("purpose", "AFTER_SALE_IMAGE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(multipart("/app/files/upload")
                        .file(new MockMultipartFile("file", "after-sale.png", "image/png", TINY_PNG))
                        .param("purpose", "AFTER_SALE_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(multipart("/app/files/upload")
                        .file(new MockMultipartFile("file", "product.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        String privateUploadResponse = mockMvc.perform(multipart("/app/files/upload")
                        .file(new MockMultipartFile("file", "after-sale.png", "image/png", TINY_PNG))
                        .param("purpose", "AFTER_SALE_IMAGE")
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.url").doesNotExist())
                .andExpect(jsonPath("$.data.publicUrl").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long fileId = objectMapper.readTree(privateUploadResponse).path("data").path("id").asLong();
        String uploadedByType = jdbcClient.sql("select uploaded_by_type from storage_file where id = :fileId")
                .param("fileId", fileId)
                .query(String.class)
                .single();
        assertThat(uploadedByType).isEqualTo("APP");
    }

    @Test
    void categoryTreeCrudRequiresAdminToken() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        String appToken = appLoginAndExtractToken();

        mockMvc.perform(get("/admin/file-categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(post("/admin/file-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":9,"name":"拒绝创建","code":"REJECTED","description":"nope","sortOrder":1,"status":"ENABLED"}
                                """)
                        .header("Authorization", "Bearer " + appToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(100001));

        long categoryId = createCategory(adminToken, """
                {"parentId":9,"name":"售后补图","code":"AFTER_SALE_REVIEW","description":"before","sortOrder":9,"status":"ENABLED"}
                """);
        long secondCategoryId = createCategory(adminToken, """
                {"parentId":9,"name":"售后补图二级","code":"AFTER_SALE_REVIEW_SECOND","description":"second","sortOrder":10,"status":"ENABLED"}
                """);

        assertThat(categoryId).isGreaterThan(9L);
        assertThat(secondCategoryId).isGreaterThan(categoryId);

        mockMvc.perform(put("/admin/file-categories/{categoryId}", categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":9,"name":"售后补图已更新","code":"AFTER_SALE_REVIEW","description":"after","sortOrder":11,"status":"DISABLED"}
                                """)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/file-categories")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..code", hasItem("AFTER_SALE_REVIEW")))
                .andExpect(jsonPath("$..name", hasItem("售后补图已更新")));

        String categoryStatus = jdbcClient.sql("select status from storage_asset_category where id = :categoryId")
                .param("categoryId", categoryId)
                .query(String.class)
                .single();
        assertThat(categoryStatus).isEqualTo("DISABLED");
    }

    @Test
    void publicRouteServesOnlyActivePublicFiles() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        String uploadResponse = mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "public.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode file = objectMapper.readTree(uploadResponse).path("data");
        long fileId = file.path("id").asLong();
        String path = UriComponentsBuilder.fromUriString(file.path("url").asText()).build().getPath();

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(TINY_PNG));

        jdbcClient.sql("update storage_file set visibility = 'PRIVATE' where id = :fileId")
                .param("fileId", fileId)
                .update();

        mockMvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));

        jdbcClient.sql("update storage_file set visibility = 'PUBLIC', status = 'DELETED', deleted_at = current_timestamp where id = :fileId")
                .param("fileId", fileId)
                .update();

        mockMvc.perform(get(path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_FILE_UNAVAILABLE.code()));
    }

    @Test
    void deleteIsBlockedByActiveProtectedUsageAndSoftDeletesAfterUsageRemoved() throws Exception {
        String adminToken = adminLoginAndExtractToken();
        String uploadResponse = mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "delete-me.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asLong();

        jdbcClient.sql("""
                        insert into storage_file_usage
                            (file_id, usage_type, owner_type, owner_id, owner_label, snapshot_url, sort_order, protected, status)
                        values
                            (:fileId, 'ORDER_ITEM_SNAPSHOT', 'ORDER_ITEM', 9001, '订单 #9001', 'snapshot-url', 1, true, 'ACTIVE')
                        """)
                .param("fileId", fileId)
                .update();

        mockMvc.perform(delete("/admin/files/{fileId}", fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(800003));

        mockMvc.perform(get("/admin/files/{fileId}", fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usages[0].ownerType").value("ORDER_ITEM"))
                .andExpect(jsonPath("$.data.usages[0].ownerLabel").value("订单 #9001"))
                .andExpect(jsonPath("$.data.usages[0].protected").value(true));

        jdbcClient.sql("update storage_file_usage set status = 'REMOVED' where file_id = :fileId")
                .param("fileId", fileId)
                .update();

        mockMvc.perform(delete("/admin/files/{fileId}", fileId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String statusValue = jdbcClient.sql("select status from storage_file where id = :fileId")
                .param("fileId", fileId)
                .query(String.class)
                .single();
        assertThat(statusValue).isEqualTo("DELETED");
    }

    @Test
    void uploadRejectsUnsupportedExtensionOversizeEmptyCorruptedImageAndPathTraversal() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "not-image.svg", "image/svg+xml", "<svg/>".getBytes()))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "too-large.png", "image/png", new byte[6 * 1024 * 1024]))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "corrupted.png", "image/png", "broken-image".getBytes()))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "../hotpot.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()));
    }

    @Test
    void uploadBindingFailuresStillReturnApiResponseEnvelope() throws Exception {
        String adminToken = adminLoginAndExtractToken();

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "hotpot.png", "image/png", TINY_PNG))
                        .param("purpose", "NOT_A_PURPOSE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.code()))
                .andExpect(jsonPath("$.msg").value(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED.message()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .param("purpose", "PRODUCT_IMAGE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.msg").value(ErrorCode.VALIDATION_FAILED.message()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "hotpot.png", "image/png", TINY_PNG))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.msg").value(ErrorCode.VALIDATION_FAILED.message()));

        mockMvc.perform(multipart("/admin/files/upload")
                        .file(new MockMultipartFile("file", "hotpot.png", "image/png", TINY_PNG))
                        .param("purpose", "PRODUCT_IMAGE")
                        .param("assetCategoryId", "oops")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.msg").value(ErrorCode.VALIDATION_FAILED.message()));
    }

    private long createCategory(String adminToken, String body) throws Exception {
        String response = mockMvc.perform(post("/admin/file-categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
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

    private String appLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/app/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"storage-controller-test"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
