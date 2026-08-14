package org.muybaby.shopserver.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentialResolver;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

@ExtendWith(OutputCaptureExtension.class)
class RestWechatMiniProgramClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void springContextCreatesRealMiniProgramClientWhenMockDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(RealWechatClientComponents.class)
                .withBean(WechatPlatformCredentialResolver.class,
                        RestWechatMiniProgramClientTest::credentials)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("shop.wechat.mini-program.mock-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RestWechatMiniProgramClient.class);
                    assertThat(context).hasSingleBean(RestWechatAccessTokenProvider.class);
                });
    }

    @Test
    void code2SessionCallsWechatApiAndReturnsSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo(containsString("https://api.weixin.qq.com/sns/jscode2session")))
                .andExpect(requestTo(containsString("appid=app-id")))
                .andExpect(requestTo(containsString("secret=app-secret")))
                .andExpect(requestTo(containsString("js_code=login-code")))
                .andExpect(requestTo(containsString("grant_type=authorization_code")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {
                          "openid": "openid-123",
                          "unionid": "unionid-123",
                          "session_key": "session-key-123"
                        }
                        """, MediaType.APPLICATION_JSON));

        WechatCodeSession session = client.code2Session("login-code");

        assertThat(session.openid()).isEqualTo("openid-123");
        assertThat(session.unionid()).isEqualTo("unionid-123");
        assertThat(session.sessionKey()).isEqualTo("session-key-123");
        server.verify();
    }

    @Test
    void code2SessionParsesWechatJsonWhenContentTypeIsTextPlain() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo(containsString("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("""
                        {
                          "openid": "openid-plain",
                          "session_key": "session-key-plain"
                        }
                        """, MediaType.TEXT_PLAIN));

        WechatCodeSession session = client.code2Session("login-code");

        assertThat(session.openid()).isEqualTo("openid-plain");
        assertThat(session.sessionKey()).isEqualTo("session-key-plain");
        server.verify();
    }

    @Test
    void code2SessionFailsWhenWechatReturnsErrorCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo(containsString("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("""
                        {"errcode": 40029, "errmsg": "invalid code"}
                        """, MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.code2Session("bad-code"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));
        server.verify();
    }

    @Test
    void code2SessionLogsWechatErrorWithoutSensitiveValues(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo(containsString("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("""
                        {"errcode": 40029, "errmsg": "invalid code"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.code2Session("bad-code"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));

        assertThat(output)
                .contains("WeChat code2Session failed")
                .contains("40029")
                .contains("invalid code")
                .doesNotContain("app-secret")
                .doesNotContain("bad-code");
        server.verify();
    }

    @Test
    void getPhoneNumberObtainsAccessTokenAndCallsWechatPhoneApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "grant_type": "client_credential",
                          "appid": "app-id",
                          "secret": "app-secret"
                        }
                        """))
                .andRespond(withSuccess("""
                        {"access_token": "access-token", "expires_in": 7200}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=access-token"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"code": "phone-code"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "errcode": 0,
                          "errmsg": "ok",
                          "phone_info": {
                            "phoneNumber": "13812345678",
                            "purePhoneNumber": "13812345678",
                            "countryCode": "86"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WechatPhoneInfo phoneInfo = client.getPhoneNumber("phone-code");

        assertThat(phoneInfo.phoneNumber()).isEqualTo("13812345678");
        assertThat(phoneInfo.purePhoneNumber()).isEqualTo("13812345678");
        assertThat(phoneInfo.countryCode()).isEqualTo("86");
        server.verify();
    }

    @Test
    void getPhoneNumberLogsStableTokenErrorBodyOnHttpClientError(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"errcode": 40013, "errmsg": "invalid appid"}
                                """));

        assertThatThrownBy(() -> client.getPhoneNumber("phone-code"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WECHAT_PHONE_FAILED));

        assertThat(output)
                .contains("WeChat stableToken failed")
                .contains("40013")
                .contains("invalid appid")
                .doesNotContain("app-secret")
                .doesNotContain("phone-code");
        server.verify();
    }

    @Test
    void getPhoneNumberLogsStableTokenStatusWhenHttpClientErrorBodyIsEmpty(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                credentials(),
                builder,
                objectMapper
        );

        server.expect(requestTo("https://api.weixin.qq.com/cgi-bin/stable_token"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getPhoneNumber("phone-code"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WECHAT_PHONE_FAILED));

        assertThat(output)
                .contains("WeChat stableToken request failed")
                .contains("status=403 FORBIDDEN")
                .contains("empty response body")
                .doesNotContain("app-secret")
                .doesNotContain("phone-code");
        server.verify();
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = RestWechatMiniProgramClient.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = {RestWechatMiniProgramClient.class, RestWechatAccessTokenProvider.class}
            )
    )
    static class RealWechatClientComponents {
    }

    private static WechatPlatformCredentialResolver credentials() {
        return () -> new WechatPlatformCredentials(
                "app-id", "app-secret", WechatPlatformCredentials.Source.DATABASE);
    }
}
