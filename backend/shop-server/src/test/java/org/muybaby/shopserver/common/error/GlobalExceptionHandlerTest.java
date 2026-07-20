package org.muybaby.shopserver.common.error;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void businessExceptionReturnsStableCodeAndMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.STOCK_SHORTAGE)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(200100);
        assertThat(response.getBody().msg()).isEqualTo("Stock shortage");
    }

    @Test
    void authenticationRequiredBusinessExceptionReturnsUnauthorized() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED)
        );

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100001);
        assertThat(response.getBody().msg()).isEqualTo("Authentication required");
    }

    @Test
    void loginProtectionErrorsUseRateLimitAndUnavailableStatuses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> limited = handler.handleBusinessException(
                new BusinessException(ErrorCode.ADMIN_LOGIN_RATE_LIMITED));
        ResponseEntity<ApiResponse<Void>> unavailable = handler.handleBusinessException(
                new BusinessException(ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE));

        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getBody()).isNotNull();
        assertThat(limited.getBody().code()).isEqualTo(100005);
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody()).isNotNull();
        assertThat(unavailable.getBody().code()).isEqualTo(100503);
    }

    @Test
    void bindingFailuresStillReturnBadRequestEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestBindingException();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100400);
        assertThat(response.getBody().msg()).isEqualTo("Validation failed");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void oversizedMultipartReturnsStorageUploadPolicyEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSizeExceededException();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(800002);
        assertThat(response.getBody().msg()).isEqualTo("Storage upload policy rejected");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void unsupportedMediaTypeReturnsUnsupportedMediaTypeEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpMediaTypeNotSupportedException();

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100400);
        assertThat(response.getBody().msg()).isEqualTo("Validation failed");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    void unsupportedMethodReturnsMethodNotAllowedEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpRequestMethodNotSupportedException(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100400);
        assertThat(response.getBody().msg()).isEqualTo("Validation failed");
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getHeaders().getAllow()).containsExactly(org.springframework.http.HttpMethod.GET);
    }

    @Test
    void unexpectedExceptionReturnsStableNonSensitiveEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(
                new IllegalStateException("database-password-must-not-leak")
        );

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100500);
        assertThat(response.getBody().msg()).isEqualTo("Internal server error");
        assertThat(response.getBody().msg()).doesNotContain("database-password-must-not-leak");
        assertThat(response.getBody().data()).isNull();
    }
}
