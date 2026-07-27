package org.muybaby.shopserver.payment.provider;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WechatRefundRequestTest {

    @Test
    void reasonIsCanonicalizedToTheWechatUtf8ByteLimit() {
        WechatRefundRequest request = request("  同意退款🙂" + "原因".repeat(30) + "  ");

        assertThat(request.reason()).startsWith("同意退款?");
        assertThat(request.reason().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(80);
        assertThat(request.reason()).doesNotContain("🙂");
        assertThat(request.reason()).doesNotEndWith(" ");
    }

    @Test
    void canonicalizationIsStableAcrossIdempotentRetries() {
        String reason = "退款原因".repeat(30);

        assertThat(request(reason).reason()).isEqualTo(request(reason).reason());
    }

    @Test
    void blankReasonRemainsEmptySoTheProviderCanOmitIt() {
        assertThat(request("   ").reason()).isEmpty();
    }

    private WechatRefundRequest request(String reason) {
        return new WechatRefundRequest(
                "trade-1", "transaction-1", "refund-1", 100L, 100L, reason, "https://example.test/refund"
        );
    }
}
