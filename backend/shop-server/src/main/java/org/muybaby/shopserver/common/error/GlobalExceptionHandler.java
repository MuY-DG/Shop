package org.muybaby.shopserver.common.error;

import com.wechat.pay.java.core.exception.ServiceException;
import org.muybaby.shopserver.common.api.ApiResponse;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.errorCode();
        RequestLogContext.markError(errorCode);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(statusFor(errorCode));
        if (ex instanceof RateLimitException rateLimitException) {
            response.header("Retry-After", Long.toString(rateLimitException.retryAfterSeconds()));
        }
        return response.body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException() {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException() {
        ErrorCode errorCode = ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler({
            BindException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class,
            MultipartException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleRequestBindingException() {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException() {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(ex.getHeaders())
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.debug("Request resource not found: method={}, path={}",
                ex.getHttpMethod(), ex.getResourcePath());
        ErrorCode errorCode = ErrorCode.RESOURCE_NOT_FOUND;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException() {
        ErrorCode errorCode = ErrorCode.AUTHENTICATION_REQUIRED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException() {
        ErrorCode errorCode = ErrorCode.PERMISSION_DENIED;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        logUnexpectedException(ex);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    private void logUnexpectedException(Exception ex) {
        ServiceException serviceException = findWechatPayServiceException(ex);
        if (serviceException != null) {
            // ServiceException embeds the complete HTTP request in its message, including the
            // Authorization header and payer identifiers. Log only stable, non-sensitive fields.
            log.error("Unhandled request failure: provider=WECHAT_PAY, exceptionType={}, errorCode={}",
                    ex.getClass().getSimpleName(), safeProviderErrorCode(serviceException.getErrorCode()));
            return;
        }
        log.error("Unhandled request failure", ex);
    }

    private ServiceException findWechatPayServiceException(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ServiceException serviceException) {
                return serviceException;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private String safeProviderErrorCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 64));
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED, ADMIN_REGISTRATION_DISABLED -> HttpStatus.FORBIDDEN;
            case DATA_CLEANUP_CONFIG_CONFLICT,
                 WECHAT_SERVICE_CARD_RUNTIME_CONFLICT,
                 WECHAT_EXPRESS_CONFIG_CONFLICT,
                 WECHAT_WAYBILL_CONFLICT,
                 ACCOUNT_RIGHTS_REQUEST_CONFLICT,
                 ACCOUNT_RIGHTS_STATE_CONFLICT,
                 ACCOUNT_CANCELLATION_ACTIVE_OBLIGATIONS,
                 FINANCE_RECONCILIATION_CONFLICT -> HttpStatus.CONFLICT;
            case ADMIN_LOGIN_RATE_LIMITED, ANALYTICS_RATE_LIMITED,
                 APP_USER_AVATAR_RATE_LIMITED,
                 STORAGE_DIRECT_UPLOAD_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                 WECHAT_WAYBILL_REGISTRATION_UNAVAILABLE,
                 WECHAT_WAYBILL_UNAVAILABLE,
                 STORAGE_IMAGE_PROCESSING_FAILED,
                 STORAGE_NOT_CONFIGURED,
                 ADMIN_REGISTRATION_SETTING_UNAVAILABLE,
                 STORAGE_CUSTOM_DOMAIN_VERIFICATION_UNAVAILABLE,
                 FINANCE_RECONCILIATION_DISABLED,
                 FINANCE_RECONCILIATION_UNAVAILABLE,
                 FINANCE_RECONCILIATION_SOURCE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
