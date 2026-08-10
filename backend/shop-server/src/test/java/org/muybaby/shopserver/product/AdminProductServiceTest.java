package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.product.dto.AdminCategoryRequest;
import org.muybaby.shopserver.product.dto.AdminProductImageUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSkuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecGroupUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminSpuSpecValueUpsertRequest;
import org.muybaby.shopserver.product.dto.AdminStockAdjustmentRequest;
import org.muybaby.shopserver.product.dto.AdminWholesaleTierUpsertRequest;
import org.muybaby.shopserver.product.service.AdminProductService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.muybaby.shopserver.support.ProductComplianceTestSupport.markNonFood;

@SpringBootTest
@ActiveProfiles("test")
class AdminProductServiceTest {

    @Autowired
    private AdminProductService adminProductService;

    @Autowired
    private ProductReadMapper productReadMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createSpuPersistsValidatedWholesaleTiers() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Wholesale Category", "", null, 1, "ENABLED"));
        AdminSkuUpsertRequest sku = new AdminSkuUpsertRequest(
                null, "WHOLESALE-SKU", "{}", "默认", 1_000L, 1_200L,
                100, 100, "", null, "ENABLED", 0
        );
        sku.setWholesaleTiers(List.of(
                new AdminWholesaleTierUpsertRequest(10, 880L),
                new AdminWholesaleTierUpsertRequest(50, 760L)
        ));

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId, "Wholesale SPU", "", "https://example.test/wholesale.jpg", null,
                "批量更优惠", "", 0, List.of(), List.of(sku)
        ));

        assertThat(productReadMapper.adminSpuDetail(spuId).skus().getFirst().wholesaleTiers())
                .extracting("minQuantity", "unitPriceCent")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10, 880L),
                        org.assertj.core.groups.Tuple.tuple(50, 760L)
                );

        AdminSkuUpsertRequest invalidSku = new AdminSkuUpsertRequest(
                null, "WHOLESALE-INVALID-SKU", "{}", "默认", 1_000L, 1_200L,
                100, 100, "", null, "ENABLED", 0
        );
        invalidSku.setWholesaleTiers(List.of(new AdminWholesaleTierUpsertRequest(10, 1_000L)));
        assertThatThrownBy(() -> adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId, "Invalid Wholesale SPU", "", "https://example.test/invalid-wholesale.jpg", null,
                "", "", 0, List.of(), List.of(invalidSku)
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void createSpuPersistsImagesSkusAndInitialStockLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Service Category", "", null, 1, "ENABLED"));

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Service SPU",
                "Service subtitle",
                "https://example.test/main.jpg",
                null,
                "A,B",
                "<p>detail</p>",
                1,
                List.of(new AdminProductImageUpsertRequest("https://example.test/gallery.jpg", null)),
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
                        null,
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
        Long disabledCategoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Disabled Category", "", null, 1, "DISABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                disabledCategoryId,
                "Unpublishable SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "UNPUBLISHABLE-SKU", "{}", "默认", 1990L, 0L, 1, 100, "", null, "ENABLED", 1))
        ));

        assertThatThrownBy(() -> adminProductService.publishSpu(spuId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);
    }

    @Test
    void updateOnSaleSpuRejectsRemovingEveryEnabledSku() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "On Sale Update Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "On Sale Update SPU",
                "",
                "https://example.test/on-sale-main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(
                        null, "ON-SALE-UPDATE-SKU", "{}", "默认", 1990L, 0L,
                        1, 100, "", null, "ENABLED", 1
                ))
        ));
        markNonFood(jdbcClient, spuId);
        adminProductService.publishSpu(spuId);

        assertThatThrownBy(() -> adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "On Sale Update SPU",
                "",
                "https://example.test/on-sale-main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of()
        )))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PRODUCT_UNAVAILABLE);

        assertThat(jdbcClient.sql("select status from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single()).isEqualTo("ON_SALE");
        assertThat(jdbcClient.sql("select count(*) from product_sku where spu_id = :spuId and deleted_at is null")
                .param("spuId", spuId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void adjustSkuStockWritesAdjustmentLog() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Stock Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Stock SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "STOCK-SKU", "{}", "默认", 1990L, 0L, 5, 100, "", null, "ENABLED", 1))
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

    @Test
    void updateSpuWritesAdjustmentLogWhenExistingSkuStockChanges() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Update Stock Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Update Stock SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "UPDATE-STOCK-SKU", "{}", "默认", 1990L, 0L, 5, 100, "", null, "ENABLED", 1))
        ));
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "Update Stock SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(skuId, "UPDATE-STOCK-SKU", "{}", "默认", 1990L, 0L, 11, 100, "", null, "ENABLED", 1))
        ), 7L);

        Map<String, Object> adjustmentLog = jdbcClient.sql("""
                        select change_type, quantity_before, quantity_delta, quantity_after, operator_type, operator_id
                        from stock_log
                        where sku_id = :skuId and change_type = 'ADJUST'
                        """)
                .param("skuId", skuId)
                .query()
                .singleRow();

        assertThat(adjustmentLog)
                .containsEntry("CHANGE_TYPE", "ADJUST")
                .containsEntry("QUANTITY_BEFORE", 5)
                .containsEntry("QUANTITY_DELTA", 6)
                .containsEntry("QUANTITY_AFTER", 11)
                .containsEntry("OPERATOR_TYPE", "ADMIN")
                .containsEntry("OPERATOR_ID", 7L);
    }

    @Test
    void updateSpuWritesInitialLogWhenAddingSkuWithStock() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Add SKU Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Add SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "ADD-SKU-ORIGINAL", "{}", "默认", 1990L, 0L, 5, 100, "", null, "ENABLED", 1))
        ));
        Long originalSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'ADD-SKU-ORIGINAL'")
                .query(Long.class)
                .single();

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "Add SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(
                        new AdminSkuUpsertRequest(originalSkuId, "ADD-SKU-ORIGINAL", "{}", "默认", 1990L, 0L, 5, 100, "", null, "ENABLED", 1),
                        new AdminSkuUpsertRequest(null, "ADD-SKU-NEW", "{\"规格\":\"新\"}", "新", 2990L, 0L, 4, 120, "", null, "ENABLED", 2)
                )
        ), 8L);

        Long newSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'ADD-SKU-NEW'")
                .query(Long.class)
                .single();
        Map<String, Object> initialLog = jdbcClient.sql("""
                        select change_type, quantity_before, quantity_delta, quantity_after, operator_type, operator_id
                        from stock_log
                        where sku_id = :skuId and change_type = 'INITIAL'
                        """)
                .param("skuId", newSkuId)
                .query()
                .singleRow();

        assertThat(initialLog)
                .containsEntry("CHANGE_TYPE", "INITIAL")
                .containsEntry("QUANTITY_BEFORE", 0)
                .containsEntry("QUANTITY_DELTA", 4)
                .containsEntry("QUANTITY_AFTER", 4)
                .containsEntry("OPERATOR_TYPE", "ADMIN")
                .containsEntry("OPERATOR_ID", 8L);
    }

    @Test
    void updateSpuUpdatesExistingSkuInPlaceAndSoftDeletesOmittedSku() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Stable SKU Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Stable SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(
                        new AdminSkuUpsertRequest(null, "STABLE-SKU-KEEP", "{\"规格\":\"保留\"}", "保留", 1990L, 0L, 5, 100, "", null, "ENABLED", 1),
                        new AdminSkuUpsertRequest(null, "STABLE-SKU-OMIT", "{\"规格\":\"移除\"}", "移除", 2990L, 0L, 3, 120, "", null, "ENABLED", 2)
                )
        ));
        Long retainedSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'STABLE-SKU-KEEP'")
                .query(Long.class)
                .single();
        Long omittedSkuId = jdbcClient.sql("select id from product_sku where sku_code = 'STABLE-SKU-OMIT'")
                .query(Long.class)
                .single();

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "Stable SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(
                        retainedSkuId,
                        "STABLE-SKU-KEEP",
                        "{\"规格\":\"保留\"}",
                        "保留",
                        2590L,
                        0L,
                        7,
                        100,
                        "",
                        null,
                        "ENABLED",
                        1
                ))
        ), 9L);

        Map<String, Object> retainedSku = jdbcClient.sql("""
                        select id, price_cent, stock_available, deleted_at
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", retainedSkuId)
                .query()
                .singleRow();
        Map<String, Object> omittedSku = jdbcClient.sql("""
                        select id, status, deleted_at
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", omittedSkuId)
                .query()
                .singleRow();

        assertThat(retainedSku)
                .containsEntry("ID", retainedSkuId)
                .containsEntry("PRICE_CENT", 2590L)
                .containsEntry("STOCK_AVAILABLE", 7)
                .containsEntry("DELETED_AT", null);
        assertThat(omittedSku)
                .containsEntry("ID", omittedSkuId)
                .containsEntry("STATUS", "DISABLED");
        assertThat(omittedSku.get("DELETED_AT")).isNotNull();

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "Stable SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(
                        new AdminSkuUpsertRequest(retainedSkuId, "STABLE-SKU-KEEP", "{\"规格\":\"保留\"}", "保留", 2590L, 0L, 7, 100, "", null, "ENABLED", 1),
                        new AdminSkuUpsertRequest(null, "STABLE-SKU-OMIT", "{\"规格\":\"移除\"}", "移除", 2990L, 0L, 3, 120, "", null, "ENABLED", 2)
                )
        ), 9L);

        Map<String, Object> restoredSku = jdbcClient.sql("""
                        select id, status, deleted_at
                        from product_sku
                        where sku_code = 'STABLE-SKU-OMIT'
                        """)
                .query()
                .singleRow();
        assertThat(restoredSku)
                .containsEntry("ID", omittedSkuId)
                .containsEntry("STATUS", "ENABLED")
                .containsEntry("DELETED_AT", null);
    }

    @Test
    void createSpuGeneratesSkuCodeWhenItIsBlank() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Generated SKU Category", "", null, 1, "ENABLED"));

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Generated SKU SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, " ", "{}", "默认", 1990L, 0L, 0, null, "", null, "ENABLED", 1))
        ));

        String skuCode = jdbcClient.sql("select sku_code from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single();

        assertThat(skuCode).startsWith("SKU-").doesNotContain(" ");
    }

    @Test
    void deleteSpuMovesOnlyProductToRecycleBinAndPreservesSkuHistory() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Delete Product Category", "", null, 1, "ENABLED"));
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Delete Product SPU",
                "",
                "https://example.test/main.jpg",
                null,
                "A",
                "<p>detail</p>",
                1,
                List.of(),
                List.of(new AdminSkuUpsertRequest(null, "DELETE-SKU", "{}", "默认", 1990L, 0L, 4, 100, "", null, "ENABLED", 1))
        ));
        Long skuId = jdbcClient.sql("select id from product_sku where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        Integer stockLogCountBefore = jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();

        adminProductService.deleteSpu(spuId);

        Map<String, Object> spu = jdbcClient.sql("select status, deleted_at from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query()
                .singleRow();
        Map<String, Object> sku = jdbcClient.sql("select status, deleted_at from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query()
                .singleRow();
        Integer stockLogCountAfter = jdbcClient.sql("select count(*) from stock_log where sku_id = :skuId")
                .param("skuId", skuId)
                .query(Integer.class)
                .single();

        assertThat(spu).containsEntry("STATUS", "OFF_SALE");
        assertThat(spu.get("DELETED_AT")).isNotNull();
        assertThat(sku).containsEntry("STATUS", "ENABLED");
        assertThat(sku.get("DELETED_AT")).isNull();
        assertThat(stockLogCountAfter).isEqualTo(stockLogCountBefore);
    }

    @Test
    void createMultiSpecSpuPersistsNormalizedTreeAndCompatibilitySnapshots() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(0L, "Structured Spec Category", "", null, 1, "ENABLED"));
        AdminSpuSpecGroupUpsertRequest colorGroup = new AdminSpuSpecGroupUpsertRequest(
                null,
                "color",
                "颜色",
                true,
                0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "red", "红色", "https://example.test/red.jpg", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "blue", "蓝色", "https://example.test/blue.jpg", null, 1)
                )
        );
        AdminSkuUpsertRequest redSku = new AdminSkuUpsertRequest(
                null, "", null, null, 3990L, null, null, null, 2500L,
                new BigDecimal("0.001200"), "", null, "ENABLED", 0, true,
                null, List.of("red"), false
        );
        AdminSkuUpsertRequest blueSku = new AdminSkuUpsertRequest(
                null, null, null, null, 4190L, 4990L, 6, 350, null,
                null, "", null, "ENABLED", 1, false,
                null, List.of("blue"), false
        );

        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId,
                "Structured Spec SPU",
                null,
                "https://example.test/main.jpg",
                null,
                "",
                null,
                "MULTI",
                1L,
                12L,
                null,
                "<p>detail</p>",
                0,
                List.of(),
                List.of(redSku, blueSku),
                List.of(colorGroup),
                "人气爆款",
                "RED",
                List.of(),
                false,
                false,
                true
        ));

        Integer groupCount = jdbcClient.sql("select count(*) from product_spu_spec_group where spu_id = :spuId and deleted_at is null")
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        Integer valueCount = jdbcClient.sql("""
                        select count(*)
                        from product_spu_spec_value v
                        join product_spu_spec_group g on g.id = v.group_id
                        where g.spu_id = :spuId and v.deleted_at is null
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();
        List<Map<String, Object>> skuRows = jdbcClient.sql("""
                        select sku_code, spec_json, spec_text, image, is_default, combination_key,
                               cost_price_cent, weight_gram, volume_cubic_meter
                        from product_sku
                        where spu_id = :spuId and deleted_at is null
                        order by sort_order
                        """)
                .param("spuId", spuId)
                .query()
                .listOfRows();
        Integer mappingCount = jdbcClient.sql("""
                        select count(*)
                        from product_sku_spec_value sv
                        join product_sku s on s.id = sv.sku_id
                        where s.spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single();

        assertThat(groupCount).isEqualTo(1);
        assertThat(valueCount).isEqualTo(2);
        assertThat(mappingCount).isEqualTo(2);
        assertThat(skuRows).hasSize(2);
        assertThat(skuRows.get(0).get("SKU_CODE").toString()).startsWith("SKU-");
        assertThat(skuRows.get(0))
                .containsEntry("SPEC_JSON", "{\"颜色\":\"红色\"}")
                .containsEntry("SPEC_TEXT", "红色")
                .containsEntry("IMAGE", "https://example.test/red.jpg")
                .containsEntry("IS_DEFAULT", true)
                .containsEntry("COMBINATION_KEY", "red")
                .containsEntry("COST_PRICE_CENT", 2500L)
                .containsEntry("WEIGHT_GRAM", null);
        assertThat(skuRows.get(0).get("VOLUME_CUBIC_METER").toString()).isEqualTo("0.001200");
        assertThat(jdbcClient.sql("select display_badge_text from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query(String.class)
                .single()).isEqualTo("人气爆款");

        var detail = productReadMapper.adminSpuDetail(spuId);
        assertThat(detail.specType()).isEqualTo("MULTI");
        assertThat(detail.virtualSales()).isEqualTo(12L);
        assertThat(detail.specGroups()).hasSize(1);
        assertThat(detail.specGroups().get(0).values()).extracting("valueKey").containsExactly("red", "blue");
        assertThat(detail.skus()).extracting("combinationKey").containsExactly("red", "blue");
        assertThat(detail.displayBadgeText()).isEqualTo("人气爆款");
        assertThat(detail.displayBadgeTone()).isEqualTo("RED");
    }

    @Test
    void legacyUpdatePreservesOmittedV2CollectionsAndSkuMappingsWhileExplicitEmptyListsClearThem() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Legacy V2 Preservation Category", "", null, 1, "ENABLED"));
        jdbcClient.sql("""
                        insert into product_guarantee_service
                            (terms_name, content_description, icon, sort_order, visible)
                        values ('Legacy V2 Guarantee', 'legacy guarantee', '', 0, true)
                        """)
                .update();
        Long guaranteeServiceId = jdbcClient.sql("""
                        select id from product_guarantee_service
                        where terms_name = 'Legacy V2 Guarantee'
                        """)
                .query(Long.class)
                .single();
        AdminSpuSpecGroupUpsertRequest colorGroup = new AdminSpuSpecGroupUpsertRequest(
                null,
                "legacy-color",
                "颜色",
                true,
                0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "legacy-red", "红色", "", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "legacy-blue", "蓝色", "", null, 1)
                )
        );
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId, "Legacy V2 Preservation SPU", "", "https://example.test/legacy-v2-main.jpg", null,
                "", null, "MULTI", 1L, 0L, "", "", 0, List.of(),
                List.of(
                        new AdminSkuUpsertRequest(null, "LEGACY-V2-RED", null, null, 1990L, 0L, 2, null,
                                null, null, "", null, "ENABLED", 0, true, null, List.of("legacy-red"), false),
                        new AdminSkuUpsertRequest(null, "LEGACY-V2-BLUE", null, null, 2090L, 0L, 3, null,
                                null, null, "", null, "ENABLED", 1, false, null, List.of("legacy-blue"), false)
                ),
                List.of(colorGroup), "热卖", "ORANGE", List.of(guaranteeServiceId),
                false, false, true
        ));
        var created = productReadMapper.adminSpuDetail(spuId);

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId,
                "Legacy V2 Preservation SPU Updated",
                "",
                "https://example.test/legacy-v2-main.jpg",
                null,
                "",
                "",
                0,
                List.of(),
                created.skus().stream()
                        .map(sku -> new AdminSkuUpsertRequest(
                                sku.id(), sku.skuCode(), sku.specJson(), sku.specText(), sku.priceCent(),
                                sku.originalPriceCent(), sku.stockAvailable(), sku.weightGram(), sku.image(),
                                sku.imageFileId(), sku.status(), sku.sortOrder()
                        ))
                        .toList()
        ));

        var preserved = productReadMapper.adminSpuDetail(spuId);
        assertThat(preserved.title()).isEqualTo("Legacy V2 Preservation SPU Updated");
        assertThat(preserved.specGroups()).hasSize(1);
        assertThat(preserved.specGroups().getFirst().values())
                .extracting("valueKey")
                .containsExactly("legacy-red", "legacy-blue");
        assertThat(preserved.displayBadgeText()).isEqualTo("热卖");
        assertThat(preserved.displayBadgeTone()).isEqualTo("ORANGE");
        assertThat(preserved.guaranteeServiceIds()).containsExactly(guaranteeServiceId);
        assertThat(preserved.skus()).extracting("specValueKeys")
                .containsExactly(List.of("legacy-red"), List.of("legacy-blue"));

        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId, preserved.title(), preserved.subtitle(), preserved.mainImage(), preserved.mainImageFileId(),
                preserved.mainVideo(), preserved.mainVideoFileId(), "MULTI", preserved.freightTemplateId(),
                preserved.virtualSales(), preserved.sellingPoints(), preserved.detailHtml(), preserved.sortOrder(),
                preserved.images().stream()
                        .map(image -> new AdminProductImageUpsertRequest(image.url(), image.fileId()))
                        .toList(),
                preserved.skus().stream()
                        .map(sku -> new AdminSkuUpsertRequest(
                                sku.id(), sku.skuCode(), sku.specJson(), sku.specText(), sku.priceCent(),
                                sku.originalPriceCent(), sku.stockAvailable(), sku.weightGram(), sku.costPriceCent(),
                                sku.volumeCubicMeter(), sku.image(), sku.imageFileId(), sku.status(), sku.sortOrder(),
                                sku.defaultSelected(), sku.combinationKey(), List.of(), true
                        ))
                        .toList(),
                List.of(), "", "NEUTRAL", List.of(), true, true, true
        ));

        var cleared = productReadMapper.adminSpuDetail(spuId);
        assertThat(cleared.specGroups()).isEmpty();
        assertThat(cleared.displayBadgeText()).isEmpty();
        assertThat(cleared.displayBadgeTone()).isEqualTo("NEUTRAL");
        assertThat(cleared.guaranteeServiceIds()).isEmpty();
        assertThat(cleared.skus()).allSatisfy(sku -> assertThat(sku.specValueKeys()).isEmpty());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from product_sku_spec_value sv
                        join product_sku s on s.id = sv.sku_id
                        where s.spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void updateStructuredSpecsReusesSkuIdsWhenNewValueKeysKeepTheSameDisplayNames() {
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Replace Structured Spec Category", "", null, 1, "ENABLED"));
        AdminSpuSpecGroupUpsertRequest originalGroup = new AdminSpuSpecGroupUpsertRequest(
                null, "color", "颜色", true, 0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "old-red", "红色", "", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "old-blue", "蓝色", "", null, 1)
                )
        );
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId, "Replace Structured Spec SPU", "", "https://example.test/main.jpg", null,
                "", null, "MULTI", 1L, 0L, "", "", 0, List.of(),
                List.of(
                        new AdminSkuUpsertRequest(null, "REPLACE-SPEC-RED", null, null, 1990L, 0L, 1, null,
                                null, null, "", null, "ENABLED", 0, true, null, List.of("old-red"), false),
                        new AdminSkuUpsertRequest(null, "REPLACE-SPEC-BLUE", null, null, 2090L, 0L, 1, null,
                                null, null, "", null, "ENABLED", 1, false, null, List.of("old-blue"), false)
                ),
                List.of(originalGroup), "", "NEUTRAL", List.of(), false, false, true
        ));
        Map<String, Object> originalIds = jdbcClient.sql("""
                        select max(case when spec_text = '红色' then id end) as red_id,
                               max(case when spec_text = '蓝色' then id end) as blue_id
                        from product_sku
                        where spu_id = :spuId
                        """)
                .param("spuId", spuId)
                .query()
                .singleRow();

        AdminSpuSpecGroupUpsertRequest replacementGroup = new AdminSpuSpecGroupUpsertRequest(
                null, "color-v2", "颜色", true, 0,
                List.of(
                        new AdminSpuSpecValueUpsertRequest(null, "new-red", "红色", "", null, 0),
                        new AdminSpuSpecValueUpsertRequest(null, "new-blue", "蓝色", "", null, 1)
                )
        );
        adminProductService.updateSpu(spuId, new AdminSpuUpsertRequest(
                categoryId, "Replace Structured Spec SPU", "", "https://example.test/main.jpg", null,
                "", null, "MULTI", 1L, 0L, "", "", 0, List.of(),
                List.of(
                        new AdminSkuUpsertRequest(null, "REPLACE-SPEC-RED-V2", null, null, 2190L, 0L, 1, null,
                                null, null, "", null, "ENABLED", 0, true, null, List.of("new-red"), false),
                        new AdminSkuUpsertRequest(null, "REPLACE-SPEC-BLUE-V2", null, null, 2290L, 0L, 1, null,
                                null, null, "", null, "ENABLED", 1, false, null, List.of("new-blue"), false)
                ),
                List.of(replacementGroup), "", "NEUTRAL", List.of(), false, false, true
        ));

        List<Map<String, Object>> updatedSkus = jdbcClient.sql("""
                        select id, spec_text, combination_key, deleted_at
                        from product_sku
                        where spu_id = :spuId
                        order by spec_text desc
                        """)
                .param("spuId", spuId)
                .query()
                .listOfRows();
        assertThat(updatedSkus).hasSize(2);
        assertThat(updatedSkus).allSatisfy(row -> assertThat(row.get("DELETED_AT")).isNull());
        assertThat(updatedSkus).extracting(row -> row.get("ID"))
                .containsExactlyInAnyOrder(originalIds.get("RED_ID"), originalIds.get("BLUE_ID"));
        assertThat(updatedSkus).extracting(row -> row.get("COMBINATION_KEY"))
                .containsExactlyInAnyOrder("new-red", "new-blue");
    }

    @Test
    void skuFallbackToSpecImageKeepsTheStorageFileIdAndUsage() {
        StoredFile specImage = insertStorageFile("structured-spec-fallback.png");
        Long categoryId = adminProductService.createCategory(new AdminCategoryRequest(
                0L, "Spec Image Fallback Category", "", null, 1, "ENABLED"));
        AdminSpuSpecGroupUpsertRequest group = new AdminSpuSpecGroupUpsertRequest(
                null, "color-image", "颜色", true, 0,
                List.of(new AdminSpuSpecValueUpsertRequest(
                        null, "fallback-red", "红色", specImage.publicUrl(), specImage.id(), 0
                ))
        );
        Long spuId = adminProductService.createSpu(new AdminSpuUpsertRequest(
                categoryId, "Spec Image Fallback SPU", "", "https://example.test/main.jpg", null,
                "", null, "MULTI", 1L, 0L, "", "", 0, List.of(),
                List.of(new AdminSkuUpsertRequest(
                        null, "SPEC-IMAGE-FALLBACK-SKU", null, null, 1990L, 0L, 1, null,
                        null, null, "", null, "ENABLED", 0, true, null, List.of("fallback-red"), false
                )),
                List.of(group), "", "NEUTRAL", List.of(), false, false, true
        ));
        Map<String, Object> sku = jdbcClient.sql("""
                        select id, image, image_file_id
                        from product_sku
                        where spu_id = :spuId and deleted_at is null
                        """)
                .param("spuId", spuId)
                .query()
                .singleRow();

        assertThat(sku)
                .containsEntry("IMAGE", specImage.publicUrl())
                .containsEntry("IMAGE_FILE_ID", specImage.id());
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where asset_id = :fileId
                          and usage_type = 'PRODUCT_SKU_IMAGE'
                          and owner_type = 'PRODUCT_SKU'
                          and owner_id = :skuId
                          and status = 'ACTIVE'
                        """)
                .param("fileId", specImage.id())
                .param("skuId", sku.get("ID"))
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    private StoredFile insertStorageFile(String originalFilename) {
        String objectKey = "public/test/product-service/" + System.nanoTime() + "-" + originalFilename;
        String publicUrl = "http://localhost:8080/files/" + objectKey;
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('LIBRARY', 'IMAGE', null, 'PUBLIC', 'TENCENT_COS', '', :objectKey, :originalFilename,
                             'image/png', 'png', 68, :sha256, 1, 1, '', null,
                             :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("sha256", Long.toHexString(System.nanoTime()))
                .param("publicUrl", publicUrl)
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new StoredFile(id, publicUrl);
    }

    private record StoredFile(Long id, String publicUrl) {
    }
}
