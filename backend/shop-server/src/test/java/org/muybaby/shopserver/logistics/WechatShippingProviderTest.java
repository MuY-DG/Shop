package org.muybaby.shopserver.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.logistics.provider.RealWechatShippingProvider;
import org.muybaby.shopserver.logistics.provider.WechatShippingUploadRequest;
import org.muybaby.shopserver.wechat.WechatAccessTokenProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WechatShippingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void realProviderUploadsShippingInfoWithStableTokenAndOfficialOrderKey() throws Exception {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        WechatAccessTokenProvider accessTokenProvider = () -> "stable-token-from-provider";
        RealWechatShippingProvider provider = new RealWechatShippingProvider(restClientBuilder, objectMapper, accessTokenProvider);

        server.expect(once(), requestTo("https://api.weixin.qq.com/wxa/sec/order/upload_shipping_info?access_token=stable-token-from-provider"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.order_key.order_number_type").value(2))
                .andExpect(jsonPath("$.order_key.transaction_id").value("wx-transaction-provider"))
                .andExpect(jsonPath("$.payer.openid").value("openid-provider"))
                .andExpect(jsonPath("$.logistics_type").value(1))
                .andExpect(jsonPath("$.delivery_mode").value(1))
                .andExpect(jsonPath("$.upload_time").value(matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$"
                )))
                .andExpect(jsonPath("$.shipping_list[0].tracking_no").value("SF1234567890"))
                .andExpect(jsonPath("$.shipping_list[0].express_company").value("顺丰速运"))
                .andExpect(content().string(not(containsString("stable-token-from-provider"))))
                .andRespond(withSuccess("""
                        {"errcode":0,"errmsg":"ok"}
                        """, MediaType.APPLICATION_JSON));

        var result = provider.upload(new WechatShippingUploadRequest(
                1L,
                "wx-transaction-provider",
                "SHIP-PROVIDER-MCH",
                "openid-provider",
                "顺丰速运",
                "SF1234567890",
                "front desk pickup"
        ));

        assertThat(result.success()).isTrue();
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.errorMessage()).isEmpty();
        server.verify();
    }

    @Test
    void realProviderFailsSafelyWhenTransactionIdIsMissing() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        RealWechatShippingProvider provider = new RealWechatShippingProvider(restClientBuilder, objectMapper, () -> "stable-token-from-provider");

        var result = provider.upload(new WechatShippingUploadRequest(
                1L,
                "",
                "SHIP-PROVIDER-MCH",
                "openid-provider",
                "顺丰速运",
                "SF1234567890",
                "front desk pickup"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_TRANSACTION_ID");
        assertThat(result.errorMessage()).isEqualTo("WeChat payment transaction id is required");
        server.verify();
    }
}
