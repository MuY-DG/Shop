package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.RefundOrderStatus;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundNotification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
public class RefundCallbackService {

    private static final String CALLBACK_TYPE_REFUND = "REFUND";

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;

    public RefundCallbackService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
    }

    public void handleRefundNotification(
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String rawBodySha256 = sha256(body);
        WechatRefundNotification notification;
        try {
            notification = wechatPayProvider.parseRefundNotification(config, timestamp, nonce, serial, signature, body);
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
            RefundCallbackRow refund = findRefundForUpdate(notification.outRefundNo())
                    .orElseThrow(() -> new BusinessException(ErrorCode.WECHAT_REFUND_FAILED));
            validateNotificationMatchesRefund(refund, notification);
            if (isDuplicate(refund, notification)) {
                updateCallbackLog(logId, "DUPLICATE", "", "");
                return;
            }
            if (isSuccessfulRefund(notification)) {
                markRefundSuccess(refund, notification);
            } else {
                markRefundFailed(refund, notification);
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

    private java.util.Optional<RefundCallbackRow> findRefundForUpdate(String outRefundNo) {
        return jdbcClient.sql("""
                        select ro.id,
                               ro.after_sale_id,
                               ro.order_id,
                               ro.payment_order_id,
                               ro.out_refund_no,
                               ro.refund_id,
                               ro.refund_amount_cent,
                               ro.status,
                               po.out_trade_no
                        from refund_order ro
                        join payment_order po on po.id = ro.payment_order_id
                        where ro.out_refund_no = :outRefundNo
                        for update
                        """)
                .param("outRefundNo", outRefundNo)
                .query(this::mapRefundCallback)
                .optional();
    }

    private void validateNotificationMatchesRefund(RefundCallbackRow refund, WechatRefundNotification notification) {
        if (!refund.outRefundNo().equals(notification.outRefundNo())
                || !refund.outTradeNo().equals(notification.outTradeNo())
                || refund.refundAmountCent() != notification.refundAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private boolean isDuplicate(RefundCallbackRow refund, WechatRefundNotification notification) {
        if (RefundOrderStatus.SUCCESS.name().equals(refund.status()) && isSuccessfulRefund(notification)) {
            return true;
        }
        return RefundOrderStatus.FAILED.name().equals(refund.status()) && !isSuccessfulRefund(notification);
    }

    private void markRefundSuccess(RefundCallbackRow refund, WechatRefundNotification notification) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime successAt = notification.successAt() == null ? now : notification.successAt();
        jdbcClient.sql("""
                        update refund_order
                        set status = :status,
                            refund_id = :refundId,
                            callback_status = :callbackStatus,
                            callback_digest = :callbackDigest,
                            last_error_code = '',
                            last_error_message = '',
                            success_at = :successAt,
                            updated_at = :updatedAt
                        where id = :refundOrderId
                        """)
                .param("status", RefundOrderStatus.SUCCESS.name())
                .param("refundId", nullToEmpty(notification.refundId()))
                .param("callbackStatus", nullToEmpty(notification.status()))
                .param("callbackDigest", nullToEmpty(notification.resourceDigest()))
                .param("successAt", successAt)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                        """)
                .param("status", AfterSaleStatus.REFUNDED.name())
                .param("updatedAt", now)
                .param("afterSaleId", refund.afterSaleId())
                .update();
        jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            refunded_at = :refundedAt,
                            updated_at = :updatedAt
                        where id = :orderId
                        """)
                .param("status", OrderStatus.REFUNDED.name())
                .param("refundedAt", successAt)
                .param("updatedAt", now)
                .param("orderId", refund.orderId())
                .update();
    }

    private void markRefundFailed(RefundCallbackRow refund, WechatRefundNotification notification) {
        LocalDateTime now = LocalDateTime.now();
        String callbackStatus = StringUtils.hasText(notification.status()) ? notification.status() : "FAILED";
        jdbcClient.sql("""
                        update refund_order
                        set status = :status,
                            refund_id = :refundId,
                            callback_status = :callbackStatus,
                            callback_digest = :callbackDigest,
                            last_error_code = :lastErrorCode,
                            last_error_message = :lastErrorMessage,
                            updated_at = :updatedAt
                        where id = :refundOrderId
                        """)
                .param("status", RefundOrderStatus.FAILED.name())
                .param("refundId", nullToEmpty(notification.refundId()))
                .param("callbackStatus", callbackStatus)
                .param("callbackDigest", nullToEmpty(notification.resourceDigest()))
                .param("lastErrorCode", callbackStatus)
                .param("lastErrorMessage", "refund callback status " + callbackStatus)
                .param("updatedAt", now)
                .param("refundOrderId", refund.id())
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                        """)
                .param("status", AfterSaleStatus.REFUND_FAILED.name())
                .param("updatedAt", now)
                .param("afterSaleId", refund.afterSaleId())
                .update();
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
        jdbcClient.sql("""
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

    private boolean isSuccessfulRefund(WechatRefundNotification notification) {
        return "SUCCESS".equals(notification.status());
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

    private RefundCallbackRow mapRefundCallback(ResultSet rs, int rowNum) throws SQLException {
        return new RefundCallbackRow(
                rs.getLong("id"),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getLong("payment_order_id"),
                rs.getString("out_refund_no"),
                rs.getString("refund_id"),
                rs.getLong("refund_amount_cent"),
                rs.getString("status"),
                rs.getString("out_trade_no")
        );
    }

    private record RefundCallbackRow(
            Long id,
            Long afterSaleId,
            Long orderId,
            Long paymentOrderId,
            String outRefundNo,
            String refundId,
            long refundAmountCent,
            String status,
            String outTradeNo
    ) {
    }
}
