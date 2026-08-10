package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RealWechatPayProvider implements WechatPayProvider {

    private static final DateTimeFormatter WECHAT_RFC3339_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RealWechatPayProvider(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public WechatJsapiPrepayResult createJsapiPrepay(ResolvedPaymentConfig config, WechatJsapiPrepayRequest request) {
        PrepayRequest prepayRequest = new PrepayRequest();
        prepayRequest.setAppid(config.appId());
        prepayRequest.setMchid(config.mchId());
        prepayRequest.setDescription(request.description());
        prepayRequest.setOutTradeNo(request.outTradeNo());
        prepayRequest.setNotifyUrl(request.notifyUrl());
        prepayRequest.setTimeExpire(formatTimeExpire(request.timeExpire()));
        Amount amount = new Amount();
        amount.setTotal(Math.toIntExact(request.amountCent()));
        amount.setCurrency(request.currency());
        prepayRequest.setAmount(amount);
        Payer payer = new Payer();
        payer.setOpenid(request.payerOpenid());
        prepayRequest.setPayer(payer);

        PrepayWithRequestPaymentResponse response = jsapiService(config).prepayWithRequestPayment(prepayRequest);
        String packageValue = response.getPackageVal();
        return new WechatJsapiPrepayResult(
                packageValue == null ? "" : packageValue.replace("prepay_id=", ""),
                response.getTimeStamp(),
                response.getNonceStr(),
                packageValue,
                response.getSignType(),
                response.getPaySign()
        );
    }

    @Override
    public WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(config.mchId());
        request.setOutTradeNo(outTradeNo);
        Transaction transaction;
        try {
            transaction = jsapiService(config).queryOrderByOutTradeNo(request);
        } catch (ServiceException ex) {
            if ("ORDER_NOT_EXIST".equals(ex.getErrorCode())) {
                return WechatPayOrderQueryResult.notPaid(outTradeNo, "NOT_FOUND");
            }
            throw ex;
        }
        boolean paid = transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS;
        long amountCent = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                ? 0L
                : transaction.getAmount().getTotal();
        LocalDateTime paidAt = transaction.getSuccessTime() == null
                ? null
                : toLocalDateTime(transaction.getSuccessTime());
        return new WechatPayOrderQueryResult(
                paid,
                transaction.getOutTradeNo(),
                nullToEmpty(transaction.getTransactionId()),
                amountCent,
                paidAt,
                transaction.getTradeState() == null ? "" : transaction.getTradeState().name()
        );
    }

    @Override
    public void closeOrder(ResolvedPaymentConfig config, String outTradeNo) {
        CloseOrderRequest request = new CloseOrderRequest();
        request.setMchid(config.mchId());
        request.setOutTradeNo(outTradeNo);
        jsapiService(config).closeOrder(request);
    }

    @Override
    public WechatPayNotification parsePayNotification(
            ResolvedPaymentConfig config,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .body(body)
                    .build();
            Transaction transaction = notificationParser(config).parse(requestParam, Transaction.class);
            validatePayNotificationMerchant(config, transaction);
            JsonNode root = objectMapper.readTree(body);
            String resourceDigest = root.path("resource").isMissingNode() ? "" : sha256(root.path("resource").toString());
            long amountCent = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                    ? 0L
                    : transaction.getAmount().getTotal();
            LocalDateTime paidAt = transaction.getSuccessTime() == null
                    ? LocalDateTime.now(clock)
                    : toLocalDateTime(transaction.getSuccessTime());
            return new WechatPayNotification(
                    root.path("id").asText(),
                    root.path("event_type").asText(),
                    transaction.getOutTradeNo(),
                    nullToEmpty(transaction.getTransactionId()),
                    transaction.getTradeState() == null ? "" : transaction.getTradeState().name(),
                    amountCent,
                    transaction.getAmount() == null ? "CNY" : nullToEmpty(transaction.getAmount().getCurrency()),
                    paidAt,
                    resourceDigest
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Override
    public WechatRefundResult requestRefund(ResolvedPaymentConfig config, WechatRefundRequest request) {
        CreateRequest createRequest = new CreateRequest();
        if (request.transactionId() != null && !request.transactionId().isBlank()) {
            createRequest.setTransactionId(request.transactionId());
        } else if (request.outTradeNo() != null && !request.outTradeNo().isBlank()) {
            createRequest.setOutTradeNo(request.outTradeNo());
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        createRequest.setOutRefundNo(request.outRefundNo());
        if (request.reason() != null && !request.reason().isBlank()) {
            createRequest.setReason(request.reason());
        }
        createRequest.setNotifyUrl(request.notifyUrl());
        AmountReq amountReq = new AmountReq();
        amountReq.setRefund(request.refundAmountCent());
        amountReq.setTotal(request.totalAmountCent());
        amountReq.setCurrency("CNY");
        createRequest.setAmount(amountReq);
        Refund refund = refundService(config).create(createRequest);
        return new WechatRefundResult(refund.getOutRefundNo(), nullToEmpty(refund.getRefundId()), refund.getStatus() == null ? "" : refund.getStatus().name());
    }

    @Override
    public WechatRefundQueryResult queryRefund(ResolvedPaymentConfig config, String outRefundNo) {
        QueryByOutRefundNoRequest request = new QueryByOutRefundNoRequest();
        request.setOutRefundNo(outRefundNo);
        Refund refund;
        try {
            refund = refundService(config).queryByOutRefundNo(request);
        } catch (ServiceException ex) {
            if ("RESOURCE_NOT_EXISTS".equals(ex.getErrorCode())
                    || "REFUND_NOT_EXIST".equals(ex.getErrorCode())) {
                return new WechatRefundQueryResult(outRefundNo, "", "", "NOT_FOUND", 0L, null);
            }
            throw ex;
        }
        long refundAmountCent = refund.getAmount() == null || refund.getAmount().getRefund() == null
                ? 0L
                : refund.getAmount().getRefund();
        LocalDateTime successAt = refund.getSuccessTime() == null
                ? null
                : toLocalDateTime(refund.getSuccessTime());
        return new WechatRefundQueryResult(
                refund.getOutRefundNo(),
                nullToEmpty(refund.getRefundId()),
                refund.getOutTradeNo(),
                refund.getStatus() == null ? "" : refund.getStatus().name(),
                refundAmountCent,
                successAt
        );
    }

    @Override
    public WechatRefundNotification parseRefundNotification(
            ResolvedPaymentConfig config,
            String timestamp,
            String nonce,
            String serial,
            String signature,
            String body
    ) {
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .body(body)
                    .build();
            com.wechat.pay.java.service.refund.model.RefundNotification refundNotification =
                    notificationParser(config).parse(requestParam, com.wechat.pay.java.service.refund.model.RefundNotification.class);
            // The current refund SDK resource has no mchid/appid fields. Merchant identity is bound
            // transactionally to payment_order's configuration id and fingerprint before mutation.
            JsonNode root = objectMapper.readTree(body);
            String resourceDigest = root.path("resource").isMissingNode() ? "" : sha256(root.path("resource").toString());
            long refundAmountCent = refundNotification.getAmount() == null || refundNotification.getAmount().getRefund() == null
                    ? 0L
                    : refundNotification.getAmount().getRefund();
            LocalDateTime successAt = refundNotification.getSuccessTime() == null
                    ? null
                    : toLocalDateTime(refundNotification.getSuccessTime());
            return new WechatRefundNotification(
                    root.path("id").asText(),
                    root.path("event_type").asText(),
                    refundNotification.getOutTradeNo(),
                    refundNotification.getOutRefundNo(),
                    nullToEmpty(refundNotification.getRefundId()),
                    refundNotification.getRefundStatus() == null ? "" : refundNotification.getRefundStatus().name(),
                    refundAmountCent,
                    successAt,
                    resourceDigest
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private JsapiServiceExtension jsapiService(ResolvedPaymentConfig config) {
        return new JsapiServiceExtension.Builder()
                .config(config(config))
                .build();
    }

    RefundService refundService(ResolvedPaymentConfig config) {
        return new RefundService.Builder()
                .config(config(config))
                .build();
    }

    void validatePayNotificationMerchant(ResolvedPaymentConfig config, Transaction transaction) {
        if (transaction == null
                || !config.mchId().equals(transaction.getMchid())
                || !config.appId().equals(transaction.getAppid())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private NotificationParser notificationParser(ResolvedPaymentConfig config) {
        return new NotificationParser((RSAPublicKeyConfig) config(config));
    }

    private Config config(ResolvedPaymentConfig config) {
        return WechatPaySdkConfigFactory.create(config);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    LocalDateTime toLocalDateTime(String value) {
        return OffsetDateTime.parse(value)
                .atZoneSameInstant(clock.getZone())
                .toLocalDateTime();
    }

    static String formatTimeExpire(LocalDateTime timeExpire) {
        return WECHAT_RFC3339_DATE_TIME.format(timeExpire.atOffset(ZoneOffset.UTC));
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
