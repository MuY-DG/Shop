package org.muybaby.shopserver.wechat.servicecard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class WechatServiceCardMySqlMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("wechat_service_card_migration")
            .withUsername("shop_test")
            .withPassword("shop_test")
            .withEnv("TZ", "UTC")
            .withUrlParam("serverTimezone", "UTC");

    @Test
    void serviceCardMigrationsApplyOnProductionMySqlAndCleanupFailedSuffixes() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .target("94")
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
                ))
                .load()
                .migrate();
        JdbcClient jdbcClient = JdbcClient.create(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ));

        assertThat(jdbcClient.sql("""
                        select count(*)
                        from information_schema.table_constraints
                        where constraint_schema = database()
                          and constraint_name in (
                            'chk_wechat_service_card_notify_type',
                            'chk_wechat_service_card_statuses',
                            'fk_wechat_service_card_order',
                            'fk_wechat_service_card_payment',
                            'chk_wechat_service_card_delivery_sequence',
                            'chk_wechat_service_card_delivery_attempts',
                            'chk_wechat_service_card_delivery_state',
                            'chk_wechat_service_card_delivery_claim',
                            'fk_wechat_service_card_delivery_card',
                            'chk_wechat_service_card_callback_status',
                            'fk_wechat_service_card_callback_card',
                            'fk_wechat_service_card_callback_delivery_card'
                          )
                        """)
                .query(Integer.class)
                .single()).isEqualTo(12);

        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at,
                             created_at, updated_at)
                        values
                            (9490001, 'MYSQL-SERVICE-CARD', 1, 'PAID', 'DIRECT',
                             'mysql-service-card', 100, 100, current_timestamp,
                             current_timestamp, current_timestamp)
                        """).update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (9490002, 9490001, 'MYSQL-SERVICE-CARD-OUT',
                             '4200000000009490001', 'mysql-openid', 'PAID', 100,
                             date_add(current_timestamp, interval 15 minute),
                             current_timestamp, current_timestamp, current_timestamp)
                        """).update();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (id, order_id, payment_order_id, notify_code_digest,
                             restore_status, remote_status, remote_code_state)
                        values
                            (9490003, 9490001, 9490002, :digest, 2, 2, 0)
                        """)
                .param("digest", "a".repeat(64))
                .update();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (card_id, sequence_no, target_status, content_json,
                             state, claim_token, claimed_at)
                        values
                            (9490003, 1, 2, '{}', 'SENDING', null, null)
                        """).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        update wechat_service_card
                        set restore_status = 3 where id = 9490003
                        """).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (card_id, sequence_no, target_status, content_json, state)
                        values
                            (9499999, 1, 2, '{}', 'PENDING')
                        """).update())
                .isInstanceOf(DataAccessException.class);

        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json, check_json,
                             state, provider_error_code)
                        values
                            (9490004, 9490003, 1, 2, '{}', '{}',
                             'FAILED', 'ACTIVATION_WINDOW_EXPIRED'),
                            (9490005, 9490003, 2, 4, '{}', null, 'PENDING', '')
                        """).update();

        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .placeholders(Map.of(
                        "seed_super_status", "DISABLED",
                        "seed_super_password_hash",
                        "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
                ))
                .load()
                .migrate();

        assertThat(jdbcClient.sql("""
                        select state
                        from wechat_service_card_delivery
                        where id = 9490005
                          and provider_error_code = 'PREDECESSOR_FAILED'
                          and next_action_at is null
                        """)
                .query(String.class)
                .single()).isEqualTo("SKIPPED");
    }
}
