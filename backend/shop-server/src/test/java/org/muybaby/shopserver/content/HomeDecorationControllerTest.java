package org.muybaby.shopserver.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "shop.content.cache-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HomeDecorationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clearContentFixtures() {
        jdbcClient.sql("delete from home_category_item").update();
        jdbcClient.sql("delete from home_product_item").update();
        jdbcClient.sql("delete from home_banner").update();
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from storage_asset").update();
        jdbcClient.sql("delete from product_sku where sku_code like 'HOME-%'").update();
        jdbcClient.sql("""
                        delete from product_spu
                        where title in ('首页编排商品', '唯一编排商品', '自动填充商品')
                        """).update();
        jdbcClient.sql("delete from product_category where name like '装修分类-%'").update();
        jdbcClient.sql("update app_contact_setting set phone_number = '' where id = 1").update();
    }

    @Test
    void adminCanManageCategoriesAndProductSectionsAndPublicHomeIsAggregated() throws Exception {
        String token = adminLoginAndExtractToken();
        Asset categoryImage = insertPublicImage("home-category.png");
        Asset hotImage = insertPublicImage("home-hot.png");
        Product product = insertOnSaleProduct("首页编排商品", "商品副标题", "http://localhost/product-main.png", 1990, 2590);

        long categoryItemId = responseId(mockMvc.perform(post("/admin/home/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"imageFileId":%d,"sortOrder":2,"status":"ENABLED"}
                                """.formatted(product.categoryId(), categoryImage.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNumber())
                .andReturn().getResponse().getContentAsString());

        long hotItemId = responseId(mockMvc.perform(post("/admin/home/hot-products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spuId":%d,"imageFileId":%d,"sortOrder":3,"status":"ENABLED"}
                                """.formatted(product.spuId(), hotImage.id())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        long recommendedItemId = responseId(mockMvc.perform(post("/admin/home/recommended-products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spuId":%d,"imageFileId":null,"sortOrder":1,"status":"ENABLED",
                                 "badgeMode":"CUSTOM","customBadgeText":"限时尝鲜"}
                                """.formatted(product.spuId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/admin/home/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(categoryItemId))
                .andExpect(jsonPath("$.data[0].categoryId").value(product.categoryId()))
                .andExpect(jsonPath("$.data[0].categoryName").value(product.categoryName()))
                .andExpect(jsonPath("$.data[0].imageUrl").value(categoryImage.url()))
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));

        mockMvc.perform(get("/admin/home/hot-products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(hotItemId))
                .andExpect(jsonPath("$.data[0].sectionType").value("HOT"))
                .andExpect(jsonPath("$.data[0].spuId").value(product.spuId()))
                .andExpect(jsonPath("$.data[0].displayImageUrl").value(hotImage.url()))
                .andExpect(jsonPath("$.data[0].minPriceCent").value(1990))
                .andExpect(jsonPath("$.data[0].maxPriceCent").value(2590))
                .andExpect(jsonPath("$.data[0].badgeMode").value("AUTO"))
                .andExpect(jsonPath("$.data[0].resolvedBadgeText").value("TOP 1"));

        mockMvc.perform(get("/admin/home/recommended-products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(recommendedItemId))
                .andExpect(jsonPath("$.data[0].sectionType").value("RECOMMENDED"))
                .andExpect(jsonPath("$.data[0].imageFileId").doesNotExist())
                .andExpect(jsonPath("$.data[0].displayImageUrl").value(product.mainImage()))
                .andExpect(jsonPath("$.data[0].badgeMode").value("CUSTOM"))
                .andExpect(jsonPath("$.data[0].customBadgeText").value("限时尝鲜"))
                .andExpect(jsonPath("$.data[0].resolvedBadgeText").value("限时尝鲜"));

        mockMvc.perform(get("/admin/home/options/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(product.categoryId()))
                .andExpect(jsonPath("$.data[0].name").value(product.categoryName()));

        mockMvc.perform(get("/admin/home/options/products")
                        .param("keyword", "编排")
                        .param("current", "1")
                        .param("size", "20")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(product.spuId()))
                .andExpect(jsonPath("$.data.records[0].title").value(product.title()));

        insertCurrentBanner(categoryImage);
        mockMvc.perform(get("/app/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.banners[0].title").value("聚合轮播"))
                .andExpect(jsonPath("$.data.categories[0].categoryId").value(product.categoryId()))
                .andExpect(jsonPath("$.data.categories[0].path")
                        .value("/pages/product/list/list?categoryId=" + product.categoryId()))
                .andExpect(jsonPath("$.data.hotProducts[0].spuId").value(product.spuId()))
                .andExpect(jsonPath("$.data.hotProducts[0].imageUrl").value(hotImage.url()))
                .andExpect(jsonPath("$.data.hotProducts[0].path")
                        .value("/pages/product/detail/detail?id=" + product.spuId()))
                .andExpect(jsonPath("$.data.recommendedProducts[0].spuId").value(product.spuId()))
                .andExpect(jsonPath("$.data.recommendedProducts[0].imageUrl").value(product.mainImage()));

        assertThat(activeUsage(categoryItemId, "HOME_CATEGORY_ITEM", "HOME_CATEGORY_IMAGE")).isEqualTo(1);
        assertThat(activeUsage(hotItemId, "HOME_PRODUCT_ITEM", "HOME_PRODUCT_IMAGE")).isEqualTo(1);
        assertThat(activeUsage(recommendedItemId, "HOME_PRODUCT_ITEM", "HOME_PRODUCT_IMAGE")).isZero();

        mockMvc.perform(put("/admin/home/categories/{itemId}", categoryItemId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"imageFileId":%d,"sortOrder":9,"status":"DISABLED"}
                                """.formatted(product.categoryId(), categoryImage.id())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/app/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isEmpty());

        mockMvc.perform(delete("/admin/home/hot-products/{itemId}", hotItemId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(activeUsage(hotItemId, "HOME_PRODUCT_ITEM", "HOME_PRODUCT_IMAGE")).isZero();
    }

    @Test
    void duplicatePlacementsInvalidTargetsAndPrivateAssetsAreRejected() throws Exception {
        String token = adminLoginAndExtractToken();
        Asset publicImage = insertPublicImage("public-home.png");
        Asset privateImage = insertPrivateImage("private-home.png");
        Product product = insertOnSaleProduct("唯一编排商品", "", "http://localhost/unique.png", 990, 990);

        mockMvc.perform(post("/admin/home/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"imageFileId":%d,"sortOrder":0,"status":"ENABLED"}
                                """.formatted(product.categoryId(), publicImage.id())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/home/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":%d,"imageFileId":%d,"sortOrder":1,"status":"ENABLED"}
                                """.formatted(product.categoryId(), publicImage.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));

        mockMvc.perform(post("/admin/home/hot-products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spuId":%d,"imageFileId":%d,"sortOrder":0,"status":"ENABLED"}
                                """.formatted(product.spuId(), privateImage.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(800001));

        mockMvc.perform(post("/admin/home/recommended-products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"spuId":999999999,"imageFileId":null,"sortOrder":0,"status":"ENABLED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(200001));
    }

    @Test
    void autoFillKeepsExistingPlacementsAndAllowsTheSameProductAcrossSections() throws Exception {
        String token = adminLoginAndExtractToken();
        Product product = insertOnSaleProduct(
                "自动填充商品", "", "http://localhost/auto-fill.png", 1290, 1590
        );

        mockMvc.perform(post("/admin/home/hot-products/auto-fill")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetCount":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existingCount").value(0))
                .andExpect(jsonPath("$.data.addedCount").value(1))
                .andExpect(jsonPath("$.data.finalCount").value(1))
                .andExpect(jsonPath("$.data.insufficient").value(false))
                .andExpect(jsonPath("$.data.addedSpuIds[0]").value(product.spuId()));

        mockMvc.perform(post("/admin/home/recommended-products/auto-fill")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetCount":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existingCount").value(0))
                .andExpect(jsonPath("$.data.addedCount").value(1))
                .andExpect(jsonPath("$.data.addedSpuIds[0]").value(product.spuId()));

        mockMvc.perform(post("/admin/home/hot-products/auto-fill")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetCount":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existingCount").value(1))
                .andExpect(jsonPath("$.data.addedCount").value(0))
                .andExpect(jsonPath("$.data.finalCount").value(1));

        Integer crossSectionCount = jdbcClient.sql("""
                        select count(*) from home_product_item
                        where spu_id = :spuId and section_type in ('HOT', 'RECOMMENDED')
                        """)
                .param("spuId", product.spuId())
                .query(Integer.class)
                .single();
        assertThat(crossSectionCount).isEqualTo(2);
    }

    @Test
    void contactSettingIsPersistedValidatedAndPublic() throws Exception {
        String token = adminLoginAndExtractToken();

        mockMvc.perform(get("/admin/contact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(""));

        mockMvc.perform(put("/admin/contact")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":" 400-800-1234 "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("400-800-1234"));

        mockMvc.perform(get("/app/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("400-800-1234"));

        mockMvc.perform(put("/admin/contact")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"not-a-phone"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(100400));
    }

    private long responseId(String response) throws Exception {
        return objectMapper.readTree(response).path("data").asLong();
    }

    private String adminLoginAndExtractToken() throws Exception {
        String response = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userName":"Super","password":"123456"}
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data").path("token").asText();
    }

    private Asset insertPublicImage(String filename) {
        return insertImage(filename, "LIBRARY", "PUBLIC");
    }

    private Asset insertPrivateImage(String filename) {
        return insertImage(filename, "ATTACHMENT", "PRIVATE");
    }

    private Asset insertImage(String filename, String scope, String visibility) {
        String objectKey = "content-test/" + System.nanoTime() + "/" + filename;
        String publicUrl = "PUBLIC".equals(visibility) ? "http://localhost/files/" + filename : null;
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, width, height,
                             alt_text, tags_json, public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:scope, 'IMAGE', null, :visibility, 'LOCAL', '', :objectKey,
                             :filename, 'image/png', 'png', 68, :sha256, 1, 1,
                             '', null, :publicUrl, 'ACTIVE', 'ADMIN', 1)
                        """)
                .param("scope", scope)
                .param("visibility", visibility)
                .param("objectKey", objectKey)
                .param("filename", filename)
                .param("sha256", "sha-" + System.nanoTime())
                .param("publicUrl", publicUrl)
                .update();
        Long id = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return new Asset(id, publicUrl);
    }

    private Product insertOnSaleProduct(
            String title,
            String subtitle,
            String mainImage,
            long firstPrice,
            long secondPrice
    ) {
        String categoryName = "装修分类-" + System.nanoTime();
        jdbcClient.sql("""
                        insert into product_category (parent_id, name, icon, sort_order, status)
                        values (0, :name, '', 0, 'ENABLED')
                        """)
                .param("name", categoryName)
                .update();
        Long categoryId = jdbcClient.sql("select id from product_category where name = :name")
                .param("name", categoryName)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_spu
                            (category_id, title, subtitle, main_image, selling_points, detail_html,
                             sort_order, status, spec_type, freight_template_id, virtual_sales)
                        values
                            (:categoryId, :title, :subtitle, :mainImage, '', '',
                             0, 'ON_SALE', 'MULTI', 1, 0)
                        """)
                .param("categoryId", categoryId)
                .param("title", title)
                .param("subtitle", subtitle)
                .param("mainImage", mainImage)
                .update();
        Long spuId = jdbcClient.sql("select id from product_spu where title = :title")
                .param("title", title)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, original_price_cent,
                             stock_available, weight_gram, image, status, sort_order, is_default, combination_key)
                        values
                            (:spuId, :firstCode, '{}', '默认', :firstPrice, :firstPrice, 10, 0, '', 'ENABLED', 0, true, 'default'),
                            (:spuId, :secondCode, '{}', '大份', :secondPrice, :secondPrice, 10, 0, '', 'ENABLED', 1, false, 'large')
                        """)
                .param("spuId", spuId)
                .param("firstCode", "HOME-FIRST-" + spuId)
                .param("secondCode", "HOME-SECOND-" + spuId)
                .param("firstPrice", firstPrice)
                .param("secondPrice", secondPrice)
                .update();
        return new Product(spuId, categoryId, categoryName, title, mainImage);
    }

    private void insertCurrentBanner(Asset image) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into home_banner
                            (title, subtitle, image_file_id, image_url, jump_type, jump_target_id, jump_path,
                             status, sort_order, start_at, end_at)
                        values
                            ('聚合轮播', '', :imageFileId, :imageUrl, 'NONE', null, '',
                             'ENABLED', 0, :startAt, :endAt)
                        """)
                .param("imageFileId", image.id())
                .param("imageUrl", image.url())
                .param("startAt", now.minusHours(1))
                .param("endAt", now.plusHours(1))
                .update();
    }

    private int activeUsage(long ownerId, String ownerType, String usageType) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_usage
                        where owner_id = :ownerId
                          and owner_type = :ownerType
                          and usage_type = :usageType
                          and status = 'ACTIVE'
                        """)
                .param("ownerId", ownerId)
                .param("ownerType", ownerType)
                .param("usageType", usageType)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private record Asset(Long id, String url) {
    }

    private record Product(Long spuId, Long categoryId, String categoryName, String title, String mainImage) {
    }
}
