package org.muybaby.shopserver.wechat.servicecard.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardProperties;
import org.muybaby.shopserver.wechat.servicecard.WechatServiceCardPropertiesTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RealWechatServiceCardProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void setUsesJsonStringsAndNeverSendsAccountTemplateRecordId() throws Exception {
        Fixture fixture = fixture();
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        fixture.server().expect(once(), request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/wxa/set_user_notify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(capture(captured))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        WechatServiceCardSetResult result = fixture.provider().setUserNotify(
                new WechatServiceCardSetRequest(
                        "openid", "420000000000000001",
                        "{\"cur_status\":2,\"wxa_path_query\":\"pages/order/detail/detail?order_id=68\","
                                + "\"product_count\":1,\"product_list\":{\"info_list\":[{\"product_img\":"
                                + "\"https://admin.muybaby6.icu/wechat/service-card-placeholder.png\","
                                + "\"product_name\":\"商品\",\"product_path_query\":"
                                + "\"pages/product/detail/detail?id=1\"}]}}",
                        "{\"pay_amount\":100,\"pay_time\":1786400000}"
                )
        );

        assertThat(result.outcome()).isEqualTo(WechatServiceCardSetResult.Outcome.APPLIED);
        JsonNode body = captured.get();
        assertThat(body.path("content_json").isTextual()).isTrue();
        assertThat(body.path("check_json").isTextual()).isTrue();
        assertThat(body.path("notify_type").intValue()).isEqualTo(2001);
        assertThat(body.has("priTmplId")).isFalse();
        assertThat(body.has("account_template_record_id")).isFalse();
        assertThat(objectMapper.readTree(body.path("content_json").asText()).path("cur_status").intValue())
                .isEqualTo(2);
        fixture.server().verify();
    }

    @Test
    void emptyQueriedContentIsValidNotYetAppliedState() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/wxa/get_user_notify"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"errcode":0,"notify_info":{"notify_type":2001,"content_json":"",
                        "code_state":0,"code_expire_time":1789000000}}
                        """, MediaType.APPLICATION_JSON));

        WechatServiceCardQueryResult result = fixture.provider().getUserNotify(
                new WechatServiceCardQueryRequest("openid", "420000000000000001")
        );

        assertThat(result.outcome()).isEqualTo(WechatServiceCardQueryResult.Outcome.FOUND);
        assertThat(result.remoteStatus()).isNull();
        assertThat(result.codeState()).isZero();
        assertThat(result.expiresAt()).isNotNull();
        fixture.server().verify();
    }

    @Test
    void unknownSetErrorIsAmbiguousAndMustReconcile() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), request -> { })
                .andRespond(withSuccess("{\"errcode\":99999,\"errmsg\":\"provider raw text\"}",
                        MediaType.APPLICATION_JSON));

        WechatServiceCardSetResult result = fixture.provider().setUserNotify(
                new WechatServiceCardSetRequest(
                        "openid", "tx",
                        "{\"cur_status\":4,\"wxa_path_query\":\"pages/order/detail/detail?order_id=1\"}",
                        null
                )
        );

        assertThat(result.outcome()).isEqualTo(WechatServiceCardSetResult.Outcome.UNKNOWN);
        assertThat(result.errorMessage()).doesNotContain("provider raw text");
        fixture.server().verify();
    }

    @Test
    void paymentCheckAcceptsExactUint32MaximumAndRejectsOutsideRangeBeforeHttp() {
        Fixture maximum = fixture();
        maximum.server().expect(once(), request -> { })
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));
        WechatServiceCardSetResult accepted = maximum.provider().setUserNotify(
                new WechatServiceCardSetRequest(
                        "openid", "tx",
                        "{\"cur_status\":4,\"wxa_path_query\":\"pages/order/detail/detail?order_id=1\"}",
                        "{\"pay_amount\":4294967295,\"pay_time\":4294967295,\"pay_channel\":1001}"
                )
        );
        assertThat(accepted.outcome()).isEqualTo(WechatServiceCardSetResult.Outcome.APPLIED);
        maximum.server().verify();

        Fixture zero = fixture();
        WechatServiceCardSetResult zeroTime = zero.provider().setUserNotify(
                new WechatServiceCardSetRequest(
                        "openid", "tx",
                        "{\"cur_status\":4,\"wxa_path_query\":\"pages/order/detail/detail?order_id=1\"}",
                        "{\"pay_amount\":0,\"pay_time\":0}"
                )
        );
        assertThat(zeroTime.outcome()).isEqualTo(WechatServiceCardSetResult.Outcome.REJECTED);
        zero.server().verify();

        Fixture overflow = fixture();
        WechatServiceCardSetResult overflowTime = overflow.provider().setUserNotify(
                new WechatServiceCardSetRequest(
                        "openid", "tx",
                        "{\"cur_status\":4,\"wxa_path_query\":\"pages/order/detail/detail?order_id=1\"}",
                        "{\"pay_amount\":0,\"pay_time\":4294967296}"
                )
        );
        assertThat(overflowTime.outcome()).isEqualTo(WechatServiceCardSetResult.Outcome.REJECTED);
        overflow.server().verify();
    }

    private Fixture fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WechatServiceCardProperties properties = WechatServiceCardPropertiesTest.properties(
                Duration.ofMinutes(1), Duration.ofHours(6)
        );
        RealWechatServiceCardProvider provider = new RealWechatServiceCardProvider(
                builder.build(), objectMapper, () -> "access-token", properties
        );
        return new Fixture(provider, server);
    }

    private RequestMatcher capture(AtomicReference<JsonNode> captured) {
        return request -> {
            MockClientHttpRequest mock = (MockClientHttpRequest) request;
            captured.set(objectMapper.readTree(new String(
                    mock.getBodyAsBytes(), StandardCharsets.UTF_8
            )));
        };
    }

    private record Fixture(
            RealWechatServiceCardProvider provider,
            MockRestServiceServer server
    ) {
    }
}
