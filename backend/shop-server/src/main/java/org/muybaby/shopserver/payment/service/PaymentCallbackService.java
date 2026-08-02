package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.muybaby.shopserver.payment.config.PaymentNotificationConfigSelector;
import org.muybaby.shopserver.payment.config.PaymentNotificationTimestampValidator;
import org.muybaby.shopserver.payment.provider.WechatPayNotification;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
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
public class PaymentCallbackService {

    private static final String CALLBACK_TYPE_PAY = "PAY";

    private final JdbcClient jdbcClient;
    private final PaymentNotificationTimestampValidator timestampValidator;
    private final PaymentNotificationConfigSelector paymentNotificationConfigSelector;
    private final WechatPayProvider wechatPayProvider;
    private final PaymentFinalizationService paymentFinalizationService;
    private final TransactionTemplate transactionTemplate;

    public PaymentCallbackService(
            JdbcClient jdbcClient,
            PaymentNotificationTimestampValidator timestampValidator,
            PaymentNotificationConfigSelector paymentNotificationConfigSelector,
            WechatPayProvider wechatPayProvider,
            PaymentFinalizationService paymentFinalizationService,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.timestampValidator = timestampValidator;
        this.paymentNotificationConfigSelector = paymentNotificationConfigSelector;
        this.wechatPayProvider = wechatPayProvider;
        this.paymentFinalizationService = paymentFinalizationService;
        this.transactionTemplate = transactionTemplate;
    }

    public void handlePayNotification(
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        handlePayNotification(null, timestamp, nonce, serial, signature, body);
    }

