package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.AfterSaleType;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEvidenceFileResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.dto.RefundOrderResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AppAfterSaleService {

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AfterSaleStatus.REQUESTED.name(),
            AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.REFUNDING.name(),
            AfterSaleStatus.REFUND_FAILED.name()
    );
    private static final Set<String> ALLOWED_ORDER_STATUSES = Set.of(OrderStatus.PAID.name(), OrderStatus.SHIPPED.name());
    private static final Set<String> ALLOWED_EVIDENCE_PURPOSES = Set.of("AFTER_SALE_IMAGE", "REFUND_EVIDENCE");

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageUsageService storageUsageService;

    public AppAfterSaleService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageUsageService storageUsageService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageUsageService = storageUsageService;
    }

    @Transactional
    public AfterSaleResponse apply(AuthenticatedPrincipal principal, Long orderId, AppAfterSaleApplyRequest request) {
        Long userId = requireAppUser(principal);
        AfterSaleType type = parseType(request == null ? null : request.afterSaleType());
        String reason = requireText(request == null ? null : request.reason(), 128);
        String description = normalizeText(request == null ? null : request.description(), 500);
        long requestedAmountCent = requirePositiveAmount(request == null ? null : request.requestedAmountCent());
        List<Long> evidenceFileIds = normalizeEvidenceFileIds(request == null ? null : request.evidenceFileIds());

        OrderRow order = findOwnedOrderForUpdate(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (requestedAmountCent > order.paidAmountCent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        rejectIfActiveAfterSaleExists(order.orderId());
        validateEvidenceFiles(userId, evidenceFileIds);

        Long afterSaleId = insertAfterSale(order, type, reason, description, requestedAmountCent);
        insertEvidence(afterSaleId, evidenceFileIds);
        addProtectedEvidenceUsage(afterSaleId, order.orderNo(), evidenceFileIds);
        return requireResponseForUser(afterSaleId, userId);
    }

    public List<AfterSaleResponse> listForOrder(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
        requireOwnedOrder(orderId, userId);
        return jdbcClient.sql("""
                        select asr.id,
                               asr.order_id,
                               o.order_no,
                               asr.user_id,
                               asr.after_sale_type,
                               asr.status,
                               asr.reason,
                               asr.description,
                               asr.requested_amount_cent,
                               asr.approved_amount_cent,
                               asr.audit_note,
                               asr.reviewed_by,
                               asr.reviewed_at,
                               asr.created_at
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        where asr.order_id = :orderId
                          and asr.user_id = :userId
                        order by asr.created_at desc, asr.id desc
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapAfterSale)
                .list()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public AfterSaleResponse detail(AuthenticatedPrincipal principal, Long afterSaleId) {
        Long userId = requireAppUser(principal);
        return requireResponseForUser(afterSaleId, userId);
    }

    private Long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private void requireOwnedOrder(Long orderId, Long userId) {
        jdbcClient.sql("""
                        select id
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
    }

    private java.util.Optional<OrderRow> findOwnedOrderForUpdate(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               user_id,
                               status,
                               paid_amount_cent
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        for update
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrder)
                .optional();
    }

    private void rejectIfActiveAfterSaleExists(Long orderId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where order_id = :orderId
                          and status in (:activeStatuses)
                        """)
                .param("orderId", orderId)
                .param("activeStatuses", ACTIVE_STATUSES)
                .query(Integer.class)
                .single();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void validateEvidenceFiles(Long userId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        List<EvidenceFileRow> rows = jdbcClient.sql("""
                        select id, purpose, visibility, status, uploaded_by_type, uploaded_by_id
                        from storage_file
                        where id in (:fileIds)
                        """)
                .param("fileIds", fileIds)
                .query(this::mapEvidenceFile)
                .list();
        if (rows.size() != fileIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        for (EvidenceFileRow row : rows) {
            if (!ALLOWED_EVIDENCE_PURPOSES.contains(row.purpose())
                    || !"PRIVATE".equals(row.visibility())
                    || !"ACTIVE".equals(row.status())
                    || !"APP".equals(row.uploadedByType())
                    || !userId.equals(row.uploadedById())) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
        }
    }

    private Long insertAfterSale(OrderRow order, AfterSaleType type, String reason, String description, long requestedAmountCent) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into after_sale_request
                            (order_id, user_id, after_sale_type, status, reason, description,
                             requested_amount_cent, created_at, updated_at)
                        values
                            (:orderId, :userId, :afterSaleType, :status, :reason, :description,
                             :requestedAmountCent, :createdAt, :updatedAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("orderId", order.orderId())
                        .addValue("userId", order.userId())
                        .addValue("afterSaleType", type.name())
                        .addValue("status", AfterSaleStatus.REQUESTED.name())
                        .addValue("reason", reason)
                        .addValue("description", description)
                        .addValue("requestedAmountCent", requestedAmountCent)
                        .addValue("createdAt", LocalDateTime.now())
                        .addValue("updatedAt", LocalDateTime.now()),
                keyHolder,
                new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return key.longValue();
    }

    private void insertEvidence(Long afterSaleId, List<Long> fileIds) {
        int sortOrder = 1;
        for (Long fileId : fileIds) {
            jdbcClient.sql("""
                            insert into after_sale_evidence
                                (after_sale_id, file_id, sort_order)
                            values
                                (:afterSaleId, :fileId, :sortOrder)
                            """)
                    .param("afterSaleId", afterSaleId)
                    .param("fileId", fileId)
                    .param("sortOrder", sortOrder++)
                    .update();
        }
    }

    private void addProtectedEvidenceUsage(Long afterSaleId, String orderNo, List<Long> fileIds) {
        int sortOrder = 1;
        for (Long fileId : fileIds) {
            storageUsageService.addProtectedUsage(
                    fileId,
                    StorageFileUsageType.AFTER_SALE_EVIDENCE,
                    StorageUsageOwnerType.AFTER_SALE,
                    afterSaleId,
                    "售后 #" + afterSaleId + " / 订单 " + orderNo,
                    "",
                    sortOrder++
            );
        }
    }

    private AfterSaleResponse requireResponseForUser(Long afterSaleId, Long userId) {
        AfterSaleRow row = jdbcClient.sql("""
                        select asr.id,
                               asr.order_id,
                               o.order_no,
                               asr.user_id,
                               asr.after_sale_type,
                               asr.status,
                               asr.reason,
                               asr.description,
                               asr.requested_amount_cent,
                               asr.approved_amount_cent,
                               asr.audit_note,
                               asr.reviewed_by,
                               asr.reviewed_at,
                               asr.created_at
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        where asr.id = :afterSaleId
                          and asr.user_id = :userId
                        """)
                .param("afterSaleId", afterSaleId)
                .param("userId", userId)
                .query(this::mapAfterSale)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return toResponse(row);
    }

    private AfterSaleResponse toResponse(AfterSaleRow row) {
        return new AfterSaleResponse(
                row.id(),
                row.orderId(),
                row.orderNo(),
                row.userId(),
                row.afterSaleType(),
                row.status(),
                row.reason(),
                row.description(),
                row.requestedAmountCent(),
                row.approvedAmountCent(),
                row.auditNote(),
                row.reviewedBy(),
                row.reviewedAt(),
                row.createdAt(),
                evidenceFileIds(row.id()),
                evidenceFiles(row.id()),
                refundOrder(row.id())
        );
    }

    private List<Long> evidenceFileIds(Long afterSaleId) {
        return jdbcClient.sql("""
                        select file_id
                        from after_sale_evidence
                        where after_sale_id = :afterSaleId
                        order by sort_order asc, id asc
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .list();
    }

    private List<AfterSaleEvidenceFileResponse> evidenceFiles(Long afterSaleId) {
        return jdbcClient.sql("""
                        select ase.file_id,
                               sf.original_filename,
                               sf.content_type,
                               sf.size_bytes,
                               sf.visibility,
                               sf.purpose,
                               sf.status
                        from after_sale_evidence ase
                        join storage_file sf on sf.id = ase.file_id
                        where ase.after_sale_id = :afterSaleId
                        order by ase.sort_order asc, ase.id asc
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapEvidenceFileResponse)
                .list();
    }

    private AfterSaleEvidenceFileResponse mapEvidenceFileResponse(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleEvidenceFileResponse(
                rs.getLong("file_id"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("visibility"),
                rs.getString("purpose"),
                rs.getString("status")
        );
    }

    private RefundOrderResponse refundOrder(Long afterSaleId) {
        return jdbcClient.sql("""
                        select id,
                               after_sale_id,
                               order_id,
                               payment_order_id,
                               out_refund_no,
                               refund_id,
                               refund_amount_cent,
                               status,
                               callback_status,
                               last_error_code,
                               last_error_message,
                               requested_at,
                               success_at
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id desc
                        limit 1
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapRefundOrder)
                .optional()
                .orElse(null);
    }

    private List<Long> normalizeEvidenceFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<Long> normalized = new ArrayList<>(new LinkedHashSet<>(fileIds));
        if (normalized.stream().anyMatch(value -> value == null || value < 1)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return normalized;
    }

    private AfterSaleType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return AfterSaleType.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private long requirePositiveAmount(Long value) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private String requireText(String value, int maxLength) {
        String normalized = normalizeText(value, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String normalizeText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("status"),
                rs.getLong("paid_amount_cent")
        );
    }

    private EvidenceFileRow mapEvidenceFile(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceFileRow(
                rs.getLong("id"),
                rs.getString("purpose"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("uploaded_by_type"),
                rs.getLong("uploaded_by_id")
        );
    }

    private AfterSaleRow mapAfterSale(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("after_sale_type"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("description"),
                rs.getLong("requested_amount_cent"),
                nullableLong(rs, "approved_amount_cent"),
                rs.getString("audit_note"),
                nullableLong(rs, "reviewed_by"),
                rs.getObject("reviewed_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private RefundOrderResponse mapRefundOrder(ResultSet rs, int rowNum) throws SQLException {
        return new RefundOrderResponse(
                rs.getLong("id"),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getLong("payment_order_id"),
                rs.getString("out_refund_no"),
                rs.getString("refund_id"),
                rs.getLong("refund_amount_cent"),
                rs.getString("status"),
                rs.getString("callback_status"),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("success_at", LocalDateTime.class)
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record OrderRow(Long orderId, String orderNo, Long userId, String status, long paidAmountCent) {
    }

    private record EvidenceFileRow(Long id, String purpose, String visibility, String status, String uploadedByType, Long uploadedById) {
    }

    private record AfterSaleRow(
            Long id,
            Long orderId,
            String orderNo,
            Long userId,
            String afterSaleType,
            String status,
            String reason,
            String description,
            Long requestedAmountCent,
            Long approvedAmountCent,
            String auditNote,
            Long reviewedBy,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt
    ) {
    }
}
