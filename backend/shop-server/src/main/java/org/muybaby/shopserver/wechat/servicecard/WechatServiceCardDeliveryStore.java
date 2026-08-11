package org.muybaby.shopserver.wechat.servicecard;

import org.muybaby.shopserver.wechat.servicecard.provider.WechatServiceCardQueryResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WechatServiceCardDeliveryStore {

    private final JdbcClient jdbcClient;
    private final WechatServiceCardProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public WechatServiceCardDeliveryStore(
            JdbcClient jdbcClient,
            WechatServiceCardProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<Long> dueIds(WechatServiceCardDeliveryState state, LocalDateTime now, int limit) {
        return jdbcClient.sql("""
                        select delivery.id
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where delivery.state = :state
                          and card.send_blocked = false
                          and (delivery.next_action_at is null or delivery.next_action_at <= :now)
                          and not exists (
                              select 1
                              from wechat_service_card_delivery earlier
                              where earlier.card_id = delivery.card_id
                                and earlier.sequence_no < delivery.sequence_no
                                and earlier.state not in ('SUCCEEDED', 'SKIPPED')
                          )
                        order by delivery.next_action_at, delivery.id
                        limit :limit
                        """)
                .param("state", state.name())
                .param("now", now)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public Optional<DeliveryClaim> claim(long deliveryId, WechatServiceCardDeliveryState expected) {
        DeliveryClaim claim = transaction.execute(status -> claimInTransaction(deliveryId, expected));
        return Optional.ofNullable(claim);
    }

    /**
     * Restores the exact pre-claim state when the runtime worker gate closes before provider I/O.
     * Claiming alone is not an attempt, so the corresponding counter is rolled back as well.
     */
    public boolean releaseWithoutProviderCall(DeliveryClaim claim) {
        if (claim == null) {
            return false;
        }
        boolean setClaim = claim.claimedState() == WechatServiceCardDeliveryState.SENDING;
        boolean queryClaim = claim.claimedState() == WechatServiceCardDeliveryState.RECONCILING;
        if (!setClaim && !queryClaim) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.execute(status ->
                releaseWithoutProviderCallInTransaction(claim, setClaim)
        ));
    }

    private boolean releaseWithoutProviderCallInTransaction(
            DeliveryClaim claim,
            boolean setClaim
    ) {
        // Use the established card -> delivery lock order. A concurrent refusal callback can
        // otherwise block the card while this method revives its claimed row as pending.
        if (!lockClaim(claim)) {
            return false;
        }
        Boolean sendBlocked = jdbcClient.sql(
                        "select send_blocked from wechat_service_card where id = :cardId")
                .param("cardId", claim.cardId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
        WechatServiceCardDeliveryState restored = setClaim
                ? WechatServiceCardDeliveryState.PENDING
                : WechatServiceCardDeliveryState.UNKNOWN;
        LocalDateTime now = now();
        if (Boolean.TRUE.equals(sendBlocked)) {
            return jdbcClient.sql("""
                            update wechat_service_card_delivery
                            set state = 'SKIPPED',
                                claim_token = null,
                                claimed_at = null,
                                next_action_at = null,
                                attempt_count = case
                                    when :setClaim and attempt_count > 0 then attempt_count - 1
                                    else attempt_count
                                end,
                                reconcile_attempt_count = case
                                    when :setClaim then reconcile_attempt_count
                                    when reconcile_attempt_count > 0 then reconcile_attempt_count - 1
                                    else reconcile_attempt_count
                                end,
                                provider_error_code = 'USER_REFUSED',
                                provider_error_message = 'The user refused this WeChat service card',
                                updated_at = :updatedAt
                            where id = :deliveryId
                              and state = :claimedState
                              and claim_token = :claimToken
                            """)
                    .param("setClaim", setClaim)
                    .param("updatedAt", now)
                    .param("deliveryId", claim.deliveryId())
                    .param("claimedState", claim.claimedState().name())
                    .param("claimToken", claim.claimToken())
                    .update() == 1;
        }
        return jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = :restoredState,
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = :nextActionAt,
                            attempt_count = case
                                when :setClaim and attempt_count > 0 then attempt_count - 1
                                else attempt_count
                            end,
                            reconcile_attempt_count = case
                                when :setClaim then reconcile_attempt_count
                                when reconcile_attempt_count > 0 then reconcile_attempt_count - 1
                                else reconcile_attempt_count
                            end,
                            updated_at = :updatedAt
                        where id = :deliveryId
                          and state = :claimedState
                          and claim_token = :claimToken
                        """)
                .param("restoredState", restored.name())
                .param("nextActionAt", now)
                .param("setClaim", setClaim)
                .param("updatedAt", now)
                .param("deliveryId", claim.deliveryId())
                .param("claimedState", claim.claimedState().name())
                .param("claimToken", claim.claimToken())
                .update() == 1;
    }

    private DeliveryClaim claimInTransaction(long deliveryId, WechatServiceCardDeliveryState expected) {
        Long cardId = jdbcClient.sql("""
                        select card_id from wechat_service_card_delivery where id = :deliveryId
                        """)
                .param("deliveryId", deliveryId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (cardId == null) {
            return null;
        }
        Boolean sendBlocked = jdbcClient.sql(
                        "select send_blocked from wechat_service_card where id = :cardId for update")
                .param("cardId", cardId)
                .query(Boolean.class)
                .optional()
                .orElse(null);
        if (sendBlocked == null || sendBlocked) {
            return null;
        }
        Candidate candidate = jdbcClient.sql("""
                        select id, card_id, state, attempt_count, next_action_at
                        from wechat_service_card_delivery
                        where id = :deliveryId
                        for update
                        """)
                .param("deliveryId", deliveryId)
                .query((rs, rowNum) -> new Candidate(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getString("state"),
                        rs.getInt("attempt_count"),
                        rs.getObject("next_action_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
        LocalDateTime now = now();
        if (candidate == null || !expected.name().equals(candidate.state())
                || (candidate.nextActionAt() != null && candidate.nextActionAt().isAfter(now))
                || hasBlockingEarlier(candidate.cardId(), candidate.id())) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        WechatServiceCardDeliveryState claimedState = expected == WechatServiceCardDeliveryState.PENDING
                ? WechatServiceCardDeliveryState.SENDING
                : WechatServiceCardDeliveryState.RECONCILING;
        int updated = jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = :claimedState,
                            claim_token = :claimToken,
                            claimed_at = :claimedAt,
                            next_action_at = null,
                            attempt_count = case
                                when :setAttempt and attempt_count < 2147483647
                                    then attempt_count + 1
                                else attempt_count
                            end,
                            reconcile_attempt_count = case
                                when :setAttempt then reconcile_attempt_count
                                when reconcile_attempt_count < 2147483647
                                    then reconcile_attempt_count + 1
                                else reconcile_attempt_count
                            end,
                            updated_at = :updatedAt
                        where id = :deliveryId and state = :expectedState
                        """)
                .param("claimedState", claimedState.name())
                .param("claimToken", token)
                .param("claimedAt", now)
                .param("setAttempt", expected == WechatServiceCardDeliveryState.PENDING)
                .param("updatedAt", now)
                .param("deliveryId", deliveryId)
                .param("expectedState", expected.name())
                .update();
        if (updated != 1) {
            return null;
        }
        return loadClaim(deliveryId, token, claimedState);
    }

    public void reconcileStale(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.claimTimeout());
        List<Long> staleIds = jdbcClient.sql("""
                        select id from wechat_service_card_delivery
                        where state in ('SENDING', 'RECONCILING') and claimed_at < :cutoff
                        order by id
                        limit :limit
                        """)
                .param("cutoff", cutoff)
                .param("limit", properties.batchSize())
                .query(Long.class)
                .list();
        for (Long deliveryId : staleIds) {
            transaction.executeWithoutResult(status -> reconcileOneStale(deliveryId, cutoff, now));
        }
    }

    private void reconcileOneStale(long deliveryId, LocalDateTime cutoff, LocalDateTime now) {
        Long cardId = jdbcClient.sql(
                        "select card_id from wechat_service_card_delivery where id = :deliveryId")
                .param("deliveryId", deliveryId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (cardId == null) {
            return;
        }
        Boolean sendBlocked = jdbcClient.sql(
                        "select send_blocked from wechat_service_card where id = :cardId for update")
                .param("cardId", cardId)
                .query(Boolean.class)
                .optional()
                .orElse(null);
        if (sendBlocked == null) {
            return;
        }
        if (sendBlocked) {
            jdbcClient.sql("""
                            update wechat_service_card_delivery
                            set state = 'SKIPPED',
                                claim_token = null,
                                claimed_at = null,
                                next_action_at = null,
                                provider_error_code = 'USER_REFUSED',
                                provider_error_message = 'The user refused this WeChat service card',
                                updated_at = :updatedAt
                            where id = :deliveryId
                              and state in ('SENDING', 'RECONCILING')
                              and claimed_at < :cutoff
                            """)
                    .param("updatedAt", now)
                    .param("deliveryId", deliveryId)
                    .param("cutoff", cutoff)
                    .update();
            return;
        }
        jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = 'UNKNOWN',
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = :nextActionAt,
                            provider_error_code = 'ATTEMPT_OUTCOME_UNKNOWN',
                            provider_error_message = 'Previous provider attempt outcome is unknown',
                            updated_at = :updatedAt
                        where id = :deliveryId
                          and state in ('SENDING', 'RECONCILING')
                          and claimed_at < :cutoff
                        """)
                .param("nextActionAt", now)
                .param("updatedAt", now)
                .param("deliveryId", deliveryId)
                .param("cutoff", cutoff)
                .update();
    }

    public void markApplied(DeliveryClaim claim, Integer codeState, LocalDateTime expiresAt) {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            if (!lockClaim(claim)) {
                return;
            }
            int updated = finishDelivery(
                    claim, WechatServiceCardDeliveryState.SUCCEEDED,
                    "", "", null, now
            );
            if (updated != 1) {
                return;
            }
            WechatServiceCardStatus target = WechatServiceCardStatus.fromCode(claim.targetStatus());
            jdbcClient.sql("""
                            update wechat_service_card
                            set remote_status = :remoteStatus,
                                remote_code_state = coalesce(:codeState, remote_code_state),
                                remote_code_expire_at = coalesce(:expiresAt, remote_code_expire_at),
                                activated_at = case
                                    when activated_at is null and :activation then :activatedAt
                                    else activated_at
                                end,
                                terminal = :terminal,
                                version = version + 1,
                                updated_at = :updatedAt
                            where id = :cardId
                            """)
                    .param("remoteStatus", target.code())
                    .param("codeState", codeState)
                    .param("expiresAt", expiresAt)
                    .param("activation", target.activationAllowed() && claim.checkJson() != null)
                    .param("activatedAt", now)
                    .param("terminal", target.terminal())
                    .param("updatedAt", now)
                    .param("cardId", claim.cardId())
                    .update();
        });
    }

    /**
     * A prior reconciliation can prove that WeChat already reached this intent or a later queued
     * intent. Settle that local prefix under the same claim fence instead of sending an illegal
     * duplicate or backwards transition.
     */
    public boolean settleFromKnownRemote(DeliveryClaim claim) {
        if (claim.lastKnownRemoteStatus() == null) {
            return false;
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            LocalDateTime now = now();
            if (!lockClaim(claim)) {
                return false;
            }
            if (claim.lastKnownRemoteStatus() == claim.targetStatus()) {
                if (finishDelivery(
                        claim, WechatServiceCardDeliveryState.SUCCEEDED,
                        "", "", null, now
                ) != 1) {
                    return false;
                }
                WechatServiceCardStatus target = WechatServiceCardStatus.fromCode(
                        claim.targetStatus()
                );
                jdbcClient.sql("""
                                update wechat_service_card
                                set terminal = :terminal,
                                    activated_at = case
                                        when activated_at is null and :activation then :activatedAt
                                        else activated_at
                                    end,
                                    version = version + 1,
                                    updated_at = :updatedAt
                                where id = :cardId
                                """)
                        .param("terminal", target.terminal())
                        .param("activation", target.activationAllowed() && claim.checkJson() != null)
                        .param("activatedAt", now)
                        .param("updatedAt", now)
                        .param("cardId", claim.cardId())
                        .update();
                return true;
            }
            if (isLaterQueuedStatus(claim, claim.lastKnownRemoteStatus())) {
                return finishDelivery(
                        claim, WechatServiceCardDeliveryState.SKIPPED,
                        "REMOTE_SUPERSEDED", "Remote state already advanced beyond this intent",
                        null, now
                ) == 1;
            }
            return false;
        }));
    }

    public void markUnknown(DeliveryClaim claim, String errorCode, String errorMessage) {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            if (!lockClaim(claim)) {
                return;
            }
            if (finishSkippedIfBlocked(claim, now)) {
                return;
            }
            finishDelivery(
                    claim, WechatServiceCardDeliveryState.UNKNOWN,
                    errorCode, errorMessage,
                    now.plus(properties.reconciliationDelay(
                            claim.reconcileAttemptCount(), claim.deliveryId()
                    )), null
            );
        });
    }

    public void markRetry(DeliveryClaim claim, String errorCode, String errorMessage) {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            if (!lockClaim(claim)) {
                return;
            }
            if (finishSkippedIfBlocked(claim, now)) {
                return;
            }
            finishDelivery(
                    claim, WechatServiceCardDeliveryState.PENDING,
                    errorCode, errorMessage,
                    now.plus(properties.setRetryDelay(claim.attemptCount())), null
            );
        });
    }

    public void markFailed(DeliveryClaim claim, String errorCode, String errorMessage) {
        transaction.executeWithoutResult(status -> {
            if (!lockClaim(claim)) {
                return;
            }
            finishFailedAndSkipSuffix(claim, errorCode, errorMessage, now());
        });
    }

    public void blockUserRefused(long cardId) {
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = now();
            Long locked = jdbcClient.sql(
                            "select id from wechat_service_card where id = :cardId for update")
                    .param("cardId", cardId)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (locked != null) {
                blockUserRefusedInTransaction(cardId, now);
            }
        });
    }

    public boolean prepareProviderCall(DeliveryClaim claim) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            LocalDateTime now = now();
            if (!lockClaim(claim)) {
                return false;
            }
            return !finishSkippedIfBlocked(claim, now);
        }));
    }

    public void applyQueryResult(DeliveryClaim claim, WechatServiceCardQueryResult result) {
        if (result == null) {
            markUnknown(claim, "QUERY_UNAVAILABLE", "Provider reconciliation is unavailable");
            return;
        }
        if (result.outcome() == WechatServiceCardQueryResult.Outcome.RETRYABLE) {
            markUnknown(
                    claim,
                    queryErrorCode(result.errorCode(), result.errorMessage()),
                    diagnosticMessage(
                            result.errorMessage(), "Provider reconciliation is unavailable"
                    )
            );
            return;
        }
        if (result.outcome() == WechatServiceCardQueryResult.Outcome.REJECTED) {
            markFailed(claim, terminalErrorCode(result.errorCode()), terminalErrorMessage(result.errorCode()));
            return;
        }
        transaction.executeWithoutResult(status -> applyQueryInTransaction(claim, result));
    }

    private void skipQueuedSuffixAfterFailure(
            long cardId,
            long failedDeliveryId,
            LocalDateTime now
    ) {
        Integer failedSequence = jdbcClient.sql("""
                        select sequence_no
                        from wechat_service_card_delivery
                        where id = :deliveryId and card_id = :cardId
                        """)
                .param("deliveryId", failedDeliveryId)
                .param("cardId", cardId)
                .query(Integer.class)
                .single();
        jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = 'SKIPPED',
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = null,
                            provider_error_code = 'PREDECESSOR_FAILED',
                            provider_error_message = 'An earlier WeChat service-card update failed',
                            updated_at = :updatedAt
                        where card_id = :cardId
                          and id <> :failedDeliveryId
                          and sequence_no > :failedSequence
                          and state in ('PENDING', 'SENDING', 'UNKNOWN', 'RECONCILING')
                        """)
                .param("updatedAt", now)
                .param("cardId", cardId)
                .param("failedDeliveryId", failedDeliveryId)
                .param("failedSequence", failedSequence)
                .update();
    }

    private void finishFailedAndSkipSuffix(
            DeliveryClaim claim,
            String errorCode,
            String errorMessage,
            LocalDateTime now
    ) {
        int updated = finishDelivery(
                claim, WechatServiceCardDeliveryState.FAILED,
                errorCode, errorMessage, null, null
        );
        if (updated == 1) {
            skipQueuedSuffixAfterFailure(claim.cardId(), claim.deliveryId(), now);
        }
    }

    private void applyQueryInTransaction(
            DeliveryClaim claim,
            WechatServiceCardQueryResult result
    ) {
        LocalDateTime now = now();
        if (!lockClaim(claim)) {
            return;
        }
        Integer remoteStatus = result.remoteStatus();
        LocalDateTime expiresAt = result.expiresAt() == null
                ? null : LocalDateTime.ofInstant(result.expiresAt(), ZoneOffset.UTC);
        boolean activationProbe = claim.checkJson() != null && claim.activatedAt() == null;
        boolean missingActivatedState = remoteStatus == null && !activationProbe;
        boolean regressed = remoteStatus != null && remoteRegressed(claim, remoteStatus);
        jdbcClient.sql("""
                        update wechat_service_card
                        set remote_status = coalesce(:remoteStatus, remote_status),
                            remote_code_state = coalesce(:codeState, remote_code_state),
                            remote_code_expire_at = coalesce(:expiresAt, remote_code_expire_at),
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :cardId
                        """)
                .param("remoteStatus", missingActivatedState || regressed ? null : remoteStatus)
                .param("codeState", result.codeState())
                .param("expiresAt", expiresAt)
                .param("updatedAt", now)
                .param("cardId", claim.cardId())
                .update();

        if (result.codeState() != null && result.codeState() != 0) {
            String code = result.codeState() == 10 ? "REFUSED"
                    : result.codeState() == 1 ? "CODE_RISK" : "CODE_ABNORMAL";
            String message = result.codeState() == 10
                    ? "The user rejected the WeChat service card"
                    : "WeChat marked the notification code for manual review";
            int updated = finishDelivery(
                    claim, WechatServiceCardDeliveryState.FAILED,
                    code, message, null, null
            );
            if (result.codeState() == 10) {
                blockUserRefusedInTransaction(claim.cardId(), now);
            } else if (updated == 1) {
                skipQueuedSuffixAfterFailure(claim.cardId(), claim.deliveryId(), now);
            }
            return;
        }

        if (missingActivatedState) {
            if (finishSkippedIfBlocked(claim, now)) {
                return;
            }
            keepReconciliationUnknown(
                    claim, now, "REMOTE_STATE_INCONSISTENT",
                    "Remote query omitted the state of an activated service card"
            );
            return;
        }
        if (regressed) {
            if (finishSkippedIfBlocked(claim, now)) {
                return;
            }
            keepReconciliationUnknown(
                    claim, now, "REMOTE_STATE_REGRESSED",
                    "Remote query returned an older service-card state"
            );
            return;
        }

        if (remoteStatus != null && remoteStatus == claim.targetStatus()) {
            finishDelivery(
                    claim, WechatServiceCardDeliveryState.SUCCEEDED,
                    "", "", null, now
            );
            WechatServiceCardStatus target = WechatServiceCardStatus.fromCode(claim.targetStatus());
            jdbcClient.sql("""
                            update wechat_service_card
                            set terminal = :terminal,
                                activated_at = case
                                    when activated_at is null and :activation then :activatedAt
                                    else activated_at
                                end,
                                updated_at = :updatedAt
                            where id = :cardId
                            """)
                    .param("terminal", target.terminal())
                    .param("activation", target.activationAllowed() && claim.checkJson() != null)
                    .param("activatedAt", now)
                    .param("updatedAt", now)
                    .param("cardId", claim.cardId())
                    .update();
            return;
        }

        boolean legalPrevious = remoteStatus == null;
        if (remoteStatus != null) {
            try {
                legalPrevious = WechatServiceCardStatus.fromCode(claim.targetStatus())
                        .canFollow(WechatServiceCardStatus.fromCode(remoteStatus));
            } catch (IllegalArgumentException ignored) {
                legalPrevious = false;
            }
        }
        if (!legalPrevious) {
            if (remoteStatus != null && isLaterQueuedStatus(claim, remoteStatus)) {
                finishDelivery(
                        claim, WechatServiceCardDeliveryState.SKIPPED,
                        "REMOTE_SUPERSEDED", "Remote state already advanced beyond this intent", null, now
                );
                return;
            }
            finishFailedAndSkipSuffix(
                    claim,
                    "REMOTE_STATE_CONFLICT",
                    "Remote service-card state conflicts with the outbox",
                    now
            );
            return;
        }

        if (finishSkippedIfBlocked(claim, now)) {
            return;
        }

        int observations = claim.notAppliedObservations() + 1;
        if (observations >= properties.notAppliedConfirmations()) {
            jdbcClient.sql("""
                            update wechat_service_card_delivery
                            set state = 'PENDING',
                                claim_token = null,
                                claimed_at = null,
                                next_action_at = :nextActionAt,
                                not_applied_observations = 0,
                                last_reconciled_at = :reconciledAt,
                                provider_error_code = 'REMOTE_NOT_APPLIED',
                                provider_error_message = 'Remote state remained at a legal previous state',
                                updated_at = :updatedAt
                            where id = :deliveryId
                              and state = :expectedState
                              and claim_token = :claimToken
                            """)
                    .param("nextActionAt", now.plus(
                            properties.setRetryDelay(claim.attemptCount())))
                    .param("reconciledAt", now)
                    .param("updatedAt", now)
                    .param("deliveryId", claim.deliveryId())
                    .param("expectedState", claim.claimedState().name())
                    .param("claimToken", claim.claimToken())
                    .update();
            return;
        }
        jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = 'UNKNOWN',
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = :nextActionAt,
                            not_applied_observations = :observations,
                            last_reconciled_at = :reconciledAt,
                            provider_error_code = 'REMOTE_NOT_APPLIED',
                            provider_error_message = 'Remote state has not applied the target yet',
                            updated_at = :updatedAt
                        where id = :deliveryId
                          and state = :expectedState
                          and claim_token = :claimToken
                        """)
                .param("nextActionAt", now.plus(properties.reconciliationDelay(
                        claim.reconcileAttemptCount(), claim.deliveryId()
                )))
                .param("observations", observations)
                .param("reconciledAt", now)
                .param("updatedAt", now)
                .param("deliveryId", claim.deliveryId())
                .param("expectedState", claim.claimedState().name())
                .param("claimToken", claim.claimToken())
                .update();
    }

    private void keepReconciliationUnknown(
            DeliveryClaim claim,
            LocalDateTime now,
            String errorCode,
            String errorMessage
    ) {
        finishDelivery(
                claim, WechatServiceCardDeliveryState.UNKNOWN,
                errorCode, errorMessage,
                now.plus(properties.reconciliationDelay(
                        claim.reconcileAttemptCount(), claim.deliveryId()
                )), null
        );
    }

    private boolean remoteRegressed(DeliveryClaim claim, int remoteStatus) {
        if (claim.lastKnownRemoteStatus() == null
                || claim.lastKnownRemoteStatus() == remoteStatus
                || claim.targetStatus() == remoteStatus
                || isLaterQueuedStatus(claim, remoteStatus)) {
            return false;
        }
        try {
            WechatServiceCardStatus known = WechatServiceCardStatus.fromCode(
                    claim.lastKnownRemoteStatus()
            );
            WechatServiceCardStatus observed = WechatServiceCardStatus.fromCode(remoteStatus);
            return !observed.canFollow(known);
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }

    private void blockUserRefusedInTransaction(long cardId, LocalDateTime now) {
        jdbcClient.sql("""
                        update wechat_service_card
                        set send_blocked = true,
                            send_block_reason = 'USER_REFUSED',
                            send_blocked_at = coalesce(send_blocked_at, :blockedAt),
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :cardId and send_blocked = false
                        """)
                .param("blockedAt", now)
                .param("updatedAt", now)
                .param("cardId", cardId)
                .update();
        jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = 'SKIPPED',
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = null,
                            provider_error_code = 'USER_REFUSED',
                            provider_error_message = 'The user refused this WeChat service card',
                            updated_at = :updatedAt
                        where card_id = :cardId
                          and state in ('PENDING', 'UNKNOWN')
                        """)
                .param("updatedAt", now)
                .param("cardId", cardId)
                .update();
    }

    private boolean finishSkippedIfBlocked(DeliveryClaim claim, LocalDateTime now) {
        Boolean blocked = jdbcClient.sql(
                        "select send_blocked from wechat_service_card where id = :cardId")
                .param("cardId", claim.cardId())
                .query(Boolean.class)
                .optional()
                .orElse(null);
        if (!Boolean.TRUE.equals(blocked)) {
            return false;
        }
        return finishDelivery(
                claim, WechatServiceCardDeliveryState.SKIPPED,
                "USER_REFUSED", "The user refused this WeChat service card", null, now
        ) == 1;
    }

    private int finishDelivery(
            DeliveryClaim claim,
            WechatServiceCardDeliveryState targetState,
            String errorCode,
            String errorMessage,
            LocalDateTime nextActionAt,
            LocalDateTime appliedAt
    ) {
        LocalDateTime now = now();
        return jdbcClient.sql("""
                        update wechat_service_card_delivery
                        set state = :targetState,
                            claim_token = null,
                            claimed_at = null,
                            next_action_at = :nextActionAt,
                            provider_error_code = :errorCode,
                            provider_error_message = :errorMessage,
                            applied_at = coalesce(:appliedAt, applied_at),
                            last_reconciled_at = case
                                when :reconciled then :updatedAt
                                else last_reconciled_at
                            end,
                            updated_at = :updatedAt
                        where id = :deliveryId
                          and state = :expectedState
                          and claim_token = :claimToken
                        """)
                .param("targetState", targetState.name())
                .param("nextActionAt", nextActionAt)
                .param("errorCode", safe(errorCode, 64))
                .param("errorMessage", safe(errorMessage, 255))
                .param("appliedAt", appliedAt)
                .param("reconciled", claim.claimedState() == WechatServiceCardDeliveryState.RECONCILING)
                .param("updatedAt", now)
                .param("deliveryId", claim.deliveryId())
                .param("expectedState", claim.claimedState().name())
                .param("claimToken", claim.claimToken())
                .update();
    }

    private boolean lockClaim(DeliveryClaim claim) {
        Long lockedCard = jdbcClient.sql("""
                        select id from wechat_service_card where id = :cardId for update
                        """)
                .param("cardId", claim.cardId())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (lockedCard == null) {
            return false;
        }
        return jdbcClient.sql("""
                        select id
                        from wechat_service_card_delivery
                        where id = :deliveryId
                          and card_id = :cardId
                          and state = :expectedState
                          and claim_token = :claimToken
                        for update
                        """)
                .param("deliveryId", claim.deliveryId())
                .param("cardId", claim.cardId())
                .param("expectedState", claim.claimedState().name())
                .param("claimToken", claim.claimToken())
                .query(Long.class)
                .optional()
                .isPresent();
    }

    private boolean isLaterQueuedStatus(DeliveryClaim claim, int remoteStatus) {
        return jdbcClient.sql("""
                        select count(*)
                        from wechat_service_card_delivery current_delivery
                        join wechat_service_card_delivery later
                          on later.card_id = current_delivery.card_id
                         and later.sequence_no > current_delivery.sequence_no
                        where current_delivery.id = :deliveryId
                          and later.target_status = :remoteStatus
                        """)
                .param("deliveryId", claim.deliveryId())
                .param("remoteStatus", remoteStatus)
                .query(Long.class)
                .single() > 0L;
    }

    private DeliveryClaim loadClaim(
            long deliveryId,
            String token,
            WechatServiceCardDeliveryState claimedState
    ) {
        return jdbcClient.sql("""
                        select delivery.id, delivery.card_id, card.order_id, card.payment_order_id,
                               delivery.target_status, delivery.content_json, delivery.check_json,
                               delivery.attempt_count, delivery.reconcile_attempt_count,
                               delivery.not_applied_observations,
                               payment.transaction_id, payment.payer_openid, payment.amount_cent,
                               payment.paid_at, payment.payment_config_id,
                               payment.payment_config_fingerprint,
                               coalesce(db_config.app_id, env_snapshot.app_id) as payment_app_id,
                               card.activated_at, card.remote_code_expire_at,
                               card.remote_status
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        join payment_order payment on payment.id = card.payment_order_id
                        left join payment_config db_config
                          on db_config.id = payment.payment_config_id
                        left join payment_config_snapshot env_snapshot
                          on payment.payment_config_id is null
                         and env_snapshot.fingerprint = payment.payment_config_fingerprint
                        where delivery.id = :deliveryId
                          and delivery.state = :claimedState
                          and delivery.claim_token = :claimToken
                        """)
                .param("deliveryId", deliveryId)
                .param("claimedState", claimedState.name())
                .param("claimToken", token)
                .query((rs, rowNum) -> mapClaim(rs, token, claimedState))
                .single();
    }

    private DeliveryClaim mapClaim(
            ResultSet rs,
            String token,
            WechatServiceCardDeliveryState claimedState
    ) throws SQLException {
        return new DeliveryClaim(
                rs.getLong("id"), rs.getLong("card_id"), rs.getLong("order_id"),
                rs.getLong("payment_order_id"), rs.getInt("target_status"),
                rs.getString("content_json"), rs.getString("check_json"),
                rs.getInt("attempt_count"), rs.getInt("reconcile_attempt_count"),
                rs.getInt("not_applied_observations"),
                token, claimedState,
                rs.getString("transaction_id"), rs.getString("payer_openid"),
                rs.getLong("amount_cent"), rs.getObject("paid_at", LocalDateTime.class),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint"),
                rs.getString("payment_app_id"),
                rs.getObject("activated_at", LocalDateTime.class),
                rs.getObject("remote_code_expire_at", LocalDateTime.class),
                rs.getObject("remote_status", Integer.class)
        );
    }

    private boolean hasBlockingEarlier(long cardId, long deliveryId) {
        return jdbcClient.sql("""
                        select count(*)
                        from wechat_service_card_delivery current_delivery
                        join wechat_service_card_delivery earlier
                          on earlier.card_id = current_delivery.card_id
                         and earlier.sequence_no < current_delivery.sequence_no
                        where current_delivery.id = :deliveryId
                          and current_delivery.card_id = :cardId
                          and earlier.state not in ('SUCCEEDED', 'SKIPPED')
                        """)
                .param("deliveryId", deliveryId)
                .param("cardId", cardId)
                .query(Long.class)
                .single() > 0L;
    }

    private static String terminalErrorCode(Integer providerCode) {
        if (providerCode == null) {
            return "PROVIDER_REJECTED";
        }
        return switch (providerCode) {
            case 85438 -> "EXPIRED";
            case 85442 -> "CONTENT_SECURITY_REJECTED";
            case 85461, 85462 -> "CONFIGURATION_BLOCKED";
            case 85433, 85434, 85435, 85439, 85440, 85441, 85443 -> "PAYLOAD_REJECTED";
            case 40003, 85436 -> "IDENTITY_REJECTED";
            default -> "PROVIDER_REJECTED";
        };
    }

    private static String queryErrorCode(Integer providerCode, String providerMessage) {
        if (providerCode != null) {
            return "WECHAT_" + providerCode;
        }
        return switch (providerMessage == null ? "" : providerMessage) {
            case "WeChat access token is unavailable" -> "ACCESS_TOKEN_UNAVAILABLE";
            case "WeChat get_user_notify is unavailable" -> "QUERY_TRANSPORT_UNAVAILABLE";
            case "WeChat get_user_notify response is invalid" -> "QUERY_RESPONSE_INVALID";
            case "WeChat notify_info is invalid" -> "QUERY_NOTIFY_INFO_INVALID";
            case "WeChat code_state is invalid" -> "QUERY_CODE_STATE_INVALID";
            case "WeChat content_json is invalid" -> "QUERY_CONTENT_INVALID";
            case "WeChat content_json status is invalid" -> "QUERY_STATUS_INVALID";
            case "WeChat code_expire_time is invalid" -> "QUERY_EXPIRY_INVALID";
            default -> "QUERY_UNAVAILABLE";
        };
    }

    private static String diagnosticMessage(String providerMessage, String fallback) {
        return providerMessage == null || providerMessage.isBlank() ? fallback : providerMessage;
    }

    private static String terminalErrorMessage(Integer providerCode) {
        return switch (terminalErrorCode(providerCode)) {
            case "EXPIRED" -> "The WeChat service-card update window expired";
            case "CONTENT_SECURITY_REJECTED" -> "WeChat content security rejected the service card";
            case "CONFIGURATION_BLOCKED" -> "WeChat service-card capability is not authorized";
            case "PAYLOAD_REJECTED" -> "WeChat rejected the service-card payload or transition";
            case "IDENTITY_REJECTED" -> "WeChat rejected the payment notification identity";
            default -> "WeChat rejected the service-card request";
        };
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private static String safe(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record Candidate(
            long id,
            long cardId,
            String state,
            int attemptCount,
            LocalDateTime nextActionAt
    ) {
    }

    public record DeliveryClaim(
            long deliveryId,
            long cardId,
            long orderId,
            long paymentOrderId,
            int targetStatus,
            String contentJson,
            String checkJson,
            int attemptCount,
            int reconcileAttemptCount,
            int notAppliedObservations,
            String claimToken,
            WechatServiceCardDeliveryState claimedState,
            String transactionId,
            String payerOpenid,
            long amountCent,
            LocalDateTime paidAt,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String paymentAppId,
            LocalDateTime activatedAt,
            LocalDateTime remoteCodeExpireAt,
            Integer lastKnownRemoteStatus
    ) {
        public WechatServiceCardPayloadFactory.PaymentSnapshot paymentSnapshot() {
            return new WechatServiceCardPayloadFactory.PaymentSnapshot(
                    paymentOrderId, transactionId, payerOpenid, amountCent, paidAt,
                    paymentConfigId, paymentConfigFingerprint, paymentAppId
            );
        }
    }
}