    public void handlePayNotification(
            String routeToken,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        timestampValidator.validate(timestamp);
        String rawBodySha256 = sha256(body);
        PaymentNotificationConfigSelector.ParsedNotification<WechatPayNotification> parsed =
                paymentNotificationConfigSelector.parse(
                        routeToken,
                        PaymentNotificationConfigSelector.NotificationKind.PAY,
                        config -> wechatPayProvider.parsePayNotification(
                                config, timestamp, nonce, serial, signature, body),
                        notification -> PaymentNotificationConfigSelector.NotificationRoute.payment(
                                notification.outTradeNo())
                );
        WechatPayNotification notification = parsed.notification();
        if (parsed.purged()) {
            requireMatchingPurgedPayment(parsed.callbackIdentity(), notification);
            return;
        }

        Long logId = reserveLiveCallbackLog(parsed.callbackIdentity(), notification, rawBodySha256, routeToken);
        if (logId == null) {
            return;
        }
        try {
            if (!isSuccessfulPayNotification(notification)) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            PaymentFinalizationService.PaidFinalizationResult result = paymentFinalizationService.finalizePaid(
                    notification.outTradeNo(),
                    notification.transactionId(),
                    notification.amountCent(),
                    notification.paidAt(),
                    notification.resourceDigest(),
                    parsed.config()
            );
            updateCallbackLog(logId, result.duplicate() ? "DUPLICATE" : "SUCCESS", "", "");
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.ORDER_STATE_CONFLICT
                    && matchesPurgedPayment(notification)) {
                deleteCallbackLog(logId);
                return;
            }
            updateCallbackLog(logId, "FAILED", ex.errorCode().name(), ex.errorCode().message());
            throw ex;
        } catch (RuntimeException ex) {
            updateCallbackLog(logId, "FAILED", "CALLBACK_FAILED", "payment callback failed");
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private Long reserveLiveCallbackLog(
            PaymentNotificationConfigSelector.CallbackIdentity callbackIdentity,
            WechatPayNotification notification,
            String rawBodySha256,
            String routeToken
    ) {
        return transactionTemplate.execute(status -> {
            if (callbackIdentity == null
                    || callbackIdentity.kind() != PaymentNotificationConfigSelector.NotificationKind.PAY
                    || callbackIdentity.orderId() == null) {
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
                requireMatchingPurgedPayment(notification);
                return null;
            }
            Long paymentOrderId = jdbcClient.sql("""
                            select order_id from payment_order
                            where out_trade_no = :outTradeNo
                            for update
                            """)
                    .param("outTradeNo", notification.outTradeNo())
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            if (!lockedOrderId.equals(paymentOrderId)) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            return insertCallbackLog(
                    notification.notifyId(),
                    notification.outTradeNo(),
                    notification.transactionId(),
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
            String transactionId,
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
                            (callback_type, notify_id, out_trade_no, transaction_id, event_type,
                             resource_digest, raw_body_sha256, route_mode, route_digest,
                             status, error_code, error_message,
                             created_at, updated_at)
                        values
                            (:callbackType, :notifyId, :outTradeNo, :transactionId, :eventType,
                             :resourceDigest, :rawBodySha256, :routeMode, :routeDigest,
                             :status, :errorCode, :errorMessage,
                             :createdAt, :updatedAt)
                        """)
                .param("callbackType", CALLBACK_TYPE_PAY)
                .param("notifyId", nullToEmpty(notifyId))
                .param("outTradeNo", nullToEmpty(outTradeNo))
                .param("transactionId", nullToEmpty(transactionId))
                .param("eventType", nullToEmpty(eventType))
                .param("resourceDigest", nullToEmpty(resourceDigest))
                .param("rawBodySha256", rawBodySha256)
                .param("routeMode", StringUtils.hasText(routeToken) ? "ROUTED" : "LEGACY")
                .param("routeDigest", StringUtils.hasText(routeToken) ? sha256(routeToken.trim()) : "")
                .param("status", status)
                .param("errorCode", nullToEmpty(errorCode))
                .param("errorMessage", nullToEmpty(errorMessage))
                .param("createdAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .param("updatedAt", LocalDateTime.now(java.time.ZoneOffset.UTC))
                .update(keyHolder, "id");
        if (insertedRows != 1) {
            throw new IllegalStateException("Payment callback log was not inserted");
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Payment callback log id was not generated");
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

    private void requireMatchingPurgedPayment(WechatPayNotification notification) {
        PurgedPaymentIdentity identity = findPurgedPayment(notification.outTradeNo());
        if (!matchingPurgedPayment(identity, notification)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireMatchingPurgedPayment(
            PaymentNotificationConfigSelector.CallbackIdentity identity,
            WechatPayNotification notification
    ) {
        if (identity == null
                || !identity.purged()
                || identity.kind() != PaymentNotificationConfigSelector.NotificationKind.PAY
                || !matchingPurgedPayment(
                        new PurgedPaymentIdentity(
                                identity.finalStatus(),
                                identity.transactionIdDigest(),
                                identity.amountCent(),
                                identity.currency()),
                        notification)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private boolean matchesPurgedPayment(WechatPayNotification notification) {
        PurgedPaymentIdentity identity = findPurgedPaymentOrNull(notification.outTradeNo());
        return identity != null && matchingPurgedPayment(identity, notification);
    }

    private boolean matchingPurgedPayment(
            PurgedPaymentIdentity identity,
            WechatPayNotification notification
    ) {
        return "PAID".equals(identity.finalStatus())
                && isSuccessfulPayNotification(notification)
                && identity.amountCent() == notification.amountCent()
                && constantTimeEquals(identity.currency(), notification.currency())
                && constantTimeEquals(
                        identity.transactionIdDigest(),
                        PurgedOrderIdentityDigests.value(notification.transactionId()));
    }

    private PurgedPaymentIdentity findPurgedPayment(String outTradeNo) {
        PurgedPaymentIdentity identity = findPurgedPaymentOrNull(outTradeNo);
        if (identity == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return identity;
    }

    private PurgedPaymentIdentity findPurgedPaymentOrNull(String outTradeNo) {
        return jdbcClient.sql("""
                        select final_status, transaction_id_digest, amount_cent, currency
                        from purged_payment_identity
                        where out_trade_no_digest = :digest
                        for update
                        """)
                .param("digest", PurgedOrderIdentityDigests.value(outTradeNo))
                .query((rs, rowNum) -> new PurgedPaymentIdentity(
                        rs.getString("final_status"),
                        rs.getString("transaction_id_digest"),
                        rs.getLong("amount_cent"),
                        rs.getString("currency")))
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

    private boolean isSuccessfulPayNotification(WechatPayNotification notification) {
        return "TRANSACTION.SUCCESS".equals(notification.eventType())
                && "SUCCESS".equals(notification.tradeState())
                && StringUtils.hasText(notification.transactionId())
                && "CNY".equals(notification.currency());
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                nullToEmpty(left).getBytes(StandardCharsets.US_ASCII),
                nullToEmpty(right).getBytes(StandardCharsets.US_ASCII));
    }

    private record PurgedPaymentIdentity(
            String finalStatus,
            String transactionIdDigest,
            long amountCent,
            String currency
    ) {
    }
}
