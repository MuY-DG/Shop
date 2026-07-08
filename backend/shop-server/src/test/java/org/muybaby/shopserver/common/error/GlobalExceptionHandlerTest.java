package org.muybaby.shopserver.common.error;

import org.muybaby.shopserver.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
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

        ResponseEntity<ApiResponse<Void>> response = handler.handleHttpRequestMethodNotSupportedException();

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100400);
        assertThat(response.getBody().msg()).isEqualTo("Validation failed");
        assertThat(response.getBody().data()).isNull();
    }
}
