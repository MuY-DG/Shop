package org.muybaby.shopserver.admin.log.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.admin.log.AdminSystemLogLevel;
import org.muybaby.shopserver.admin.log.AdminSystemLogResult;
import org.muybaby.shopserver.admin.log.AdminSystemLogType;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogRecord;
import org.muybaby.shopserver.admin.log.service.AdminSystemLogRecorder;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.web.RequestIdFilter;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.security.web.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AdminSystemLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemLogFilter.class);
    private static final String LOGIN_PATH = "/admin/auth/login";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long FAILURE_WARNING_INTERVAL_MILLIS = 60_000L;
    private static final Set<String> WRITING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EXCLUDED_PATHS = Set.of(
            "/admin/auth/refresh",
            "/admin/auth/current-user",
            "/admin/system/menus",
            "/admin/system/access-catalog",
            "/admin/system/logs",
            "/admin/realtime/tickets"
    );

    private final AdminSystemLogRecorder recorder;
    private final ClientIpResolver clientIpResolver;
    private final AtomicLong lastFailureWarningAt = new AtomicLong();

    public AdminSystemLogFilter(AdminSystemLogRecorder recorder, ClientIpResolver clientIpResolver) {
        this.recorder = recorder;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        AuthenticatedPrincipal principal = currentPrincipal();
        Throwable requestFailure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            requestFailure = ex;
            throw ex;
        } finally {
            try {
                recordRequest(request, response, principal, requestFailure, startedAt);
            } catch (RuntimeException ex) {
                warnRecorderFailure(ex);
            }
        }
    }

    private void recordRequest(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticatedPrincipal principal,
            Throwable requestFailure,
            long startedAt
    ) {
        String path = requestPath(request);
        int status = requestFailure != null && response.getStatus() < 500
                ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                : response.getStatus();
        if (!shouldRecord(request, path, principal, status)) {
            return;
        }
        AdminSystemLogType type = type(request.getMethod(), path, status);
        AdminSystemLogResult result = status >= 400
                ? AdminSystemLogResult.FAILURE
                : AdminSystemLogResult.SUCCESS;
        AdminSystemLogLevel level = status >= 500
                ? AdminSystemLogLevel.ERROR
                : result == AdminSystemLogResult.FAILURE
                ? AdminSystemLogLevel.WARN
                : AdminSystemLogLevel.INFO;
        Operator operator = operator(request, path, principal);
        String routePattern = attribute(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        ErrorDetails error = errorDetails(request, status);

        AdminSystemLogRecord record = new AdminSystemLogRecord(
                type,
                result,
                level,
                operator.id(),
                clean(operator.username(), 64),
                clean(module(path), 64),
                clean(action(request.getMethod(), path, routePattern), 128),
                clean(request.getMethod(), 10),
                clean(path, 255),
                clean(routePattern, 255),
                status,
                Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L),
                clean(clientIpResolver.resolve(request), 45),
                clean(request.getHeader("User-Agent"), 255),
                clean(attribute(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE), 128),
                clean(error.code(), 64),
                clean(error.message(), 255),
                LocalDateTime.now(BUSINESS_ZONE)
        );
        recorder.record(record);
    }

    private boolean shouldRecord(
            HttpServletRequest request,
            String path,
            AuthenticatedPrincipal principal,
            int status
    ) {
        if (!path.startsWith("/admin/")) {
            return false;
        }
        if (LOGIN_PATH.equals(path)) {
            return status != 429
                    && status != HttpServletResponse.SC_SERVICE_UNAVAILABLE
                    && request.getAttribute(RequestLogContext.LOGIN_OPERATOR_NAME_ATTRIBUTE) != null;
        }
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            return false;
        }
        if (status < 400 && isExcluded(path)) {
            return false;
        }
        return "GET".equals(request.getMethod()) || WRITING_METHODS.contains(request.getMethod());
    }

    private boolean isExcluded(String path) {
        for (String excludedPath : EXCLUDED_PATHS) {
            if (path.equals(excludedPath) || path.startsWith(excludedPath + "/")) {
                return true;
            }
        }
        return false;
    }

    private AdminSystemLogType type(String method, String path, int status) {
        if (LOGIN_PATH.equals(path)) {
            return AdminSystemLogType.LOGIN;
        }
        if (status >= 500) {
            return AdminSystemLogType.EXCEPTION;
        }
        return "GET".equals(method) ? AdminSystemLogType.ACCESS : AdminSystemLogType.OPERATION;
    }

    private Operator operator(
            HttpServletRequest request,
            String path,
            AuthenticatedPrincipal principal
    ) {
        if (LOGIN_PATH.equals(path)) {
            Object id = request.getAttribute(RequestLogContext.LOGIN_OPERATOR_ID_ATTRIBUTE);
            Long operatorId = id instanceof Number value ? value.longValue() : null;
            return new Operator(
                    operatorId,
                    attribute(request, RequestLogContext.LOGIN_OPERATOR_NAME_ATTRIBUTE)
            );
        }
        return new Operator(principal.subjectId(), principal.subjectName());
    }

    private ErrorDetails errorDetails(HttpServletRequest request, int status) {
        if (status < 400) {
            return new ErrorDetails("", "");
        }
        String code = attribute(request, RequestLogContext.ERROR_CODE_ATTRIBUTE);
        String message = attribute(request, RequestLogContext.ERROR_MESSAGE_ATTRIBUTE);
        if (!code.isBlank() || !message.isBlank()) {
            return new ErrorDetails(code, message);
        }
        ErrorCode fallback = switch (status) {
            case HttpServletResponse.SC_UNAUTHORIZED -> ErrorCode.AUTHENTICATION_REQUIRED;
            case HttpServletResponse.SC_FORBIDDEN -> ErrorCode.PERMISSION_DENIED;
            case HttpServletResponse.SC_NOT_FOUND -> ErrorCode.RESOURCE_NOT_FOUND;
            case HttpServletResponse.SC_BAD_REQUEST,
                 HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                 HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE -> ErrorCode.VALIDATION_FAILED;
            default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : null;
        };
        return fallback == null
                ? new ErrorDetails("", "")
                : new ErrorDetails(Integer.toString(fallback.code()), fallback.message());
    }

    private String module(String path) {
        String remainder = path.substring("/admin/".length());
        int slash = remainder.indexOf('/');
        String module = slash < 0 ? remainder : remainder.substring(0, slash);
        return module.toLowerCase(Locale.ROOT);
    }

    private String action(String method, String path, String routePattern) {
        if (LOGIN_PATH.equals(path)) {
            return "login";
        }
        return method + " " + (routePattern.isBlank() ? path : routePattern);
    }

    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private AuthenticatedPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? principal
                : null;
    }

    private String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value == null ? "" : value.toString();
    }

    private String clean(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int index = 0; index < value.length() && result.length() < maxLength; index++) {
            char character = value.charAt(index);
            result.append(Character.isISOControl(character) || isBidirectionalControl(character)
                    ? ' '
                    : character);
        }
        return result.toString().trim();
    }

    private boolean isBidirectionalControl(char character) {
        return character == '\u061C'
                || character == '\u200E'
                || character == '\u200F'
                || character >= '\u202A' && character <= '\u202E'
                || character >= '\u2066' && character <= '\u2069';
    }

    private void warnRecorderFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastFailureWarningAt.get();
        if (now - previous < FAILURE_WARNING_INTERVAL_MILLIS
                || !lastFailureWarningAt.compareAndSet(previous, now)) {
            return;
        }
        log.warn("Admin system log persistence failed: {}",
                exception.getClass().getSimpleName());
    }

    private record Operator(Long id, String username) {
    }

    private record ErrorDetails(String code, String message) {
    }
}
