package org.muybaby.shopserver.wechat.platform;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

@Component
public class WechatPlatformConfigVerifier {

    private static final Logger log = LoggerFactory.getLogger(WechatPlatformConfigVerifier.class);
    private static final String STABLE_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/stable_token";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public WechatPlatformConfigVerifier(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientHttpRequestFactorySettings baseSettings
    ) {
        ClientHttpRequestFactorySettings settings = baseSettings.withTimeouts(
                Duration.ofSeconds(3), Duration.ofSeconds(8));
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactoryBuilder.build(settings))
                .build();
        this.objectMapper = objectMapper;
    }

    WechatPlatformConfigVerifier(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public void requireUsable(WechatPlatformCredentials credentials) {
        if (credentials == null
                || !StringUtils.hasText(credentials.appId())
                || !StringUtils.hasText(credentials.appSecret())) {
            throw new BusinessException(ErrorCode.WECHAT_PLATFORM_CREDENTIAL_INVALID);
        }
        try {
            String body = restClient.post()
                    .uri(STABLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(new AccessTokenRequest(
                            "client_credential",
                            credentials.appId(),
                            credentials.appSecret(),
                            false
                    )))
                    .retrieve()
                    .body(String.class);
            AccessTokenResponse response = readResponse(body);
            if (response == null || !StringUtils.hasText(response.accessToken())) {
                log.warn(
                        "WeChat platform config verification rejected: errcode={}",
                        response == null ? null : response.errcode());
                throw new BusinessException(response != null && response.hasError()
                        ? ErrorCode.WECHAT_PLATFORM_CREDENTIAL_INVALID
                        : ErrorCode.WECHAT_PLATFORM_VERIFICATION_UNAVAILABLE);
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.WECHAT_PLATFORM_CREDENTIAL_INVALID);
        } catch (RestClientResponseException ex) {
            log.warn(
                    "WeChat platform config verification failed: status={}",
                    ex.getStatusCode());
            throw new BusinessException(ex.getStatusCode().value() == 429
                    || ex.getStatusCode().is5xxServerError()
                    ? ErrorCode.WECHAT_PLATFORM_VERIFICATION_UNAVAILABLE
                    : ErrorCode.WECHAT_PLATFORM_CREDENTIAL_INVALID);
        } catch (RestClientException ex) {
            log.warn(
                    "WeChat platform config verification unavailable: exception={}",
                    ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.WECHAT_PLATFORM_VERIFICATION_UNAVAILABLE);
        }
    }

    private AccessTokenResponse readResponse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return objectMapper.readValue(body, AccessTokenResponse.class);
        } catch (JsonProcessingException ex) {
            log.warn("WeChat platform config verification returned an unreadable response");
            throw new BusinessException(ErrorCode.WECHAT_PLATFORM_VERIFICATION_UNAVAILABLE);
        }
    }

    private record AccessTokenRequest(
            @JsonProperty("grant_type") String grantType,
            String appid,
            String secret,
            @JsonProperty("force_refresh") boolean forceRefresh
    ) {
        @Override
        public String toString() {
            return "AccessTokenRequest[grantType=" + grantType
                    + ", appidConfigured=" + StringUtils.hasText(appid)
                    + ", secret=<redacted>, forceRefresh=" + forceRefresh + "]";
        }
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

        @Override
        public String toString() {
            return "AccessTokenResponse[accessToken=<redacted>, expiresIn=" + expiresIn
                    + ", errcode=" + errcode + ", errmsg=" + errmsg + "]";
        }
    }
}
