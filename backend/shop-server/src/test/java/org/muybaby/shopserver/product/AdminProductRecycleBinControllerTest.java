package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.muybaby.shopserver.support.AdminTokenTestSupport.issueAdminToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminProductRecycleBinControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private OpaqueTokenService opaqueTokenService;

    @Test
    void recycleHidesProductFromActiveListAndRestorePreservesTheWholeAggregate() throws Exception {
        ProductFixture fixture = insertRichProduct("Recycle restore product");
        String deleteToken = adminToken("product:spu:delete");
        String restoreToken = adminToken("product:spu:restore");

        mockMvc.perform(delete("/admin/product/spus/{spuId}", fixture.spuId())
                        .header("Authorization", bearer(deleteToken)))
                .andExpect(status().isOk());

        assertThat(spuState(fixture.spuId()))
                .containsEntry("STATUS", "OFF_SALE");
        assertThat(spuState(fixture.spuId()).get("DELETED_AT")).isNotNull();
        assertThat(skuState(fixture.skuId()))
                .containsEntry("STATUS", "ENABLED")
                .containsEntry("IS_DEFAULT", true)
                .containsEntry("DELETED_AT", null);
        assertThat(activeAggregateRowCounts(fixture))
                .containsEntry("gallery", 1)
                .containsEntry("specGroups", 1)
                .containsEntry("specValues", 1)
                .containsEntry("skuSpecValues", 1)
                .containsEntry("tags", 1)
                .containsEntry("guarantees", 1)
                .containsEntry("coupons", 1)
                .containsEntry("activeUsages", 4);

        mockMvc.perform(post("/admin/product/skus/{skuId}/stock-adjustments", fixture.skuId())
                        .header("Authorization", bearer(adminToken("product:sku:stock")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantityDelta":3,"reason":"recycled products are immutable"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200002));
        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", fixture.skuId())
                .query(Integer.class)
                .single()).isEqualTo(9);

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(restoreToken))
                        .param("title", fixture.originalTitle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(restoreToken))
                        .param("recycled", "true")
                        .param("title", fixture.originalTitle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(fixture.spuId()))
                .andExpect(jsonPath("$.data.records[0].status").value("OFF_SALE"))
                .andExpect(jsonPath("$.data.records[0].deletedAt", notNullValue()));

        mockMvc.perform(post("/admin/product/spus/{spuId}/restore", fixture.spuId())
                        .header("Authorization", bearer(restoreToken)))
                .andExpect(status().isOk());

        assertThat(spuState(fixture.spuId()))
                .containsEntry("STATUS", "OFF_SALE")
                .containsEntry("DELETED_AT", null);
        assertThat(skuState(fixture.skuId()))
                .containsEntry("STATUS", "ENABLED")
                .containsEntry("IS_DEFAULT", true)
                .containsEntry("DELETED_AT", null);
        assertThat(activeAggregateRowCounts(fixture))
                .containsEntry("gallery", 1)
                .containsEntry("specGroups", 1)
                .containsEntry("specValues", 1)
                .containsEntry("skuSpecValues", 1)
                .containsEntry("tags", 1)
                .containsEntry("guarantees", 1)
                .containsEntry("coupons", 1)
                .containsEntry("activeUsages", 4);
    }

    @Test
    void purgeRejectsAConfirmationTitleThatDoesNotExactlyMatch() throws Exception {
        ProductFixture fixture = insertRichProduct("Purge title contract");
        recycle(fixture.spuId());

        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", fixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:purge")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(fixture.originalTitle() + " ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200004));

        assertThat(spuState(fixture.spuId()).get("PURGED_AT")).isNull();
        assertThat(jdbcClient.sql("select title from product_spu where id = :spuId")
                .param("spuId", fixture.spuId())
                .query(String.class)
                .single()).isEqualTo(fixture.originalTitle());
        assertThat(activeAggregateRowCounts(fixture).get("activeUsages")).isEqualTo(4);
    }

    @Test
    void purgeRejectsProductsWithLockedInventory() throws Exception {
        ProductFixture fixture = insertRichProduct("Locked inventory product");
        OrderFixture order = insertOrderSnapshot(fixture);
        jdbcClient.sql("""
                        insert into stock_lock
                            (order_id, order_item_id, sku_id, quantity, status)
                        values
                            (:orderId, :orderItemId, :skuId, 1, 'LOCKED')
                        """)
                .param("orderId", order.orderId())
                .param("orderItemId", order.orderItemId())
                .param("skuId", fixture.skuId())
                .update();
        recycle(fixture.spuId());

        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", fixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:purge")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(fixture.originalTitle())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200005));

        assertThat(spuState(fixture.spuId()).get("PURGED_AT")).isNull();
        assertThat(count("product_spu_image", "spu_id", fixture.spuId())).isEqualTo(1);
        assertThat(count("stock_lock", "sku_id", fixture.skuId())).isEqualTo(1);
    }

    @Test
    void purgeRejectsAProductUsedByAnEnabledHomeBanner() throws Exception {
        ProductFixture fixture = insertRichProduct("Banner referenced product");
        jdbcClient.sql("""
                        insert into home_banner
                            (title, image_file_id, image_url, jump_type, jump_target_id, status, sort_order)
                        values
                            (:title, :fileId, :imageUrl, 'PRODUCT', :spuId, 'ENABLED', 0)
                        """)
                .param("title", "Enabled product banner " + fixture.spuId())
                .param("fileId", fixture.fileId())
                .param("imageUrl", fixture.fileUrl())
                .param("spuId", fixture.spuId())
                .update();
        recycle(fixture.spuId());

        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", fixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:purge")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(fixture.originalTitle())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200006));

        assertThat(spuState(fixture.spuId()).get("PURGED_AT")).isNull();
        assertThat(count("home_banner", "jump_target_id", fixture.spuId())).isEqualTo(1);
        assertThat(activeAggregateRowCounts(fixture).get("activeUsages")).isEqualTo(4);
    }

    @Test
    void purgeKeepsOrderSnapshotsProtectedFilesAndSharedMasterDataButRemovesPrivateProductData() throws Exception {
        ProductFixture fixture = insertRichProduct("Order history purge product");
        long disabledBannerId = insertProductBanner(fixture, "DISABLED");
        OrderFixture order = insertOrderSnapshot(fixture);
        insertCartItem(fixture.skuId());
        jdbcClient.sql("""
                        insert into stock_log
                            (sku_id, change_type, quantity_before, quantity_delta, quantity_after,
                             reason, operator_type, operator_id)
                        values
                            (:skuId, 'INITIAL', 0, 9, 9, 'fixture', 'ADMIN', 1)
                        """)
                .param("skuId", fixture.skuId())
                .update();
        recycle(fixture.spuId());

        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", fixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:purge")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(fixture.originalTitle())))
                .andExpect(status().isOk());

        Map<String, Object> tombstone = jdbcClient.sql("""
                        select category_id, freight_template_id, title, subtitle,
                               main_image, main_image_file_id, main_video, main_video_file_id,
                               virtual_sales, selling_points, detail_html, sort_order,
                               status, deleted_at, purged_at
                        from product_spu
                        where id = :spuId
                        """)
                .param("spuId", fixture.spuId())
                .query()
                .singleRow();
        assertThat(tombstone)
                .containsEntry("CATEGORY_ID", fixture.categoryId())
                .containsEntry("FREIGHT_TEMPLATE_ID", fixture.freightTemplateId())
                .containsEntry("TITLE", "[已永久删除商品 #" + fixture.spuId() + "]")
                .containsEntry("SUBTITLE", "")
                .containsEntry("MAIN_IMAGE", "")
                .containsEntry("MAIN_IMAGE_FILE_ID", null)
                .containsEntry("MAIN_VIDEO", "")
                .containsEntry("MAIN_VIDEO_FILE_ID", null)
                .containsEntry("VIRTUAL_SALES", 0L)
                .containsEntry("SELLING_POINTS", "")
                .containsEntry("DETAIL_HTML", "")
                .containsEntry("SORT_ORDER", 0)
                .containsEntry("STATUS", "OFF_SALE");
        assertThat(tombstone.get("DELETED_AT")).isNotNull();
        assertThat(tombstone.get("PURGED_AT")).isNotNull();

        Map<String, Object> skuTombstone = jdbcClient.sql("""
                        select sku_code, spec_json, spec_text, price_cent, original_price_cent,
                               cost_price_cent, stock_available, weight_gram, volume_cubic_meter,
                               image, image_file_id, status, is_default, combination_key, deleted_at
                        from product_sku
                        where id = :skuId
                        """)
                .param("skuId", fixture.skuId())
                .query()
                .singleRow();
        assertThat(skuTombstone)
                .containsEntry("SKU_CODE", fixture.skuCode())
                .containsEntry("SPEC_JSON", "{}")
                .containsEntry("SPEC_TEXT", "[已永久删除规格 #" + fixture.skuId() + "]")
                .containsEntry("PRICE_CENT", 0L)
                .containsEntry("ORIGINAL_PRICE_CENT", 0L)
                .containsEntry("COST_PRICE_CENT", null)
                .containsEntry("STOCK_AVAILABLE", 0)
                .containsEntry("WEIGHT_GRAM", null)
                .containsEntry("VOLUME_CUBIC_METER", null)
                .containsEntry("IMAGE", "")
                .containsEntry("IMAGE_FILE_ID", null)
                .containsEntry("STATUS", "DISABLED")
                .containsEntry("IS_DEFAULT", false)
                .containsEntry("COMBINATION_KEY", "PURGED:" + fixture.skuId());
        assertThat(skuTombstone.get("DELETED_AT")).isNotNull();

        assertThat(count("product_spu_image", "spu_id", fixture.spuId())).isZero();
        assertThat(count("product_spu_spec_group", "spu_id", fixture.spuId())).isZero();
        assertThat(count("product_spu_spec_value", "group_id", fixture.specGroupId())).isZero();
        assertThat(count("product_sku_spec_value", "sku_id", fixture.skuId())).isZero();
        assertThat(count("product_spu_tag", "spu_id", fixture.spuId())).isZero();
        assertThat(count("product_spu_guarantee_service", "spu_id", fixture.spuId())).isZero();
        assertThat(count("product_spu_coupon", "spu_id", fixture.spuId())).isZero();
        assertThat(count("cart_item", "sku_id", fixture.skuId())).isZero();
        assertThat(activeProductUsageCount(fixture.spuId(), fixture.skuId(), fixture.specValueId())).isZero();

        Map<String, Object> orderItem = jdbcClient.sql("""
                        select spu_id, sku_id, product_title, main_image, main_image_file_id,
                               sku_code, spec_text, unit_price_cent, quantity
                        from order_item
                        where id = :orderItemId
                        """)
                .param("orderItemId", order.orderItemId())
                .query()
                .singleRow();
        assertThat(orderItem)
                .containsEntry("SPU_ID", fixture.spuId())
                .containsEntry("SKU_ID", fixture.skuId())
                .containsEntry("PRODUCT_TITLE", fixture.originalTitle())
                .containsEntry("MAIN_IMAGE", fixture.fileUrl())
                .containsEntry("MAIN_IMAGE_FILE_ID", fixture.fileId())
                .containsEntry("SKU_CODE", fixture.skuCode())
                .containsEntry("SPEC_TEXT", "红色")
                .containsEntry("UNIT_PRICE_CENT", 3190L)
                .containsEntry("QUANTITY", 2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_type = 'ORDER_ITEM'
                          and owner_id = :orderItemId
                          and protected = true
                          and status = 'ACTIVE'
                        """)
                .param("orderItemId", order.orderItemId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(jdbcClient.sql("select status from storage_asset where id = :fileId")
                .param("fileId", fixture.fileId())
                .query(String.class)
                .single()).isEqualTo("ACTIVE");
        assertThat(count("stock_log", "sku_id", fixture.skuId())).isEqualTo(1);

        assertThat(count("product_category", "id", fixture.categoryId())).isEqualTo(1);
        assertThat(count("freight_template", "id", fixture.freightTemplateId())).isEqualTo(1);
        assertThat(count("product_spec_template", "id", fixture.specTemplateId())).isEqualTo(1);
        assertThat(count("product_guarantee_service", "id", fixture.guaranteeServiceId())).isEqualTo(1);
        assertThat(count("coupon_template", "id", fixture.couponTemplateId())).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select jump_type, jump_target_id, jump_path, status
                        from home_banner
                        where id = :bannerId
                        """)
                .param("bannerId", disabledBannerId)
                .query()
                .singleRow())
                .containsEntry("JUMP_TYPE", "NONE")
                .containsEntry("JUMP_TARGET_ID", null)
                .containsEntry("JUMP_PATH", "")
                .containsEntry("STATUS", "DISABLED");

        mockMvc.perform(post("/admin/product/spus/{spuId}/restore", fixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:restore"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200003));
        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(adminToken("product:spu:restore")))
                        .param("recycled", "true")
                        .param("title", "[已永久删除商品 #" + fixture.spuId() + "]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void restoreAndPurgeRequireTheirOwnAuthorities() throws Exception {
        ProductFixture restoreFixture = insertRichProduct("Restore authority product");
        recycle(restoreFixture.spuId());

        mockMvc.perform(post("/admin/product/spus/{spuId}/restore", restoreFixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:delete"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
        mockMvc.perform(post("/admin/product/spus/{spuId}/restore", restoreFixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:restore"))))
                .andExpect(status().isOk());

        ProductFixture purgeFixture = insertRichProduct("Purge authority product");
        recycle(purgeFixture.spuId());
        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", purgeFixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:restore")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(purgeFixture.originalTitle())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));
        mockMvc.perform(post("/admin/product/spus/{spuId}/purge", purgeFixture.spuId())
                        .header("Authorization", bearer(adminToken("product:spu:purge")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(purgeBody(purgeFixture.originalTitle())))
                .andExpect(status().isOk());
    }

    @Test
    void recycleBinListRequiresADeleteRestoreOrPurgeAuthority() throws Exception {
        ProductFixture fixture = insertRichProduct("Recycle list authority product");
        recycle(fixture.spuId());
        String stockOnlyToken = adminToken("product:sku:stock");

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(stockOnlyToken))
                        .param("title", fixture.originalTitle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(stockOnlyToken))
                        .param("recycled", "true")
                        .param("title", fixture.originalTitle()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(100003));

        mockMvc.perform(get("/admin/product/spus")
                        .header("Authorization", bearer(adminToken("product:spu:delete")))
                        .param("recycled", "true")
                        .param("title", fixture.originalTitle()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private ProductFixture insertRichProduct(String titlePrefix) {
        String suffix = Long.toString(System.nanoTime());
        String title = titlePrefix + " " + suffix;
        String skuCode = "RECYCLE-SKU-" + suffix;
        long categoryId = insertCategory("Recycle category " + suffix);
        long freightTemplateId = insertFreightTemplate("Recycle freight " + suffix);
        long fileId = insertStorageFile("recycle-" + suffix + ".png");
        String fileUrl = jdbcClient.sql("select public_url from storage_asset where id = :fileId")
                .param("fileId", fileId)
                .query(String.class)
                .single();

        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, main_image_file_id,
                             main_video, spec_type, freight_template_id, virtual_sales,
                             selling_points, detail_html, sort_order, status)
                        values
                            (:categoryId, :title, 'subtitle snapshot', :mainImage, :mainImageFileId,
                             'https://example.test/product.mp4', 'MULTI', :freightTemplateId, 17,
                             'selling points snapshot', '<p>detail snapshot</p>', 8, 'ON_SALE')
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("mainImage", fileUrl)
                .param("mainImageFileId", fileId)
                .param("freightTemplateId", freightTemplateId)
                .update();
        long spuId = jdbcClient.sql("select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        insert into product_spu_image (spu_id, url, file_id, sort_order)
                        values (:spuId, :url, :fileId, 0)
                        """)
                .param("spuId", spuId)
                .param("url", fileUrl)
                .param("fileId", fileId)
                .update();
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, cost_price_cent, volume_cubic_meter,
                             image, image_file_id, status, sort_order, is_default, combination_key)
                        values
                            (:spuId, :skuCode, '{"color":"red"}', '红色', 3190, 3990,
                             9, 500, 2100, 0.001200,
                             :image, :imageFileId, 'ENABLED', 0, true, 'red')
                        """)
                .param("spuId", spuId)
                .param("skuCode", skuCode)
                .param("image", fileUrl)
                .param("imageFileId", fileId)
                .update();
        long skuId = jdbcClient.sql("select id from product_sku where sku_code = :skuCode")
                .param("skuCode", skuCode)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        insert into product_spu_spec_group
                            (spu_id, group_key, name, image_enabled, sort_order)
                        values
                            (:spuId, 'color', '颜色', true, 0)
                        """)
                .param("spuId", spuId)
                .update();
        long specGroupId = jdbcClient.sql("select id from product_spu_spec_group where spu_id = :spuId")
                .param("spuId", spuId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spu_spec_value
                            (group_id, value_key, value_name, image, image_file_id, sort_order)
                        values
                            (:groupId, 'red', '红色', :image, :imageFileId, 0)
                        """)
                .param("groupId", specGroupId)
                .param("image", fileUrl)
                .param("imageFileId", fileId)
                .update();
        long specValueId = jdbcClient.sql("select id from product_spu_spec_value where group_id = :groupId")
                .param("groupId", specGroupId)
                .query(Long.class)
                .single();
        jdbcClient.sql("insert into product_sku_spec_value (sku_id, spec_value_id) values (:skuId, :valueId)")
                .param("skuId", skuId)
                .param("valueId", specValueId)
                .update();

        long specTemplateId = insertSpecTemplate("Recycle template " + suffix);
        long guaranteeServiceId = insertGuaranteeService("Recycle guarantee " + suffix);
        long couponTemplateId = insertCouponTemplate("Recycle coupon " + suffix);
        jdbcClient.sql("""
                        insert into product_spu_guarantee_service (spu_id, service_id, sort_order)
                        values (:spuId, :serviceId, 0)
                        """)
                .param("spuId", spuId)
                .param("serviceId", guaranteeServiceId)
                .update();
        jdbcClient.sql("insert into product_spu_tag (spu_id, tag_code) values (:spuId, 'HOT_SALE')")
                .param("spuId", spuId)
                .update();
        jdbcClient.sql("""
                        insert into product_spu_coupon (spu_id, coupon_template_id)
                        values (:spuId, :couponTemplateId)
                        """)
                .param("spuId", spuId)
                .param("couponTemplateId", couponTemplateId)
                .update();

        insertUsage(fileId, "PRODUCT_SPU_MAIN", "PRODUCT_SPU", spuId, title, fileUrl, false);
        insertUsage(fileId, "PRODUCT_SPU_GALLERY", "PRODUCT_SPU", spuId, title, fileUrl, false);
        insertUsage(fileId, "PRODUCT_SKU_IMAGE", "PRODUCT_SKU", skuId, skuCode, fileUrl, false);
        insertUsage(fileId, "PRODUCT_SPEC_VALUE_IMAGE", "PRODUCT_SPEC_VALUE", specValueId, "红色", fileUrl, false);

        return new ProductFixture(
                spuId,
                skuId,
                specGroupId,
                specValueId,
                categoryId,
                freightTemplateId,
                specTemplateId,
                guaranteeServiceId,
                couponTemplateId,
                fileId,
                title,
                skuCode,
                fileUrl
        );
    }

    private long insertCategory(String name) {
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 0, 'ENABLED')
                        """)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from product_category where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertFreightTemplate(String name) {
        jdbcClient.sql("""
                        insert into freight_template
                            (name, charge_mode, fixed_amount_cent, status, sort_order)
                        values
                            (:name, 'FREE', 0, 'ENABLED', 0)
                        """)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from freight_template where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertSpecTemplate(String name) {
        jdbcClient.sql("insert into product_spec_template (name) values (:name)")
                .param("name", name)
                .update();
        long templateId = jdbcClient.sql("select id from product_spec_template where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spec_template_group
                            (template_id, group_key, name, image_enabled, sort_order)
                        values
                            (:templateId, 'template-color', '模板颜色', true, 0)
                        """)
                .param("templateId", templateId)
                .update();
        long groupId = jdbcClient.sql("select id from product_spec_template_group where template_id = :templateId")
                .param("templateId", templateId)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spec_template_value
                            (group_id, value_key, value_name, sort_order)
                        values
                            (:groupId, 'template-red', '模板红色', 0)
                        """)
                .param("groupId", groupId)
                .update();
        return templateId;
    }

    private long insertGuaranteeService(String name) {
        jdbcClient.sql("""
                        insert into product_guarantee_service
                            (terms_name, content_description, icon, sort_order, visible)
                        values
                            (:name, 'guarantee description', '', 0, true)
                        """)
                .param("name", name)
                .update();
        return jdbcClient.sql("select id from product_guarantee_service where terms_name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertCouponTemplate(String name) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into coupon_template
                            (name, coupon_type, discount_type, threshold_cent, discount_cent,
                             total_stock, per_user_limit, valid_start_at, valid_end_at, status)
                        values
                            (:name, 'NORMAL', 'AMOUNT_OFF', 0, 100,
                             100, 1, :validStartAt, :validEndAt, 'ENABLED')
                        """)
                .param("name", name)
                .param("validStartAt", now.minusDays(1))
                .param("validEndAt", now.plusDays(30))
                .update();
        return jdbcClient.sql("select id from coupon_template where name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private long insertStorageFile(String originalFilename) {
        String objectKey = "public/test/recycle/" + System.nanoTime() + "-" + originalFilename;
        String publicUrl = "http://localhost:8080/files/" + objectKey;
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256,
                             width, height, alt_text, public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            ('LIBRARY', 'IMAGE', null, 'PUBLIC', 'LOCAL', '', :objectKey,
                             :originalFilename, 'image/png', 'png', 68, :sha256,
                             1, 1, '', :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("objectKey", objectKey)
                .param("originalFilename", originalFilename)
                .param("sha256", "sha-" + System.nanoTime())
                .param("publicUrl", publicUrl)
                .update();
        return jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
    }

    private OrderFixture insertOrderSnapshot(ProductFixture fixture) {
        String orderNo = "RECYCLE" + System.nanoTime();
        jdbcClient.sql("""
                        insert into shop_order
                            (order_no, user_id, status, source, idempotency_key)
                        values
                            (:orderNo, 1, 'PAID', 'DIRECT', :idempotencyKey)
                        """)
                .param("orderNo", orderNo)
                .param("idempotencyKey", "recycle-" + orderNo)
                .update();
        long orderId = jdbcClient.sql("select id from shop_order where order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into order_item
                            (order_id, sku_id, spu_id, product_title, main_image, main_image_file_id,
                             sku_code, spec_text, unit_price_cent, quantity)
                        values
                            (:orderId, :skuId, :spuId, :productTitle, :mainImage, :mainImageFileId,
                             :skuCode, '红色', 3190, 2)
                        """)
                .param("orderId", orderId)
                .param("skuId", fixture.skuId())
                .param("spuId", fixture.spuId())
                .param("productTitle", fixture.originalTitle())
                .param("mainImage", fixture.fileUrl())
                .param("mainImageFileId", fixture.fileId())
                .param("skuCode", fixture.skuCode())
                .update();
        long orderItemId = jdbcClient.sql("select id from order_item where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        insertUsage(
                fixture.fileId(),
                "ORDER_ITEM_SNAPSHOT",
                "ORDER_ITEM",
                orderItemId,
                fixture.originalTitle(),
                fixture.fileUrl(),
                true
        );
        return new OrderFixture(orderId, orderItemId);
    }

    private long insertProductBanner(ProductFixture fixture, String status) {
        String title = status + " product banner " + System.nanoTime();
        jdbcClient.sql("""
                        insert into home_banner
                            (title, image_file_id, image_url, jump_type, jump_target_id,
                             jump_path, status, sort_order)
                        values
                            (:title, :fileId, :imageUrl, 'PRODUCT', :spuId,
                             '', :status, 0)
                        """)
                .param("title", title)
                .param("fileId", fixture.fileId())
                .param("imageUrl", fixture.fileUrl())
                .param("spuId", fixture.spuId())
                .param("status", status)
                .update();
        return jdbcClient.sql("select id from home_banner where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
    }

    private void insertCartItem(long skuId) {
        jdbcClient.sql("insert into cart_item (user_id, sku_id, quantity) values (:userId, :skuId, 2)")
                .param("userId", Math.max(2L, System.nanoTime()))
                .param("skuId", skuId)
                .update();
    }

    private void insertUsage(
            long fileId,
            String usageType,
            String ownerType,
            long ownerId,
            String ownerLabel,
            String snapshotUrl,
            boolean protectedUsage
    ) {
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (asset_id, usage_type, owner_type, owner_id, owner_label,
                             snapshot_url, sort_order, protected, status)
                        values
                            (:fileId, :usageType, :ownerType, :ownerId, :ownerLabel,
                             :snapshotUrl, 0, :protectedUsage, 'ACTIVE')
                        """)
                .param("fileId", fileId)
                .param("usageType", usageType)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .param("ownerLabel", ownerLabel)
                .param("snapshotUrl", snapshotUrl)
                .param("protectedUsage", protectedUsage)
                .update();
    }

    private void recycle(long spuId) throws Exception {
        mockMvc.perform(delete("/admin/product/spus/{spuId}", spuId)
                        .header("Authorization", bearer(adminToken("product:spu:delete"))))
                .andExpect(status().isOk());
    }

    private Map<String, Object> spuState(long spuId) {
        return jdbcClient.sql("select status, deleted_at, purged_at from product_spu where id = :spuId")
                .param("spuId", spuId)
                .query()
                .singleRow();
    }

    private Map<String, Object> skuState(long skuId) {
        return jdbcClient.sql("select status, is_default, deleted_at from product_sku where id = :skuId")
                .param("skuId", skuId)
                .query()
                .singleRow();
    }

    private Map<String, Integer> activeAggregateRowCounts(ProductFixture fixture) {
        return Map.of(
                "gallery", count("product_spu_image", "spu_id", fixture.spuId()),
                "specGroups", activeCount("product_spu_spec_group", "spu_id", fixture.spuId()),
                "specValues", activeCount("product_spu_spec_value", "group_id", fixture.specGroupId()),
                "skuSpecValues", count("product_sku_spec_value", "sku_id", fixture.skuId()),
                "tags", count("product_spu_tag", "spu_id", fixture.spuId()),
                "guarantees", count("product_spu_guarantee_service", "spu_id", fixture.spuId()),
                "coupons", count("product_spu_coupon", "spu_id", fixture.spuId()),
                "activeUsages", activeProductUsageCount(fixture.spuId(), fixture.skuId(), fixture.specValueId())
        );
    }

    private int activeProductUsageCount(long spuId, long skuId, long specValueId) {
        return jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where status = 'ACTIVE'
                          and (
                            (owner_type = 'PRODUCT_SPU' and owner_id = :spuId)
                            or (owner_type = 'PRODUCT_SKU' and owner_id = :skuId)
                            or (owner_type = 'PRODUCT_SPEC_VALUE' and owner_id = :specValueId)
                          )
                        """)
                .param("spuId", spuId)
                .param("skuId", skuId)
                .param("specValueId", specValueId)
                .query(Integer.class)
                .single();
    }

    private int activeCount(String table, String idColumn, long id) {
        return jdbcClient.sql("select count(*) from " + table + " where " + idColumn + " = :id and deleted_at is null")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private int count(String table, String idColumn, long id) {
        return jdbcClient.sql("select count(*) from " + table + " where " + idColumn + " = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }

    private String adminToken(String... permissions) {
        return issueAdminToken(jdbcClient, opaqueTokenService, List.of(permissions));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String purgeBody(String title) {
        return "{\"confirmationTitle\":\"" + title + "\"}";
    }

    private record ProductFixture(
            long spuId,
            long skuId,
            long specGroupId,
            long specValueId,
            long categoryId,
            long freightTemplateId,
            long specTemplateId,
            long guaranteeServiceId,
            long couponTemplateId,
            long fileId,
            String originalTitle,
            String skuCode,
            String fileUrl
    ) {
    }

    private record OrderFixture(long orderId, long orderItemId) {
    }
}
