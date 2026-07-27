package org.muybaby.shopserver.auth.session;

import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminSessionLimitReconciler {

    private final JdbcClient jdbcClient;
    private final OpaqueTokenService opaqueTokenService;

    public AdminSessionLimitReconciler(
            JdbcClient jdbcClient,
            OpaqueTokenService opaqueTokenService
    ) {
        this.jdbcClient = jdbcClient;
        this.opaqueTokenService = opaqueTokenService;
    }

    public List<Long> limitedUserIds() {
        return jdbcClient.sql("""
                        SELECT id
                        FROM admin_user
                        WHERE max_sessions > 0
                        ORDER BY id
                        """)
                .query(Long.class)
                .list();
    }

    /**
     * Enforces the current database policy, rather than an event snapshot. Holding the
     * user row lock while trimming orders this operation against concurrent policy updates,
     * so a delayed retry cannot over-trim after the limit has been raised.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileUser(Long userId) {
        Integer maxSessions = jdbcClient.sql("""
                        SELECT max_sessions
                        FROM admin_user
                        WHERE id = :userId
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (maxSessions == null || maxSessions == 0) {
            return;
        }
        opaqueTokenService.trimSubjectSessions(TokenKind.ADMIN, userId, maxSessions);
    }
}
