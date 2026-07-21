package org.muybaby.shopserver.payment.service;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.payment.PaymentInitiationProperties;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.WechatPaymentParamsResponse;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayRequest;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentInitiationService {

    private static final String CURRENCY_CNY = "CNY";
    private static final String OPERATOR_TYPE_APP = "APP";
    private static final int DEFAULT_PAYMENT_EXPIRE_MINUTES = 24 * 60;

    private final JdbcClient jdbcClient;
    private final PaymentProperties paymentProperties;
    private final PaymentInitiationProperties initiationProperties;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentNotificationRouteService paymentNotificationRouteService;
    private final WechatPayProvider wechatPayProvider;
    private final OrderStatusLogService orderStatusLogService;
    private final PaymentAttemptService paymentAttemptService;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;

    public PaymentInitiationService(
            JdbcClient jdbcClient,
            PaymentProperties paymentProperties,
            PaymentInitiationProperties initiationProperties,
            PaymentConfigResolver paymentConfigResolver,
            PaymentNotificationRouteService paymentNotificationRouteService,
            WechatPayProvider wechatPayProvider,
            OrderStatusLogService orderStatusLogService,
            PaymentAttemptService paymentAttemptService,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentProperties = paymentProperties;
        this.initiationProperties = initiationProperties;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
        this.wechatPayProvider = wechatPayProvider;
        this.orderStatusLogService = orderStatusLogService;
        this.paymentAttemptService = paymentAttemptService;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public WechatPaymentParamsResponse initiate(Long userId, Long orderId) {
        WechatPaymentParamsResponse response = withoutTransaction.execute(
                status -> initiateOutsideTransaction(userId, orderId));
        if (response == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return response;
    }

    private WechatPaymentParamsResponse initiateOutsideTransaction(Long userId, Long orderId) {
        PreparedInitiation prepared = prepareWithStableConfiguration(userId, orderId);
        ResolvedPaymentConfig config = prepared.config();
        InitiationPreparation preparation = prepared.preparation();
        if (preparation == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (preparation.completedPayment() != null) {
            CompletedPayment completed = preparation.completedPayment();
            return buildPaymentParams(config, completed.outTradeNo(), completed.prepayId());
        }

        ClaimedPayment claimed = preparation.claimedPayment();
        if (claimed == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        long paymentAttemptId;
        try {
            paymentAttemptId = paymentAttemptService.started(
                    claimed.orderId(), claimed.outTradeNo(), claimed.amountCent(), now());
        } catch (RuntimeException ex) {
            releaseFailedClaim(claimed, ex);
            throw ex;
        }

        WechatJsapiPrepayResult prepay;
        try {
            prepay = wechatPayProvider.createJsapiPrepay(config, toProviderRequest(claimed, config));
            validatePrepayResult(prepay);
        } catch (RuntimeException ex) {
            recordFailedAttempt(paymentAttemptId, ex);
            releaseFailedClaim(claimed, ex);
            throw ex;
        }

        try {
            paymentAttemptService.prepaySucceeded(paymentAttemptId, now());
        } catch (RuntimeException ex) {
            recordFailedAttempt(paymentAttemptId, ex);
            releaseFailedClaim(claimed, ex);
            throw ex;
        }

        PrepayFinalization finalization = requiresNewTransaction.execute(
                status -> finalizePrepay(claimed, paymentAttemptId, prepay));
        if (finalization == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (finalization.alreadyCompleted()) {
            return buildPaymentParams(config, claimed.outTradeNo(), finalization.prepayId());
        }
        return toResponse(prepay);
    }

    private PreparedInitiation prepareWithStableConfiguration(Long userId, Long orderId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            InitiationRoute route = findInitiationRoute(userId, orderId);
            if (OrderStatus.CREATED.name().equals(route.orderStatus())) {
                ResolvedPaymentConfig config = paymentConfigResolver.resolve();
                InitiationPreparation preparation;
                try {
                    preparation = requiresNewTransaction.execute(
                            status -> prepareCreatedInitiation(userId, orderId, config));
                } catch (BusinessException ex) {
                    if (ex.errorCode() == ErrorCode.PAYMENT_CONFIGURATION_CHANGED) {
                        continue;
                    }
                    throw ex;
                }
                if (preparation == null) {
                    continue;
                }
                return new PreparedInitiation(config, preparation);
            }
            if (!OrderStatus.PAYING.name().equals(route.orderStatus()) || route.paymentOrderId() == null) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            ResolvedPaymentConfig config = paymentConfigResolver.resolveForPayment(
                    route.paymentConfigId(), route.paymentConfigFingerprint());
            InitiationPreparation preparation = requiresNewTransaction.execute(
                    status -> prepareExistingInitiation(userId, orderId, route.paymentOrderId(), config));
            if (preparation == null) {
                continue;
            }
            return new PreparedInitiation(config, preparation);
        }
        throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
    }

    private InitiationPreparation prepareCreatedInitiation(
            Long userId,
            Long orderId,
            ResolvedPaymentConfig config
    ) {
        OrderInitiationRow order = findOrderForUpdate(orderId, userId);
        LocalDateTime claimedAt = now();
        if (!OrderStatus.CREATED.name().equals(order.status())) {
            return null;
        }
        ResolvedPaymentConfig lockedConfig = paymentConfigResolver.lockForPaymentCreation(config);
        return createPreparation(order, userId, lockedConfig, claimedAt);
    }

    private InitiationPreparation prepareExistingInitiation(
            Long userId,
            Long orderId,
            Long paymentOrderId,
            ResolvedPaymentConfig config
    ) {
        OrderInitiationRow order = findOrderForUpdate(orderId, userId);
        PaymentInitiationRow payment = findPaymentForUpdate(paymentOrderId);
        if (!orderId.equals(payment.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        LocalDateTime claimedAt = now();
        validateStableRequest(order, payment, config);
        if (OrderStatus.PAYING.name().equals(payment.status())) {
            if (!payment.expiresAt().isAfter(claimedAt) || !StringUtils.hasText(payment.prepayId())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            return new InitiationPreparation(
                    null,
                    new CompletedPayment(payment.outTradeNo(), payment.prepayId())
            );
        }
        if (!"PREPARING".equals(payment.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        LocalDateTime expiredClaimBefore = claimedAt.minus(initiationProperties.claimTimeout());
        if (StringUtils.hasText(payment.claimToken())
                && payment.claimedAt() != null
                && payment.claimedAt().isAfter(expiredClaimBefore)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String claimToken = UUID.randomUUID().toString();
        int claimedRows = jdbcClient.sql("""
                        update payment_order
                        set prepay_claim_token = :claimToken,
                            prepay_claimed_at = :claimedAt,
                            prepay_attempts = prepay_attempts + 1,
                            updated_at = :updatedAt
                        where id = :paymentOrderId
                          and status = 'PREPARING'
                          and (
                              prepay_claim_token is null
                              or prepay_claimed_at is null
                              or prepay_claimed_at <= :expiredClaimBefore
                          )
                        """)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("updatedAt", claimedAt)
                .param("paymentOrderId", payment.paymentOrderId())
                .param("expiredClaimBefore", expiredClaimBefore)
                .update();
        requireUpdated(claimedRows);
        return new InitiationPreparation(toClaimedPayment(order, payment, claimToken), null);
    }

    private InitiationPreparation createPreparation(
            OrderInitiationRow order,
            Long userId,
            ResolvedPaymentConfig config,
            LocalDateTime claimedAt
    ) {
        String payerOpenid = findOpenid(userId);
        String outTradeNo = outTradeNo(order);
        LocalDateTime expiresAt = claimedAt.plusMinutes(expireMinutes());
        String notificationRouteToken = paymentNotificationRouteService.issueToken();
        // Validate the exact callback URL before persisting a payment that would otherwise be
        // permanently bound to an unusable immutable configuration.
        paymentNotificationRouteService.payNotifyUrl(
                config.notifyUrl(), notificationRouteToken);
        String requestDigest = requestDigest(
                outTradeNo,
                order.payableAmountCent(),
                CURRENCY_CNY,
                payerOpenid,
                expiresAt,
                notificationRouteToken,
                config
        );
        String paymentConfigFingerprint = paymentConfigResolver.captureForPayment(config);
        String claimToken = UUID.randomUUID().toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int insertedRows = jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, payment_config_fingerprint,
                             notification_route_token, out_trade_no, prepay_id, payer_openid, status,
                             amount_cent, currency, request_digest, expires_at,
                             prepay_claim_token, prepay_claimed_at, prepay_attempts,
                             created_at, updated_at)
                        values
                            (:orderId, :paymentConfigId, :paymentConfigFingerprint,
                             :notificationRouteToken, :outTradeNo, '', :payerOpenid, 'PREPARING',
                             :amountCent, :currency, :requestDigest, :expiresAt,
                             :claimToken, :claimedAt, 1,
                             :createdAt, :updatedAt)
                        """)
                .param("orderId", order.orderId())
                .param("paymentConfigId", config.configId())
                .param("paymentConfigFingerprint", paymentConfigFingerprint)
                .param("notificationRouteToken", notificationRouteToken)
                .param("outTradeNo", outTradeNo)
                .param("payerOpenid", payerOpenid)
                .param("amountCent", order.payableAmountCent())
                .param("currency", CURRENCY_CNY)
                .param("requestDigest", requestDigest)
                .param("expiresAt", expiresAt)
                .param("claimToken", claimToken)
                .param("claimedAt", claimedAt)
                .param("createdAt", claimedAt)
                .param("updatedAt", claimedAt)
                .update(keyHolder, "id");
        if (insertedRows != 1 || keyHolder.getKey() == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        int orderRows = jdbcClient.sql("""
                        update shop_order
                        set status = 'PAYING',
                            merchant_trade_no = :outTradeNo,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = 'CREATED'
                        """)
                .param("outTradeNo", outTradeNo)
                .param("updatedAt", claimedAt)
                .param("orderId", order.orderId())
                .update();
        requireUpdated(orderRows);
        orderStatusLogService.record(
                order.orderId(), OrderStatus.CREATED.name(), OrderStatus.PAYING.name(),
                "PAYMENT_STARTED", OPERATOR_TYPE_APP, userId, "发起微信支付", claimedAt
        );
        ClaimedPayment claimed = new ClaimedPayment(
                keyHolder.getKey().longValue(),
                order.orderId(),
                order.orderNo(),
                outTradeNo,
                payerOpenid,
                order.payableAmountCent(),
                CURRENCY_CNY,
                expiresAt,
                notificationRouteToken,
                claimToken
        );
        return new InitiationPreparation(claimed, null);
    }

    private PrepayFinalization finalizePrepay(
            ClaimedPayment claimed,
            long paymentAttemptId,
            WechatJsapiPrepayResult prepay
    ) {
        PaymentFinalizationRow current = jdbcClient.sql("""
                        select status, prepay_id, prepay_claim_token
                        from payment_order
                        where id = :paymentOrderId
                        for update
                        """)
                .param("paymentOrderId", claimed.paymentOrderId())
                .query(this::mapPaymentFinalizationRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (OrderStatus.PAYING.name().equals(current.status())) {
            if (!prepay.prepayId().equals(current.prepayId())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            return new PrepayFinalization(current.prepayId(), true);
        }
        if (!"PREPARING".equals(current.status())
                || !claimed.claimToken().equals(current.claimToken())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        LocalDateTime finalizedAt = now();
        int updatedRows = jdbcClient.sql("""
                        update payment_order
                        set status = 'PAYING',
                            prepay_id = :prepayId,
                            prepay_claim_token = null,
                            prepay_claimed_at = null,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :updatedAt
                        where id = :paymentOrderId
                          and status = 'PREPARING'
                          and prepay_claim_token = :claimToken
                        """)
                .param("prepayId", prepay.prepayId())
                .param("updatedAt", finalizedAt)
                .param("paymentOrderId", claimed.paymentOrderId())
                .param("claimToken", claimed.claimToken())
                .update();
        requireUpdated(updatedRows);
        paymentAttemptService.bindPaymentOrder(paymentAttemptId, claimed.paymentOrderId(), finalizedAt);
        return new PrepayFinalization(prepay.prepayId(), false);
    }

    private void releaseFailedClaim(ClaimedPayment claimed, RuntimeException failure) {
        try {
            requiresNewTransaction.executeWithoutResult(status -> jdbcClient.sql("""
                            update payment_order
                            set prepay_claim_token = null,
                                prepay_claimed_at = null,
                                last_error_code = :errorCode,
                                last_error_message = 'Provider prepay request failed; retry allowed',
                                updated_at = :updatedAt
                            where id = :paymentOrderId
                              and status = 'PREPARING'
                              and prepay_claim_token = :claimToken
                            """)
                    .param("errorCode", safeErrorCode(failure))
                    .param("updatedAt", now())
                    .param("paymentOrderId", claimed.paymentOrderId())
                    .param("claimToken", claimed.claimToken())
                    .update());
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private void recordFailedAttempt(long paymentAttemptId, RuntimeException failure) {
        try {
            paymentAttemptService.prepayFailed(paymentAttemptId, failure, now());
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private OrderInitiationRow findOrderForUpdate(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               status,
                               payable_amount_cent
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderInitiationRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private InitiationRoute findInitiationRoute(Long userId, Long orderId) {
        return jdbcClient.sql("""
                        select o.status as order_status,
                               po.id as payment_order_id,
                               po.payment_config_id,
                               po.payment_config_fingerprint
                        from shop_order o
                        left join payment_order po on po.id = (
                            select max(candidate.id)
                            from payment_order candidate
                            where candidate.order_id = o.id
                              and candidate.status in ('PREPARING', 'PAYING')
                        )
                        where o.id = :orderId
                          and o.user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapInitiationRoute)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private PaymentInitiationRow findPaymentForUpdate(Long paymentOrderId) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               payment_config_id,
                               payment_config_fingerprint,
                               out_trade_no,
                               prepay_id,
                               payer_openid,
                               status,
                               amount_cent,
                               currency,
                               request_digest,
                               expires_at,
                               notification_route_token,
                               prepay_claim_token,
                               prepay_claimed_at,
                               prepay_attempts
                        from payment_order
                        where id = :paymentOrderId
                        for update
                        """)
                .param("paymentOrderId", paymentOrderId)
                .query(this::mapPaymentInitiationRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private String findOpenid(Long userId) {
        return jdbcClient.sql("""
                        select openid
                        from app_user
                        where id = :userId
                          and status = 'ENABLED'
                        """)
                .param("userId", userId)
                .query(String.class)
                .optional()
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private void validateStableRequest(
            OrderInitiationRow order,
            PaymentInitiationRow payment,
            ResolvedPaymentConfig config
    ) {
        String expectedDigest = requestDigest(
                payment.outTradeNo(),
                payment.amountCent(),
                payment.currency(),
                payment.payerOpenid(),
                payment.expiresAt(),
                payment.notificationRouteToken(),
                config
        );
        if (payment.amountCent() != order.payableAmountCent()
                || !Objects.equals(payment.paymentConfigId(), config.configId())
                || (StringUtils.hasText(payment.paymentConfigFingerprint())
                    && !paymentConfigResolver.fingerprint(config).equals(payment.paymentConfigFingerprint()))
                || !CURRENCY_CNY.equals(payment.currency())
                || !outTradeNo(order).equals(payment.outTradeNo())
                || !StringUtils.hasText(payment.payerOpenid())
                || payment.expiresAt() == null
                || !(expectedDigest.equals(payment.requestDigest())
                    || isLegacyCompletedPaymentDigest(payment))) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private boolean isLegacyCompletedPaymentDigest(PaymentInitiationRow payment) {
        // V39 backfills existing rows with zero, while every payment created by this service starts
        // at one. This keeps the legacy digest escape hatch limited to rolling-upgrade data.
        if (!OrderStatus.PAYING.name().equals(payment.status()) || payment.prepayAttempts() != 0) {
            return false;
        }
        String legacyDigest = sha256(String.join("|",
                payment.outTradeNo(),
                Long.toString(payment.amountCent()),
                payment.payerOpenid()
        ));
        return legacyDigest.equals(payment.requestDigest());
    }

    private ClaimedPayment toClaimedPayment(
            OrderInitiationRow order,
            PaymentInitiationRow payment,
            String claimToken
    ) {
        return new ClaimedPayment(
                payment.paymentOrderId(),
                order.orderId(),
                order.orderNo(),
                payment.outTradeNo(),
                payment.payerOpenid(),
                payment.amountCent(),
                payment.currency(),
                payment.expiresAt(),
                payment.notificationRouteToken(),
                claimToken
        );
    }

    private WechatJsapiPrepayRequest toProviderRequest(ClaimedPayment claimed, ResolvedPaymentConfig config) {
        return new WechatJsapiPrepayRequest(
                "Shop order " + claimed.orderNo(),
                claimed.outTradeNo(),
                claimed.amountCent(),
                claimed.currency(),
                claimed.payerOpenid(),
                paymentNotificationRouteService.payNotifyUrl(
                        config.notifyUrl(), claimed.notificationRouteToken()),
                claimed.expiresAt()
        );
    }

    private WechatPaymentParamsResponse buildPaymentParams(
            ResolvedPaymentConfig config,
            String outTradeNo,
            String prepayId
    ) {
        String packageValue = "prepay_id=" + prepayId;
        if (Boolean.TRUE.equals(paymentProperties.mockEnabled())) {
            return new WechatPaymentParamsResponse(
                    "1783500000",
                    "mock-nonce-" + outTradeNo,
                    packageValue,
                    "RSA",
                    "mock-pay-sign-" + outTradeNo
            );
        }
        requireSigningMaterial(config);
        try {
            String timeStamp = String.valueOf(clock.instant().getEpochSecond());
            String nonceStr = UUID.randomUUID().toString().replace("-", "");
            String message = config.appId() + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
            String paySign = new RSAPublicKeyConfig.Builder()
                    .merchantId(config.mchId())
                    .merchantSerialNumber(config.merchantSerialNo())
                    .privateKey(config.privateKeyPem())
                    .apiV3Key(config.apiV3Key())
                    .publicKeyId(config.wechatPublicKeyId())
                    .publicKey(config.wechatPublicKeyPem())
                    .build()
                    .createSigner()
                    .sign(message)
                    .getSign();
            return new WechatPaymentParamsResponse(timeStamp, nonceStr, packageValue, "RSA", paySign);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private WechatPaymentParamsResponse toResponse(WechatJsapiPrepayResult prepay) {
        return new WechatPaymentParamsResponse(
                prepay.timeStamp(),
                prepay.nonceStr(),
                prepay.packageValue(),
                prepay.signType(),
                prepay.paySign()
        );
    }

    private void validatePrepayResult(WechatJsapiPrepayResult prepay) {
        if (prepay == null
                || !StringUtils.hasText(prepay.prepayId())
                || !StringUtils.hasText(prepay.timeStamp())
                || !StringUtils.hasText(prepay.nonceStr())
                || !StringUtils.hasText(prepay.packageValue())
                || !StringUtils.hasText(prepay.signType())
                || !StringUtils.hasText(prepay.paySign())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private String requestDigest(
            String outTradeNo,
            long amountCent,
            String currency,
            String payerOpenid,
            LocalDateTime expiresAt,
            String notificationRouteToken,
            ResolvedPaymentConfig config
    ) {
        String stableRequest = String.join("|",
                nullToEmpty(outTradeNo),
                Long.toString(amountCent),
                nullToEmpty(currency),
                nullToEmpty(payerOpenid),
                expiresAt == null ? "" : expiresAt.withNano(0).toString(),
                config.source() == null ? "" : config.source().name(),
                config.configId() == null ? "" : config.configId().toString(),
                nullToEmpty(config.appId()),
                nullToEmpty(config.mchId()),
                nullToEmpty(config.notifyUrl())
        );
        if (StringUtils.hasText(notificationRouteToken)) {
            stableRequest = stableRequest + "|" + notificationRouteToken;
        }
        return sha256(stableRequest);
    }

    private String outTradeNo(OrderInitiationRow order) {
        String candidate = "P" + order.orderNo();
        return candidate.length() <= 32 ? candidate : "PAY" + order.orderId();
    }

    private int expireMinutes() {
        return paymentProperties.expireMinutes() == null || paymentProperties.expireMinutes() < 1
                ? DEFAULT_PAYMENT_EXPIRE_MINUTES
                : paymentProperties.expireMinutes();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).withNano(0);
    }

    private String safeErrorCode(RuntimeException failure) {
        String simpleName = failure == null ? null : failure.getClass().getSimpleName();
        if (!StringUtils.hasText(simpleName)) {
            return "RuntimeException";
        }
        return simpleName.length() <= 64 ? simpleName : simpleName.substring(0, 64);
    }

    private void requireUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireSigningMaterial(ResolvedPaymentConfig config) {
        if (!StringUtils.hasText(config.appId())
                || !StringUtils.hasText(config.mchId())
                || !StringUtils.hasText(config.merchantSerialNo())
                || !StringUtils.hasText(config.apiV3Key())
                || !StringUtils.hasText(config.privateKeyPem())
                || !StringUtils.hasText(config.wechatPublicKeyId())
                || !StringUtils.hasText(config.wechatPublicKeyPem())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private OrderInitiationRow mapOrderInitiationRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderInitiationRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent")
        );
    }

    private PaymentInitiationRow mapPaymentInitiationRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentInitiationRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no"),
                rs.getString("prepay_id"),
                rs.getString("payer_openid"),
                rs.getString("status"),
                rs.getLong("amount_cent"),
                rs.getString("currency"),
                rs.getString("request_digest"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getString("notification_route_token"),
                rs.getString("prepay_claim_token"),
                rs.getObject("prepay_claimed_at", LocalDateTime.class),
                rs.getInt("prepay_attempts")
        );
    }

    private InitiationRoute mapInitiationRoute(ResultSet rs, int rowNum) throws SQLException {
        return new InitiationRoute(
                rs.getString("order_status"),
                rs.getObject("payment_order_id", Long.class),
                rs.getObject("payment_config_id", Long.class),
                rs.getString("payment_config_fingerprint")
        );
    }

    private PaymentFinalizationRow mapPaymentFinalizationRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentFinalizationRow(
                rs.getString("status"),
                rs.getString("prepay_id"),
                rs.getString("prepay_claim_token")
        );
    }

    private record InitiationPreparation(ClaimedPayment claimedPayment, CompletedPayment completedPayment) {
    }

    private record PreparedInitiation(ResolvedPaymentConfig config, InitiationPreparation preparation) {
    }

    private record InitiationRoute(
            String orderStatus,
            Long paymentOrderId,
            Long paymentConfigId,
            String paymentConfigFingerprint
    ) {
    }

    private record CompletedPayment(String outTradeNo, String prepayId) {
    }

    private record ClaimedPayment(
            Long paymentOrderId,
            Long orderId,
            String orderNo,
            String outTradeNo,
            String payerOpenid,
            long amountCent,
            String currency,
            LocalDateTime expiresAt,
            String notificationRouteToken,
            String claimToken
    ) {
    }

    private record OrderInitiationRow(Long orderId, String orderNo, String status, long payableAmountCent) {
    }

    private record PaymentInitiationRow(
            Long paymentOrderId,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String prepayId,
            String payerOpenid,
            String status,
            long amountCent,
            String currency,
            String requestDigest,
            LocalDateTime expiresAt,
            String notificationRouteToken,
            String claimToken,
            LocalDateTime claimedAt,
            int prepayAttempts
    ) {
    }

    private record PaymentFinalizationRow(String status, String prepayId, String claimToken) {
    }

    private record PrepayFinalization(String prepayId, boolean alreadyCompleted) {
    }
}
