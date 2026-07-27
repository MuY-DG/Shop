package org.muybaby.shopserver.auth.session;

import org.muybaby.shopserver.auth.dto.AdminSessionResponse;
import org.muybaby.shopserver.auth.token.AccountSession;
import org.muybaby.shopserver.auth.token.OpaqueTokenService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class AdminSessionManagementService {

    private final JdbcClient jdbcClient;
    private final OpaqueTokenService opaqueTokenService;
    private final ApplicationEventPublisher eventPublisher;

    public AdminSessionManagementService(
            JdbcClient jdbcClient,
            OpaqueTokenService opaqueTokenService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.opaqueTokenService = opaqueTokenService;
        this.eventPublisher = eventPublisher;
    }

    public List<AdminSessionResponse> sessions(Long userId, String currentSessionId) {
        requireAdminUser(userId);
        return opaqueTokenService.listSessions(TokenKind.ADMIN, userId).stream()
                .sorted(Comparator.comparing(AccountSession::lastSeenAt).reversed())
                .map(session -> toResponse(session, session.sessionId().equals(currentSessionId)))
                .toList();
    }

    public void revokeSession(Long userId, String sessionId) {
        requireAdminUser(userId);
        opaqueTokenService.revokeSubjectSession(TokenKind.ADMIN, userId, sessionId);
    }

    @Transactional
    public void revokeAllSessions(Long userId) {
        Integer maxSessions = jdbcClient.sql("""
                        SELECT max_sessions
                        FROM admin_user
                        WHERE id = :userId
                        """)
                .param("userId", userId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));

        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET auth_version = auth_version + 1,
                            updated_at = :now
                        WHERE id = :userId
                        """)
                .param("now", LocalDateTime.now())
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        eventPublisher.publishEvent(new AdminSessionPolicyChangedEvent(userId, true, maxSessions));
    }

    private void requireAdminUser(Long userId) {
        long count = jdbcClient.sql("SELECT COUNT(*) FROM admin_user WHERE id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    private AdminSessionResponse toResponse(AccountSession session, boolean current) {
        AdminDeviceDescription device = AdminDeviceDescription.fromUserAgent(session.userAgent());
        return new AdminSessionResponse(
                session.sessionId(),
                device.deviceName(),
                device.browser(),
                device.os(),
                session.ipAddress(),
                session.userAgent(),
                session.loginAt(),
                session.lastSeenAt(),
                current
        );
    }
}
