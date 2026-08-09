package org.muybaby.shopserver.logistics.waybill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.muybaby.shopserver.logistics.service.WechatShippingErrorSanitizer;
import org.muybaby.shopserver.logistics.waybill.provider.MockWechatElectronicWaybillProvider;
import org.muybaby.shopserver.logistics.waybill.provider.MockWechatWaybillRegistrationProvider;
import org.muybaby.shopserver.logistics.waybill.provider.RealWechatElectronicWaybillProvider;
import org.muybaby.shopserver.logistics.waybill.provider.RealWechatWaybillRegistrationProvider;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillAddRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillCancelRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillEnvironment;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillGetRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatElectronicWaybillTestUpdateRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressCargoItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressContact;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressHttpProperties;
import org.muybaby.shopserver.logistics.waybill.provider.WechatExpressShopItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillGoodsItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationRequest;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class WechatExpressProviderTest {

    private static final String ACCESS_TOKEN = "express-access-token-never-log";
    private static final String OPENID = "openid-express-never-log";
    private static final String RECEIVER_PHONE = "13800138000";
    private static final String SENDER_PHONE = "13900139000";
    private static final String TRANSACTION_ID = "4200000000000000999";
    private static final String WAYBILL_ID = "WXTESTEXPRESS0000014";
    private static final String PROVIDER_ORDER_ID = "SHOP-WB-91-1";
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void addUsesExactOfficialPayloadAndAcceptsDocumentedSuccessWithoutErrcode(CapturedOutput output) throws Exception {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/add"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(captureJson(captured))
                .andRespond(withSuccess("""
                        {
                          "order_id":"SHOP-WB-91-1",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "waybill_data":[{"key":"foo","value":"bar"}]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().add(addRequest());

        assertThat(captured.get()).isEqualTo(objectMapper.readTree("""
                {
                  "order_id":"SHOP-WB-91-1",
                  "openid":"openid-express-never-log",
                  "delivery_id":"TEST",
                  "biz_id":"test_biz_id",
                  "custom_remark":"易碎",
                  "add_source":0,
                  "sender":{
                    "name":"沐宝仓库",
                    "mobile":"13900139000",
                    "company":"沐宝",
                    "country":"中国",
                    "province":"广东省",
                    "city":"深圳市",
                    "area":"南山区",
                    "address":"科技园 1 号"
                  },
                  "receiver":{
                    "name":"测试买家",
                    "mobile":"13800138000",
                    "country":"中国",
                    "province":"广东省",
                    "city":"广州市",
                    "area":"天河区",
                    "address":"体育西路 2 号"
                  },
                  "cargo":{
                    "count":1,
                    "weight":1.2,
                    "space_x":20.0,
                    "space_y":15.0,
                    "space_z":10.0,
                    "detail_list":[{"name":"菌汤锅底","count":2}]
                  },
                  "shop":{
                    "wxa_path":"pages/order/detail/detail?order_id=91",
                    "detail_list":[{
                      "goods_name":"菌汤锅底",
                      "goods_img_url":"https://img.example.test/product.webp",
                      "goods_desc":"默认规格 x2"
                    }]
                  },
                  "insured":{"use_insured":0,"insured_value":0},
                  "service":{"service_type":1,"service_name":"test_service_name"},
                  "expect_time":0
                }
                """));
        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(result.providerOrderId()).isEqualTo(PROVIDER_ORDER_ID);
        assertThat(result.waybillId()).isEqualTo(WAYBILL_ID);
        assertSafeLogs(output, "operation=ADD", "recordId=501", "SUCCESS");
        fixture.server().verify();
    }

    @Test
    void getUsesExactIdentityAndReturnsPrintHtml() throws Exception {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        AtomicReference<JsonNode> captured = new AtomicReference<>();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/get"))
                .andExpect(captureJson(captured))
                .andRespond(withSuccess("""
                        {
                          "order_id":"SHOP-WB-91-1",
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "order_status":0,
                          "print_html":"PGh0bWw+PC9odG1sPg=="
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 1
        ));

        assertThat(captured.get()).isEqualTo(objectMapper.readTree("""
                {
                  "order_id":"SHOP-WB-91-1",
                  "openid":"openid-express-never-log",
                  "delivery_id":"TEST",
                  "waybill_id":"WXTESTEXPRESS0000014",
                  "print_type":1
                }
                """));
        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(result.orderStatus()).isZero();
        assertThat(result.printHtmlBase64()).isEqualTo("PGh0bWw+PC9odG1sPg==");
        fixture.server().verify();
    }

    @Test
    void getIdentityMismatchIsUnknown() {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/get"))
                .andRespond(withSuccess("""
                        {
                          "errcode":0,
                          "order_id":"another-order",
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "order_status":0
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 0
        ));

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("RESPONSE_IDENTITY_MISMATCH");
        fixture.server().verify();
    }

    @Test
    void cancelAndSandboxUpdateRequireExplicitZero() {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/cancel"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"errmsg\":\"ok\",\"delivery_resultcode\":0,\"delivery_resultmsg\":\"\"}",
                        MediaType.APPLICATION_JSON
                ));
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/test_update_order"))
                .andRespond(withSuccess("{\"errcode\":930561,\"errmsg\":\"bad args\"}", MediaType.APPLICATION_JSON));

        var cancel = fixture.provider().cancel(new WechatElectronicWaybillCancelRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID
        ));
        var testUpdate = fixture.provider().testUpdate(new WechatElectronicWaybillTestUpdateRequest(
                501L, "test_biz_id", PROVIDER_ORDER_ID, "TEST", WAYBILL_ID,
                1_786_000_000L, 100001, "揽件成功"
        ));

        assertThat(cancel.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(testUpdate.outcome()).isEqualTo(WechatProviderOutcome.REJECTED);
        assertThat(testUpdate.errorCode()).isEqualTo("WECHAT_930561");
        fixture.server().verify();
    }

    @Test
    void cancelRejectsNonzeroCarrierResultEvenWhenWechatErrcodeIsZero() {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/cancel"))
                .andRespond(withSuccess("""
                        {
                          "errcode":0,
                          "errmsg":"ok",
                          "delivery_resultcode":10002,
                          "delivery_resultmsg":"sensitive carrier message"
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().cancel(new WechatElectronicWaybillCancelRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID
        ));

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.REJECTED);
        assertThat(result.errorCode()).isEqualTo("WECHAT_DELIVERY_10002");
        assertThat(result.errorMessage()).doesNotContain("sensitive carrier message");
        fixture.server().verify();
    }

    @Test
    void transportAndAccessTokenFailuresNeverEchoSecrets(CapturedOutput output) {
        ElectronicFixture transport = electronicFixture(() -> ACCESS_TOKEN);
        transport.server().expect(once(), endpoint("/cgi-bin/express/business/order/add"))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "transport " + ACCESS_TOKEN + " " + OPENID + " " + RECEIVER_PHONE
                    );
                });

        var unknown = transport.provider().add(addRequest());
        assertThat(unknown.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        transport.server().verify();

        ElectronicFixture unavailable = electronicFixture(() -> {
            throw new IllegalStateException("token " + ACCESS_TOKEN + " " + OPENID);
        });
        var noToken = unavailable.provider().add(addRequest());
        assertThat(noToken.outcome()).isEqualTo(WechatProviderOutcome.UNAVAILABLE);
        unavailable.server().verify();

        assertSafeLogs(output, "UNKNOWN", "UNAVAILABLE");
    }

    @Test
    void contentLengthOverLimitIsRejectedBeforeReadingOrDecoding(CapturedOutput output) {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        AtomicBoolean bodyRead = new AtomicBoolean();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/add"))
                .andRespond(request -> {
                    MockClientHttpResponse response = new MockClientHttpResponse(
                            unreadableBody(bodyRead), HttpStatus.OK
                    );
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    response.getHeaders().setContentLength(MAX_RESPONSE_BYTES + 1L);
                    return response;
                });

        var result = fixture.provider().add(addRequest());

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(bodyRead).isFalse();
        assertSafeLogs(output, "UNKNOWN", "RESPONSE_TOO_LARGE");
        fixture.server().verify();
    }

    @Test
    void responseWithoutContentLengthReadsAtMostLimitPlusOneByte() {
        RegistrationFixture fixture = registrationFixture(() -> ACCESS_TOKEN);
        CountingInputStream body = new CountingInputStream(MAX_RESPONSE_BYTES + 10_000);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andRespond(request -> {
                    MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                });

        var result = fixture.provider().trace(registrationRequest());

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(body.bytesRead()).isEqualTo(MAX_RESPONSE_BYTES + 1);
        fixture.server().verify();
    }

    @Test
    void non2xxResponseIsClassifiedWithoutReadingErrorBody(CapturedOutput output) {
        RegistrationFixture fixture = registrationFixture(() -> ACCESS_TOKEN);
        AtomicBoolean bodyRead = new AtomicBoolean();
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andRespond(request -> new MockClientHttpResponse(
                        unreadableBody(bodyRead), HttpStatus.BAD_GATEWAY
                ));

        var result = fixture.provider().trace(registrationRequest());

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertThat(bodyRead).isFalse();
        assertSafeLogs(output, "UNKNOWN", "REQUEST_AMBIGUOUS");
        fixture.server().verify();
    }

    @Test
    void traceAndFollowUseExactOfficialPayloadAndReturnToken() throws Exception {
        RegistrationFixture traceFixture = registrationFixture(() -> ACCESS_TOKEN);
        AtomicReference<JsonNode> traceBody = new AtomicReference<>();
        traceFixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andExpect(captureJson(traceBody))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\",\"waybill_token\":\"trace-token\"}", MediaType.APPLICATION_JSON));

        var trace = traceFixture.provider().trace(registrationRequest());

        assertThat(traceBody.get()).isEqualTo(objectMapper.readTree("""
                {
                  "openid":"openid-express-never-log",
                  "sender_phone":"13900139000",
                  "receiver_phone":"13800138000",
                  "waybill_id":"WXTESTEXPRESS0000014",
                  "goods_info":{"detail_list":[{
                    "goods_name":"菌汤锅底",
                    "goods_img_url":"https://img.example.test/product.webp"
                  }]},
                  "trans_id":"4200000000000000999",
                  "order_detail_path":"pages/order/detail/detail?order_id=91",
                  "delivery_id":"TEST"
                }
                """));
        assertThat(trace.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(trace.waybillToken()).isEqualTo("trace-token");
        traceFixture.server().verify();

        RegistrationFixture followFixture = registrationFixture(() -> ACCESS_TOKEN);
        followFixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/follow_waybill"))
                .andRespond(withSuccess("{\"errcode\":0,\"waybill_token\":\"follow-token\"}", MediaType.APPLICATION_JSON));
        var follow = followFixture.provider().follow(registrationRequest());

        assertThat(follow.outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(follow.waybillToken()).isEqualTo("follow-token");
        followFixture.server().verify();
    }

    @Test
    void registrationRequiresNonemptyTokenAndSanitizesWechatError(CapturedOutput output) {
        RegistrationFixture fixture = registrationFixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andRespond(withSuccess("{\"errcode\":0,\"errmsg\":\"ok\"}", MediaType.APPLICATION_JSON));

        var missingToken = fixture.provider().trace(registrationRequest());

        assertThat(missingToken.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(missingToken.errorCode()).isEqualTo("AMBIGUOUS_RESPONSE");
        assertSafeLogs(output, "operation=TRACE", "shipmentId=701", "UNKNOWN");
        fixture.server().verify();
    }

    @Test
    void systemBusyIsUnknownInsteadOfRejected() {
        ElectronicFixture electronic = electronicFixture(() -> ACCESS_TOKEN);
        electronic.server().expect(once(), endpoint("/cgi-bin/express/business/order/add"))
                .andRespond(withSuccess("{\"errcode\":-1,\"errmsg\":\"system busy\"}", MediaType.APPLICATION_JSON));

        var add = electronic.provider().add(addRequest());

        assertThat(add.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(add.errorCode()).isEqualTo("WECHAT_SYSTEM_BUSY");
        electronic.server().verify();

        RegistrationFixture registration = registrationFixture(() -> ACCESS_TOKEN);
        registration.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andRespond(withSuccess("{\"errcode\":-1,\"errmsg\":\"system busy\"}", MediaType.APPLICATION_JSON));

        var trace = registration.provider().trace(registrationRequest());

        assertThat(trace.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(trace.errorCode()).isEqualTo("WECHAT_SYSTEM_BUSY");
        registration.server().verify();
    }

    @Test
    void requestedPrintRequiresStrictBoundedBase64() {
        ElectronicFixture fixture = electronicFixture(() -> ACCESS_TOKEN);
        String oversizedDecodedPrint = Base64.getEncoder().encodeToString(new byte[2_000_001]);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/get"))
                .andRespond(withSuccess("""
                        {
                          "order_id":"SHOP-WB-91-1",
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "order_status":0
                        }
                        """, MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/get"))
                .andRespond(withSuccess("""
                        {
                          "order_id":"SHOP-WB-91-1",
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "order_status":0,
                          "print_html":"%s"
                        }
                        """.formatted(oversizedDecodedPrint), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), endpoint("/cgi-bin/express/business/order/get"))
                .andRespond(withSuccess("""
                        {
                          "order_id":"SHOP-WB-91-1",
                          "delivery_id":"TEST",
                          "waybill_id":"WXTESTEXPRESS0000014",
                          "order_status":0,
                          "print_html":"not@base64"
                        }
                        """, MediaType.APPLICATION_JSON));

        var missing = fixture.provider().get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 1
        ));
        var oversized = fixture.provider().get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 1
        ));
        var malformed = fixture.provider().get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 1
        ));

        assertThat(missing.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(missing.errorCode()).isEqualTo("PRINT_DATA_INVALID");
        assertThat(malformed.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(malformed.errorCode()).isEqualTo("PRINT_DATA_INVALID");
        assertThat(oversized.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(oversized.errorCode()).isEqualTo("PRINT_DATA_INVALID");
        fixture.server().verify();
    }

    @Test
    void registrationRejectsTokenLongerThanPersistenceLimit() {
        RegistrationFixture fixture = registrationFixture(() -> ACCESS_TOKEN);
        fixture.server().expect(once(), endpoint("/cgi-bin/express/delivery/open_msg/trace_waybill"))
                .andRespond(withSuccess(
                        "{\"errcode\":0,\"waybill_token\":\"" + "t".repeat(1025) + "\"}",
                        MediaType.APPLICATION_JSON
                ));

        var result = fixture.provider().trace(registrationRequest());

        assertThat(result.outcome()).isEqualTo(WechatProviderOutcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("WAYBILL_TOKEN_INVALID");
        fixture.server().verify();
    }

    @Test
    void mockProvidersRejectRequestsThatRealProvidersReject() {
        var electronic = new MockWechatElectronicWaybillProvider();
        var registration = new MockWechatWaybillRegistrationProvider();

        assertThat(electronic.add(addRequest()).outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(registration.trace(registrationRequest()).outcome()).isEqualTo(WechatProviderOutcome.SUCCESS);
        assertThat(electronic.add(addRequest("SF")).outcome())
                .isEqualTo(WechatProviderOutcome.UNAVAILABLE);
        assertThat(electronic.get(new WechatElectronicWaybillGetRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", WAYBILL_ID, 2
        )).outcome()).isEqualTo(WechatProviderOutcome.UNAVAILABLE);
        assertThat(electronic.cancel(new WechatElectronicWaybillCancelRequest(
                501L, PROVIDER_ORDER_ID, OPENID, "TEST", ""
        )).outcome()).isEqualTo(WechatProviderOutcome.UNAVAILABLE);
        assertThat(electronic.testUpdate(new WechatElectronicWaybillTestUpdateRequest(
                501L, "test_biz_id", PROVIDER_ORDER_ID, "TEST", WAYBILL_ID,
                1_786_000_000L, 999999, "invalid"
        )).outcome()).isEqualTo(WechatProviderOutcome.UNAVAILABLE);
        assertThat(registration.trace(registrationRequest("")).outcome())
                .isEqualTo(WechatProviderOutcome.UNAVAILABLE);
    }

    private WechatElectronicWaybillAddRequest addRequest() {
        return addRequest("TEST");
    }

    private WechatElectronicWaybillAddRequest addRequest(String deliveryId) {
        return new WechatElectronicWaybillAddRequest(
                501L,
                WechatElectronicWaybillEnvironment.SANDBOX,
                PROVIDER_ORDER_ID,
                OPENID,
                deliveryId,
                "test_biz_id",
                "易碎",
                contact("沐宝仓库", SENDER_PHONE, "沐宝", "广东省", "深圳市", "南山区", "科技园 1 号"),
                contact("测试买家", RECEIVER_PHONE, null, "广东省", "广州市", "天河区", "体育西路 2 号"),
                1,
                new BigDecimal("1.2"),
                new BigDecimal("20.0"),
                new BigDecimal("15.0"),
                new BigDecimal("10.0"),
                List.of(new WechatExpressCargoItem("菌汤锅底", 2)),
                "pages/order/detail/detail?order_id=91",
                List.of(new WechatExpressShopItem(
                        "菌汤锅底", "https://img.example.test/product.webp", "默认规格 x2"
                )),
                1,
                "test_service_name",
                0L
        );
    }

    private WechatWaybillRegistrationRequest registrationRequest() {
        return registrationRequest(OPENID);
    }

    private WechatWaybillRegistrationRequest registrationRequest(String openid) {
        return new WechatWaybillRegistrationRequest(
                701L,
                openid,
                SENDER_PHONE,
                RECEIVER_PHONE,
                WAYBILL_ID,
                "TEST",
                TRANSACTION_ID,
                "pages/order/detail/detail?order_id=91",
                List.of(new WechatWaybillGoodsItem(
                        "菌汤锅底", "https://img.example.test/product.webp"
                ))
        );
    }

    private WechatExpressContact contact(
            String name,
            String mobile,
            String company,
            String province,
            String city,
            String area,
            String address
    ) {
        return new WechatExpressContact(name, mobile, company, "中国", province, city, area, address);
    }

    private ElectronicFixture electronicFixture(WechatAccessTokenProvider tokenProvider) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var provider = new RealWechatElectronicWaybillProvider(
                builder.build(), httpProperties(), objectMapper, tokenProvider,
                new WechatShippingErrorSanitizer()
        );
        return new ElectronicFixture(server, provider);
    }

    private RegistrationFixture registrationFixture(WechatAccessTokenProvider tokenProvider) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var provider = new RealWechatWaybillRegistrationProvider(
                builder.build(), httpProperties(), objectMapper, tokenProvider,
                new WechatShippingErrorSanitizer()
        );
        return new RegistrationFixture(server, provider);
    }

    private WechatExpressHttpProperties httpProperties() {
        return new WechatExpressHttpProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(15), DataSize.ofMegabytes(5)
        );
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

    private InputStream unreadableBody(AtomicBoolean bodyRead) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                bodyRead.set(true);
                throw new IOException("response body must not be read");
            }
        };
    }

    private void assertSafeLogs(CapturedOutput output, String... expectedFragments) {
        String logs = output.getAll();
        assertThat(logs).contains(expectedFragments);
        assertThat(logs)
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(OPENID)
                .doesNotContain(RECEIVER_PHONE)
                .doesNotContain(SENDER_PHONE)
                .doesNotContain(TRANSACTION_ID)
                .doesNotContain(WAYBILL_ID)
                .doesNotContain(PROVIDER_ORDER_ID);
    }

    private record ElectronicFixture(
            MockRestServiceServer server,
            RealWechatElectronicWaybillProvider provider
    ) {
    }

    private record RegistrationFixture(
            MockRestServiceServer server,
            RealWechatWaybillRegistrationProvider provider
    ) {
    }

    private static final class CountingInputStream extends InputStream {

        private int remaining;
        private int bytesRead;

        private CountingInputStream(int length) {
            this.remaining = length;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            bytesRead++;
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'x');
            remaining -= count;
            bytesRead += count;
            return count;
        }

        private int bytesRead() {
            return bytesRead;
        }
    }
}
