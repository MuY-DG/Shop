package org.muybaby.shopserver.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class SchemaGenerationBaselineMySqlTest {

    private static final String SUPER_SENTINEL_HASH =
            "$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("schema_generation_two")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @Test
    void generationTwoBaselineAndCurrentUpgradesRunOnProductionMySql() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("8")
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
        jdbc.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html, compliance_type, status)
                        values
                            (9890021, 1, 'Legacy unclassified', '', '', 'UNCLASSIFIED', 'DRAFT'),
                            (9890022, 1, 'Existing food', '', '', 'FOOD', 'DRAFT'),
                            (9890023, 1, 'Existing non-food', '', '', 'NON_FOOD', 'DRAFT')
                        """).update();

        Flyway flyway = MigrationTestSupport.migrateToLatest(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("13");
        assertThat(flyway.info().applied()).hasSize(13);
        assertThat(jdbc.sql("select compliance_type from product_spu order by id")
                .query(String.class).list()).containsExactly("NON_FOOD", "FOOD", "NON_FOOD");
        assertThat(jdbc.sql("select icon from admin_menu where id = 105")
                .query(String.class).single()).isEqualTo("ri:route-line");
        assertThat(jdbc.sql("select path from admin_menu where id = 204")
                .query(String.class).single()).isEqualTo("/audit-log");
        assertThat(jdbc.sql("select count(*) from admin_menu where parent_id = 204")
                .query(Long.class).single()).isEqualTo(5L);
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'admin_system_log'
                          and column_name in (
                              'event_code', 'summary', 'target_type', 'target_id',
                              'related_target_type', 'related_target_id', 'provider_error_code'
                          )
                        """).query(Long.class).single()).isEqualTo(7L);
        jdbc.sql("""
                        insert into product_spu (id, category_id, title, selling_points, detail_html, status)
                        values (9890024, 1, 'New default', '', '', 'DRAFT')
                        """).update();
        assertThat(jdbc.sql("select compliance_type from product_spu where id = 9890024")
                .query(String.class).single()).isEqualTo("NON_FOOD");
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'after_sale_request'
                          and column_name = 'app_deleted_at'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.sql("""
                        update product_spu set compliance_type = 'UNCLASSIFIED' where id = 9890024
                        """).update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_product_spu_compliance_type");
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                        """).query(Long.class).single()).isEqualTo(132);
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name = 'refund_provider_attempt'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = database()
                          and table_name in ('payment_config_snapshot', 'payment_runtime_setting')
                        """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'payment_config'
                          and column_name in (
                              'private_key_file_id', 'merchant_certificate_file_id',
                              'wechat_public_key_file_id'
                          )
                """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'payment_callback_log'
                          and column_name = 'route_mode'
                        """).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and is_nullable = 'NO'
                          and (
                            (table_name = 'payment_callback_log' and column_name = 'route_digest')
                            or (table_name = 'payment_order' and column_name in (
                                'payment_config_id', 'payment_config_fingerprint',
                                'notification_route_token'))
                            or (table_name = 'refund_order' and column_name = 'notification_route_token')
                            or (table_name in ('purged_payment_identity', 'purged_refund_identity')
                                and column_name in (
                                    'notification_route_digest', 'payment_config_id',
                                    'payment_config_fingerprint'))
                          )
                        """).query(Long.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and is_nullable = 'NO'
                          and column_default is null
                          and (
                            (table_name = 'product_sku' and column_name = 'combination_key')
                            or (table_name = 'shop_order' and column_name = 'checkout_request_digest')
                          )
                        """).query(Long.class).single()).isEqualTo(2);

        assertThat(jdbc.sql("""
                        select count(*)
                        from admin_user
                        where id = 1 and username = 'Super' and status = 'DISABLED'
                          and password_hash = :sentinel
                          and max_sessions = 0 and auth_version = 1
                          and username_normalized = 'super'
                        """)
                .param("sentinel", SUPER_SENTINEL_HASH)
                .query(Long.class)
                .single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select marker_value
                        from system_health_marker
                        where id = 1 and marker_key = 'schema'
                        """).query(String.class).single())
                .isEqualTo("generation-2");
        assertThat(jdbc.sql("""
                        select count(*)
                        from wechat_shipping_runtime_setting
                        where id = 1 and upload_enabled = false and delivery_enabled = false
                          and receipt_reconciliation_enabled = false and revision = 1
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*)
                        from wechat_service_card_runtime_setting
                        where id = 1 and capture_enabled = false and worker_enabled = false
                          and revision = 1
                        """).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        select count(*)
                        from finance_reconciliation_runtime_setting
                        where id = 1 and worker_enabled = false and daily_enabled = false
                          and revision = 1
                        """).query(Long.class).single()).isEqualTo(1);
    }
}
