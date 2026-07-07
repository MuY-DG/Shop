package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AdminProductServiceTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createSpuPersistsImagesSkusAndInitialStockLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Service Category", "", 1, "ENABLED"));

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Service SPU",
                "Service subtitle",
                "https://example.test/main.jpg",
                "A,B",
                "<p>detail</p>",
                1,
                List.of("https://example.test/gallery.jpg"),
                List.of(new AdminSkuUpsertRequest(
                        null,
                        "SERVICE-SKU-1",
                        "{\"口味\":\"牛油\"}",
                        "牛油",
                        3990L,
                        4990L,
                        8,
                        300,
                        "https://example.test/sku.jpg",
                        "ENABLED",
                        1
                ))
        ));

        Integer imageCount = jdbcClient.sql("select count(*) from product_spu_image where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        Integer skuCount = jdbcClient.sql("select count(*) from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        Integer stockLogCount = jdbcClient.sql("""
                        select count(*)
                        from stock_log l
                        join product_sku s on s.id = l.sku_id
                        where s.spu_id = :spuId and l.change_type = 'INITIAL'
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();

        assertThat(imageCount).isEqualTo(1);
        assertThat(skuCount).isEqualTo(1);
        assertThat(stockLogCount).isEqualTo(1);
    }

    @Test
    void publishRequiresEnabledCategoryAndEnabledSku() {
        Long disabledCategoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Disabled Category", "", 1, "DISABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                disabledCategoryId,
                "Unpublishable SPU",
                "",
                "https://example.test/main.jpg",
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "UNPUBLISHABLE-SKU", "{}", "默认", 1990L, 0L, 1, 100, "", "ENABLED", 1))
        ));

        assertThatThrownBy(() -> adminProductService.publishSpu(spuId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    }

    @Test
    void adjustSkuStockWritesAdjustmentLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Stock Category", "", 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Stock SPU",
                "",
                "https://example.test/main.jpg",
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "STOCK-SKU", "{}", "默认", 1990L, 0L, 5, 100, "", "ENABLED", 1))
        ));
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();

        adminProductService.adjustSkuStock(skuId, new AdminStockAdjustmentRequest(7, "追加库存"), 1L);

        Integer stock = jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();
        Integer adjustmentLogs = jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId and change_type = 'ADJUST'")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();

        assertThat(stock).isEqualTo(12);
        assertThat(adjustmentLogs).isEqualTo(1);
    }
}
