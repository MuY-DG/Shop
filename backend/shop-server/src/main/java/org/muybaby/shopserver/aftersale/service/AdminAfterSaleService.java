package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AdminAfterSaleStatusGroup;
import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.AfterSaleType;
import org.muybaby.shopserver.aftersale.OrderRefundStatus;
import org.muybaby.shopserver.aftersale.RefundOrderStatus;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleAuditRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleDetailResponse;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleQueryRequest;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleStatusCountsResponse;
import org.muybaby.shopserver.aftersale.dto.AdminAfterSaleSummaryResponse;
import org.muybaby.shopserver.aftersale.dto.AdminRefundOperationRequest;
import org.muybaby.shopserver.aftersale.dto.AdminRefundOperationResponse;
import org.muybaby.shopserver.aftersale.dto.AdminReturnInspectionRequest;
import org.muybaby.shopserver.aftersale.dto.AdminReturnReceiveRequest;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEvidenceFileResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.RefundOrderResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.common.error.ProviderFailureCode;
import org.muybaby.shopserver.common.web.RequestLogContext;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.dto.OrderStatusLogResponse;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.payment.config.PaymentConfigResolver;
import org.muybaby.shopserver.payment.config.PaymentNotificationRouteService;
import org.muybaby.shopserver.payment.config.ResolvedPaymentConfig;
import org.muybaby.shopserver.payment.provider.WechatPayProvider;
import org.muybaby.shopserver.payment.provider.WechatPayOrderQueryResult;
import org.muybaby.shopserver.payment.provider.WechatRefundQueryResult;
import org.muybaby.shopserver.payment.provider.WechatRefundRequest;
import org.muybaby.shopserver.payment.provider.WechatRefundResult;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.provider.PrivateObjectAccess;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Service
public class AdminAfterSaleService {

