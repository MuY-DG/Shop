package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
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
class AdminProductStockPermissionTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createWithNonZeroStockRequiresSkuStockPermission() {
        Long categoryId = createCategory("Create Stock Permission Category");

        assertThatThrownBy(() -> adminProductService.createSpu(
                productRequest(categoryId, null, "CREATE-STOCK-PERMISSION-SKU", "Denied create", 3),
                false
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);

        assertThat(jdbcClient.sql("select count(*) from product_spu where title = 'Denied create'")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void updateWithoutSkuStockPermissionMayKeepButCannotChangeExistingStock() {
        Long categoryId = createCategory("Update Stock Permission Category");
        Long spuId = adminProductService.createSpu(
                productRequest(categoryId, null, "UPDATE-STOCK-PERMISSION-SKU", "Original title", 5)
        );
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId and deleted_at is null")
                .param("spuId", spuId)
                .query(Long.class)
                .single();

        adminProductService.updateSpu(
                spuId,
                productRequest(categoryId, skuId, "UPDATE-STOCK-PERMISSION-SKU", "Metadata only", 5),
                42L,
                false
        );

        assertThat(jdbcClient.sql("select title from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single()).isEqualTo("Metadata only");

        assertThatThrownBy(() -> adminProductService.updateSpu(
                spuId,
                productRequest(categoryId, skuId, "UPDATE-STOCK-PERMISSION-SKU", "Forbidden stock change", 7),
                42L,
                false
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);

        AdminSpuUpsertRequest thresholdChange = productRequest(
                categoryId, skuId, "UPDATE-STOCK-PERMISSION-SKU", "Forbidden threshold change", 5);
        thresholdChange.skus().getFirst().setLowStockThreshold(2);
        assertThatThrownBy(() -> adminProductService.updateSpu(
                spuId,
                thresholdChange,
                42L,
                false
        ))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PERMISSION_DENIED);

        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single()).isEqualTo(5);
        assertThat(jdbcClient.sql("select low_stock_threshold from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single()).isEqualTo(10);
        assertThat(jdbcClient.sql("select title from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single()).isEqualTo("Metadata only");
    }

    private Long createCategory(String name) {
        return adminProductService.createCategory(new AdminCategoryRequest(
                0L, name, "", null, 0, "ENABLED"
        ));
    }

    private AdminSpuUpsertRequest productRequest(
            Long categoryId,
            Long skuId,
            String skuCode,
            String title,
            int stock
    ) {
        return new AdminSpuUpsertRequest(
                categoryId,
                title,
                "",
                "https://example.test/stock-permission-main.jpg",
                null,
                "",
                "<p>detail</p>",
                0,
                List.of(),
                List.of(new AdminSkuUpsertRequest(
                        skuId,
                        skuCode,
                        "{}",
                        "默认",
                        1990L,
                        0L,
                        stock,
                        100,
                        "",
                        null,
                        "ENABLED",
                        0
                ))
        );
    }
}
