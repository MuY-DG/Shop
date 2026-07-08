package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.RefundOrderStatus;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleAuditRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleQueryRequest;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.RefundOrderResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.muybaby.shopserver.payment.provider.WechatRefundResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@Service
public class AdminAfterSaleService {

    private static final Set<String> AUDITABLE_STATUSES = Set.of(AfterSaleStatus.REQUESTED.name());
    private static final Set<String> REFUNDABLE_ORDER_STATUSES = Set.of(OrderStatus.PAID.name(), OrderStatus.SHIPPED.name());
    private static final DateTimeFormatter REFUND_NO_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final WechatPayProvider wechatPayProvider;

    public AdminAfterSaleService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            WechatPayProvider wechatPayProvider
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.wechatPayProvider = wechatPayProvider;
    }

    public PageResult<AfterSaleResponse> page(AuthenticatedPrincipal principal, AdminAfterSaleQueryRequest query) {
        requireAdminUser(principal);
        AdminAfterSaleQueryRequest normalized = query == null
                ? new AdminAfterSaleQueryRequest(null, null, null, null)
                : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        String status = StringUtils.hasText(normalized.status()) ? normalized.status().trim() : null;
        String orderNoLike = StringUtils.hasText(normalized.orderNo()) ? "%" + normalized.orderNo().trim() + "%" : null;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        where (:status is null or asr.status = :status)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                        """)
                .param("status", status)
                .param("orderNoLike", orderNoLike)
                .query(Long.class)
                .single();

        List<AfterSaleResponse> records = jdbcClient.sql("""
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
                        where (:status is null or asr.status = :status)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                        order by asr.created_at desc, asr.id desc
                        limit :limit offset :offset
                        """)
                .param("status", status)
                .param("orderNoLike", orderNoLike)
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAfterSale)
                .list()
                .stream()
                .map(this::toResponse)
                .toList();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public AfterSaleResponse detail(AuthenticatedPrincipal principal, Long afterSaleId) {
        requireAdminUser(principal);
        return requireResponse(afterSaleId);
    }

    @Transactional
    public AfterSaleResponse reject(AuthenticatedPrincipal principal, Long afterSaleId, AdminAfterSaleAuditRequest request) {
        Long adminUserId = requireAdminUser(principal);
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!AUDITABLE_STATUSES.contains(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String auditNote = requireAuditNote(request == null ? null : request.auditNote());
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            audit_note = :auditNote,
                            reviewed_by = :reviewedBy,
                            reviewed_at = :reviewedAt,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                        """)
                .param("status", AfterSaleStatus.REJECTED.name())
                .param("auditNote", auditNote)
                .param("reviewedBy", adminUserId)
                .param("reviewedAt", now)
                .param("updatedAt", now)
                .param("afterSaleId", afterSaleId)
                .update();
        return requireResponse(afterSaleId);
    }

    @Transactional
    public AfterSaleResponse approve(AuthenticatedPrincipal principal, Long afterSaleId, AdminAfterSaleAuditRequest request) {
        Long adminUserId = requireAdminUser(principal);
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!AUDITABLE_STATUSES.contains(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        long approvedAmountCent = requireApprovedAmount(request == null ? null : request.approvedAmountCent());
        if (approvedAmountCent > afterSale.requestedAmountCent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String auditNote = normalizeAuditNote(request == null ? null : request.auditNote());
        OrderRefundRow order = findOrderForUpdate(afterSale.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!REFUNDABLE_ORDER_STATUSES.contains(order.status()) || approvedAmountCent > order.paidAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        PaymentOrderRow payment = findPaidPaymentForUpdate(order.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!StringUtils.hasText(payment.transactionId()) && !StringUtils.hasText(payment.outTradeNo())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        rejectIfRefundOrderExists(afterSaleId);

        ResolvedPaymentConfig config = paymentConfigResolver.resolve();
        String outRefundNo = nextOutRefundNo(afterSaleId);
        WechatRefundResult refundResult = wechatPayProvider.requestRefund(config, new WechatRefundRequest(
                payment.outTradeNo(),
                payment.transactionId(),
                outRefundNo,
                approvedAmountCent,
                payment.amountCent(),
                StringUtils.hasText(auditNote) ? auditNote : afterSale.reason(),
                config.refundNotifyUrl()
        ));

        LocalDateTime now = LocalDateTime.now();
        String providerStatus = StringUtils.hasText(refundResult.status())
                ? refundResult.status()
                : RefundOrderStatus.PROCESSING.name();
        jdbcClient.sql("""
                        insert into refund_order
                            (after_sale_id, order_id, payment_order_id, out_refund_no, refund_id,
                             refund_amount_cent, status, callback_status, requested_at, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :paymentOrderId, :outRefundNo, :refundId,
                             :refundAmountCent, :status, :callbackStatus, :requestedAt, :createdAt, :updatedAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .param("paymentOrderId", payment.id())
                .param("outRefundNo", outRefundNo)
                .param("refundId", nullToEmpty(refundResult.refundId()))
                .param("refundAmountCent", approvedAmountCent)
                .param("status", RefundOrderStatus.PROCESSING.name())
                .param("callbackStatus", providerStatus)
                .param("requestedAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            approved_amount_cent = :approvedAmountCent,
                            audit_note = :auditNote,
                            reviewed_by = :reviewedBy,
                            reviewed_at = :reviewedAt,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                        """)
                .param("status", AfterSaleStatus.REFUNDING.name())
                .param("approvedAmountCent", approvedAmountCent)
                .param("auditNote", auditNote)
                .param("reviewedBy", adminUserId)
                .param("reviewedAt", now)
                .param("updatedAt", now)
                .param("afterSaleId", afterSaleId)
                .update();
        jdbcClient.sql("""
                        update shop_order
                        set status = :status,
                            refunding_at = :refundingAt,
                            updated_at = :updatedAt
                        where id = :orderId
                        """)
                .param("status", OrderStatus.REFUNDING.name())
                .param("refundingAt", now)
                .param("updatedAt", now)
                .param("orderId", order.orderId())
                .update();
        return requireResponse(afterSaleId);
    }

    private Long requireAdminUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private java.util.Optional<AfterSaleAuditRow> findAfterSaleForUpdate(Long afterSaleId) {
        return jdbcClient.sql("""
                        select asr.id,
                               asr.order_id,
                               asr.status,
                               asr.reason,
                               asr.requested_amount_cent
                        from after_sale_request asr
                        where asr.id = :afterSaleId
                        for update
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapAfterSaleAudit)
                .optional();
    }

    private java.util.Optional<OrderRefundRow> findOrderForUpdate(Long orderId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               status,
                               paid_amount_cent
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapOrderRefund)
                .optional();
    }

    private java.util.Optional<PaymentOrderRow> findPaidPaymentForUpdate(Long orderId) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               out_trade_no,
                               transaction_id,
                               status,
                               amount_cent
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAID'
                        order by paid_at desc, id desc
                        limit 1
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapPaymentOrder)
                .optional();
    }

    private void rejectIfRefundOrderExists(Long afterSaleId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where after_sale_id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single();
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private AfterSaleResponse requireResponse(Long afterSaleId) {
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
                        """)
                .param("afterSaleId", afterSaleId)
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

    private long requireApprovedAmount(Long value) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }

    private String requireAuditNote(String value) {
        String normalized = normalizeAuditNote(value);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String normalizeAuditNote(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String nextOutRefundNo(Long afterSaleId) {
        String candidate = "RF" + afterSaleId + REFUND_NO_TIME_FORMATTER.format(LocalDateTime.now());
        if (candidate.length() <= 64) {
            return candidate;
        }
        return "RF" + afterSaleId;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private AfterSaleAuditRow mapAfterSaleAudit(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleAuditRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getLong("requested_amount_cent")
        );
    }

    private OrderRefundRow mapOrderRefund(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRefundRow(
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getLong("paid_amount_cent")
        );
    }

    private PaymentOrderRow mapPaymentOrder(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOrderRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("out_trade_no"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getLong("amount_cent")
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

    private record AfterSaleAuditRow(Long id, Long orderId, String status, String reason, long requestedAmountCent) {
    }

    private record OrderRefundRow(Long orderId, String status, long paidAmountCent) {
    }

    private record PaymentOrderRow(Long id, Long orderId, String outTradeNo, String transactionId, String status, long amountCent) {
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
