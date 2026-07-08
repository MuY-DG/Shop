package org.muybaby.shopserver.payment.service;

import com.wechat.pay.java.core.RSAPublicKeyConfig;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.coupon.UserCouponStatus;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.StockLockStatus;
import org.muybaby.shopserver.order.service.OrderCloseService;
import org.muybaby.shopserver.payment.PaymentProperties;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.dto.PaymentCancelResponse;
import org.muybaby.shopserver.payment.dto.PaymentSyncResponse;
import org.muybaby.shopserver.payment.dto.WechatPaymentParamsResponse;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayRequest;
import org.muybaby.shopserver.payment.provider.WechatJsapiPrepayResult;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class AppPaymentService {

    private static final String CURRENCY_CNY = "CNY";
    private static final String OPERATOR_TYPE_APP = "APP";

    private final JdbcClient jdbcClient;
    private final PaymentProperties paymentProperties;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;
    private final OrderCloseService orderCloseService;

    public AppPaymentService(
            JdbcClient jdbcClient,
            PaymentProperties paymentProperties,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider,
            OrderCloseService orderCloseService
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentProperties = paymentProperties;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
        this.orderCloseService = orderCloseService;
    }

    @Transactional
    public WechatPaymentParamsResponse pay(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        OrderPaymentRow order = findOrderForUpdate(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        if (OrderStatus.PAYING.name().equals(order.status())) {
            PaymentOrderRow activePayment = findActivePaymentForOrder(orderId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
            return buildPaymentParams(config, activePayment.outTradeNo(), activePayment.prepayId());
        }
        if (!OrderStatus.CREATED.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        String payerOpenid = findOpenid(userId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(expireMinutes());
        String outTradeNo = outTradeNo(order);
        WechatJsapiPrepayRequest request = new WechatJsapiPrepayRequest(
                "Shop order " + order.orderNo(),
                outTradeNo,
                order.payableAmountCent(),
                CURRENCY_CNY,
                payerOpenid,
                config.notifyUrl(),
                expiresAt
        );
        WechatJsapiPrepayResult prepay = wechatPayProvider.createJsapiPrepay(config, request);
        jdbcClient.sql("""
                        insert into payment_order
                            (order_id, payment_config_id, out_trade_no, prepay_id, payer_openid, status,
                             amount_cent, currency, request_digest, expires_at, created_at, updated_at)
                        values
                            (:orderId, :paymentConfigId, :outTradeNo, :prepayId, :payerOpenid, 'PAYING',
                             :amountCent, :currency, :requestDigest, :expiresAt, :createdAt, :updatedAt)
                        """)
                .param("orderId", order.orderId())
                .param("paymentConfigId", activePaymentConfigId())
                .param("outTradeNo", outTradeNo)
                .param("prepayId", prepay.prepayId())
                .param("payerOpenid", payerOpenid)
                .param("amountCent", order.payableAmountCent())
                .param("currency", CURRENCY_CNY)
                .param("requestDigest", sha256(outTradeNo + "|" + order.payableAmountCent() + "|" + payerOpenid))
                .param("expiresAt", expiresAt)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        int updatedRows = jdbcClient.sql("""
                        update shop_order
                        set status = 'PAYING',
                            merchant_trade_no = :outTradeNo,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = 'CREATED'
                        """)
                .param("outTradeNo", outTradeNo)
                .param("updatedAt", now)
                .param("orderId", order.orderId())
                .update();
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return toResponse(prepay);
    }

    @Transactional
    public PaymentSyncResponse sync(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        OrderPaymentRow order = findOrderForUpdate(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (OrderStatus.PAID.name().equals(order.status())) {
            return new PaymentSyncResponse(order.orderId(), order.status(), order.paymentTransactionId());
        }
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        PaymentOrderRow payment = findActivePaymentForOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        WechatPayOrderQueryResult queryResult = wechatPayProvider.queryOrder(config, payment.outTradeNo());
        if (!queryResult.paid()) {
            return new PaymentSyncResponse(order.orderId(), order.status(), "");
        }
        PaidFinalizationResult result = finalizePaid(
                queryResult.outTradeNo(),
                queryResult.transactionId(),
                queryResult.amountCent(),
                queryResult.paidAt() == null ? LocalDateTime.now() : queryResult.paidAt(),
                ""
        );
        return new PaymentSyncResponse(result.orderId(), result.orderStatus(), result.transactionId());
    }

    @Transactional
    public PaymentCancelResponse cancel(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        OrderPaymentRow order = findOrderForUpdate(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (OrderStatus.CREATED.name().equals(order.status())) {
            orderCloseService.closeCreatedOrder(order.orderId(), "APP_CANCEL", OPERATOR_TYPE_APP, userId);
            return new PaymentCancelResponse(order.orderId(), OrderStatus.CLOSED.name());
        }
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        closePayingPayment(config, order.orderId(), userId, "APP_CANCEL");
        return new PaymentCancelResponse(order.orderId(), OrderStatus.CLOSED.name());
    }

    @Transactional
    public PaidFinalizationResult finalizePaid(
            String outTradeNo,
            String transactionId,
            long amountCent,
            LocalDateTime paidAt,
            String callbackDigest
    ) {
        PaymentOrderRow payment = findPaymentForUpdate(outTradeNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (OrderStatus.PAID.name().equals(payment.status())) {
            validatePaidDuplicate(payment, transactionId, amountCent);
            OrderPaymentRow order = findOrderForUpdate(payment.orderId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
            validatePaidDuplicateOrder(order, outTradeNo, transactionId, amountCent);
            return new PaidFinalizationResult(order.orderId(), order.status(), payment.transactionId(), true);
        }
        if (!OrderStatus.PAYING.name().equals(payment.status()) || payment.amountCent() != amountCent) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        OrderPaymentRow order = findOrderForUpdate(payment.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!OrderStatus.PAYING.name().equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        LocalDateTime effectivePaidAt = paidAt == null ? LocalDateTime.now() : paidAt;
        jdbcClient.sql("""
                        update payment_order
                        set status = 'PAID',
                            transaction_id = :transactionId,
                            callback_digest = :callbackDigest,
                            paid_at = :paidAt,
                            updated_at = :updatedAt
                        where out_trade_no = :outTradeNo
                          and status = 'PAYING'
                        """)
                .param("transactionId", transactionId)
                .param("callbackDigest", nullToEmpty(callbackDigest))
                .param("paidAt", effectivePaidAt)
                .param("updatedAt", LocalDateTime.now())
                .param("outTradeNo", outTradeNo)
                .update();
        jdbcClient.sql("""
                        update shop_order
                        set status = 'PAID',
                            paid_amount_cent = :paidAmountCent,
                            paid_at = :paidAt,
                            payment_transaction_id = :transactionId,
                            merchant_trade_no = :outTradeNo,
                            updated_at = :updatedAt
                        where id = :orderId
                          and status = 'PAYING'
                        """)
                .param("paidAmountCent", amountCent)
                .param("paidAt", effectivePaidAt)
                .param("transactionId", transactionId)
                .param("outTradeNo", outTradeNo)
                .param("updatedAt", LocalDateTime.now())
                .param("orderId", order.orderId())
                .update();
        jdbcClient.sql("""
                        update stock_lock
                        set status = :confirmed,
                            updated_at = :updatedAt
                        where order_id = :orderId
                          and status = :locked
                        """)
                .param("confirmed", StockLockStatus.CONFIRMED.name())
                .param("updatedAt", LocalDateTime.now())
                .param("orderId", order.orderId())
                .param("locked", StockLockStatus.LOCKED.name())
                .update();
        if (order.userCouponId() != null) {
            jdbcClient.sql("""
                            update user_coupon
                            set status = :used,
                                used_order_id = :orderId,
                                used_at = :usedAt,
                                updated_at = :updatedAt
                            where id = :userCouponId
                              and locked_order_id = :orderId
                              and status = :locked
                            """)
                    .param("used", UserCouponStatus.USED.name())
                    .param("orderId", order.orderId())
                    .param("usedAt", effectivePaidAt)
                    .param("updatedAt", LocalDateTime.now())
                    .param("userCouponId", order.userCouponId())
                    .param("locked", UserCouponStatus.LOCKED.name())
                    .update();
        }
        return new PaidFinalizationResult(order.orderId(), OrderStatus.PAID.name(), transactionId, false);
    }

    @Transactional
    public void closePayingPayment(ResolvedPaymentConfig config, Long orderId, Long operatorId, String closeReason) {
        PaymentOrderRow payment = findActivePaymentForOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        wechatPayProvider.closeOrder(config, payment.outTradeNo());
        jdbcClient.sql("""
                        update payment_order
                        set status = 'CLOSED',
                            closed_at = :closedAt,
                            updated_at = :updatedAt
                        where out_trade_no = :outTradeNo
                          and status = 'PAYING'
                        """)
                .param("closedAt", LocalDateTime.now())
                .param("updatedAt", LocalDateTime.now())
                .param("outTradeNo", payment.outTradeNo())
                .update();
        orderCloseService.closePayingOrder(orderId, closeReason, OPERATOR_TYPE_APP, operatorId);
    }

    private WechatPaymentParamsResponse buildPaymentParams(ResolvedPaymentConfig config, String outTradeNo, String prepayId) {
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
            String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
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

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private Optional<OrderPaymentRow> findOrderForUpdate(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               user_id,
                               status,
                               payable_amount_cent,
                               paid_amount_cent,
                               user_coupon_id,
                               payment_transaction_id,
                               merchant_trade_no
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrderPaymentRow)
                .optional();
    }

    private Optional<OrderPaymentRow> findOrderForUpdate(Long orderId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               user_id,
                               status,
                               payable_amount_cent,
                               paid_amount_cent,
                               user_coupon_id,
                               payment_transaction_id,
                               merchant_trade_no
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderPaymentRow)
                .optional();
    }

    private Optional<PaymentOrderRow> findActivePaymentForOrder(Long orderId) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               out_trade_no,
                               prepay_id,
                               transaction_id,
                               status,
                               amount_cent,
                               expires_at
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAYING'
                          and expires_at > :now
                        order by id desc
                        limit 1
                        for update
                        """)
                .param("orderId", orderId)
                .param("now", LocalDateTime.now())
                .query(this::mapPaymentOrderRow)
                .optional();
    }

    private Optional<PaymentOrderRow> findPaymentForUpdate(String outTradeNo) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               out_trade_no,
                               prepay_id,
                               transaction_id,
                               status,
                               amount_cent,
                               expires_at
                        from payment_order
                        where out_trade_no = :outTradeNo
                        for update
                        """)
                .param("outTradeNo", outTradeNo)
                .query(this::mapPaymentOrderRow)
                .optional();
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
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    private Long activePaymentConfigId() {
        return jdbcClient.sql("""
                        select id
                        from payment_config
                        where enabled = true
                          and status = 'ACTIVE'
                        order by id desc
                        limit 1
                        """)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private int expireMinutes() {
        return paymentProperties.expireMinutes() == null || paymentProperties.expireMinutes() < 1
                ? 15
                : paymentProperties.expireMinutes();
    }

    private String outTradeNo(OrderPaymentRow order) {
        String candidate = "P" + order.orderNo();
        if (candidate.length() <= 32) {
            return candidate;
        }
        return "PAY" + order.orderId();
    }

    private OrderPaymentRow mapOrderPaymentRow(ResultSet rs, int rowNum) throws SQLException {
        return new OrderPaymentRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getLong("payable_amount_cent"),
                rs.getLong("paid_amount_cent"),
                rs.getObject("user_coupon_id", Long.class),
                rs.getString("payment_transaction_id"),
                rs.getString("merchant_trade_no")
        );
    }

    private PaymentOrderRow mapPaymentOrderRow(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOrderRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no"),
                rs.getString("prepay_id"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getLong("amount_cent"),
                rs.getObject("expires_at", LocalDateTime.class)
        );
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

    private void validatePaidDuplicate(PaymentOrderRow payment, String transactionId, long amountCent) {
        if (payment.amountCent() != amountCent
                || !StringUtils.hasText(payment.transactionId())
                || !payment.transactionId().equals(transactionId)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void validatePaidDuplicateOrder(OrderPaymentRow order, String outTradeNo, String transactionId, long amountCent) {
        if (!OrderStatus.PAID.name().equals(order.status())
                || order.paidAmountCent() != amountCent
                || !outTradeNo.equals(order.merchantTradeNo())
                || !StringUtils.hasText(order.paymentTransactionId())
                || !order.paymentTransactionId().equals(transactionId)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private record OrderPaymentRow(
            Long orderId,
            String orderNo,
            Long userId,
            String status,
            long payableAmountCent,
            long paidAmountCent,
            Long userCouponId,
            String paymentTransactionId,
            String merchantTradeNo
    ) {
    }

    private record PaymentOrderRow(
            Long paymentOrderId,
            Long orderId,
            String outTradeNo,
            String prepayId,
            String transactionId,
            String status,
            long amountCent,
            LocalDateTime expiresAt
    ) {
    }

    public record PaidFinalizationResult(
            Long orderId,
            String orderStatus,
            String transactionId,
            boolean duplicate
    ) {
    }
}
