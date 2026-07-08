package org.muybaby.shopserver.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RestWechatAccessTokenProvider implements WechatAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(RestWechatAccessTokenProvider.class);
    private static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";

    private final WechatMiniProgramProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestWechatAccessTokenProvider(
            WechatMiniProgramProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getAccessToken() {
        validateCredentials();
        try {
            String body = restClient.post()
                    .uri(STABLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(writeWechatRequestBody(
                            new AccessTokenRequest("client_credential", properties.appId(), properties.appSecret(), false)
                    ))
                    .retrieve()
                    .body(String.class);
            AccessTokenResponse response = readWechatResponse(body);

            if (response == null || response.hasError() || !StringUtils.hasText(response.accessToken())) {
                logWechatError(response == null ? null : response.errcode(), response == null ? null : response.errmsg());
                throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
            }
            return response.accessToken();
        } catch (RestClientException ex) {
            logWechatRequestFailure(ex);
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private String writeWechatRequestBody(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            log.warn("WeChat stableToken request serialization failed: exception={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private AccessTokenResponse readWechatResponse(String body) {
        if (!StringUtils.hasText(body)) {
            logWechatError(null, "empty response");
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
        try {
            return objectMapper.readValue(body, AccessTokenResponse.class);
        } catch (JsonProcessingException ex) {
            log.warn("WeChat stableToken response parse failed: exception={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private void validateCredentials() {
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
            log.warn("WeChat mini program credentials missing for stable token");
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private void logWechatError(Integer errcode, String errmsg) {
        log.warn("WeChat stableToken failed: errcode={}, errmsg={}", errcode, errmsg);
    }

    private void logWechatRequestFailure(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            logWechatResponseBody(responseException.getStatusCode().toString(), responseException.getResponseBodyAsString());
            return;
        }
        log.warn("WeChat stableToken request failed: exception={}", ex.getClass().getSimpleName());
    }

    private void logWechatResponseBody(String status, String body) {
        if (!StringUtils.hasText(body)) {
            log.warn("WeChat stableToken request failed: status={}, empty response body", status);
            return;
        }
        try {
            WechatErrorResponse response = objectMapper.readValue(body, WechatErrorResponse.class);
            logWechatError(response.errcode(), response.errmsg());
        } catch (JsonProcessingException parseException) {
            log.warn("WeChat stableToken request failed: status={}, response parse exception={}", status, parseException.getClass().getSimpleName());
        }
    }

    private record AccessTokenRequest(
            @JsonProperty("grant_type") String grantType,
            String appid,
            String secret,
            @JsonProperty("force_refresh") boolean forceRefresh
    ) {
    }

    private record AccessTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Integer expiresIn,
            Integer errcode,
            String errmsg
    ) {
        private boolean hasError() {
            return errcode != null && errcode != 0;
        }
    }

    private record WechatErrorResponse(Integer errcode, String errmsg) {
    }
}
