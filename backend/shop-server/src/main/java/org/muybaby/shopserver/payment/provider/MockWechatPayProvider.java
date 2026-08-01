package org.muybaby.shopserver.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@ConditionalOnProperty(prefix = "shop.pay", name = "mock-enabled", havingValue = "true")
public class MockWechatPayProvider implements WechatPayProvider {

    private static final String VALID_SIGNATURE = "mock-valid-signature";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, WechatPayOrderQueryResult> paidOrders = new ConcurrentHashMap<>();
    private final Map<String, WechatRefundQueryResult> refunds = new ConcurrentHashMap<>();
    private final List<String> closedOutTradeNos = new CopyOnWriteArrayList<>();
    private final List<String> queriedOutTradeNos = new CopyOnWriteArrayList<>();
    private final Map<String, Long> queriedPaymentConfigIds = new ConcurrentHashMap<>();
    private final List<String> queriedOutRefundNos = new CopyOnWriteArrayList<>();
    private final List<String> requestedOutRefundNos = new CopyOnWriteArrayList<>();
    private final Set<String> closeFailures = ConcurrentHashMap.newKeySet();
    private final Set<String> refundQueryFailures = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> requiredPayNotificationConfigIds = new ConcurrentHashMap<>();
    private final Map<String, Long> requiredRefundNotificationConfigIds = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> payNotificationConfigAttempts = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> refundNotificationConfigAttempts = new ConcurrentHashMap<>();

    @Autowired
    public MockWechatPayProvider(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    protected MockWechatPayProvider(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
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
        queriedOutTradeNos.add(outTradeNo);
        if (config.configId() != null) {
            queriedPaymentConfigIds.put(outTradeNo, config.configId());
        }
        return paidOrders.getOrDefault(outTradeNo, WechatPayOrderQueryResult.notPaid(outTradeNo, "NOTPAY"));
    }

    @Override
    public void closeOrder(ResolvedPaymentConfig config, String outTradeNo) {
        if (closeFailures.contains(outTradeNo)) {
            throw new IllegalStateException("Mock payment close failure");
        }
        WechatPayOrderQueryResult current = paidOrders.get(outTradeNo);
        if (current != null && current.paid()) {
            throw new IllegalStateException("Mock paid order cannot be closed");
        }
        closedOutTradeNos.add(outTradeNo);
        paidOrders.put(outTradeNo, WechatPayOrderQueryResult.notPaid(outTradeNo, "CLOSED"));
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
            recordConfigAttempt(payNotificationConfigAttempts, outTradeNo, config.configId());
            Long requiredConfigId = requiredPayNotificationConfigIds.get(outTradeNo);
            if (requiredConfigId != null && !Objects.equals(requiredConfigId, config.configId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
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
        requestedOutRefundNos.add(request.outRefundNo());
        refunds.put(request.outRefundNo(), new WechatRefundQueryResult(
                request.outRefundNo(),
                "mock-refund-" + request.outRefundNo(),
                request.outTradeNo(),
                "PROCESSING",
                request.refundAmountCent(),
                null
        ));
        return new WechatRefundResult(request.outRefundNo(), "mock-refund-" + request.outRefundNo(), "PROCESSING");
    }

    @Override
    public WechatRefundQueryResult queryRefund(ResolvedPaymentConfig config, String outRefundNo) {
        queriedOutRefundNos.add(outRefundNo);
        if (refundQueryFailures.contains(outRefundNo)) {
            throw new IllegalStateException("Mock refund query failure");
        }
        WechatRefundQueryResult result = refunds.get(outRefundNo);
        if (result == null) {
            return new WechatRefundQueryResult(outRefundNo, "", "", "NOT_FOUND", 0L, null);
        }
        return result;
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
        if (!VALID_SIGNATURE.equals(signature)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.path("resource");
            JsonNode amount = resource.path("amount");
            String outRefundNo = resource.path("out_refund_no").asText();
            recordConfigAttempt(refundNotificationConfigAttempts, outRefundNo, config.configId());
            Long requiredConfigId = requiredRefundNotificationConfigIds.get(outRefundNo);
            if (requiredConfigId != null && !Objects.equals(requiredConfigId, config.configId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            return new WechatRefundNotification(
                    root.path("id").asText(),
                    root.path("event_type").asText(),
                    resource.path("out_trade_no").asText(),
                    outRefundNo,
                    resource.path("refund_id").asText(),
                    resource.path("refund_status").asText(),
                    amount.path("refund").asLong(),
                    parseWechatTime(resource.path("success_time").asText()),
                    sha256(resource.toString())
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
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

    public List<String> queriedOutTradeNos() {
        return List.copyOf(queriedOutTradeNos);
    }

    public Long queriedPaymentConfigId(String outTradeNo) {
        return queriedPaymentConfigIds.get(outTradeNo);
    }

    public void markOrderState(String outTradeNo, String tradeState) {
        paidOrders.put(outTradeNo, WechatPayOrderQueryResult.notPaid(outTradeNo, tradeState));
    }

    public void failCloseFor(String outTradeNo) {
        closeFailures.add(outTradeNo);
    }

    public void markRefundStatus(String outRefundNo, String status, LocalDateTime successAt) {
        refunds.compute(outRefundNo, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("Mock refund does not exist");
            }
            return new WechatRefundQueryResult(
                    current.outRefundNo(),
                    current.refundId(),
                    current.outTradeNo(),
                    status,
                    current.refundAmountCent(),
                    successAt
            );
        });
    }

    public void failRefundQueryFor(String outRefundNo) {
        refundQueryFailures.add(outRefundNo);
    }

    public void requirePayNotificationConfig(String outTradeNo, long configId) {
        requiredPayNotificationConfigIds.put(outTradeNo, configId);
    }

    public void requireRefundNotificationConfig(String outRefundNo, long configId) {
        requiredRefundNotificationConfigIds.put(outRefundNo, configId);
    }

    public List<Long> payNotificationConfigAttempts(String outTradeNo) {
        return List.copyOf(payNotificationConfigAttempts.getOrDefault(outTradeNo, List.of()));
    }

    public List<Long> refundNotificationConfigAttempts(String outRefundNo) {
        return List.copyOf(refundNotificationConfigAttempts.getOrDefault(outRefundNo, List.of()));
    }

    public List<String> queriedOutRefundNos() {
        return List.copyOf(queriedOutRefundNos);
    }

    public List<String> requestedOutRefundNos() {
        return List.copyOf(requestedOutRefundNos);
    }

    public void forgetRefund(String outRefundNo) {
        refunds.remove(outRefundNo);
    }

    public void reset() {
        paidOrders.clear();
        refunds.clear();
        closedOutTradeNos.clear();
        queriedOutTradeNos.clear();
        queriedPaymentConfigIds.clear();
        queriedOutRefundNos.clear();
        requestedOutRefundNos.clear();
        closeFailures.clear();
        refundQueryFailures.clear();
        requiredPayNotificationConfigIds.clear();
        requiredRefundNotificationConfigIds.clear();
        payNotificationConfigAttempts.clear();
        refundNotificationConfigAttempts.clear();
    }

    private LocalDateTime parseWechatTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(clock);
        }
        return OffsetDateTime.parse(value)
                .atZoneSameInstant(clock.getZone())
                .toLocalDateTime();
    }

    private void recordConfigAttempt(Map<String, List<Long>> attempts, String key, Long configId) {
        if (configId == null) {
            return;
        }
        attempts.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(configId);
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
