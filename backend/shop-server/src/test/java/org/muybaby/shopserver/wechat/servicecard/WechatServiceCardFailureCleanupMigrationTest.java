package org.muybaby.shopserver.wechat.servicecard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WechatServiceCardFailureCleanupMigrationTest {

    private static final Map<String, String> PLACEHOLDERS = Map.of(
            "seed_super_status", "DISABLED",
            "seed_super_password_hash",
            "$2y$10$VtYIL778Ftr75pHOJ3dV0efoMsPK20vZncmZ/vB6tkYj3aW9fqT.i"
    );

    @Test
    void v96SkipsActiveSuffixesAfterAnEarlierTerminalFailure() {
        String databaseName = "service_card_cleanup_"
                + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(PLACEHOLDERS)
                .target("95")
                .load()
                .migrate();
        JdbcClient jdbcClient = JdbcClient.create(
                new DriverManagerDataSource(jdbcUrl, "sa", "")
        );
        seedCard(jdbcClient, 9_496_001L, true);
        seedCard(jdbcClient, 9_496_101L, false);

        Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .placeholders(PLACEHOLDERS)
                .load()
                .migrate();

        assertThat(states(jdbcClient, 9_496_003L))
                .containsExactly("FAILED", "SKIPPED", "SKIPPED");
        assertThat(errorCodes(jdbcClient, 9_496_003L))
                .containsExactly(
                        "ACTIVATION_WINDOW_EXPIRED",
                        "PREDECESSOR_FAILED",
                        "PREDECESSOR_FAILED"
                );
        assertThat(states(jdbcClient, 9_496_103L))
                .containsExactly("UNKNOWN", "PENDING");
    }

    private static void seedCard(JdbcClient jdbcClient, long orderId, boolean failedPrefix) {
        long paymentId = orderId + 1;
        long cardId = orderId + 2;
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'DIRECT', :idempotencyKey,
                             100, 100, current_timestamp, current_timestamp, current_timestamp)
                        """)
                .param("id", orderId)
                .param("orderNo", "CLEANUP-" + orderId)
                .param("idempotencyKey", "service-card-cleanup-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :outTradeNo, :transactionId, :openid,
                             'PAID', 100, current_timestamp, current_timestamp,
                             current_timestamp, current_timestamp)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "CLEANUP-OUT-" + orderId)
                .param("transactionId", "4200" + orderId)
                .param("openid", "cleanup-openid-" + orderId)
                .update();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (id, order_id, payment_order_id, notify_code_digest)
                        values (:id, :orderId, :paymentId, :digest)
                        """)
                .param("id", cardId)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("digest", String.format("%064x", cardId))
                .update();
        if (failedPrefix) {
            insertDelivery(jdbcClient, cardId + 1, cardId, 1, 2, "FAILED", "{}",
                    "ACTIVATION_WINDOW_EXPIRED");
            insertDelivery(jdbcClient, cardId + 2, cardId, 2, 4, "PENDING", null, "");
            insertDelivery(jdbcClient, cardId + 3, cardId, 3, 6, "UNKNOWN", null, "");
            return;
        }
        insertDelivery(jdbcClient, cardId + 1, cardId, 1, 2, "UNKNOWN", "{}", "");
        insertDelivery(jdbcClient, cardId + 2, cardId, 2, 4, "PENDING", null, "");
    }

    private static void insertDelivery(
            JdbcClient jdbcClient,
            long id,
            long cardId,
            int sequence,
            int target,
            String state,
            String checkJson,
            String errorCode
    ) {
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json, check_json,
                             state, next_action_at, provider_error_code)
                        values
                            (:id, :cardId, :sequence, :target, '{}', :checkJson,
                             :state, current_timestamp, :errorCode)
                        """)
                .param("id", id)
                .param("cardId", cardId)
                .param("sequence", sequence)
                .param("target", target)
                .param("checkJson", checkJson)
                .param("state", state)
                .param("errorCode", errorCode)
                .update();
    }

    private static List<String> states(JdbcClient jdbcClient, long cardId) {
        return jdbcClient.sql("""
                        select state from wechat_service_card_delivery
                        where card_id = :cardId order by sequence_no
                        """)
                .param("cardId", cardId)
                .query(String.class)
                .list();
    }

    private static List<String> errorCodes(JdbcClient jdbcClient, long cardId) {
        return jdbcClient.sql("""
                        select provider_error_code from wechat_service_card_delivery
                        where card_id = :cardId order by sequence_no
                        """)
                .param("cardId", cardId)
                .query(String.class)
                .list();
    }
}
