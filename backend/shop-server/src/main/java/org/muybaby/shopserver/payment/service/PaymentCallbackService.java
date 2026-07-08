package org.muybaby.shopserver.payment.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayNotification;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class PaymentCallbackService {

    private static final String CALLBACK_TYPE_PAY = "PAY";

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final AppPaymentService appPaymentService;

    public PaymentCallbackService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            AppPaymentService appPaymentService
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
        this.appPaymentService = appPaymentService;
    }

    public void handlePayNotification(
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String rawBodySha256 = sha256(body);
        WechatPayNotification notification;
        try {
            notification = wechatPayProvider.parsePayNotification(config, timestamp, nonce, serial, signature, body);
        } catch (BusinessException ex) {
            insertCallbackLog(
                    "",
                    "",
                    "",
                    "",
                    "",
                    rawBodySha256,
                    "FAILED",
                    "VERIFY_FAILED",
                    "notification verification failed"
            );
            throw ex;
        }

        Long logId = insertCallbackLog(
                notification.notifyId(),
                notification.outTradeNo(),
                notification.transactionId(),
                notification.eventType(),
                notification.resourceDigest(),
                rawBodySha256,
                "PROCESSING",
                "",
                ""
        );
        try {
            AppPaymentService.PaidFinalizationResult result = appPaymentService.finalizePaid(
                    notification.outTradeNo(),
                    notification.transactionId(),
                    notification.amountCent(),
                    notification.paidAt(),
                    notification.resourceDigest()
            );
            updateCallbackLog(logId, result.duplicate() ? "DUPLICATE" : "SUCCESS", "", "");
        } catch (BusinessException ex) {
            updateCallbackLog(logId, "FAILED", ex.errorCode().name(), ex.errorCode().message());
            throw ex;
        } catch (RuntimeException ex) {
            updateCallbackLog(logId, "FAILED", "CALLBACK_FAILED", "payment callback failed");
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private Long insertCallbackLog(
            String notifyId,
            String outTradeNo,
            String transactionId,
            String eventType,
            String resourceDigest,
            String rawBodySha256,
            String status,
            String errorCode,
            String errorMessage
    ) {
        jdbcClient.sql("""
                        insert into payment_callback_log
                            (callback_type, notify_id, out_trade_no, transaction_id, event_type,
                             resource_digest, raw_body_sha256, status, error_code, error_message,
                             created_at, updated_at)
                        values
                            (:callbackType, :notifyId, :outTradeNo, :transactionId, :eventType,
                             :resourceDigest, :rawBodySha256, :status, :errorCode, :errorMessage,
                             :createdAt, :updatedAt)
                        """)
                .param("callbackType", CALLBACK_TYPE_PAY)
                .param("notifyId", nullToEmpty(notifyId))
                .param("outTradeNo", nullToEmpty(outTradeNo))
                .param("transactionId", nullToEmpty(transactionId))
                .param("eventType", nullToEmpty(eventType))
                .param("resourceDigest", nullToEmpty(resourceDigest))
                .param("rawBodySha256", rawBodySha256)
                .param("status", status)
                .param("errorCode", nullToEmpty(errorCode))
                .param("errorMessage", nullToEmpty(errorMessage))
                .param("createdAt", LocalDateTime.now())
                .param("updatedAt", LocalDateTime.now())
                .update();
        return jdbcClient.sql("""
                        select id
                        from payment_callback_log
                        where raw_body_sha256 = :rawBodySha256
                        order by id desc
                        limit 1
                        """)
                .param("rawBodySha256", rawBodySha256)
                .query(Long.class)
                .single();
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
