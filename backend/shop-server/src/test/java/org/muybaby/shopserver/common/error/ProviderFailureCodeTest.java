package org.muybaby.shopserver.common.error;

import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderFailureCodeTest {

    @Test
    void extractsWechatErrorCodeThroughWrapperWithoutUsingSensitiveMessage() {
        ServiceException providerFailure = new ServiceException(
                mock(HttpRequest.class),
                400,
                "{\"code\":\"INVALID_REQUEST\",\"message\":\"authorization-secret\"}"
        );

        assertThat(ProviderFailureCode.safeCode(
                new IllegalStateException("wrapper-secret", providerFailure)))
                .isEqualTo("INVALID_REQUEST")
                .doesNotContain("secret");
    }

    @Test
    void fallsBackToStableExceptionType() {
        assertThat(ProviderFailureCode.safeCode(new IllegalStateException("sensitive-detail")))
                .isEqualTo("IllegalStateException");
        assertThat(ProviderFailureCode.safeCode(null)).isEqualTo("RuntimeException");
    }
}
