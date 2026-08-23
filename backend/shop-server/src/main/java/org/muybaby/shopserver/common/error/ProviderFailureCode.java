package org.muybaby.shopserver.common.error;

import com.wechat.pay.java.core.exception.ServiceException;

/**
 * Extracts a stable provider error code without persisting or logging exception messages, which
 * may contain request headers and customer identifiers.
 */
public final class ProviderFailureCode {

    private static final int MAX_CODE_LENGTH = 64;

    private ProviderFailureCode() {
    }

    public static String safeCode(Throwable failure) {
        ServiceException serviceException = findWechatPayServiceException(failure);
        if (serviceException != null) {
            return sanitize(serviceException.getErrorCode(), "WECHAT_PAY_ERROR");
        }
        String fallback = failure == null ? null : failure.getClass().getSimpleName();
        return sanitize(fallback, "RuntimeException");
    }

    public static ServiceException findWechatPayServiceException(Throwable failure) {
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

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_CODE_LENGTH));
    }
}
