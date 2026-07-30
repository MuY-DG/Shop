package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.aftersale.AfterSaleStatus;
import org.muybaby.shopserver.aftersale.AfterSaleType;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEvidenceFileResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.dto.RefundOrderResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.order.service.OrderStatusLogService;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageFileUsageType;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageUsageOwnerType;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.StorageService;
import org.muybaby.shopserver.storage.service.StorageUsageService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AppAfterSaleService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter AFTER_SALE_NO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int AFTER_SALE_NO_RANDOM_BYTES = 9;
    private static final int AFTER_SALE_NO_RANDOM_WIDTH = 14;
    private static final SecureRandom AFTER_SALE_NO_RANDOM = new SecureRandom();
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AfterSaleStatus.REQUESTED.name(),
            AfterSaleStatus.APPROVED.name(),
            AfterSaleStatus.REFUNDING.name(),
            AfterSaleStatus.REFUND_FAILED.name()
    );
    private static final Set<String> ALLOWED_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.COMPLETED.name()
    );
    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageService storageService;
    private final StorageUsageService storageUsageService;
    private final OrderStatusLogService orderStatusLogService;

    public AppAfterSaleService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageService storageService,
            StorageUsageService storageUsageService,
            OrderStatusLogService orderStatusLogService
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageService = storageService;
        this.storageUsageService = storageUsageService;
        this.orderStatusLogService = orderStatusLogService;
    }

    public StorageAssetResponse uploadEvidence(
            AuthenticatedPrincipal principal,
            Long orderId,
            MultipartFile file
    ) {
        Long userId = requireAppUser(principal);
        OrderRow order = findOwnedOrder(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return storageService.uploadAfterSaleEvidence(principal, order.orderId(), file);
    }

    @Transactional
    public AfterSaleResponse apply(AuthenticatedPrincipal principal, Long orderId, AppAfterSaleApplyRequest request) {
        Long userId = requireAppUser(principal);
        AfterSaleType type = parseType(request == null ? null : request.afterSaleType());
        if (type != AfterSaleType.REFUND_ONLY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String reason = requireText(request == null ? null : request.reason(), 128);
        String description = normalizeText(request == null ? null : request.description(), 500);
        long requestedAmountCent = requirePositiveAmount(request == null ? null : request.requestedAmountCent());
        List<Long> evidenceFileIds = normalizeEvidenceFileIds(request == null ? null : request.evidenceFileIds());

        OrderRow order = findOwnedOrderForUpdate(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        if (requestedAmountCent != order.paidAmountCent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        rejectIfActiveAfterSaleExists(order.orderId());
        validateEvidenceFiles(userId, order.orderId(), evidenceFileIds);

        LocalDateTime requestedAt = LocalDateTime.now();
        String afterSaleNo = nextAfterSaleNo(requestedAt);
        Long afterSaleId = insertAfterSale(
                order, afterSaleNo, type, reason, description,
                requestedAmountCent, requestedAt);
        insertEvidence(afterSaleId, evidenceFileIds);
        addProtectedEvidenceUsage(afterSaleId, afterSaleNo, order.orderNo(), evidenceFileIds);
        claimEvidenceFiles(evidenceFileIds);
        orderStatusLogService.record(
                order.orderId(), afterSaleId, order.status(), order.status(),
                "AFTER_SALE_REQUESTED", "APP", userId,
                "用户申请售后", requestedAt
        );
        return requireResponseForUser(afterSaleId, userId);
    }

    public PageResult<AfterSaleResponse> list(
            AuthenticatedPrincipal principal,
            Long current,
            Long size,
            String status
    ) {
        Long userId = requireAppUser(principal);
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

        List<AfterSaleRow> pageRows = jdbcClient.sql("""
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
        Long userId = requireAppUser(principal);
        requireOwnedOrder(orderId, userId);
        List<AfterSaleRow> rows = jdbcClient.sql("""
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
        Long userId = requireAppUser(principal);
        return requireResponseForUser(afterSaleId, userId);
    }

    public AfterSaleResponse latestForOrder(AuthenticatedPrincipal principal, Long orderId) {
        Long userId = requireAppUser(principal);
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

    private java.util.Optional<OrderRow> findOwnedOrder(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               order_no,
                               user_id,
                               status,
                               paid_amount_cent
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
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

    private void validateEvidenceFiles(Long userId, Long orderId, List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        List<EvidenceFileRow> rows = jdbcClient.sql("""
                        select asset.id,
                               asset.scope,
                               asset.media_kind,
                               asset.visibility,
                               asset.status,
                               asset.uploaded_by_type,
                               asset.uploaded_by_id,
                               asset.upload_context_type,
                               asset.upload_context_id,
                               exists(
                                   select 1
                                   from storage_asset_usage asset_usage
                                   where asset_usage.asset_id = asset.id
                                     and asset_usage.status = 'ACTIVE'
                               ) as has_active_usage
                        from storage_asset asset
                        where asset.id in (:fileIds)
                          and asset.expires_at > current_timestamp
                        """)
                .param("fileIds", fileIds)
                .query(this::mapEvidenceFile)
                .list();
        if (rows.size() != fileIds.size()) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        for (EvidenceFileRow row : rows) {
            if (!StorageAssetScope.ATTACHMENT.name().equals(row.scope())
                    || !StorageMediaKind.IMAGE.name().equals(row.mediaKind())
                    || !"PRIVATE".equals(row.visibility())
                    || !"ACTIVE".equals(row.status())
                    || !"APP".equals(row.uploadedByType())
                    || !userId.equals(row.uploadedById())
                    || !"ORDER".equals(row.uploadContextType())
                    || !orderId.equals(row.uploadContextId())
                    || row.hasActiveUsage()) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
        }
    }

    private void claimEvidenceFiles(List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        update storage_asset
                        set expires_at = null,
                            updated_at = current_timestamp
                        where id in (:fileIds)
                        """)
                .param("fileIds", fileIds)
                .update();
    }

    private Long insertAfterSale(
            OrderRow order,
            String afterSaleNo,
            AfterSaleType type,
            String reason,
            String description,
            long requestedAmountCent,
            LocalDateTime requestedAt
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into after_sale_request
                            (after_sale_no, order_id, user_id, after_sale_type, status, reason, description,
                             requested_amount_cent, created_at, updated_at)
                        values
                            (:afterSaleNo, :orderId, :userId, :afterSaleType, :status, :reason, :description,
                             :requestedAmountCent, :createdAt, :updatedAt)
                        """,
                new MapSqlParameterSource()
                        .addValue("afterSaleNo", afterSaleNo)
                        .addValue("orderId", order.orderId())
                        .addValue("userId", order.userId())
                        .addValue("afterSaleType", type.name())
                        .addValue("status", AfterSaleStatus.REQUESTED.name())
                        .addValue("reason", reason)
                        .addValue("description", description)
                        .addValue("requestedAmountCent", requestedAmountCent)
                        .addValue("createdAt", requestedAt)
                        .addValue("updatedAt", requestedAt),
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

    private void addProtectedEvidenceUsage(
            Long afterSaleId,
            String afterSaleNo,
            String orderNo,
            List<Long> fileIds
    ) {
        int sortOrder = 1;
        for (Long fileId : fileIds) {
            storageUsageService.addProtectedUsage(
                    fileId,
                    StorageFileUsageType.AFTER_SALE_EVIDENCE,
                    StorageUsageOwnerType.AFTER_SALE,
                    afterSaleId,
                    "售后 " + afterSaleNo + " / 订单 " + orderNo,
                    "",
                    sortOrder++
            );
        }
    }

    private AfterSaleResponse requireResponseForUser(Long afterSaleId, Long userId) {
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
        return rows.stream()
                .map(row -> toResponse(row, evidenceBatch, refundsByAfterSaleId))
                .toList();
    }

    private AfterSaleResponse toResponse(
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
            fileIdsByAfterSaleId.computeIfAbsent(row.afterSaleId(), ignored -> new ArrayList<>())
                    .add(row.fileId());
            if (row.file() != null) {
                filesByAfterSaleId.computeIfAbsent(row.afterSaleId(), ignored -> new ArrayList<>())
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

    static String nextAfterSaleNo(LocalDateTime requestedAt) {
        byte[] randomBytes = new byte[AFTER_SALE_NO_RANDOM_BYTES];
        AFTER_SALE_NO_RANDOM.nextBytes(randomBytes);
        String randomSuffix = new BigInteger(1, randomBytes)
                .toString(Character.MAX_RADIX)
                .toUpperCase(Locale.ROOT);
        String paddedSuffix = "0".repeat(Math.max(0, AFTER_SALE_NO_RANDOM_WIDTH - randomSuffix.length()))
                + randomSuffix;
        return "AS" + requestedAt.format(AFTER_SALE_NO_TIME_FORMATTER) + paddedSuffix;
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
                rs.getString("scope"),
                rs.getString("media_kind"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("uploaded_by_type"),
                rs.getLong("uploaded_by_id"),
                rs.getString("upload_context_type"),
                rs.getObject("upload_context_id", Long.class),
                rs.getBoolean("has_active_usage")
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

    private record OrderRow(Long orderId, String orderNo, Long userId, String status, long paidAmountCent) {
    }

    private record EvidenceFileRow(
            Long id,
            String scope,
            String mediaKind,
            String visibility,
            String status,
            String uploadedByType,
            Long uploadedById,
            String uploadContextType,
            Long uploadContextId,
            boolean hasActiveUsage
    ) {
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
