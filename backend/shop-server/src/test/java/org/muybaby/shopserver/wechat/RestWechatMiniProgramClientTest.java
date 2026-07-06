package org.muybaby.shopserver.wechat;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
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
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class RestWechatMiniProgramClientTest {

    @Test
    void code2SessionCallsWechatApiAndReturnsSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                new WechatMiniProgramProperties("app-id", "app-secret", false),
                builder
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
    void code2SessionFailsWhenWechatReturnsErrorCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                new WechatMiniProgramProperties("app-id", "app-secret", false),
                builder
        );

        server.expect(requestTo(containsString("https://api.weixin.qq.com/sns/jscode2session")))
                .andRespond(withSuccess("""
                        {"errcode": 40029, "errmsg": "invalid code"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.code2Session("bad-code"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));
        server.verify();
    }

    @Test
    void getPhoneNumberObtainsAccessTokenAndCallsWechatPhoneApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestWechatMiniProgramClient client = new RestWechatMiniProgramClient(
                new WechatMiniProgramProperties("app-id", "app-secret", false),
                builder
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
}
