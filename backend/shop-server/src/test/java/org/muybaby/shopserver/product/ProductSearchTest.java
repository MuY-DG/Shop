package org.muybaby.shopserver.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.content.dto.AdminHomeProductOptionQuery;
import org.muybaby.shopserver.content.service.HomeDecorationService;
import org.muybaby.shopserver.customerservice.service.CustomerServiceService;
import org.muybaby.shopserver.product.dto.*;
import org.muybaby.shopserver.product.service.AppProductService;
import org.muybaby.shopserver.product.service.ProductParameterService;
import org.muybaby.shopserver.product.service.ProductReadMapper;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProductSearchTest {
    private static final long CATEGORY = 9_300_000L;
    private static final long SPU = 9_300_001L;
    private static final long SKU = 9_300_100L;

    @Autowired JdbcClient jdbc;
    @Autowired AppProductService app;
    @Autowired ProductReadMapper admin;
    @Autowired ProductParameterService parameters;
    @Autowired HomeDecorationService home;
    @Autowired CustomerServiceService customerService;
    @Autowired MockMvc mvc;

    @BeforeEach
    void catalog() {
        jdbc.sql("INSERT INTO product_category (id, parent_id, name, status) VALUES (:id, 0, '搜索回归分类', 'ENABLED')")
                .param("id", CATEGORY).update();
        spu(SPU, "火锅底料", "家庭装 SearchSubtitle", "ON_SALE");
        sku(SKU, SPU, "牛油微辣 500g", "MATCH-BEEF-500", "ENABLED", 0, 3000);
        sku(SKU + 1, SPU, "牛油微辣 200g", "MATCH-BEEF-200", "ENABLED", 5, 1200);
        sku(SKU + 2, SPU, "番茄 1000g", "MATCH-TOMATO", "ENABLED", 9, 5000);
        sku(SKU + 3, SPU, "停用口味", "MATCH-DISABLED", "DISABLED", 4, 8000);
        sku(SKU + 4, SPU, "删除口味", "MATCH-DELETED", "ENABLED", 8, 9999);
        jdbc.sql("UPDATE product_sku SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", SKU + 4).update();
    }

    @Test
    void skuMatchesPreserveSpuAggregationAndPublicResponse() throws Exception {
        var page = appPage("牛油");
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).hasSize(1);
        var record = page.records().getFirst();
        assertThat(record.minPriceCent()).isEqualTo(1200);
        assertThat(record.maxPriceCent()).isEqualTo(5000);
        assertThat(record.saleState()).isEqualTo(ProductSaleState.AVAILABLE);
        assertThat(record.searchMatches()).extracting(ProductSearchMatchResponse::skuId)
                .containsExactly(SKU + 1, SKU);
        assertThat(record.searchMatches().getLast().saleState()).isEqualTo(ProductSaleState.SOLD_OUT);
        var adminRecord = adminPage("牛油").records().getFirst();
        assertThat(adminRecord.totalStock()).isEqualTo(18);
        assertThat(adminRecord.skuCount()).isEqualTo(4);
        assertThat(adminRecord.maxPriceCent()).isEqualTo(8000);
        mvc.perform(get("/app/product/spus").param("categoryId", Long.toString(CATEGORY)).param("keyword", "牛油 500g"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].searchMatches[0].specText").value("牛油微辣 500g"))
                .andExpect(jsonPath("$.data.records[0].searchMatches[0].saleState").value("SOLD_OUT"))
                .andExpect(jsonPath("$.data.records[0].searchMatches[0].stockAvailable").doesNotExist());
    }

    @Test
    void wordsCanSpanTitleAndOneSkuButNeverDifferentSkus() {
        assertThat(appPage("  火锅\t牛油　500G ").total()).isEqualTo(1);
        assertThat(appPage("牛油 1000g").total()).isZero();
        assertThat(adminPage("牛油 1000g").total()).isZero();
        assertThat(appPage("searchsubtitle").total()).isEqualTo(1);
        assertThat(appPage("searchsubtitle").records().getFirst().searchMatches()).isEmpty();
        assertThat(appPage(" ").records().getFirst().searchMatches()).isEmpty();
    }

    @Test
    void statusDeletionAndIdentifiersRespectAudience() {
        assertThat(appPage("停用口味").total()).isZero();
        assertThat(adminPage("停用口味").records().getFirst().searchMatches().getFirst().status()).isEqualTo("DISABLED");
        assertThat(appPage("删除口味").total()).isZero();
        assertThat(adminPage("删除口味").total()).isZero();
        assertThat(adminPage("match-beef-500").total()).isEqualTo(1);
        assertThat(appPage("match-beef-500").total()).isZero();
        assertThat(adminPage(Long.toString(SPU)).total()).isEqualTo(1);
        assertThat(adminPage(Long.toString(SPU + 50)).total()).isZero();
        assertThat(appPage(Long.toString(SPU)).total()).isZero();
        assertThat(adminPage("9999999999999999999999999999").total()).isZero();
    }

    @Test
    void literalWildcardsAndQuotesDoNotBroadenTheSearch() {
        sku(SKU + 5, SPU, "限定 100%_! O'Reilly", "MATCH-LITERAL", "ENABLED", 1, 1000);
        assertThat(appPage("%_!").total()).isEqualTo(1);
        assertThat(appPage("O'Reilly").total()).isEqualTo(1);
        assertThat(appPage("%_!").records().getFirst().searchMatches())
                .extracting(ProductSearchMatchResponse::skuId).containsExactly(SKU + 5);
        assertThat(appPage("' OR 1=1 --").total()).isZero();
    }

    @Test
    void multipleMatchingSkusDoNotDuplicatePagesAndPriceSortUsesAllSkus() {
        spu(SPU + 1, "火锅底料二号", "", "ON_SALE");
        sku(SKU + 10, SPU + 1, "牛油 500g", "MATCH-SECOND", "ENABLED", 1, 2000);
        var first = app.page(new ProductPageRequest(CATEGORY, "牛油 500g", 1L, 1L, "PRICE_ASC"));
        var second = app.page(new ProductPageRequest(CATEGORY, "牛油 500g", 2L, 1L, "PRICE_ASC"));
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.records()).extracting(AppSpuListItemResponse::id).containsExactly(SPU);
        assertThat(second.records()).extracting(AppSpuListItemResponse::id).containsExactly(SPU + 1);
        var adminFirst = admin.adminSpuPage(new AdminSpuQueryRequest(CATEGORY, "牛油", null, false, 1L, 1L));
        var adminSecond = admin.adminSpuPage(new AdminSpuQueryRequest(CATEGORY, "牛油", null, false, 2L, 1L));
        assertThat(adminFirst.total()).isEqualTo(2);
        assertThat(adminFirst.records()).hasSize(1);
        assertThat(adminSecond.records()).hasSize(1);
        assertThat(adminFirst.records().getFirst().id()).isNotEqualTo(adminSecond.records().getFirst().id());
    }

    @Test
    void publicVisibilityAndAdminRecycleFiltersStillApply() {
        jdbc.sql("UPDATE product_spu SET status = 'OFF_SALE' WHERE id = :id").param("id", SPU).update();
        assertThat(appPage("牛油").total()).isZero();
        assertThat(adminPage("牛油").total()).isEqualTo(1);
        assertThat(admin.adminSpuPage(new AdminSpuQueryRequest(CATEGORY, "牛油", "ON_SALE", false, 1L, 10L)).total()).isZero();
        jdbc.sql("UPDATE product_spu SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id").param("id", SPU).update();
        assertThat(adminPage("牛油").total()).isZero();
        assertThat(admin.adminSpuPage(new AdminSpuQueryRequest(CATEGORY, "牛油", null, true, 1L, 10L)).total()).isEqualTo(1);
        jdbc.sql("UPDATE product_spu SET deleted_at = NULL, status = 'ON_SALE' WHERE id = :id").param("id", SPU).update();
        jdbc.sql("UPDATE product_category SET status = 'DISABLED' WHERE id = :id").param("id", CATEGORY).update();
        assertThat(appPage("牛油").total()).isZero();
    }

    @Test
    void facetsAndParameterFiltersUseTheSameSpuSearch() {
        jdbc.sql("""
                INSERT INTO product_parameter_definition
                    (id, parameter_code, parameter_name, value_type, filterable, status)
                VALUES (9300500, 'SEARCH_STYLE', '用途', 'SINGLE_SELECT', TRUE, 'ENABLED')
                """).update();
        jdbc.sql("""
                INSERT INTO product_parameter_option (id, parameter_id, option_code, option_label)
                VALUES (9300501, 9300500, 'HOT_POT', '火锅'), (9300502, 9300500, 'OTHER', '其他')
                """).update();
        jdbc.sql("INSERT INTO product_category_parameter (category_id, parameter_id) VALUES (:id, 9300500)")
                .param("id", CATEGORY).update();
        jdbc.sql("""
                INSERT INTO product_spu_parameter_value (spu_id, parameter_id, option_codes_json)
                VALUES (:id, 9300500, '["HOT_POT"]')
                """).param("id", SPU).update();
        var facets = parameters.filterFacets(CATEGORY, "牛油");
        assertThat(facets).hasSize(1);
        assertThat(facets.getFirst().options().getFirst().productCount()).isEqualTo(1);
        assertThat(app.page(new ProductPageRequest(CATEGORY, "牛油", 1L, 10L, null, "SEARCH_STYLE:HOT_POT")).total()).isEqualTo(1);
        assertThat(app.page(new ProductPageRequest(CATEGORY, "牛油", 1L, 10L, null, "SEARCH_STYLE:OTHER")).total()).isZero();
        assertThat(parameters.filterFacets(CATEGORY, "牛油 1000g").getFirst().options().getFirst().productCount()).isZero();
    }

    @Test
    void decorationAndCustomerServiceSelectionShareSearchRules() {
        assertThat(home.productOptions(new AdminHomeProductOptionQuery("match-beef-500", 1L, 10L)).records())
                .extracting("id").containsExactly(SPU);
        assertThat(home.productOptions(new AdminHomeProductOptionQuery("MATCH-DISABLED", 1L, 10L)).total()).isZero();
        assertThat(home.productOptions(new AdminHomeProductOptionQuery("牛油 1000g", 1L, 10L)).total()).isZero();
        var principal = new AuthenticatedPrincipal(TokenKind.APP, 9300900L, "search-test", List.of(), List.of());
        assertThat(customerService.productCandidatesForApp(principal, "牛油 500g"))
                .extracting("productId").containsExactly(SPU);
        assertThat(customerService.productCandidatesForApp(principal, "牛油 1000g")).isEmpty();
        assertThat(customerService.productCandidatesForApp(principal, "MATCH-BEEF-500")).isEmpty();
    }

    private PageResult<AppSpuListItemResponse> appPage(String keyword) {
        return app.page(new ProductPageRequest(CATEGORY, keyword, 1L, 10L));
    }

    private PageResult<AdminSpuListItemResponse> adminPage(String keyword) {
        return admin.adminSpuPage(new AdminSpuQueryRequest(CATEGORY, keyword, null, false, 1L, 10L));
    }

    private void spu(long id, String title, String subtitle, String status) {
        jdbc.sql("""
                INSERT INTO product_spu (id, category_id, title, subtitle, selling_points, detail_html, status)
                VALUES (:id, :category, :title, :subtitle, '', '', :status)
                """).param("id", id).param("category", CATEGORY).param("title", title)
                .param("subtitle", subtitle).param("status", status).update();
    }

    private void sku(long id, long spuId, String text, String code, String status, int stock, long price) {
        jdbc.sql("""
                INSERT INTO product_sku (id, spu_id, sku_code, spec_json, spec_text, price_cent,
                    stock_available, status, combination_key)
                VALUES (:id, :spuId, :code, '{}', :text, :price, :stock, :status, :code)
                """).param("id", id).param("spuId", spuId).param("code", code).param("text", text)
                .param("price", price).param("stock", stock).param("status", status).update();
    }
}
