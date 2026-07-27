package org.muybaby.shopserver.logistics.provider;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.muybaby.shopserver.wechat.WechatMiniProgramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
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
    private final WechatMiniProgramProperties properties;

    public RealWechatShippingProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider,
            WechatMiniProgramProperties properties
    ) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
        this.properties = properties;
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
            String responseBody = restClient.post()
                    .uri(UPLOAD_URL, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
        if (!StringUtils.hasText(properties.appId())) {
            return WechatShippingCapabilityResult.unavailable(
                    "MISSING_APP_ID", "WeChat mini program app id is not configured"
            );
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(new CapabilityRequest(properties.appId()));
        } catch (JsonProcessingException ex) {
            log.warn("WeChat shipping capability payload failed: exception={}", ex.getClass().getSimpleName());
            return WechatShippingCapabilityResult.unknown(
                    "PAYLOAD_ERROR", "WeChat shipping capability is unknown"
            );
        }

        try {
            String responseBody = restClient.post()
                    .uri(CAPABILITY_URL, accessTokenProvider.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
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
        if (!StringUtils.hasText(transactionId)) {
            return WechatReceiptQueryResult.unavailable(
                    MISSING_TRANSACTION_ID, MISSING_TRANSACTION_ID_MESSAGE
            );
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(new ReceiptQueryRequest(transactionId));
        } catch (JsonProcessingException ex) {
            WechatReceiptQueryResult result = unknownReceiptResponse("PAYLOAD_ERROR");
            logReceiptResult(result, ex);
            return result;
        }

        String accessToken;
        try {
            accessToken = accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            WechatReceiptQueryResult result = receiptAccessTokenUnavailable();
            logReceiptResult(result, ex);
            return result;
        }
        if (!StringUtils.hasText(accessToken)) {
            WechatReceiptQueryResult result = receiptAccessTokenUnavailable();
            logReceiptResult(result, null);
            return result;
        }

        try {
            String responseBody = restClient.post()
                    .uri(ORDER_QUERY_URL, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            WechatReceiptQueryResult result = parseReceiptResponse(responseBody, transactionId);
            logReceiptResult(result, null);
            return result;
        } catch (RestClientException ex) {
            WechatReceiptQueryResult result = ambiguousReceiptRequest();
            logReceiptResult(result, ex);
            return result;
        } catch (RuntimeException ex) {
            WechatReceiptQueryResult result = ambiguousReceiptRequest();
            logReceiptResult(result, ex);
            return result;
        }
    }

    @Override
    public List<WechatDeliveryCompanyResult> getDeliveryCompanies() {
        try {
            String responseBody = restClient.post()
                    .uri(DELIVERY_LIST_URL, accessTokenProvider.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(String.class);
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

    private WechatReceiptQueryResult parseReceiptResponse(String body, String expectedTransactionId) {
        try {
            JsonNode response = readResponseObject(body);
            Integer errcode = strictErrcode(response);
            if (errcode == null) {
                return unknownReceiptResponse("AMBIGUOUS_RESPONSE");
            }
            if (errcode != 0) {
                return unknownReceiptResponse(safeErrorCode(errcode));
            }
            JsonNode order = response == null ? null : response.get("order");
            if (order == null || !order.isObject()) {
                return unknownReceiptResponse("AMBIGUOUS_RESPONSE");
            }
            JsonNode returnedTransactionId = order.get("transaction_id");
            JsonNode orderState = order.get("order_state");
            if (returnedTransactionId == null
                    || !returnedTransactionId.isTextual()
                    || !expectedTransactionId.equals(returnedTransactionId.textValue())
                    || orderState == null
                    || !orderState.isIntegralNumber()
                    || !orderState.canConvertToInt()) {
                return unknownReceiptResponse("ORDER_MISMATCH");
            }
            int state = orderState.intValue();
            if (CONFIRMED_ORDER_STATES.contains(state)) {
                return WechatReceiptQueryResult.confirmed(state);
            }
            if (NOT_CONFIRMED_ORDER_STATES.contains(state)) {
                return WechatReceiptQueryResult.notConfirmed(state);
            }
            return unknownReceiptResponse("UNKNOWN_ORDER_STATE");
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return unknownReceiptResponse("AMBIGUOUS_RESPONSE");
        }
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

    private WechatReceiptQueryResult receiptAccessTokenUnavailable() {
        return WechatReceiptQueryResult.unavailable(
                "ACCESS_TOKEN_UNAVAILABLE", "WeChat receipt status is unavailable"
        );
    }

    private WechatReceiptQueryResult ambiguousReceiptRequest() {
        return unknownReceiptResponse("REQUEST_AMBIGUOUS");
    }

    private WechatReceiptQueryResult unknownReceiptResponse(String errorCode) {
        return WechatReceiptQueryResult.unknown(
                errorCode, "WeChat receipt status could not be confirmed"
        );
    }

    private WechatShippingCapabilityResult unknownCapabilityResponse() {
        return WechatShippingCapabilityResult.unknown(
                "AMBIGUOUS_RESPONSE", "WeChat shipping capability is unknown"
        );
    }

    private void logReceiptResult(WechatReceiptQueryResult result, Exception exception) {
        if (exception == null) {
            log.info(
                    "WeChat receipt status query completed: status={}, orderState={}, errorCode={}",
                    result.status(), result.orderState(), result.errorCode()
            );
            return;
        }
        log.warn(
                "WeChat receipt status query failed safely: status={}, errorCode={}, exception={}",
                result.status(), result.errorCode(), exception.getClass().getSimpleName()
        );
    }

    private void logUploadResult(Long orderId, WechatShippingUploadResult result) {
        log.info(
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

    private record ShippingUploadPayload(
            @JsonProperty("order_key") OrderKey orderKey,
            @JsonProperty("logistics_type") Integer logisticsType,
            @JsonProperty("delivery_mode") Integer deliveryMode,
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
