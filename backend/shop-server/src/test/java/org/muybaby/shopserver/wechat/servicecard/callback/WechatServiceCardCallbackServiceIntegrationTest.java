package org.muybaby.shopserver.wechat.servicecard.callback;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryState;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class WechatServiceCardCallbackServiceIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(9_470_000L);

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WechatServiceCardCallbackService callbackService;

    @Autowired
    WechatServiceCardDeliveryStore deliveryStore;

    @Test
    void prefixedUserRefusalIsIdempotentAndBlocksOnlyTheMatchedCard() {
        Fixture fixture = seedCard(2);
        long deliveryId = insertDelivery(fixture.cardId(), 1, 2, "PENDING");
        Fixture other = seedCard(2);
        long otherDeliveryId = insertDelivery(other.cardId(), 1, 2, "PENDING");
        String body = event(
                fixture.openid(), "p1." + fixture.transactionId(), 2, -1004
        );

        callbackService.accept(body);
        callbackService.accept(body);

        DeliveryDiagnostic delivery = delivery(deliveryId);
        assertThat(delivery.state()).isEqualTo("SKIPPED");
        assertThat(delivery.messageResultState()).isEqualTo("FAILED");
        assertThat(delivery.failureCode()).isEqualTo(-1004);
        assertThat(delivery.failureMessage())
                .isEqualTo("The user rejected service-card message delivery")
                .doesNotContain(fixture.openid(), fixture.transactionId());
        assertThat(cardRemoteStatus(fixture.cardId())).isEqualTo(2);
        assertThat(cardBlock(fixture.cardId()))
                .isEqualTo(new CardBlock(true, "USER_REFUSED", true));
        assertThat(delivery(otherDeliveryId)).isEqualTo(
                new DeliveryDiagnostic("PENDING", "UNKNOWN", null, "")
        );
        assertThat(cardBlock(other.cardId()))
                .isEqualTo(new CardBlock(false, "", false));
        assertThat(deliveryStore.dueIds(
                WechatServiceCardDeliveryState.PENDING,
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1), 10
        )).contains(otherDeliveryId).doesNotContain(deliveryId);

        CallbackLog callback = callbacksFor(fixture.cardId());
        assertThat(callback.count()).isOne();
        assertThat(callback.matchedCount()).isOne();
        assertThat(callback.deliveryId()).isEqualTo(deliveryId);
    }

    @Test
    void unknownIdentityIsRecordedUnmatchedWithoutMutatingAnyDelivery() {
        Fixture fixture = seedCard(4);
        long deliveryId = insertDelivery(fixture.cardId(), 1, 4, "SUCCEEDED");

        callbackService.accept(event(
                "different-openid", fixture.transactionId(), 4, -10001
        ));

        assertThat(delivery(deliveryId)).isEqualTo(
                new DeliveryDiagnostic("SUCCEEDED", "UNKNOWN", null, "")
        );
        CallbackLog callback = jdbcClient.sql("""
                        select count(*) as row_count,
                               sum(case when matched then 1 else 0 end) as matched_count,
                               max(delivery_id) as delivery_id
                        from wechat_service_card_callback_log
                        where fail_ret = -10001 and card_id is null
                        """)
                .query((rs, rowNum) -> new CallbackLog(
                        rs.getLong("row_count"), rs.getLong("matched_count"),
                        rs.getObject("delivery_id", Long.class)
                ))
                .single();
        assertThat(callback.count()).isOne();
        assertThat(callback.matchedCount()).isZero();
        assertThat(callback.deliveryId()).isNull();
    }

    @Test
    void repeatedTargetStatusIsKeptUnmatchedInsteadOfGuessingADelivery() {
        Fixture fixture = seedCard(4);
        long first = insertDelivery(fixture.cardId(), 1, 7, "SUCCEEDED");
        long second = insertDelivery(fixture.cardId(), 2, 7, "PENDING");

        callbackService.accept(event(fixture.openid(), fixture.transactionId(), 7, -1005));

        assertThat(delivery(first).messageResultState()).isEqualTo("UNKNOWN");
        assertThat(delivery(second).messageResultState()).isEqualTo("UNKNOWN");
        CallbackLog callback = callbacksFor(fixture.cardId());
        assertThat(callback.count()).isOne();
        assertThat(callback.matchedCount()).isZero();
        assertThat(callback.deliveryId()).isNull();
        assertThat(cardRemoteStatus(fixture.cardId())).isEqualTo(4);
    }

    @Test
    void rejectsNonFailureWrongTypeAndWrongEventBeforePersistence() {
        Fixture fixture = seedCard(2);

        assertThatThrownBy(() -> callbackService.accept(
                event(fixture.openid(), fixture.transactionId(), 2, 0)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> callbackService.accept(event(
                fixture.openid(), fixture.transactionId(), 2, -1004
        ).replace("\"notify_type\":2001", "\"notify_type\":1001")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> callbackService.accept(event(
                fixture.openid(), fixture.transactionId(), 2, -1004
        ).replace("notify_service_msg_send_result", "different_event")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card_callback_log
                        where card_id = :cardId
                        """)
                .param("cardId", fixture.cardId())
                .query(Long.class)
                .single()).isZero();
    }

    private Fixture seedCard(int remoteStatus) {
        long orderId = IDS.incrementAndGet();
        long paymentId = IDS.incrementAndGet();
        long cardId = IDS.incrementAndGet();
        String transactionId = "4200" + orderId;
        String openid = "callback-openid-" + orderId;
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 12, 0);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'DIRECT', :key,
                             100, 100, :now, :now, :now)
                        """)
                .param("id", orderId)
                .param("orderNo", "CALLBACK-" + orderId)
                .param("key", "callback-" + orderId)
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
                .param("outTradeNo", "CALLBACK-OUT-" + orderId)
                .param("transactionId", transactionId)
                .param("openid", openid)
                .param("expiresAt", now.plusMinutes(15))
                .param("now", now)
                .update();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (id, order_id, payment_order_id, notify_code_digest,
                             remote_status, activated_at, created_at, updated_at)
                        values
                            (:id, :orderId, :paymentId, :digest,
                             :remoteStatus, :now, :now, :now)
                        """)
                .param("id", cardId)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("digest", sha256(transactionId))
                .param("remoteStatus", remoteStatus)
                .param("now", now)
                .update();
        return new Fixture(cardId, transactionId, openid);
    }

    private long insertDelivery(long cardId, int sequence, int targetStatus, String state) {
        long id = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json,
                             state, applied_at, created_at, updated_at)
                        values
                            (:id, :cardId, :sequence, :targetStatus, '{}',
                             :state, :appliedAt, current_timestamp, current_timestamp)
                        """)
                .param("id", id)
                .param("cardId", cardId)
                .param("sequence", sequence)
                .param("targetStatus", targetStatus)
                .param("state", state)
                .param("appliedAt", "SUCCEEDED".equals(state) ? LocalDateTime.now() : null)
                .update();
        return id;
    }

    private DeliveryDiagnostic delivery(long deliveryId) {
        return jdbcClient.sql("""
                        select state, message_result_state, message_fail_ret, message_fail_message
                        from wechat_service_card_delivery where id = :id
                        """)
                .param("id", deliveryId)
                .query((rs, rowNum) -> new DeliveryDiagnostic(
                        rs.getString("state"), rs.getString("message_result_state"),
                        rs.getObject("message_fail_ret", Integer.class),
                        rs.getString("message_fail_message")
                ))
                .single();
    }

    private CallbackLog callbacksFor(long cardId) {
        return jdbcClient.sql("""
                        select count(*) as row_count,
                               sum(case when matched then 1 else 0 end) as matched_count,
                               max(delivery_id) as delivery_id
                        from wechat_service_card_callback_log where card_id = :cardId
                        """)
                .param("cardId", cardId)
                .query((rs, rowNum) -> new CallbackLog(
                        rs.getLong("row_count"), rs.getLong("matched_count"),
                        rs.getObject("delivery_id", Long.class)
                ))
                .single();
    }

    private Integer cardRemoteStatus(long cardId) {
        return jdbcClient.sql("select remote_status from wechat_service_card where id = :id")
                .param("id", cardId)
                .query(Integer.class)
                .single();
    }

    private CardBlock cardBlock(long cardId) {
        return jdbcClient.sql("""
                        select send_blocked, send_block_reason, send_blocked_at
                        from wechat_service_card where id = :id
                        """)
                .param("id", cardId)
                .query((rs, rowNum) -> new CardBlock(
                        rs.getBoolean("send_blocked"),
                        rs.getString("send_block_reason"),
                        rs.getObject("send_blocked_at", LocalDateTime.class) != null
                ))
                .single();
    }

    private String event(String openid, String notifyCode, int status, int failureCode) {
        return """
                {"MsgType":"event","Event":"notify_service_msg_send_result",
                 "openid":"%s","notify_type":2001,"notify_code":"%s",
                 "card_status":%d,"fail_ret":%d}
                """.formatted(openid, notifyCode, status, failureCode).strip();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Fixture(long cardId, String transactionId, String openid) {
    }

    private record DeliveryDiagnostic(
            String state,
            String messageResultState,
            Integer failureCode,
            String failureMessage
    ) {
    }

    private record CallbackLog(long count, long matchedCount, Long deliveryId) {
    }

    private record CardBlock(boolean blocked, String reason, boolean hasBlockedAt) {
    }
}
