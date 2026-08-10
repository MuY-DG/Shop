package org.muybaby.shopserver.auth.service;

import org.muybaby.shopserver.auth.dto.AdminPasswordChangeRequest;
import org.muybaby.shopserver.auth.dto.AdminProfileUpdateRequest;
import org.muybaby.shopserver.auth.session.AdminSessionPolicyChangedEvent;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AdminSelfService {

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    public AdminSelfService(
            JdbcClient jdbcClient,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void updateProfile(Long userId, AdminProfileUpdateRequest request) {
        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET display_name = :displayName,
                            email = :email,
                            updated_at = :now
                        WHERE id = :userId
                          AND status = 'ENABLED'
                        """)
                .param("displayName", request.displayName().strip())
                .param("email", normalize(request.email()))
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
    }

    @Transactional
    public void changePassword(Long userId, AdminPasswordChangeRequest request) {
        PasswordState current = jdbcClient.sql("""
                        SELECT password_hash, max_sessions
                        FROM admin_user
                        WHERE id = :userId
                          AND status = 'ENABLED'
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new PasswordState(
                        rs.getString("password_hash"),
                        rs.getInt("max_sessions")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE));

        if (!passwordEncoder.matches(request.currentPassword(), current.passwordHash())) {
            throw new BusinessException(ErrorCode.ADMIN_CURRENT_PASSWORD_INVALID);
        }

        int updated = jdbcClient.sql("""
                        UPDATE admin_user
                        SET password_hash = :passwordHash,
                            auth_version = auth_version + 1,
                            updated_at = :now
                        WHERE id = :userId
                        """)
                .param("passwordHash", passwordEncoder.encode(request.newPassword()))
                .param("now", LocalDateTime.now(ZoneOffset.UTC))
                .param("userId", userId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ADMIN_USER_UNAVAILABLE);
        }
        eventPublisher.publishEvent(new AdminSessionPolicyChangedEvent(
                userId,
                true,
                current.maxSessions()
        ));
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private record PasswordState(String passwordHash, int maxSessions) {
    }
}
