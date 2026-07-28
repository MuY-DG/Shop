package org.muybaby.shopserver.product;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.product.dto.ProductPageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ProductPageRequestTest {

    @Test
    void onlyAllowsKnownProductSortClauses() {
        assertThat(new ProductPageRequest(null, null, 1L, 10L, "SALES_DESC").orderByClause())
                .startsWith("display_sales DESC");
        assertThat(new ProductPageRequest(null, null, 1L, 10L, "price_asc").orderByClause())
                .contains("min(k.price_cent) ASC");
        assertThat(new ProductPageRequest(null, null, 1L, 10L, "PRICE_DESC").orderByClause())
                .contains("min(k.price_cent) DESC");
        assertThat(new ProductPageRequest(null, null, 1L, 10L, "DROP TABLE product_spu").orderByClause())
                .isEqualTo("s.sort_order ASC, s.id DESC");
    }

    @Test
    void onlyKeepsWellFormedParameterFilters() {
        ProductPageRequest request = new ProductPageRequest(
                null,
                null,
                1L,
                10L,
                null,
                "spice:medium,bad code:value,WEIGHT:,MATERIAL:STEEL,SPICE:HOT,SQL:' OR 1=1"
        );

        assertThat(request.normalizedParameterFilters())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "SPICE", "HOT",
                        "MATERIAL", "STEEL"
                ));
    }
}
