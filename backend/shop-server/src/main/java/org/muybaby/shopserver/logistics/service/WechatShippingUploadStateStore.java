package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingDeliveryProperties;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class WechatShippingUploadStateStore {

    private static final int MAX_ITEM_DESC_CODE_POINTS = 120;
    private static final String SF_DELIVERY_ID = "SF";
    static final String STALE_ERROR_CODE = "ATTEMPT_OUTCOME_UNKNOWN";
    static final String STALE_ERROR_MESSAGE = "Previous WeChat shipping attempt outcome is unknown";

    private final JdbcClient jdbcClient;
    private final WechatShippingDeliveryProperties properties;
    private final Clock clock;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public WechatShippingUploadStateStore(
            JdbcClient jdbcClient,
            WechatShippingDeliveryProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
        this.clock = clock;
        this.required = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<UploadClaim> claimInitial(long shipmentId, LocalDateTime now) {
        return claimUpload(
                shipmentId,
                List.of(WechatShippingUploadStatus.PENDING.name()),
                false,
                false,
                false,
                now
        );
    }

    public Optional<UploadClaim> claimScheduled(long shipmentId, LocalDateTime now) {
        return claimUpload(
                shipmentId,
                List.of(
                        WechatShippingUploadStatus.PENDING.name(),
                        WechatShippingUploadStatus.UNAVAILABLE.name()
                ),
                true,
                false,
                true,
                now
        );
    }

    public UploadClaim claimOperatorRetry(long orderId, boolean uploadEnabled, LocalDateTime now) {
        return Objects.requireNonNull(required.execute(status -> {
            if (!uploadEnabled) {
                throw conflict();
            }
            lockShippedOrder(orderId);
            RetryCandidate candidate = loadRetryCandidateForUpdate(orderId);
            if (!candidate.reconstructable()
                    || !List.of(
                    WechatShippingUploadStatus.FAILED.name(),
                    WechatShippingUploadStatus.UNAVAILABLE.name(),
                    WechatShippingUploadStatus.SKIPPED.name()
            ).contains(candidate.uploadStatus())) {
                throw conflict();
            }
            return claimLocked(candidate, true, now);
        }));
    }

    private Optional<UploadClaim> claimUpload(
            long shipmentId,
            List<String> claimableStatuses,
            boolean requireDue,
            boolean operatorRetry,
            boolean enforceAttemptLimit,
            LocalDateTime now
    ) {
        Optional<UploadClaim> claim = required.execute(status -> {
            Long orderId = jdbcClient.sql("select order_id from order_shipment where id = :shipmentId")
                    .param("shipmentId", shipmentId)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (orderId == null || !isShippedOrderLocked(orderId)) {
                return Optional.empty();
            }
            RetryCandidate candidate = loadRetryCandidateByShipmentForUpdate(shipmentId);
            if (candidate == null
                    || !candidate.reconstructable()
                    || !claimableStatuses.contains(candidate.uploadStatus())
                    || (enforceAttemptLimit
                    && candidate.attemptCount() >= properties.maxAttempts())
                    || (requireDue
                    && candidate.nextActionAt() != null
                    && candidate.nextActionAt().isAfter(now))) {
                return Optional.empty();
            }
            return Optional.of(claimLocked(candidate, operatorRetry, now));
        });
        return claim == null ? Optional.empty() : claim;
    }

    private UploadClaim claimLocked(RetryCandidate candidate, boolean operatorRetry, LocalDateTime now) {
        String claimToken = UUID.randomUUID().toString();
        int claimed = jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :uploading,
                            wechat_error_code = '',
                            wechat_error_message = '',
                            wechat_upload_claim_token = :claimToken,
                            wechat_upload_claimed_at = :claimedAt,
                            wechat_upload_next_action_at = null,
                            wechat_upload_attempt_count = wechat_upload_attempt_count + 1,
                            retry_count = retry_count + :retryIncrement,
                            last_attempt_at = :now,
                            updated_at = :now
                        where id = :shipmentId
                          and wechat_upload_status = :expectedStatus
                        """)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("claimToken", claimToken)
                .param("claimedAt", now)
                .param("retryIncrement", operatorRetry ? 1 : 0)
                .param("now", now)
                .param("shipmentId", candidate.shipmentId())
                .param("expectedStatus", candidate.uploadStatus())
                .update();
        if (claimed != 1) {
            throw conflict();
        }
        return new UploadClaim(candidate.shipmentId(), claimToken, candidate.attemptCount() + 1);
    }

    public AttemptContext prepareAttempt(UploadClaim claim, WechatProviderMode providerMode) {
        Long orderId = findOrderId(claim.shipmentId());
        if (orderId == null) {
            throw conflict();
        }
        return Objects.requireNonNull(required.execute(status -> {
            lockOrder(orderId);
            AttemptContext current = loadAttemptContext(claim);
            String uploadTime = nextUploadTime(current.uploadTime());
            LocalDateTime now = now();
            int updated = jdbcClient.sql("""
                            update order_shipment
                            set upload_time = :uploadTime,
                                wechat_provider_mode = :providerMode,
                                last_attempt_at = :now,
                                updated_at = :now
                            where id = :shipmentId
                              and wechat_upload_status = :uploading
                              and wechat_upload_claim_token = :claimToken
                            """)
                    .param("uploadTime", uploadTime)
                    .param("providerMode", providerMode.name())
                    .param("now", now)
                    .param("shipmentId", claim.shipmentId())
                    .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                    .param("claimToken", claim.claimToken())
                    .update();
            if (updated != 1) {
                throw conflict();
            }
            return current.withUploadTime(uploadTime);
        }));
    }

    public boolean writeTerminal(
            UploadClaim claim,
            WechatProviderMode providerMode,
            WechatShippingUploadStatus uploadStatus,
            String errorCode,
            String errorMessage,
            LocalDateTime attemptedAt,
            LocalDateTime uploadedAt
    ) {
        Long orderId = findOrderId(claim.shipmentId());
        if (orderId == null) {
            return false;
        }
        LocalDateTime nextActionAt;
        if (uploadStatus == WechatShippingUploadStatus.UNAVAILABLE
                && claim.attemptCount() < properties.maxAttempts()) {
            nextActionAt = attemptedAt.plus(properties.retryDelay(claim.attemptCount()));
        } else if (uploadStatus == WechatShippingUploadStatus.UNKNOWN) {
            nextActionAt = attemptedAt.plus(properties.unknownRecheckInterval());
        } else {
            nextActionAt = null;
        }
        return Boolean.TRUE.equals(required.execute(status -> {
            lockOrder(orderId);
            return jdbcClient.sql("""
                        update order_shipment
                        set wechat_provider_mode = :providerMode,
                            wechat_upload_status = :uploadStatus,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = :uploadedAt,
                            wechat_upload_claim_token = null,
                            wechat_upload_claimed_at = null,
                            wechat_upload_next_action_at = :nextActionAt,
                            wechat_upload_not_uploaded_observations = case
                                when :uploaded then 0
                                else wechat_upload_not_uploaded_observations
                            end,
                            last_attempt_at = :attemptedAt,
                            updated_at = :attemptedAt
                        where id = :shipmentId
                          and wechat_upload_status = :uploading
                          and wechat_upload_claim_token = :claimToken
                        """)
                .param("providerMode", providerMode.name())
                .param("uploadStatus", uploadStatus.name())
                .param("errorCode", defaultString(errorCode))
                .param("errorMessage", defaultString(errorMessage))
                .param("uploadedAt", uploadedAt)
                .param("nextActionAt", nextActionAt)
                .param("uploaded", uploadStatus == WechatShippingUploadStatus.UPLOADED)
                .param("attemptedAt", attemptedAt)
                .param("shipmentId", claim.shipmentId())
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("claimToken", claim.claimToken())
                .update() == 1;
        }));
    }

    public void fallbackUnknown(
            UploadClaim claim,
            WechatProviderMode providerMode,
            LocalDateTime now
    ) {
        Long orderId = findOrderId(claim.shipmentId());
        if (orderId == null) {
            return;
        }
        requiresNew.executeWithoutResult(status -> {
            lockOrder(orderId);
            jdbcClient.sql("""
                        update order_shipment
                        set wechat_provider_mode = :providerMode,
                            wechat_upload_status = :unknown,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = null,
                            wechat_upload_claim_token = null,
                            wechat_upload_claimed_at = null,
                            wechat_upload_next_action_at = :nextActionAt,
                            last_attempt_at = :now,
                            updated_at = :now
                        where id = :shipmentId
                          and wechat_upload_status = :uploading
                          and wechat_upload_claim_token = :claimToken
                        """)
                .param("providerMode", providerMode.name())
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("errorCode", STALE_ERROR_CODE)
                .param("errorMessage", STALE_ERROR_MESSAGE)
                .param("nextActionAt", now.plus(properties.unknownRecheckInterval()))
                .param("now", now)
                .param("shipmentId", claim.shipmentId())
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("claimToken", claim.claimToken())
                .update();
        });
    }

    public List<Long> findDueUploadShipmentIds(LocalDateTime now, int batchSize) {
        return jdbcClient.sql("""
                        select sh.id
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        where o.status = 'SHIPPED'
                          and sh.wechat_upload_status in (:pending, :unavailable)
                          and sh.wechat_upload_attempt_count < :maxAttempts
                          and coalesce(sh.wechat_upload_next_action_at, sh.created_at) <= :now
                        order by coalesce(sh.wechat_upload_next_action_at, sh.created_at), sh.id
                        limit :batchSize
                        """)
                .param("pending", WechatShippingUploadStatus.PENDING.name())
                .param("unavailable", WechatShippingUploadStatus.UNAVAILABLE.name())
                .param("maxAttempts", properties.maxAttempts())
                .param("now", now)
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
    }

    public List<Long> findDueUnknownShipmentIds(LocalDateTime now, int batchSize) {
        return jdbcClient.sql("""
                        select sh.id
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        where o.status = 'SHIPPED'
                          and sh.wechat_provider_mode = 'REAL'
                          and sh.wechat_upload_status = :unknown
                          and coalesce(sh.wechat_upload_next_action_at, sh.updated_at, sh.created_at) <= :now
                          and (
                              sh.wechat_upload_claim_token is null
                              or sh.wechat_upload_claimed_at is null
                              or sh.wechat_upload_claimed_at <= :staleBefore
                          )
                        order by coalesce(sh.wechat_upload_next_action_at, sh.updated_at, sh.created_at), sh.id
                        limit :batchSize
                        """)
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("now", now)
                .param("staleBefore", now.minus(properties.claimTimeout()))
                .param("batchSize", batchSize)
                .query(Long.class)
                .list();
    }

    public Optional<UnknownClaim> claimUnknownByShipment(
            long shipmentId,
            boolean force,
            LocalDateTime now
    ) {
        Optional<UnknownClaim> claimed = required.execute(status -> {
            Long orderId = jdbcClient.sql("select order_id from order_shipment where id = :shipmentId")
                    .param("shipmentId", shipmentId)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (orderId == null || !isShippedOrderLocked(orderId)) {
                return Optional.empty();
            }
            UnknownCandidate candidate = loadUnknownCandidateForUpdate(shipmentId);
            if (candidate == null
                    || candidate.providerMode() != WechatProviderMode.REAL
                    || candidate.uploadStatus() != WechatShippingUploadStatus.UNKNOWN
                    || (!force
                    && candidate.nextActionAt() != null
                    && candidate.nextActionAt().isAfter(now))
                    || (StringUtils.hasText(candidate.claimToken())
                    && candidate.claimedAt() != null
                    && candidate.claimedAt().isAfter(now.minus(properties.claimTimeout())))) {
                return Optional.empty();
            }
            PaymentIdentity identity = loadPaymentIdentity(orderId);
            String token = UUID.randomUUID().toString();
            int updated = jdbcClient.sql("""
                            update order_shipment
                            set wechat_upload_claim_token = :claimToken,
                                wechat_upload_claimed_at = :claimedAt,
                                wechat_upload_next_action_at = null,
                                updated_at = :updatedAt
                            where id = :shipmentId
                              and wechat_upload_status = :unknown
                            """)
                    .param("claimToken", token)
                    .param("claimedAt", now)
                    .param("updatedAt", now)
                    .param("shipmentId", shipmentId)
                    .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                    .update();
            if (updated != 1) {
                return Optional.empty();
            }
            return Optional.of(new UnknownClaim(
                    shipmentId,
                    orderId,
                    token,
                    candidate.logisticsType(),
                    candidate.deliveryMode(),
                    candidate.expressCompanyCode(),
                    candidate.trackingNo(),
                    identity.transactionId(),
                    candidate.notUploadedObservations(),
                    candidate.lastReconciledAt()
            ));
        });
        return claimed == null ? Optional.empty() : claimed;
    }

    public Optional<UnknownClaim> claimUnknownByOrder(long orderId, LocalDateTime now) {
        Long shipmentId = jdbcClient.sql("select id from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(this::conflict);
        return claimUnknownByShipment(shipmentId, true, now);
    }

    public boolean markReconciledUploaded(UnknownClaim claim, LocalDateTime now) {
        return Boolean.TRUE.equals(required.execute(status -> {
            lockOrder(claim.orderId());
            return jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :uploaded,
                            wechat_error_code = '',
                            wechat_error_message = '',
                            wechat_uploaded_at = coalesce(wechat_uploaded_at, :uploadedAt),
                            wechat_upload_claim_token = null,
                            wechat_upload_claimed_at = null,
                            wechat_upload_next_action_at = null,
                            wechat_upload_not_uploaded_observations = 0,
                            wechat_upload_last_reconciled_at = :reconciledAt,
                            updated_at = :updatedAt
                        where id = :shipmentId
                          and wechat_upload_status = :unknown
                          and wechat_upload_claim_token = :claimToken
                        """)
                .param("uploaded", WechatShippingUploadStatus.UPLOADED.name())
                .param("uploadedAt", now)
                .param("reconciledAt", now)
                .param("updatedAt", now)
                .param("shipmentId", claim.shipmentId())
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("claimToken", claim.claimToken())
                .update() == 1;
        }));
    }

    public boolean recordDefinitiveNotUploaded(UnknownClaim claim, LocalDateTime now) {
        boolean sufficientlySpaced = claim.lastReconciledAt() == null
                || !claim.lastReconciledAt().isAfter(now.minus(properties.unknownRecheckInterval()));
        int observations = sufficientlySpaced
                ? claim.notUploadedObservations() + 1
                : claim.notUploadedObservations();
        boolean confirmed = observations >= properties.notUploadedConfirmations();
        WechatShippingUploadStatus nextStatus = confirmed
                ? WechatShippingUploadStatus.PENDING
                : WechatShippingUploadStatus.UNKNOWN;
        LocalDateTime nextActionAt = confirmed
                ? now
                : now.plus(properties.unknownRecheckInterval());
        return completeUnknownClaim(
                claim,
                nextStatus,
                "REMOTE_NOT_UPLOADED",
                "WeChat reports that shipping information has not been uploaded",
                observations,
                nextActionAt,
                now
        );
    }

    public boolean recordReconciliationUnknown(
            UnknownClaim claim,
            String errorCode,
            String errorMessage,
            LocalDateTime now
    ) {
        return completeUnknownClaim(
                claim,
                WechatShippingUploadStatus.UNKNOWN,
                errorCode,
                errorMessage,
                claim.notUploadedObservations(),
                now.plus(properties.unknownRecheckInterval()),
                now
        );
    }

    private boolean completeUnknownClaim(
            UnknownClaim claim,
            WechatShippingUploadStatus nextStatus,
            String errorCode,
            String errorMessage,
            int observations,
            LocalDateTime nextActionAt,
            LocalDateTime now
    ) {
        return Boolean.TRUE.equals(required.execute(status -> {
            lockOrder(claim.orderId());
            return jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :nextStatus,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_upload_claim_token = null,
                            wechat_upload_claimed_at = null,
                            wechat_upload_next_action_at = :nextActionAt,
                            wechat_upload_not_uploaded_observations = :observations,
                            wechat_upload_last_reconciled_at = :reconciledAt,
                            updated_at = :updatedAt
                        where id = :shipmentId
                          and wechat_upload_status = :unknown
                          and wechat_upload_claim_token = :claimToken
                        """)
                .param("nextStatus", nextStatus.name())
                .param("errorCode", defaultString(errorCode))
                .param("errorMessage", defaultString(errorMessage))
                .param("nextActionAt", nextActionAt)
                .param("observations", observations)
                .param("reconciledAt", now)
                .param("updatedAt", now)
                .param("shipmentId", claim.shipmentId())
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("claimToken", claim.claimToken())
                .update() == 1;
        }));
    }

    public boolean reconcileStaleByOrder(long orderId, LocalDateTime now) {
        return Boolean.TRUE.equals(required.execute(status -> {
            lockOrder(orderId);
            return reconcileByPredicate("order_id = :identifier", orderId, now);
        }));
    }

    public boolean reconcileStaleByShipment(long shipmentId, LocalDateTime now) {
        Long orderId = findOrderId(shipmentId);
        if (orderId == null) {
            return false;
        }
        return Boolean.TRUE.equals(required.execute(status -> {
            lockOrder(orderId);
            return reconcileByPredicate("id = :identifier", shipmentId, now);
        }));
    }

    public int reconcileStaleBatch(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.claimTimeout());
        List<Long> ids = jdbcClient.sql("""
                            select id
                            from order_shipment
                            where wechat_upload_status = :uploading
                              and coalesce(wechat_upload_claimed_at, last_attempt_at, updated_at, created_at) < :cutoff
                            order by coalesce(wechat_upload_claimed_at, last_attempt_at, updated_at, created_at), id
                            limit :batchSize
                            """)
                    .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                    .param("cutoff", cutoff)
                    .param("batchSize", properties.batchSize())
                    .query(Long.class)
                    .list();
        int reconciled = 0;
        for (Long id : ids) {
            if (reconcileStaleByShipment(id, now)) {
                reconciled++;
            }
        }
        return reconciled;
    }

    private boolean reconcileByPredicate(String predicate, long identifier, LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.claimTimeout());
        return jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :unknown,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = null,
                            wechat_upload_claim_token = null,
                            wechat_upload_claimed_at = null,
                            wechat_upload_next_action_at = :nextActionAt,
                            last_attempt_at = :now,
                            updated_at = :now
                        where %s
                          and wechat_upload_status = :uploading
                          and coalesce(wechat_upload_claimed_at, last_attempt_at, updated_at, created_at) < :cutoff
                        """.formatted(predicate))
                .param("identifier", identifier)
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("errorCode", STALE_ERROR_CODE)
                .param("errorMessage", STALE_ERROR_MESSAGE)
                .param("nextActionAt", now.plus(properties.unknownRecheckInterval()))
                .param("now", now)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("cutoff", cutoff)
                .update() == 1;
    }

    private AttemptContext loadAttemptContext(UploadClaim claim) {
        AttemptContext shipment = jdbcClient.sql("""
                        select sh.id as shipment_id, sh.order_id, sh.logistics_type, sh.delivery_mode,
                               sh.item_desc, sh.express_company_code, sh.tracking_no,
                               sh.consignor_contact, sh.receiver_contact, sh.upload_time
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        where sh.id = :shipmentId
                          and o.status = 'SHIPPED'
                          and sh.wechat_upload_status = :uploading
                          and sh.wechat_upload_claim_token = :claimToken
                        """)
                .param("shipmentId", claim.shipmentId())
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("claimToken", claim.claimToken())
                .query(this::mapAttemptContext)
                .optional()
                .orElseThrow(this::conflict);
        PaymentIdentity paymentIdentity = loadPaymentIdentity(shipment.orderId());
        return shipment.withPaymentIdentity(
                paymentIdentity.transactionId(), paymentIdentity.payerOpenid()
        );
    }

    private PaymentIdentity loadPaymentIdentity(long orderId) {
        return jdbcClient.sql("""
                        select transaction_id, payer_openid
                        from payment_order
                        where order_id = :orderId and status = 'PAID'
                        order by updated_at desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentIdentity(
                        defaultString(rs.getString("transaction_id")),
                        defaultString(rs.getString("payer_openid"))
                ))
                .optional()
                .orElse(new PaymentIdentity("", ""));
    }

    private AttemptContext mapAttemptContext(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptContext(
                rs.getLong("shipment_id"),
                rs.getLong("order_id"),
                LogisticsType.fromValue(rs.getInt("logistics_type")),
                DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("tracking_no"),
                rs.getString("consignor_contact"),
                rs.getString("receiver_contact"),
                "",
                "",
                rs.getString("upload_time")
        );
    }

    private RetryCandidate loadRetryCandidateForUpdate(long orderId) {
        return jdbcClient.sql("""
                        select id, logistics_type, delivery_mode, item_desc,
                               express_company_code, tracking_no,
                               consignor_contact, receiver_contact, status,
                               wechat_upload_status, wechat_upload_next_action_at,
                               wechat_upload_attempt_count
                        from order_shipment
                        where order_id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapRetryCandidate)
                .optional()
                .orElseThrow(this::conflict);
    }

    private RetryCandidate loadRetryCandidateByShipmentForUpdate(long shipmentId) {
        return jdbcClient.sql("""
                        select id, logistics_type, delivery_mode, item_desc,
                               express_company_code, tracking_no,
                               consignor_contact, receiver_contact, status,
                               wechat_upload_status, wechat_upload_next_action_at,
                               wechat_upload_attempt_count
                        from order_shipment
                        where id = :shipmentId
                        for update
                        """)
                .param("shipmentId", shipmentId)
                .query(this::mapRetryCandidate)
                .optional()
                .orElse(null);
    }

    private RetryCandidate mapRetryCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new RetryCandidate(
                rs.getLong("id"),
                rs.getInt("logistics_type"),
                rs.getInt("delivery_mode"),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("tracking_no"),
                rs.getString("consignor_contact"),
                rs.getString("receiver_contact"),
                rs.getString("status"),
                rs.getString("wechat_upload_status"),
                rs.getObject("wechat_upload_next_action_at", LocalDateTime.class),
                rs.getInt("wechat_upload_attempt_count")
        );
    }

    private UnknownCandidate loadUnknownCandidateForUpdate(long shipmentId) {
        return jdbcClient.sql("""
                        select logistics_type, delivery_mode, express_company_code, tracking_no,
                               wechat_provider_mode, wechat_upload_status,
                               wechat_upload_claim_token, wechat_upload_claimed_at,
                               wechat_upload_next_action_at,
                               wechat_upload_not_uploaded_observations,
                               wechat_upload_last_reconciled_at
                        from order_shipment
                        where id = :shipmentId
                        for update
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new UnknownCandidate(
                        LogisticsType.fromValue(rs.getInt("logistics_type")),
                        DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                        defaultString(rs.getString("express_company_code")),
                        defaultString(rs.getString("tracking_no")),
                        providerMode(rs.getString("wechat_provider_mode")),
                        uploadStatus(rs.getString("wechat_upload_status")),
                        rs.getString("wechat_upload_claim_token"),
                        rs.getObject("wechat_upload_claimed_at", LocalDateTime.class),
                        rs.getObject("wechat_upload_next_action_at", LocalDateTime.class),
                        rs.getInt("wechat_upload_not_uploaded_observations"),
                        rs.getObject("wechat_upload_last_reconciled_at", LocalDateTime.class)
                ))
                .optional()
                .orElse(null);
    }

    private void lockShippedOrder(long orderId) {
        if (!isShippedOrderLocked(orderId)) {
            throw conflict();
        }
    }

    private void lockOrder(long orderId) {
        boolean exists = jdbcClient.sql("select id from shop_order where id = :orderId for update")
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .isPresent();
        if (!exists) {
            throw conflict();
        }
    }

    private Long findOrderId(long shipmentId) {
        return jdbcClient.sql("select order_id from order_shipment where id = :shipmentId")
                .param("shipmentId", shipmentId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private boolean isShippedOrderLocked(long orderId) {
        return jdbcClient.sql("select status from shop_order where id = :orderId for update")
                .param("orderId", orderId)
                .query(String.class)
                .optional()
                .filter("SHIPPED"::equals)
                .isPresent();
    }

    private String nextUploadTime(String previous) {
        OffsetDateTime candidate = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        if (StringUtils.hasText(previous)) {
            try {
                OffsetDateTime previousTime = OffsetDateTime.parse(previous, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (!candidate.isAfter(previousTime)) {
                    candidate = previousTime.plusNanos(1);
                }
            } catch (DateTimeParseException ignored) {
                // A malformed legacy value is safely replaced before any dispatch.
            }
        }
        return candidate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private WechatProviderMode providerMode(String value) {
        try {
            return WechatProviderMode.valueOf(value);
        } catch (RuntimeException ex) {
            return WechatProviderMode.UNKNOWN;
        }
    }

    private WechatShippingUploadStatus uploadStatus(String value) {
        try {
            return WechatShippingUploadStatus.valueOf(value);
        } catch (RuntimeException ex) {
            return WechatShippingUploadStatus.UNKNOWN;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record PaymentIdentity(String transactionId, String payerOpenid) {
    }

    private record RetryCandidate(
            long shipmentId,
            int logisticsType,
            int deliveryMode,
            String itemDesc,
            String expressCompanyCode,
            String trackingNo,
            String consignorContact,
            String receiverContact,
            String localStatus,
            String uploadStatus,
            LocalDateTime nextActionAt,
            int attemptCount
    ) {
        boolean reconstructable() {
            try {
                LogisticsType type = LogisticsType.fromValue(logisticsType);
                if (DeliveryMode.fromValue(deliveryMode) != DeliveryMode.UNIFIED
                        || !StringUtils.hasText(itemDesc)
                        || itemDesc.codePointCount(0, itemDesc.length()) > MAX_ITEM_DESC_CODE_POINTS
                        || !"SHIPPED".equals(localStatus)) {
                    return false;
                }
                if (type != LogisticsType.EXPRESS) {
                    return true;
                }
                if (!StringUtils.hasText(expressCompanyCode) || !StringUtils.hasText(trackingNo)) {
                    return false;
                }
                return !SF_DELIVERY_ID.equals(expressCompanyCode)
                        || StringUtils.hasText(consignorContact)
                        || StringUtils.hasText(receiverContact);
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }

    private record UnknownCandidate(
            LogisticsType logisticsType,
            DeliveryMode deliveryMode,
            String expressCompanyCode,
            String trackingNo,
            WechatProviderMode providerMode,
            WechatShippingUploadStatus uploadStatus,
            String claimToken,
            LocalDateTime claimedAt,
            LocalDateTime nextActionAt,
            int notUploadedObservations,
            LocalDateTime lastReconciledAt
    ) {
    }

    public record UploadClaim(long shipmentId, String claimToken, int attemptCount) {
        public UploadClaim {
            if (shipmentId <= 0 || !StringUtils.hasText(claimToken) || attemptCount < 1) {
                throw new IllegalArgumentException("Invalid WeChat shipping upload claim");
            }
        }
    }

    public record UnknownClaim(
            long shipmentId,
            long orderId,
            String claimToken,
            LogisticsType logisticsType,
            DeliveryMode deliveryMode,
            String expressCompanyCode,
            String trackingNo,
            String transactionId,
            int notUploadedObservations,
            LocalDateTime lastReconciledAt
    ) {
        public List<String> knownSecrets() {
            return Stream.of(transactionId, trackingNo)
                    .filter(StringUtils::hasText)
                    .toList();
        }
    }

    public record AttemptContext(
            long shipmentId,
            long orderId,
            LogisticsType logisticsType,
            DeliveryMode deliveryMode,
            String itemDesc,
            String expressCompanyCode,
            String trackingNo,
            String consignorContact,
            String receiverContact,
            String transactionId,
            String openid,
            String uploadTime
    ) {
        AttemptContext withPaymentIdentity(String transactionIdValue, String openidValue) {
            return new AttemptContext(
                    shipmentId, orderId, logisticsType, deliveryMode, itemDesc,
                    expressCompanyCode, trackingNo, consignorContact, receiverContact,
                    transactionIdValue, openidValue, uploadTime
            );
        }

        AttemptContext withUploadTime(String value) {
            return new AttemptContext(
                    shipmentId, orderId, logisticsType, deliveryMode, itemDesc,
                    expressCompanyCode, trackingNo, consignorContact, receiverContact,
                    transactionId, openid, value
            );
        }

        List<String> knownSecrets() {
            return Stream.of(
                            transactionId, openid, itemDesc, trackingNo,
                            consignorContact, receiverContact
                    )
                    .filter(StringUtils::hasText)
                    .toList();
        }
    }
}
