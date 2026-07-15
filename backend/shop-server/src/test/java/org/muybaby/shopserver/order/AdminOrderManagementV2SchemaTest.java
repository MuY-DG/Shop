package org.muybaby.shopserver.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AdminOrderManagementV2SchemaTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void v20CreatesOrderStatusLogAndAdminSearchIndexes() {
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_name = 'order_status_log'
                        """)
                .query(Integer.class)
                .single()).isEqualTo(1);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_name = 'order_status_log'
                          and column_name in (
                            'id', 'order_id', 'from_status', 'to_status', 'event_type',
                            'operator_type', 'operator_id', 'description', 'created_at'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(9);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.indexes
                        where index_name in (
                            'idx_order_status_log_order_created',
                            'idx_shop_order_admin_status_created',
                            'idx_shop_order_admin_receiver_phone',
                            'idx_order_shipment_admin_tracking_no'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(4);
    }

    @Test
    void adminStatusTabsUseBusinessFacingGroups() {
        assertThat(AdminOrderStatusGroup.values()).containsExactly(
                AdminOrderStatusGroup.ALL,
                AdminOrderStatusGroup.UNPAID,
                AdminOrderStatusGroup.TO_SHIP,
                AdminOrderStatusGroup.TO_RECEIVE,
                AdminOrderStatusGroup.COMPLETED,
                AdminOrderStatusGroup.CLOSED,
                AdminOrderStatusGroup.REFUNDING,
                AdminOrderStatusGroup.REFUNDED
        );
        assertThat(AdminOrderStatusGroup.UNPAID.statuses()).containsExactly(
                OrderStatus.CREATED.name(),
                OrderStatus.PAYING.name()
        );
    }
}
