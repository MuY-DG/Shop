package org.muybaby.shopserver.order.cleanup;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OrderAggregateCleanupMySqlMigrationTest {

    private static final long ORDER_ID = 9_900_801L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("order_cleanup_migration")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @Test
    void v80BackfillsOnlyValidatedStockLogOrderIds() {
        migrateTo("79");
        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        seedOrderAndLegacyStockLogs(jdbcClient);

        migrateTo("80");

        Map<Long, Long> orderIdsByLogId = new LinkedHashMap<>();
        jdbcClient.sql("""
                        select id, order_id
                        from stock_log
                        where id between 9900811 and 9900816
                        order by id
                        """)
                .query((rs, rowNum) -> Map.entry(
                        rs.getLong("id"), java.util.Optional.ofNullable(
                                rs.getObject("order_id", Long.class))))
                .list()
                .forEach(entry -> orderIdsByLogId.put(entry.getKey(), entry.getValue().orElse(null)));

        assertThat(orderIdsByLogId.get(9_900_811L)).isEqualTo(ORDER_ID);
        assertThat(orderIdsByLogId.get(9_900_812L)).isEqualTo(ORDER_ID);
        assertThat(orderIdsByLogId.get(9_900_813L)).isNull();
        assertThat(orderIdsByLogId.get(9_900_814L)).isNull();
        assertThat(orderIdsByLogId.get(9_900_815L)).isNull();
        assertThat(orderIdsByLogId.get(9_900_816L)).isNull();
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target(version)
                .placeholders(seedPlaceholders())
                .load()
                .migrate();
    }

    private Map<String, String> seedPlaceholders() {
        return Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash",
                "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
    }

    private void seedOrderAndLegacyStockLogs(JdbcClient jdbcClient) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent,
                             receiver_name, receiver_phone, receiver_address, created_at, updated_at)
                        values
                            (:orderId, 'ORDER-CLEANUP-MIGRATION', 1, 'CLOSED', 'CART',
                             'order-cleanup-migration', 100, 100, 0, 0, 100, 0,
                             'Migration User', '13800000000', 'Migration Address',
                             current_timestamp, current_timestamp)
                        """)
                .param("orderId", ORDER_ID)
                .update();

        insertStockLog(jdbcClient, 9_900_811L, "ORDER_LOCK", "Order submit " + ORDER_ID);
        insertStockLog(jdbcClient, 9_900_812L, "ORDER_RELEASE", "Timeout close order " + ORDER_ID);
        insertStockLog(jdbcClient, 9_900_813L, "ORDER_LOCK", "Order submit 9999999999999999999");
        insertStockLog(jdbcClient, 9_900_814L, "ORDER_RELEASE", "Timeout close order " + ORDER_ID + " trailing");
        insertStockLog(jdbcClient, 9_900_815L, "ORDER_LOCK", "Order submit 9900802");
        insertStockLog(jdbcClient, 9_900_816L, "MANUAL_ADJUST", "Order submit " + ORDER_ID);
    }

    private void insertStockLog(JdbcClient jdbcClient, long id, String changeType, String reason) {
        jdbcClient.sql("""
                        insert into stock_log
                            (id, sku_id, change_type, quantity_before, quantity_delta,
                             quantity_after, reason, operator_type, operator_id)
                        values
                            (:id, 1, :changeType, 10, -1, 9, :reason, 'SYSTEM', 0)
                        """)
                .param("id", id)
                .param("changeType", changeType)
                .param("reason", reason)
                .update();
    }
}
