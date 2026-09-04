package org.muybaby.shopserver.wechat.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatPlatformConfigVerifierTest {

    @Test
    void acceptsCredentialsWhenWechatReturnsAStableAccessToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "grant_type":"client_credential",
                          "appid":"wx-app",
                          "secret":"app-secret",
                          "force_refresh":false
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"access_token\":\"stable-token\",\"expires_in\":7200}",
                        MediaType.APPLICATION_JSON));

        verifier(builder).requireUsable(credentials());

        server.verify();
    }

    @Test
    void rejectsInvalidAppIdOrSecretUsingTheProviderErrorResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withSuccess(
                        "{\"errcode\":40125,\"errmsg\":\"invalid appsecret\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier(builder).requireUsable(credentials()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_PLATFORM_CREDENTIAL_INVALID));
        server.verify();
    }

    @Test
    void reportsWechatServerFailureAsTemporarilyUnavailable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> verifier(builder).requireUsable(credentials()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ErrorCode.WECHAT_PLATFORM_VERIFICATION_UNAVAILABLE));
        server.verify();
    }

    private WechatPlatformConfigVerifier verifier(RestClient.Builder builder) {
        return new WechatPlatformConfigVerifier(builder, new ObjectMapper());
    }

    private WechatPlatformCredentials credentials() {
        return new WechatPlatformCredentials(
                "wx-app", "app-secret", WechatPlatformCredentials.Source.DATABASE);
    }
}
