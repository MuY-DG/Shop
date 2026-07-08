package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "true")
public class MockWechatPayProvider implements WechatPayProvider {

    private static final String VALID_SIGNATURE = "mock-valid-signature";

    private final ObjectMapper objectMapper;
    private final Map<String, WechatPayOrderQueryResult> paidOrders = new LinkedHashMap<>();
    private final List<String> closedOutTradeNos = new ArrayList<>();

    public MockWechatPayProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WechatJsapiPrepayResult createJsapiPrepay(ResolvedPaymentConfig config, WechatJsapiPrepayRequest request) {
        String prepayId = "mock-prepay-" + request.outTradeNo();
        return new WechatJsapiPrepayResult(
                prepayId,
                "1783500000",
                "mock-nonce-" + request.outTradeNo(),
                "prepay_id=" + prepayId,
                "RSA",
                "mock-pay-sign-" + request.outTradeNo()
        );
    }

    @Override
    public WechatPayOrderQueryResult queryOrder(ResolvedPaymentConfig config, String outTradeNo) {
        return paidOrders.getOrDefault(outTradeNo, WechatPayOrderQueryResult.notPaid(outTradeNo, "NOTPAY"));
    }

    @Override
    public void closeOrder(ResolvedPaymentConfig config, String outTradeNo) {
        closedOutTradeNos.add(outTradeNo);
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
        if (!VALID_SIGNATURE.equals(signature)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.path("resource");
            JsonNode amount = resource.path("amount");
            String outTradeNo = resource.path("out_trade_no").asText();
            String transactionId = resource.path("transaction_id").asText();
            long amountCent = amount.path("total").asLong();
            return new WechatPayNotification(
                    root.path("id").asText(),
                    root.path("event_type").asText(),
                    outTradeNo,
                    transactionId,
                    resource.path("trade_state").asText(),
                    amountCent,
                    amount.path("currency").asText("CNY"),
                    parseWechatTime(resource.path("success_time").asText()),
                    sha256(resource.toString())
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Override
    public WechatRefundResult requestRefund(ResolvedPaymentConfig config, WechatRefundRequest request) {
        return new WechatRefundResult(request.outRefundNo(), "mock-refund-" + request.outRefundNo(), "PROCESSING");
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

    public void markOrderPaid(String outTradeNo, long amountCent, String transactionId) {
        paidOrders.put(outTradeNo, new WechatPayOrderQueryResult(
                true,
                outTradeNo,
                transactionId,
                amountCent,
                LocalDateTime.of(2026, 7, 8, 12, 0),
                "SUCCESS"
        ));
    }

    public List<String> closedOutTradeNos() {
        return List.copyOf(closedOutTradeNos);
    }

    public void reset() {
        paidOrders.clear();
        closedOutTradeNos.clear();
    }

    private LocalDateTime parseWechatTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        return OffsetDateTime.parse(value).toLocalDateTime();
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
