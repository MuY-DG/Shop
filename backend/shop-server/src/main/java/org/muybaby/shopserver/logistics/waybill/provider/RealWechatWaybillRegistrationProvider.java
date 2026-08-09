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

import java.net.URI;
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
public class RealWechatWaybillRegistrationProvider implements WechatWaybillRegistrationProvider {

    private static final Logger log = LoggerFactory.getLogger(RealWechatWaybillRegistrationProvider.class);
    private static final String TRACE_URL =
            "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/trace_waybill?access_token={accessToken}";
    private static final String FOLLOW_URL =
            "https://api.weixin.qq.com/cgi-bin/express/delivery/open_msg/follow_waybill?access_token={accessToken}";
    private static final String SANDBOX_DELIVERY_ID = "TEST";
    private static final int MAX_WAYBILL_TOKEN_LENGTH = 1024;
    private static final String REJECTED_MESSAGE = "WeChat waybill registration failed";
    private static final String UNKNOWN_MESSAGE = "WeChat waybill registration result is unknown";
    private static final String UNAVAILABLE_MESSAGE = "WeChat waybill registration is unavailable";

    private final RestClient restClient;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;
    private final WechatShippingErrorSanitizer errorSanitizer;

    public RealWechatWaybillRegistrationProvider(
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
    public WechatWaybillRegistrationResult trace(WechatWaybillRegistrationRequest request) {
        return register("TRACE", TRACE_URL, request);
    }

    @Override
    public WechatWaybillRegistrationResult follow(WechatWaybillRegistrationRequest request) {
        return register("FOLLOW", FOLLOW_URL, request);
    }

    private WechatWaybillRegistrationResult register(
            String operation,
            String url,
            WechatWaybillRegistrationRequest request
    ) {
        if (!valid(request)) {
            return finish(operation, shipmentId(request), unavailable("INVALID_REQUEST"), null);
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(payload(request));
        } catch (JsonProcessingException ex) {
            return finish(operation, request.shipmentId(), unavailable("PAYLOAD_ERROR"), ex);
        }

        String accessToken;
        try {
            accessToken = accessTokenProvider.getAccessToken();
        } catch (RuntimeException ex) {
            return finish(operation, request.shipmentId(), unavailable("ACCESS_TOKEN_UNAVAILABLE"), ex);
        }
        if (!StringUtils.hasText(accessToken)) {
            return finish(operation, request.shipmentId(), unavailable("ACCESS_TOKEN_UNAVAILABLE"), null);
        }

        try {
            String responseBody = restClient.post()
                    .uri(url, accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((httpRequest, response) ->
                            WechatExpressResponseBodyReader.readJson(response, maxResponseBytes));
            return finish(operation, request.shipmentId(), parse(responseBody), null);
        } catch (WechatExpressResponseBodyReader.ResponseTooLargeException ex) {
            return finish(operation, request.shipmentId(), unknown("RESPONSE_TOO_LARGE"), ex);
        } catch (RestClientException ex) {
            return finish(operation, request.shipmentId(), unknown("REQUEST_AMBIGUOUS"), ex);
        } catch (RuntimeException ex) {
            return finish(operation, request.shipmentId(), unknown("REQUEST_AMBIGUOUS"), ex);
        }
    }

    private WechatWaybillRegistrationResult parse(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        if (response == null || !response.isObject()) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        JsonNode errcodeNode = response.get("errcode");
        if (errcodeNode == null || !errcodeNode.isIntegralNumber() || !errcodeNode.canConvertToInt()) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        int errcode = errcodeNode.intValue();
        if (errcode != 0) {
            if (errcode == -1) {
                return unknown("WECHAT_SYSTEM_BUSY");
            }
            var safe = errorSanitizer.sanitize("WECHAT_" + errcode, REJECTED_MESSAGE, List.of());
            return WechatWaybillRegistrationResult.failure(
                    WechatProviderOutcome.REJECTED, safe.code(), safe.message()
            );
        }
        JsonNode tokenNode = response.get("waybill_token");
        if (tokenNode == null || !tokenNode.isTextual() || !StringUtils.hasText(tokenNode.textValue())) {
            return unknown("AMBIGUOUS_RESPONSE");
        }
        String token = tokenNode.textValue().trim();
        if (token.length() > MAX_WAYBILL_TOKEN_LENGTH) {
            return unknown("WAYBILL_TOKEN_INVALID");
        }
        return WechatWaybillRegistrationResult.success(token);
    }

    private Map<String, Object> payload(WechatWaybillRegistrationRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("openid", request.openid());
        if (StringUtils.hasText(request.senderPhone())) {
            payload.put("sender_phone", request.senderPhone().trim());
        }
        payload.put("receiver_phone", request.receiverPhone());
        payload.put("waybill_id", request.waybillId());
        payload.put("goods_info", Map.of(
                "detail_list",
                request.goods().stream()
                        .map(item -> Map.of(
                                "goods_name", item.goodsName(),
                                "goods_img_url", item.goodsImageUrl()
                        ))
                        .toList()
        ));
        payload.put("trans_id", request.transactionId());
        payload.put("order_detail_path", request.orderDetailPath());
        if (StringUtils.hasText(request.deliveryId())) {
            String deliveryId = request.deliveryId().trim();
            // TEST belongs to the electronic-waybill sandbox, not the query/message carrier directory.
            if (!SANDBOX_DELIVERY_ID.equalsIgnoreCase(deliveryId)) {
                payload.put("delivery_id", deliveryId);
            }
        }
        return payload;
    }

    private boolean valid(WechatWaybillRegistrationRequest request) {
        return request != null
                && request.shipmentId() != null
                && !missing(
                request.openid(),
                request.receiverPhone(),
                request.waybillId(),
                request.transactionId(),
                request.orderDetailPath()
        )
                && request.goods() != null
                && !request.goods().isEmpty()
                && request.goods().stream().allMatch(this::validGoods);
    }

    private boolean validGoods(WechatWaybillGoodsItem item) {
        if (item == null || missing(item.goodsName(), item.goodsImageUrl())) {
            return false;
        }
        try {
            URI uri = URI.create(item.goodsImageUrl().trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private WechatWaybillRegistrationResult unavailable(String code) {
        return WechatWaybillRegistrationResult.failure(
                WechatProviderOutcome.UNAVAILABLE, code, UNAVAILABLE_MESSAGE
        );
    }

    private WechatWaybillRegistrationResult unknown(String code) {
        return WechatWaybillRegistrationResult.failure(
                WechatProviderOutcome.UNKNOWN, code, UNKNOWN_MESSAGE
        );
    }

    private WechatWaybillRegistrationResult finish(
            String operation,
            Long shipmentId,
            WechatWaybillRegistrationResult result,
            Exception exception
    ) {
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            log.info(
                    "WeChat waybill registration completed: operation={}, shipmentId={}, outcome={}",
                    operation, shipmentId, result.outcome()
            );
        } else {
            log.warn(
                    "WeChat waybill registration completed: operation={}, shipmentId={}, outcome={}, errorCode={}, exception={}",
                    operation,
                    shipmentId,
                    result.outcome(),
                    result.errorCode(),
                    exception == null ? "none" : exception.getClass().getSimpleName()
            );
        }
        return result;
    }

    private Long shipmentId(WechatWaybillRegistrationRequest request) {
        return request == null ? null : request.shipmentId();
    }
}
