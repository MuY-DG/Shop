package org.muybaby.shopserver.logistics.waybill.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.logistics.service.WechatShippingErrorSanitizer;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.mini-program",
        name = "mock-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class RealWechatElectronicWaybillProvider implements WechatElectronicWaybillProvider {

    private static final Logger log = LoggerFactory.getLogger(RealWechatElectronicWaybillProvider.class);
    private static final String ADD_URL =
            "https://api.weixin.qq.com/cgi-bin/express/business/order/add?access_token={accessToken}";
    private static final String GET_URL =
            "https://api.weixin.qq.com/cgi-bin/express/business/order/get?access_token={accessToken}";
    private static final String CANCEL_URL =
            "https://api.weixin.qq.com/cgi-bin/express/business/order/cancel?access_token={accessToken}";
    private static final String TEST_UPDATE_URL =
            "https://api.weixin.qq.com/cgi-bin/express/business/test_update_order?access_token={accessToken}";
    private static final String SANDBOX_DELIVERY_ID = "TEST";
    private static final String SANDBOX_BIZ_ID = "test_biz_id";
    private static final int SANDBOX_SERVICE_TYPE = 1;
    private static final String SANDBOX_SERVICE_NAME = "test_service_name";
    private static final Set<Integer> SANDBOX_ACTION_TYPES = Set.of(100001, 200001, 300002, 300003);
    private static final int MAX_PRINT_HTML_BASE64_LENGTH = 4_000_000;
    private static final int MAX_PRINT_HTML_DECODED_LENGTH = 2_000_000;
    private static final String REJECTED_MESSAGE = "WeChat electronic waybill operation failed";
    private static final String UNKNOWN_MESSAGE = "WeChat electronic waybill result is unknown";
    private static final String UNAVAILABLE_MESSAGE = "WeChat express service is unavailable";

    private final RestClient restClient;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;
    private final WechatShippingErrorSanitizer errorSanitizer;

    public RealWechatElectronicWaybillProvider(
            @Qualifier(WechatExpressHttpConfiguration.REST_CLIENT_BEAN_NAME) RestClient restClient,
            WechatExpressHttpProperties httpProperties,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider,
            WechatShippingErrorSanitizer errorSanitizer
    ) {
        this.restClient = restClient;
        this.maxResponseBytes = httpProperties.maxResponseBytes();
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
        this.errorSanitizer = errorSanitizer;
    }

    @Override
    public WechatElectronicWaybillResult add(WechatElectronicWaybillAddRequest request) {
        String validationCode = validateAdd(request);
        if (validationCode != null) {
            return finish("ADD", localRecordId(request), unavailable(validationCode), null);
        }
        return post(
                "ADD",
                request.localRecordId(),
                ADD_URL,
                addPayload(request),
                response -> parseAdd(response, request)
        );
    }

    @Override
    public WechatElectronicWaybillResult get(WechatElectronicWaybillGetRequest request) {
        String validationCode = validateGet(request);
        if (validationCode != null) {
            return finish("GET", localRecordId(request), unavailable(validationCode), null);
        }
        return post(
                "GET",
                request.localRecordId(),
                GET_URL,
                getPayload(request),
                response -> parseGet(response, request)
        );
    }

    @Override
    public WechatElectronicWaybillResult cancel(WechatElectronicWaybillCancelRequest request) {
        String validationCode = validateCancel(request);
        if (validationCode != null) {
            return finish("CANCEL", localRecordId(request), unavailable(validationCode), null);
        }
        return post(
                "CANCEL",
                request.localRecordId(),
                CANCEL_URL,
                cancelPayload(request),
                this::parseCarrierOperation
        );
    }

    @Override
    public WechatElectronicWaybillResult testUpdate(WechatElectronicWaybillTestUpdateRequest request) {
        String validationCode = validateTestUpdate(request);
        if (validationCode != null) {
            return finish("TEST_UPDATE", localRecordId(request), unavailable(validationCode), null);
        }
        return post(
                "TEST_UPDATE",
                request.localRecordId(),
                TEST_UPDATE_URL,
                testUpdatePayload(request),
                this::parseOperationOnly
        );
    }

    private WechatElectronicWaybillResult post(
            String operation,
            Long localRecordId,
            String url,
            Map<String, Object> payload,
            ResponseParser parser
    ) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return finish(operation, localRecordId, unavailable("PAYLOAD_ERROR"), ex);
        }

        String accessToken;
        try {
            accessToken = accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            return finish(operation, localRecordId, unavailable("ACCESS_TOKEN_UNAVAILABLE"), ex);
        }
        if (!StringUtils.hasText(accessToken)) {
            return finish(operation, localRecordId, unavailable("ACCESS_TOKEN_UNAVAILABLE"), null);
        }

        try {
            String responseBody = restClient.post()
                    .uri(url, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((httpRequest, response) ->
                            WechatExpressResponseBodyReader.readJson(response, maxResponseBytes));
            return finish(operation, localRecordId, parser.parse(responseBody), null);
        } catch (WechatExpressResponseBodyReader.ResponseTooLargeException ex) {
            return finish(operation, localRecordId, unknown("RESPONSE_TOO_LARGE"), ex);
        } catch (RestClientException ex) {
            return finish(operation, localRecordId, unknown("REQUEST_AMBIGUOUS"), ex);
        } catch (RuntimeException ex) {
            return finish(operation, localRecordId, unknown("REQUEST_AMBIGUOUS"), ex);
        }
    }

    private WechatElectronicWaybillResult parseAdd(
            String responseBody,
            WechatElectronicWaybillAddRequest request
    ) {
        JsonNode response = readObject(responseBody);
        if (response == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = optionalErrcode(response);
        if (hasInvalidErrcode(response, errcode)) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (errcode != null && errcode != 0) {
            return classifyWechatError(errcode);
        }
        Integer deliveryResultCode = optionalInteger(response, "delivery_resultcode");
        if (hasInvalidInteger(response, "delivery_resultcode", deliveryResultCode)) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (deliveryResultCode != null && deliveryResultCode != 0) {
            return classifyDeliveryError(deliveryResultCode);
        }

        String returnedOrderId = strictText(response, "order_id");
        String returnedWaybillId = strictText(response, "waybill_id");
        if (!request.providerOrderId().equals(returnedOrderId) || !StringUtils.hasText(returnedWaybillId)) {
            return unknown("RESPONSE_IDENTITY_MISMATCH");
        }
        return WechatElectronicWaybillResult.success(
                returnedOrderId, request.deliveryId(), returnedWaybillId, 0, null
        );
    }

    private WechatElectronicWaybillResult parseGet(
            String responseBody,
            WechatElectronicWaybillGetRequest request
    ) {
        JsonNode response = readObject(responseBody);
        if (response == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = optionalErrcode(response);
        if (hasInvalidErrcode(response, errcode)) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (errcode != null && errcode != 0) {
            return classifyWechatError(errcode);
        }

        String returnedOrderId = strictText(response, "order_id");
        String returnedDeliveryId = strictText(response, "delivery_id");
        String returnedWaybillId = strictText(response, "waybill_id");
        Integer orderStatus = strictInteger(response, "order_status");
        boolean waybillMatches = !StringUtils.hasText(request.waybillId())
                ? StringUtils.hasText(returnedWaybillId)
                : request.waybillId().equals(returnedWaybillId);
        if (!request.providerOrderId().equals(returnedOrderId)
                || !request.deliveryId().equals(returnedDeliveryId)
                || !waybillMatches
                || orderStatus == null
                || (orderStatus != 0 && orderStatus != 1)) {
            return unknown("RESPONSE_IDENTITY_MISMATCH");
        }

        JsonNode printHtmlNode = response.get("print_html");
        String printHtml = null;
        if (printHtmlNode == null || printHtmlNode.isNull()) {
            if (request.printType() != null) {
                return unknown("PRINT_DATA_INVALID");
            }
        } else {
            printHtml = validPrintHtmlBase64(printHtmlNode);
            if (printHtml == null) {
                return unknown("PRINT_DATA_INVALID");
            }
        }
        return WechatElectronicWaybillResult.success(
                returnedOrderId, returnedDeliveryId, returnedWaybillId, orderStatus, printHtml
        );
    }

    private WechatElectronicWaybillResult parseOperationOnly(String responseBody) {
        JsonNode response = readObject(responseBody);
        if (response == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = optionalErrcode(response);
        if (hasInvalidErrcode(response, errcode) || errcode == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (errcode != 0) {
            return classifyWechatError(errcode);
        }
        return WechatElectronicWaybillResult.success(null, null, null, null, null);
    }

    private WechatElectronicWaybillResult parseCarrierOperation(String responseBody) {
        JsonNode response = readObject(responseBody);
        if (response == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = optionalErrcode(response);
        if (hasInvalidErrcode(response, errcode) || errcode == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (errcode != 0) {
            return classifyWechatError(errcode);
        }
        Integer deliveryResultCode = optionalInteger(response, "delivery_resultcode");
        if (deliveryResultCode == null) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (deliveryResultCode != 0) {
            return classifyDeliveryError(deliveryResultCode);
        }
        return WechatElectronicWaybillResult.success(null, null, null, null, null);
    }

    private JsonNode readObject(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            return response != null && response.isObject() ? response : null;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return null;
        }
    }

    private Integer optionalErrcode(JsonNode response) {
        JsonNode node = response.get("errcode");
        if (node == null) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return null;
        }
        return node.intValue();
    }

    private boolean hasInvalidErrcode(JsonNode response, Integer errcode) {
        return response.has("errcode") && errcode == null;
    }

    private String strictText(JsonNode response, String field) {
        JsonNode node = response.get(field);
        return node != null && node.isTextual() && StringUtils.hasText(node.textValue())
                ? node.textValue().trim()
                : null;
    }

    private Integer strictInteger(JsonNode response, String field) {
        JsonNode node = response.get(field);
        return node != null && node.isIntegralNumber() && node.canConvertToInt()
                ? node.intValue()
                : null;
    }

    private Integer optionalInteger(JsonNode response, String field) {
        JsonNode node = response.get(field);
        if (node == null) {
            return null;
        }
        return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    private boolean hasInvalidInteger(JsonNode response, String field, Integer value) {
        return response.has(field) && value == null;
    }

    private String validPrintHtmlBase64(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String encoded = node.textValue();
        if (!StringUtils.hasText(encoded) || encoded.length() > MAX_PRINT_HTML_BASE64_LENGTH) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length == 0 || decoded.length > MAX_PRINT_HTML_DECODED_LENGTH) {
                return null;
            }
            return Base64.getEncoder().encodeToString(decoded).equals(encoded) ? encoded : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Map<String, Object> addPayload(WechatElectronicWaybillAddRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", request.providerOrderId());
        payload.put("openid", request.openid());
        payload.put("delivery_id", request.deliveryId());
        payload.put("biz_id", request.bizId());
        if (StringUtils.hasText(request.customRemark())) {
            payload.put("custom_remark", request.customRemark().trim());
        }
        payload.put("add_source", 0);
        payload.put("sender", contactPayload(request.sender()));
        payload.put("receiver", contactPayload(request.receiver()));

        Map<String, Object> cargo = new LinkedHashMap<>();
        cargo.put("count", request.parcelCount());
        cargo.put("weight", request.weightKg());
        cargo.put("space_x", request.lengthCm());
        cargo.put("space_y", request.widthCm());
        cargo.put("space_z", request.heightCm());
        cargo.put("detail_list", request.cargoItems().stream()
                .map(item -> Map.<String, Object>of("name", item.name(), "count", item.count()))
                .toList());
        payload.put("cargo", cargo);

        Map<String, Object> shop = new LinkedHashMap<>();
        shop.put("wxa_path", request.miniProgramOrderPath());
        shop.put("detail_list", request.shopItems().stream()
                .map(item -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("goods_name", item.goodsName());
                    value.put("goods_img_url", item.goodsImageUrl());
                    value.put("goods_desc", item.goodsDescription());
                    return value;
                })
                .toList());
        payload.put("shop", shop);
        payload.put("insured", Map.of("use_insured", 0, "insured_value", 0));
        payload.put("service", Map.of(
                "service_type", request.serviceType(),
                "service_name", request.serviceName()
        ));
        if (request.expectedPickupTime() != null) {
            payload.put("expect_time", request.expectedPickupTime());
        }
        return payload;
    }

    private Map<String, Object> getPayload(WechatElectronicWaybillGetRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", request.providerOrderId());
        if (StringUtils.hasText(request.openid())) {
            payload.put("openid", request.openid());
        }
        payload.put("delivery_id", request.deliveryId());
        if (StringUtils.hasText(request.waybillId())) {
            payload.put("waybill_id", request.waybillId());
        }
        if (request.printType() != null) {
            payload.put("print_type", request.printType());
        }
        return payload;
    }

    private Map<String, Object> cancelPayload(WechatElectronicWaybillCancelRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", request.providerOrderId());
        if (StringUtils.hasText(request.openid())) {
            payload.put("openid", request.openid());
        }
        payload.put("delivery_id", request.deliveryId());
        payload.put("waybill_id", request.waybillId());
        return payload;
    }

    private Map<String, Object> testUpdatePayload(WechatElectronicWaybillTestUpdateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("biz_id", request.bizId());
        payload.put("order_id", request.providerOrderId());
        payload.put("delivery_id", request.deliveryId());
        payload.put("waybill_id", request.waybillId());
        payload.put("action_time", request.actionTime());
        payload.put("action_type", request.actionType());
        payload.put("action_msg", request.actionMessage());
        return payload;
    }

    private Map<String, Object> contactPayload(WechatExpressContact contact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", contact.name().trim());
        value.put("mobile", contact.mobile().trim());
        if (StringUtils.hasText(contact.company())) {
            value.put("company", contact.company().trim());
        }
        value.put("country", StringUtils.hasText(contact.country()) ? contact.country().trim() : "中国");
        value.put("province", contact.province().trim());
        value.put("city", contact.city().trim());
        value.put("area", contact.area().trim());
        value.put("address", contact.address().trim());
        return value;
    }

    private String validateAdd(WechatElectronicWaybillAddRequest request) {
        if (request == null
                || request.localRecordId() == null
                || request.environment() == null
                || missing(request.providerOrderId(), request.openid(), request.deliveryId(), request.bizId())
                || !validContact(request.sender())
                || !validContact(request.receiver())
                || request.parcelCount() < 1
                || !positive(request.weightKg())
                || !positive(request.lengthCm())
                || !positive(request.widthCm())
                || !positive(request.heightCm())
                || request.cargoItems() == null
                || request.cargoItems().isEmpty()
                || request.cargoItems().stream().anyMatch(item -> item == null || !StringUtils.hasText(item.name()) || item.count() < 1)
                || !StringUtils.hasText(request.miniProgramOrderPath())
                || request.shopItems() == null
                || request.shopItems().isEmpty()
                || request.shopItems().stream().anyMatch(item -> !validShopItem(item))
                || request.serviceType() < 0
                || !StringUtils.hasText(request.serviceName())
                || (request.expectedPickupTime() != null && request.expectedPickupTime() < 0)) {
            return "INVALID_REQUEST";
        }
        if (request.environment() == WechatElectronicWaybillEnvironment.SANDBOX
                && (!SANDBOX_DELIVERY_ID.equals(request.deliveryId())
                || !SANDBOX_BIZ_ID.equals(request.bizId())
                || request.serviceType() != SANDBOX_SERVICE_TYPE
                || !SANDBOX_SERVICE_NAME.equals(request.serviceName()))) {
            return "INVALID_SANDBOX_CONFIGURATION";
        }
        return null;
    }

    private String validateGet(WechatElectronicWaybillGetRequest request) {
        if (request == null
                || request.localRecordId() == null
                || missing(request.providerOrderId(), request.deliveryId())
                || (request.printType() != null && request.printType() != 0 && request.printType() != 1)) {
            return "INVALID_REQUEST";
        }
        return null;
    }

    private String validateCancel(WechatElectronicWaybillCancelRequest request) {
        if (request == null
                || request.localRecordId() == null
                || missing(request.providerOrderId(), request.deliveryId(), request.waybillId())) {
            return "INVALID_REQUEST";
        }
        return null;
    }

    private String validateTestUpdate(WechatElectronicWaybillTestUpdateRequest request) {
        if (request == null
                || request.localRecordId() == null
                || !SANDBOX_BIZ_ID.equals(request.bizId())
                || !SANDBOX_DELIVERY_ID.equals(request.deliveryId())
                || missing(request.providerOrderId(), request.waybillId(), request.actionMessage())
                || request.actionTime() < 1
                || !SANDBOX_ACTION_TYPES.contains(request.actionType())) {
            return "INVALID_SANDBOX_EVENT";
        }
        return null;
    }

    private boolean validContact(WechatExpressContact contact) {
        return contact != null && !missing(
                contact.name(), contact.mobile(), contact.province(), contact.city(), contact.area(), contact.address()
        );
    }

    private boolean validShopItem(WechatExpressShopItem item) {
        if (item == null || missing(item.goodsName(), item.goodsImageUrl(), item.goodsDescription())) {
            return false;
        }
        try {
            URI uri = URI.create(item.goodsImageUrl().trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private WechatElectronicWaybillResult rejected(int errcode) {
        var safe = errorSanitizer.sanitize("WECHAT_" + errcode, REJECTED_MESSAGE, List.of());
        return WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.REJECTED, safe.code(), safe.message()
        );
    }

    private WechatElectronicWaybillResult classifyWechatError(int errcode) {
        return errcode == -1 ? unknown("WECHAT_SYSTEM_BUSY") : rejected(errcode);
    }

    private WechatElectronicWaybillResult rejectedDelivery(int resultCode) {
        var safe = errorSanitizer.sanitize(
                "WECHAT_DELIVERY_" + resultCode, REJECTED_MESSAGE, List.of()
        );
        return WechatElectronicWaybillResult.failure(
                WechatProviderOutcome.REJECTED, safe.code(), safe.message()
        );
    }

    private WechatElectronicWaybillResult classifyDeliveryError(int resultCode) {
        return resultCode == -1 ? unknown("WECHAT_SYSTEM_BUSY") : rejectedDelivery(resultCode);
    }

    private WechatElectronicWaybillResult unknown(String code) {
        return WechatElectronicWaybillResult.failure(WechatProviderOutcome.UNKNOWN, code, UNKNOWN_MESSAGE);
    }

    private WechatElectronicWaybillResult unavailable(String code) {
        return WechatElectronicWaybillResult.failure(WechatProviderOutcome.UNAVAILABLE, code, UNAVAILABLE_MESSAGE);
    }

    private WechatElectronicWaybillResult finish(
            String operation,
            Long localRecordId,
            WechatElectronicWaybillResult result,
            Exception exception
    ) {
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            log.info(
                    "WeChat express operation completed: operation={}, recordId={}, outcome={}",
                    operation, localRecordId, result.outcome()
            );
        } else {
            log.warn(
                    "WeChat express operation completed: operation={}, recordId={}, outcome={}, errorCode={}, exception={}",
                    operation,
                    localRecordId,
                    result.outcome(),
                    result.errorCode(),
                    exception == null ? "none" : exception.getClass().getSimpleName()
            );
        }
        return result;
    }

    private Long localRecordId(WechatElectronicWaybillAddRequest request) {
        return request == null ? null : request.localRecordId();
    }

    private Long localRecordId(WechatElectronicWaybillGetRequest request) {
        return request == null ? null : request.localRecordId();
    }

    private Long localRecordId(WechatElectronicWaybillCancelRequest request) {
        return request == null ? null : request.localRecordId();
    }

    private Long localRecordId(WechatElectronicWaybillTestUpdateRequest request) {
        return request == null ? null : request.localRecordId();
    }

    @FunctionalInterface
    private interface ResponseParser {
        WechatElectronicWaybillResult parse(String responseBody);
    }
}