    private static final DateTimeFormatter REFUND_NO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int REFUND_NO_RANDOM_BYTES = 9;
    private static final int REFUND_NO_RANDOM_WIDTH = 14;
    private static final SecureRandom REFUND_NO_RANDOM = new SecureRandom();
    private static final Set<String> AUDITABLE_STATUSES = Set.of(AfterSaleStatus.REQUESTED.name());
    private static final Set<String> REFUNDABLE_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(),
            OrderStatus.PARTIALLY_SHIPPED.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.COMPLETED.name()
    );

    private final JdbcClient jdbcClient;
    private final PaymentConfigResolver paymentConfigResolver;
    private final PaymentNotificationRouteService paymentNotificationRouteService;
    private final WechatPayProvider wechatPayProvider;
    private final StorageProvider storageProvider;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate refundStateTransaction;
    private final TransactionTemplate withoutTransaction;
    private final OrderStatusLogService orderStatusLogService;
    private final AfterSaleOrderContextQueryService orderContextQueryService;
    private final RefundRecoveryService refundRecoveryService;
    private final AfterSaleV2WorkflowService afterSaleV2WorkflowService;
    private final AfterSaleV2ReadService afterSaleV2ReadService;
    private final AfterSaleStatusLogService afterSaleStatusLogService;
    private final RefundFinalizationService refundFinalizationService;
    private final RefundProviderAttemptService refundProviderAttemptService;
    private final Clock clock;

    public AdminAfterSaleService(
            JdbcClient jdbcClient,
            PaymentConfigResolver paymentConfigResolver,
            PaymentNotificationRouteService paymentNotificationRouteService,
            WechatPayProvider wechatPayProvider,
            StorageProvider storageProvider,
            PlatformTransactionManager transactionManager,
            OrderStatusLogService orderStatusLogService,
            AfterSaleOrderContextQueryService orderContextQueryService,
            RefundRecoveryService refundRecoveryService,
            AfterSaleV2WorkflowService afterSaleV2WorkflowService,
            AfterSaleV2ReadService afterSaleV2ReadService,
            AfterSaleStatusLogService afterSaleStatusLogService,
            RefundFinalizationService refundFinalizationService,
            RefundProviderAttemptService refundProviderAttemptService,
            Clock clock
    ) {
        this.jdbcClient = jdbcClient;
        this.paymentConfigResolver = paymentConfigResolver;
        this.paymentNotificationRouteService = paymentNotificationRouteService;
        this.wechatPayProvider = wechatPayProvider;
        this.storageProvider = storageProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.refundStateTransaction = new TransactionTemplate(transactionManager);
        this.refundStateTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.orderStatusLogService = orderStatusLogService;
        this.orderContextQueryService = orderContextQueryService;
        this.refundRecoveryService = refundRecoveryService;
        this.afterSaleV2WorkflowService = afterSaleV2WorkflowService;
        this.afterSaleV2ReadService = afterSaleV2ReadService;
        this.afterSaleStatusLogService = afterSaleStatusLogService;
        this.refundFinalizationService = refundFinalizationService;
        this.refundProviderAttemptService = refundProviderAttemptService;
        this.clock = clock;
    }

    public PageResult<AdminAfterSaleSummaryResponse> page(
            AuthenticatedPrincipal principal,
            AdminAfterSaleQueryRequest query
    ) {
        requireAdminUser(principal);
        AdminAfterSaleQueryRequest normalized = normalizedQuery(query);
        AfterSaleQueryFilters filters = normalizeFilters(normalized, true);
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        left join app_user u on u.id = asr.user_id
                        where asr.status in (:statuses)
                          and (:afterSaleId is null or asr.id = :afterSaleId)
                          and (:afterSaleNoLike is null or asr.after_sale_no like :afterSaleNoLike)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or asr.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:afterSaleType is null or asr.after_sale_type = :afterSaleType)
                          and (:createdStart is null or asr.created_at >= :createdStart)
                          and (:createdEnd is null or asr.created_at <= :createdEnd)
                          and (:refundNoLike is null or exists (
                              select 1 from refund_order ro
                              where ro.after_sale_id = asr.id
                                and (ro.out_refund_no like :refundNoLike or ro.refund_id like :refundNoLike)
                          ))
                        """)
                .param("statuses", filters.statuses())
                .param("afterSaleId", filters.afterSaleId())
                .param("afterSaleNoLike", filters.afterSaleNoLike())
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("afterSaleType", filters.afterSaleType())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("refundNoLike", filters.refundNoLike())
                .query(Long.class)
                .single();

        List<AdminAfterSaleSummaryResponse> records = jdbcClient.sql("""
                        select asr.id,
                               asr.after_sale_no,
                               asr.order_id,
                               o.order_no,
                               asr.user_id,
                               u.nickname as user_nickname,
                               asr.after_sale_type,
                               asr.status,
                               asr.reason,
                               asr.requested_amount_cent,
                               asr.created_at
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        left join app_user u on u.id = asr.user_id
                        where asr.status in (:statuses)
                          and (:afterSaleId is null or asr.id = :afterSaleId)
                          and (:afterSaleNoLike is null or asr.after_sale_no like :afterSaleNoLike)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or asr.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:afterSaleType is null or asr.after_sale_type = :afterSaleType)
                          and (:createdStart is null or asr.created_at >= :createdStart)
                          and (:createdEnd is null or asr.created_at <= :createdEnd)
                          and (:refundNoLike is null or exists (
                              select 1 from refund_order ro
                              where ro.after_sale_id = asr.id
                                and (ro.out_refund_no like :refundNoLike or ro.refund_id like :refundNoLike)
                          ))
                        order by asr.created_at desc, asr.id desc
                        limit :limit offset :offset
                        """)
                .param("statuses", filters.statuses())
                .param("afterSaleId", filters.afterSaleId())
                .param("afterSaleNoLike", filters.afterSaleNoLike())
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("afterSaleType", filters.afterSaleType())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("refundNoLike", filters.refundNoLike())
                .param("limit", size)
                .param("offset", offset)
                .query(this::mapAfterSaleSummary)
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public AdminAfterSaleStatusCountsResponse statusCounts(
            AuthenticatedPrincipal principal,
            AdminAfterSaleQueryRequest query
    ) {
        requireAdminUser(principal);
        AfterSaleQueryFilters filters = normalizeFilters(normalizedQuery(query), false);
        Map<String, Long> counts = new HashMap<>();
        jdbcClient.sql("""
                        select asr.status, count(*) as status_count
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        left join app_user u on u.id = asr.user_id
                        where (:afterSaleId is null or asr.id = :afterSaleId)
                          and (:afterSaleNoLike is null or asr.after_sale_no like :afterSaleNoLike)
                          and (:orderNoLike is null or o.order_no like :orderNoLike)
                          and (:userId is null or asr.user_id = :userId)
                          and (:userPhone is null or u.phone_number = :userPhone)
                          and (:userNicknameLike is null or lower(u.nickname) like lower(:userNicknameLike))
                          and (:afterSaleType is null or asr.after_sale_type = :afterSaleType)
                          and (:createdStart is null or asr.created_at >= :createdStart)
                          and (:createdEnd is null or asr.created_at <= :createdEnd)
                          and (:refundNoLike is null or exists (
                              select 1 from refund_order ro
                              where ro.after_sale_id = asr.id
                                and (ro.out_refund_no like :refundNoLike or ro.refund_id like :refundNoLike)
                          ))
                        group by asr.status
                        """)
                .param("afterSaleId", filters.afterSaleId())
                .param("afterSaleNoLike", filters.afterSaleNoLike())
                .param("orderNoLike", filters.orderNoLike())
                .param("userId", filters.userId())
                .param("userPhone", filters.userPhone())
                .param("userNicknameLike", filters.userNicknameLike())
                .param("afterSaleType", filters.afterSaleType())
                .param("createdStart", filters.createdStart())
                .param("createdEnd", filters.createdEnd())
                .param("refundNoLike", filters.refundNoLike())
                .query((rs, rowNum) -> new StatusCountRow(rs.getString("status"), rs.getLong("status_count")))
                .list()
                .forEach(row -> counts.put(row.status(), row.count()));

        return new AdminAfterSaleStatusCountsResponse(
                countForGroup(counts, AdminAfterSaleStatusGroup.ALL),
                countForGroup(counts, AdminAfterSaleStatusGroup.PENDING_REVIEW),
                countForGroup(counts, AdminAfterSaleStatusGroup.REFUNDING),
                countForGroup(counts, AdminAfterSaleStatusGroup.REFUNDED),
                countForGroup(counts, AdminAfterSaleStatusGroup.REJECTED),
                countForGroup(counts, AdminAfterSaleStatusGroup.REFUND_FAILED)
        );
    }

    public AdminAfterSaleDetailResponse detail(AuthenticatedPrincipal principal, Long afterSaleId) {
        requireAdminUser(principal);
        AfterSaleResponse afterSale = requireResponse(afterSaleId);
        return AdminAfterSaleDetailResponse.from(
                afterSale,
                orderContextQueryService.requireContext(afterSale.orderId()),
                refundProviderAttemptService.findByAfterSaleId(afterSaleId)
        );
    }

    public List<OrderStatusLogResponse> records(
            AuthenticatedPrincipal principal,
            Long afterSaleId
    ) {
        requireAdminUser(principal);
        Integer exists = jdbcClient.sql(
                        "select count(*) from after_sale_request where id = :afterSaleId")
                .param("afterSaleId", afterSaleId)
                .query(Integer.class)
                .single();
        if (exists == null || exists == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcClient.sql("""
                        select id, order_id, after_sale_id, from_status, to_status, event_type,
                               operator_type, operator_id, description, created_at
                        from order_status_log
                        where after_sale_id = :afterSaleId
                        order by created_at asc, id asc
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new OrderStatusLogResponse(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getObject("after_sale_id", Long.class),
                        rs.getString("from_status"),
                        rs.getString("to_status"),
                        rs.getString("event_type"),
                        rs.getString("operator_type"),
                        rs.getObject("operator_id", Long.class),
                        rs.getString("description"),
                        rs.getObject("created_at", LocalDateTime.class)
                ))
                .list();
    }

    public ResponseEntity<InputStreamResource> evidence(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            Long fileId
    ) {
        requireAdminUser(principal);
        EvidenceResourceRow row = jdbcClient.sql("""
                        select sf.object_key,
                               sf.provider,
                               sf.storage_container,
                               sf.storage_region,
                               sf.original_filename,
                               sf.content_type
                        from after_sale_evidence ase
                        join storage_asset sf on sf.id = ase.file_id
                        where ase.after_sale_id = :afterSaleId
                          and ase.file_id = :fileId
                          and sf.visibility = 'PRIVATE'
                          and sf.status = 'ACTIVE'
                          and sf.scope = 'ATTACHMENT'
                          and sf.media_kind = 'IMAGE'
                        """)
                .param("afterSaleId", afterSaleId)
                .param("fileId", fileId)
                .query((rs, rowNum) -> new EvidenceResourceRow(
                        rs.getString("object_key"),
                        rs.getString("provider"),
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("original_filename"),
                        rs.getString("content_type")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        try {
            StoredObject storedObject = storageProvider.open(row.objectLocation());
            MediaType contentType = MediaType.parseMediaType(
                    StringUtils.hasText(row.contentType())
                            ? row.contentType()
                            : MediaType.APPLICATION_OCTET_STREAM_VALUE
            );
            String contentDisposition = ContentDisposition.inline()
                    .filename(row.originalFilename(), StandardCharsets.UTF_8)
                    .build()
                    .toString();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .contentType(contentType)
                    .contentLength(storedObject.sizeBytes())
                    .body(new InputStreamResource(storedObject.inputStream()));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    @Transactional
    public AfterSaleResponse reject(AuthenticatedPrincipal principal, Long afterSaleId, AdminAfterSaleAuditRequest request) {
        Long adminUserId = requireAdminUser(principal);
        Long routeOrderId = jdbcClient.sql("select order_id from after_sale_request where id = :id")
                .param("id", afterSaleId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        OrderRefundRow order = findOrderForUpdate(routeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!order.orderId().equals(afterSale.orderId())
                || !AUDITABLE_STATUSES.contains(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        String auditNote = requireAuditNote(request == null ? null : request.auditNote());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        requireUpdated(jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            audit_note = :auditNote,
                            reviewed_by = :reviewedBy,
                            reviewed_at = :reviewedAt,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId and status = :expectedStatus
                        """)
                .param("status", AfterSaleStatus.REJECTED.name())
                .param("auditNote", auditNote)
                .param("reviewedBy", adminUserId)
                .param("reviewedAt", now)
                .param("updatedAt", now)
                .param("afterSaleId", afterSaleId)
                .param("expectedStatus", AfterSaleStatus.REQUESTED.name())
                .update());
        afterSaleStatusLogService.record(
                afterSaleId, afterSale.status(), AfterSaleStatus.REJECTED.name(),
                "AFTER_SALE_REJECTED", "ADMIN", adminUserId,
                "售后审核拒绝：" + auditNote, now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "AFTER_SALE_REJECTED", "ADMIN", adminUserId,
                "售后审核拒绝", now
        );
        return requireResponse(afterSaleId);
    }

    public AfterSaleResponse approve(AuthenticatedPrincipal principal, Long afterSaleId, AdminAfterSaleAuditRequest request) {
        Long adminUserId = requireAdminUser(principal);
        if (AfterSaleType.RETURN_REFUND.name().equals(afterSaleV2WorkflowService.type(afterSaleId))) {
            afterSaleV2WorkflowService.approveReturn(adminUserId, afterSaleId, request);
            return requireResponse(afterSaleId);
        }
        long approvedAmountCent = afterSaleV2WorkflowService
                .previewApproval(afterSaleId, request).approvedAmountCent();
        String auditNote = normalizeAuditNote(request == null ? null : request.auditNote());
        AfterSaleResponse response = withoutTransaction.execute(status -> approveOutsideTransaction(
                adminUserId, afterSaleId, approvedAmountCent, auditNote, request, null));
        if (response == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return response;
    }

    public AfterSaleResponse receiveReturn(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            AdminReturnReceiveRequest request
    ) {
        Long adminUserId = requireAdminUser(principal);
        afterSaleV2WorkflowService.receiveReturn(
                adminUserId, afterSaleId, request == null ? null : request.note());
        return requireResponse(afterSaleId);
    }

    public AfterSaleResponse inspectReturn(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            AdminReturnInspectionRequest request
    ) {
        Long adminUserId = requireAdminUser(principal);
        String decision = request == null || request.decision() == null
                ? "" : request.decision().trim().toUpperCase(Locale.ROOT);
        if ("REJECT".equals(decision) || "REJECTED".equals(decision)) {
            afterSaleV2WorkflowService.rejectInspection(
                    adminUserId, afterSaleId, request == null ? null : request.note());
            return requireResponse(afterSaleId);
        }
        if (!"ACCEPT".equals(decision) && !"ACCEPTED".equals(decision)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        long approvedAmountCent = requireExistingApprovedAmount(afterSaleId);
        AfterSaleResponse response = withoutTransaction.execute(status -> approveOutsideTransaction(
                adminUserId, afterSaleId, approvedAmountCent,
                normalizeAuditNote(request.note()), null, request));
        if (response == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return response;
    }

    public AfterSaleResponse retryClosedRefund(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            AdminRefundOperationRequest request
    ) {
        Long adminUserId = requireAdminUser(principal);
        String note = requireRefundOperationNote(request);
        AfterSaleResponse response = withoutTransaction.execute(status ->
                retryClosedRefundOutsideTransaction(adminUserId, afterSaleId, note));
        if (response == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return response;
    }

    public AdminRefundOperationResponse queryRefundProvider(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            Long refundOrderId,
            AdminRefundOperationRequest request
    ) {
        return executeRefundProviderOperation(
                principal, afterSaleId, refundOrderId, request, false);
    }

    public AdminRefundOperationResponse resubmitRefundProvider(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            Long refundOrderId,
            AdminRefundOperationRequest request
    ) {
        return executeRefundProviderOperation(
                principal, afterSaleId, refundOrderId, request, true);
    }

    public AdminRefundOperationResponse markRefundManualIntervention(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            Long refundOrderId,
            AdminRefundOperationRequest request
    ) {
        Long adminUserId = requireAdminUser(principal);
        String note = requireRefundOperationNote(request);
        transactionTemplate.executeWithoutResult(status -> {
            RefundOperationTarget target = findRefundOperationTargetForUpdate(afterSaleId, refundOrderId);
            if (RefundOrderStatus.SUCCESS.name().equals(target.refundStatus())
                    || (!RefundOrderStatus.PROCESSING.name().equals(target.refundStatus())
                    && !RefundOrderStatus.FAILED.name().equals(target.refundStatus()))
                    || (!AfterSaleStatus.REFUNDING.name().equals(target.afterSaleStatus())
                    && !AfterSaleStatus.REFUND_FAILED.name().equals(target.afterSaleStatus()))
                    || !(REFUNDABLE_ORDER_STATUSES.contains(target.orderStatus())
                    || OrderStatus.REFUNDING.name().equals(target.orderStatus()))
                    || "CLOSED".equals(target.callbackStatus())
                    || refundRecoveryService.isRecoveryClaimActive(
                    target.recoveryClaimToken(), target.recoveryClaimedAt())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }

            clearExpiredRecoveryClaimIfPresent(
                    target.refundOrderId(), target.recoveryClaimToken(), target.recoveryClaimedAt());

            boolean alreadyManual = "MANUAL_INTERVENTION".equals(target.callbackStatus());
            if (alreadyManual
                    && (!RefundOrderStatus.FAILED.name().equals(target.refundStatus())
                    || !AfterSaleStatus.REFUND_FAILED.name().equals(target.afterSaleStatus()))) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }

            if (!alreadyManual) {
                LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
                int refundRows = jdbcClient.sql("""
                                update refund_order
                                set status = :status,
                                    callback_status = :callbackStatus,
                                    last_error_code = :lastErrorCode,
                                    last_error_message = :lastErrorMessage,
                                    failed_at = coalesce(failed_at, :failedAt),
                                    recovery_claim_token = null,
                                    recovery_claimed_at = null,
                                    next_recovery_at = null,
                                    updated_at = :updatedAt
                                where id = :refundOrderId
                                  and status = :expectedStatus
                                  and recovery_claim_token is null
                                """)
                        .param("status", RefundOrderStatus.FAILED.name())
                        .param("callbackStatus", "MANUAL_INTERVENTION")
                        .param("lastErrorCode", "MANUAL_INTERVENTION")
                        .param("lastErrorMessage", "Refund requires manual intervention")
                        .param("failedAt", now)
                        .param("updatedAt", now)
                        .param("refundOrderId", refundOrderId)
                        .param("expectedStatus", target.refundStatus())
                        .update();
                if (refundRows != 1) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                }
                if (AfterSaleStatus.REFUNDING.name().equals(target.afterSaleStatus())) {
                    int afterSaleRows = jdbcClient.sql("""
                                    update after_sale_request
                                    set status = :status,
                                        version = version + 1,
                                        updated_at = :updatedAt
                                    where id = :afterSaleId
                                      and status = :expectedStatus
                                    """)
                            .param("status", AfterSaleStatus.REFUND_FAILED.name())
                            .param("updatedAt", now)
                            .param("afterSaleId", afterSaleId)
                            .param("expectedStatus", AfterSaleStatus.REFUNDING.name())
                            .update();
                    if (afterSaleRows != 1) {
                        throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
                    }
                    requireUpdated(jdbcClient.sql("""
                                        update shop_order
                                        set refund_status = :refundStatus, updated_at = :now
                                        where id = :orderId and status = :orderStatus
                                        """)
                                .param("refundStatus", OrderRefundStatus.REFUND_FAILED.name())
                                .param("now", now)
                                .param("orderId", target.orderId())
                                .param("orderStatus", target.orderStatus())
                                .update());
                    afterSaleStatusLogService.record(
                            afterSaleId, AfterSaleStatus.REFUNDING.name(),
                            AfterSaleStatus.REFUND_FAILED.name(),
                            "REFUND_MANUAL_INTERVENTION", "ADMIN", adminUserId,
                            auditDescription("人工介入退款", note), now);
                }
            }
            orderStatusLogService.record(
                    target.orderId(), afterSaleId, target.orderStatus(), target.orderStatus(),
                    "REFUND_MANUAL_INTERVENTION", "ADMIN", adminUserId,
                    auditDescription("人工介入退款", note), LocalDateTime.now(java.time.ZoneOffset.UTC)
            );
        });
        return new AdminRefundOperationResponse(
                "MANUAL_INTERVENTION",
                "MANUAL_INTERVENTION",
                "MANUAL_INTERVENTION",
                false,
                requireResponse(afterSaleId)
        );
    }

    private AdminRefundOperationResponse executeRefundProviderOperation(
            AuthenticatedPrincipal principal,
            Long afterSaleId,
            Long refundOrderId,
            AdminRefundOperationRequest request,
            boolean resubmitWhenMissing
    ) {
        Long adminUserId = requireAdminUser(principal);
        String note = requireRefundOperationNote(request);
        RefundOperationTarget target = transactionTemplate.execute(status -> {
            RefundOperationTarget current = findRefundOperationTargetForUpdate(
                    afterSaleId, refundOrderId);
            if (!RefundOrderStatus.PROCESSING.name().equals(current.refundStatus())
                    && !RefundOrderStatus.FAILED.name().equals(current.refundStatus())
                    && !RefundOrderStatus.SUCCESS.name().equals(current.refundStatus())) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            if (resubmitWhenMissing && "CLOSED".equals(current.callbackStatus())) {
                // CLOSED is a definitive provider terminal state. It must use the dedicated
                // new-merchant-refund-number flow after the cause is resolved; the old number is
                // never resubmitted, even if a later query unexpectedly reports NOT_FOUND.
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            orderStatusLogService.record(
                    current.orderId(), current.afterSaleId(), current.orderStatus(), current.orderStatus(),
                    resubmitWhenMissing ? "REFUND_RESUBMIT_REQUESTED" : "REFUND_QUERY_REQUESTED",
                    "ADMIN", adminUserId,
                    auditDescription(
                            resubmitWhenMissing ? "请求安全重提退款" : "请求查询渠道退款状态", note),
                    LocalDateTime.now(java.time.ZoneOffset.UTC)
            );
            return current;
        });
        if (target == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (RefundOrderStatus.SUCCESS.name().equals(target.refundStatus())) {
            recordRefundOperationResult(
                    target, adminUserId,
                    resubmitWhenMissing ? "REFUND_RESUBMIT_COMPLETED" : "REFUND_QUERY_COMPLETED",
                    auditDescription("退款已成功，本次操作幂等返回", note)
            );
            return new AdminRefundOperationResponse(
                    resubmitWhenMissing ? "PROVIDER_RESUBMIT" : "PROVIDER_QUERY",
                    "DUPLICATE",
                    target.callbackStatus(),
                    false,
                    requireResponse(afterSaleId)
            );
        }

        RefundRecoveryService.ManualRecoveryResult result;
        try {
            result = resubmitWhenMissing
                    ? refundRecoveryService.resubmitRefundNow(refundOrderId)
                    : refundRecoveryService.queryRefundNow(refundOrderId);
        } catch (RuntimeException failure) {
            String providerErrorCode = ProviderFailureCode.safeCode(failure);
            recordRefundOperationResult(
                    target, adminUserId,
                    resubmitWhenMissing ? "REFUND_RESUBMIT_FAILED" : "REFUND_QUERY_FAILED",
                    auditDescription("退款人工操作失败", providerErrorCode)
            );
            RequestLogContext.markProviderError(providerErrorCode);
            WechatRefundFailureClassifier.Classification classification =
                    WechatRefundFailureClassifier.classify(providerErrorCode);
            if (classification.disposition().requiresMerchantAction()) {
                throw new BusinessException(classification.errorCode());
            }
            if (failure instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(classification.errorCode());
        }

        String resultSummary = "渠道状态=" + result.providerStatus()
                + ", 处理结果=" + (result.outcome() == null ? "NO_CHANGE" : result.outcome().name())
                + (result.resubmitted() ? ", 已使用原商户退款单号重提" : "");
        recordRefundOperationResult(
                target, adminUserId,
                resubmitWhenMissing ? "REFUND_RESUBMIT_COMPLETED" : "REFUND_QUERY_COMPLETED",
                auditDescription(resultSummary, note)
        );
        return new AdminRefundOperationResponse(
                resubmitWhenMissing ? "PROVIDER_RESUBMIT" : "PROVIDER_QUERY",
                result.outcome() == null ? result.providerStatus() : result.outcome().name(),
                result.providerStatus(),
                result.resubmitted(),
                requireResponse(afterSaleId)
        );
    }

    private void submitRefund(PreparedRefundRequest refundContext) {
        WechatRefundResult refundResult;

        try {
            refundResult = wechatPayProvider.requestRefund(
                    refundContext.config(), refundContext.toProviderRequest());
        } catch (RuntimeException ex) {
            String providerErrorCode = ProviderFailureCode.safeCode(ex);
            WechatRefundFailureClassifier.Classification classification =
                    WechatRefundFailureClassifier.classify(providerErrorCode);
            RequestLogContext.markProviderError(providerErrorCode);
            refundProviderAttemptService.recordRefund(
                    refundContext.refundOrderId(), "SUBMISSION", "ADMIN", "FAILURE", "",
                    "QUERY_REQUIRED", ex);

            WechatRefundQueryResult queryResult;
            try {
                queryResult = wechatPayProvider.queryRefund(
                        refundContext.config(), refundContext.outRefundNo());
                refundProviderAttemptService.recordRefund(
                        refundContext.refundOrderId(), "QUERY", "ADMIN", "SUCCESS",
                        queryResult.status(),
                        "NOT_FOUND".equalsIgnoreCase(queryResult.status())
                                ? "NOT_FOUND" : "PROVIDER_CONFIRMED",
                        null);
            } catch (RuntimeException queryFailure) {
                refundProviderAttemptService.recordRefund(
                        refundContext.refundOrderId(), "QUERY", "ADMIN", "FAILURE", "",
                        "RECOVERY_SCHEDULED", queryFailure);
                markRefundRequestUncertain(refundContext, ex);
                throw new BusinessException(ErrorCode.WECHAT_REFUND_RECONCILIATION_PENDING);
            }

            if (!"NOT_FOUND".equalsIgnoreCase(queryResult.status())) {
                refundFinalizationService.apply(
                        new RefundFinalizationService.ProviderRefundState(
                                queryResult.outRefundNo(), queryResult.refundId(),
                                queryResult.outTradeNo(), queryResult.status(),
                                queryResult.refundAmountCent(), queryResult.successAt(), ""),
                        refundContext.config());
                return;
            }

            if (classification.disposition().requiresMerchantAction()) {
                refundFinalizationService.rejectSubmission(
                        refundContext.outRefundNo(), providerErrorCode, refundContext.config());
                refundProviderAttemptService.recordRefund(
                        refundContext.refundOrderId(), "DECISION", "ADMIN", "FAILURE", "",
                        "MERCHANT_ACTION_REQUIRED", ex);
                throw new BusinessException(classification.errorCode());
            }
            markRefundRequestUncertain(refundContext, ex);
            refundProviderAttemptService.recordRefund(
                    refundContext.refundOrderId(), "DECISION", "ADMIN", "FAILURE", "",
                    "RECOVERY_SCHEDULED", ex);
            throw new BusinessException(ErrorCode.WECHAT_REFUND_RECONCILIATION_PENDING);
        }

        markRefundProviderAccepted(refundContext, refundResult);
        refundProviderAttemptService.recordRefund(
                refundContext.refundOrderId(), "SUBMISSION", "ADMIN", "SUCCESS",
                refundResult.status(), "ACCEPTED", null);
    }

    private AfterSaleResponse approveOutsideTransaction(
            Long adminUserId,
            Long afterSaleId,
            long approvedAmountCent,
            String auditNote,
            AdminAfterSaleAuditRequest auditRequest,
            AdminReturnInspectionRequest inspectionRequest
    ) {
        RefundProviderPreflight providerPreflight = preflightRefundProvider(
                afterSaleId, RefundPreflightMode.APPROVE, approvedAmountCent);
        PreparedRefundRequest refundContext = refundStateTransaction.execute(status ->
                prepareRefundRequest(
                        adminUserId, afterSaleId, approvedAmountCent, auditNote,
                        auditRequest, inspectionRequest, providerPreflight));
        if (refundContext == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        submitRefund(refundContext);
        return requireResponse(afterSaleId);
    }

    private AfterSaleResponse retryClosedRefundOutsideTransaction(
            Long adminUserId,
            Long afterSaleId,
            String note
    ) {
        RefundProviderPreflight providerPreflight = preflightRefundProvider(
                afterSaleId, RefundPreflightMode.CLOSED_RETRY, null);
        PreparedRefundRequest refundContext = refundStateTransaction.execute(status ->
                prepareClosedRefundRetry(adminUserId, afterSaleId, note, providerPreflight));
        if (refundContext == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        submitRefund(refundContext);
        return requireResponse(afterSaleId);
    }

    private RefundProviderPreflight preflightRefundProvider(
            Long afterSaleId,
            RefundPreflightMode mode,
            Long approvedAmountCent
    ) {
        RefundPaymentIdentity identity = jdbcClient.sql("""
                        select po.id as payment_order_id,
                               po.payment_config_id,
                               po.payment_config_fingerprint,
                               po.out_trade_no,
                               po.transaction_id,
                               po.amount_cent as payment_amount_cent,
                               asr.status as after_sale_status,
                               asr.after_sale_type,
                               asr.requested_amount_cent,
                               asr.approved_amount_cent,
                               o.id as order_id,
                               o.status as order_status,
                               o.paid_amount_cent,
                               o.refund_status as order_refund_status,
                               o.refunded_amount_cent,
                               ro.id as latest_refund_id,
                               ro.order_id as latest_refund_order_id,
                               ro.payment_order_id as latest_refund_payment_order_id,
                               ro.refund_amount_cent as latest_refund_amount_cent,
                               ro.status as latest_refund_status,
                               ro.callback_status as latest_refund_callback_status,
                               ro.recovery_claim_token,
                               ro.recovery_claimed_at
                        from after_sale_request asr
                        join shop_order o on o.id = asr.order_id
                        join payment_order po on po.order_id = asr.order_id
                        left join refund_order ro on ro.id = (
                            select max(latest_ro.id)
                            from refund_order latest_ro
                            where latest_ro.after_sale_id = asr.id
                        )
                        where asr.id = :afterSaleId
                          and po.status = 'PAID'
                        order by po.paid_at desc, po.id desc
                        limit 1
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> new RefundPaymentIdentity(
                        rs.getLong("payment_order_id"),
                        rs.getObject("payment_config_id", Long.class),
                        rs.getString("payment_config_fingerprint"),
                        rs.getString("out_trade_no"),
                        rs.getString("transaction_id"),
                        rs.getLong("payment_amount_cent"),
                        rs.getString("after_sale_status"),
                        rs.getString("after_sale_type"),
                        rs.getLong("requested_amount_cent"),
                        rs.getObject("approved_amount_cent", Long.class),
                        rs.getLong("order_id"),
                        rs.getString("order_status"),
                        rs.getLong("paid_amount_cent"),
                        rs.getString("order_refund_status"),
                        rs.getLong("refunded_amount_cent"),
                        rs.getObject("latest_refund_id", Long.class),
                        rs.getObject("latest_refund_order_id", Long.class),
                        rs.getObject("latest_refund_payment_order_id", Long.class),
                        rs.getObject("latest_refund_amount_cent", Long.class),
                        rs.getString("latest_refund_status"),
                        rs.getString("latest_refund_callback_status"),
                        rs.getString("recovery_claim_token"),
                        rs.getObject("recovery_claimed_at", LocalDateTime.class)))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        requireEligibleRefundPreflight(mode, identity, approvedAmountCent);
        ResolvedPaymentConfig resolvedConfig = paymentConfigResolver.resolveForPayment(
                identity.paymentConfigId(), identity.paymentConfigFingerprint());
        verifyWechatPaidOrder(afterSaleId, identity, resolvedConfig);
        String notificationRouteToken = paymentNotificationRouteService.issueToken();
        String refundNotifyUrl = paymentNotificationRouteService.refundNotifyUrl(
                resolvedConfig.refundNotifyUrl(), notificationRouteToken);
        return new RefundProviderPreflight(
                identity, notificationRouteToken, resolvedConfig, refundNotifyUrl);
    }

    private void verifyWechatPaidOrder(
            Long afterSaleId,
            RefundPaymentIdentity identity,
            ResolvedPaymentConfig resolvedConfig
    ) {
        WechatPayOrderQueryResult providerOrder;
        try {
            providerOrder = wechatPayProvider.queryOrder(resolvedConfig, identity.outTradeNo());
        } catch (RuntimeException failure) {
            String providerErrorCode = ProviderFailureCode.safeCode(failure);
            RequestLogContext.markProviderError(providerErrorCode);
            refundProviderAttemptService.recordPreflight(
                    afterSaleId, identity.orderId(), identity.outTradeNo(), "ADMIN",
                    "FAILURE", "", "PREFLIGHT_REJECTED", failure);
            throw new BusinessException(ErrorCode.WECHAT_REFUND_PREFLIGHT_FAILED);
        }
        boolean paidWithinRefundWindow = providerOrder.paidAt() != null
                && !providerOrder.paidAt().isBefore(LocalDateTime.now(clock).minusDays(365));
        boolean verified = providerOrder.paid()
                && "SUCCESS".equalsIgnoreCase(providerOrder.tradeState())
                && identity.outTradeNo().equals(providerOrder.outTradeNo())
                && identity.paymentAmountCent() == providerOrder.amountCent()
                && (!StringUtils.hasText(identity.transactionId())
                || identity.transactionId().equals(providerOrder.transactionId()))
                && paidWithinRefundWindow;
        refundProviderAttemptService.recordPreflight(
                afterSaleId, identity.orderId(), identity.outTradeNo(), "ADMIN",
                verified ? "SUCCESS" : "FAILURE", providerOrder.tradeState(),
                verified ? "VERIFIED_PAID" : "PREFLIGHT_REJECTED", null);
        if (!verified) {
            throw new BusinessException(ErrorCode.WECHAT_REFUND_PREFLIGHT_FAILED);
        }
        Integer successfulRefundCount = jdbcClient.sql("""
                        select count(*)
                        from refund_order
                        where order_id = :orderId and status = 'SUCCESS'
                        """)
                .param("orderId", identity.orderId())
                .query(Integer.class)
                .single();
        if (successfulRefundCount != null && successfulRefundCount >= 50) {
            throw new BusinessException(ErrorCode.WECHAT_REFUND_REQUEST_REJECTED);
        }
    }

    private void requireEligibleRefundPreflight(
            RefundPreflightMode mode,
            RefundPaymentIdentity identity,
            Long approvedAmountCent
    ) {
        boolean eligibleOrderStatus = REFUNDABLE_ORDER_STATUSES.contains(identity.orderStatus())
                || mode == RefundPreflightMode.CLOSED_RETRY
                && OrderStatus.REFUNDING.name().equals(identity.orderStatus());
        if (!eligibleOrderStatus
                || identity.paymentAmountCent() != identity.paidAmountCent()
                || identity.refundedAmountCent() < 0
                || identity.refundedAmountCent() > identity.paidAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (mode == RefundPreflightMode.APPROVE) {
            boolean expectedState = AfterSaleType.REFUND_ONLY.name().equals(identity.afterSaleType())
                    ? AfterSaleStatus.REQUESTED.name().equals(identity.afterSaleStatus())
                    : AfterSaleType.RETURN_REFUND.name().equals(identity.afterSaleType())
                    && AfterSaleStatus.WAITING_INSPECTION.name().equals(identity.afterSaleStatus());
            long remainingAmountCent = identity.paidAmountCent() - identity.refundedAmountCent();
            if (!expectedState
                    || identity.latestRefundId() != null
                    || approvedAmountCent == null
                    || approvedAmountCent <= 0
                    || approvedAmountCent > identity.requestedAmountCent()
                    || approvedAmountCent > remainingAmountCent) {
                throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
            }
            return;
        }
        long remainingAmountCent = identity.paidAmountCent() - identity.refundedAmountCent();
        if (!AfterSaleStatus.REFUND_FAILED.name().equals(identity.afterSaleStatus())
                || identity.latestRefundId() == null
                || identity.latestRefundId() <= 0
                || !identity.orderId().equals(identity.latestRefundOrderId())
                || !identity.paymentOrderId().equals(identity.latestRefundPaymentOrderId())
                || !RefundOrderStatus.FAILED.name().equals(identity.latestRefundStatus())
                || !"CLOSED".equals(identity.latestRefundCallbackStatus())
                || identity.latestRefundAmountCent() == null
                || identity.latestRefundAmountCent() <= 0
                || identity.latestRefundAmountCent() > remainingAmountCent
                || !identity.latestRefundAmountCent().equals(identity.afterSaleApprovedAmountCent())
                || refundRecoveryService.isRecoveryClaimActive(
                identity.recoveryClaimToken(), identity.recoveryClaimedAt())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private void requireMatchingPreflight(
            PaymentOrderRow payment,
            RefundProviderPreflight providerPreflight
    ) {
        RefundPaymentIdentity identity = providerPreflight.identity();
        if (!Objects.equals(payment.id(), identity.paymentOrderId())
                || !Objects.equals(payment.paymentConfigId(), identity.paymentConfigId())
                || !Objects.equals(
                payment.paymentConfigFingerprint(), identity.paymentConfigFingerprint())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private PreparedRefundRequest prepareRefundRequest(
            Long adminUserId,
            Long afterSaleId,
            long approvedAmountCent,
            String auditNote,
            AdminAfterSaleAuditRequest auditRequest,
            AdminReturnInspectionRequest inspectionRequest,
            RefundProviderPreflight providerPreflight
    ) {
        OrderRefundRow order = findOrderForUpdate(providerPreflight.identity().orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!order.orderId().equals(afterSale.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        boolean expectedAfterSaleStatus =
                (AfterSaleType.REFUND_ONLY.name().equals(afterSale.afterSaleType())
                && AfterSaleStatus.REQUESTED.name().equals(afterSale.status()))
                || (AfterSaleType.RETURN_REFUND.name().equals(afterSale.afterSaleType())
                && AfterSaleStatus.WAITING_INSPECTION.name().equals(afterSale.status()));
        if (!expectedAfterSaleStatus) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (approvedAmountCent <= 0 || approvedAmountCent > afterSale.requestedAmountCent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!REFUNDABLE_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (hasProcessingRefund(order.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        PaymentOrderRow payment = findPaidPaymentForUpdate(order.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!StringUtils.hasText(payment.transactionId()) && !StringUtils.hasText(payment.outTradeNo())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        requireMatchingPreflight(payment, providerPreflight);
        rejectIfRefundOrderExists(afterSaleId);

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        AfterSaleV2WorkflowService.ApprovalPlan plan;
        if (AfterSaleType.RETURN_REFUND.name().equals(afterSale.afterSaleType())) {
            plan = afterSaleV2WorkflowService.applyInspectionAcceptanceLocked(
                    afterSaleId,
                    inspectionRequest == null ? null : inspectionRequest.items(),
                    inspectionRequest == null ? auditNote : inspectionRequest.note(),
                    adminUserId,
                    now);
        } else {
            boolean unshipped = OrderStatus.PAID.name().equals(order.status())
                    && order.shippedAt() == null
                    && !shipmentExists(order.orderId());
            plan = afterSaleV2WorkflowService.applyRefundOnlyApprovalLocked(
                    afterSaleId, auditRequest, unshipped, now);
        }
        if (plan.approvedAmountCent() != approvedAmountCent) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        long refundedAfter;
        try {
            refundedAfter = Math.addExact(order.refundedAmountCent(), approvedAmountCent);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (refundedAfter > order.paidAmountCent()) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        boolean restockRequired = hasRestockItems(afterSaleId);
        String outRefundNo = nextRefundNo(now);
        String notificationRouteToken = providerPreflight.notificationRouteToken();
        String providerReason = WechatRefundRequest.providerSafeReason(auditNote);

        jdbcClient.sql("""
                        insert into refund_order
                            (after_sale_id, order_id, payment_order_id, notification_route_token,
                             out_refund_no, refund_id, provider_reason,
                             refund_amount_cent, status, callback_status, restock_required,
                             requested_at, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :paymentOrderId, :notificationRouteToken,
                             :outRefundNo, :refundId, :providerReason,
                             :refundAmountCent, :status, :callbackStatus, :restockRequired,
                             :requestedAt, :createdAt, :updatedAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .param("paymentOrderId", payment.id())
                .param("notificationRouteToken", notificationRouteToken)
                .param("outRefundNo", outRefundNo)
                .param("refundId", "")
                .param("providerReason", providerReason)
                .param("refundAmountCent", approvedAmountCent)
                .param("status", RefundOrderStatus.PROCESSING.name())
                .param("callbackStatus", RefundOrderStatus.PROCESSING.name())
                .param("restockRequired", restockRequired)
                .param("requestedAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        Long refundOrderId = jdbcClient.sql("""
                        select id
                        from refund_order
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", outRefundNo)
                .query(Long.class)
                .single();
        int afterSaleRows = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            approved_amount_cent = :approvedAmountCent,
                            audit_note = :auditNote,
                            reviewed_by = :reviewedBy,
                            reviewed_at = :reviewedAt,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                          and status = :expectedStatus
                        """)
                .param("status", AfterSaleStatus.REFUNDING.name())
                .param("approvedAmountCent", approvedAmountCent)
                .param("auditNote", auditNote)
                .param("reviewedBy", adminUserId)
                .param("reviewedAt", now)
                .param("updatedAt", now)
                .param("afterSaleId", afterSaleId)
                .param("expectedStatus", afterSale.status())
                .update();
        requireUpdated(afterSaleRows);
        long refundingAmountAfter = order.refundedAmountCent() + approvedAmountCent;
        boolean fullyRefunding = refundingAmountAfter == order.paidAmountCent();
        String refundStatus = fullyRefunding
                ? OrderRefundStatus.FULL_REFUNDING.name()
                : OrderRefundStatus.PARTIAL_REFUNDING.name();
        String targetOrderStatus = fullyRefunding
                ? OrderStatus.REFUNDING.name() : order.status();
        int orderRows = jdbcClient.sql("""
                            update shop_order
                            set status = :targetOrderStatus,
                                refund_status = :refundStatus,
                                refunding_at = coalesce(refunding_at, :refundingAt),
                                updated_at = :updatedAt
                            where id = :orderId
                              and status = :expectedOrderStatus
                              and refunded_amount_cent = :refundedAmountCent
                            """)
                    .param("targetOrderStatus", targetOrderStatus)
                    .param("refundStatus", refundStatus)
                    .param("refundingAt", now)
                    .param("updatedAt", now)
                    .param("orderId", order.orderId())
                    .param("expectedOrderStatus", order.status())
                    .param("refundedAmountCent", order.refundedAmountCent())
                    .update();
        requireUpdated(orderRows);
        afterSaleStatusLogService.record(
                afterSaleId, afterSale.status(), AfterSaleStatus.REFUNDING.name(),
                "REFUND_STARTED", "ADMIN", adminUserId,
                "售后审核通过，开始退款", now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), targetOrderStatus,
                "REFUND_STARTED", "ADMIN", adminUserId,
                "售后审核通过，开始退款", now);
        return new PreparedRefundRequest(
                refundOrderId,
                providerPreflight.config(),
                providerPreflight.notifyUrl(),
                payment.outTradeNo(),
                payment.transactionId(),
                outRefundNo,
                approvedAmountCent,
                payment.amountCent(),
                providerReason
        );
    }

    private PreparedRefundRequest prepareClosedRefundRetry(
            Long adminUserId,
            Long afterSaleId,
            String operationNote,
            RefundProviderPreflight providerPreflight
    ) {
        OrderRefundRow order = findOrderForUpdate(providerPreflight.identity().orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!order.orderId().equals(afterSale.orderId())
                || !AfterSaleStatus.REFUND_FAILED.name().equals(afterSale.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (!(REFUNDABLE_ORDER_STATUSES.contains(order.status())
                || OrderStatus.REFUNDING.name().equals(order.status()))) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        PaymentOrderRow payment = findPaidPaymentForUpdate(order.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!StringUtils.hasText(payment.transactionId()) && !StringUtils.hasText(payment.outTradeNo())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        requireMatchingPreflight(payment, providerPreflight);

        RefundRetrySource source = findLatestRefundForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        boolean commonInvalid = !RefundOrderStatus.FAILED.name().equals(source.status())
                || !"CLOSED".equals(source.callbackStatus())
                || refundRecoveryService.isRecoveryClaimActive(
                source.recoveryClaimToken(), source.recoveryClaimedAt())
                || !order.orderId().equals(source.orderId())
                || !payment.id().equals(source.paymentOrderId())
                || source.refundAmountCent() <= 0
                || afterSale.approvedAmountCent() == null
                || source.refundAmountCent() != afterSale.approvedAmountCent();
        boolean amountInvalid = payment.amountCent() != order.paidAmountCent()
                || source.refundAmountCent() > order.paidAmountCent() - order.refundedAmountCent();
        if (commonInvalid || amountInvalid) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }

        clearExpiredRecoveryClaimIfPresent(
                source.refundOrderId(), source.recoveryClaimToken(), source.recoveryClaimedAt());

        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        String outRefundNo = nextRefundNo(now);
        String notificationRouteToken = providerPreflight.notificationRouteToken();
        String providerReason = source.providerReason() != null
                ? source.providerReason()
                : WechatRefundRequest.providerSafeReason(afterSale.auditNote());
        jdbcClient.sql("""
                        insert into refund_order
                            (after_sale_id, order_id, payment_order_id, notification_route_token,
                             out_refund_no, refund_id, provider_reason,
                             refund_amount_cent, status, callback_status, restock_required,
                             requested_at, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :paymentOrderId, :notificationRouteToken,
                             :outRefundNo, '', :providerReason,
                             :refundAmountCent, :status, :callbackStatus, :restockRequired,
                             :requestedAt, :createdAt, :updatedAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .param("paymentOrderId", payment.id())
                .param("notificationRouteToken", notificationRouteToken)
                .param("outRefundNo", outRefundNo)
                .param("providerReason", providerReason)
                .param("refundAmountCent", source.refundAmountCent())
                .param("status", RefundOrderStatus.PROCESSING.name())
                .param("callbackStatus", RefundOrderStatus.PROCESSING.name())
                .param("restockRequired", source.restockRequired())
                .param("requestedAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update();
        Long refundOrderId = jdbcClient.sql("""
                        select id
                        from refund_order
                        where out_refund_no = :outRefundNo
                        """)
                .param("outRefundNo", outRefundNo)
                .query(Long.class)
                .single();
        int updated = jdbcClient.sql("""
                        update after_sale_request
                        set status = :status,
                            version = version + 1,
                            updated_at = :updatedAt
                        where id = :afterSaleId
                          and status = :expectedStatus
                        """)
                .param("status", AfterSaleStatus.REFUNDING.name())
                .param("updatedAt", now)
                .param("afterSaleId", afterSaleId)
                .param("expectedStatus", AfterSaleStatus.REFUND_FAILED.name())
                .update();
        requireUpdated(updated);
        String logDescription = auditDescription(
                "渠道退款关闭后新单重试：旧退款=" + source.refundOrderId()
                        + "/" + source.outRefundNo()
                        + "，新退款=" + refundOrderId + "/" + outRefundNo,
                operationNote);
        long refundAfter = order.refundedAmountCent() + source.refundAmountCent();
        String refundStatus = refundAfter == order.paidAmountCent()
                ? OrderRefundStatus.FULL_REFUNDING.name()
                : OrderRefundStatus.PARTIAL_REFUNDING.name();
        requireUpdated(jdbcClient.sql("""
                            update shop_order
                            set refund_status = :refundStatus, updated_at = :now
                            where id = :orderId
                              and refunded_amount_cent = :refundedAmountCent
                            """)
                    .param("refundStatus", refundStatus)
                    .param("now", now)
                    .param("orderId", order.orderId())
                    .param("refundedAmountCent", order.refundedAmountCent())
                    .update());
        afterSaleStatusLogService.record(
                afterSaleId, AfterSaleStatus.REFUND_FAILED.name(), AfterSaleStatus.REFUNDING.name(),
                "REFUND_RETRIED", "ADMIN", adminUserId, logDescription, now);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "REFUND_RETRIED", "ADMIN", adminUserId,
                logDescription, now
        );

        return new PreparedRefundRequest(
                refundOrderId,
                providerPreflight.config(),
                providerPreflight.notifyUrl(),
                payment.outTradeNo(),
                payment.transactionId(),
                outRefundNo,
                source.refundAmountCent(),
                payment.amountCent(),
                providerReason
        );
    }

    private void markRefundProviderAccepted(PreparedRefundRequest refundContext, WechatRefundResult refundResult) {
        refundStateTransaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
            String providerStatus = StringUtils.hasText(refundResult.status())
                    ? refundResult.status()
                    : RefundOrderStatus.PROCESSING.name();
            jdbcClient.sql("""
                            update refund_order
                            set refund_id = case when :refundId = '' then refund_id else :refundId end,
                                callback_status = :callbackStatus,
                                last_error_code = '',
                                last_error_message = '',
                                updated_at = :updatedAt
                            where id = :refundOrderId
                              and status = :expectedStatus
                            """)
                    .param("refundId", nullToEmpty(refundResult.refundId()))
                    .param("callbackStatus", providerStatus)
                    .param("updatedAt", now)
                    .param("refundOrderId", refundContext.refundOrderId())
                    .param("expectedStatus", RefundOrderStatus.PROCESSING.name())
                    .update();
        });
    }

    private void markRefundRequestUncertain(PreparedRefundRequest refundContext, RuntimeException failure) {
        refundStateTransaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
            jdbcClient.sql("""
                            update refund_order
                            set callback_status = :callbackStatus,
                                last_error_code = :lastErrorCode,
                                last_error_message = :lastErrorMessage,
                                next_recovery_at = :nextRecoveryAt,
                                updated_at = :updatedAt
                            where id = :refundOrderId
                              and status = :expectedStatus
                            """)
                    .param("callbackStatus", "REQUEST_UNKNOWN")
                    .param("lastErrorCode", ProviderFailureCode.safeCode(failure))
                    .param("lastErrorMessage", "Refund request result is unknown; provider query scheduled")
                    .param("nextRecoveryAt", now)
                    .param("updatedAt", now)
                    .param("refundOrderId", refundContext.refundOrderId())
                    .param("expectedStatus", RefundOrderStatus.PROCESSING.name())
                    .update();
        });
    }

    private AdminAfterSaleQueryRequest normalizedQuery(AdminAfterSaleQueryRequest query) {
        return query == null
                ? new AdminAfterSaleQueryRequest(
                        null, null, null, null, null, null,
                        null, null, null, null, null, null, null
                )
                : query;
    }

    private AfterSaleQueryFilters normalizeFilters(AdminAfterSaleQueryRequest query, boolean includeStatus) {
        List<String> statuses = Arrays.stream(AfterSaleStatus.values()).map(Enum::name).toList();
        if (includeStatus && StringUtils.hasText(query.status())) {
            try {
                statuses = List.of(AfterSaleStatus.valueOf(
                        query.status().trim().toUpperCase(Locale.ROOT)
                ).name());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        } else if (includeStatus && StringUtils.hasText(query.statusGroup())) {
            try {
                statuses = AdminAfterSaleStatusGroup.valueOf(
                        query.statusGroup().trim().toUpperCase(Locale.ROOT)
                ).statuses();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }

        Long afterSaleId = query.afterSaleId();
        if (afterSaleId != null && afterSaleId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Long userId = null;
        String userPhone = null;
        String userNicknameLike = null;
        if (StringUtils.hasText(query.userKeyword())) {
            String keyword = query.userKeyword().trim();
            String searchType = StringUtils.hasText(query.userSearchType())
                    ? query.userSearchType().trim().toUpperCase(Locale.ROOT)
                    : "";
            if ("USER_ID".equals(searchType)) {
                try {
                    userId = Long.valueOf(keyword);
                } catch (NumberFormatException ex) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                }
            } else if ("USER_PHONE".equals(searchType)) {
                userPhone = keyword;
            } else if ("USER_NAME".equals(searchType)) {
                userNicknameLike = like(keyword);
            } else {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }

        String afterSaleType = null;
        if (StringUtils.hasText(query.afterSaleType())) {
            try {
                afterSaleType = AfterSaleType.valueOf(
                        query.afterSaleType().trim().toUpperCase(Locale.ROOT)
                ).name();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        LocalDateTime createdStart = query.createdStartUtc();
        LocalDateTime createdEnd = query.createdEndUtc();
        if (createdStart != null && createdEnd != null && createdStart.isAfter(createdEnd)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        return new AfterSaleQueryFilters(
                statuses,
                afterSaleId,
                like(query.afterSaleNo()),
                like(query.orderNo()),
                userId,
                userPhone,
                userNicknameLike,
                afterSaleType,
                createdStart,
                createdEnd,
                like(query.refundNo())
        );
    }

    private String like(String value) {
        return StringUtils.hasText(value) ? "%" + value.trim() + "%" : null;
    }

    private long countForGroup(Map<String, Long> counts, AdminAfterSaleStatusGroup group) {
        return group.statuses().stream().mapToLong(status -> counts.getOrDefault(status, 0L)).sum();
    }

    private Long requireAdminUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.ADMIN) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private String requireRefundOperationNote(AdminRefundOperationRequest request) {
        String note = request == null || request.note() == null ? "" : request.note().trim();
        if (!StringUtils.hasText(note) || note.length() > 180) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return note;
    }

    private RefundOperationTarget findRefundOperationTarget(Long afterSaleId, Long refundOrderId) {
        if (afterSaleId == null || afterSaleId < 1 || refundOrderId == null || refundOrderId < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return jdbcClient.sql("""
                        select ro.id,
                               ro.after_sale_id,
                               ro.order_id,
                               ro.status as refund_status,
                               ro.callback_status,
                               ro.recovery_claim_token,
                               ro.recovery_claimed_at,
                               asr.status as after_sale_status,
                               o.status as order_status
                        from refund_order ro
                        join after_sale_request asr on asr.id = ro.after_sale_id
                        join shop_order o on o.id = ro.order_id
                        where ro.id = :refundOrderId
                          and ro.after_sale_id = :afterSaleId
                          and not exists (
                              select 1
                              from refund_order newer
                              where newer.after_sale_id = ro.after_sale_id
                                and newer.id > ro.id
                          )
                        """)
                .param("refundOrderId", refundOrderId)
                .param("afterSaleId", afterSaleId)
                .query(this::mapRefundOperationTarget)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private RefundOperationTarget findRefundOperationTargetForUpdate(
            Long afterSaleId,
            Long refundOrderId
    ) {
        Long orderId = jdbcClient.sql("select order_id from after_sale_request where id = :id")
                .param("id", afterSaleId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        OrderRefundRow order = findOrderForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        AfterSaleAuditRow afterSale = findAfterSaleForUpdate(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!order.orderId().equals(afterSale.orderId())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return jdbcClient.sql("""
                        select ro.id,
                               ro.after_sale_id,
                               ro.order_id,
                               ro.status as refund_status,
                               ro.callback_status,
                               ro.recovery_claim_token,
                               ro.recovery_claimed_at
                        from refund_order ro
                        where ro.id = :refundOrderId
                          and ro.after_sale_id = :afterSaleId
                          and ro.order_id = :orderId
                          and not exists (
                              select 1
                              from refund_order newer
                              where newer.after_sale_id = ro.after_sale_id
                                and newer.id > ro.id
                          )
                        for update
                        """)
                .param("refundOrderId", refundOrderId)
                .param("afterSaleId", afterSaleId)
                .param("orderId", order.orderId())
                .query((rs, rowNum) -> new RefundOperationTarget(
                        rs.getLong("id"),
                        rs.getLong("after_sale_id"),
                        rs.getLong("order_id"),
                        rs.getString("refund_status"),
                        rs.getString("callback_status"),
                        rs.getString("recovery_claim_token"),
                        rs.getObject("recovery_claimed_at", LocalDateTime.class),
                        afterSale.status(),
                        order.status()
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private RefundOperationTarget mapRefundOperationTarget(ResultSet rs, int rowNum) throws SQLException {
        return new RefundOperationTarget(
                rs.getLong("id"),
                rs.getLong("after_sale_id"),
                rs.getLong("order_id"),
                rs.getString("refund_status"),
                rs.getString("callback_status"),
                rs.getString("recovery_claim_token"),
                rs.getObject("recovery_claimed_at", LocalDateTime.class),
                rs.getString("after_sale_status"),
                rs.getString("order_status")
        );
    }

    private void recordRefundOperationResult(
            RefundOperationTarget target,
            Long adminUserId,
            String eventType,
            String description
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            RefundOperationTarget current = findRefundOperationTargetForUpdate(
                    target.afterSaleId(), target.refundOrderId());
            orderStatusLogService.record(
                    current.orderId(), current.afterSaleId(), target.orderStatus(), current.orderStatus(),
                    eventType, "ADMIN", adminUserId, description, LocalDateTime.now(java.time.ZoneOffset.UTC)
            );
        });
    }

    private String auditDescription(String summary, String note) {
        String description = summary + "；操作原因：" + note;
        return description.length() <= 255 ? description : description.substring(0, 255);
    }

    private java.util.Optional<AfterSaleAuditRow> findAfterSaleForUpdate(Long afterSaleId) {
        return jdbcClient.sql("""
                        select asr.id,
                               asr.after_sale_no,
                               asr.order_id,
                               asr.after_sale_type,
                               asr.status,
                               asr.reason,
                               asr.requested_amount_cent,
                               asr.approved_amount_cent,
                               asr.audit_note
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
                               paid_amount_cent,
                               refund_status,
                               refunded_amount_cent,
                               shipped_at
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
                               payment_config_id,
                               payment_config_fingerprint,
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

    private java.util.Optional<RefundRetrySource> findLatestRefundForUpdate(Long afterSaleId) {
        return jdbcClient.sql("""
                        select id,
                               order_id,
                               payment_order_id,
                               out_refund_no,
                               provider_reason,
                               refund_amount_cent,
                               status,
                               callback_status,
                               restock_required,
                               recovery_claim_token,
                               recovery_claimed_at
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id desc
                        limit 1
                        for update
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapRefundRetrySource)
                .optional();
    }

    private void rejectIfRefundOrderExists(Long afterSaleId) {
        Long existingId = jdbcClient.sql("""
                        select id
                        from refund_order
                        where after_sale_id = :afterSaleId
                        order by id desc
                        limit 1
                        for update
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (existingId != null) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private boolean shipmentExists(Long orderId) {
        return jdbcClient.sql("select count(*) from order_shipment where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single() > 0L;
    }

    private boolean hasProcessingRefund(Long orderId) {
        return jdbcClient.sql("""
                        select count(*) from refund_order
                        where order_id = :orderId and status = :status
                        """)
                .param("orderId", orderId)
                .param("status", RefundOrderStatus.PROCESSING.name())
                .query(Long.class)
                .single() > 0L;
    }

    private boolean hasRestockItems(Long afterSaleId) {
        return jdbcClient.sql("""
                        select count(*) from after_sale_item
                        where after_sale_id = :afterSaleId and restock_quantity > 0
                        """)
                .param("afterSaleId", afterSaleId)
                .query(Long.class)
                .single() > 0L;
    }

    private long requireExistingApprovedAmount(Long afterSaleId) {
        Long amount = jdbcClient.sql("""
                        select approved_amount_cent
                        from after_sale_request
                        where id = :afterSaleId
                          and after_sale_type = :afterSaleType
                          and status = :status
                        """)
                .param("afterSaleId", afterSaleId)
                .param("afterSaleType", AfterSaleType.RETURN_REFUND.name())
                .param("status", AfterSaleStatus.WAITING_INSPECTION.name())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return amount;
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private AfterSaleResponse requireResponse(Long afterSaleId) {
        AfterSaleRow row = jdbcClient.sql("""
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
                        where asr.id = :afterSaleId
                        """)
                .param("afterSaleId", afterSaleId)
                .query(this::mapAfterSale)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        return afterSaleV2ReadService.decorate(toResponse(row));
    }

    private AfterSaleResponse toResponse(AfterSaleRow row) {
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
        Function<StorageObjectLocation, PrivateObjectAccess> accessResolver =
                storageProvider.privateReadAccessResolver(Duration.ofMinutes(5));
        return jdbcClient.sql("""
                        select ase.file_id,
                               sf.original_filename,
                               sf.content_type,
                               sf.size_bytes,
                               sf.scope,
                               sf.media_kind,
                               sf.visibility,
                               sf.status,
                               sf.provider,
                               sf.storage_container,
                               sf.storage_region,
                               sf.object_key
                        from after_sale_evidence ase
                        join storage_asset sf on sf.id = ase.file_id
                        where ase.after_sale_id = :afterSaleId
                        order by ase.sort_order asc, ase.id asc
                        """)
                .param("afterSaleId", afterSaleId)
                .query((rs, rowNum) -> mapEvidenceFile(rs, rowNum, accessResolver))
                .list();
    }

    private AfterSaleEvidenceFileResponse mapEvidenceFile(
            ResultSet rs,
            int rowNum,
            Function<StorageObjectLocation, PrivateObjectAccess> accessResolver
    ) throws SQLException {
        PrivateObjectAccess access = accessResolver.apply(new StorageObjectLocation(
                StorageProviderKind.valueOf(rs.getString("provider")),
                rs.getString("storage_container"),
                rs.getString("storage_region"),
                rs.getString("object_key")
        ));
        return new AfterSaleEvidenceFileResponse(
                rs.getLong("file_id"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("scope"),
                rs.getString("media_kind"),
                rs.getString("visibility"),
                rs.getString("status"),
                access.mode().name(),
                access.url(),
                access.expiresAt()
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

    static String nextRefundNo(LocalDateTime requestedAt) {
        byte[] randomBytes = new byte[REFUND_NO_RANDOM_BYTES];
        REFUND_NO_RANDOM.nextBytes(randomBytes);
        String randomSuffix = new BigInteger(1, randomBytes)
                .toString(Character.MAX_RADIX)
                .toUpperCase(Locale.ROOT);
        String paddedSuffix = "0".repeat(Math.max(0, REFUND_NO_RANDOM_WIDTH - randomSuffix.length()))
                + randomSuffix;
        return "RF" + requestedAt.format(REFUND_NO_TIME_FORMATTER) + paddedSuffix;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private AfterSaleAuditRow mapAfterSaleAudit(ResultSet rs, int rowNum) throws SQLException {
        return new AfterSaleAuditRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("after_sale_type"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getLong("requested_amount_cent"),
                nullableLong(rs, "approved_amount_cent"),
                rs.getString("audit_note")
        );
    }

    private OrderRefundRow mapOrderRefund(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRefundRow(
                rs.getLong("order_id"),
                rs.getString("status"),
                rs.getLong("paid_amount_cent"),
                rs.getString("refund_status"),
                rs.getLong("refunded_amount_cent"),
                rs.getObject("shipped_at", LocalDateTime.class)
        );
    }

    private PaymentOrderRow mapPaymentOrder(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentOrderRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                nullableLong(rs, "payment_config_id"),
                rs.getString("payment_config_fingerprint"),
                rs.getString("out_trade_no"),
                rs.getString("transaction_id"),
                rs.getString("status"),
                rs.getLong("amount_cent")
        );
    }

    private RefundRetrySource mapRefundRetrySource(ResultSet rs, int rowNum) throws SQLException {
        return new RefundRetrySource(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getLong("payment_order_id"),
                rs.getString("out_refund_no"),
                rs.getString("provider_reason"),
                rs.getLong("refund_amount_cent"),
                rs.getString("status"),
                rs.getString("callback_status"),
                rs.getBoolean("restock_required"),
                rs.getString("recovery_claim_token"),
                rs.getObject("recovery_claimed_at", LocalDateTime.class)
        );
    }

    private void clearExpiredRecoveryClaimIfPresent(
            Long refundOrderId,
            String claimToken,
            LocalDateTime claimedAt
    ) {
        if (!StringUtils.hasText(claimToken)) {
            return;
        }
        if (refundRecoveryService.isRecoveryClaimActive(claimToken, claimedAt)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        int updated = jdbcClient.sql("""
                        update refund_order
                        set recovery_claim_token = null,
                            recovery_claimed_at = null
                        where id = :refundOrderId
                          and recovery_claim_token = :claimToken
                        """)
                .param("refundOrderId", refundOrderId)
                .param("claimToken", claimToken)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
    }

    private AdminAfterSaleSummaryResponse mapAfterSaleSummary(ResultSet rs, int rowNum) throws SQLException {
        return new AdminAfterSaleSummaryResponse(
                rs.getLong("id"),
                rs.getString("after_sale_no"),
                rs.getLong("order_id"),
                rs.getString("order_no"),
                rs.getLong("user_id"),
                rs.getString("user_nickname"),
                rs.getString("after_sale_type"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getLong("requested_amount_cent"),
                rs.getObject("created_at", LocalDateTime.class)
        );
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

    private record AfterSaleAuditRow(
            Long id,
            Long orderId,
            String afterSaleType,
            String status,
            String reason,
            long requestedAmountCent,
            Long approvedAmountCent,
            String auditNote
    ) {
    }

    private record OrderRefundRow(
            Long orderId,
            String status,
            long paidAmountCent,
            String refundStatus,
            long refundedAmountCent,
            LocalDateTime shippedAt
    ) {
    }

    private record PaymentOrderRow(
            Long id,
            Long orderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String transactionId,
            String status,
            long amountCent
    ) {
    }

    private record RefundPaymentIdentity(
            Long paymentOrderId,
            Long paymentConfigId,
            String paymentConfigFingerprint,
            String outTradeNo,
            String transactionId,
            long paymentAmountCent,
            String afterSaleStatus,
            String afterSaleType,
            long requestedAmountCent,
            Long afterSaleApprovedAmountCent,
            Long orderId,
            String orderStatus,
            long paidAmountCent,
            String orderRefundStatus,
            long refundedAmountCent,
            Long latestRefundId,
            Long latestRefundOrderId,
            Long latestRefundPaymentOrderId,
            Long latestRefundAmountCent,
            String latestRefundStatus,
            String latestRefundCallbackStatus,
            String recoveryClaimToken,
            LocalDateTime recoveryClaimedAt
    ) {
    }

    private enum RefundPreflightMode {
        APPROVE,
        CLOSED_RETRY
    }

    private record RefundProviderPreflight(
            RefundPaymentIdentity identity,
            String notificationRouteToken,
            ResolvedPaymentConfig config,
            String notifyUrl
    ) {
    }

    private record RefundRetrySource(
            Long refundOrderId,
            Long orderId,
            Long paymentOrderId,
            String outRefundNo,
            String providerReason,
            long refundAmountCent,
            String status,
            String callbackStatus,
            boolean restockRequired,
            String recoveryClaimToken,
            LocalDateTime recoveryClaimedAt
    ) {
    }

    private record RefundOperationTarget(
            Long refundOrderId,
            Long afterSaleId,
            Long orderId,
            String refundStatus,
            String callbackStatus,
            String recoveryClaimToken,
            LocalDateTime recoveryClaimedAt,
            String afterSaleStatus,
            String orderStatus
    ) {
    }

    private record AfterSaleQueryFilters(
            List<String> statuses,
            Long afterSaleId,
            String afterSaleNoLike,
            String orderNoLike,
            Long userId,
            String userPhone,
            String userNicknameLike,
            String afterSaleType,
            LocalDateTime createdStart,
            LocalDateTime createdEnd,
            String refundNoLike
    ) {
    }

    private record StatusCountRow(String status, Long count) {
    }

    private record EvidenceResourceRow(
            String objectKey,
            String provider,
            String storageContainer,
            String storageRegion,
            String originalFilename,
            String contentType
    ) {
        private StorageObjectLocation objectLocation() {
            return new StorageObjectLocation(
                    StorageProviderKind.valueOf(provider),
                    storageContainer,
                    storageRegion,
                    objectKey
            );
        }
    }

    private record PreparedRefundRequest(
            Long refundOrderId,
            ResolvedPaymentConfig config,
            String notifyUrl,
            String outTradeNo,
            String transactionId,
            String outRefundNo,
            long refundAmountCent,
            long totalAmountCent,
            String reason
    ) {
        private WechatRefundRequest toProviderRequest() {
            return new WechatRefundRequest(
                    outTradeNo,
                    transactionId,
                    outRefundNo,
                    refundAmountCent,
                    totalAmountCent,
                    reason,
                    notifyUrl
            );
        }
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
