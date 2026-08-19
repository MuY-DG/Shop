package org.muybaby.shopserver.logistics.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatReceiptQueryStatus;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RealWechatShippingProvider implements WechatShippingProvider {

    public static final String MISSING_TRANSACTION_ID = "MISSING_TRANSACTION_ID";
    public static final String MISSING_TRANSACTION_ID_MESSAGE = "WeChat payment transaction id is required";

    private static final Logger log = LoggerFactory.getLogger(RealWechatShippingProvider.class);
    private static final String UPLOAD_URL = "https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token={accessToken}";
    private static final String ORDER_QUERY_URL = "https://api.weixin.qq.com/wxa/sec/order/get_order?access_token={accessToken}";
    private static final String CAPABILITY_URL = "https://api.weixin.qq.com/wxa/sec/order/is_trade_managed?access_token={accessToken}";
    private static final String DELIVERY_LIST_URL = "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/get_delivery_list?access_token={accessToken}";
    private static final Set<Integer> CONFIRMED_ORDER_STATES = Set.of(3, 4, 6);
    private static final Set<Integer> NOT_CONFIRMED_ORDER_STATES = Set.of(1, 2, 5);
    private static final Set<Integer> KNOWN_UNAVAILABLE_CAPABILITY_CODES = Set.of(
            40013,
            40014,
            40125,
            42001,
            48001,
            61007
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;
    private final WechatPlatformCredentialResolver credentialResolver;
    private final int maxResponseBytes;

    public RealWechatShippingProvider(
            @Qualifier(WechatShippingHttpConfiguration.REST_CLIENT_BEAN_NAME) RestClient restClient,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider,
            WechatPlatformCredentialResolver credentialResolver,
            WechatShippingHttpProperties httpProperties
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
        this.credentialResolver = credentialResolver;
        this.maxResponseBytes = httpProperties.maxResponseBytes();
    }

    @Override
    public WechatProviderMode mode() {
        return WechatProviderMode.REAL;
    }

    @Override
    public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
        WechatShippingUploadResult validationFailure = validateUpload(request);
        if (validationFailure != null) {
            logUploadResult(request == null ? null : request.orderId(), validationFailure);
            return validationFailure;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(toPayload(request));
        } catch (JsonProcessingException ex) {
            WechatShippingUploadResult result = WechatShippingUploadResult.failed(
                    "PAYLOAD_ERROR", "WeChat shipping upload payload could not be processed"
            );
            log.warn(
                    "WeChat shipping upload payload failed: orderId={}, status={}, exception={}",
                    request.orderId(), result.status(), ex.getClass().getSimpleName()
            );
            return result;
        }

        String accessToken;
        try {
            accessToken = accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            WechatShippingUploadResult result = accessTokenUnavailable();
            log.warn(
                    "WeChat shipping access token unavailable: orderId={}, status={}, exception={}",
                    request.orderId(), result.status(), ex.getClass().getSimpleName()
            );
            return result;
        }
        if (!StringUtils.hasText(accessToken)) {
            WechatShippingUploadResult result = accessTokenUnavailable();
            logUploadResult(request.orderId(), result);
            return result;
        }

        try {
            String responseBody = postJson(UPLOAD_URL, accessToken, body);
            WechatShippingUploadResult result = parseUploadResponse(responseBody);
            logUploadResult(request.orderId(), result);
            return result;
        } catch (RestClientException ex) {
            return ambiguousUpload(request.orderId(), ex);
        } catch (RuntimeException ex) {
            return ambiguousUpload(request.orderId(), ex);
        }
    }

    @Override
    public WechatShippingCapabilityResult queryCapability() {
        String appId;
        try {
            appId = credentialResolver.resolve().appId();
        } catch (RuntimeException ex) {
            return WechatShippingCapabilityResult.unavailable(
                    "MISSING_APP_ID", "WeChat mini program app id is not configured"
            );
        }
        if (!StringUtils.hasText(appId)) {
            return WechatShippingCapabilityResult.unavailable(
                    "MISSING_APP_ID", "WeChat mini program app id is not configured"
            );
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(new CapabilityRequest(appId));
        } catch (JsonProcessingException ex) {
            log.warn("WeChat shipping capability payload failed: exception={}", ex.getClass().getSimpleName());
            return WechatShippingCapabilityResult.unknown(
                    "PAYLOAD_ERROR", "WeChat shipping capability is unknown"
            );
        }

        try {
            String responseBody = postJson(
                    CAPABILITY_URL, accessTokenProvider.getAccessToken(), body
            );
            WechatShippingCapabilityResult result = parseCapabilityResponse(responseBody);
            log.info(
                    "WeChat shipping capability completed: state={}, errorCode={}",
                    result.state(), result.errorCode()
            );
            return result;
        } catch (RestClientException ex) {
            return ambiguousCapability(ex);
        } catch (RuntimeException ex) {
            return ambiguousCapability(ex);
        }
    }

    @Override
    public WechatReceiptQueryResult queryReceiptStatus(String transactionId) {
        WechatShippingOrderQueryResult orderResult = queryShippingOrder(transactionId);
        WechatReceiptQueryResult result;
        if (orderResult.status() == WechatShippingOrderQueryStatus.UNAVAILABLE) {
            result = WechatReceiptQueryResult.unavailable(
                    orderResult.errorCode(), orderResult.errorMessage()
            );
        } else if (orderResult.orderState() != null
                && CONFIRMED_ORDER_STATES.contains(orderResult.orderState())) {
            result = WechatReceiptQueryResult.confirmed(orderResult.orderState());
        } else if (orderResult.orderState() != null
                && NOT_CONFIRMED_ORDER_STATES.contains(orderResult.orderState())) {
            result = WechatReceiptQueryResult.notConfirmed(orderResult.orderState());
        } else {
            result = WechatReceiptQueryResult.unknown(
                    orderResult.errorCode(), "WeChat receipt status could not be confirmed"
            );
        }
        logReceiptResult(result, null);
        return result;
    }

    @Override
    public WechatShippingOrderQueryResult queryShippingOrder(String transactionId) {
        if (!StringUtils.hasText(transactionId)) {
            return WechatShippingOrderQueryResult.unavailable(
                    MISSING_TRANSACTION_ID, MISSING_TRANSACTION_ID_MESSAGE
            );
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(new ReceiptQueryRequest(transactionId));
        } catch (JsonProcessingException ex) {
            WechatShippingOrderQueryResult result = unknownShippingOrderResponse("PAYLOAD_ERROR");
            logShippingOrderResult(result, ex);
            return result;
        }

        String accessToken;
        try {
            accessToken = accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            WechatShippingOrderQueryResult result = shippingOrderAccessTokenUnavailable();
            logShippingOrderResult(result, ex);
            return result;
        }
        if (!StringUtils.hasText(accessToken)) {
            WechatShippingOrderQueryResult result = shippingOrderAccessTokenUnavailable();
            logShippingOrderResult(result, null);
            return result;
        }

        try {
            String responseBody = postJson(ORDER_QUERY_URL, accessToken, body);
            WechatShippingOrderQueryResult result = parseShippingOrderResponse(
                    responseBody, transactionId
            );
            logShippingOrderResult(result, null);
            return result;
        } catch (RestClientException ex) {
            WechatShippingOrderQueryResult result = ambiguousShippingOrderRequest();
            logShippingOrderResult(result, ex);
            return result;
        } catch (RuntimeException ex) {
            WechatShippingOrderQueryResult result = ambiguousShippingOrderRequest();
            logShippingOrderResult(result, ex);
            return result;
        }
    }

    @Override
    public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
        try {
            String responseBody = postJson(
                    DELIVERY_LIST_URL, accessTokenProvider.getAccessToken(), "{}"
            );
            DeliveryListResponse response = readDeliveryListResponse(responseBody);
            if (response.errcode() != 0) {
                log.warn(
                        "WeChat delivery company lookup rejected: errorCode={}",
                        response.errcode() == null ? "AMBIGUOUS_RESPONSE" : safeErrorCode(response.errcode())
                );
                throw safeDeliveryLookupFailure();
            }
            Map<String, WechatDeliveryCompanyResult> uniqueCompanies = new LinkedHashMap<>();
            for (DeliveryCompanyPayload item : response.deliveryList()) {
                if (item != null
                        && StringUtils.hasText(item.deliveryId())
                        && StringUtils.hasText(item.deliveryName())) {
                    String id = item.deliveryId().trim();
                    uniqueCompanies.putIfAbsent(
                            id,
                            new WechatDeliveryCompanyResult(id, item.deliveryName().trim())
                    );
                }
            }
            log.info("WeChat delivery company lookup completed: count={}", uniqueCompanies.size());
            return List.copyOf(uniqueCompanies.values());
        } catch (SafeDeliveryLookupException ex) {
            throw safeDeliveryLookupFailure();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            log.warn("WeChat delivery company response ambiguous: exception={}", ex.getClass().getSimpleName());
            throw safeDeliveryLookupFailure();
        } catch (RestClientException ex) {
            log.warn("WeChat delivery company request failed: exception={}", ex.getClass().getSimpleName());
            throw safeDeliveryLookupFailure();
        } catch (RuntimeException ex) {
            log.warn("WeChat delivery company request failed: exception={}", ex.getClass().getSimpleName());
            throw safeDeliveryLookupFailure();
        }
    }

    private WechatShippingOrderQueryResult parseShippingOrderResponse(
            String body,
            String expectedTransactionId
    ) {
        try {
            JsonNode response = readResponseObject(body);
            Integer errcode = strictErrcode(response);
            if (errcode == null) {
                return unknownShippingOrderResponse("AMBIGUOUS_RESPONSE");
            }
            if (errcode != 0) {
                return unknownShippingOrderResponse(safeErrorCode(errcode));
            }
            JsonNode order = response == null ? null : response.get("order");
            if (order == null || !order.isObject()) {
                return unknownShippingOrderResponse("AMBIGUOUS_RESPONSE");
            }
            JsonNode returnedTransactionId = order.get("transaction_id");
            JsonNode orderState = order.get("order_state");
            if (returnedTransactionId == null
                    || !returnedTransactionId.isTextual()
                    || !expectedTransactionId.equals(returnedTransactionId.textValue())
                    || orderState == null
                    || !orderState.isIntegralNumber()
                    || !orderState.canConvertToInt()) {
                return unknownShippingOrderResponse("ORDER_MISMATCH");
            }
            int state = orderState.intValue();
            WechatShippingSummary shipping = parseShippingSummary(order.get("shipping"));
            if (state == 1 && (shipping == null || !shipping.finishShipping())) {
                return WechatShippingOrderQueryResult.notUploaded(expectedTransactionId, state);
            }
            if (Set.of(2, 3, 4, 6).contains(state)
                    && shipping != null
                    && shipping.finishShipping()) {
                return WechatShippingOrderQueryResult.uploaded(
                        expectedTransactionId, state, shipping
                );
            }
            return WechatShippingOrderQueryResult.unknown(
                    expectedTransactionId,
                    state,
                    shipping,
                    state == 5 ? "REMOTE_ORDER_REFUNDED" : "SHIPPING_FACTS_AMBIGUOUS",
                    "WeChat shipping state could not be confirmed"
            );
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return unknownShippingOrderResponse("AMBIGUOUS_RESPONSE");
        }
    }

    private WechatShippingSummary parseShippingSummary(JsonNode shippingNode) {
        if (shippingNode == null || !shippingNode.isObject()) {
            return null;
        }
        JsonNode logisticsTypeNode = shippingNode.get("logistics_type");
        JsonNode deliveryModeNode = shippingNode.get("delivery_mode");
        JsonNode finishNode = shippingNode.get("finish_shipping");
        JsonNode shippingListNode = shippingNode.get("shipping_list");
        if (logisticsTypeNode == null
                || !logisticsTypeNode.isIntegralNumber()
                || !logisticsTypeNode.canConvertToInt()
                || deliveryModeNode == null
                || !deliveryModeNode.isIntegralNumber()
                || !deliveryModeNode.canConvertToInt()
                || finishNode == null
                || !finishNode.isBoolean()
                || shippingListNode == null
                || !shippingListNode.isArray()) {
            return null;
        }
        LogisticsType logisticsType = LogisticsType.fromValue(logisticsTypeNode.intValue());
        org.muybaby.shopserver.logistics.DeliveryMode deliveryMode =
                org.muybaby.shopserver.logistics.DeliveryMode.fromValue(deliveryModeNode.intValue());
        List<WechatShippingFact> facts = new ArrayList<>();
        for (JsonNode item : shippingListNode) {
            if (item == null || !item.isObject()) {
                return null;
            }
            facts.add(new WechatShippingFact(
                    textualOrNull(item.get("tracking_no")),
                    textualOrNull(item.get("express_company"))
            ));
        }
        return new WechatShippingSummary(
                logisticsType,
                deliveryMode,
                finishNode.booleanValue(),
                facts
        );
    }

    private String textualOrNull(JsonNode node) {
        return node != null && node.isTextual() && StringUtils.hasText(node.textValue())
                ? node.textValue().trim()
                : null;
    }

    private WechatShippingUploadResult validateUpload(WechatShippingUploadRequest request) {
        if (request == null) {
            return WechatShippingUploadResult.failed("INVALID_REQUEST", "WeChat shipping upload request is required");
        }
        if (!StringUtils.hasText(request.transactionId())) {
            return WechatShippingUploadResult.failed(MISSING_TRANSACTION_ID, MISSING_TRANSACTION_ID_MESSAGE);
        }
        if (!StringUtils.hasText(request.openid())) {
            return WechatShippingUploadResult.failed("MISSING_OPENID", "WeChat payer openid is required");
        }
        if (request.logisticsType() == null || request.deliveryMode() == null || !StringUtils.hasText(request.uploadTime())) {
            return WechatShippingUploadResult.failed("INVALID_REQUEST", "WeChat shipping upload request is incomplete");
        }
        if (request.shippingList() == null || request.shippingList().size() != 1 || request.shippingList().getFirst() == null) {
            return WechatShippingUploadResult.failed(
                    "INVALID_SHIPPING_LIST", "Exactly one WeChat shipping item is required"
            );
        }
        WechatShippingItem item = request.shippingList().getFirst();
        if (!StringUtils.hasText(item.itemDesc())) {
            return WechatShippingUploadResult.failed("INVALID_ITEM_DESC", "WeChat shipping item description is required");
        }
        if (request.logisticsType() == LogisticsType.EXPRESS
                && (!StringUtils.hasText(item.trackingNo()) || !StringUtils.hasText(item.expressCompany()))) {
            return WechatShippingUploadResult.failed(
                    "INVALID_EXPRESS_ITEM", "WeChat express company and tracking number are required"
            );
        }
        return null;
    }

    private ShippingUploadPayload toPayload(WechatShippingUploadRequest request) {
        WechatShippingItem item = request.shippingList().getFirst();
        ShippingItemPayload shippingItem;
        if (request.logisticsType() == LogisticsType.EXPRESS) {
            ContactPayload contact = StringUtils.hasText(item.consignorContact())
                    || StringUtils.hasText(item.receiverContact())
                    ? new ContactPayload(nullIfBlank(item.consignorContact()), nullIfBlank(item.receiverContact()))
                    : null;
            shippingItem = new ShippingItemPayload(
                    item.trackingNo(),
                    item.expressCompany(),
                    item.itemDesc(),
                    contact
            );
        } else {
            shippingItem = new ShippingItemPayload(null, null, item.itemDesc(), null);
        }
        return new ShippingUploadPayload(
                new OrderKey(2, request.transactionId()),
                request.logisticsType().value(),
                request.deliveryMode().value(),
                request.deliveryMode() == DeliveryMode.SPLIT ? request.allDelivered() : null,
                List.of(shippingItem),
                request.uploadTime(),
                new Payer(request.openid())
        );
    }

    private WechatShippingUploadResult parseUploadResponse(String body) {
        try {
            JsonNode response = readResponseObject(body);
            Integer errcode = strictErrcode(response);
            if (errcode == null) {
                return unknownUploadResponse();
            }
            if (errcode == 0) {
                return WechatShippingUploadResult.uploaded();
            }
            return WechatShippingUploadResult.failed(
                    safeErrorCode(errcode), "WeChat shipping upload failed"
            );
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return unknownUploadResponse();
        }
    }

    private WechatShippingCapabilityResult parseCapabilityResponse(String body) {
        try {
            JsonNode response = readResponseObject(body);
            Integer errcode = strictErrcode(response);
            if (errcode == null) {
                return unknownCapabilityResponse();
            }
            if (errcode != 0) {
                String code = safeErrorCode(errcode);
                if (KNOWN_UNAVAILABLE_CAPABILITY_CODES.contains(errcode)) {
                    return WechatShippingCapabilityResult.unavailable(
                            code, "WeChat shipping capability is unavailable"
                    );
                }
                return WechatShippingCapabilityResult.unknown(
                    code, "WeChat shipping capability is unknown"
                );
            }
            JsonNode tradeManaged = response.get("is_trade_managed");
            if (tradeManaged == null || !tradeManaged.isBoolean()) {
                return unknownCapabilityResponse();
            }
            if (tradeManaged.booleanValue()) {
                return WechatShippingCapabilityResult.available();
            }
            return WechatShippingCapabilityResult.unmanaged();
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return unknownCapabilityResponse();
        }
    }

    private DeliveryListResponse readDeliveryListResponse(String body) throws JsonProcessingException {
        JsonNode response = readResponseObject(body);
        Integer errcode = strictErrcode(response);
        if (errcode == null) {
            throw new SafeDeliveryLookupException();
        }
        if (errcode != 0) {
            return new DeliveryListResponse(errcode, null);
        }
        JsonNode deliveryList = response.get("delivery_list");
        if (deliveryList == null || !deliveryList.isArray()) {
            throw new SafeDeliveryLookupException();
        }
        List<DeliveryCompanyPayload> companies = new java.util.ArrayList<>();
        for (JsonNode item : deliveryList) {
            if (item != null && item.isObject()) {
                JsonNode deliveryId = item.get("delivery_id");
                JsonNode deliveryName = item.get("delivery_name");
                if (deliveryId != null && deliveryId.isTextual()
                        && deliveryName != null && deliveryName.isTextual()
                        && StringUtils.hasText(deliveryId.textValue())
                        && StringUtils.hasText(deliveryName.textValue())) {
                    companies.add(new DeliveryCompanyPayload(deliveryId.textValue(), deliveryName.textValue()));
                }
            }
        }
        if (!deliveryList.isEmpty() && companies.isEmpty()) {
            throw new SafeDeliveryLookupException();
        }
        return new DeliveryListResponse(errcode, List.copyOf(companies));
    }

    private JsonNode readResponseObject(String body) throws JsonProcessingException {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        JsonNode response = objectMapper.readTree(body);
        return response != null && response.isObject() ? response : null;
    }

    private String postJson(String url, String accessToken, String body) {
        return restClient.post()
                .uri(url, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) ->
                        WechatShippingResponseBodyReader.readJson(response, maxResponseBytes));
    }

    private Integer strictErrcode(JsonNode response) {
        if (response == null) {
            return null;
        }
        JsonNode errcode = response.get("errcode");
        if (errcode == null || !errcode.isIntegralNumber() || !errcode.canConvertToInt()) {
            return null;
        }
        return errcode.intValue();
    }

    private WechatShippingUploadResult ambiguousUpload(Long orderId, RuntimeException ex) {
        WechatShippingUploadResult result = WechatShippingUploadResult.unknown(
                "REQUEST_AMBIGUOUS", "WeChat shipping upload result is unknown"
        );
        log.warn(
                "WeChat shipping upload request ambiguous: orderId={}, status={}, exception={}",
                orderId, result.status(), ex.getClass().getSimpleName()
        );
        return result;
    }

    private WechatShippingCapabilityResult ambiguousCapability(RuntimeException ex) {
        WechatShippingCapabilityResult result = WechatShippingCapabilityResult.unknown(
                "REQUEST_AMBIGUOUS", "WeChat shipping capability is unknown"
        );
        log.warn(
                "WeChat shipping capability request ambiguous: state={}, exception={}",
                result.state(), ex.getClass().getSimpleName()
        );
        return result;
    }

    private WechatShippingUploadResult unknownUploadResponse() {
        return WechatShippingUploadResult.unknown(
                "AMBIGUOUS_RESPONSE", "WeChat shipping upload result is unknown"
        );
    }

    private WechatShippingUploadResult accessTokenUnavailable() {
        return WechatShippingUploadResult.unavailable(
                "ACCESS_TOKEN_UNAVAILABLE", "WeChat access token is unavailable"
        );
    }

    private WechatShippingOrderQueryResult shippingOrderAccessTokenUnavailable() {
        return WechatShippingOrderQueryResult.unavailable(
                "ACCESS_TOKEN_UNAVAILABLE", "WeChat shipping order status is unavailable"
        );
    }

    private WechatShippingOrderQueryResult ambiguousShippingOrderRequest() {
        return unknownShippingOrderResponse("REQUEST_AMBIGUOUS");
    }

    private WechatShippingOrderQueryResult unknownShippingOrderResponse(String errorCode) {
        return WechatShippingOrderQueryResult.unknown(
                errorCode, "WeChat shipping order status could not be confirmed"
        );
    }

    private WechatShippingCapabilityResult unknownCapabilityResponse() {
        return WechatShippingCapabilityResult.unknown(
                "AMBIGUOUS_RESPONSE", "WeChat shipping capability is unknown"
        );
    }

    private void logReceiptResult(WechatReceiptQueryResult result, Exception exception) {
        if (exception != null) {
            log.warn(
                    "WeChat receipt status query failed safely: status={}, errorCode={}, exception={}",
                    result.status(), result.errorCode(), exception.getClass().getSimpleName()
            );
            return;
        }
        if (result.status() == WechatReceiptQueryStatus.UNKNOWN
                || result.status() == WechatReceiptQueryStatus.UNAVAILABLE) {
            log.warn(
                    "WeChat receipt status query completed: status={}, orderState={}, errorCode={}",
                    result.status(), result.orderState(), result.errorCode()
            );
            return;
        }
        log.info(
                "WeChat receipt status query completed: status={}, orderState={}, errorCode={}",
                result.status(), result.orderState(), result.errorCode()
        );
    }

    private void logShippingOrderResult(
            WechatShippingOrderQueryResult result,
            Exception exception
    ) {
        if (exception != null) {
            log.warn(
                    "WeChat shipping order query failed safely: status={}, orderState={}, errorCode={}, exception={}",
                    result.status(), result.orderState(), result.errorCode(),
                    exception.getClass().getSimpleName()
            );
            return;
        }
        if (result.status() == WechatShippingOrderQueryStatus.UPLOADED
                || result.status() == WechatShippingOrderQueryStatus.NOT_UPLOADED) {
            log.info(
                    "WeChat shipping order query completed: status={}, orderState={}, errorCode={}",
                    result.status(), result.orderState(), result.errorCode()
            );
            return;
        }
        log.warn(
                "WeChat shipping order query completed: status={}, orderState={}, errorCode={}",
                result.status(), result.orderState(), result.errorCode()
        );
    }

    private void logUploadResult(Long orderId, WechatShippingUploadResult result) {
        if (result.status() == WechatShippingUploadStatus.UPLOADED) {
            log.info(
                    "WeChat shipping upload completed: orderId={}, status={}, errorCode={}",
                    orderId, result.status(), result.errorCode()
            );
            return;
        }
        log.warn(
                "WeChat shipping upload completed: orderId={}, status={}, errorCode={}",
                orderId, result.status(), result.errorCode()
        );
    }

    private IllegalStateException safeDeliveryLookupFailure() {
        return new IllegalStateException("WeChat delivery company lookup failed");
    }

    private String safeErrorCode(Integer errcode) {
        return "WECHAT_" + errcode;
    }

    private String nullIfBlank(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ShippingUploadPayload(
            @JsonProperty("order_key") OrderKey orderKey,
            @JsonProperty("logistics_type") Integer logisticsType,
            @JsonProperty("delivery_mode") Integer deliveryMode,
            @JsonProperty("is_all_delivered") Boolean allDelivered,
            @JsonProperty("shipping_list") List<ShippingItemPayload> shippingList,
            @JsonProperty("upload_time") String uploadTime,
            Payer payer
    ) {
    }

    private record OrderKey(
            @JsonProperty("order_number_type") Integer orderNumberType,
            @JsonProperty("transaction_id") String transactionId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ShippingItemPayload(
            @JsonProperty("tracking_no") String trackingNo,
            @JsonProperty("express_company") String expressCompany,
            @JsonProperty("item_desc") String itemDesc,
            ContactPayload contact
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ContactPayload(
            @JsonProperty("consignor_contact") String consignorContact,
            @JsonProperty("receiver_contact") String receiverContact
    ) {
    }

    private record Payer(String openid) {
    }

    private record CapabilityRequest(String appid) {
    }

    private record ReceiptQueryRequest(
            @JsonProperty("transaction_id") String transactionId
    ) {
    }

    private record DeliveryListResponse(
            Integer errcode,
            List<DeliveryCompanyPayload> deliveryList
    ) {
    }

    private record DeliveryCompanyPayload(
            String deliveryId,
            String deliveryName
    ) {
    }

    private static final class SafeDeliveryLookupException extends RuntimeException {
    }
}
