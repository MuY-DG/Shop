package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
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
import com.wechat.pay.java.service.refund.model.Refund;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RealWechatPayProvider implements WechatPayProvider {

    private final ObjectMapper objectMapper;

    public RealWechatPayProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WechatJsapiPrepayResult createJsapiPrepay(ResolvedPaymentConfig config, WechatJsapiPrepayRequest request) {
        PrepayRequest prepayRequest = new PrepayRequest();
        prepayRequest.setAppid(config.appId());
        prepayRequest.setMchid(config.mchId());
        prepayRequest.setDescription(request.description());
        prepayRequest.setOutTradeNo(request.outTradeNo());
        prepayRequest.setNotifyUrl(request.notifyUrl());
        prepayRequest.setTimeExpire(request.timeExpire().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
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
        Transaction transaction = jsapiService(config).queryOrderByOutTradeNo(request);
        boolean paid = transaction.getTradeState() == Transaction.TradeStateEnum.SUCCESS;
        long amountCent = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                ? 0L
                : transaction.getAmount().getTotal();
        LocalDateTime paidAt = transaction.getSuccessTime() == null
                ? null
                : OffsetDateTime.parse(transaction.getSuccessTime()).toLocalDateTime();
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
            JsonNode root = objectMapper.readTree(body);
            String resourceDigest = root.path("resource").isMissingNode() ? "" : sha256(root.path("resource").toString());
            long amountCent = transaction.getAmount() == null || transaction.getAmount().getTotal() == null
                    ? 0L
                    : transaction.getAmount().getTotal();
            LocalDateTime paidAt = transaction.getSuccessTime() == null
                    ? LocalDateTime.now()
                    : OffsetDateTime.parse(transaction.getSuccessTime()).toLocalDateTime();
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
        createRequest.setOutTradeNo(request.outTradeNo());
        createRequest.setTransactionId(request.transactionId());
        createRequest.setOutRefundNo(request.outRefundNo());
        createRequest.setReason(request.reason());
        createRequest.setNotifyUrl(request.notifyUrl());
        AmountReq amountReq = new AmountReq();
        amountReq.setRefund(request.refundAmountCent());
        amountReq.setTotal(request.totalAmountCent());
        amountReq.setCurrency("CNY");
        createRequest.setAmount(amountReq);
        Refund refund = new RefundService.Builder().config(config(config)).build().create(createRequest);
        return new WechatRefundResult(refund.getOutRefundNo(), nullToEmpty(refund.getRefundId()), refund.getStatus() == null ? "" : refund.getStatus().name());
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
        throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private JsapiServiceExtension jsapiService(ResolvedPaymentConfig config) {
        return new JsapiServiceExtension.Builder()
                .config(config(config))
                .build();
    }

    private NotificationParser notificationParser(ResolvedPaymentConfig config) {
        return new NotificationParser((RSAPublicKeyConfig) config(config));
    }

    private Config config(ResolvedPaymentConfig config) {
        return new RSAPublicKeyConfig.Builder()
                .merchantId(config.mchId())
                .merchantSerialNumber(config.merchantSerialNo())
                .privateKey(config.privateKeyPem())
                .apiV3Key(config.apiV3Key())
                .publicKeyId(config.wechatPublicKeyId())
                .publicKey(config.wechatPublicKeyPem())
                .build();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
