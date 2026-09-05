package org.muybaby.shopserver.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaGenerationBaselineTest {

    private static final String SUPER_SENTINEL_HASH =
            "$2a$10$dSCU.t56l8Z7MPya89bXnuiMIjScayWL.KeTgc92TqlfLu.woUoYm";

    @Test
    void cleanH2DatabaseBuildsTheGenerationTwoContract() {
        String jdbcUrl = "jdbc:h2:mem:baseline_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", "");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));

        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("21");
        assertThat(flyway.info().applied()).hasSize(21);
        assertThat(tableCount(jdbc)).isEqualTo(125);

        assertThat(tableExists(jdbc, "payment_config_snapshot")).isFalse();
        assertThat(tableExists(jdbc, "payment_runtime_setting")).isFalse();
        assertThat(columnExists(jdbc, "payment_config", "private_key_file_id")).isFalse();
        assertThat(columnExists(jdbc, "payment_config", "merchant_certificate_file_id")).isFalse();
        assertThat(columnExists(jdbc, "payment_config", "wechat_public_key_file_id")).isFalse();
        assertThat(columnExists(jdbc, "wechat_platform_config", "imported_from_env_at")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_config")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_config_audit")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_runtime_setting")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_runtime_audit")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_delivery")).isFalse();
        assertThat(tableExists(jdbc, "wechat_service_card_callback_log")).isFalse();
        assertThat(columnExists(jdbc, "payment_callback_log", "route_mode")).isFalse();
        assertThat(columnExists(jdbc, "after_sale_request", "app_deleted_at")).isTrue();
        assertThat(columnExists(jdbc, "order_item", "category_id_snapshot")).isTrue();
        assertThat(columnExists(jdbc, "order_item", "category_name_snapshot")).isTrue();
        assertThat(tableExists(jdbc, "refund_provider_attempt")).isTrue();
        assertThat(columnExists(jdbc, "admin_system_log", "related_target_type")).isTrue();
        assertThat(columnExists(jdbc, "admin_system_log", "related_target_id")).isTrue();
        assertThat(columnExists(jdbc, "admin_system_log", "provider_error_code")).isTrue();
        assertNonNullable(jdbc, "payment_callback_log", "route_digest");
        assertNonNullable(jdbc, "payment_order", "payment_config_id");
        assertNonNullable(jdbc, "payment_order", "payment_config_fingerprint");
        assertNonNullable(jdbc, "payment_order", "notification_route_token");
        assertNonNullable(jdbc, "refund_order", "notification_route_token");
        assertNonNullable(jdbc, "purged_payment_identity", "notification_route_digest");
        assertNonNullable(jdbc, "purged_payment_identity", "payment_config_id");
        assertNonNullable(jdbc, "purged_refund_identity", "notification_route_digest");
        assertNonNullable(jdbc, "purged_refund_identity", "payment_config_id");
        assertNonNullable(jdbc, "product_sku", "combination_key");
        assertNonNullable(jdbc, "shop_order", "checkout_request_digest");
        assertNoDefault(jdbc, "product_sku", "combination_key");
        assertNoDefault(jdbc, "shop_order", "checkout_request_digest");
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into product_sku
                            (spu_id, sku_code, spec_json, spec_text, price_cent, status, combination_key)
                        values (1, 'BASELINE-EMPTY-COMBINATION', '{}', '', 1, 'ENABLED', '')
                        """).update())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into shop_order
                            (order_no, user_id, status, source, idempotency_key, checkout_request_digest)
                        values ('BASELINE-BAD-DIGEST', 1, 'CREATED', 'CART', 'baseline-bad-digest', 'short')
                        """).update())
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> superAdmin = jdbc.sql("""
                        select username, password_hash, status, max_sessions, auth_version,
                               username_normalized
                        from admin_user
                        where id = 1
                        """)
                .query()
                .singleRow();
        assertThat(superAdmin)
                .containsEntry("username", "Super")
                .containsEntry("password_hash", SUPER_SENTINEL_HASH)
                .containsEntry("status", "DISABLED")
                .containsEntry("max_sessions", 0)
                .containsEntry("auth_version", 1L)
                .containsEntry("username_normalized", "super");
        assertThat(jdbc.sql("""
                        select marker_value
                        from system_health_marker
                        where id = 1 and marker_key = 'schema'
                        """).query(String.class).single())
                .isEqualTo("generation-2");

        assertReferenceData(jdbc);
        assertFailClosedRuntimeDefaults(jdbc);
        assertV2OnlySecretConstraints(jdbc);
        InventoryAmountConstraintTestSupport.insertValidLegacyRows(jdbc);
        InventoryAmountConstraintTestSupport.assertLegacyValuesAndWriteConstraints(jdbc);
    }

    @Test
    void v21LeavesHistoricalCategorySnapshotsUnknownEvenWhenCurrentCategoryExists() {
        String jdbcUrl = "jdbc:h2:mem:v21_category_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(jdbcUrl, "sa", "").target("20").load().migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        jdbc.sql("""
                        insert into product_category (id, parent_id, name, status)
                        values (9890210, 0, 'Current product category', 'ENABLED')
                        """).update();
        jdbc.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html, status)
                        values (9890021, 9890210, 'Legacy product', '', '', 'DRAFT')
                        """).update();
        InventoryAmountConstraintTestSupport.insertValidLegacyRows(jdbc);

        MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", "");

        assertThat(jdbc.sql("""
                        select count(*) from order_item item
                        join product_spu product on product.id = item.spu_id
                        join product_category category on category.id = product.category_id
                        where item.id = 9890203 and category.name = 'Current product category'
                          and item.category_id_snapshot is null and item.category_name_snapshot is null
                        """).query(Long.class).single()).isOne();
    }

    @Test
    void v20RefusesNegativeLegacyInventoryWithoutRewritingIt() {
        String jdbcUrl = legacyConstraintDatabase("negative_inventory");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        InventoryAmountConstraintTestSupport.insertValidLegacyRows(jdbc);
        jdbc.sql("update product_sku set stock_available = -1 where id = 9890201").update();

        assertThatThrownBy(() -> MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", ""))
                .hasStackTraceContaining("chk_product_sku_stock_nonnegative");
        assertThat(jdbc.sql("select stock_available from product_sku where id = 9890201")
                .query(Integer.class).single()).isEqualTo(-1);
    }

    @Test
    void v20RefusesDuplicateLegacyStockLocksWithoutDeletingEitherRow() {
        String jdbcUrl = legacyConstraintDatabase("duplicate_stock_lock");
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(jdbcUrl, "sa", ""));
        InventoryAmountConstraintTestSupport.insertValidLegacyRows(jdbc);
        jdbc.sql("""
                        insert into stock_lock (order_id, order_item_id, sku_id, quantity, status)
                        values (9890202, 9890203, 9890201, 1, 'LOCKED')
                        """).update();

        assertThatThrownBy(() -> MigrationTestSupport.migrateToLatest(jdbcUrl, "sa", ""))
                .hasStackTraceContaining("uk_stock_lock_order_item");
        assertThat(jdbc.sql("select count(*) from stock_lock where order_item_id = 9890203")
                .query(Long.class).single()).isEqualTo(2L);
    }

    private String legacyConstraintDatabase(String scenario) {
        String jdbcUrl = "jdbc:h2:mem:v20_" + scenario + "_"
                + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(jdbcUrl, "sa", "").target("19").load().migrate();
        return jdbcUrl;
    }

    private long tableCount(JdbcClient jdbc) {
        return jdbc.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_type = 'BASE TABLE'
                          and table_name <> 'flyway_schema_history'
                        """)
                .query(Long.class)
                .single();
    }

    private boolean tableExists(JdbcClient jdbc, String tableName) {
        return jdbc.sql("""
                        select count(*)
                        from information_schema.tables
                        where table_schema = 'public' and table_name = :tableName
                        """)
                .param("tableName", tableName)
                .query(Long.class)
                .single() == 1;
    }

    private boolean columnExists(JdbcClient jdbc, String tableName, String columnName) {
        return jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = :tableName
                          and column_name = :columnName
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(Long.class)
                .single() == 1;
    }

    private void assertNonNullable(JdbcClient jdbc, String tableName, String columnName) {
        assertThat(jdbc.sql("""
                        select is_nullable
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = :tableName
                          and column_name = :columnName
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(String.class)
                .single()).isEqualToIgnoringCase("NO");
    }

    private void assertNoDefault(JdbcClient jdbc, String tableName, String columnName) {
        assertThat(jdbc.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = :tableName
                          and column_name = :columnName
                          and column_default is null
                        """)
                .param("tableName", tableName)
                .param("columnName", columnName)
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    private void assertReferenceData(JdbcClient jdbc) {
        assertThat(jdbc.sql("select count(*) from admin_role").query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("select count(*) from admin_permission").query(Long.class).single()).isEqualTo(123);
        assertThat(jdbc.sql("select count(*) from admin_menu").query(Long.class).single()).isEqualTo(61);
        assertThat(jdbc.sql("select count(*) from admin_permission where auth_mark like 'wechat-service-card:%'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from admin_menu where id = 806")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select icon from admin_menu where id = 105")
                .query(String.class).single()).isEqualTo("ri:route-line");
        assertThat(jdbc.sql("select count(*) from admin_role_permission where role_id = 1")
                .query(Long.class).single()).isEqualTo(123);
        assertThat(jdbc.sql("select count(*) from admin_user_role where user_id = 1 and role_id = 1")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select checkpoint_name from payment_secret_rotation_checkpoint order by checkpoint_name")
                .query(String.class).list())
                .containsExactly("payment-config", "storage-runtime-setting");
    }

    private void assertFailClosedRuntimeDefaults(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
                        select upload_enabled, delivery_enabled, receipt_reconciliation_enabled,
                               revision, change_reason
                        from wechat_shipping_runtime_setting where id = 1
                        """).query().singleRow())
                .containsEntry("upload_enabled", false)
                .containsEntry("delivery_enabled", false)
                .containsEntry("receipt_reconciliation_enabled", false)
                .containsEntry("revision", 1L)
                .containsEntry("change_reason", "INITIAL_FAIL_CLOSED");
        assertThat(jdbc.sql("""
                        select worker_enabled, daily_enabled, revision, change_reason
                        from finance_reconciliation_runtime_setting where id = 1
                        """).query().singleRow())
                .containsEntry("worker_enabled", false)
                .containsEntry("daily_enabled", false)
                .containsEntry("revision", 1L)
                .containsEntry("change_reason", "INITIAL_FAIL_CLOSED");
    }

    private void assertV2OnlySecretConstraints(JdbcClient jdbc) {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into payment_config
                            (config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode,
                             wechat_public_key_id, notify_url, refund_notify_url,
                             secret_cipher_version, secret_key_id)
                        values
                            ('invalid-v1', 'app', 'mch', 'serial', 'v1:cipher', 'v1:key',
                             'v1:public', 'PUBLIC_KEY', 'public-id',
                             'https://example.test/pay', 'https://example.test/refund', 1, '')
                        """).update())
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into wechat_platform_config
                            (id, app_id, app_secret_ciphertext, secret_cipher_version,
                             secret_key_id, secret_revision, revision)
                        values (1, 'app', 'v1:cipher', 1, '', 1, 1)
                        """).update())
                .isInstanceOf(RuntimeException.class);
    }
}
