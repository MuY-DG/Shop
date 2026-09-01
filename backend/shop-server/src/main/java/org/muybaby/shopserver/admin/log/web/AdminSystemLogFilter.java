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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AdminSystemLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminSystemLogFilter.class);
    private static final String LOGIN_PATH = "/admin/auth/login";
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
    private final long slowRequestThresholdMillis;
    private final AtomicLong lastFailureWarningAt = new AtomicLong();

    public AdminSystemLogFilter(
            AdminSystemLogRecorder recorder,
            ClientIpResolver clientIpResolver,
            @Value("${shop.admin-system-log.slow-request-threshold:1s}") java.time.Duration slowRequestThreshold
    ) {
        this.recorder = recorder;
        this.clientIpResolver = clientIpResolver;
        this.slowRequestThresholdMillis = Math.max(1L, slowRequestThreshold.toMillis());
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
        long durationMillis = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        if (!shouldRecord(request, path, principal, status, durationMillis)) {
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
        AuditSemantics semantics = semantics(
                type,
                result,
                request.getMethod(),
                path,
                routePattern,
                status,
                error
        );

        AdminSystemLogRecord record = new AdminSystemLogRecord(
                type,
                result,
                level,
                clean(semantics.eventCode(), 128),
                clean(semantics.summary(), 255),
                clean(semantics.targetType(), 64),
                clean(semantics.targetId(), 128),
                clean(semantics.relatedTargetType(), 64),
                clean(semantics.relatedTargetId(), 128),
                operator.id(),
                clean(operator.username(), 64),
                clean(module(path), 64),
                clean(action(request.getMethod(), path, routePattern), 128),
                clean(request.getMethod(), 10),
                clean(path, 255),
                clean(routePattern, 255),
                status,
                durationMillis,
                clean(clientIpResolver.resolve(request), 45),
                clean(request.getHeader("User-Agent"), 255),
                clean(attribute(request, RequestIdFilter.REQUEST_ID_ATTRIBUTE), 128),
                clean(error.code(), 64),
                clean(error.providerCode(), 64),
                clean(error.message(), 255),
                LocalDateTime.now(java.time.ZoneOffset.UTC)
        );
        recorder.record(record);
    }

    private boolean shouldRecord(
            HttpServletRequest request,
            String path,
            AuthenticatedPrincipal principal,
            int status,
            long durationMillis
    ) {
        if (!path.startsWith("/admin/")) {
            return false;
        }
        if (LOGIN_PATH.equals(path)) {
            return request.getAttribute(RequestLogContext.LOGIN_OPERATOR_NAME_ATTRIBUTE) != null;
        }
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            return false;
        }
        if (status < 400 && isExcluded(path)) {
            return false;
        }
        if (WRITING_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        return status >= 400 || durationMillis >= slowRequestThresholdMillis;
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
            return AdminSystemLogType.SECURITY;
        }
        if (status >= 500) {
            return AdminSystemLogType.EXCEPTION;
        }
        if (status == HttpServletResponse.SC_FORBIDDEN || isSecurityOperation(method, path)) {
            return AdminSystemLogType.SECURITY;
        }
        return "GET".equals(method) ? AdminSystemLogType.REQUEST : AdminSystemLogType.OPERATION;
    }

    private boolean isSecurityOperation(String method, String path) {
        if (!WRITING_METHODS.contains(method)) {
            return false;
        }
        return path.equals("/admin/auth/logout")
                || path.equals("/admin/auth/logout-all")
                || path.equals("/admin/auth/password")
                || path.equals("/admin/auth/profile")
                || path.startsWith("/admin/auth/sessions/")
                || path.contains("/sessions/");
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
            return new ErrorDetails("", "", "");
        }
        String code = attribute(request, RequestLogContext.ERROR_CODE_ATTRIBUTE);
        String message = attribute(request, RequestLogContext.ERROR_MESSAGE_ATTRIBUTE);
        String providerCode = attribute(request, RequestLogContext.PROVIDER_ERROR_CODE_ATTRIBUTE);
        if (!code.isBlank() || !message.isBlank()) {
            return new ErrorDetails(code, message, providerCode);
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
                ? new ErrorDetails("", "", providerCode)
                : new ErrorDetails(Integer.toString(fallback.code()), fallback.message(), providerCode);
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

    private AuditSemantics semantics(
            AdminSystemLogType type,
            AdminSystemLogResult result,
            String method,
            String path,
            String routePattern,
            int status,
            ErrorDetails error
    ) {
        if (type == AdminSystemLogType.SECURITY && LOGIN_PATH.equals(path)) {
            String summary = result == AdminSystemLogResult.SUCCESS
                    ? "管理员登录成功"
                    : appendError("管理员登录失败", error);
            return new AuditSemantics(
                    result == AdminSystemLogResult.SUCCESS
                            ? "ADMIN_LOGIN_SUCCESS"
                            : "ADMIN_LOGIN_FAILURE",
                    summary,
                    "admin-account",
                    "",
                    "",
                    ""
            );
        }

        Target target = target(path, routePattern);
        String moduleLabel = moduleLabel(module(path));
        String subject = target.id().isBlank()
                ? moduleLabel
                : moduleLabel + "（" + target.id() + "）";
        String eventCode = eventCode(method, path, routePattern, status);

        if (type == AdminSystemLogType.EXCEPTION) {
            return new AuditSemantics(
                    eventCode,
                    appendError(subject + "请求发生服务异常", error),
                    target.type(),
                    target.id(),
                    target.relatedType(),
                    target.relatedId()
            );
        }
        if (type == AdminSystemLogType.REQUEST) {
            String summary = status >= 400
                    ? appendError(subject + "请求失败", error)
                    : subject + "慢请求";
            return new AuditSemantics(
                    eventCode, summary, target.type(), target.id(),
                    target.relatedType(), target.relatedId());
        }

        if (type == AdminSystemLogType.SECURITY) {
            String summary = switch (path) {
                case "/admin/auth/logout" -> "退出当前登录设备";
                case "/admin/auth/logout-all" -> "退出全部登录设备";
                case "/admin/auth/password" -> "修改登录密码";
                case "/admin/auth/profile" -> "修改个人资料";
                default -> status == HttpServletResponse.SC_FORBIDDEN
                        ? "权限校验失败"
                        : "执行安全操作";
            };
            summary = result == AdminSystemLogResult.SUCCESS
                    ? summary + "成功"
                    : appendError(summary, error);
            return new AuditSemantics(
                    eventCode, summary, target.type(), target.id(),
                    target.relatedType(), target.relatedId());
        }

        String summary = switch (method) {
            case "POST" -> "提交" + subject;
            case "PUT", "PATCH" -> "修改" + subject;
            case "DELETE" -> "删除" + subject;
            default -> "操作" + subject;
        };
        if (result == AdminSystemLogResult.FAILURE) {
            summary = appendError(summary + "失败", error);
        } else {
            summary += "成功";
        }
        return new AuditSemantics(
                eventCode, summary, target.type(), target.id(),
                target.relatedType(), target.relatedId());
    }

    private String appendError(String summary, ErrorDetails error) {
        if (error == null || error.message().isBlank()) {
            return summary;
        }
        return summary + "：" + error.message();
    }

    private String eventCode(String method, String path, String routePattern, int status) {
        String source = method + "_" + (routePattern.isBlank() ? path : routePattern);
        String normalized = source
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        return status >= 500 ? normalized + "_EXCEPTION" : normalized;
    }

    private Target target(String path, String routePattern) {
        if (routePattern == null || routePattern.isBlank()) {
            return new Target(module(path), "", "", "");
        }
        String[] actualSegments = path.split("/");
        String[] patternSegments = routePattern.split("/");
        int length = Math.min(actualSegments.length, patternSegments.length);
        Target preferred = null;
        Target last = null;
        for (int index = 0; index < length; index++) {
            String patternSegment = patternSegments[index];
            if (patternSegment.startsWith("{") && patternSegment.endsWith("}")) {
                String type = patternSegment.substring(1, patternSegment.length() - 1);
                Target candidate = new Target(type, actualSegments[index], "", "");
                last = candidate;
                if ("afterSaleId".equals(type)) {
                    preferred = candidate;
                }
            }
        }
        Target primary = preferred == null ? last : preferred;
        if (primary == null) {
            return new Target(module(path), "", "", "");
        }
        if (last != null && (!last.type().equals(primary.type()) || !last.id().equals(primary.id()))) {
            return new Target(primary.type(), primary.id(), last.type(), last.id());
        }
        return primary;
    }

    private String moduleLabel(String module) {
        return switch (module) {
            case "auth" -> "账号与会话";
            case "system" -> "系统配置";
            case "product" -> "商品";
            case "assets", "asset-folders" -> "素材";
            case "operations" -> "运营统计";
            case "customer-service" -> "在线客服";
            case "customer-service-management" -> "客服管理";
            case "marketing" -> "营销";
            case "home" -> "首页装修";
            case "after-sales", "aftersale" -> "售后";
            case "pay", "payment" -> "支付";
            case "orders", "order" -> "订单";
            case "logistics" -> "物流";
            case "finance" -> "财务";
            case "data-cleanup" -> "数据清理";
            case "compliance" -> "合规";
            default -> module.isBlank() ? "后台" : module;
        };
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

    private record AuditSemantics(
            String eventCode,
            String summary,
            String targetType,
            String targetId,
            String relatedTargetType,
            String relatedTargetId
    ) {
    }

    private record Target(String type, String id, String relatedType, String relatedId) {
    }

    private record ErrorDetails(String code, String message, String providerCode) {
    }
}
