package org.muybaby.shopserver.auth.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AdminLastLoginWriter {

    private final JdbcClient jdbcClient;

    public AdminLastLoginWriter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void update(long userId, LocalDateTime lastLoginAt) {
        jdbcClient.sql("""
                        update admin_user
                        set last_login_at = :lastLoginAt,
                            updated_at = :lastLoginAt
                        where id = :userId
                          and status = 'ENABLED'
                        """)
                .param("lastLoginAt", lastLoginAt)
                .param("userId", userId)
                .update();
    }
}
