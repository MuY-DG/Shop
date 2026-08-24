package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.muybaby.shopserver.payment.config.PaymentNotificationConfigSelector;
import org.muybaby.shopserver.payment.config.PaymentNotificationTimestampValidator;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class RefundCallbackService {

    private static final String CALLBACK_TYPE_REFUND = "REFUND";

    private final JdbcClient jdbcClient;
    private final PaymentNotificationTimestampValidator timestampValidator;
    private final PaymentNotificationConfigSelector paymentNotificationConfigSelector;
    private final WechatPayProvider wechatPayProvider;
    private final RefundFinalizationService refundFinalizationService;
    private final TransactionTemplate transactionTemplate;

    public RefundCallbackService(
            JdbcClient jdbcClient,
            PaymentNotificationTimestampValidator timestampValidator,
            PaymentNotificationConfigSelector paymentNotificationConfigSelector,
            WechatPayProvider wechatPayProvider,
            RefundFinalizationService refundFinalizationService,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.timestampValidator = timestampValidator;
        this.paymentNotificationConfigSelector = paymentNotificationConfigSelector;
        this.wechatPayProvider = wechatPayProvider;
        this.refundFinalizationService = refundFinalizationService;
        this.transactionTemplate = transactionTemplate;
    }

    public void handleRefundNotification(
            String routeToken,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        timestampValidator.validate(timestamp);
        String rawBodySha256 = sha256(body);
        PaymentNotificationConfigSelector.ParsedNotification<WechatRefundNotification> parsed =
                paymentNotificationConfigSelector.parse(
                        routeToken,
                        PaymentNotificationConfigSelector.NotificationKind.REFUND,
                        config -> wechatPayProvider.parseRefundNotification(
                                config, timestamp, nonce, serial, signature, body),
                        notification -> PaymentNotificationConfigSelector.NotificationRoute.refund(
                                notification.outTradeNo(), notification.outRefundNo())
                );
        WechatRefundNotification notification = parsed.notification();
        if (parsed.purged()) {
            requireMatchingPurgedRefund(parsed.callbackIdentity(), notification);
            return;
        }

        Long logId = reserveLiveCallbackLog(parsed.callbackIdentity(), notification, rawBodySha256, routeToken);
        if (logId == null) {
            return;
        }
        try {
            RefundFinalizationService.Outcome outcome = refundFinalizationService.apply(
                    new RefundFinalizationService.ProviderRefundState(
                            notification.outRefundNo(),
                            notification.refundId(),
                            notification.outTradeNo(),
                            notification.status(),
                            notification.refundAmountCent(),
                            notification.successAt(),
                            notification.resourceDigest()
                    ),
                    parsed.config()
            );
            if (outcome == RefundFinalizationService.Outcome.DUPLICATE) {
                updateCallbackLog(logId, "DUPLICATE", "", "");
                return;
            }
            updateCallbackLog(logId, "SUCCESS", "", "");
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.ORDER_STATE_CONFLICT
                    && matchesPurgedRefund(notification)) {
                deleteCallbackLog(logId);
                return;
            }
            updateCallbackLog(logId, "FAILED", ex.errorCode().name(), ex.errorCode().message());
            throw ex;
        } catch (RuntimeException ex) {
            updateCallbackLog(logId, "FAILED", "REFUND_CALLBACK_FAILED", "refund callback failed");
            throw new BusinessException(ErrorCode.WECHAT_REFUND_FAILED);
        }
    }

    private Long reserveLiveCallbackLog(
            PaymentNotificationConfigSelector.CallbackIdentity callbackIdentity,
            WechatRefundNotification notification,
            String rawBodySha256,
            String routeToken
    ) {
        return transactionTemplate.execute(status -> {
            if (callbackIdentity == null
                    || callbackIdentity.kind() != PaymentNotificationConfigSelector.NotificationKind.REFUND
                    || callbackIdentity.orderId() == null
                    || callbackIdentity.afterSaleId() == null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            Long lockedOrderId = jdbcClient.sql("""
                            select id from shop_order
                            where id = :orderId
                            for update
                            """)
                    .param("orderId", callbackIdentity.orderId())
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (lockedOrderId == null) {
                requireMatchingPurgedRefund(notification);
                return null;
            }
            Long lockedAfterSaleId = jdbcClient.sql("""
                            select id from after_sale_request
                            where id = :afterSaleId and order_id = :orderId
                            for update
                            """)
                    .param("afterSaleId", callbackIdentity.afterSaleId())
                    .param("orderId", lockedOrderId)
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (lockedAfterSaleId == null) {
                requireMatchingPurgedRefund(notification);
                return null;
            }
            Long refundOrderId = jdbcClient.sql("""
                            select ro.order_id
                            from refund_order ro
                            join payment_order po on po.id = ro.payment_order_id
                            where ro.out_refund_no = :outRefundNo
                              and ro.after_sale_id = :afterSaleId
                              and po.out_trade_no = :outTradeNo
                            for update
                            """)
                    .param("outRefundNo", notification.outRefundNo())
                    .param("afterSaleId", lockedAfterSaleId)
                    .param("outTradeNo", notification.outTradeNo())
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (!lockedOrderId.equals(refundOrderId)) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            return insertCallbackLog(
                    notification.notifyId(),
                    notification.outTradeNo(),
                    notification.outRefundNo(),
                    notification.refundId(),
                    notification.eventType(),
                    notification.resourceDigest(),
                    rawBodySha256,
                    routeToken,
                    "PROCESSING",
                    "",
                    ""
            );
        });
    }

    private Long insertCallbackLog(
            String notifyId,
            String outTradeNo,
            String outRefundNo,
            String refundId,
            String eventType,
            String resourceDigest,
            String rawBodySha256,
            String routeToken,
            String status,
            String errorCode,
            String errorMessage
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int insertedRows = jdbcClient.sql("""
                        insert into payment_callback_log
                            (callback_type, notify_id, out_trade_no, out_refund_no, refund_id, event_type,
                             resource_digest, raw_body_sha256, route_digest,
                             status, error_code, error_message,
                             created_at, updated_at)
                        values
                            (:callbackType, :notifyId, :outTradeNo, :outRefundNo, :refundId, :eventType,
                             :resourceDigest, :rawBodySha256, :routeDigest,
                             :status, :errorCode, :errorMessage,
                             :createdAt, :updatedAt)
                        """)
                .param("callbackType", CALLBACK_TYPE_REFUND)
                .param("notifyId", nullToEmpty(notifyId))
                .param("outTradeNo", nullToEmpty(outTradeNo))
                .param("outRefundNo", nullToEmpty(outRefundNo))
                .param("refundId", nullToEmpty(refundId))
                .param("eventType", nullToEmpty(eventType))
                .param("resourceDigest", nullToEmpty(resourceDigest))
                .param("rawBodySha256", rawBodySha256)
                .param("routeDigest", sha256(routeToken))
                .param("status", status)
                .param("errorCode", nullToEmpty(errorCode))
                .param("errorMessage", nullToEmpty(errorMessage))
                .param("createdAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .update(keyHolder, "id");
        if (insertedRows != 1) {
            throw new IllegalStateException("Refund callback log was not inserted");
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Refund callback log id was not generated");
        }
        return key.longValue();
    }

    private void updateCallbackLog(Long logId, String status, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        update payment_callback_log
                        set status = :status,
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            updated_at = :updatedAt
                        where id = :logId
                        """)
                .param("status", status)
                .param("errorCode", nullToEmpty(errorCode))
                .param("errorMessage", nullToEmpty(errorMessage))
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("logId", logId)
                .update();
    }

    private void requireMatchingPurgedRefund(WechatRefundNotification notification) {
        PurgedRefundIdentity identity = findPurgedRefund(
                notification.outTradeNo(), notification.outRefundNo());
        if (!matchingPurgedRefund(identity, notification)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireMatchingPurgedRefund(
            PaymentNotificationConfigSelector.CallbackIdentity identity,
            WechatRefundNotification notification
    ) {
        if (identity == null
                || !identity.purged()
                || identity.kind() != PaymentNotificationConfigSelector.NotificationKind.REFUND
                || !matchingPurgedRefund(
                        new PurgedRefundIdentity(
                                identity.finalStatus(),
                                identity.finalCallbackStatus(),
                                identity.refundIdDigest(),
                                identity.amountCent()),
                        notification)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private boolean matchesPurgedRefund(WechatRefundNotification notification) {
        PurgedRefundIdentity identity = findPurgedRefundOrNull(
                notification.outTradeNo(), notification.outRefundNo());
        return identity != null && matchingPurgedRefund(identity, notification);
    }

    private boolean matchingPurgedRefund(
            PurgedRefundIdentity identity,
            WechatRefundNotification notification
    ) {
        boolean matchingTerminalState = ("SUCCESS".equals(identity.finalStatus())
                && "SUCCESS".equals(identity.finalCallbackStatus())
                && "REFUND.SUCCESS".equals(notification.eventType())
                && "SUCCESS".equals(notification.status()))
                || ("FAILED".equals(identity.finalStatus())
                && "CLOSED".equals(identity.finalCallbackStatus())
                && "REFUND.CLOSED".equals(notification.eventType())
                && "CLOSED".equals(notification.status()));
        return matchingTerminalState
                && identity.refundAmountCent() == notification.refundAmountCent()
                && StringUtils.hasText(notification.refundId())
                && constantTimeEquals(
                        identity.refundIdDigest(),
                        PurgedOrderIdentityDigests.value(notification.refundId()));
    }

    private PurgedRefundIdentity findPurgedRefund(String outTradeNo, String outRefundNo) {
        PurgedRefundIdentity identity = findPurgedRefundOrNull(outTradeNo, outRefundNo);
        if (identity == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return identity;
    }

    private PurgedRefundIdentity findPurgedRefundOrNull(String outTradeNo, String outRefundNo) {
        return jdbcClient.sql("""
                        select final_status, final_callback_status,
                               refund_id_digest, refund_amount_cent
                        from purged_refund_identity
                        where out_trade_no_digest = :outTradeNoDigest
                          and out_refund_no_digest = :outRefundNoDigest
                        for update
                        """)
                .param("outTradeNoDigest", PurgedOrderIdentityDigests.value(outTradeNo))
                .param("outRefundNoDigest", PurgedOrderIdentityDigests.value(outRefundNo))
                .query((rs, rowNum) -> new PurgedRefundIdentity(
                        rs.getString("final_status"),
                        rs.getString("final_callback_status"),
                        rs.getString("refund_id_digest"),
                        rs.getLong("refund_amount_cent")))
                .optional()
                .orElse(null);
    }

    private void deleteCallbackLog(Long logId) {
        jdbcClient.sql("delete from payment_callback_log where id = :logId")
                .param("logId", logId)
                .update();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(nullToEmpty(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                nullToEmpty(left).getBytes(StandardCharsets.US_ASCII),
                nullToEmpty(right).getBytes(StandardCharsets.US_ASCII));
    }

    private record PurgedRefundIdentity(
            String finalStatus,
            String finalCallbackStatus,
            String refundIdDigest,
            long refundAmountCent
    ) {
    }

}
