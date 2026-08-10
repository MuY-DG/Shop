package org.muybaby.shopserver.support;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class ProductComplianceTestSupport {

    private ProductComplianceTestSupport() {
    }

    public static void markNonFood(JdbcClient jdbcClient, Long spuId) {
        int updated = jdbcClient.sql("""
                        update product_spu
                        set compliance_type = 'NON_FOOD'
                        where id = :spuId
                          and deleted_at is null
                          and purged_at is null
                        """)
                .param("spuId", spuId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Product fixture does not exist: " + spuId);
        }
    }
}
