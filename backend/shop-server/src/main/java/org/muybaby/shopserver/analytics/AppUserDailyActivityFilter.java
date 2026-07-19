package org.muybaby.shopserver.analytics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
public class AppUserDailyActivityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AppUserDailyActivityFilter.class);

    private final AppUserDailyActivityService activityService;

    public AppUserDailyActivityFilter(AppUserDailyActivityService activityService) {
        this.activityService = activityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedPrincipal principal = authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedPrincipal value ? value : null;
        filterChain.doFilter(request, response);
        if (principal == null || principal.kind() != TokenKind.APP || !isBusinessRequest(request)) {
            return;
        }
        try {
            activityService.record(principal.subjectId(), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Failed to record app daily activity for user {}", principal.subjectId(), ex);
        }
    }

    private boolean isBusinessRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.startsWith("/app/")
                && !path.startsWith("/app/auth/")
                && !path.equals("/app/health")
                && !path.equals("/app/analytics/events/batch");
    }
}
