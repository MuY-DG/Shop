package org.muybaby.shopserver.accountcancellation.service;

import org.muybaby.shopserver.accountcancellation.dto.AdminAccountCancellationQuery;
import org.muybaby.shopserver.accountcancellation.dto.AdminAccountCancellationResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Service
public class AdminAccountCancellationService {

    private static final Set<String> MINI_PROGRAM_ENVS = Set.of("develop", "trial", "release");

    private final JdbcClient jdbcClient;

    public AdminAccountCancellationService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminAccountCancellationResponse> page(AdminAccountCancellationQuery query) {
        AdminAccountCancellationQuery normalized = query == null
                ? new AdminAccountCancellationQuery(null, null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        Long userId = normalizeUserId(normalized.userId());
        String miniProgramEnv = normalizeEnv(normalized.miniProgramEnv());

        long total = jdbcClient.sql("""
                        select count(*)
                        from app_user_account_cancellation cancellation
                        where (:userId is null or cancellation.user_id = :userId)
                          and (:miniProgramEnv = '' or cancellation.mini_program_env = :miniProgramEnv)
                        """)
                .param("userId", userId)
                .param("miniProgramEnv", miniProgramEnv)
                .query(Long.class)
                .single();
        List<AdminAccountCancellationResponse> records = jdbcClient.sql("""
                        select cancellation.id, cancellation.user_id,
                               cancellation.legal_document_revision_id,
                               cancellation.notice_version, cancellation.notice_content_sha256,
                               cancellation.channel, cancellation.mini_program_env,
                               cancellation.identity_verified_at,
                               cancellation.deleted_data_categories,
                               cancellation.retained_data_categories,
                               cancellation.completed_at
                        from app_user_account_cancellation cancellation
                        where (:userId is null or cancellation.user_id = :userId)
                          and (:miniProgramEnv = '' or cancellation.mini_program_env = :miniProgramEnv)
                        order by cancellation.completed_at desc, cancellation.id desc
                        limit :size offset :offset
                        """)
                .param("userId", userId)
                .param("miniProgramEnv", miniProgramEnv)
                .param("size", size)
                .param("offset", offset)
                .query(this::map)
                .list();
        return PageResult.of(records, total, current, size);
    }

    private AdminAccountCancellationResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new AdminAccountCancellationResponse(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getLong("legal_document_revision_id"),
                rs.getString("notice_version"),
                rs.getString("notice_content_sha256"),
                rs.getString("channel"),
                rs.getString("mini_program_env"),
                rs.getObject("identity_verified_at", LocalDateTime.class),
                categories(rs.getString("deleted_data_categories")),
                categories(rs.getString("retained_data_categories")),
                rs.getObject("completed_at", LocalDateTime.class)
        );
    }

    private Long normalizeUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return userId;
    }

    private String normalizeEnv(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        if (!MINI_PROGRAM_ENVS.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private List<String> categories(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
