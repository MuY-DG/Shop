package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;

/**
 * Selects the merchant configuration capable of verifying and decrypting a payment notification.
 * Every notification resolves one persisted opaque route before any provider payload is parsed.
 */
@Service
public class PaymentNotificationConfigSelector {

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentConfigIdentityValidator paymentConfigIdentityValidator;
    private final PaymentNotificationRouteService paymentNotificationRouteService;

    public PaymentNotificationConfigSelector(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            PaymentConfigIdentityValidator paymentConfigIdentityValidator,
            PaymentNotificationRouteService paymentNotificationRouteService
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentConfigIdentityValidator = paymentConfigIdentityValidator;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
    }

    public <T> ParsedNotification<T> parse(
            String routeToken,
            NotificationKind notificationKind,
            Function<ResolvedPaymentConfig, T> parser,
            Function<T, NotificationRoute> routeExtractor
    ) {
        if (notificationKind == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return parseRouted(routeToken, notificationKind, parser, routeExtractor);
    }

    private <T> ParsedNotification<T> parseRouted(
            String routeToken,
            NotificationKind notificationKind,
            Function<ResolvedPaymentConfig, T> parser,
            Function<T, NotificationRoute> routeExtractor
    ) {
        String normalizedToken;
        try {
            normalizedToken = paymentNotificationRouteService.requireRouteToken(routeToken);
        } catch (BusinessException ex) {
            throw new PaymentNotificationRouteRejectedException();
        }
        RoutedPaymentIdentity routedIdentity = findRoutedPaymentIdentity(
                normalizedToken, notificationKind);
        String presentedRouteIdentity = routedIdentity.callbackIdentity().purged()
                ? PurgedOrderIdentityDigests.value(normalizedToken)
                : normalizedToken;
        if (!constantTimeEquals(presentedRouteIdentity, routedIdentity.routeTokenIdentity())) {
            throw new PaymentNotificationRouteRejectedException();
        }
        ResolvedPaymentConfig exactConfig = paymentConfigResolver.resolveForPayment(
                routedIdentity.configId(), routedIdentity.fingerprint());
        T notification = parser.apply(exactConfig);
        NotificationRoute actualRoute = requireRoute(routeExtractor.apply(notification));
        if (!matchesExpectedRoute(routedIdentity, actualRoute)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        paymentConfigIdentityValidator.validate(
                routedIdentity.configId(), routedIdentity.fingerprint(), exactConfig);
        return new ParsedNotification<>(exactConfig, notification, routedIdentity.callbackIdentity());
    }

    private RoutedPaymentIdentity findRoutedPaymentIdentity(
            String routeToken,
            NotificationKind notificationKind
    ) {
        if (notificationKind == NotificationKind.REFUND) {
            RoutedPaymentIdentity liveIdentity = jdbcClient.sql("""
                            select po.payment_config_id,
                                   po.payment_config_fingerprint,
                                   po.out_trade_no,
                                   ro.out_refund_no,
                                   ro.notification_route_token,
                                   ro.order_id,
                                   ro.after_sale_id
                            from refund_order ro
                            join payment_order po on po.id = ro.payment_order_id
                            where ro.notification_route_token = :routeToken
                            """)
                    .param("routeToken", routeToken)
                    .query((rs, rowNum) -> new RoutedPaymentIdentity(
                            rs.getLong("payment_config_id"),
                            rs.getString("payment_config_fingerprint"),
                            NotificationRoute.refund(
                                    rs.getString("out_trade_no"),
                                    rs.getString("out_refund_no")),
                            rs.getString("notification_route_token"),
                            null,
                            null,
                            CallbackIdentity.liveRefund(
                                    rs.getObject("order_id", Long.class),
                                    rs.getObject("after_sale_id", Long.class))
                    ))
                    .optional()
                    .orElse(null);
            if (liveIdentity != null) {
                return liveIdentity;
            }
            return jdbcClient.sql("""
                            select payment_config_id, payment_config_fingerprint,
                                   out_trade_no_digest, out_refund_no_digest,
                                   notification_route_digest, final_status,
                                   final_callback_status, refund_id_digest,
                                   refund_amount_cent
                            from purged_refund_identity
                            where notification_route_digest = :routeDigest
                            """)
                    .param("routeDigest", PurgedOrderIdentityDigests.value(routeToken))
                    .query((rs, rowNum) -> new RoutedPaymentIdentity(
                            rs.getLong("payment_config_id"),
                            rs.getString("payment_config_fingerprint"),
                            null,
                            rs.getString("notification_route_digest"),
                            rs.getString("out_trade_no_digest"),
                            rs.getString("out_refund_no_digest"),
                            CallbackIdentity.purgedRefund(
                                    rs.getString("final_status"),
                                    rs.getString("final_callback_status"),
                                    rs.getString("refund_id_digest"),
                                    rs.getLong("refund_amount_cent"))
                    ))
                    .optional()
                    .orElseThrow(PaymentNotificationRouteRejectedException::new);
        }
        if (notificationKind != NotificationKind.PAY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        RoutedPaymentIdentity liveIdentity = jdbcClient.sql("""
                        select payment_config_id,
                               payment_config_fingerprint,
                               out_trade_no,
                               notification_route_token,
                               order_id
                        from payment_order
                        where notification_route_token = :routeToken
                        """)
                .param("routeToken", routeToken)
                .query((rs, rowNum) -> new RoutedPaymentIdentity(
                        rs.getLong("payment_config_id"),
                        rs.getString("payment_config_fingerprint"),
                        NotificationRoute.payment(rs.getString("out_trade_no")),
                        rs.getString("notification_route_token"),
                        null,
                        null,
                        CallbackIdentity.livePayment(rs.getObject("order_id", Long.class))
                ))
                .optional()
                .orElse(null);
        if (liveIdentity != null) {
            return liveIdentity;
        }
        return jdbcClient.sql("""
                        select payment_config_id, payment_config_fingerprint,
                               out_trade_no_digest, notification_route_digest,
                               final_status, transaction_id_digest, amount_cent, currency
                        from purged_payment_identity
                        where notification_route_digest = :routeDigest
                        """)
                .param("routeDigest", PurgedOrderIdentityDigests.value(routeToken))
                .query((rs, rowNum) -> new RoutedPaymentIdentity(
                        rs.getLong("payment_config_id"),
                        rs.getString("payment_config_fingerprint"),
                        null,
                        rs.getString("notification_route_digest"),
                        rs.getString("out_trade_no_digest"),
                        null,
                        CallbackIdentity.purgedPayment(
                                rs.getString("final_status"),
                                rs.getString("transaction_id_digest"),
                                rs.getLong("amount_cent"),
                                rs.getString("currency"))
                ))
                .optional()
                .orElseThrow(PaymentNotificationRouteRejectedException::new);
    }

    private boolean matchesExpectedRoute(RoutedPaymentIdentity identity, NotificationRoute actualRoute) {
        if (!identity.callbackIdentity().purged()) {
            return identity.expectedRoute().equals(actualRoute);
        }
        boolean tradeMatches = constantTimeEquals(
                identity.outTradeNoDigest(),
                PurgedOrderIdentityDigests.value(actualRoute.outTradeNo()));
        if (!tradeMatches) {
            return false;
        }
        if (identity.callbackIdentity().kind() == NotificationKind.PAY) {
            return true;
        }
        return constantTimeEquals(
                identity.outRefundNoDigest(),
                PurgedOrderIdentityDigests.value(actualRoute.outRefundNo()));
    }

    private NotificationRoute requireRoute(NotificationRoute route) {
        if (route == null || route.outTradeNo() == null || route.outTradeNo().isBlank()
                || (route.refund() && (route.outRefundNo() == null || route.outRefundNo().isBlank()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new NotificationRoute(
                route.outTradeNo().trim(),
                route.refund() ? route.outRefundNo().trim() : ""
        );
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                (left == null ? "" : left).getBytes(StandardCharsets.US_ASCII),
                (right == null ? "" : right).getBytes(StandardCharsets.US_ASCII));
    }

    private record RoutedPaymentIdentity(
            Long configId,
            String fingerprint,
            NotificationRoute expectedRoute,
            String routeTokenIdentity,
            String outTradeNoDigest,
            String outRefundNoDigest,
            CallbackIdentity callbackIdentity
    ) {
    }

    public enum NotificationKind {
        PAY,
        REFUND
    }

    public record NotificationRoute(String outTradeNo, String outRefundNo) {

        public static NotificationRoute payment(String outTradeNo) {
            return new NotificationRoute(outTradeNo, "");
        }

        public static NotificationRoute refund(String outTradeNo, String outRefundNo) {
            return new NotificationRoute(outTradeNo, outRefundNo);
        }

        private boolean refund() {
            return outRefundNo != null && !outRefundNo.isBlank();
        }
    }

    public record CallbackIdentity(
            NotificationKind kind,
            boolean purged,
            Long orderId,
            Long afterSaleId,
            String finalStatus,
            String finalCallbackStatus,
            String transactionIdDigest,
            String refundIdDigest,
            long amountCent,
            String currency
    ) {
        private static CallbackIdentity livePayment(Long orderId) {
            return new CallbackIdentity(
                    NotificationKind.PAY, false, orderId, null,
                    "", "", "", "", 0L, "");
        }

        private static CallbackIdentity liveRefund(Long orderId, Long afterSaleId) {
            return new CallbackIdentity(
                    NotificationKind.REFUND, false, orderId, afterSaleId,
                    "", "", "", "", 0L, "");
        }

        private static CallbackIdentity purgedPayment(
                String finalStatus,
                String transactionIdDigest,
                long amountCent,
                String currency
        ) {
            return new CallbackIdentity(
                    NotificationKind.PAY, true, null, null,
                    finalStatus, "", transactionIdDigest, "", amountCent, currency);
        }

        private static CallbackIdentity purgedRefund(
                String finalStatus,
                String finalCallbackStatus,
                String refundIdDigest,
                long amountCent
        ) {
            return new CallbackIdentity(
                    NotificationKind.REFUND, true, null, null,
                    finalStatus, finalCallbackStatus, "", refundIdDigest, amountCent, "");
        }
    }

    public record ParsedNotification<T>(
            ResolvedPaymentConfig config,
            T notification,
            CallbackIdentity callbackIdentity
    ) {
        public boolean purged() {
            return callbackIdentity.purged();
        }
    }
}
