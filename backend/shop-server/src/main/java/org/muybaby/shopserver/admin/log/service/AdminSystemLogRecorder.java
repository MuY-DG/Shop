package org.muybaby.shopserver.admin.log.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class AdminSystemLogRecorder {


    private final JdbcClient jdbcClient;

    public AdminSystemLogRecorder(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminSystemLogRecord record) {
        Objects.requireNonNull(record, "record");
        jdbcClient.sql("""
                        insert into admin_system_log (
                            log_type, result, level, operator_id, operator_name,
                            module, action, request_method, request_path, route_pattern,
                            http_status, duration_ms, client_ip, user_agent, request_id,
                            error_code, error_message, occurred_at
                        )
                        values (
                            :logType, :result, :level, :operatorId, :operatorName,
                            :module, :action, :requestMethod, :requestPath, :routePattern,
                            :httpStatus, :durationMs, :clientIp, :userAgent, :requestId,
                            :errorCode, :errorMessage, :occurredAt
                        )
                        """)
                .param("logType", Objects.requireNonNull(record.type(), "type").name())
                .param("result", Objects.requireNonNull(record.result(), "result").name())
                .param("level", Objects.requireNonNull(record.level(), "level").name())
                .param("operatorId", record.operatorUserId(), Types.BIGINT)
                .param("operatorName", truncate(record.operatorUsername(), 64))
                .param("module", truncate(record.module(), 64))
                .param("action", truncate(record.action(), 128))
                .param("requestMethod", truncate(record.requestMethod(), 10))
                .param("requestPath", truncate(record.requestPath(), 255))
                .param("routePattern", truncate(record.requestPattern(), 255))
                .param("httpStatus", record.statusCode())
                .param("durationMs", Math.max(record.durationMs(), 0L))
                .param("clientIp", truncate(record.clientIp(), 45))
                .param("userAgent", truncate(record.userAgent(), 255))
                .param("requestId", truncate(record.requestId(), 128))
                .param("errorCode", truncate(record.errorCode(), 64))
                .param("errorMessage", truncate(record.errorMessage(), 255))
                .param(
                        "occurredAt",
                        Objects.requireNonNullElseGet(
                                record.createdAt(),
                                () -> LocalDateTime.now(java.time.ZoneOffset.UTC)
                        )
                )
                .update();
    }

    private String truncate(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
