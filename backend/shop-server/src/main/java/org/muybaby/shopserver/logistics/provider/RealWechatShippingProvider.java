package org.muybaby.shopserver.logistics.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RealWechatShippingProvider implements WechatShippingProvider {

    public static final String MISSING_TRANSACTION_ID = "MISSING_TRANSACTION_ID";
    public static final String MISSING_TRANSACTION_ID_MESSAGE = "WeChat payment transaction id is required";

    private static final Logger log = LoggerFactory.getLogger(RealWechatShippingProvider.class);
    private static final String UPLOAD_URL = "https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token={accessToken}";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WechatAccessTokenProvider accessTokenProvider;

    public RealWechatShippingProvider(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            WechatAccessTokenProvider accessTokenProvider
    ) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.accessTokenProvider = accessTokenProvider;
    }

    @Override
    public WechatShippingUploadResult upload(WechatShippingUploadRequest request) {
        if (!StringUtils.hasText(request.transactionId())) {
            return WechatShippingUploadResult.failed(MISSING_TRANSACTION_ID, MISSING_TRANSACTION_ID_MESSAGE);
        }
        if (!StringUtils.hasText(request.openid())) {
            return WechatShippingUploadResult.failed("MISSING_OPENID", "WeChat payer openid is required");
        }

        try {
            String body = objectMapper.writeValueAsString(toPayload(request));
            String responseBody = restClient.post()
                    .uri(UPLOAD_URL, accessTokenProvider.getAccessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            WechatUploadResponse response = objectMapper.readValue(responseBody, WechatUploadResponse.class);
            if (response == null || response.errcode() == null || response.errcode() == 0) {
                return WechatShippingUploadResult.uploaded();
            }
            return WechatShippingUploadResult.failed(safeErrorCode(response.errcode()), safeMessage(response.errmsg()));
        } catch (JsonProcessingException ex) {
            log.warn("WeChat shipping upload serialization failed: exception={}", ex.getClass().getSimpleName());
            return WechatShippingUploadResult.failed("PAYLOAD_ERROR", "WeChat shipping upload payload could not be processed");
        } catch (RestClientException ex) {
            log.warn("WeChat shipping upload request failed: exception={}", ex.getClass().getSimpleName());
            return WechatShippingUploadResult.failed("REQUEST_FAILED", "WeChat shipping upload request failed");
        }
    }

    private ShippingUploadPayload toPayload(WechatShippingUploadRequest request) {
        return new ShippingUploadPayload(
                new OrderKey(2, request.transactionId()),
                1,
                List.of(new ShippingItem(request.trackingNo(), request.expressCompany(), defaultString(request.shipmentNote()))),
                new Payer(request.openid())
        );
    }

    private String safeErrorCode(Integer errcode) {
        return "WECHAT_" + errcode;
    }

    private String safeMessage(String errmsg) {
        return StringUtils.hasText(errmsg) ? errmsg : "WeChat shipping upload failed";
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record ShippingUploadPayload(
            @JsonProperty("order_key") OrderKey orderKey,
            @JsonProperty("logistics_type") Integer logisticsType,
            @JsonProperty("shipping_list") List<ShippingItem> shippingList,
            Payer payer
    ) {
    }

    private record OrderKey(
            @JsonProperty("order_number_type") Integer orderNumberType,
            @JsonProperty("transaction_id") String transactionId
    ) {
    }

    private record ShippingItem(
            @JsonProperty("tracking_no") String trackingNo,
            @JsonProperty("express_company") String expressCompany,
            @JsonProperty("item_desc") String itemDesc
    ) {
    }

    private record Payer(String openid) {
    }

    private record WechatUploadResponse(Integer errcode, String errmsg) {
    }
}
