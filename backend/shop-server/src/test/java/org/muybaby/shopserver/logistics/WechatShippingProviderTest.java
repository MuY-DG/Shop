package org.muybaby.shopserver.logistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.muybaby.shopserver.logistics.provider.MockWechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.RealWechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatDeliveryCompanyResult;
import org.muybaby.shopserver.logistics.provider.WechatShippingItem;
import org.muybaby.shopserver.logistics.provider.WechatShippingOrderQueryStatus;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.muybaby.shopserver.wechat.platform.WechatPlatformCredentials;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class WechatShippingProviderTest {

    private static final String ACCESS_TOKEN = "synthetic-access-token-never-log";
    private static final String AUTHORIZATION = "Bearer synthetic-authorization-never-log";
    private static final String OPENID = "openid-test-value-never-log";
    private static final String CONSIGNOR_CONTACT = "*******4321";
    private static final String RECEIVER_CONTACT = "*******8000";
    private static final String TRACKING_NO = "SF1234567890";
    private static final String UPLOAD_TIME = "2026-07-09T12:34:56Z";
    private static final String ITEM_DESC = "菌汤锅底 2份";
    private static final String APP_ID = "configured-app-id";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void expressUploadUsesExactOfficialPayloadAndOnlyExplicitZeroMeansUploaded(CapturedOutput output) throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok"}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().upload(uploadRequest(
                LogisticsType.EXPRESS,
                new WechatShippingItem(TRACKING_NO, "SF", ITEM_DESC, CONSIGNOR_CONTACT, RECEIVER_CONTACT)
        ));

        assertThat(capturedBody.get()).isEqualTo(objectMapper.readTree("""
                {
                  "order_key": {
                    "order_number_type": 2,
                    "transaction_id": "4200000000000000001"
                  },
                  "logistics_type": 1,
                  "delivery_mode": 1,
                  "shipping_list": [
                    {
                      "tracking_no": "SF1234567890",
                      "express_company": "SF",
                      "item_desc": "菌汤锅底 2份",
                      "contact": {
                        "consignor_contact": "*******4321",
                        "receiver_contact": "*******8000"
                      }
                    }
                  ],
                  "upload_time": "2026-07-09T12:34:56Z",
                  "payer": {
                    "openid": "openid-test-value-never-log"
                  }
                }
                """));
        assertThat(capturedBody.get().path("shipping_list")).hasSize(1);
        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();
        assertSafeLogs(output, "orderId=91", "UPLOADED");
        fixture.server().verify();
    }

    @Test
    void splitUploadSendsPackageCompletionFlag() {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

        fixture.provider().upload(new WechatShippingUploadRequest(
                91L,
                "4200000000000000001",
                OPENID,
                LogisticsType.EXPRESS,
                DeliveryMode.SPLIT,
                false,
                UPLOAD_TIME,
                List.of(expressItem())
        ));

        assertThat(capturedBody.get().path("delivery_mode").asInt()).isEqualTo(2);
        assertThat(capturedBody.get().path("is_all_delivered").asBoolean()).isFalse();
        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("nonExpressModes")
    void nonExpressUploadUsesExactMinimalItemShape(LogisticsType logisticsType, int officialValue) throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

        var result = fixture.provider().upload(uploadRequest(
                logisticsType,
                new WechatShippingItem(TRACKING_NO, "SF", ITEM_DESC, CONSIGNOR_CONTACT, RECEIVER_CONTACT)
        ));

        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.UPLOADED);
        JsonNode body = capturedBody.get();
        assertThat(body.path("logistics_type").asInt()).isEqualTo(officialValue);
        assertThat(body.path("delivery_mode").asInt()).isEqualTo(1);
        assertThat(body.path("upload_time").asText()).isEqualTo(UPLOAD_TIME);
        assertThat(body.path("shipping_list")).hasSize(1);
        assertThat(body.path("shipping_list").get(0)).isEqualTo(objectMapper.readTree("""
                {"item_desc":"菌汤锅底 2份"}
                """));
        assertThat(body.path("shipping_list").get(0).has("tracking_no")).isFalse();
        assertThat(body.path("shipping_list").get(0).has("express_company")).isFalse();
        assertThat(body.path("shipping_list").get(0).has("contact")).isFalse();
        fixture.server().verify();
    }

    @Test
    void expressUploadOmitsContactWhenBothContactsAreNull() throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

        fixture.provider().upload(uploadRequest(
                LogisticsType.EXPRESS,
                new WechatShippingItem(TRACKING_NO, "SF", ITEM_DESC, null, null)
        ));

        assertThat(capturedBody.get().path("shipping_list").get(0).has("contact")).isFalse();
        fixture.server().verify();
    }

    @Test
    void uploadRejectsAnythingOtherThanOneShippingItemWithoutCallingWechat() {
        ProviderFixture fixture = fixture();

        var emptyResult = fixture.provider().upload(new WechatShippingUploadRequest(
                91L, "4200000000000000001", OPENID, LogisticsType.EXPRESS,
                DeliveryMode.UNIFIED, UPLOAD_TIME, List.of()
        ));
        var multipleResult = fixture.provider().upload(new WechatShippingUploadRequest(
                91L, "4200000000000000001", OPENID, LogisticsType.EXPRESS,
                DeliveryMode.UNIFIED, UPLOAD_TIME,
                List.of(expressItem(), expressItem())
        ));

        assertThat(emptyResult.status()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(emptyResult.errorCode()).isEqualTo("INVALID_SHIPPING_LIST");
        assertThat(multipleResult.status()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(multipleResult.errorCode()).isEqualTo("INVALID_SHIPPING_LIST");
        fixture.server().verify();
    }

    @Test
    void nonzeroUploadResponseIsFailedAndUntrustedMessageIsNotReturnedOrLogged(CapturedOutput output) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andRespond(withSuccess("""
                        {"errcode":48001,"errmsg":"echo synthetic-access-token-never-log openid-test-value-never-log SF1234567890"}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().upload(uploadRequest(LogisticsType.EXPRESS, expressItem()));

        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("WECHAT_48001");
        assertThat(result.errorMessage()).isEqualTo("WeChat shipping upload failed");
        assertSafeLogs(output, "orderId=91", "FAILED", "WECHAT_48001");
        assertThat(output.getAll()).contains(" WARN ");
        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("ambiguousUploadBodies")
    void ambiguousUploadResponseIsUnknown(String responseBody) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var result = fixture.provider().upload(uploadRequest(LogisticsType.EXPRESS, expressItem()));

        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("AMBIGUOUS_RESPONSE");
        assertThat(result.errorMessage()).isEqualTo("WeChat shipping upload result is unknown");
        fixture.server().verify();
    }

    @Test
    void uploadTransportFailureIsUnknownAndLogsOnlySafeMetadata(CapturedOutput output) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/upload_shipping_info"))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "transport " + ACCESS_TOKEN + " " + OPENID + " " + TRACKING_NO + " " + AUTHORIZATION
                    );
                });

        var result = fixture.provider().upload(uploadRequest(LogisticsType.EXPRESS, expressItem()));

        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertSafeLogs(output, "orderId=91", "UNKNOWN", "ResourceAccessException");
        fixture.server().verify();
    }

    @Test
    void accessTokenFailureBeforeUploadDispatchIsUnavailableAndLogsOnlySafeMetadata(CapturedOutput output) {
        ProviderFixture fixture = fixture(() -> {
            throw new IllegalStateException("token acquisition failed " + ACCESS_TOKEN + " " + OPENID);
        });

        var result = fixture.provider().upload(uploadRequest(LogisticsType.EXPRESS, expressItem()));

        assertThat(result.status()).isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("ACCESS_TOKEN_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("WeChat access token is unavailable");
        assertSafeLogs(output, "orderId=91", "UNAVAILABLE", "IllegalStateException");
        fixture.server().verify();
    }

    @Test
    void receiptQueryUsesOfficialPayloadAndAcceptsConfirmedState(CapturedOutput output) throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("""
                        {
                          "errcode": 0,
                          "errmsg": "ok",
                          "order": {
                            "transaction_id": "4200000000000000001",
                            "order_state": 3
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().queryReceiptStatus("4200000000000000001");

        assertThat(capturedBody.get()).isEqualTo(objectMapper.readTree("""
                {"transaction_id":"4200000000000000001"}
                """));
        assertThat(result.status()).isEqualTo(WechatReceiptQueryStatus.CONFIRMED);
        assertThat(result.orderState()).isEqualTo(3);
        assertThat(result.confirmed()).isTrue();
        assertSafeLogs(output, "status=CONFIRMED", "orderState=3");
        fixture.server().verify();
    }

    @Test
    void receiptQueryMapsAllDocumentedTerminalAndNonterminalStates() {
        for (int orderState : List.of(4, 6)) {
            ProviderFixture fixture = fixture();
            fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                    .andRespond(withSuccess("""
                            {"errcode":0,"order":{"transaction_id":"4200000000000000001","order_state":%d}}
                            """.formatted(orderState), MediaType.APPLICATION_JSON));

            assertThat(fixture.provider().queryReceiptStatus("4200000000000000001").status())
                    .isEqualTo(WechatReceiptQueryStatus.CONFIRMED);
            fixture.server().verify();
        }

        for (int orderState : List.of(1, 2, 5)) {
            ProviderFixture fixture = fixture();
            fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                    .andRespond(withSuccess("""
                            {"errcode":0,"order":{"transaction_id":"4200000000000000001","order_state":%d}}
                            """.formatted(orderState), MediaType.APPLICATION_JSON));

            var result = fixture.provider().queryReceiptStatus("4200000000000000001");
            assertThat(result.status()).isEqualTo(WechatReceiptQueryStatus.NOT_CONFIRMED);
            assertThat(result.orderState()).isEqualTo(orderState);
            fixture.server().verify();
        }
    }

    @Test
    void receiptQueryRejectsMismatchedOrderAndAmbiguousWechatResponse(CapturedOutput output) {
        ProviderFixture mismatch = fixture();
        mismatch.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(withSuccess("""
                        {"errcode":0,"order":{"transaction_id":"different","order_state":3}}
                        """, MediaType.APPLICATION_JSON));

        var mismatchResult = mismatch.provider().queryReceiptStatus("4200000000000000001");

        assertThat(mismatchResult.status()).isEqualTo(WechatReceiptQueryStatus.UNKNOWN);
        assertThat(mismatchResult.errorCode()).isEqualTo("ORDER_MISMATCH");
        mismatch.server().verify();

        ProviderFixture rejected = fixture();
        rejected.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(withSuccess("""
                        {"errcode":10060001,"errmsg":"untrusted provider message"}
                        """, MediaType.APPLICATION_JSON));

        var rejectedResult = rejected.provider().queryReceiptStatus("4200000000000000001");

        assertThat(rejectedResult.status()).isEqualTo(WechatReceiptQueryStatus.UNKNOWN);
        assertThat(rejectedResult.errorCode()).isEqualTo("WECHAT_10060001");
        assertThat(rejectedResult.errorMessage()).isEqualTo("WeChat receipt status could not be confirmed");
        assertSafeLogs(output, "status=UNKNOWN", "WECHAT_10060001");
        assertThat(output.getAll()).contains(" WARN ");
        rejected.server().verify();
    }

    @Test
    void receiptQueryTokenAndTransportFailuresFailClosedWithoutSensitiveLogs(CapturedOutput output) {
        ProviderFixture tokenFailure = fixture(() -> {
            throw new IllegalStateException("token " + ACCESS_TOKEN + " transaction 4200000000000000001");
        });

        var unavailable = tokenFailure.provider().queryReceiptStatus("4200000000000000001");

        assertThat(unavailable.status()).isEqualTo(WechatReceiptQueryStatus.UNAVAILABLE);
        assertThat(unavailable.errorCode()).isEqualTo("ACCESS_TOKEN_UNAVAILABLE");
        tokenFailure.server().verify();

        ProviderFixture transportFailure = fixture();
        transportFailure.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "transport " + ACCESS_TOKEN + " transaction 4200000000000000001"
                    );
                });

        var unknown = transportFailure.provider().queryReceiptStatus("4200000000000000001");

        assertThat(unknown.status()).isEqualTo(WechatReceiptQueryStatus.UNKNOWN);
        assertThat(unknown.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertSafeLogs(output, "status=UNAVAILABLE", "status=UNKNOWN", "ResourceAccessException");
        transportFailure.server().verify();
    }

    @Test
    void shippingOrderQueryRetainsOnlyComparableShippingFacts() {
        ProviderFixture uploadedFixture = fixture();
        uploadedFixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(withSuccess("""
                        {
                          "errcode": 0,
                          "order": {
                            "transaction_id": "4200000000000000001",
                            "order_state": 2,
                            "shipping": {
                              "logistics_type": 1,
                              "delivery_mode": 1,
                              "finish_shipping": true,
                              "shipping_list": [
                                {"tracking_no":"SF1234567890","express_company":"SF","item_desc":"must-not-escape"}
                              ]
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var uploaded = uploadedFixture.provider()
                .queryShippingOrder("4200000000000000001");

        assertThat(uploaded.status()).isEqualTo(WechatShippingOrderQueryStatus.UPLOADED);
        assertThat(uploaded.transactionId()).isEqualTo("4200000000000000001");
        assertThat(uploaded.orderState()).isEqualTo(2);
        assertThat(uploaded.shipping().logisticsType()).isEqualTo(LogisticsType.EXPRESS);
        assertThat(uploaded.shipping().deliveryMode()).isEqualTo(DeliveryMode.UNIFIED);
        assertThat(uploaded.shipping().finishShipping()).isTrue();
        assertThat(uploaded.shipping().shippingList()).singleElement().satisfies(item -> {
            assertThat(item.trackingNo()).isEqualTo(TRACKING_NO);
            assertThat(item.expressCompany()).isEqualTo("SF");
        });
        uploadedFixture.server().verify();

        ProviderFixture notUploadedFixture = fixture();
        notUploadedFixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(withSuccess("""
                        {"errcode":0,"order":{
                          "transaction_id":"4200000000000000001","order_state":1
                        }}
                        """, MediaType.APPLICATION_JSON));

        var notUploaded = notUploadedFixture.provider()
                .queryShippingOrder("4200000000000000001");

        assertThat(notUploaded.status()).isEqualTo(WechatShippingOrderQueryStatus.NOT_UPLOADED);
        assertThat(notUploaded.transactionId()).isEqualTo("4200000000000000001");
        assertThat(notUploaded.orderState()).isEqualTo(1);
        assertThat(notUploaded.shipping()).isNull();
        notUploadedFixture.server().verify();
    }

    @Test
    void oversizedShippingResponseFailsClosedAsUnknown(CapturedOutput output) {
        ProviderFixture fixture = fixture(() -> ACCESS_TOKEN, DataSize.ofBytes(64));
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/get_order"))
                .andRespond(withSuccess("x".repeat(65), MediaType.APPLICATION_JSON));

        var result = fixture.provider().queryShippingOrder("4200000000000000001");

        assertThat(result.status()).isEqualTo(WechatShippingOrderQueryStatus.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertSafeLogs(output, "status=UNKNOWN", "ResponseTooLargeException");
        fixture.server().verify();
    }

    @Test
    void capabilityUsesOfficialEndpointAndConfiguredAppId() throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/is_trade_managed"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok","is_trade_managed":true}
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.provider().queryCapability();

        assertThat(capturedBody.get()).isEqualTo(objectMapper.readTree("""
                {"appid":"configured-app-id"}
                """));
        assertThat(result.state()).isEqualTo(WechatShippingCapabilityState.AVAILABLE);
        assertThat(result.tradeManaged()).isTrue();
        assertThat(result.errorCode()).isNull();
        fixture.server().verify();
    }

    @Test
    void explicitUnmanagedAndKnownAccountErrorAreUnavailable() {
        ProviderFixture unmanaged = fixture();
        unmanaged.server().expect(once(), safeEndpoint("/wxa/sec/order/is_trade_managed"))
                .andRespond(withSuccess("""
                        {"errcode":0,"is_trade_managed":false}
                        """, MediaType.APPLICATION_JSON));

        var unmanagedResult = unmanaged.provider().queryCapability();

        assertThat(unmanagedResult.state()).isEqualTo(WechatShippingCapabilityState.UNAVAILABLE);
        assertThat(unmanagedResult.tradeManaged()).isFalse();
        assertThat(unmanagedResult.errorCode()).isEqualTo("TRADE_NOT_MANAGED");
        unmanaged.server().verify();

        ProviderFixture unauthorized = fixture();
        unauthorized.server().expect(once(), safeEndpoint("/wxa/sec/order/is_trade_managed"))
                .andRespond(withSuccess("""
                        {"errcode":48001,"errmsg":"api unauthorized"}
                        """, MediaType.APPLICATION_JSON));

        var unauthorizedResult = unauthorized.provider().queryCapability();

        assertThat(unauthorizedResult.state()).isEqualTo(WechatShippingCapabilityState.UNAVAILABLE);
        assertThat(unauthorizedResult.tradeManaged()).isNull();
        assertThat(unauthorizedResult.errorCode()).isEqualTo("WECHAT_48001");
        unauthorized.server().verify();
    }

    @ParameterizedTest
    @MethodSource("ambiguousCapabilityBodies")
    void malformedOrIncompleteCapabilityResponseIsUnknown(String responseBody) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/is_trade_managed"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var result = fixture.provider().queryCapability();

        assertThat(result.state()).isEqualTo(WechatShippingCapabilityState.UNKNOWN);
        assertThat(result.tradeManaged()).isNull();
        fixture.server().verify();
    }

    @Test
    void capabilityTransportFailureIsUnknownAndDoesNotLeakExceptionMessage(CapturedOutput output) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/wxa/sec/order/is_trade_managed"))
                .andRespond(request -> {
                    throw new ResourceAccessException("transport " + ACCESS_TOKEN + " " + AUTHORIZATION);
                });

        var result = fixture.provider().queryCapability();

        assertThat(result.state()).isEqualTo(WechatShippingCapabilityState.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("REQUEST_AMBIGUOUS");
        assertSafeLogs(output, "UNKNOWN", "ResourceAccessException");
        fixture.server().verify();
    }

    @Test
    void carrierDirectoryUsesOfficialPostEndpointAndParsesOnlyCompleteRows() throws Exception {
        ProviderFixture fixture = fixture();
        AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
        fixture.server().expect(once(), safeEndpoint("/cgi-bin/express/delivery/open_msg/get_delivery_list"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureJson(capturedBody))
                .andRespond(withSuccess("""
                        {
                          "errcode": 0,
                          "errmsg": "ok",
                          "delivery_list": [
                            {"delivery_id":"SF","delivery_name":"顺丰速运"},
                            {"delivery_id":"JD","delivery_name":"京东物流"},
                            {"delivery_id":"","delivery_name":"不完整"},
                            {"delivery_id":"ZT","delivery_name":"  "}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<WechatDeliveryCompanyResult> result = fixture.provider().getDeliveryCompanies();

        assertThat(capturedBody.get()).isEqualTo(objectMapper.createObjectNode());
        assertThat(result).containsExactly(
                new WechatDeliveryCompanyResult("SF", "顺丰速运"),
                new WechatDeliveryCompanyResult("JD", "京东物流")
        );
        fixture.server().verify();
    }

    @ParameterizedTest
    @MethodSource("invalidCarrierBodies")
    void carrierDirectoryFailureThrowsSafeExceptionWithoutAcceptingAmbiguousData(String responseBody) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/cgi-bin/express/delivery/open_msg/get_delivery_list"))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        var assertion = assertThatThrownBy(() -> fixture.provider().getDeliveryCompanies())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WeChat delivery company lookup failed")
                .hasMessageNotContaining(ACCESS_TOKEN);
        if (!responseBody.isEmpty()) {
            assertion.hasMessageNotContaining(responseBody);
        }
        fixture.server().verify();
    }

    @Test
    void carrierTransportFailureThrowsSafeExceptionAndDoesNotLeakLogs(CapturedOutput output) {
        ProviderFixture fixture = fixture();
        fixture.server().expect(once(), safeEndpoint("/cgi-bin/express/delivery/open_msg/get_delivery_list"))
                .andRespond(request -> {
                    throw new ResourceAccessException("transport " + ACCESS_TOKEN + " " + OPENID);
                });

        assertThatThrownBy(() -> fixture.provider().getDeliveryCompanies())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WeChat delivery company lookup failed")
                .hasMessageNotContaining(ACCESS_TOKEN);
        assertSafeLogs(output, "ResourceAccessException");
        fixture.server().verify();
    }

    @Test
    void runtimeMockProviderNeverClaimsPlatformAvailabilityOrUploadSuccess() {
        MockWechatShippingProvider provider = new MockWechatShippingProvider();

        assertThat(provider.mode()).isEqualTo(WechatProviderMode.MOCK);
        assertThat(provider.upload(uploadRequest(LogisticsType.EXPRESS, expressItem())).status())
                .isEqualTo(WechatShippingUploadStatus.UNAVAILABLE);
        assertThat(provider.upload(uploadRequest(LogisticsType.EXPRESS, expressItem())).errorCode())
                .isEqualTo("MOCK_PROVIDER");
        assertThat(provider.queryCapability().state()).isEqualTo(WechatShippingCapabilityState.UNAVAILABLE);
        assertThat(provider.queryCapability().errorCode()).isEqualTo("MOCK_PROVIDER");
        assertThat(provider.queryReceiptStatus("4200000000000000001").status())
                .isEqualTo(WechatReceiptQueryStatus.UNAVAILABLE);
        assertThat(provider.queryReceiptStatus("4200000000000000001").errorCode())
                .isEqualTo("MOCK_PROVIDER");
    }

    @ParameterizedTest
    @EnumSource(value = WechatShippingUploadStatus.class, names = {"PENDING", "SKIPPED", "UPLOADING"})
    void providerResultRejectsStatusesOutsideItsPublicContract(WechatShippingUploadStatus status) {
        assertThatThrownBy(() -> new org.muybaby.shopserver.logistics.provider.WechatShippingUploadResult(
                status, "INVALID", "invalid provider result"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private ProviderFixture fixture() {
        return fixture(() -> ACCESS_TOKEN);
    }

    private ProviderFixture fixture(WechatAccessTokenProvider accessTokenProvider) {
        return fixture(accessTokenProvider, DataSize.ofMegabytes(1));
    }

    private ProviderFixture fixture(
            WechatAccessTokenProvider accessTokenProvider,
            DataSize maxResponseSize
    ) {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        RealWechatShippingProvider provider = new RealWechatShippingProvider(
                restClientBuilder.build(),
                objectMapper,
                accessTokenProvider,
                () -> new WechatPlatformCredentials(
                        APP_ID, "configured-secret",
                        WechatPlatformCredentials.Source.DATABASE),
                new org.muybaby.shopserver.logistics.provider.WechatShippingHttpProperties(
                        java.time.Duration.ofSeconds(3),
                        java.time.Duration.ofSeconds(15),
                        maxResponseSize
                )
        );
        return new ProviderFixture(server, provider);
    }

    private WechatShippingUploadRequest uploadRequest(LogisticsType logisticsType, WechatShippingItem item) {
        return new WechatShippingUploadRequest(
                91L,
                "4200000000000000001",
                OPENID,
                logisticsType,
                DeliveryMode.UNIFIED,
                UPLOAD_TIME,
                List.of(item)
        );
    }

    private WechatShippingItem expressItem() {
        return new WechatShippingItem(TRACKING_NO, "SF", ITEM_DESC, CONSIGNOR_CONTACT, RECEIVER_CONTACT);
    }

    private RequestMatcher safeEndpoint(String expectedPath) {
        return request -> {
            URI uri = request.getURI();
            if (!"https".equals(uri.getScheme())
                    || !"api.weixin.qq.com".equals(uri.getHost())
                    || !expectedPath.equals(uri.getPath())) {
                throw new AssertionError("Unexpected WeChat endpoint path");
            }
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || !rawQuery.startsWith("access_token=")
                    || rawQuery.length() <= "access_token=".length()) {
                throw new AssertionError("WeChat endpoint is missing an access token query parameter");
            }
        };
    }

    private RequestMatcher captureJson(AtomicReference<JsonNode> target) {
        return request -> target.set(objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsBytes()));
    }

    private void assertSafeLogs(CapturedOutput output, String... expectedSafeMetadata) {
        assertThat(output.getAll())
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain(AUTHORIZATION)
                .doesNotContain(OPENID)
                .doesNotContain(CONSIGNOR_CONTACT)
                .doesNotContain(RECEIVER_CONTACT)
                .doesNotContain(TRACKING_NO)
                .doesNotContain("shipping_list")
                .doesNotContain("transaction_id");
        assertThat(output.getAll()).contains(expectedSafeMetadata);
    }

    private static Stream<Arguments> nonExpressModes() {
        return Stream.of(
                Arguments.of(LogisticsType.LOCAL_DELIVERY, 2),
                Arguments.of(LogisticsType.VIRTUAL, 3),
                Arguments.of(LogisticsType.PICKUP, 4)
        );
    }

    private static Stream<String> ambiguousUploadBodies() {
        return Stream.of(
                "{}",
                "",
                "   ",
                "null",
                "not-json",
                "{\"errcode\":\"0\"}",
                "{\"errcode\":0.0}"
        );
    }

    private static Stream<String> ambiguousCapabilityBodies() {
        return Stream.of(
                "{}",
                "",
                "   ",
                "null",
                "not-json",
                "{\"is_trade_managed\":true}",
                "{\"errcode\":\"0\",\"is_trade_managed\":true}",
                "{\"errcode\":0.0,\"is_trade_managed\":true}",
                "{\"errcode\":0,\"is_trade_managed\":\"true\"}",
                "{\"errcode\":0,\"is_trade_managed\":1}"
        );
    }

    private static Stream<String> invalidCarrierBodies() {
        return Stream.of(
                "{}",
                "",
                "   ",
                "null",
                "not-json",
                "{\"errcode\":0}",
                "{\"errcode\":\"0\",\"delivery_list\":[]}",
                "{\"errcode\":0.0,\"delivery_list\":[]}",
                "{\"errcode\":0,\"delivery_list\":{}}",
                "{\"errcode\":0,\"delivery_list\":[null,{}, {\"delivery_id\":\"\",\"delivery_name\":\"  \"}]}",
                "{\"errcode\":48001,\"errmsg\":\"api unauthorized\"}"
        );
    }

    private record ProviderFixture(MockRestServiceServer server, RealWechatShippingProvider provider) {
    }
}
