package org.muybaby.shopserver.payment.config;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.payment.PaymentNotificationRouteProperties;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentNotificationRouteServiceTest {

    private static final String VALID_TOKEN = "0123456789abcdefghijklmnopqrstuv";

    @Test
    void disabledIssuanceKeepsLegacyRoute() {
        PaymentNotificationRouteService service = service(false);

        assertThat(service.issueToken()).isNull();
        assertThat(service.payNotifyUrl("https://pay.example.test/wxpay/pay/notify/", null))
                .isEqualTo("https://pay.example.test/wxpay/pay/notify/");
        assertThat(service.refundNotifyUrl("https://pay.example.test/wxpay/refund/notify", ""))
                .isEqualTo("https://pay.example.test/wxpay/refund/notify");
    }

    @Test
    void enabledIssuanceCreatesUniqueUrlSafeTokensWith192BitsOfRandomInput() {
        PaymentNotificationRouteService service = service(true);
        Set<String> tokens = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            String token = service.issueToken();
            assertThat(token).matches("[A-Za-z0-9_-]{32}");
            assertThat(token).doesNotContain("=");
            tokens.add(token);
        }

        assertThat(tokens).hasSize(100);
    }

    @Test
    void routedUrlUsesCanonicalPathAndAcceptsTheMaximumSupportedLength() {
        PaymentNotificationRouteService service = service(true);
        String prefix = "https://pay.example.test/";
        String maximumBaseUrl = prefix + "a".repeat(220 - prefix.length());

        assertThat(service.payNotifyUrl(maximumBaseUrl + "/", VALID_TOKEN))
                .isEqualTo(maximumBaseUrl + "/r/" + VALID_TOKEN)
                .hasSize(255);
    }

    @Test
    void rejectsMalformedTokens() {
        PaymentNotificationRouteService service = service(true);

        assertValidationFailure(() -> service.requireRouteToken(null));
        assertValidationFailure(() -> service.requireRouteToken(""));
        assertValidationFailure(() -> service.requireRouteToken("a".repeat(31)));
        assertValidationFailure(() -> service.requireRouteToken("a".repeat(33)));
        assertValidationFailure(() -> service.requireRouteToken("a".repeat(31) + "="));
        assertValidationFailure(() -> service.requireRouteToken("a".repeat(31) + "."));
        assertValidationFailure(() -> service.requireRouteToken(" " + VALID_TOKEN));
        assertValidationFailure(() -> service.requireRouteToken(VALID_TOKEN + " "));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://pay.example.test/wxpay/pay/notify", "not-a-route-token"));
    }

    @Test
    void rejectsUnsafeOrNonRoutableBaseUrls() {
        PaymentNotificationRouteService service = service(true);

        assertValidationFailure(() -> service.payNotifyUrl(null, VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl("", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "http://pay.example.test/wxpay/pay/notify", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://user:password@pay.example.test/wxpay/pay/notify", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://pay.example.test/wxpay/pay/notify?route=guessable", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://pay.example.test/wxpay/pay/notify#fragment", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://pay.example.test:0/wxpay/pay/notify", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl(
                "https://pay.example.test:70000/wxpay/pay/notify", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl("https://pay.example.test", VALID_TOKEN));
        assertValidationFailure(() -> service.payNotifyUrl("https://pay.example.test/", VALID_TOKEN));
    }

    @Test
    void rejectsAValidBaseWhoseRoutedFormExceedsWechatLimit() {
        PaymentNotificationRouteService service = service(true);
        String prefix = "https://pay.example.test/";
        String tooLongWhenRouted = prefix + "a".repeat(221 - prefix.length());

        assertThat(tooLongWhenRouted).hasSize(221);
        assertValidationFailure(() -> service.refundNotifyUrl(tooLongWhenRouted, VALID_TOKEN));
        assertValidationFailure(() -> service.validateRoutedBaseUrl(tooLongWhenRouted));
    }

    private PaymentNotificationRouteService service(boolean enabled) {
        return new PaymentNotificationRouteService(new PaymentNotificationRouteProperties(enabled));
    }

    private void assertValidationFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
