package org.muybaby.shopserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.admin.rbac.service.AdminAuthorization;
import org.muybaby.shopserver.admin.rbac.service.AdminRbacService;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.muybaby.shopserver.user.service.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final PathTokenKindResolver pathTokenKindResolver;
    private final OpaqueTokenService opaqueTokenService;
    private final AdminRbacService adminRbacService;
    private final AppUserService appUserService;

    public TokenAuthenticationFilter(
            PathTokenKindResolver pathTokenKindResolver,
            OpaqueTokenService opaqueTokenService,
            AdminRbacService adminRbacService,
            AppUserService appUserService
    ) {
        this.pathTokenKindResolver = pathTokenKindResolver;
        this.opaqueTokenService = opaqueTokenService;
        this.adminRbacService = adminRbacService;
        this.appUserService = appUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean authenticationSet = false;
        try {
            Optional<TokenKind> requiredKind = pathTokenKindResolver.resolve(requestPath(request));
            if (requiredKind.isPresent()) {
                SecurityContextHolder.clearContext();
                String token = bearerToken(request);
                if (token != null) {
                    Optional<TokenSession> session = opaqueTokenService.lookupAccessToken(token, requiredKind.get())
                            .flatMap(this::withLiveAuthorization);
                    if (session.isPresent()) {
                        touchAdminSessionBestEffort(session.get());
                        SecurityContextHolder.getContext().setAuthentication(new TokenAuthentication(session.get()));
                        authenticationSet = true;
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            if (authenticationSet) {
                SecurityContextHolder.clearContext();
            }
        }
    }

    private void touchAdminSessionBestEffort(TokenSession session) {
        if (session.kind() != TokenKind.ADMIN) {
            return;
        }
        try {
            opaqueTokenService.touchSession(session.sessionId(), TokenKind.ADMIN);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to update admin session activity for {}", session.sessionId(), ex);
        }
    }

    private Optional<TokenSession> withLiveAuthorization(TokenSession storedSession) {
        if (storedSession.kind() == TokenKind.APP) {
            return appUserService.findEnabledSessionState(storedSession.subjectId())
                    .filter(state -> storedSession.authVersion() == state.authVersion())
                    .map(state -> storedSession);
        }

        return adminRbacService.findEnabledAuthorizationByUserId(storedSession.subjectId())
                .filter(authorization -> storedSession.authVersion() == authorization.authVersion())
                .map(authorization -> liveAdminSession(storedSession, authorization));
    }

    private TokenSession liveAdminSession(TokenSession storedSession, AdminAuthorization authorization) {
        return new TokenSession(
                storedSession.sessionId(),
                storedSession.generationId(),
                storedSession.kind(),
                authorization.userId(),
                authorization.username(),
                authorization.roles(),
                authorization.permissions(),
                storedSession.authVersion(),
                storedSession.issuedAt()
        );
    }

    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
