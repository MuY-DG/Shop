package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.dto.AfterSaleEvidenceFileResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.RefundOrderResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 小程序端售后读路径：列表、详情、按订单查询统一走这里。 */
@Service
public class AppAfterSaleQueryService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbcClient;
    private final AfterSaleV2ReadService afterSaleV2ReadService;

    public AppAfterSaleQueryService(
            JdbcClient jdbcClient,
            AfterSaleV2ReadService afterSaleV2ReadService
    ) {
        this.jdbcClient = jdbcClient;
        this.afterSaleV2ReadService = afterSaleV2ReadService;
    }

    public PageResult<AfterSaleResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        long userId = requireAppUser(principal);
        long pageCurrent = normalizeCurrent(current);
        long pageSize = normalizeSize(size);
        long offset = (pageCurrent - 1) * pageSize;
        String normalizedStatus = StringUtils.hasText(status) ? status.trim() : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        where o.user_id = :userId
                          and (:status is null or asr.status = :status)
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .query(Long.class)
                .single();

        List<AfterSaleRow> pageRows = jdbcClient.sql(AFTER_SALE_SELECT + """
                        where o.user_id = :userId
                          and (:status is null or asr.status = :status)
                        order by asr.created_at desc, asr.id desc
                        limit :limit offset :offset
                        """)
                .param("userId", userId)
                .param("status", normalizedStatus)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapAfterSale)
                .list();
        List<AfterSaleResponse> records = toResponses(pageRows);

        return PageResult.of(records, total == null ? 0L : total, pageCurrent, pageSize);
    }

    public List<AfterSaleResponse> listForOrder(AuthenticatedPrincipal principal, Long orderId) {
        long userId = requireAppUser(principal);
        requireOwnedOrder(orderId, userId);
        List<AfterSaleRow> rows = jdbcClient.sql(AFTER_SALE_SELECT + """
                        where asr.order_id = :orderId
                          and o.user_id = :userId
                        order by asr.created_at desc, asr.id desc
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapAfterSale)
                .list();
        return toResponses(rows);
    }

    public AfterSaleResponse detail(AuthenticatedPrincipal principal, Long afterSaleId) {
        long userId = requireAppUser(principal);
        return requireResponseForUser(afterSaleId, userId);
    }

    public AfterSaleResponse latestForOrder(AuthenticatedPrincipal principal, Long orderId) {
        long userId = requireAppUser(principal);
        AfterSaleRow row = jdbcClient.sql(AFTER_SALE_SELECT + """
                        where asr.order_id = :orderId
                          and o.user_id = :userId
                        order by asr.created_at desc, asr.id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapAfterSale)
                .optional()
                .orElse(null);
        return row == null ? null : toResponses(List.of(row)).getFirst();
    }

    private static final String AFTER_SALE_SELECT = """
            select asr.id,
                   asr.after_sale_no,
                   asr.order_id,
                   o.order_no,
                   asr.user_id,
                   u.nickname as user_nickname,
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
            left join app_user u on u.id = asr.user_id
            """;

    private long requireAppUser(AuthenticatedPrincipal principal) {
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

    private AfterSaleResponse requireResponseForUser(Long afterSaleId, Long userId) {
        AfterSaleRow row = jdbcClient.sql(AFTER_SALE_SELECT + """
                        where asr.id = :afterSaleId
                          and o.user_id = :userId
                        """)
                .param("afterSaleId", afterSaleId)
                .param("userId", userId)
                .query(this::mapAfterSale)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return toResponses(List.of(row)).getFirst();
    }

    private List<AfterSaleResponse> toResponses(List<AfterSaleRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> afterSaleIds = rows.stream()
                .map(AfterSaleRow::id)
                .toList();
        EvidenceBatch evidenceBatch = evidenceBatch(afterSaleIds);
        Map<Long, RefundOrderResponse> refundsByAfterSaleId = refundOrders(afterSaleIds);
        List<AfterSaleResponse> bases = rows.stream()
                .map(row -> toBaseResponse(row, evidenceBatch, refundsByAfterSaleId))
                .toList();
        return afterSaleV2ReadService.decorateAll(bases);
    }

    private AfterSaleResponse toBaseResponse(
            AfterSaleRow row,
            EvidenceBatch evidenceBatch,
            Map<Long, RefundOrderResponse> refundsByAfterSaleId
    ) {
        return new AfterSaleResponse(
                row.id(),
                row.afterSaleNo(),
                row.orderId(),
                row.orderNo(),
                row.userId(),
                row.userNickname(),
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
                evidenceBatch.fileIdsByAfterSaleId().getOrDefault(row.id(), List.of()),
                evidenceBatch.filesByAfterSaleId().getOrDefault(row.id(), List.of()),
                refundsByAfterSaleId.get(row.id())
        );
    }

    private EvidenceBatch evidenceBatch(List<Long> afterSaleIds) {
        List<EvidenceProjectionRow> rows = jdbcClient.sql("""
                        select ase.after_sale_id,
                               ase.file_id,
                               asset.id as asset_id,
                               asset.original_filename,
                               asset.content_type,
                               asset.size_bytes,
                               asset.scope,
                               asset.media_kind,
                               asset.visibility,
                               asset.status
                        from after_sale_evidence ase
                        left join storage_asset asset on asset.id = ase.file_id
                        where ase.after_sale_id in (:afterSaleIds)
                        order by ase.after_sale_id asc, ase.sort_order asc, ase.id asc
                        """)
                .param("afterSaleIds", afterSaleIds)
                .query(this::mapEvidenceProjection)
                .list();

        Map<Long, List<Long>> fileIdsByAfterSaleId = new HashMap<>();
        Map<Long, List<AfterSaleEvidenceFileResponse>> filesByAfterSaleId = new HashMap<>();
        for (EvidenceProjectionRow row : rows) {
            fileIdsByAfterSaleId.computeIfAbsent(row.afterSaleId(), ignored -> new java.util.ArrayList<>())
                    .add(row.fileId());
            if (row.file() != null) {
                filesByAfterSaleId.computeIfAbsent(row.afterSaleId(), ignored -> new java.util.ArrayList<>())
                        .add(row.file());
            }
        }
        return new EvidenceBatch(fileIdsByAfterSaleId, filesByAfterSaleId);
    }

    private EvidenceProjectionRow mapEvidenceProjection(ResultSet rs, int rowNum) throws SQLException {
        Long afterSaleId = rs.getLong("after_sale_id");
        Long fileId = rs.getLong("file_id");
        Long assetId = rs.getObject("asset_id", Long.class);
        AfterSaleEvidenceFileResponse file = assetId == null ? null : new AfterSaleEvidenceFileResponse(
                fileId,
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("scope"),
                rs.getString("media_kind"),
                rs.getString("visibility"),
                rs.getString("status"),
                null,
                null,
                null
        );
        return new EvidenceProjectionRow(afterSaleId, fileId, file);
    }

    private Map<Long, RefundOrderResponse> refundOrders(List<Long> afterSaleIds) {
        List<RefundOrderResponse> rows = jdbcClient.sql("""
                        select ro.id,
                               ro.after_sale_id,
                               ro.order_id,
                               ro.payment_order_id,
                               ro.out_refund_no,
                               ro.refund_id,
                               ro.refund_amount_cent,
                               ro.status,
                               ro.callback_status,
                               ro.last_error_code,
                               ro.last_error_message,
                               ro.requested_at,
                               ro.success_at
                        from refund_order ro
                        join (
                            select after_sale_id, max(id) as latest_id
                            from refund_order
                            where after_sale_id in (:afterSaleIds)
                            group by after_sale_id
                        ) latest
                          on latest.after_sale_id = ro.after_sale_id
                         and latest.latest_id = ro.id
                        order by ro.after_sale_id asc
                        """)
                .param("afterSaleIds", afterSaleIds)
                .query(this::mapRefundOrder)
                .list();
        Map<Long, RefundOrderResponse> refundsByAfterSaleId = new HashMap<>();
        for (RefundOrderResponse row : rows) {
            refundsByAfterSaleId.put(row.afterSaleId(), row);
        }
        return refundsByAfterSaleId;
    }

    private long normalizeCurrent(Long current) {
        if (current == null) {
            return 1L;
        }
        if (current < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return current;
    }

    private long normalizeSize(Long size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private AfterSaleRow mapAfterSale(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleRow(
                rs.getLong("id"),
                rs.getString("after_sale_no"),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("user_nickname"),
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
                null,
                null,
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("success_at", LocalDateTime.class)
        );
    }

    private Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private record EvidenceProjectionRow(
            Long afterSaleId,
            Long fileId,
            AfterSaleEvidenceFileResponse file
    ) {
    }

    private record EvidenceBatch(
            Map<Long, List<Long>> fileIdsByAfterSaleId,
            Map<Long, List<AfterSaleEvidenceFileResponse>> filesByAfterSaleId
    ) {
    }

    private record AfterSaleRow(
            Long id,
            String afterSaleNo,
            Long orderId,
            String orderNo,
            Long userId,
            String userNickname,
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
