package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void updateWithoutSkuStockPermissionIgnoresStockButProtectsThreshold() {
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

        adminProductService.updateSpu(
                spuId,
                productRequest(categoryId, skuId, "UPDATE-STOCK-PERMISSION-SKU", "Ignored stock change", 7),
                42L,
                false
        );

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
                .single()).isEqualTo("Ignored stock change");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleMetadataFormPreservesLatestStockWithOrWithoutStockPermission(boolean stockWriteAllowed) {
        Long categoryId = createCategory("Stale Stock Category " + stockWriteAllowed);
        String skuCode = "STALE-STOCK-SKU-" + stockWriteAllowed;
        Long spuId = adminProductService.createSpu(productRequest(categoryId, null, skuCode, "Before", 5));
        Long skuId = skuId(spuId);
        AdminSpuUpsertRequest staleForm = productRequest(categoryId, skuId, skuCode, "After", 5);

        adminProductService.adjustSkuStock(skuId, new AdminStockAdjustmentRequest(-1, "出库调整"), 41L);
        adminProductService.updateSpu(spuId, staleForm, 42L, stockWriteAllowed);

        assertThat(stock(skuId)).isEqualTo(4);
        assertThat(jdbcClient.sql("select title from product_spu where id = :spuId")
                .param("spuId", spuId).query(String.class).single()).isEqualTo("After");
        assertThat(jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId")
                .param("skuId", skuId).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select quantity_before, quantity_delta, quantity_after, operator_id
                        from stock_log where sku_id = :skuId and change_type = 'ADJUST'
                        """).param("skuId", skuId).query().singleRow())
                .containsEntry("QUANTITY_BEFORE", 5)
                .containsEntry("QUANTITY_DELTA", -1)
                .containsEntry("QUANTITY_AFTER", 4)
                .containsEntry("OPERATOR_ID", 41L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void omittedStockPreservesExistingSkuMatchedByCode(boolean stockWriteAllowed) {
        Long categoryId = createCategory("Omitted Stock Category " + stockWriteAllowed);
        String skuCode = "OMITTED-STOCK-SKU-" + stockWriteAllowed;
        Long spuId = adminProductService.createSpu(productRequest(categoryId, null, skuCode, "Before", 5));
        Long skuId = skuId(spuId);
        AdminSpuUpsertRequest request = productRequest(categoryId, null, " " + skuCode + " ", "After", null);

        adminProductService.updateSpu(spuId, request, 42L, stockWriteAllowed);

        assertThat(stock(skuId)).isEqualTo(5);
        assertThat(jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId")
                .param("skuId", skuId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void addingNewSkuWithInitialStockRequiresPermissionAndWritesInitialLogWhenAllowed() {
        Long categoryId = createCategory("New SKU Stock Permission Category");
        Long spuId = adminProductService.createSpu(productRequest(
                categoryId, null, "NEW-SKU-STOCK-ORIGINAL", "Before", 5));
        AdminSpuUpsertRequest request = productRequest(
                categoryId, skuId(spuId), "NEW-SKU-STOCK-ORIGINAL", "After", null);
        request.setSkus(List.of(request.skus().getFirst(), new AdminSkuUpsertRequest(
                null, "NEW-SKU-STOCK-ADDED", "{\"规格\":\"新增\"}", "新增", 1990L, 0L, 3,
                100, "", null, "ENABLED", 1)));

        assertThatThrownBy(() -> adminProductService.updateSpu(spuId, request, 42L, false))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(jdbcClient.sql("select count(*) from product_sku where sku_code = 'NEW-SKU-STOCK-ADDED'")
                .query(Integer.class).single()).isZero();

        adminProductService.updateSpu(spuId, request, 42L, true);

        assertThat(jdbcClient.sql("""
                        select l.quantity_before, l.quantity_delta, l.quantity_after, l.operator_id
                        from stock_log l join product_sku s on s.id = l.sku_id
                        where s.sku_code = 'NEW-SKU-STOCK-ADDED' and l.change_type = 'INITIAL'
                        """).query().singleRow())
                .containsEntry("QUANTITY_BEFORE", 0)
                .containsEntry("QUANTITY_DELTA", 3)
                .containsEntry("QUANTITY_AFTER", 3)
                .containsEntry("OPERATOR_ID", 42L);
    }

    @Test
    void createWithoutStockPermissionMayUseDefaultZeroInitialStock() {
        Long categoryId = createCategory("Zero Initial Stock Category");
        Long spuId = adminProductService.createSpu(productRequest(
                categoryId, null, "ZERO-INITIAL-STOCK", "Zero initial stock", null), false);

        assertThat(stock(skuId(spuId))).isZero();
    }

    private Long skuId(Long spuId) {
        return jdbcClient.sql("select id from product_sku where spu_id = :spuId and deleted_at is null")
                .param("spuId", spuId).query(Long.class).single();
    }

    private int stock(Long skuId) {
        return jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", skuId).query(Integer.class).single();
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
            Integer stock
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
