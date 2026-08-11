package org.muybaby.shopserver.wechat.servicecard;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardDeliveryStore.DeliveryClaim;
import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "shop.wechat.service-card-2001.batch-size=50",
        "shop.wechat.service-card-2001.claim-timeout=2m",
        "shop.wechat.service-card-2001.max-attempts=3",
        "shop.wechat.service-card-2001.retry-backoff=1m",
        "shop.wechat.service-card-2001.max-retry-backoff=30m",
        "shop.wechat.service-card-2001.unknown-recheck-interval=1m",
        "shop.wechat.service-card-2001.max-unknown-recheck-interval=6h",
        "shop.wechat.service-card-2001.not-applied-confirmations=2"
})
@ActiveProfiles("test")
class WechatServiceCardDeliveryStoreIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(9_480_000L);

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WechatServiceCardDeliveryStore store;

    @Test
    void sequenceTwoAndSixStayBlockedUntilTwoThenFourAreApplied() {
        CardFixture card = seedCard(null, null);
        long activation = insertDelivery(card.cardId(), 1, 2, "PENDING", "{}", 0, 0);
        long shipped = insertDelivery(card.cardId(), 2, 4, "PENDING", null, 0, 0);
        long signed = insertDelivery(card.cardId(), 3, 6, "PENDING", null, 0, 0);
        LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);

        assertThat(store.dueIds(WechatServiceCardDeliveryState.PENDING, dueAt, 10))
                .contains(activation)
                .doesNotContain(shipped, signed);
        DeliveryClaim activationClaim = store.claim(
                activation, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();
        store.markApplied(activationClaim, 0, dueAt.plusDays(30));

        assertThat(store.dueIds(WechatServiceCardDeliveryState.PENDING, dueAt, 10))
                .contains(shipped)
                .doesNotContain(signed);
        DeliveryClaim shippedClaim = store.claim(
                shipped, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();
        store.markApplied(shippedClaim, null, null);

        assertThat(store.dueIds(WechatServiceCardDeliveryState.PENDING, dueAt, 10))
                .contains(signed);
        DeliveryClaim signedClaim = store.claim(
                signed, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();
        store.markApplied(signedClaim, null, null);

        assertThat(deliveryStates(card.cardId()))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertThat(cardRemoteStatus(card.cardId())).isEqualTo(6);
    }

    @Test
    void terminalFailureSkipsEveryQueuedSuffixInsteadOfLeavingItBlockedForever() {
        CardFixture failedCard = seedCard(null, null);
        long activation = insertDelivery(
                failedCard.cardId(), 1, 2, "PENDING", "{}", 0, 0
        );
        long shipped = insertDelivery(
                failedCard.cardId(), 2, 4, "PENDING", null, 0, 0
        );
        long signed = insertDelivery(
                failedCard.cardId(), 3, 6, "UNKNOWN", null, 1, 2
        );
        CardFixture unaffectedCard = seedCard(null, null);
        long unaffected = insertDelivery(
                unaffectedCard.cardId(), 1, 2, "PENDING", "{}", 0, 0
        );
        DeliveryClaim claim = store.claim(
                activation, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();

        store.markFailed(
                claim, "ACTIVATION_WINDOW_EXPIRED",
                "The WeChat service-card activation window expired"
        );

        assertThat(deliveryStates(failedCard.cardId()))
                .containsExactly("FAILED", "SKIPPED", "SKIPPED");
        assertThat(deliveryRow(shipped).errorCode()).isEqualTo("PREDECESSOR_FAILED");
        assertThat(deliveryRow(signed).errorCode()).isEqualTo("PREDECESSOR_FAILED");
        assertThat(deliveryRow(shipped).nextActionAt()).isNull();
        assertThat(deliveryState(unaffected)).isEqualTo("PENDING");
    }

    @Test
    void staleClaimTokenCannotOverwriteANewerWorkerClaimOrCardState() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertDelivery(card.cardId(), 1, 2, "PENDING", "{}", 0, 0);
        DeliveryClaim stale = store.claim(
                deliveryId, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();
        assertThat(stale.claimToken()).isNotBlank();
        assertThat(stale.attemptCount()).isOne();

        jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = 'UNKNOWN', claim_token = null, claimed_at = null,
                            next_action_at = :dueAt
                        where id = :id
                        """)
                .param("dueAt", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .param("id", deliveryId)
                .update();
        DeliveryClaim current = store.claim(
                deliveryId, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.markApplied(stale, 0, LocalDateTime.now(ZoneOffset.UTC).plusDays(30));

        assertThat(deliveryState(deliveryId)).isEqualTo("RECONCILING");
        assertThat(cardRemoteStatus(card.cardId())).isNull();

        store.markApplied(current, 0, LocalDateTime.now(ZoneOffset.UTC).plusDays(30));

        assertThat(deliveryState(deliveryId)).isEqualTo("SUCCEEDED");
        assertThat(cardRemoteStatus(card.cardId())).isEqualTo(2);
    }

    @Test
    void staleSendingAndReconcilingClaimsRecoverOnlyToUnknown() {
        CardFixture card = seedCard(null, null);
        long sending = insertClaimedDelivery(card.cardId(), 1, 2, "SENDING");
        long reconciling = insertClaimedDelivery(card.cardId(), 2, 4, "RECONCILING");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);

        store.reconcileStale(now);

        assertThat(deliveryStates(card.cardId()))
                .containsExactly("UNKNOWN", "UNKNOWN");
        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card_delivery
                        where card_id = :cardId
                          and claim_token is null
                          and claimed_at is null
                          and provider_error_code = 'ATTEMPT_OUTCOME_UNKNOWN'
                          and next_action_at = :now
                        """)
                .param("cardId", card.cardId())
                .param("now", now)
                .query(Long.class)
                .single()).isEqualTo(2L);
    }

    @Test
    void emptyQueryMeansNotYetActivatedOnlyForActivationIntent() {
        CardFixture activationCard = seedCard(null, null);
        long activation = insertDelivery(
                activationCard.cardId(), 1, 2, "UNKNOWN", "{}", 1, 0
        );
        DeliveryClaim activationClaim = store.claim(
                activation, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(
                activationClaim, WechatServiceCardQueryResult.notFound(0, "")
        );

        DeliveryRow activationRow = deliveryRow(activation);
        assertThat(activationRow.state()).isEqualTo("UNKNOWN");
        assertThat(activationRow.observations()).isOne();
        assertThat(activationRow.errorCode()).isEqualTo("REMOTE_NOT_APPLIED");

        LocalDateTime activatedAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        CardFixture updateCard = seedCard(2, activatedAt);
        long update = insertDelivery(updateCard.cardId(), 1, 4, "UNKNOWN", null, 1, 0);
        DeliveryClaim updateClaim = store.claim(
                update, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(updateClaim, WechatServiceCardQueryResult.notFound(0, ""));

        DeliveryRow updateRow = deliveryRow(update);
        assertThat(updateRow.state()).isEqualTo("UNKNOWN");
        assertThat(updateRow.observations()).isZero();
        assertThat(updateRow.errorCode()).isEqualTo("REMOTE_STATE_INCONSISTENT");
        assertThat(cardRemoteStatus(updateCard.cardId())).isEqualTo(2);
    }

    @Test
    void repeatedUnknownQueriesUseLongerBackoffAndNeverFailFromSetAttemptCountAlone() {
        CardFixture card = seedCard(2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        long deliveryId = insertDelivery(
                card.cardId(), 1, 4, "UNKNOWN", null, 3, 3
        );
        DeliveryClaim claim = store.claim(
                deliveryId, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        store.applyQueryResult(
                claim, WechatServiceCardQueryResult.retryable(
                        85431, "WeChat service is temporarily unavailable"
                )
        );

        DeliveryRow row = deliveryRow(deliveryId);
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.errorCode()).isEqualTo("WECHAT_85431");
        assertThat(row.errorMessage()).isEqualTo("WeChat service is temporarily unavailable");
        assertThat(row.nextActionAt())
                .isAfter(before.plusMinutes(7))
                .isBefore(before.plusMinutes(10));
    }

    @Test
    void transportQueryFailureKeepsASpecificSafeDiagnostic() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertDelivery(
                card.cardId(), 1, 2, "UNKNOWN", "{}", 1, 0
        );
        DeliveryClaim claim = store.claim(
                deliveryId, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(
                claim, WechatServiceCardQueryResult.retryable(
                        null, "WeChat get_user_notify is unavailable"
                )
        );

        DeliveryRow row = deliveryRow(deliveryId);
        assertThat(row.state()).isEqualTo("UNKNOWN");
        assertThat(row.errorCode()).isEqualTo("QUERY_TRANSPORT_UNAVAILABLE");
        assertThat(row.errorMessage()).isEqualTo("WeChat get_user_notify is unavailable");
    }

    @Test
    void pendingIntentBeyondConfiguredAttemptCountStillClaimsAndUsesCappedBackoff() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertDelivery(
                card.cardId(), 1, 2, "PENDING", "{}", 10, 0
        );
        DeliveryClaim claim = store.claim(
                deliveryId, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        assertThat(claim.attemptCount()).isEqualTo(11);
        store.markRetry(claim, "PROVIDER_UNAVAILABLE", "pre-send failure");

        DeliveryRow row = deliveryRow(deliveryId);
        assertThat(row.state()).isEqualTo("PENDING");
        assertThat(row.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(row.nextActionAt())
                .isAfter(before.plusMinutes(29))
                .isBefore(before.plusMinutes(31));
    }

    @Test
    void queryCodeStateTenBlocksOnlyThatCardAndSkipsItsUnstartedSuffix() {
        CardFixture refused = seedCard(
                2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        );
        long current = insertDelivery(
                refused.cardId(), 1, 4, "UNKNOWN", null, 1, 0
        );
        long suffix = insertDelivery(
                refused.cardId(), 2, 6, "PENDING", null, 0, 0
        );
        CardFixture unaffected = seedCard(null, null);
        long unaffectedDelivery = insertDelivery(
                unaffected.cardId(), 1, 2, "PENDING", "{}", 0, 0
        );
        DeliveryClaim claim = store.claim(
                current, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(claim, WechatServiceCardQueryResult.found(
                2, 10, Instant.now().plusSeconds(3600)
        ));

        assertThat(deliveryState(current)).isEqualTo("FAILED");
        assertThat(deliveryState(suffix)).isEqualTo("SKIPPED");
        assertThat(cardBlock(refused.cardId()))
                .isEqualTo(new CardBlock(true, "USER_REFUSED", true));
        assertThat(cardRemoteCodeState(refused.cardId())).isEqualTo(10);
        assertThat(deliveryState(unaffectedDelivery)).isEqualTo("PENDING");
        assertThat(cardBlock(unaffected.cardId()))
                .isEqualTo(new CardBlock(false, "", false));
        assertThat(store.dueIds(
                WechatServiceCardDeliveryState.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1), 10
        )).contains(unaffectedDelivery).doesNotContain(suffix);
    }

    @Test
    void queryRiskStateFailsCurrentIntentAndSkipsItsQueuedSuffix() {
        CardFixture card = seedCard(
                2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1)
        );
        long current = insertDelivery(
                card.cardId(), 1, 4, "UNKNOWN", null, 1, 0
        );
        long suffix = insertDelivery(
                card.cardId(), 2, 6, "PENDING", null, 0, 0
        );
        DeliveryClaim claim = store.claim(
                current, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(claim, WechatServiceCardQueryResult.found(
                2, 1, Instant.now().plusSeconds(3600)
        ));

        assertThat(deliveryState(current)).isEqualTo("FAILED");
        assertThat(deliveryState(suffix)).isEqualTo("SKIPPED");
        assertThat(deliveryRow(suffix).errorCode()).isEqualTo("PREDECESSOR_FAILED");
    }

    @Test
    void refusalBetweenClaimAndProviderPreflightSkipsWithTheOriginalClaimFence() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertDelivery(card.cardId(), 1, 2, "PENDING", "{}", 0, 0);
        DeliveryClaim claim = store.claim(
                deliveryId, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();

        store.blockUserRefused(card.cardId());

        assertThat(deliveryState(deliveryId)).isEqualTo("SENDING");
        assertThat(store.prepareProviderCall(claim)).isFalse();
        assertThat(deliveryState(deliveryId)).isEqualTo("SKIPPED");
        assertThat(cardBlock(card.cardId()))
                .isEqualTo(new CardBlock(true, "USER_REFUSED", true));
    }

    @Test
    void runtimeReleaseDoesNotReviveAClaimAfterTheCardWasBlocked() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertDelivery(card.cardId(), 1, 2, "PENDING", "{}", 0, 0);
        DeliveryClaim claim = store.claim(
                deliveryId, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();

        store.blockUserRefused(card.cardId());

        assertThat(store.releaseWithoutProviderCall(claim)).isTrue();
        assertThat(deliveryState(deliveryId)).isEqualTo("SKIPPED");
        assertThat(deliveryAttempts(deliveryId))
                .isEqualTo(new DeliveryAttempts(0, 0, "USER_REFUSED"));
    }

    @Test
    void runtimeReleaseRestoresOriginalStateAndRollsBackOnlyTheClaimCounter() {
        CardFixture setCard = seedCard(null, null);
        long pending = insertDelivery(setCard.cardId(), 1, 2, "PENDING", "{}", 0, 0);
        DeliveryClaim setClaim = store.claim(
                pending, WechatServiceCardDeliveryState.PENDING
        ).orElseThrow();

        assertThat(store.releaseWithoutProviderCall(setClaim)).isTrue();
        assertThat(deliveryState(pending)).isEqualTo("PENDING");
        assertThat(deliveryAttempts(pending))
                .isEqualTo(new DeliveryAttempts(0, 0, ""));

        CardFixture queryCard = seedCard(2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        long unknown = insertDelivery(queryCard.cardId(), 1, 4, "UNKNOWN", null, 3, 2);
        DeliveryClaim queryClaim = store.claim(
                unknown, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        assertThat(store.releaseWithoutProviderCall(queryClaim)).isTrue();
        assertThat(deliveryState(unknown)).isEqualTo("UNKNOWN");
        assertThat(deliveryAttempts(unknown))
                .isEqualTo(new DeliveryAttempts(3, 2, ""));
    }

    @Test
    void staleInFlightClaimOnBlockedCardRecoversToSkippedNotUnknown() {
        CardFixture card = seedCard(null, null);
        long deliveryId = insertClaimedDelivery(card.cardId(), 1, 2, "SENDING");
        store.blockUserRefused(card.cardId());

        store.reconcileStale(LocalDateTime.now(ZoneOffset.UTC).withNano(0));

        assertThat(deliveryState(deliveryId)).isEqualTo("SKIPPED");
        assertThat(jdbcClient.sql("""
                        select count(*) from wechat_service_card_delivery
                        where id = :id and claim_token is null and claimed_at is null
                          and provider_error_code = 'USER_REFUSED'
                        """)
                .param("id", deliveryId)
                .query(Long.class)
                .single()).isOne();
    }

    @Test
    void callbackBlockAfterQueryPreflightSkipsRetryablePriorButKeepsTargetSuccess() {
        LocalDateTime activatedAt = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        CardFixture priorCard = seedCard(2, activatedAt);
        long priorDelivery = insertDelivery(
                priorCard.cardId(), 1, 4, "UNKNOWN", null, 1, 0
        );
        DeliveryClaim priorClaim = store.claim(
                priorDelivery, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();
        assertThat(store.prepareProviderCall(priorClaim)).isTrue();
        store.blockUserRefused(priorCard.cardId());

        store.applyQueryResult(priorClaim, WechatServiceCardQueryResult.found(
                2, 0, Instant.now().plusSeconds(3600)
        ));

        assertThat(deliveryState(priorDelivery)).isEqualTo("SKIPPED");
        assertThat(deliveryRow(priorDelivery).nextActionAt()).isNull();

        CardFixture targetCard = seedCard(2, activatedAt);
        long targetDelivery = insertDelivery(
                targetCard.cardId(), 1, 4, "UNKNOWN", null, 1, 0
        );
        DeliveryClaim targetClaim = store.claim(
                targetDelivery, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();
        assertThat(store.prepareProviderCall(targetClaim)).isTrue();
        store.blockUserRefused(targetCard.cardId());

        store.applyQueryResult(targetClaim, WechatServiceCardQueryResult.found(
                4, 0, Instant.now().plusSeconds(3600)
        ));

        assertThat(deliveryState(targetDelivery)).isEqualTo("SUCCEEDED");
        assertThat(cardRemoteStatus(targetCard.cardId())).isEqualTo(4);
    }

    @Test
    void remoteStateMatchingALaterQueuedIntentSkipsStaleIntentWithoutBlockingSequence() {
        CardFixture card = seedCard(2, LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
        long shipped = insertDelivery(card.cardId(), 1, 4, "UNKNOWN", null, 1, 0);
        long signed = insertDelivery(card.cardId(), 2, 6, "PENDING", null, 0, 0);
        DeliveryClaim claim = store.claim(
                shipped, WechatServiceCardDeliveryState.UNKNOWN
        ).orElseThrow();

        store.applyQueryResult(claim, WechatServiceCardQueryResult.found(
                6, 0, Instant.now().plusSeconds(3600)
        ));

        assertThat(deliveryState(shipped)).isEqualTo("SKIPPED");
        assertThat(cardRemoteStatus(card.cardId())).isEqualTo(6);
        assertThat(store.dueIds(
                WechatServiceCardDeliveryState.PENDING,
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1), 10
        )).contains(signed).doesNotContain(shipped);
    }

    private CardFixture seedCard(Integer remoteStatus, LocalDateTime activatedAt) {
        long orderId = IDS.incrementAndGet();
        long paymentId = IDS.incrementAndGet();
        long cardId = IDS.incrementAndGet();
        LocalDateTime paidAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0);
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             payable_amount_cent, paid_amount_cent, paid_at, created_at, updated_at)
                        values
                            (:id, :orderNo, 1, 'PAID', 'DIRECT', :key,
                             100, 100, :paidAt, :paidAt, :paidAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "STORE-" + orderId)
                .param("key", "store-" + orderId)
                .param("paidAt", paidAt)
                .update();
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, payer_openid,
                             status, amount_cent, expires_at, paid_at, created_at, updated_at)
                        values
                            (:id, :orderId, :outTradeNo, :transactionId, :openid,
                             'PAID', 100, :expiresAt, :paidAt, :paidAt, :paidAt)
                        """)
                .param("id", paymentId)
                .param("orderId", orderId)
                .param("outTradeNo", "STORE-OUT-" + orderId)
                .param("transactionId", "4200" + orderId)
                .param("openid", "store-openid-" + orderId)
                .param("expiresAt", paidAt.plusMinutes(15))
                .param("paidAt", paidAt)
                .update();
        jdbcClient.sql("""
                        insert into wechat_service_card
                            (id, order_id, payment_order_id, notify_code_digest,
                             remote_status, activated_at, created_at, updated_at)
                        values
                            (:id, :orderId, :paymentId, :digest,
                             :remoteStatus, :activatedAt, :paidAt, :paidAt)
                        """)
                .param("id", cardId)
                .param("orderId", orderId)
                .param("paymentId", paymentId)
                .param("digest", String.format("%064x", cardId))
                .param("remoteStatus", remoteStatus)
                .param("activatedAt", activatedAt)
                .param("paidAt", paidAt)
                .update();
        return new CardFixture(cardId);
    }

    private long insertDelivery(
            long cardId,
            int sequence,
            int targetStatus,
            String state,
            String checkJson,
            int setAttempts,
            int reconcileAttempts
    ) {
        long id = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json, check_json,
                             state, attempt_count, reconcile_attempt_count, next_action_at,
                             created_at, updated_at)
                        values
                            (:id, :cardId, :sequence, :targetStatus, '{}', :checkJson,
                             :state, :setAttempts, :reconcileAttempts, :nextActionAt,
                             current_timestamp, current_timestamp)
                        """)
                .param("id", id)
                .param("cardId", cardId)
                .param("sequence", sequence)
                .param("targetStatus", targetStatus)
                .param("checkJson", checkJson)
                .param("state", state)
                .param("setAttempts", setAttempts)
                .param("reconcileAttempts", reconcileAttempts)
                .param("nextActionAt", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .update();
        return id;
    }

    private long insertClaimedDelivery(long cardId, int sequence, int target, String state) {
        long id = IDS.incrementAndGet();
        jdbcClient.sql("""
                        insert into wechat_service_card_delivery
                            (id, card_id, sequence_no, target_status, content_json,
                             state, attempt_count, claim_token, claimed_at,
                             created_at, updated_at)
                        values
                            (:id, :cardId, :sequence, :target, '{}',
                             :state, 1, :claimToken, :claimedAt,
                             current_timestamp, current_timestamp)
                        """)
                .param("id", id)
                .param("cardId", cardId)
                .param("sequence", sequence)
                .param("target", target)
                .param("state", state)
                .param("claimToken", "stale-claim-" + id)
                .param("claimedAt", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10))
                .update();
        return id;
    }

    private String deliveryState(long deliveryId) {
        return jdbcClient.sql("select state from wechat_service_card_delivery where id = :id")
                .param("id", deliveryId)
                .query(String.class)
                .single();
    }

    private List<String> deliveryStates(long cardId) {
        return jdbcClient.sql("""
                        select state from wechat_service_card_delivery
                        where card_id = :cardId order by sequence_no
                        """)
                .param("cardId", cardId)
                .query(String.class)
                .list();
    }

    private Integer cardRemoteStatus(long cardId) {
        return jdbcClient.sql("select remote_status from wechat_service_card where id = :id")
                .param("id", cardId)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    private Integer cardRemoteCodeState(long cardId) {
        return jdbcClient.sql("select remote_code_state from wechat_service_card where id = :id")
                .param("id", cardId)
                .query(Integer.class)
                .optional()
                .orElse(null);
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

    private DeliveryRow deliveryRow(long deliveryId) {
        return jdbcClient.sql("""
                        select state, not_applied_observations, provider_error_code,
                               provider_error_message, next_action_at
                        from wechat_service_card_delivery where id = :id
                        """)
                .param("id", deliveryId)
                .query((rs, rowNum) -> new DeliveryRow(
                        rs.getString("state"), rs.getInt("not_applied_observations"),
                        rs.getString("provider_error_code"),
                        rs.getString("provider_error_message"),
                        rs.getObject("next_action_at", LocalDateTime.class)
                ))
                .single();
    }

    private DeliveryAttempts deliveryAttempts(long deliveryId) {
        return jdbcClient.sql("""
                        select attempt_count, reconcile_attempt_count, provider_error_code
                        from wechat_service_card_delivery where id = :id
                        """)
                .param("id", deliveryId)
                .query((rs, rowNum) -> new DeliveryAttempts(
                        rs.getInt("attempt_count"),
                        rs.getInt("reconcile_attempt_count"),
                        rs.getString("provider_error_code")
                ))
                .single();
    }

    private record CardFixture(long cardId) {
    }

    private record DeliveryRow(
            String state,
            int observations,
            String errorCode,
            String errorMessage,
            LocalDateTime nextActionAt
    ) {
    }

    private record CardBlock(boolean blocked, String reason, boolean hasBlockedAt) {
    }

    private record DeliveryAttempts(
            int setAttempts,
            int reconciliationAttempts,
            String errorCode
    ) {
    }
}
