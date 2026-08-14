package org.muybaby.shopserver.payment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class PaymentConfigSoftDeleteMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("payment_config_soft_delete")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @Test
    void migrationAddsDeleteAuditStateAndSuperOnlyPermissionOnProductionMySql() {
        Map<String, String> placeholders = Map.of(
                "seed_super_status", "DISABLED",
                "seed_super_password_hash",
                "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
        );
        Flyway flywayV101 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("101")
                .placeholders(placeholders)
                .load();
        flywayV101.migrate();
        assertThat(flywayV101.info().current().getVersion().getVersion()).isEqualTo("101");

        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ));
        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode,
                             wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status, secret_cipher_version, secret_key_id,
                             secret_revision, secret_reencrypted_at, created_at, updated_at)
                        values
                            (102001, 'Legacy Enabled', 'wx-legacy-enabled', 'mch-legacy-enabled',
                             'serial-legacy-enabled', 'cipher-api-enabled',
                             'cipher-private-enabled', 'cipher-public-enabled',
                             'PUBLIC_KEY', 'public-key-enabled',
                             'https://pay.example.test/wxpay/pay/enabled',
                             'https://pay.example.test/wxpay/refund/enabled',
                             true, 'ACTIVE', 2, 'key-enabled', 7,
                             '2026-08-14 10:01:00', '2026-08-14 10:00:00',
                             '2026-08-14 10:00:30'),
                            (102002, 'Legacy Disabled', 'wx-legacy-disabled', 'mch-legacy-disabled',
                             'serial-legacy-disabled', 'cipher-api-disabled',
                             'cipher-private-disabled', 'cipher-public-disabled',
                             'PUBLIC_KEY', 'public-key-disabled',
                             'https://pay.example.test/wxpay/pay/disabled',
                             'https://pay.example.test/wxpay/refund/disabled',
                             false, 'ACTIVE', 3, 'key-disabled', 11,
                             '2026-08-14 11:01:00', '2026-08-14 11:00:00',
                             '2026-08-14 11:00:30')
                        """).update();
        String legacyRowProjection = """
                select id, config_name, app_id, mch_id, merchant_serial_no,
                       api_v3_key_ciphertext, private_key_file_id,
                       merchant_certificate_file_id, private_key_pem_ciphertext,
                       wechat_public_key_pem_ciphertext, verify_mode,
                       wechat_public_key_id, wechat_public_key_file_id,
                       notify_url, refund_notify_url, enabled, status,
                       secret_cipher_version, secret_key_id, secret_revision,
                       secret_reencrypted_at, created_at, updated_at
                from payment_config
                where id in (102001, 102002)
                order by id
                """;
        List<Map<String, Object>> legacyRowsBefore = jdbcClient.sql(legacyRowProjection)
                .query()
                .listOfRows();
        assertThat(legacyRowsBefore).hasSize(2);

        Flyway flywayV102 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("102")
                .placeholders(placeholders)
                .load();
        flywayV102.migrate();
        assertThat(flywayV102.info().current().getVersion().getVersion()).isEqualTo("102");

        assertThat(jdbcClient.sql(legacyRowProjection).query().listOfRows())
                .isEqualTo(legacyRowsBefore);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_config
                        where id in (102001, 102002)
                          and status = 'ACTIVE'
                          and deleted_at is null
                          and deleted_by is null
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from payment_config
                        where (id = 102001 and enabled = true)
                           or (id = 102002 and enabled = false)
                        """).query(Integer.class).single()).isEqualTo(2);

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.columns
                        where table_schema = database()
                          and table_name = 'payment_config'
                          and column_name in ('deleted_at', 'deleted_by')
                        """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.table_constraints
                        where constraint_schema = database()
                          and table_name = 'payment_config'
                          and constraint_name = 'chk_payment_config_delete_state'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_permission
                        where id = 8004 and auth_mark = 'payment:config:delete'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id = 8004
                          and role_item.code = 'R_SUPER'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from admin_role_permission role_permission
                        join admin_role role_item on role_item.id = role_permission.role_id
                        where role_permission.permission_id = 8004
                          and role_item.code <> 'R_SUPER'
                        """).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from admin_menu_permission
                        where menu_id = 802 and permission_id = 8004
                        """).query(Integer.class).single()).isEqualTo(1);

        jdbcClient.sql("""
                        insert into payment_config
                            (id, config_name, app_id, mch_id, merchant_serial_no,
                             api_v3_key_ciphertext, private_key_pem_ciphertext,
                             wechat_public_key_pem_ciphertext, verify_mode,
                             wechat_public_key_id, notify_url, refund_notify_url,
                             enabled, status)
                        values
                            (102003, 'Migration Delete Test', 'wx-migration', 'mch-migration',
                             'serial-migration', 'cipher-api', 'cipher-private', 'cipher-public',
                             'PUBLIC_KEY', 'public-key-id',
                             'https://pay.example.test/wxpay/pay/notify',
                             'https://pay.example.test/wxpay/refund/notify', false, 'ACTIVE')
                        """).update();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update payment_config set status = 'DELETED' where id = 102003
                        """).update()).isInstanceOf(DataAccessException.class);

        jdbcClient.sql("""
                        update payment_config
                        set status = 'DELETED', enabled = false,
                            deleted_at = current_timestamp, deleted_by = 1
                        where id = 102003
                        """).update();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update payment_config set enabled = true where id = 102003
                        """).update()).isInstanceOf(DataAccessException.class);
    }
}
