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
}
