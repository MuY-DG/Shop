package org.muybaby.shopserver.content.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public class DisplayConfigRepository {
    private final JdbcClient jdbcClient;

    public DisplayConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DisplayConfigRow current() {
        return jdbcClient.sql("SELECT display_name, revision, updated_at FROM app_display_config WHERE id = 1")
                .query((rs, rowNum) -> new DisplayConfigRow(
                        rs.getString("display_name"), rs.getLong("revision"),
                        rs.getTimestamp("updated_at").toLocalDateTime()))
                .single();
    }

    public boolean update(String displayName, long expectedVersion, Long adminId, LocalDateTime updatedAt) {
        return jdbcClient.sql("""
                        UPDATE app_display_config
                        SET display_name = :displayName, revision = revision + 1,
                            updated_by = :adminId, updated_at = :updatedAt
                        WHERE id = 1 AND revision = :expectedVersion
                        """)
                .param("displayName", displayName)
                .param("adminId", adminId)
                .param("updatedAt", updatedAt)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    public record DisplayConfigRow(String displayName, long version, LocalDateTime updatedAt) {
    }
}
