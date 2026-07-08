package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
class AdminProductSpuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanStorageTables() {
        jdbcClient.sql("delete from storage_file_usage").update();
        jdbcClient.sql("delete from storage_file").update();
    }

    @Test
    void adminCanCreatePublishListDetailUnpublishAndAdjustStockWithFileUsages() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);
        StoredFile mainFile = insertStorageFile("spu-main-create.png");
        StoredFile galleryFile = insertStorageFile("spu-gallery-create.png");
        StoredFile skuFile = insertStorageFile("spu-sku-create.png");
        StoredFile detailFile = insertStorageFile("spu-detail-create.png");
        StoredFile replacementMainFile = insertStorageFile("spu-main-update.png");
        StoredFile replacementGalleryFile = insertStorageFile("spu-gallery-update.png");
        StoredFile replacementSkuFile = insertStorageFile("spu-sku-update.png");
        StoredFile replacementDetailFile = insertStorageFile("spu-detail-update.png");

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller SPU",
                                  "subtitle": "Controller subtitle",
                                  "mainImage": "%s",
                                  "mainImageFileId": %d,
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p><img src=\\"%s\\"/></p>",
                                  "sortOrder": 1,
                                  "images": [
                                    {"url": "%s", "fileId": %d}
                                  ],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-1",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 5,
                                      "weightGram": 300,
                                      "image": "%s",
                                      "imageFileId": %d,
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(
                                categoryId,
                                mainFile.publicUrl(), mainFile.id(),
                                detailFile.publicUrl(),
                                galleryFile.publicUrl(), galleryFile.id(),
                                skuFile.publicUrl(), skuFile.id()
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .param("current", "1")
                        .param("size", "20")
                        .param("title", "Controller"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[0].status").value("ON_SALE"));

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.images[0].fileId").value(galleryFile.id()))
                .andExpect(jsonPath("$.data.skus[0].imageFileId").value(skuFile.id()))
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(5))
                .andExpect(jsonPath("$.data.skus[0].sortOrder").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        assertThat(activeUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(detailFile.id(), "PRODUCT_DETAIL_HTML", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isEqualTo(1);

        mockMvc.perform(post("/admin/product/skus/" + skuId + "/stock-adjustments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta": 3, "reason": "controller adjustment"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(8));

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller SPU",
                                  "subtitle": "Controller subtitle",
                                  "mainImage": "%s",
                                  "mainImageFileId": %d,
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p><img src=\\"%s\\"/></p>",
                                  "sortOrder": 1,
                                  "images": [
                                    {"url": "%s", "fileId": %d}
                                  ],
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-SKU-1",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 10,
                                      "weightGram": 300,
                                      "image": "%s",
                                      "imageFileId": %d,
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(
                                categoryId,
                                replacementMainFile.publicUrl(), replacementMainFile.id(),
                                replacementDetailFile.publicUrl(),
                                replacementGalleryFile.publicUrl(), replacementGalleryFile.id(),
                                skuId,
                                replacementSkuFile.publicUrl(), replacementSkuFile.id()
                        )))
                .andExpect(status().isOk());

        Integer updateStockAuditLogs = jdbcClient.sql("""
                        select count(*)
                        from stock_log
                        where sku_id = :skuId
                          and change_type = 'ADJUST'
                          and quantity_before = 8
                          and quantity_delta = 2
                          and quantity_after = 10
                          and operator_type = 'ADMIN'
                          and operator_id = 1
                        """)
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        assertThat(updateStockAuditLogs).isEqualTo(1);

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainImageFileId").value(replacementMainFile.id()))
                .andExpect(jsonPath("$.data.images[0].fileId").value(replacementGalleryFile.id()))
                .andExpect(jsonPath("$.data.skus[0].imageFileId").value(replacementSkuFile.id()))
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(10));

        assertThat(activeUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isZero();
        assertThat(removedUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(replacementMainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isZero();
        assertThat(activeUsageCount(replacementGalleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(detailFile.id(), "PRODUCT_DETAIL_HTML", "PRODUCT_SPU", spuId)).isZero();
        assertThat(activeUsageCount(replacementDetailFile.id(), "PRODUCT_DETAIL_HTML", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isZero();
        assertThat(activeUsageCount(replacementSkuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isEqualTo(1);

        mockMvc.perform(post("/admin/product/spus/" + spuId + "/unpublish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"));
    }

    @Test
    void adminSpuCreateAcceptsLegacyStringGalleryPayload() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Legacy Gallery SPU",
                                  "subtitle": "Legacy subtitle",
                                  "mainImage": "https://example.test/legacy-main.jpg",
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>legacy</p>",
                                  "sortOrder": 3,
                                  "images": ["https://example.test/legacy-gallery.jpg"],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-LEGACY",
                                      "specJson": "{\\"口味\\":\\"清汤\\"}",
                                      "specText": "清汤",
                                      "priceCent": 2990,
                                      "originalPriceCent": 3990,
                                      "stockAvailable": 6,
                                      "weightGram": 250,
                                      "image": "https://example.test/legacy-sku.jpg",
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images[0].url").value("https://example.test/legacy-gallery.jpg"));
    }

    private long createCategory(String token) throws Exception {
        String response = mockMvc.perform(post("/admin/product/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":0,"name":"SPU Controller Category %d","icon":"","sortOrder":1,"status":"ENABLED"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").asLong();
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

    private int activeUsageCount(long fileId, String usageType, String ownerType, long ownerId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_file_usage
                        where file_id = :fileId
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
                        from storage_file_usage
                        where file_id = :fileId
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
        String objectKey = "public/test/spu/" + System.nanoTime() + "-" + originalFilename;
        String publicUrl = "http://localhost:8080/files/public/test/" + originalFilename;
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('PRODUCT_IMAGE', 1, 'PUBLIC', 'LOCAL', '', :objectKey, :originalFilename,
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
                        from storage_file
                        where object_key = :objectKey
                        """)
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        assertThat(fileId).isNotNull();
        return new StoredFile(fileId, publicUrl);
    }

    private record StoredFile(Long id, String publicUrl) {
    }
}
