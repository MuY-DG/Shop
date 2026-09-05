package org.muybaby.shopserver.common.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.HttpRequest;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    @Test
    void unresolvedFulfillmentReturnsAnActionableConflict() {
        ResponseEntity<ApiResponse<Void>> response = new GlobalExceptionHandler().handleBusinessException(
                new BusinessException(ErrorCode.ORDER_FULFILLMENT_UNRESOLVED));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400002);
        assertThat(response.getBody().msg()).contains("历史退款", "核对履约记录");
    }

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
    void imageProcessingFailureReturnsServiceUnavailable() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.STORAGE_IMAGE_PROCESSING_FAILED));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(800007);
        assertThat(response.getBody().msg()).isEqualTo("COS 图片处理暂时失败，请稍后重试");
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
    void missingResourceReturnsNotFoundEnvelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "admin/missing");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNoResourceFoundException(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(100404);
        assertThat(response.getBody().msg()).isEqualTo("Resource not found");
        assertThat(response.getBody().data()).isNull();
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

    @Test
    void wechatServiceExceptionLogOmitsRequestAndResponseSecrets() {
        String secret = "openid-and-authorization-must-not-leak";
        ServiceException exception = new ServiceException(
                mock(HttpRequest.class),
                400,
                "{\"code\":\"PARAM_ERROR\",\"message\":\"" + secret + "\"}"
        );
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler().handleUnexpectedException(exception);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage())
                .contains("provider=WECHAT_PAY", "errorCode=PARAM_ERROR")
                .doesNotContain(secret, "Authorization", "openid");
        assertThat(event.getThrowableProxy()).isNull();
    }
}
