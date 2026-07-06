package org.muybaby.shopserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final PathTokenKindResolver pathTokenKindResolver;
    private final OpaqueTokenService opaqueTokenService;

    public TokenAuthenticationFilter(PathTokenKindResolver pathTokenKindResolver, OpaqueTokenService opaqueTokenService) {
        this.pathTokenKindResolver = pathTokenKindResolver;
        this.opaqueTokenService = opaqueTokenService;
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
                    Optional<TokenSession> session = opaqueTokenService.lookupAccessToken(token, requiredKind.get());
                    if (session.isPresent()) {
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
