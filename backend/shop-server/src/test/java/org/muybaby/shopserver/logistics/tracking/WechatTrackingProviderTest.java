package org.muybaby.shopserver.logistics.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.muybaby.shopserver.logistics.service.WechatShippingErrorSanitizer;
import org.muybaby.shopserver.logistics.tracking.provider.RealWechatTrackingProvider;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathRequest;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressHttpProperties;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class WechatTrackingProviderTest {

    private static final String ACCESS_TOKEN = "tracking-access-token-never-log";
    private static final String WAYBILL_TOKEN = "waybill-token-never-log";
    private static final String OPENID = "openid-tracking-never-log";
    private static final String PROVIDER_ORDER_ID = "SHOP-WB-91-1";
    private static final String WAYBILL_ID = "WXTESTEXPRESS0000014";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void queryTraceUsesTokenOnlyAndReturnsDocumentedStatus() throws Exception {
        Fixture fixture = fixture(() -> ACCESS_TOKEN);
        AtomicReference<JsonNode> body = new AtomicReference<>();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/query_trace"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(captureJson(body))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok","waybill_info":{"status":3}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().query(new WechatTrackingQueryRequest(
                701L, WaybillRegistrationKind.TRACE, WAYBILL_TOKEN
        ));

        assertThat(body.get()).isEqualTo(objectMapper.readTree("""
                {"waybill_token":"waybill-token-never-log"}
                """));
        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(result.logisticsStatus()).isEqualTo(3);
        fixture.server().verify();
    }

    @Test
    void queryFollowUsesFollowEndpoint() {
        Fixture fixture = fixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/query_follow_trace"))
                .andRespond(withSuccess("""
                        {"waybill_info":{"status":4}}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().query(new WechatTrackingQueryRequest(
                701L, WaybillRegistrationKind.FOLLOW, WAYBILL_TOKEN
        ));

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(result.logisticsStatus()).isEqualTo(4);
        fixture.server().verify();
    }

    @Test
    void getPathUsesElectronicWaybillIdentityAndParsesItems() throws Exception {
        Fixture fixture = fixture(() -> ACCESS_TOKEN);
        AtomicReference<JsonNode> body = new AtomicReference<>();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/path/get"))
                .andExpect(captureJson(body))
                .andRespond(withSuccess("""
                        {
                          "errcode":0,
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "path_item_list":[
                            {"action_time":1786000000,"action_type":1001,"action_msg":"快件已揽收"},
                            {"action_time":1786003600,"action_type":2001,"action_msg":"运输中"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().getPath(pathRequest());

        assertThat(body.get()).isEqualTo(objectMapper.readTree("""
                {
                  "order_id":"SHOP-WB-91-1",
                  "openid":"openid-tracking-never-log",
                  "delivery_id":"TEST",
                  "waybill_id":"WXTESTEXPRESS0000014"
                }
                """));
        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(result.pathItems()).hasSize(2);
        assertThat(result.pathItems().getFirst().actionMessage()).isEqualTo("快件已揽收");
        fixture.server().verify();
    }

    @Test
    void queryFailureDoesNotPreventIndependentPathRequestAndLogsNoSecrets(CapturedOutput output) {
        Fixture fixture = fixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/query_trace"))
                .andRespond(request -> {
                    throw new ResourceAccessException("transport " + WAYBILL_TOKEN + " " + OPENID);
                });
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/path/get"))
                .andRespond(withSuccess("""
                        {"errcode":0,"path_item_list":[]}
                        """, MediaType.APPLICATION_JSON));

        var query = fixture.provider().query(new WechatTrackingQueryRequest(
                701L, WaybillRegistrationKind.TRACE, WAYBILL_TOKEN
        ));
        var path = fixture.provider().getPath(pathRequest());

        assertThat(query.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(path.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(path.pathItems()).isEmpty();
        assertThat(output.getAll())
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(WAYBILL_TOKEN)
                .doesNotContain(OPENID)
                .doesNotContain(PROVIDER_ORDER_ID)
                .doesNotContain(WAYBILL_ID);
        fixture.server().verify();
    }

    @Test
    void getPathRejectsMismatchedIdentityAndOversizedLists() {
        Fixture mismatch = fixture(() -> ACCESS_TOKEN);
        mismatch.server().expect(once(), endpoint("/cgi-bin/express/business/path/get"))
                .andRespond(withSuccess("""
                        {"delivery_id":"SF","waybill_id":"WXTESTEXPRESS0000014","path_item_list":[]}
                        """, MediaType.APPLICATION_JSON));

        var mismatchResult = mismatch.provider().getPath(pathRequest());

        assertThat(mismatchResult.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(mismatchResult.errorCode()).isEqualTo("RESPONSE_IDENTITY_MISMATCH");
        mismatch.server().verify();

        Fixture oversized = fixture(() -> ACCESS_TOKEN, 1);
        oversized.server().expect(once(), endpoint("/cgi-bin/express/business/path/get"))
                .andRespond(withSuccess("""
                        {"path_item_list":[
                          {"action_time":1786000000,"action_type":1,"action_msg":"一"},
                          {"action_time":1786000001,"action_type":2,"action_msg":"二"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var oversizedResult = oversized.provider().getPath(pathRequest());

        assertThat(oversizedResult.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(oversizedResult.errorCode()).isEqualTo("PATH_ITEMS_EXCEEDED");
        oversized.server().verify();
    }

    private WechatTrackingPathRequest pathRequest() {
        return new WechatTrackingPathRequest(
                701L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID
        );
    }

    private Fixture fixture(WechatAccessTokenProvider tokenProvider) {
        return fixture(tokenProvider, 200);
    }

    private Fixture fixture(WechatAccessTokenProvider tokenProvider, int maxPathItems) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var provider = new RealWechatTrackingProvider(
                builder.build(),
                new WechatExpressHttpProperties(
                        Duration.ofSeconds(3), Duration.ofSeconds(15), DataSize.ofMegabytes(5)
                ),
                new WechatTrackingProperties(
                        Duration.ofMinutes(5), Duration.ofMinutes(5), maxPathItems
                ),
                objectMapper,
                tokenProvider,
                new WechatShippingErrorSanitizer()
        );
        return new Fixture(server, provider);
    }

    private RequestMatcher endpoint(String expectedPath) {
        return request -> {
            URI uri = request.getURI();
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("api.weixin.qq.com");
            assertThat(uri.getPath()).isEqualTo(expectedPath);
            assertThat(uri.getQuery()).startsWith("access_token=");
        };
    }

    private RequestMatcher captureJson(AtomicReference<JsonNode> target) {
        return request -> {
            var mockRequest = (MockClientHttpRequest) request;
            target.set(objectMapper.readTree(mockRequest.getBodyAsBytes()));
        };
    }

    private record Fixture(
            MockRestServiceServer server,
            RealWechatTrackingProvider provider
    ) {
    }
}
