package org.muybaby.shopserver.product.engagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminProductReviewSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void adminReviewMenuPermissionsAndModerationColumnsAreSeeded() {
        assertThat(jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'product_review'
                          AND column_name IN ('moderated_at', 'moderated_by_admin_user_id')
                        ORDER BY column_name
                        """)
                .query(String.class)
                .list()).containsExactly("moderated_at", "moderated_by_admin_user_id");

        assertThat(jdbcClient.sql("""
                        SELECT CONCAT(name, '|', path, '|', component, '|', title)
                        FROM admin_menu
                        WHERE id = 306 AND parent_id = 300
                        """)
                .query(String.class)
                .single()).isEqualTo("ProductReview|review|/product/review|商品评论");

        assertThat(jdbcClient.sql("""
                        SELECT auth_mark
                        FROM admin_permission
                        WHERE auth_mark IN ('product:review:read', 'product:review:moderate')
                        ORDER BY auth_mark
                        """)
                .query(String.class)
                .list()).containsExactly("product:review:moderate", "product:review:read");

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM admin_menu_permission amp
                        JOIN admin_permission p ON p.id = amp.permission_id
                        WHERE amp.menu_id = 306
                          AND p.auth_mark IN ('product:review:read', 'product:review:moderate')
                        """)
                .query(Long.class)
                .single()).isEqualTo(2);
    }
}
