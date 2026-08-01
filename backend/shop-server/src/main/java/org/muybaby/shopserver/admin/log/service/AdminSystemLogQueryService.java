package org.muybaby.shopserver.admin.log.service;

import org.muybaby.shopserver.admin.log.AdminSystemLogResult;
import org.muybaby.shopserver.admin.log.AdminSystemLogType;
import org.muybaby.shopserver.admin.log.dto.AdminSystemLogQuery;
import org.muybaby.shopserver.admin.log.dto.AdminSystemLogResponse;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AdminSystemLogQueryService {

    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final LocalDateTime MYSQL_TIMESTAMP_MIN =
            LocalDateTime.of(1970, 1, 2, 0, 0);
    private static final LocalDateTime MYSQL_TIMESTAMP_MAX =
            LocalDateTime.of(2038, 1, 18, 0, 0);

    private final JdbcClient jdbcClient;

    public AdminSystemLogQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResult<AdminSystemLogResponse> page(AdminSystemLogQuery query) {
        Filters filters = filters(query);
        long current = normalizeCurrent(query == null ? null : query.current());
        long size = normalizeSize(query == null ? null : query.size());
        long offset = offset(current, size);
        String whereClause = whereClause(filters);

        long total = bindFilters(jdbcClient.sql("""
                        select count(*)
                        from admin_system_log log
                        """ + whereClause), filters)
                .query(Long.class)
                .single();

        List<AdminSystemLogResponse> records = bindFilters(jdbcClient.sql("""
                        select id, log_type, result, level, operator_id, operator_name,
                               module, action, request_method, request_path, route_pattern,
                               http_status, duration_ms, client_ip, user_agent, request_id,
                               error_code, error_message, occurred_at
                        from admin_system_log log
                        """ + whereClause + """
                        order by log.occurred_at desc, log.id desc
                        limit :size offset :offset
                        """), filters)
                .param("size", size)
                .param("offset", offset)
                .query(this::map)
                .list();

        return PageResult.of(records, total, current, size);
    }

    private String whereClause(Filters filters) {
        StringBuilder where = new StringBuilder("""
                        where (:type = '' or log.log_type = :type)
                          and (:result = '' or log.result = :result)
                          and (:module = '' or log.module = :module)
                          and (:operator = ''
                               or lower(log.operator_name) like lower(:operatorPattern) escape '!'
                               or (:operatorIdFilter = true and log.operator_id = :operatorId))
                          and (:clientIp = '' or log.client_ip = :clientIp)
                          and (:requestId = '' or log.request_id = :requestId)
                        """);
        if (filters.occurredStart() != null) {
            where.append(" and log.occurred_at >= :occurredStart\n");
        }
        if (filters.occurredEnd() != null) {
            where.append(" and log.occurred_at <= :occurredEnd\n");
        }
        return where.toString();
    }

    private JdbcClient.StatementSpec bindFilters(JdbcClient.StatementSpec statement, Filters filters) {
        JdbcClient.StatementSpec bound = statement
                .param("type", filters.type())
                .param("result", filters.result())
                .param("module", filters.module())
                .param("operator", filters.operator())
                .param("operatorPattern", "%" + escapeLike(filters.operator()) + "%")
                .param("operatorIdFilter", filters.operatorId() != null)
                .param("operatorId", filters.operatorId() == null ? -1L : filters.operatorId())
                .param("clientIp", filters.clientIp())
                .param("requestId", filters.requestId());
        if (filters.occurredStart() != null) {
            bound = bound.param("occurredStart", filters.occurredStart());
        }
        if (filters.occurredEnd() != null) {
            bound = bound.param("occurredEnd", filters.occurredEnd());
        }
        return bound;
    }

    private Filters filters(AdminSystemLogQuery query) {
        AdminSystemLogQuery normalized = query == null
                ? new AdminSystemLogQuery(null, null, null, null, null, null, null, null, null, null)
                : query;
        String type = enumName(normalized.type(), AdminSystemLogType.class);
        String result = enumName(normalized.result(), AdminSystemLogResult.class);
        String operator = normalize(normalized.operator());
        Long operatorId = parsePositiveLong(operator);
        LocalDateTime start = normalized.occurredStartUtc();
        LocalDateTime end = normalized.occurredEndUtc();
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (outsideTimestampRange(start) || outsideTimestampRange(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new Filters(
                type,
                result,
                normalize(normalized.module()).toLowerCase(Locale.ROOT),
                operator,
                operatorId,
                normalize(normalized.clientIp()),
                normalize(normalized.requestId()),
                start,
                end
        );
    }

    private <E extends Enum<E>> String enumName(String value, Class<E> enumType) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Long parsePositiveLong(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long normalizeCurrent(Long current) {
        return current == null || current < 1L ? 1L : current;
    }

    private long normalizeSize(Long size) {
        if (size == null || size < 1L) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private long offset(long current, long size) {
        try {
            return Math.multiplyExact(current - 1L, size);
        } catch (ArithmeticException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean outsideTimestampRange(LocalDateTime value) {
        return value != null
                && (value.isBefore(MYSQL_TIMESTAMP_MIN) || value.isAfter(MYSQL_TIMESTAMP_MAX));
    }

    private String escapeLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private AdminSystemLogResponse map(ResultSet rs, int rowNum) throws SQLException {
        return new AdminSystemLogResponse(
                rs.getLong("id"),
                rs.getString("log_type"),
                rs.getString("level"),
                rs.getString("result"),
                rs.getString("module"),
                rs.getString("action"),
                rs.getObject("operator_id", Long.class),
                rs.getString("operator_name"),
                rs.getString("request_method"),
                rs.getString("request_path"),
                rs.getString("route_pattern"),
                rs.getString("request_id"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getInt("http_status"),
                rs.getLong("duration_ms"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getObject("occurred_at", LocalDateTime.class)
        );
    }

    private record Filters(
            String type,
            String result,
            String module,
            String operator,
            Long operatorId,
            String clientIp,
            String requestId,
            LocalDateTime occurredStart,
            LocalDateTime occurredEnd
    ) {
    }
}
