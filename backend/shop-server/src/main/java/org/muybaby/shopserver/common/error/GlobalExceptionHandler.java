package org.muybaby.shopserver.common.error;

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
        log.error("Unhandled request failure", ex);
        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        RequestLogContext.markError(errorCode);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(errorCode.code(), errorCode.message()));
    }

    private HttpStatus statusFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case ADMIN_LOGIN_RATE_LIMITED, ANALYTICS_RATE_LIMITED,
                 APP_USER_AVATAR_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                 STORAGE_IMAGE_COMPRESSION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
