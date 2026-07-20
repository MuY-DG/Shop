package org.muybaby.shopserver.payment.config;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Selects the merchant configuration capable of verifying and decrypting a payment notification.
 * The notification resource is encrypted, so its merchant order number cannot be used to select a
 * configuration before decryption. The current configuration is attempted first, followed by a
 * bounded set of recently used database revisions and encrypted ENV snapshots.
 */
@Service
public class PaymentNotificationConfigSelector {

    private static final int MAX_HISTORICAL_CONFIG_CANDIDATES = 32;

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentConfigIdentityValidator paymentConfigIdentityValidator;

    public PaymentNotificationConfigSelector(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            PaymentConfigIdentityValidator paymentConfigIdentityValidator
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentConfigIdentityValidator = paymentConfigIdentityValidator;
    }

    public <T> ParsedNotification<T> parse(
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

        for (HistoricalConfigIdentity identity : historicalConfigIdentities()) {
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
            return new ParsedNotification<>(bootstrapConfig, bootstrapNotification);
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
                return new ParsedNotification<>(exactConfig, exactNotification);
            } catch (BusinessException exactFailure) {
                throw identityMismatch;
            }
        }
    }

    private StoredPaymentIdentity findStoredPaymentIdentity(NotificationRoute route) {
        if (route.refund()) {
            return jdbcClient.sql("""
                            select po.payment_config_id, po.payment_config_fingerprint
                            from refund_order ro
                            join payment_order po on po.id = ro.payment_order_id
                            where ro.out_refund_no = :outRefundNo
                              and po.out_trade_no = :outTradeNo
                            """)
                    .param("outRefundNo", route.outRefundNo())
                    .param("outTradeNo", route.outTradeNo())
                    .query((rs, rowNum) -> new StoredPaymentIdentity(
                            rs.getObject("payment_config_id", Long.class),
                            rs.getString("payment_config_fingerprint")
                    ))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        }
        return jdbcClient.sql("""
                        select payment_config_id, payment_config_fingerprint
                        from payment_order
                        where out_trade_no = :outTradeNo
                        """)
                .param("outTradeNo", route.outTradeNo())
                .query((rs, rowNum) -> new StoredPaymentIdentity(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
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

    private List<HistoricalConfigIdentity> historicalConfigIdentities() {
        return jdbcClient.sql("""
                        select payment_config_id,
                               payment_config_fingerprint,
                               max(updated_at) as last_used_at
                        from payment_order
                        where payment_config_id is not null
                           or payment_config_fingerprint <> ''
                        group by payment_config_id, payment_config_fingerprint
                        order by last_used_at desc, payment_config_id desc,
                                 payment_config_fingerprint desc
                        limit :limit
                        """)
                .param("limit", MAX_HISTORICAL_CONFIG_CANDIDATES)
                .query((rs, rowNum) -> new HistoricalConfigIdentity(
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint")
                ))
                .list();
    }

    private String identityKey(Long configId, String fingerprint) {
        return configId == null
                ? "ENV:" + (fingerprint == null ? "" : fingerprint)
                : "DB:" + configId;
    }

    private record HistoricalConfigIdentity(Long configId, String fingerprint) {
    }

    private record StoredPaymentIdentity(Long configId, String fingerprint) {
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

    public record ParsedNotification<T>(ResolvedPaymentConfig config, T notification) {
    }
}
