package org.muybaby.shopserver.aftersale.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.service.DirectUploadService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.muybaby.shopserver.storage.StorageProviderKind;
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
import org.springframework.web.multipart.MultipartFile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 售后凭证上传：普通上传与直传会话的订单归属校验收口在这里，
 * 申请前可上传图片或视频，申请后的读取必须校验用户与售后引用。
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
    private final StorageProvider storageProvider;

    public AfterSaleEvidenceService(
            JdbcClient jdbcClient,
            StorageService storageService,
            DirectUploadService directUploadService,
            StorageProvider storageProvider
    ) {
        this.jdbcClient = jdbcClient;
        this.storageService = storageService;
        this.directUploadService = directUploadService;
        this.storageProvider = storageProvider;
    }

    public ResponseEntity<InputStreamResource> evidence(
            AuthenticatedPrincipal principal, Long afterSaleId, Long fileId
    ) {
        long userId = requireAppUser(principal);
        EvidenceRow row = jdbcClient.sql("""
                        select asset.provider, asset.storage_container, asset.storage_region,
                               asset.object_key, asset.original_filename, asset.content_type
                        from after_sale_request request
                        join after_sale_evidence evidence on evidence.after_sale_id = request.id
                        join storage_asset asset on asset.id = evidence.file_id
                        where request.id = :afterSaleId and request.user_id = :userId
                          and request.app_deleted_at is null and evidence.file_id = :fileId
                          and asset.scope = 'ATTACHMENT' and asset.visibility = 'PRIVATE'
                          and asset.status = 'ACTIVE' and asset.media_kind in ('IMAGE', 'VIDEO')
                        """)
                .param("afterSaleId", afterSaleId).param("userId", userId).param("fileId", fileId)
                .query((rs, rowNum) -> new EvidenceRow(
                        new StorageObjectLocation(StorageProviderKind.valueOf(rs.getString("provider")),
                                rs.getString("storage_container"), rs.getString("storage_region"),
                                rs.getString("object_key")),
                        rs.getString("original_filename"), rs.getString("content_type")))
                .optional().orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        try {
            StoredObject object = storageProvider.open(row.location());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                            .filename(row.filename(), StandardCharsets.UTF_8).build().toString())
                    .contentType(MediaType.parseMediaType(row.contentType()))
                    .contentLength(object.sizeBytes())
                    .body(new InputStreamResource(object.inputStream()));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
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
        return directUploadService.createAfterSaleEvidence(principal, order.orderId(), request);
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
        return directUploadService.completeAfterSaleEvidence(
                principal,
                uploadId,
                order.orderId()
        ).asset();
    }

    public void cancelEvidenceUploadSession(
            AuthenticatedPrincipal principal,
            Long orderId,
            String uploadId
    ) {
        requireAppUser(principal);
        directUploadService.cancelAfterSaleEvidence(
                principal,
                uploadId,
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

    private record EvidenceRow(StorageObjectLocation location, String filename, String contentType) {
    }
}
