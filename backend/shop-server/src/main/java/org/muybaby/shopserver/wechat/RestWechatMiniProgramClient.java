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
public class RestWechatMiniProgramClient implements WechatMiniProgramClient {

    private static final Logger log = LoggerFactory.getLogger(RestWechatMiniProgramClient.class);
    private static final String CODE_TO_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String GET_PHONE_NUMBER_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private final WechatMiniProgramProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestWechatMiniProgramClient(
            WechatMiniProgramProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public WechatCodeSession code2Session(String code) {
        validateCredentials(ErrorCode.WECHAT_LOGIN_FAILED);
        try {
            String body = restClient.get()
                    .uri(CODE_TO_SESSION_URL + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                            properties.appId(), properties.appSecret(), code)
                    .retrieve()
                    .body(String.class);
            CodeToSessionResponse response = readWechatResponse(
                    "code2Session",
                    body,
                    CodeToSessionResponse.class,
                    ErrorCode.WECHAT_LOGIN_FAILED
            );

            if (response == null || response.hasError() || !StringUtils.hasText(response.openid())) {
                logWechatError("code2Session", response == null ? null : response.errcode(), response == null ? null : response.errmsg());
                throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
            }
            return new WechatCodeSession(response.openid(), response.unionid(), response.sessionKey());
        } catch (RestClientException ex) {
            logWechatRequestFailure("code2Session", ex);
            throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
        }
    }

    @Override
    public WechatPhoneInfo getPhoneNumber(String code) {
        validateCredentials(ErrorCode.WECHAT_PHONE_FAILED);
        String accessToken = fetchAccessToken();
        try {
            String body = restClient.post()
                    .uri(GET_PHONE_NUMBER_URL + "?access_token={accessToken}", accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(writeWechatRequestBody("getPhoneNumber", new PhoneNumberRequest(code), ErrorCode.WECHAT_PHONE_FAILED))
                    .retrieve()
                    .body(String.class);
            PhoneNumberResponse response = readWechatResponse(
                    "getPhoneNumber",
                    body,
                    PhoneNumberResponse.class,
                    ErrorCode.WECHAT_PHONE_FAILED
            );

            if (response == null || response.hasError() || response.phoneInfo() == null) {
                logWechatError("getPhoneNumber", response == null ? null : response.errcode(), response == null ? null : response.errmsg());
                throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
            }
            PhoneInfoResponse phoneInfo = response.phoneInfo();
            return new WechatPhoneInfo(phoneInfo.phoneNumber(), phoneInfo.purePhoneNumber(), phoneInfo.countryCode());
        } catch (RestClientException ex) {
            logWechatRequestFailure("getPhoneNumber", ex);
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private String fetchAccessToken() {
        try {
            String body = restClient.post()
                    .uri(STABLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(writeWechatRequestBody(
                            "stableToken",
                            new AccessTokenRequest("client_credential", properties.appId(), properties.appSecret(), false),
                            ErrorCode.WECHAT_PHONE_FAILED
                    ))
                    .retrieve()
                    .body(String.class);
            AccessTokenResponse response = readWechatResponse(
                    "stableToken",
                    body,
                    AccessTokenResponse.class,
                    ErrorCode.WECHAT_PHONE_FAILED
            );

            if (response == null || response.hasError() || !StringUtils.hasText(response.accessToken())) {
                logWechatError("stableToken", response == null ? null : response.errcode(), response == null ? null : response.errmsg());
                throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
            }
            return response.accessToken();
        } catch (RestClientException ex) {
            logWechatRequestFailure("stableToken", ex);
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private String writeWechatRequestBody(String operation, Object request, ErrorCode errorCode) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            log.warn("WeChat {} request serialization failed: exception={}", operation, ex.getClass().getSimpleName());
            throw new BusinessException(errorCode);
        }
    }

    private <T> T readWechatResponse(String operation, String body, Class<T> responseType, ErrorCode errorCode) {
        if (!StringUtils.hasText(body)) {
            logWechatError(operation, null, "empty response");
            throw new BusinessException(errorCode);
        }
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException ex) {
            log.warn("WeChat {} response parse failed: exception={}", operation, ex.getClass().getSimpleName());
            throw new BusinessException(errorCode);
        }
    }

    private void validateCredentials(ErrorCode errorCode) {
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
            log.warn("WeChat mini program credentials missing for {}", errorCode.name());
            throw new BusinessException(errorCode);
        }
    }

    private void logWechatError(String operation, Integer errcode, String errmsg) {
        log.warn("WeChat {} failed: errcode={}, errmsg={}", operation, errcode, errmsg);
    }

    private void logWechatRequestFailure(String operation, RestClientException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            logWechatResponseBody(operation, responseException.getStatusCode().toString(), responseException.getResponseBodyAsString());
            return;
        }
        log.warn("WeChat {} request failed: exception={}", operation, ex.getClass().getSimpleName());
    }

    private void logWechatResponseBody(String operation, String status, String body) {
        if (!StringUtils.hasText(body)) {
            log.warn("WeChat {} request failed: status={}, empty response body", operation, status);
            return;
        }
        try {
            WechatErrorResponse response = objectMapper.readValue(body, WechatErrorResponse.class);
            logWechatError(operation, response.errcode(), response.errmsg());
        } catch (JsonProcessingException parseException) {
            log.warn("WeChat {} request failed: status={}, response parse exception={}", operation, status, parseException.getClass().getSimpleName());
        }
    }

    private record CodeToSessionResponse(
            String openid,
            String unionid,
            @JsonProperty("session_key") String sessionKey,
            Integer errcode,
            String errmsg
    ) {
        private boolean hasError() {
            return errcode != null && errcode != 0;
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

    private record PhoneNumberRequest(String code) {
    }

    private record PhoneNumberResponse(
            Integer errcode,
            String errmsg,
            @JsonProperty("phone_info") PhoneInfoResponse phoneInfo
    ) {
        private boolean hasError() {
            return errcode != null && errcode != 0;
        }
    }

    private record PhoneInfoResponse(
            String phoneNumber,
            String purePhoneNumber,
            String countryCode
    ) {
    }

    private record WechatErrorResponse(Integer errcode, String errmsg) {
    }
}
