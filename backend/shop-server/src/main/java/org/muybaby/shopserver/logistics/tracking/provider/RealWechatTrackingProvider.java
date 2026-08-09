package org.muybaby.shopserver.logistics.tracking.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.logistics.service.WechatShippingErrorSanitizer;
import org.muybaby.shopserver.logistics.tracking.WechatTrackingProperties;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressHttpConfiguration;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressHttpProperties;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressResponseBodyReader;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        prefix = "shop.wechat.mini-program",
        name = "mock-enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class RealWechatTrackingProvider implements WechatTrackingProvider {

    private static final Logger log = LoggerFactory.getLogger(RealWechatTrackingProvider.class);
    private static final String QUERY_TRACE_URL =
            "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/query_trace?access_token={accessToken}";
    private static final String QUERY_FOLLOW_TRACE_URL =
            "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/query_follow_trace?access_token={accessToken}";
    private static final String GET_PATH_URL =
            "https://api.weixin.qq.com/cgi-bin/express/business/path/get?access_token={accessToken}";
    private static final int MAX_ACTION_MESSAGE_LENGTH = 512;
    private static final String QUERY_REJECTED_MESSAGE = "WeChat tracking status query failed";
    private static final String PATH_REJECTED_MESSAGE = "WeChat tracking path query failed";
    private static final String UNKNOWN_MESSAGE = "WeChat tracking query result is unknown";
    private static final String UNAVAILABLE_MESSAGE = "WeChat tracking service is unavailable";

    private final RestClient restClient;
    private final int maxResponseBytes;
    private final int maxPathItems;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;
    private final WechatShippingErrorSanitizer errorSanitizer;

    public RealWechatTrackingProvider(
            @Qualifier(WechatExpressHttpConfiguration.REST_CLIENT_BEAN_NAME) RestClient restClient,
            WechatExpressHttpProperties httpProperties,
            WechatTrackingProperties trackingProperties,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider,
            WechatShippingErrorSanitizer errorSanitizer
    ) {
        this.restClient = restClient;
        this.maxResponseBytes = httpProperties.maxResponseBytes();
        this.maxPathItems = trackingProperties.maxPathItems();
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
        this.errorSanitizer = errorSanitizer;
    }

    @Override
    public WechatTrackingQueryResult query(WechatTrackingQueryRequest request) {
        if (!validQuery(request)) {
            return finishQuery(request, unavailableQuery("INVALID_REQUEST"), null);
        }
        String url = request.registrationKind() == WaybillRegistrationKind.FOLLOW
                ? QUERY_FOLLOW_TRACE_URL
                : QUERY_TRACE_URL;
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "waybill_token", request.waybillToken().trim()
            ));
        } catch (JsonProcessingException ex) {
            return finishQuery(request, unavailableQuery("PAYLOAD_ERROR"), ex);
        }
        String accessToken = accessToken();
        if (!StringUtils.hasText(accessToken)) {
            return finishQuery(request, unavailableQuery("ACCESS_TOKEN_UNAVAILABLE"), null);
        }
        try {
            String responseBody = post(url, accessToken, body);
            return finishQuery(request, parseQuery(responseBody), null);
        } catch (WechatExpressResponseBodyReader.ResponseTooLargeException ex) {
            return finishQuery(request, unknownQuery("RESPONSE_TOO_LARGE"), ex);
        } catch (RuntimeException ex) {
            return finishQuery(request, unknownQuery("REQUEST_AMBIGUOUS"), ex);
        }
    }

    @Override
    public WechatTrackingPathResult getPath(WechatTrackingPathRequest request) {
        if (!validPath(request)) {
            return finishPath(request, unavailablePath("INVALID_REQUEST"), null);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("order_id", request.providerOrderId().trim());
        payload.put("openid", request.openid().trim());
        payload.put("delivery_id", request.deliveryId().trim());
        payload.put("waybill_id", request.waybillId().trim());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return finishPath(request, unavailablePath("PAYLOAD_ERROR"), ex);
        }
        String accessToken = accessToken();
        if (!StringUtils.hasText(accessToken)) {
            return finishPath(request, unavailablePath("ACCESS_TOKEN_UNAVAILABLE"), null);
        }
        try {
            String responseBody = post(GET_PATH_URL, accessToken, body);
            return finishPath(request, parsePath(responseBody, request), null);
        } catch (WechatExpressResponseBodyReader.ResponseTooLargeException ex) {
            return finishPath(request, unknownPath("RESPONSE_TOO_LARGE"), ex);
        } catch (RuntimeException ex) {
            return finishPath(request, unknownPath("REQUEST_AMBIGUOUS"), ex);
        }
    }

    private String post(String url, String accessToken, String body) {
        return restClient.post()
                .uri(url, accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((httpRequest, response) ->
                        WechatExpressResponseBodyReader.readJson(response, maxResponseBytes));
    }

    private WechatTrackingQueryResult parseQuery(String responseBody) {
        JsonNode response = objectResponse(responseBody);
        if (response == null) {
            return unknownQuery("AMBIGUOUS_RESPONSE");
        }
        if (hasInvalidErrcode(response)) {
            return unknownQuery("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = errcode(response);
        if (errcode != null && errcode != 0) {
            return rejectedQuery(errcode);
        }
        JsonNode statusNode = response.path("waybill_info").get("status");
        if (statusNode == null || !statusNode.isIntegralNumber() || !statusNode.canConvertToInt()) {
            return unknownQuery("AMBIGUOUS_RESPONSE");
        }
        int status = statusNode.intValue();
        if (status < 0 || status > 6) {
            return unknownQuery("STATUS_INVALID");
        }
        return WechatTrackingQueryResult.success(status);
    }

    private WechatTrackingPathResult parsePath(
            String responseBody,
            WechatTrackingPathRequest request
    ) {
        JsonNode response = objectResponse(responseBody);
        if (response == null) {
            return unknownPath("AMBIGUOUS_RESPONSE");
        }
        if (hasInvalidErrcode(response)) {
            return unknownPath("AMBIGUOUS_RESPONSE");
        }
        Integer errcode = errcode(response);
        if (errcode != null && errcode != 0) {
            return rejectedPath(errcode);
        }
        if (!matchesIfPresent(response.get("delivery_id"), request.deliveryId())
                || !matchesIfPresent(response.get("waybill_id"), request.waybillId())) {
            return unknownPath("RESPONSE_IDENTITY_MISMATCH");
        }
        JsonNode itemsNode = response.get("path_item_list");
        if (itemsNode == null || !itemsNode.isArray()) {
            return unknownPath("AMBIGUOUS_RESPONSE");
        }
        if (itemsNode.size() > maxPathItems) {
            return unknownPath("PATH_ITEMS_EXCEEDED");
        }
        List<WechatTrackingPathItem> items = new ArrayList<>();
        for (JsonNode itemNode : itemsNode) {
            if (itemNode == null || !itemNode.isObject()) {
                return unknownPath("PATH_ITEM_INVALID");
            }
            JsonNode timeNode = itemNode.get("action_time");
            JsonNode typeNode = itemNode.get("action_type");
            JsonNode messageNode = itemNode.get("action_msg");
            if (timeNode == null || !timeNode.isIntegralNumber() || !timeNode.canConvertToLong()
                    || timeNode.longValue() <= 0
                    || typeNode == null || !typeNode.isIntegralNumber() || !typeNode.canConvertToInt()
                    || messageNode == null || !messageNode.isTextual()) {
                return unknownPath("PATH_ITEM_INVALID");
            }
            String message = messageNode.textValue() == null ? "" : messageNode.textValue().trim();
            if (!StringUtils.hasText(message) || message.length() > MAX_ACTION_MESSAGE_LENGTH) {
                return unknownPath("PATH_ITEM_INVALID");
            }
            items.add(new WechatTrackingPathItem(
                    timeNode.longValue(), typeNode.intValue(), message
            ));
        }
        return WechatTrackingPathResult.success(items);
    }

    private JsonNode objectResponse(String responseBody) {
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

    private Integer errcode(JsonNode response) {
        JsonNode node = response.get("errcode");
        if (node == null) {
            return null;
        }
        return node.intValue();
    }

    private boolean hasInvalidErrcode(JsonNode response) {
        JsonNode node = response.get("errcode");
        return node != null && (!node.isIntegralNumber() || !node.canConvertToInt());
    }

    private boolean matchesIfPresent(JsonNode node, String expected) {
        return node == null || (node.isTextual() && expected.trim().equals(node.textValue()));
    }

    private String accessToken() {
        try {
            return accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean validQuery(WechatTrackingQueryRequest request) {
        return request != null
                && request.shipmentId() > 0
                && request.registrationKind() != null
                && StringUtils.hasText(request.waybillToken());
    }

    private boolean validPath(WechatTrackingPathRequest request) {
        return request != null
                && request.shipmentId() > 0
                && !missing(
                request.providerOrderId(), request.openid(), request.deliveryId(), request.waybillId()
        );
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private WechatTrackingQueryResult rejectedQuery(int errcode) {
        if (errcode == -1) {
            return unknownQuery("WECHAT_SYSTEM_BUSY");
        }
        var safe = errorSanitizer.sanitize(
                "WECHAT_" + errcode, QUERY_REJECTED_MESSAGE, List.of()
        );
        return WechatTrackingQueryResult.failure(
                WechatProviderOutcome.REJECTED, safe.code(), safe.message()
        );
    }

    private WechatTrackingPathResult rejectedPath(int errcode) {
        if (errcode == -1) {
            return unknownPath("WECHAT_SYSTEM_BUSY");
        }
        var safe = errorSanitizer.sanitize(
                "WECHAT_" + errcode, PATH_REJECTED_MESSAGE, List.of()
        );
        return WechatTrackingPathResult.failure(
                WechatProviderOutcome.REJECTED, safe.code(), safe.message()
        );
    }

    private WechatTrackingQueryResult unavailableQuery(String code) {
        return WechatTrackingQueryResult.failure(
                WechatProviderOutcome.UNAVAILABLE, code, UNAVAILABLE_MESSAGE
        );
    }

    private WechatTrackingPathResult unavailablePath(String code) {
        return WechatTrackingPathResult.failure(
                WechatProviderOutcome.UNAVAILABLE, code, UNAVAILABLE_MESSAGE
        );
    }

    private WechatTrackingQueryResult unknownQuery(String code) {
        return WechatTrackingQueryResult.failure(
                WechatProviderOutcome.UNKNOWN, code, UNKNOWN_MESSAGE
        );
    }

    private WechatTrackingPathResult unknownPath(String code) {
        return WechatTrackingPathResult.failure(
                WechatProviderOutcome.UNKNOWN, code, UNKNOWN_MESSAGE
        );
    }

    private WechatTrackingQueryResult finishQuery(
            WechatTrackingQueryRequest request,
            WechatTrackingQueryResult result,
            Exception exception
    ) {
        logResult("QUERY", shipmentId(request), result.outcome(), result.errorCode(), exception);
        return result;
    }

    private WechatTrackingPathResult finishPath(
            WechatTrackingPathRequest request,
            WechatTrackingPathResult result,
            Exception exception
    ) {
        logResult("GET_PATH", shipmentId(request), result.outcome(), result.errorCode(), exception);
        return result;
    }

    private void logResult(
            String operation,
            Long shipmentId,
            WechatProviderOutcome outcome,
            String errorCode,
            Exception exception
    ) {
        if (outcome == WechatProviderOutcome.SUCCESS) {
            log.info(
                    "WeChat tracking request completed: operation={}, shipmentId={}, outcome={}",
                    operation, shipmentId, outcome
            );
            return;
        }
        log.warn(
                "WeChat tracking request completed: operation={}, shipmentId={}, outcome={}, errorCode={}, exception={}",
                operation,
                shipmentId,
                outcome,
                safeCode(errorCode),
                exception == null ? "none" : exception.getClass().getSimpleName()
        );
    }

    private Long shipmentId(WechatTrackingQueryRequest request) {
        return request == null ? null : request.shipmentId();
    }

    private Long shipmentId(WechatTrackingPathRequest request) {
        return request == null ? null : request.shipmentId();
    }

    private String safeCode(String value) {
        if (!StringUtils.hasText(value)) {
            return "UNKNOWN";
        }
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.substring(0, Math.min(safe.length(), 64));
    }
}
