package org.muybaby.shopserver.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestLogContext {

    public static final String ERROR_CODE_ATTRIBUTE =
            RequestLogContext.class.getName() + ".errorCode";
    public static final String ERROR_MESSAGE_ATTRIBUTE =
            RequestLogContext.class.getName() + ".errorMessage";
    public static final String PROVIDER_ERROR_CODE_ATTRIBUTE =
            RequestLogContext.class.getName() + ".providerErrorCode";
    public static final String LOGIN_OPERATOR_ID_ATTRIBUTE =
            RequestLogContext.class.getName() + ".loginOperatorId";
    public static final String LOGIN_OPERATOR_NAME_ATTRIBUTE =
            RequestLogContext.class.getName() + ".loginOperatorName";

    private RequestLogContext() {
    }

    public static void markError(ErrorCode errorCode) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            markError(attributes.getRequest(), errorCode);
        }
    }

    public static void markError(HttpServletRequest request, ErrorCode errorCode) {
        if (request == null || errorCode == null) {
            return;
        }
        request.setAttribute(ERROR_CODE_ATTRIBUTE, Integer.toString(errorCode.code()));
        request.setAttribute(ERROR_MESSAGE_ATTRIBUTE, errorCode.message());
    }

    public static void markProviderError(String providerErrorCode) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && providerErrorCode != null && !providerErrorCode.isBlank()) {
            attributes.getRequest().setAttribute(PROVIDER_ERROR_CODE_ATTRIBUTE, providerErrorCode);
        }
    }

    public static String currentRequestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            Object requestId = attributes.getRequest().getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            return requestId == null ? "" : requestId.toString();
        }
        return "";
    }

    public static void markLoginCandidate(HttpServletRequest request, String username) {
        if (request != null) {
            request.setAttribute(LOGIN_OPERATOR_NAME_ATTRIBUTE, username);
        }
    }

    public static void markLoginSuccess(HttpServletRequest request, Long operatorId, String username) {
        if (request == null) {
            return;
        }
        request.setAttribute(LOGIN_OPERATOR_ID_ATTRIBUTE, operatorId);
        request.setAttribute(LOGIN_OPERATOR_NAME_ATTRIBUTE, username);
    }
}
