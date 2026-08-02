package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.cleanup.PurgedOrderIdentityDigests;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Selects the merchant configuration capable of verifying and decrypting a payment notification.
 * Routed notifications resolve one persisted payment/refund identity before decrypting. Legacy
 * notifications have no such route and retain the bounded historical-candidate fallback.
 */
@Service
public class PaymentNotificationConfigSelector {

    private static final int MAX_HISTORICAL_CONFIG_CANDIDATES = 32;

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
            Function<ResolvedPaymentConfig, T> parser,
            Function<T, NotificationRoute> routeExtractor
    ) {
        return parse(null, NotificationKind.LEGACY, parser, routeExtractor);
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
        if (routeToken != null) {
            if (notificationKind == NotificationKind.LEGACY) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return parseRouted(routeToken, notificationKind, parser, routeExtractor);
        }
        return parseLegacy(notificationKind, parser, routeExtractor);
    }

    private <T> ParsedNotification<T> parseLegacy(
            NotificationKind notificationKind,
            Function<ResolvedPaymentConfig, T> parser,
            Function<T, NotificationRoute> routeExtractor
    ) {
        BusinessException firstFailure = null;
        Set<String> attemptedConfigIdentities = new LinkedHashSet<>();

        try {
            ResolvedPaymentConfig current = paymentConfigResolver.resolve();
            attemptedConfigIdentities.add(identityKey(
                    current.configId(), paymentConfigResolver.fingerprint(current)));
            return parseAndBindToStoredIdentity(
                    current, parser, routeExtractor, attemptedConfigIdentities);
        } catch (BusinessException ex) {
            firstFailure = ex;
        }

        for (HistoricalConfigIdentity identity : historicalConfigIdentities(notificationKind)) {
            if (!attemptedConfigIdentities.add(identityKey(
                    identity.configId(), identity.fingerprint()))) {
                continue;
            }
            try {
                ResolvedPaymentConfig historical = paymentConfigResolver.resolveForPayment(
                        identity.configId(), identity.fingerprint());
                return parseAndBindToStoredIdentity(
                        historical, parser, routeExtractor, attemptedConfigIdentities);
            } catch (BusinessException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                // Try the next immutable configuration revision.
            }
        }
        throw firstFailure == null
                ? new BusinessException(ErrorCode.VALIDATION_FAILED)
                : firstFailure;
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

    private <T> ParsedNotification<T> parseAndBindToStoredIdentity(
            ResolvedPaymentConfig bootstrapConfig,
            Function<ResolvedPaymentConfig, T> parser,
            Function<T, NotificationRoute> routeExtractor,
            Set<String> attemptedConfigIdentities
    ) {
        T bootstrapNotification = parser.apply(bootstrapConfig);
        NotificationRoute bootstrapRoute = requireRoute(routeExtractor.apply(bootstrapNotification));
        StoredPaymentIdentity storedIdentity = findStoredPaymentIdentity(bootstrapRoute);
        try {
            paymentConfigIdentityValidator.validate(
                    storedIdentity.configId(), storedIdentity.fingerprint(), bootstrapConfig);
            return new ParsedNotification<>(
                    bootstrapConfig, bootstrapNotification, storedIdentity.callbackIdentity());
        } catch (BusinessException identityMismatch) {
            String exactIdentityKey = identityKey(
                    storedIdentity.configId(), storedIdentity.fingerprint());
            if (!attemptedConfigIdentities.add(exactIdentityKey)) {
                throw identityMismatch;
            }

            try {
                ResolvedPaymentConfig exactConfig = paymentConfigResolver.resolveForPayment(
                        storedIdentity.configId(), storedIdentity.fingerprint());
                T exactNotification = parser.apply(exactConfig);
                NotificationRoute exactRoute = requireRoute(routeExtractor.apply(exactNotification));
                if (!bootstrapRoute.equals(exactRoute)) {
                    throw identityMismatch;
                }
                StoredPaymentIdentity exactStoredIdentity = findStoredPaymentIdentity(exactRoute);
                if (!Objects.equals(storedIdentity, exactStoredIdentity)) {
                    throw identityMismatch;
                }
                paymentConfigIdentityValidator.validate(
                        exactStoredIdentity.configId(), exactStoredIdentity.fingerprint(), exactConfig);
                return new ParsedNotification<>(
                        exactConfig, exactNotification, exactStoredIdentity.callbackIdentity());
            } catch (BusinessException exactFailure) {
                throw identityMismatch;
            }
        }
    }

    private StoredPaymentIdentity findStoredPaymentIdentity(NotificationRoute route) {
        if (route.refund()) {
            StoredPaymentIdentity liveIdentity = jdbcClient.sql("""
                            select po.payment_config_id, po.payment_config_fingerprint,
                                   ro.order_id, ro.after_sale_id
                            from refund_order ro
                            join payment_order po on po.id = ro.payment_order_id
                            where ro.out_refund_no = :outRefundNo
                              and po.out_trade_no = :outTradeNo
                              and ro.notification_route_token is null
                            """)
                    .param("outRefundNo", route.outRefundNo())
                    .param("outTradeNo", route.outTradeNo())
                    .query((rs, rowNum) -> new StoredPaymentIdentity(
                            rs.getObject("payment_config_id", Long.class),
                            rs.getString("payment_config_fingerprint"),
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
                                   final_status, final_callback_status,
                                   refund_id_digest, refund_amount_cent
                            from purged_refund_identity
                            where out_refund_no_digest = :outRefundNoDigest
                              and out_trade_no_digest = :outTradeNoDigest
                              and notification_route_digest is null
                            """)
                    .param("outRefundNoDigest", PurgedOrderIdentityDigests.value(route.outRefundNo()))
                    .param("outTradeNoDigest", PurgedOrderIdentityDigests.value(route.outTradeNo()))
                    .query((rs, rowNum) -> new StoredPaymentIdentity(
                            rs.getObject("payment_config_id", Long.class),
                            rs.getString("payment_config_fingerprint"),
                            CallbackIdentity.purgedRefund(
                                    rs.getString("final_status"),
                                    rs.getString("final_callback_status"),
                                    rs.getString("refund_id_digest"),
                                    rs.getLong("refund_amount_cent"))
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        }
        StoredPaymentIdentity liveIdentity = jdbcClient.sql("""
                        select payment_config_id, payment_config_fingerprint, order_id
                        from payment_order
                        where out_trade_no = :outTradeNo
                          and notification_route_token is null
                        """)
                .param("outTradeNo", route.outTradeNo())
                .query((rs, rowNum) -> new StoredPaymentIdentity(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint"),
                        CallbackIdentity.livePayment(rs.getObject("order_id", Long.class))
                ))
                .optional()
                .orElse(null);
        if (liveIdentity != null) {
            return liveIdentity;
        }
        return jdbcClient.sql("""
                        select payment_config_id, payment_config_fingerprint,
                               final_status, transaction_id_digest, amount_cent, currency
                        from purged_payment_identity
                        where out_trade_no_digest = :outTradeNoDigest
                          and notification_route_digest is null
                        """)
                .param("outTradeNoDigest", PurgedOrderIdentityDigests.value(route.outTradeNo()))
                .query((rs, rowNum) -> new StoredPaymentIdentity(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint"),
                        CallbackIdentity.purgedPayment(
                                rs.getString("final_status"),
                                rs.getString("transaction_id_digest"),
                                rs.getLong("amount_cent"),
                                rs.getString("currency"))
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
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
                            rs.getObject("payment_config_id", Long.class),
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
                            rs.getObject("payment_config_id", Long.class),
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
                        rs.getObject("payment_config_id", Long.class),
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
                        rs.getObject("payment_config_id", Long.class),
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
        return identity.outRefundNoDigest() == null
                || constantTimeEquals(
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

    private List<HistoricalConfigIdentity> historicalConfigIdentities(NotificationKind notificationKind) {
        if (notificationKind == NotificationKind.REFUND) {
            return jdbcClient.sql("""
                            select history.payment_config_id,
                                   history.payment_config_fingerprint,
                                   max(history.last_used_at) as last_used_at
                            from (
                                select po.payment_config_id,
                                       po.payment_config_fingerprint,
                                       ro.updated_at as last_used_at
                                from refund_order ro
                                join payment_order po on po.id = ro.payment_order_id
                                where ro.notification_route_token is null
                                union all
                                select payment_config_id,
                                       payment_config_fingerprint,
                                       purged_at as last_used_at
                                from purged_refund_identity
                                where notification_route_digest is null
                            ) history
                            where history.payment_config_id is not null
                               or history.payment_config_fingerprint <> ''
                            group by history.payment_config_id, history.payment_config_fingerprint
                            order by last_used_at desc, history.payment_config_id desc,
                                     history.payment_config_fingerprint desc
                            limit :limit
                            """)
                    .param("limit", MAX_HISTORICAL_CONFIG_CANDIDATES)
                    .query((rs, rowNum) -> new HistoricalConfigIdentity(
                            rs.getObject("payment_config_id", Long.class),
                            rs.getString("payment_config_fingerprint")
                    ))
                    .list();
        }
        return jdbcClient.sql("""
                        select history.payment_config_id,
                               history.payment_config_fingerprint,
                               max(history.last_used_at) as last_used_at
                        from (
                            select payment_config_id,
                                   payment_config_fingerprint,
                                   updated_at as last_used_at
                            from payment_order
                            where notification_route_token is null
                            union all
                            select payment_config_id,
                                   payment_config_fingerprint,
                                   purged_at as last_used_at
                            from purged_payment_identity
                            where notification_route_digest is null
                        ) history
                        where history.payment_config_id is not null
                           or history.payment_config_fingerprint <> ''
                        group by history.payment_config_id, history.payment_config_fingerprint
                        order by last_used_at desc, history.payment_config_id desc,
                                 history.payment_config_fingerprint desc
                        limit :limit
                        """)
                .param("limit", MAX_HISTORICAL_CONFIG_CANDIDATES)
                .query((rs, rowNum) -> new HistoricalConfigIdentity(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint")
                ))
                .list();
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                (left == null ? "" : left).getBytes(StandardCharsets.US_ASCII),
                (right == null ? "" : right).getBytes(StandardCharsets.US_ASCII));
    }

    private String identityKey(Long configId, String fingerprint) {
        return configId == null
                ? "ENV:" + (fingerprint == null ? "" : fingerprint)
                : "DB:" + configId;
    }

    private record HistoricalConfigIdentity(Long configId, String fingerprint) {
    }

    private record StoredPaymentIdentity(
            Long configId,
            String fingerprint,
            CallbackIdentity callbackIdentity
    ) {
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
        LEGACY,
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
        public ParsedNotification(ResolvedPaymentConfig config, T notification) {
            this(config, notification, new CallbackIdentity(
                    NotificationKind.LEGACY, false, null, null,
                    "", "", "", "", 0L, ""));
        }

        public boolean purged() {
            return callbackIdentity != null && callbackIdentity.purged();
        }
    }
}
