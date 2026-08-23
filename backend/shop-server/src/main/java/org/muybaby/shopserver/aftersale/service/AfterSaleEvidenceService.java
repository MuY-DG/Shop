package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageUploadProfile;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.service.DirectUploadService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * 售后凭证上传：普通上传与直传会话的订单归属校验收口在这里，
 * 与售后单本身的生命周期无关（申请前即可传图）。
 */
@Service
public class AfterSaleEvidenceService {

    private static final Set<String> ALLOWED_ORDER_STATUSES = Set.of(
            OrderStatus.PAID.name(),
            OrderStatus.PARTIALLY_SHIPPED.name(),
            OrderStatus.SHIPPED.name(),
            OrderStatus.COMPLETED.name()
    );

    private final JdbcClient jdbcClient;
    private final StorageService storageService;
    private final DirectUploadService directUploadService;

    public AfterSaleEvidenceService(
            JdbcClient jdbcClient,
            StorageService storageService,
            DirectUploadService directUploadService
    ) {
        this.jdbcClient = jdbcClient;
        this.storageService = storageService;
        this.directUploadService = directUploadService;
    }

    public StorageAssetResponse uploadEvidence(
            AuthenticatedPrincipal principal,
            Long orderId,
            MultipartFile file
    ) {
        long userId = requireAppUser(principal);
        OrderRow order = findOwnedOrder(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return storageService.uploadAfterSaleEvidence(principal, order.orderId(), file);
    }

    public DirectUploadSessionResponse createEvidenceUploadSession(
            AuthenticatedPrincipal principal,
            Long orderId,
            DirectUploadSessionRequest request
    ) {
        long userId = requireAppUser(principal);
        OrderRow order = findOwnedOrder(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return directUploadService.create(
                principal,
                StorageUploadProfile.AFTER_SALE_EVIDENCE,
                null,
                "ORDER",
                order.orderId(),
                request
        );
    }

    public StorageAssetResponse completeEvidenceUploadSession(
            AuthenticatedPrincipal principal,
            Long orderId,
            String uploadId
    ) {
        long userId = requireAppUser(principal);
        OrderRow order = findOwnedOrder(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        if (!ALLOWED_ORDER_STATUSES.contains(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return directUploadService.complete(
                principal,
                uploadId,
                StorageUploadProfile.AFTER_SALE_EVIDENCE,
                order.orderId()
        ).asset();
    }

    public void cancelEvidenceUploadSession(
            AuthenticatedPrincipal principal,
            Long orderId,
            String uploadId
    ) {
        requireAppUser(principal);
        directUploadService.cancel(
                principal,
                uploadId,
                StorageUploadProfile.AFTER_SALE_EVIDENCE,
                orderId
        );
    }

    private long requireAppUser(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() != TokenKind.APP) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.subjectId();
    }

    private java.util.Optional<OrderRow> findOwnedOrder(Long orderId, Long userId) {
        return jdbcClient.sql("""
                        select id as order_id,
                               status
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(this::mapOrder)
                .optional();
    }

    private OrderRow mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRow(
                rs.getLong("order_id"),
                rs.getString("status")
        );
    }

    private record OrderRow(Long orderId, String status) {
    }
}
