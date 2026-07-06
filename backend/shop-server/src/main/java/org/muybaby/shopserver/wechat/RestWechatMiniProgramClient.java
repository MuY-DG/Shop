package org.muybaby.shopserver.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(prefix = "shop.wechat.mini-program", name = "mock-enabled", havingValue = "false", matchIfMissing = true)
public class RestWechatMiniProgramClient implements WechatMiniProgramClient {

    private static final String CODE_TO_SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String STABLE_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/stable_token";
    private static final String GET_PHONE_NUMBER_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private final WechatMiniProgramProperties properties;
    private final RestClient restClient;

    public RestWechatMiniProgramClient(WechatMiniProgramProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public WechatCodeSession code2Session(String code) {
        validateCredentials(ErrorCode.WECHAT_LOGIN_FAILED);
        try {
            CodeToSessionResponse response = restClient.get()
                    .uri(CODE_TO_SESSION_URL + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                            properties.appId(), properties.appSecret(), code)
                    .retrieve()
                    .body(CodeToSessionResponse.class);

            if (response == null || response.hasError() || !StringUtils.hasText(response.openid())) {
                throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
            }
            return new WechatCodeSession(response.openid(), response.unionid(), response.sessionKey());
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.WECHAT_LOGIN_FAILED);
        }
    }

    @Override
    public WechatPhoneInfo getPhoneNumber(String code) {
        validateCredentials(ErrorCode.WECHAT_PHONE_FAILED);
        String accessToken = fetchAccessToken();
        try {
            PhoneNumberResponse response = restClient.post()
                    .uri(GET_PHONE_NUMBER_URL + "?access_token={accessToken}", accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PhoneNumberRequest(code))
                    .retrieve()
                    .body(PhoneNumberResponse.class);

            if (response == null || response.hasError() || response.phoneInfo() == null) {
                throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
            }
            PhoneInfoResponse phoneInfo = response.phoneInfo();
            return new WechatPhoneInfo(phoneInfo.phoneNumber(), phoneInfo.purePhoneNumber(), phoneInfo.countryCode());
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private String fetchAccessToken() {
        try {
            AccessTokenResponse response = restClient.post()
                    .uri(STABLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AccessTokenRequest("client_credential", properties.appId(), properties.appSecret()))
                    .retrieve()
                    .body(AccessTokenResponse.class);

            if (response == null || response.hasError() || !StringUtils.hasText(response.accessToken())) {
                throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
            }
            return response.accessToken();
        } catch (RestClientException ex) {
            throw new BusinessException(ErrorCode.WECHAT_PHONE_FAILED);
        }
    }

    private void validateCredentials(ErrorCode errorCode) {
        if (!StringUtils.hasText(properties.appId()) || !StringUtils.hasText(properties.appSecret())) {
            throw new BusinessException(errorCode);
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
            String secret
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
}
