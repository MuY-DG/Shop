package org.muybaby.shopserver.security;

import org.muybaby.shopserver.auth.token.TokenSession;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class TokenAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public TokenAuthentication(TokenSession session) {
        super(authorities(session));
        this.principal = new AuthenticatedPrincipal(
                session.sessionId(),
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

    private static Collection<? extends GrantedAuthority> authorities(TokenSession session) {
        Set<String> authorities = new LinkedHashSet<>(session.permissions());
        for (String role : session.roles()) {
            authorities.add(role);
            if (role.startsWith("R_") && role.length() > 2) {
                authorities.add("ROLE_" + role.substring(2));
            }
        }
        return authorities.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
