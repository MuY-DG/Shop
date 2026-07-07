package org.muybaby.shopserver.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
    private ObjectMapper objectMapper;

    @Test
    void publicAppApisReturnOnlyPublishedProductsWithoutToken() throws Exception {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "App Category", "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "App Published SPU",
                "App subtitle",
                "https://example.test/main.jpg",
                "A,B",
                "<p>detail</p>",
                1,
                List.of("https://example.test/gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, "APP-SKU-1", "{\"口味\":\"牛油\"}", "牛油", 3990L, 4990L, 9, 300, "https://example.test/sku.jpg", "ENABLED", 1))
        ));
        adminProductService.publishSpu(spuId);

        mockMvc.perform(get("/app/product/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));

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
                .andExpect(jsonPath("$.data.skus[0].skuCode").value("APP-SKU-1"));

        adminProductService.unpublishSpu(spuId);

        mockMvc.perform(get("/app/product/spus/" + spuId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    @Test
    void productListKeepsPublishedSpuWhenAllSkusAreDisabledAndFiltersDisabledSkuData() throws Exception {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Edge Category", "", 2, "ENABLED"));
        Long hiddenSkuSpuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Disabled SKU SPU",
                "Edge subtitle",
                "https://example.test/disabled-main.jpg",
                " Fresh , , Spicy  ",
                "<p>edge detail</p>",
                1,
                List.of("https://example.test/disabled-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, "EDGE-SKU-1", "{\"规格\":\"大份\"}", "大份", 5990L, 6990L, 4, 400, "https://example.test/edge-sku.jpg", "ENABLED", 1))
        ));
        adminProductService.publishSpu(hiddenSkuSpuId);
        adminProductService.updateSpu(hiddenSkuSpuId, new AdminSpuUpsertRequest(
                categoryId,
                "Disabled SKU SPU",
                "Edge subtitle",
                "https://example.test/disabled-main.jpg",
                " Fresh , , Spicy  ",
                "<p>edge detail</p>",
                1,
                List.of("https://example.test/disabled-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, "EDGE-SKU-1", "{\"规格\":\"大份\"}", "大份", 5990L, 6990L, 4, 400, "https://example.test/edge-sku.jpg", "DISABLED", 1))
        ));

        Long mixedSkuSpuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Mixed SKU SPU",
                "Mixed subtitle",
                "https://example.test/mixed-main.jpg",
                " Crisp , , Hot ",
                "<p>mixed detail</p>",
                2,
                List.of("https://example.test/mixed-gallery.jpg"),
                List.of(
                        new AdminSkuUpsertRequest(null, "MIX-SKU-1", "{\"规格\":\"中份\"}", "中份", 3990L, 4990L, 5, 350, "https://example.test/mix-1.jpg", "ENABLED", 1),
                        new AdminSkuUpsertRequest(null, "MIX-SKU-2", "{\"规格\":\"大份\"}", "大份", 8990L, 9990L, 8, 500, "https://example.test/mix-2.jpg", "DISABLED", 2)
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
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Disabled After Publish", "", 3, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Hidden By Disabled Category",
                "Category disabled subtitle",
                "https://example.test/hidden-main.jpg",
                "A",
                "<p>hidden detail</p>",
                1,
                List.of("https://example.test/hidden-gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(null, "HIDDEN-CAT-SKU", "{}", "默认", 2990L, 3990L, 3, 200, "", "ENABLED", 1))
        ));
        adminProductService.publishSpu(spuId);
        adminProductService.updateCategory(categoryId, new AdminCategoryRequest(0L, "Disabled After Publish", "", 3, "DISABLED"));

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
}
