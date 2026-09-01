package org.muybaby.shopserver.operation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.support.MigrationTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OperationsAnalyticsSchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void operationsMenusPermissionsAndRoleGrantsExist() {
        Integer menuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_menu
                        where (id = 100 and parent_id is null and path = '/operations' and component = '/index/index')
                           or (id = 101 and parent_id = 100 and path = 'overview' and component = '/operations/overview')
                           or (id = 102 and parent_id = 100 and path = 'trade-statistics' and component = '/operations/trade-statistics')
                           or (id = 103 and parent_id = 100 and path = 'product-statistics' and component = '/operations/product-statistics')
                           or (id = 104 and parent_id = 100 and path = 'user-statistics' and component = '/operations/user-statistics')
                           or (id = 105 and parent_id = 100 and path = 'traffic-statistics' and component = '/operations/traffic-statistics')
                           or (id = 106 and parent_id = 100 and path = 'marketing-statistics' and component = '/operations/marketing-statistics')
                           or (id = 107 and parent_id = 100 and path = 'service-statistics' and component = '/operations/service-statistics')
                        """)
                .query(Integer.class)
                .single();
        Integer permissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where auth_mark in (
                            'operation:overview:read',
                            'operation:trade:read',
                            'operation:product:read',
                            'operation:user:read',
                            'operation:traffic:read',
                            'operation:marketing:read',
                            'operation:service:read'
                        )
                        """)
                .query(Integer.class)
                .single();
        Integer superMenuCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_menu rm
                        join admin_role r on r.id = rm.role_id
                        where r.code = 'R_SUPER'
                          and rm.menu_id between 100 and 107
                        """)
                .query(Integer.class)
                .single();
        Integer superPermissionCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        where r.code = 'R_SUPER'
                          and rp.permission_id between 17001 and 17007
                        """)
                .query(Integer.class)
                .single();
        Integer adminOverviewGrantCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        join admin_permission p on p.id = rp.permission_id
                        where r.code = 'R_ADMIN'
                          and p.auth_mark = 'operation:overview:read'
                        """)
                .query(Integer.class)
                .single();
        Integer adminSensitiveGrantCount = jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission rp
                        join admin_role r on r.id = rp.role_id
                        where r.code = 'R_ADMIN'
                          and rp.permission_id between 17002 and 17007
                        """)
                .query(Integer.class)
                .single();

        assertThat(menuCount).isEqualTo(8);
        assertThat(permissionCount).isEqualTo(7);
        assertThat(superMenuCount).isEqualTo(8);
        assertThat(superPermissionCount).isEqualTo(7);
        assertThat(adminOverviewGrantCount).isEqualTo(1);
        assertThat(adminSensitiveGrantCount).isZero();
    }

    @Test
    void migrationRepairsTheLegacyTrafficMenuIcon() {
        String jdbcUrl = "jdbc:h2:mem:operations_menu_icon_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .target("10")
                .load()
                .migrate();
        JdbcClient legacyJdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));

        assertThat(legacyJdbc.sql("select icon from admin_menu where id = 105")
                .query(String.class)
                .single()).isEqualTo("ri:funnel-line");

        MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", "");

        assertThat(legacyJdbc.sql("select icon from admin_menu where id = 105")
                .query(String.class)
                .single()).isEqualTo("ri:route-line");
        assertThat(legacyJdbc.sql("select icon from admin_menu where id = 104")
                .query(String.class)
                .single()).isEqualTo("ri:user-heart-line");
    }

    @Test
    void statisticsIndexesExist() {
        Integer indexCount = jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where lower(index_name) in (
                            'idx_shop_order_statistics_paid',
                            'idx_refund_order_statistics_success',
                            'idx_payment_order_statistics_created',
                            'idx_order_item_statistics_spu_order',
                            'idx_app_user_statistics_created',
                            'idx_user_coupon_statistics_claimed',
                            'idx_user_coupon_statistics_used',
                            'idx_order_shipment_statistics_shipped',
                            'idx_after_sale_statistics_created',
                            'idx_customer_service_statistics_activated',
                            'idx_product_sku_statistics_stock',
                            'idx_refund_order_status_failed',
                            'idx_shop_order_statistics_created',
                            'idx_user_coupon_status_valid_end',
                            'idx_after_sale_evidence_after_sale_sort'
                        )
                        """)
                .query(Integer.class)
                .single();

        assertThat(indexCount).isEqualTo(15);
    }
}
