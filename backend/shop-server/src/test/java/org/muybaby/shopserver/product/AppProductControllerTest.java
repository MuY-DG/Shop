package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void cleanStorageTables() {
        jdbcClient.sql("delete from storage_file_usage").update();
        jdbcClient.sql("delete from storage_file").update();
    }

    @Test
    void publicAppApisReturnOnlyPublishedProductsWithoutTokenAndExposeFileIds() throws Exception {
        StoredFile categoryIcon = insertStorageFile("app-category-icon.png");
        StoredFile mainFile = insertStorageFile("app-main.png");
        StoredFile galleryFile = insertStorageFile("app-gallery.png");
        StoredFile skuFile = insertStorageFile("app-sku.png");
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "App Category", categoryIcon.publicUrl(), categoryIcon.id(), 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "App Published SPU",
                "App subtitle",
                mainFile.publicUrl(),
                mainFile.id(),
                "A,B",
                "<p>detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest(galleryFile.publicUrl(), galleryFile.id())),
                List.of(new AdminSkuUpsertRequest(null, "APP-SKU-1", "{\"口味\":\"牛油\"}", "牛油", 3990L, 4990L, 9, 300, skuFile.publicUrl(), skuFile.id(), "ENABLED", 1))
        ));
        adminProductService.publishSpu(spuId);

        String categoryResponse = mockMvc.perform(get("/app/product/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(findCategoryNode(categoryResponse, "App Category").path("iconFileId").asLong()).isEqualTo(categoryIcon.id());

        mockMvc.perform(get("/app/product/spus")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "Published"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.records[0].title").value("App Published SPU"));

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(spuId))
                .andExpect(jsonPath("$.data.mainImageFileId").value(mainFile.id()))
                .andExpect(jsonPath("$.data.images[0].fileId").value(galleryFile.id()))
                .andExpect(jsonPath("$.data.skus[0].skuCode").value("APP-SKU-1"))
                .andExpect(jsonPath("$.data.skus[0].imageFileId").value(skuFile.id()));

        adminProductService.unpublishSpu(spuId);

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    @Test
    void productListKeepsPublishedSpuWhenAllSkusAreDisabledAndFiltersDisabledSkuData() throws Exception {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Edge Category", "", null, 2, "ENABLED"));
        Long hiddenSkuSpuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Disabled SKU SPU",
                "Edge subtitle",
                "https://example.test/disabled-main.jpg",
                null,
                " Fresh , , Spicy  ",
                "<p>edge detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest("https://example.test/disabled-gallery.jpg", null)),
                List.of(new AdminSkuUpsertRequest(null, "EDGE-SKU-1", "{\"规格\":\"大份\"}", "大份", 5990L, 6990L, 4, 400, "https://example.test/edge-sku.jpg", null, "ENABLED", 1))
        ));
        adminProductService.publishSpu(hiddenSkuSpuId);
        adminProductService.updateSpu(hiddenSkuSpuId, new AdminSpuUpsertRequest(
                categoryId,
                "Disabled SKU SPU",
                "Edge subtitle",
                "https://example.test/disabled-main.jpg",
                null,
                " Fresh , , Spicy  ",
                "<p>edge detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest("https://example.test/disabled-gallery.jpg", null)),
                List.of(new AdminSkuUpsertRequest(null, "EDGE-SKU-1", "{\"规格\":\"大份\"}", "大份", 5990L, 6990L, 4, 400, "https://example.test/edge-sku.jpg", null, "DISABLED", 1))
        ));

        Long mixedSkuSpuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Mixed SKU SPU",
                "Mixed subtitle",
                "https://example.test/mixed-main.jpg",
                null,
                " Crisp , , Hot ",
                "<p>mixed detail</p>",
                2,
                List.of(new AdminProductImageUpsertRequest("https://example.test/mixed-gallery.jpg", null)),
                List.of(
                        new AdminSkuUpsertRequest(null, "MIX-SKU-1", "{\"规格\":\"中份\"}", "中份", 3990L, 4990L, 5, 350, "https://example.test/mix-1.jpg", null, "ENABLED", 1),
                        new AdminSkuUpsertRequest(null, "MIX-SKU-2", "{\"规格\":\"大份\"}", "大份", 8990L, 9990L, 8, 500, "https://example.test/mix-2.jpg", null, "DISABLED", 2)
                )
        ));
        adminProductService.publishSpu(mixedSkuSpuId);

        mockMvc.perform(get("/app/product/spus")
                        .param("current", "1")
                        .param("size", "10")
                        .param("categoryId", categoryId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].title").value("Disabled SKU SPU"))
                .andExpect(jsonPath("$.data.records[0].minPriceCent").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].maxPriceCent").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].totalStock").value(0))
                .andExpect(jsonPath("$.data.records[0].sellingPoints[0]").value("Fresh"))
                .andExpect(jsonPath("$.data.records[0].sellingPoints[1]").value("Spicy"))
                .andExpect(jsonPath("$.data.records[1].title").value("Mixed SKU SPU"))
                .andExpect(jsonPath("$.data.records[1].minPriceCent").value(3990))
                .andExpect(jsonPath("$.data.records[1].maxPriceCent").value(3990))
                .andExpect(jsonPath("$.data.records[1].totalStock").value(5));

        mockMvc.perform(get("/app/product/spus/" + mixedSkuSpuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellingPoints[0]").value("Crisp"))
                .andExpect(jsonPath("$.data.sellingPoints[1]").value("Hot"))
                .andExpect(jsonPath("$.data.skus.length()").value(1))
                .andExpect(jsonPath("$.data.skus[0].skuCode").value("MIX-SKU-1"));
    }

    @Test
    void publicAppApisHidePublishedProductsWhenCategoryIsDisabled() throws Exception {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Disabled After Publish", "", null, 3, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Hidden By Disabled Category",
                "Category disabled subtitle",
                "https://example.test/hidden-main.jpg",
                null,
                "A",
                "<p>hidden detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest("https://example.test/hidden-gallery.jpg", null)),
                List.of(new AdminSkuUpsertRequest(null, "HIDDEN-CAT-SKU", "{}", "默认", 2990L, 3990L, 3, 200, "", null, "ENABLED", 1))
        ));
        adminProductService.publishSpu(spuId);
        adminProductService.updateCategory(categoryId, new AdminCategoryRequest(0L, "Disabled After Publish", "", null, 3, "DISABLED"));

        mockMvc.perform(get("/app/product/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", not(org.hamcrest.Matchers.hasItem(categoryId.intValue()))));

        mockMvc.perform(get("/app/product/spus")
                        .param("current", "1")
                        .param("size", "10")
                        .param("categoryId", categoryId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    private StoredFile insertStorageFile(String originalFilename) {
        String objectKey = "public/test/app-product/" + System.nanoTime() + "-" + originalFilename;
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

    private com.fasterxml.jackson.databind.JsonNode findCategoryNode(String response, String name) throws Exception {
        for (com.fasterxml.jackson.databind.JsonNode node : new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data")) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("Category not found in response: " + name);
    }

    private record StoredFile(Long id, String publicUrl) {
    }
}
