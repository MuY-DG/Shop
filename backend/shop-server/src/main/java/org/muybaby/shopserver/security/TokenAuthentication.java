package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class TokenAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public TokenAuthentication(TokenSession session) {
        super(session.permissions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList());
        this.principal = new AuthenticatedPrincipal(
                session.kind(),
                session.subjectId(),
                session.subjectName(),
                session.roles(),
                session.permissions());
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }
}
