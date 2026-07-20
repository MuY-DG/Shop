package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentNotificationConfigSelector;
import org.muybaby.shopserver.payment.config.PaymentNotificationTimestampValidator;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

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

    public RefundCallbackService(
            JdbcClient jdbcClient,
            PaymentNotificationTimestampValidator timestampValidator,
            PaymentNotificationConfigSelector paymentNotificationConfigSelector,
            WechatPayProvider wechatPayProvider,
            RefundFinalizationService refundFinalizationService
    ) {
        this.jdbcClient = jdbcClient;
        this.timestampValidator = timestampValidator;
        this.paymentNotificationConfigSelector = paymentNotificationConfigSelector;
        this.wechatPayProvider = wechatPayProvider;
        this.refundFinalizationService = refundFinalizationService;
    }

    public void handleRefundNotification(
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        timestampValidator.validate(timestamp);
        String rawBodySha256 = sha256(body);
        PaymentNotificationConfigSelector.ParsedNotification<WechatRefundNotification> parsed;
        try {
            parsed = paymentNotificationConfigSelector.parse(
                    config -> wechatPayProvider.parseRefundNotification(
                            config, timestamp, nonce, serial, signature, body),
                    notification -> PaymentNotificationConfigSelector.NotificationRoute.refund(
                            notification.outTradeNo(), notification.outRefundNo())
            );
        } catch (BusinessException ex) {
            insertCallbackLog(
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    rawBodySha256,
                    "FAILED",
                    "VERIFY_FAILED",
                    "refund notification verification failed"
            );
            throw ex;
        }
        WechatRefundNotification notification = parsed.notification();

        Long logId = insertCallbackLog(
                notification.notifyId(),
                notification.outTradeNo(),
                notification.outRefundNo(),
                notification.refundId(),
                notification.eventType(),
                notification.resourceDigest(),
                rawBodySha256,
                "PROCESSING",
                "",
                ""
        );
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
            updateCallbackLog(logId, "FAILED", ex.errorCode().name(), ex.errorCode().message());
            throw ex;
        } catch (RuntimeException ex) {
            updateCallbackLog(logId, "FAILED", "REFUND_CALLBACK_FAILED", "refund callback failed");
            throw new BusinessException(ErrorCode.WECHAT_REFUND_FAILED);
        }
    }

    private Long insertCallbackLog(
            String notifyId,
            String outTradeNo,
            String outRefundNo,
            String refundId,
            String eventType,
            String resourceDigest,
            String rawBodySha256,
            String status,
            String errorCode,
            String errorMessage
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int insertedRows = jdbcClient.sql("""
                        insert into payment_callback_log
                            (callback_type, notify_id, out_trade_no, out_refund_no, refund_id, event_type,
                             resource_digest, raw_body_sha256, status, error_code, error_message,
                             created_at, updated_at)
                        values
                            (:callbackType, :notifyId, :outTradeNo, :outRefundNo, :refundId, :eventType,
                             :resourceDigest, :rawBodySha256, :status, :errorCode, :errorMessage,
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
                .param("status", status)
                .param("errorCode", nullToEmpty(errorCode))
                .param("errorMessage", nullToEmpty(errorMessage))
                .param("createdAt", LocalDateTime.now())
                .param("updatedAt", LocalDateTime.now())
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
                .param("updatedAt", LocalDateTime.now())
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

}
