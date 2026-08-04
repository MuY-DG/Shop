package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.aftersale.service.AfterSaleFulfillmentPolicy;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatReceiptReconciliationProperties;
import org.muybaby.shopserver.logistics.provider.WechatReceiptQueryResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingProvider;
import org.muybaby.shopserver.order.service.OrderReceiptCompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WechatReceiptReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(
            WechatReceiptReconciliationService.class);
    private static final int MAX_BATCH_SIZE = 200;
    private static final int MAX_CLAIM_CONTENTION_RETRIES = 8;

    private final JdbcClient jdbcClient;
    private final WechatShippingProvider wechatShippingProvider;
    private final AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy;
    private final OrderReceiptCompletionService completionService;
    private final WechatReceiptReconciliationProperties properties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public WechatReceiptReconciliationService(
            JdbcClient jdbcClient,
            WechatShippingProvider wechatShippingProvider,
            AfterSaleFulfillmentPolicy afterSaleFulfillmentPolicy,
            OrderReceiptCompletionService completionService,
            WechatReceiptReconciliationProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.wechatShippingProvider = wechatShippingProvider;
        this.afterSaleFulfillmentPolicy = afterSaleFulfillmentPolicy;
        this.completionService = completionService;
        this.properties = properties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public int reconcilePendingReceipts() {
        return reconcilePendingReceipts(properties.batchSize());
    }

    public int reconcilePendingReceipts(int batchSize) {
        requireValidBatchSize(batchSize);
        if (!realProviderAvailable()) {
            return 0;
        }
        Integer completed = withoutTransaction.execute(
                status -> reconcileOutsideTransaction(batchSize));
        return completed == null ? 0 : completed;
    }

    private int reconcileOutsideTransaction(int batchSize) {
        LocalDateTime scanTime = LocalDateTime.now(clock);
        int completedCount = 0;
        for (int index = 0; index < batchSize; index++) {
            ClaimedReceipt claimed = claimNextReceipt(scanTime);
            if (claimed == null) {
                break;
            }
            completedCount += reconcileClaimedReceipt(claimed);
        }
        return completedCount;
    }

    private int reconcileClaimedReceipt(ClaimedReceipt claimed) {
        if (!StringUtils.hasText(claimed.transactionId())) {
            releaseClaim(claimed, null, "MISSING_TRANSACTION_ID");
            return 0;
        }

        try {
            WechatReceiptQueryResult result = wechatShippingProvider.queryReceiptStatus(
                    claimed.transactionId());
            if (result == null) {
                releaseClaim(claimed, null, "AMBIGUOUS_RESPONSE");
                return 0;
            }

            boolean completed = result.confirmed()
                    && completionService.completeAutomatically(claimed.orderId());
            releaseClaim(claimed, result.orderState(), safeErrorCode(result.errorCode()));
            if (completed) {
                log.info(
                        "WeChat receipt reconciliation completed local order: orderId={}, orderState={}",
                        claimed.orderId(), result.orderState()
                );
                return 1;
            }
            return 0;
        } catch (RuntimeException ex) {
            releaseClaim(claimed, null, "RECONCILIATION_FAILED");
            log.warn(
                    "One WeChat receipt could not be reconciled; it will be retried (orderId={}, type={})",
                    claimed.orderId(), ex.getClass().getSimpleName()
            );
            return 0;
        }
    }

    private ClaimedReceipt claimNextReceipt(LocalDateTime scanTime) {
        for (int attempt = 0; attempt < MAX_CLAIM_CONTENTION_RETRIES; attempt++) {
            ClaimedReceipt claimed = requiresNewTransaction.execute(
                    status -> claimNextReceiptOnce(scanTime));
            if (claimed != null) {
                return claimed;
            }
            Thread.onSpinWait();
        }
        return null;
    }

    private ClaimedReceipt claimNextReceiptOnce(LocalDateTime scanTime) {
        LocalDateTime claimedAt = LocalDateTime.now(clock);
        LocalDateTime shippedBefore = scanTime.minus(properties.minShippedAge());
        LocalDateTime recheckBefore = scanTime.minus(properties.recheckInterval());
        LocalDateTime expiredClaimBefore = claimedAt.minus(properties.claimTimeout());
        List<ReceiptCandidate> candidates = jdbcClient.sql("""
                        select sh.id as shipment_id,
                               o.id as order_id,
                               coalesce(
                                   nullif((
                                       select po.transaction_id
                                       from payment_order po
                                       where po.order_id = o.id
                                       order by po.updated_at desc, po.id desc
                                       limit 1
                                   ), ''),
                                   nullif(o.payment_transaction_id, ''),
                                   ''
                               ) as transaction_id
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        where o.status = 'SHIPPED'
                          and coalesce(o.shipped_at, sh.shipped_at, sh.created_at) <= :shippedBefore
                          and sh.wechat_provider_mode = 'REAL'
                          and sh.wechat_upload_status = 'UPLOADED'
                          and (
                              sh.wechat_receipt_last_checked_at is null
                              or sh.wechat_receipt_last_checked_at <= :recheckBefore
                          )
                          and (
                              sh.wechat_receipt_claim_token is null
                              or sh.wechat_receipt_claimed_at is null
                              or sh.wechat_receipt_claimed_at <= :expiredClaimBefore
                          )
                          and not exists (
                              select 1
                              from after_sale_request blocked
                              where blocked.order_id = o.id
                                and blocked.status in (:blockingStatuses)
                          )
                        order by
                            case when sh.wechat_receipt_last_checked_at is null then 0 else 1 end,
                            sh.wechat_receipt_last_checked_at asc,
                            coalesce(o.shipped_at, sh.shipped_at, sh.created_at) asc,
                            sh.id asc
                        limit 1
                        """)
                .param("shippedBefore", shippedBefore)
                .param("recheckBefore", recheckBefore)
                .param("expiredClaimBefore", expiredClaimBefore)
                .param("blockingStatuses", afterSaleFulfillmentPolicy.blockingStatuses())
                .query((rs, rowNum) -> new ReceiptCandidate(
                        rs.getLong("shipment_id"),
                        rs.getLong("order_id"),
                        rs.getString("transaction_id")
                ))
                .list();
        if (candidates.isEmpty()) {
            return null;
        }

        ReceiptCandidate candidate = candidates.getFirst();
        String claimToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update order_shipment
                        set wechat_receipt_claim_token = :claimToken,
                            wechat_receipt_claimed_at = :claimedAt
                        where id = :shipmentId
                          and wechat_provider_mode = 'REAL'
                          and wechat_upload_status = 'UPLOADED'
                          and (
                              wechat_receipt_last_checked_at is null
                              or wechat_receipt_last_checked_at <= :recheckBefore
                          )
                          and (
                              wechat_receipt_claim_token is null
                              or wechat_receipt_claimed_at is null
                              or wechat_receipt_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("shipmentId", candidate.shipmentId())
                .param("recheckBefore", recheckBefore)
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        if (updated != 1) {
            return null;
        }
        return new ClaimedReceipt(
                candidate.shipmentId(), candidate.orderId(),
                candidate.transactionId(), claimToken
        );
    }

    private void releaseClaim(
            ClaimedReceipt claimed,
            Integer orderState,
            String errorCode
    ) {
        requiresNewTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                        update order_shipment
                        set wechat_receipt_claim_token = null,
                            wechat_receipt_claimed_at = null,
                            wechat_receipt_last_checked_at = :checkedAt,
                            wechat_receipt_order_state = :orderState,
                            wechat_receipt_last_error_code = :errorCode
                        where id = :shipmentId
                          and wechat_receipt_claim_token = :claimToken
                        """)
                .param("checkedAt", LocalDateTime.now(clock))
                .param("orderState", orderState)
                .param("errorCode", safeErrorCode(errorCode))
                .param("shipmentId", claimed.shipmentId())
                .param("claimToken", claimed.claimToken())
                .update());
    }

    private boolean realProviderAvailable() {
        try {
            return wechatShippingProvider.mode() == WechatProviderMode.REAL;
        } catch (RuntimeException ex) {
            log.warn(
                    "WeChat receipt reconciliation skipped because provider mode is unavailable (type={})",
                    ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    private String safeErrorCode(String errorCode) {
        if (!StringUtils.hasText(errorCode)) {
            return "";
        }
        String normalized = errorCode.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    private void requireValidBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "WeChat receipt reconciliation batch size must be between 1 and "
                            + MAX_BATCH_SIZE
            );
        }
    }

    private record ReceiptCandidate(
            Long shipmentId,
            Long orderId,
            String transactionId
    ) {
    }

    private record ClaimedReceipt(
            Long shipmentId,
            Long orderId,
            String transactionId,
            String claimToken
    ) {
    }
}
