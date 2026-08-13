package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WechatServiceCardSchemaTest {

    private static final AtomicLong IDS = new AtomicLong(9_460_000L);

    @Autowired
    JdbcClient jdbcClient;

    private long orderId;
    private long paymentId;
    private long cardId;

    @BeforeEach
    void seedAggregate() {
        orderId = IDS.incrementAndGet();
        paymentId = IDS.incrementAndGet();
        cardId = IDS.incrementAndGet();
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'DIRECT', :idempotencyKey,
                             100, 100, :now, :now, :now)
                        """)
                .param("id", orderId)
                .param("orderNo", "SCHEMA-" + orderId)
                .param("idempotencyKey", "service-card-schema-" + orderId)
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :outTradeNo, :transactionId, :openid,
                             'PAID', 100, :expiresAt, :now, :now, :now)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "SCHEMA-OUT-" + orderId)
                .param("transactionId", "4200" + orderId)
                .param("openid", "schema-openid-" + orderId)
                .param("expiresAt", now.plusMinutes(15))
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (id, order_id, payment_order_id, notify_code_digest,
                             account_template_record_id, restore_status, remote_status,
                             remote_code_state, created_at, updated_at)
                        values
                            (:id, :orderId, :paymentId, :digest,
                             'template-record', 2, 2, 0, :now, :now)
                        """)
                .param("id", cardId)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("digest", hex(cardId))
                .param("now", now)
                .update();
    }

    @Test
    void representativeCardDeliveryAndCallbackRowsAreAccepted() {
        long deliveryId = insertValidDelivery("SENDING", "claim-token", true);
        jdbcClient.sql("""
                        insert into wechat_service_card_callback_log
                            (event_digest, card_id, delivery_id, card_status, fail_ret,
                             fail_message, matched, received_at, created_at)
                        values
                            (:digest, :cardId, :deliveryId, 2, -1004,
                             'The user rejected service-card message delivery', true,
                             current_timestamp, current_timestamp)
                        """)
                .param("digest", hex(IDS.incrementAndGet()))
                .param("cardId", cardId)
                .param("deliveryId", deliveryId)
                .update();

        assertThat(count("wechat_service_card", "id", cardId)).isOne();
        assertThat(count("wechat_service_card_delivery", "id", deliveryId)).isOne();
        assertThat(count("wechat_service_card_callback_log", "delivery_id", deliveryId)).isOne();
    }

    @Test
    void cardForeignKeysAndStatusChecksRejectInvalidRows() {
        assertRejected("""
                insert into wechat_service_card
                    (order_id, payment_order_id, notify_code_digest, notify_type)
                values (:missingOrder, :paymentId, :digest, 2001)
                """, "missingOrder", IDS.incrementAndGet(), "paymentId", paymentId,
                "digest", hex(IDS.incrementAndGet()));
        assertRejected("""
                insert into wechat_service_card
                    (order_id, payment_order_id, notify_code_digest, notify_type)
                values (:orderId, :missingPayment, :digest, 2001)
                """, "orderId", orderId, "missingPayment", IDS.incrementAndGet(),
                "digest", hex(IDS.incrementAndGet()));

        assertRejected("update wechat_service_card set notify_type = 2002 where id = :id",
                "id", cardId);
        assertRejected("update wechat_service_card set restore_status = 3 where id = :id",
                "id", cardId);
        assertRejected("update wechat_service_card set remote_status = 12 where id = :id",
                "id", cardId);
        assertRejected("update wechat_service_card set remote_code_state = 3 where id = :id",
                "id", cardId);
        assertRejected("""
                        update wechat_service_card
                        set send_blocked = true where id = :id
                        """, "id", cardId);
        assertRejected("""
                        update wechat_service_card
                        set send_blocked = false,
                            send_block_reason = 'USER_REFUSED',
                            send_blocked_at = current_timestamp
                        where id = :id
                        """, "id", cardId);

        jdbcClient.sql("""
                        update wechat_service_card
                        set send_blocked = true,
                            send_block_reason = 'USER_REFUSED',
                            send_blocked_at = current_timestamp
                        where id = :id
                        """)
                .param("id", cardId)
                .update();
        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card
                        where id = :id and send_blocked = true
                          and send_block_reason = 'USER_REFUSED'
                          and send_blocked_at is not null
                        """)
                .param("id", cardId)
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void deliveryStateClaimAndMessageResultChecksRejectImpossibleCombinations() {
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 1, "target", 12,
                "state", "PENDING", "attempts", 0, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "UNKNOWN", "failRet", null, "resultAt", null);
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 2, "target", 2,
                "state", "SENDING", "attempts", 1, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "UNKNOWN", "failRet", null, "resultAt", null);
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 3, "target", 2,
                "state", "PENDING", "attempts", 0, "reconcileAttempts", 0,
                "observations", 0, "claimToken", "orphan-token",
                "claimedAt", LocalDateTime.now(),
                "messageState", "UNKNOWN", "failRet", null, "resultAt", null);
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 4, "target", 2,
                "state", "PENDING", "attempts", -1, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "UNKNOWN", "failRet", null, "resultAt", null);
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 5, "target", 2,
                "state", "PENDING", "attempts", 0, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "FAILED", "failRet", 0,
                "resultAt", LocalDateTime.now());
        assertRejected(deliverySql(),
                "cardId", cardId, "sequence", 6, "target", 2,
                "state", "PENDING", "attempts", 0, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "FAILED", "failRet", -1004, "resultAt", null);
    }

    @Test
    void deliveryAndCallbackForeignKeysAndMatchedInvariantAreEnforced() {
        assertRejected(deliverySql(),
                "cardId", IDS.incrementAndGet(), "sequence", 1, "target", 2,
                "state", "PENDING", "attempts", 0, "reconcileAttempts", 0,
                "observations", 0, "claimToken", null, "claimedAt", null,
                "messageState", "UNKNOWN", "failRet", null, "resultAt", null);

        long deliveryId = insertValidDelivery("SUCCEEDED", null, false);
        assertRejected("""
                insert into wechat_service_card_callback_log
                    (event_digest, card_id, delivery_id, card_status, fail_ret,
                     fail_message, matched, received_at)
                values (:digest, :cardId, :deliveryId, 2, -1004, 'fixed', true, current_timestamp)
                """, "digest", hex(IDS.incrementAndGet()), "cardId", cardId,
                "deliveryId", IDS.incrementAndGet());
        assertRejected("""
                insert into wechat_service_card_callback_log
                    (event_digest, card_id, delivery_id, card_status, fail_ret,
                     fail_message, matched, received_at)
                values (:digest, null, null, 2, -1004, 'fixed', true, current_timestamp)
                """, "digest", hex(IDS.incrementAndGet()));
        assertRejected("""
                insert into wechat_service_card_callback_log
                    (event_digest, card_id, delivery_id, card_status, fail_ret,
                     fail_message, matched, received_at)
                values (:digest, :cardId, :deliveryId, 12, -1004, 'fixed', true, current_timestamp)
                """, "digest", hex(IDS.incrementAndGet()), "cardId", cardId,
                "deliveryId", deliveryId);
    }

    private long insertValidDelivery(String state, String claimToken, boolean claimed) {
        long id = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json, check_json,
                             state, attempt_count, claim_token, claimed_at, next_action_at)
                        values
                            (:id, :cardId, 1, 2, '{}', '{}',
                             :state, 1, :claimToken, :claimedAt, current_timestamp)
                        """)
                .param("id", id)
                .param("cardId", cardId)
                .param("state", state)
                .param("claimToken", claimToken)
                .param("claimedAt", claimed ? LocalDateTime.now() : null)
                .update();
        return id;
    }

    private String deliverySql() {
        return """
                insert into wechat_service_card_delivery
                    (card_id, sequence_no, target_status, content_json, check_json,
                     state, attempt_count, reconcile_attempt_count, not_applied_observations,
                     claim_token, claimed_at, message_result_state, message_fail_ret,
                     message_result_at)
                values
                    (:cardId, :sequence, :target, '{}', '{}',
                     :state, :attempts, :reconcileAttempts, :observations,
                     :claimToken, :claimedAt, :messageState, :failRet, :resultAt)
                """;
    }

    private void assertRejected(String sql, Object... params) {
        assertThatThrownBy(() -> {
            JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
            for (int index = 0; index < params.length; index += 2) {
                statement = statement.param((String) params[index], params[index + 1]);
            }
            statement.update();
        }).isInstanceOf(DataAccessException.class);
    }

    private long count(String table, String column, long value) {
        return jdbcClient.sql("select count(*) from " + table + " where " + column + " = :value")
                .param("value", value)
                .query(Long.class)
                .single();
    }

    private String hex(long value) {
        return String.format("%064x", value);
    }
}
