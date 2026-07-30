package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.muybaby.shopserver.support.TestHashSupport.sha256;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @AfterEach
    void cleanStorageTables() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();
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
                                  "virtualSales": 5,
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
                                      "lowStockThreshold": 6,
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
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].status".formatted(spuId))
                        .value(contains("ON_SALE")))
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].actualSales".formatted(spuId))
                        .value(contains(0)))
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].virtualSales".formatted(spuId))
                        .value(contains(5)))
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].displaySales".formatted(spuId))
                        .value(contains(5)));

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.images[0].fileId").value(galleryFile.id()))
                .andExpect(jsonPath("$.data.skus[0].imageFileId").value(skuFile.id()))
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(5))
                .andExpect(jsonPath("$.data.skus[0].lowStockThreshold").value(6))
                .andExpect(jsonPath("$.data.skus[0].sortOrder").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        mockMvc.perform(put("/admin/product/skus/" + skuId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lowStockThreshold\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/admin/product/skus/" + skuId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lowStockThreshold\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].lowStockThreshold").value(3));

        long paidOrderId = insertSalesOrder("PAID", true);
        long refundedOrderId = insertSalesOrder("REFUNDED", true);
        long unpaidOrderId = insertSalesOrder("PENDING_PAYMENT", false);
        insertSalesOrderItem(paidOrderId, spuId, skuId, 2);
        insertSalesOrderItem(refundedOrderId, spuId, skuId, 3);
        insertSalesOrderItem(unpaidOrderId, spuId, skuId, 7);

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .param("title", "Controller SPU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].actualSales".formatted(spuId))
                        .value(contains(5)))
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].virtualSales".formatted(spuId))
                        .value(contains(5)))
                .andExpect(jsonPath("$.data.records[?(@.id == %d)].displaySales".formatted(spuId))
                        .value(contains(10)));

        assertThat(activeUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(detailFile.id(), "PRODUCT_DETAIL_HTML", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isEqualTo(1);

        mockMvc.perform(post("/admin/product/skus/" + skuId + "/stock-adjustments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "missing quantity"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));

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
                                      "lowStockThreshold": 4,
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
                .andExpect(jsonPath("$.data.skus[0].stockAvailable").value(10))
                .andExpect(jsonPath("$.data.skus[0].lowStockThreshold").value(4));

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
    void adminRejectsMoreThanNineGalleryImages() throws Exception {
        String token = loginAndExtractToken();
        String images = IntStream.rangeClosed(1, 10)
                .mapToObj(index -> "{\"url\":\"https://example.test/gallery-%d.png\"}".formatted(index))
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": 1,
                                  "title": "Too many gallery images",
                                  "mainImage": "https://example.test/main.png",
                                  "images": [%s],
                                  "skus": []
                                }
                                """.formatted(images)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.code()));
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

    @Test
    void deleteSpuRequiresDeleteAuthorityAndMovesOnlySpuToRecycleBin() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);
        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Delete Controller SPU",
                                  "mainImage": "https://example.test/delete-main.jpg",
                                  "detailHtml": "<p>delete</p>",
                                  "sortOrder": 0,
                                  "skus": [{
                                    "skuCode": "DELETE-CTRL-SKU",
                                    "priceCent": 1990,
                                    "stockAvailable": 3,
                                    "status": "ENABLED",
                                    "sortOrder": 0
                                  }]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();
        String updateOnlyToken = limitedAdminToken(List.of("product:spu:update"));

        mockMvc.perform(delete("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + updateOnlyToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(delete("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
        assertThat(jdbcClient.sql("select count(*) from product_spu where id = :spuId and deleted_at is not null")
                .param("spuId", spuId)
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select count(*) from product_sku where spu_id = :spuId and deleted_at is null and status = 'ENABLED'")
                .param("spuId", spuId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void legacySpuUpdatePreservesMainGalleryAndSkuFileIdsWhenUrlsAreUnchanged() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);
        StoredFile mainFile = insertStorageFile("spu-main-legacy.png");
        StoredFile galleryFile = insertStorageFile("spu-gallery-legacy.png");
        StoredFile skuFile = insertStorageFile("spu-sku-legacy.png");

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Legacy Update SPU",
                                  "subtitle": "Legacy subtitle",
                                  "mainImage": "%s",
                                  "mainImageFileId": %d,
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>legacy</p>",
                                  "sortOrder": 1,
                                  "images": [
                                    {"url": "%s", "fileId": %d}
                                  ],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-LEGACY-UPDATE",
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
                                galleryFile.publicUrl(), galleryFile.id(),
                                skuFile.publicUrl(), skuFile.id()
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Legacy Update SPU",
                                  "subtitle": "Legacy subtitle",
                                  "mainImage": "%s",
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>legacy</p>",
                                  "sortOrder": 1,
                                  "images": ["%s"],
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-SKU-LEGACY-UPDATE",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 5,
                                      "weightGram": 300,
                                      "image": "%s",
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(
                                categoryId,
                                mainFile.publicUrl(),
                                galleryFile.publicUrl(),
                                skuId,
                                skuFile.publicUrl()
                        )))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.images[0].fileId").value(galleryFile.id()))
                .andExpect(jsonPath("$.data.skus[0].imageFileId").value(skuFile.id()));

        assertThat(jdbcClient.sql("""
                        select main_image_file_id
                        from product_spu
                        where id = :spuId
                        """)
                .param("spuId", spuId)
                .query(Long.class)
                .single()).isEqualTo(mainFile.id());
        assertThat(jdbcClient.sql("""
                        select file_id
                        from product_spu_image
                        where spu_id = :spuId
                          and url = :url
                        """)
                .param("spuId", spuId)
                .param("url", galleryFile.publicUrl())
                .query(Long.class)
                .single()).isEqualTo(galleryFile.id());
        assertThat(jdbcClient.sql("""
                        select image_file_id
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .query(Long.class)
                .single()).isEqualTo(skuFile.id());
        assertThat(activeUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isEqualTo(1);
    }

    @Test
    void legacyPutPreservesOmittedV2CollectionsAndExplicitEmptyArraysClearThem() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);
        jdbcClient.sql("""
                        insert into product_guarantee_service
                            (terms_name, content_description, icon, sort_order, visible)
                        values ('Controller Legacy Guarantee', 'legacy guarantee', '', 0, true)
                        """)
                .update();
        long guaranteeServiceId = jdbcClient.sql("""
                        select id from product_guarantee_service
                        where terms_name = 'Controller Legacy Guarantee'
                        """)
                .query(Long.class)
                .single();

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller Legacy V2 SPU",
                                  "mainImage": "https://example.test/controller-legacy-v2-main.jpg",
                                  "specType": "MULTI",
                                  "sortOrder": 0,
                                  "specGroups": [{
                                    "groupKey": "controller-color",
                                    "name": "颜色",
                                    "imageEnabled": true,
                                    "sortOrder": 0,
                                    "values": [
                                      {"valueKey": "controller-red", "valueName": "红色", "sortOrder": 0},
                                      {"valueKey": "controller-blue", "valueName": "蓝色", "sortOrder": 1}
                                    ]
                                  }],
                                  "displayBadgeText": "热卖",
                                  "displayBadgeTone": "RED",
                                  "guaranteeServiceIds": [%d],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-LEGACY-V2-RED",
                                      "priceCent": 1990,
                                      "costPriceCent": 1200,
                                      "stockAvailable": 2,
                                      "volumeCubicMeter": 0.001200,
                                      "status": "ENABLED",
                                      "defaultSelected": false,
                                      "sortOrder": 0,
                                      "specValueKeys": ["controller-red"]
                                    },
                                    {
                                      "skuCode": "CTRL-LEGACY-V2-BLUE",
                                      "priceCent": 2090,
                                      "costPriceCent": 1300,
                                      "stockAvailable": 3,
                                      "volumeCubicMeter": 0.002300,
                                      "status": "ENABLED",
                                      "defaultSelected": true,
                                      "sortOrder": 1,
                                      "specValueKeys": ["controller-blue"]
                                    }
                                  ]
                                }
                                """.formatted(categoryId, guaranteeServiceId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();
        long redSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'CTRL-LEGACY-V2-RED'")
                .query(Long.class)
                .single();
        long blueSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'CTRL-LEGACY-V2-BLUE'")
                .query(Long.class)
                .single();

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller Legacy V2 SPU Updated",
                                  "mainImage": "https://example.test/controller-legacy-v2-main.jpg",
                                  "sortOrder": 0,
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-LEGACY-V2-RED",
                                      "specJson": "{\\"颜色\\":\\"红色\\"}",
                                      "specText": "红色",
                                      "priceCent": 1990,
                                      "stockAvailable": 2,
                                      "status": "ENABLED",
                                      "sortOrder": 0
                                    },
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-LEGACY-V2-BLUE",
                                      "specJson": "{\\"颜色\\":\\"蓝色\\"}",
                                      "specText": "蓝色",
                                      "priceCent": 2090,
                                      "stockAvailable": 3,
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(categoryId, redSkuId, blueSkuId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Controller Legacy V2 SPU Updated"))
                .andExpect(jsonPath("$.data.specGroups.length()").value(1))
                .andExpect(jsonPath("$.data.specGroups[0].values.length()").value(2))
                .andExpect(jsonPath("$.data.displayBadgeText").value("热卖"))
                .andExpect(jsonPath("$.data.displayBadgeTone").value("RED"))
                .andExpect(jsonPath("$.data.guaranteeServiceIds[0]").value(guaranteeServiceId))
                .andExpect(jsonPath("$.data.skus[0].specValueKeys[0]").value("controller-red"))
                .andExpect(jsonPath("$.data.skus[0].costPriceCent").value(1200))
                .andExpect(jsonPath("$.data.skus[0].volumeCubicMeter").value(0.001200))
                .andExpect(jsonPath("$.data.skus[0].defaultSelected").value(false))
                .andExpect(jsonPath("$.data.skus[0].combinationKey").value("controller-red"))
                .andExpect(jsonPath("$.data.skus[1].specValueKeys[0]").value("controller-blue"))
                .andExpect(jsonPath("$.data.skus[1].costPriceCent").value(1300))
                .andExpect(jsonPath("$.data.skus[1].volumeCubicMeter").value(0.002300))
                .andExpect(jsonPath("$.data.skus[1].defaultSelected").value(true))
                .andExpect(jsonPath("$.data.skus[1].combinationKey").value("controller-blue"));

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Controller Legacy V2 SPU Updated",
                                  "mainImage": "https://example.test/controller-legacy-v2-main.jpg",
                                  "specType": "MULTI",
                                  "sortOrder": 0,
                                  "specGroups": [],
                                  "displayBadgeText": "",
                                  "displayBadgeTone": "NEUTRAL",
                                  "guaranteeServiceIds": [],
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-LEGACY-V2-RED",
                                      "specJson": "{\\"颜色\\":\\"红色\\"}",
                                      "specText": "红色",
                                      "priceCent": 1990,
                                      "stockAvailable": 2,
                                      "status": "ENABLED",
                                      "sortOrder": 0,
                                      "specValueKeys": []
                                    },
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-LEGACY-V2-BLUE",
                                      "specJson": "{\\"颜色\\":\\"蓝色\\"}",
                                      "specText": "蓝色",
                                      "priceCent": 2090,
                                      "stockAvailable": 3,
                                      "status": "ENABLED",
                                      "sortOrder": 1,
                                      "specValueKeys": []
                                    }
                                  ]
                                }
                                """.formatted(categoryId, redSkuId, blueSkuId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.specGroups.length()").value(0))
                .andExpect(jsonPath("$.data.displayBadgeText").value(""))
                .andExpect(jsonPath("$.data.displayBadgeTone").value("NEUTRAL"))
                .andExpect(jsonPath("$.data.guaranteeServiceIds.length()").value(0))
                .andExpect(jsonPath("$.data.skus[0].specValueKeys.length()").value(0))
                .andExpect(jsonPath("$.data.skus[1].specValueKeys.length()").value(0));
    }

    @Test
    void explicitNullSpuUpdateClearsMainGalleryAndSkuFileIdsWhenUrlsAreUnchanged() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);
        StoredFile mainFile = insertStorageFile("spu-main-clear.png");
        StoredFile galleryFile = insertStorageFile("spu-gallery-clear.png");
        StoredFile skuFile = insertStorageFile("spu-sku-clear.png");

        String createResponse = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Clear Update SPU",
                                  "subtitle": "Clear subtitle",
                                  "mainImage": "%s",
                                  "mainImageFileId": %d,
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>clear</p>",
                                  "sortOrder": 1,
                                  "images": [
                                    {"url": "%s", "fileId": %d}
                                  ],
                                  "skus": [
                                    {
                                      "skuCode": "CTRL-SKU-CLEAR-UPDATE",
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
                                galleryFile.publicUrl(), galleryFile.id(),
                                skuFile.publicUrl(), skuFile.id()
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(createResponse).path("data").asLong();

        String detailResponse = mockMvc.perform(get("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long skuId = objectMapper.readTree(detailResponse).path("data").path("skus").get(0).path("id").asLong();

        mockMvc.perform(put("/admin/product/spus/" + spuId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Clear Update SPU",
                                  "subtitle": "Clear subtitle",
                                  "mainImage": "%s",
                                  "mainImageFileId": null,
                                  "sellingPoints": "A,B",
                                  "detailHtml": "<p>clear</p>",
                                  "sortOrder": 1,
                                  "images": [
                                    {"url": "%s", "fileId": null}
                                  ],
                                  "skus": [
                                    {
                                      "id": %d,
                                      "skuCode": "CTRL-SKU-CLEAR-UPDATE",
                                      "specJson": "{\\"口味\\":\\"牛油\\"}",
                                      "specText": "牛油",
                                      "priceCent": 3990,
                                      "originalPriceCent": 4990,
                                      "stockAvailable": 5,
                                      "weightGram": 300,
                                      "image": "%s",
                                      "imageFileId": null,
                                      "status": "ENABLED",
                                      "sortOrder": 1
                                    }
                                  ]
                                }
                                """.formatted(
                                categoryId,
                                mainFile.publicUrl(),
                                galleryFile.publicUrl(),
                                skuId,
                                skuFile.publicUrl()
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(jdbcClient.sql("""
                        select main_image_file_id
                        from product_spu
                        where id = :spuId
                        """)
                .param("spuId", spuId)
                .query((rs, rowNum) -> rs.getObject("main_image_file_id", Long.class))
                .optional()
                .orElse(null)).isNull();
        assertThat(jdbcClient.sql("""
                        select file_id
                        from product_spu_image
                        where spu_id = :spuId
                          and url = :url
                        """)
                .param("spuId", spuId)
                .param("url", galleryFile.publicUrl())
                .query((rs, rowNum) -> rs.getObject("file_id", Long.class))
                .optional()
                .orElse(null)).isNull();
        assertThat(jdbcClient.sql("""
                        select image_file_id
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", skuId)
                .query((rs, rowNum) -> rs.getObject("image_file_id", Long.class))
                .optional()
                .orElse(null)).isNull();
        assertThat(activeUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isZero();
        assertThat(removedUsageCount(mainFile.id(), "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isZero();
        assertThat(removedUsageCount(galleryFile.id(), "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId)).isEqualTo(1);
        assertThat(activeUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isZero();
        assertThat(removedUsageCount(skuFile.id(), "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId)).isEqualTo(1);
    }

    @Test
    void createDefaultsOptionalProductSortAndVirtualSalesToZero() throws Exception {
        String token = loginAndExtractToken();
        long categoryId = createCategory(token);

        String response = mockMvc.perform(post("/admin/product/spus")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "categoryId": %d,
                                  "title": "Optional defaults SPU",
                                  "mainImage": "https://example.test/optional-defaults.jpg",
                                  "specType": "SINGLE",
                                  "skus": [
                                    {
                                      "skuCode": "OPTIONAL-DEFAULTS-SKU",
                                      "specJson": "{}",
                                      "specText": "默认",
                                      "priceCent": 1990,
                                      "originalPriceCent": 0,
                                      "stockAvailable": 0,
                                      "status": "ENABLED",
                                      "sortOrder": 0,
                                      "defaultSelected": true
                                    }
                                  ]
                                }
                                """.formatted(categoryId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long spuId = objectMapper.readTree(response).path("data").asLong();

        assertThat(jdbcClient.sql("select sort_order from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbcClient.sql("select virtual_sales from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single()).isZero();
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

    private String limitedAdminToken(List<String> permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, permissions);
    }

    private long insertSalesOrder(String orderStatus, boolean paid) {
        String orderNo = "SALE" + System.nanoTime();
        String idempotencyKey = "sale-" + System.nanoTime();
        if (paid) {
            jdbcClient.sql("""
                            insert into shop_order
                                (order_no, user_id, status, source, idempotency_key, paid_at)
                            values
                                (:orderNo, 1, :status, 'DIRECT', :idempotencyKey, current_timestamp)
                            """)
                    .param("orderNo", orderNo)
                    .param("status", orderStatus)
                    .param("idempotencyKey", idempotencyKey)
                    .update();
        } else {
            jdbcClient.sql("""
                            insert into shop_order
                                (order_no, user_id, status, source, idempotency_key)
                            values
                                (:orderNo, 1, :status, 'DIRECT', :idempotencyKey)
                            """)
                    .param("orderNo", orderNo)
                    .param("status", orderStatus)
                    .param("idempotencyKey", idempotencyKey)
                    .update();
        }
        return jdbcClient.sql("select id from shop_order where order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    private void insertSalesOrderItem(long orderId, long spuId, long skuId, int quantity) {
        jdbcClient.sql("""
                        insert into order_item
                            (order_id, sku_id, spu_id, product_title, sku_code, quantity)
                        values
                            (:orderId, :skuId, :spuId, 'Sales Product', 'SALES-SKU', :quantity)
                        """)
                .param("orderId", orderId)
                .param("skuId", skuId)
                .param("spuId", spuId)
                .param("quantity", quantity)
                .update();
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
        String objectKey = "public/test/spu/" + System.nanoTime() + "-" + originalFilename;
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
                .param("sha256", sha256(objectKey))
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

    private record StoredFile(Long id, String publicUrl) {
    }
}
