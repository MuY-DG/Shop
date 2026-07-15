package org.muybaby.shopserver.storage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageAssetScope;
import org.muybaby.shopserver.storage.StorageFileStatus;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.StorageUploadProfile;
import org.muybaby.shopserver.storage.UploadedByType;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.StorageAssetFolderRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetFolderResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetQueryRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetUsageResponse;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final String FOLDER_ENABLED = "ENABLED";
    private static final String FOLDER_DISABLED = "DISABLED";
    private static final String REFERENCE_REFERENCED = "REFERENCED";
    private static final String REFERENCE_UNREFERENCED = "UNREFERENCED";
    private static final String AFTER_SALE_ORDER_CONTEXT = "ORDER";
    private static final String CUSTOMER_SERVICE_CONVERSATION_CONTEXT = "CUSTOMER_SERVICE_CONVERSATION";
    private static final Duration AFTER_SALE_EVIDENCE_TTL = Duration.ofHours(24);
    private static final Duration PAYMENT_SECRET_STAGING_TTL = Duration.ofHours(2);

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageProvider storageProvider;
    private final UploadPolicy uploadPolicy;
    private final StorageObjectKeyGenerator storageObjectKeyGenerator;
    private final StorageUsageService storageUsageService;
    private final StorageRuntimeConfigService storageRuntimeConfigService;
    private final ObjectMapper objectMapper;

    public StorageService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageProvider storageProvider,
            UploadPolicy uploadPolicy,
            StorageObjectKeyGenerator storageObjectKeyGenerator,
            StorageUsageService storageUsageService,
            StorageRuntimeConfigService storageRuntimeConfigService,
            ObjectMapper objectMapper
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageProvider = storageProvider;
        this.uploadPolicy = uploadPolicy;
        this.storageObjectKeyGenerator = storageObjectKeyGenerator;
        this.storageUsageService = storageUsageService;
        this.storageRuntimeConfigService = storageRuntimeConfigService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StorageAssetResponse uploadLibrary(
            AuthenticatedPrincipal principal,
            Long folderId,
            MultipartFile file
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String contentType = defaultContentType(file.getContentType());
        StorageUploadProfile profile = uploadPolicy.detectLibraryProfile(originalFilename, contentType);
        return upload(
                principal,
                profile,
                normalizeFolderId(folderId),
                null,
                null,
                null,
                file,
                UploadedByType.ADMIN
        );
    }

    @Transactional
    public StorageAssetResponse uploadAfterSaleEvidence(
            AuthenticatedPrincipal principal,
            Long orderId,
            MultipartFile file
    ) {
        requirePrincipal(principal, TokenKind.APP);
        if (orderId == null || orderId <= 0 || !ownsOrder(principal.subjectId(), orderId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return upload(
                principal,
                StorageUploadProfile.AFTER_SALE_EVIDENCE,
                null,
                AFTER_SALE_ORDER_CONTEXT,
                orderId,
                databaseNow().plus(AFTER_SALE_EVIDENCE_TTL),
                file,
                UploadedByType.APP
        );
    }

    @Transactional
    public StorageAssetResponse uploadCustomerServiceImage(
            AuthenticatedPrincipal principal,
            Long conversationId,
            MultipartFile file
    ) {
        if (principal == null
                || (principal.kind() != TokenKind.APP && principal.kind() != TokenKind.ADMIN)
                || principal.subjectId() == null
                || conversationId == null
                || conversationId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return upload(
                principal,
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                null,
                CUSTOMER_SERVICE_CONVERSATION_CONTEXT,
                conversationId,
                null,
                file,
                principal.kind() == TokenKind.APP ? UploadedByType.APP : UploadedByType.ADMIN
        );
    }

    @Transactional
    public StorageAssetResponse uploadPaymentSecret(
            AuthenticatedPrincipal principal,
            MultipartFile file
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        return upload(
                principal,
                StorageUploadProfile.PAYMENT_SECRET,
                null,
                null,
                null,
                databaseNow().plus(PAYMENT_SECRET_STAGING_TTL),
                file,
                UploadedByType.ADMIN
        );
    }

    public PageResult<StorageAssetResponse> page(StorageAssetQueryRequest query) {
        StorageAssetQueryRequest normalized = query == null
                ? new StorageAssetQueryRequest(null, null, null, null, null, null, null, null)
                : query;
        validateQuery(normalized);

        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;
        StringBuilder predicate = new StringBuilder("""
                where a.scope = 'LIBRARY'
                  and a.status = 'ACTIVE'
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (StringUtils.hasText(normalized.keyword())) {
            predicate.append(" and lower(a.original_filename) like :keyword escape '!'");
            params.addValue("keyword", "%" + escapeKeywordLike(normalized.keyword().trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (normalized.mediaKind() != null) {
            predicate.append(" and a.media_kind = :mediaKind");
            params.addValue("mediaKind", normalized.mediaKind().name());
        }
        if (normalized.folderId() != null) {
            if (normalized.folderId() == 0L) {
                predicate.append(" and a.folder_id is null");
            } else {
                List<Long> folderIds = new ArrayList<>(descendantFolderIds(normalized.folderId()));
                if (folderIds.isEmpty()) {
                    throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
                }
                predicate.append(" and a.folder_id in (:folderIds)");
                params.addValue("folderIds", folderIds);
            }
        }

        String referenceStatus = normalizeReferenceStatus(normalized.referenceStatus());
        if (REFERENCE_REFERENCED.equals(referenceStatus)) {
            predicate.append("""
                    and exists (
                        select 1 from storage_asset_usage usage_ref
                        where usage_ref.asset_id = a.id and usage_ref.status = 'ACTIVE'
                    )
                    """);
        } else if (REFERENCE_UNREFERENCED.equals(referenceStatus)) {
            predicate.append("""
                    and not exists (
                        select 1 from storage_asset_usage usage_ref
                        where usage_ref.asset_id = a.id and usage_ref.status = 'ACTIVE'
                    )
                    """);
        }
        if (normalized.createdFrom() != null) {
            predicate.append(" and a.created_at >= :createdFrom");
            params.addValue("createdFrom", normalized.createdFrom());
        }
        if (normalized.createdTo() != null) {
            predicate.append(" and a.created_at <= :createdTo");
            params.addValue("createdTo", normalized.createdTo());
        }

        Long total = namedParameterJdbcTemplate.queryForObject(
                "select count(*) from storage_asset a " + predicate,
                params,
                Long.class
        );

        params.addValue("limit", size).addValue("offset", offset);
        List<StorageAssetResponse> records = namedParameterJdbcTemplate.query(
                assetSelect() + predicate + " order by a.created_at desc, a.id desc limit :limit offset :offset",
                params,
                (rs, rowNum) -> toResponse(mapAssetRow(rs, rowNum), null)
        );
        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public StorageAssetResponse detail(Long assetId) {
        AssetRow row = findLibraryAssetRow(assetId, false);
        return toResponse(row, storageUsageService.usages(assetId));
    }

    public List<StorageAssetUsageResponse> usages(Long assetId) {
        findLibraryAssetRow(assetId, false);
        return storageUsageService.usages(assetId);
    }

    @Transactional
    public StorageAssetResponse move(Long assetId, Long folderId) {
        Long normalizedFolderId = normalizeFolderId(folderId);
        lockFolderTree();
        if (normalizedFolderId != null) {
            requireEnabledFolderChain(normalizedFolderId, true);
        }
        AssetRow row = findLibraryAssetRow(assetId, true);
        jdbcClient.sql("""
                        update storage_asset
                        set folder_id = :folderId,
                            updated_at = current_timestamp
                        where id = :assetId
                        """)
                .param("folderId", normalizedFolderId)
                .param("assetId", row.id())
                .update();
        return detail(assetId);
    }

    @Transactional
    public void moveBatch(List<Long> assetIds, Long folderId) {
        if (assetIds == null || assetIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<Long> normalizedAssetIds = assetIds.stream().distinct().sorted().toList();
        Long normalizedFolderId = normalizeFolderId(folderId);
        lockFolderTree();
        if (normalizedFolderId != null) {
            requireEnabledFolderChain(normalizedFolderId, true);
        }
        normalizedAssetIds.forEach(assetId -> findLibraryAssetRow(assetId, true));
        namedParameterJdbcTemplate.update("""
                        update storage_asset
                        set folder_id = :folderId,
                            updated_at = current_timestamp
                        where id in (:assetIds)
                        """,
                new MapSqlParameterSource()
                        .addValue("folderId", normalizedFolderId)
                        .addValue("assetIds", normalizedAssetIds));
    }

    @Transactional
    public StorageAssetResponse updateDisplayName(Long assetId, String displayName) {
        AssetRow row = findLibraryAssetRow(assetId, true);
        String originalFilename = displayFilename(displayName, row.extension());
        jdbcClient.sql("""
                        update storage_asset
                        set original_filename = :originalFilename,
                            updated_at = current_timestamp
                        where id = :assetId
                        """)
                .param("originalFilename", originalFilename)
                .param("assetId", row.id())
                .update();
        return detail(assetId);
    }

    @Transactional
    public void delete(Long assetId) {
        AssetRow row = findLibraryAssetRow(assetId, true);
        if (storageUsageService.hasActiveUsages(assetId) || hasLocalPublicUrlReferences(row.publicUrl())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_IN_USE);
        }

        jdbcClient.sql("""
                        update storage_asset
                        set status = 'DELETED',
                            folder_id = null,
                            public_url = null,
                            deleted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :assetId
                        """)
                .param("assetId", assetId)
                .update();
        try {
            storageProvider.delete(row.objectLocation());
        } catch (RuntimeException ignored) {
            // Metadata is authoritative. Physical cleanup is best effort.
        }
    }

    public ResponseEntity<InputStreamResource> publicResource(String publicPath) {
        String normalizedPath = normalizePublicPath(publicPath);
        AssetRow row = namedParameterJdbcTemplate.query(
                        assetSelect() + """
                                where a.object_key = :objectKey
                                  and a.scope = 'LIBRARY'
                                  and a.visibility = 'PUBLIC'
                                  and a.status = 'ACTIVE'
                                """,
                        new MapSqlParameterSource("objectKey", "public/" + normalizedPath),
                        this::mapAssetRow
                ).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        try {
            StoredObject storedObject = storageProvider.open(row.objectLocation());
            MediaType mediaType = MediaType.parseMediaType(
                    StringUtils.hasText(storedObject.contentType()) ? storedObject.contentType() : row.contentType()
            );
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(row.sizeBytes())
                    .body(new InputStreamResource(storedObject.inputStream()));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    public ResponseEntity<InputStreamResource> customerServiceImageResource(
            Long assetId,
            Long conversationId
    ) {
        if (assetId == null || assetId <= 0 || conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        PrivateAttachmentRow row = jdbcClient.sql("""
                        select provider, storage_container, storage_region, object_key,
                               content_type, size_bytes
                        from storage_asset
                        where id = :assetId
                          and scope = 'ATTACHMENT'
                          and media_kind = 'IMAGE'
                          and visibility = 'PRIVATE'
                          and status = 'ACTIVE'
                          and upload_context_type = :contextType
                          and upload_context_id = :conversationId
                        """)
                .param("assetId", assetId)
                .param("contextType", CUSTOMER_SERVICE_CONVERSATION_CONTEXT)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new PrivateAttachmentRow(
                        StorageProviderKind.valueOf(rs.getString("provider")),
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        try {
            StoredObject storedObject = storageProvider.open(row.objectLocation());
            MediaType mediaType = MediaType.parseMediaType(
                    StringUtils.hasText(storedObject.contentType())
                            ? storedObject.contentType()
                            : row.contentType()
            );
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(row.sizeBytes())
                    .body(new InputStreamResource(storedObject.inputStream()));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    public List<StorageAssetFolderResponse> folderTree() {
        List<FolderRow> rows = jdbcClient.sql("""
                        select id, parent_id, name, sort_order, status, created_at, updated_at
                        from storage_asset_folder
                        order by parent_id asc, sort_order asc, id asc
                        """)
                .query(this::mapFolderRow)
                .list();
        return buildFolderTree(rows);
    }

    @Transactional
    public StorageAssetFolderResponse createFolder(StorageAssetFolderRequest request) {
        Long parentId = normalizeParentId(request.parentId());
        lockFolderTree();
        if (parentId != 0L) {
            requireEnabledFolderChain(parentId, true);
        }
        String status = normalizeFolderStatus(request.status());
        String name = normalizeFolderName(request.name());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into storage_asset_folder (parent_id, name, sort_order, status)
                            values (:parentId, :name, :sortOrder, :status)
                            """,
                    new MapSqlParameterSource()
                            .addValue("parentId", databaseParentId(parentId))
                            .addValue("name", name)
                            .addValue("sortOrder", normalizeSortOrder(request.sortOrder()))
                            .addValue("status", status),
                    keyHolder,
                    new String[]{"id"});
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }
        return requireFolderResponse(requireGeneratedId(keyHolder));
    }

    @Transactional
    public StorageAssetFolderResponse updateFolder(Long folderId, StorageAssetFolderRequest request) {
        lockFolderTree();
        FolderRow existing = findFolderRow(folderId, true);
        Long parentId = normalizeParentId(request.parentId());
        if (folderId.equals(parentId) || descendantFolderIds(folderId).contains(parentId)) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_CYCLE);
        }
        if (parentId != 0L) {
            requireEnabledFolderChain(parentId, true);
        }
        try {
            jdbcClient.sql("""
                            update storage_asset_folder
                            set parent_id = :parentId,
                                name = :name,
                                sort_order = :sortOrder,
                                status = :status,
                                updated_at = current_timestamp
                            where id = :folderId
                            """)
                    .param("parentId", databaseParentId(parentId))
                    .param("name", normalizeFolderName(request.name()))
                    .param("sortOrder", normalizeSortOrder(request.sortOrder()))
                    .param("status", normalizeFolderStatus(request.status()))
                    .param("folderId", existing.id())
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }
        return requireFolderResponse(folderId);
    }

    @Transactional
    public void deleteFolder(Long folderId) {
        lockFolderTree();
        FolderRow row = findFolderRow(folderId, true);
        Integer childCount = jdbcClient.sql("select count(*) from storage_asset_folder where parent_id = :folderId")
                .param("folderId", row.id())
                .query(Integer.class)
                .single();
        Integer assetCount = jdbcClient.sql("select count(*) from storage_asset where folder_id = :folderId")
                .param("folderId", row.id())
                .query(Integer.class)
                .single();
        if ((childCount != null && childCount > 0) || (assetCount != null && assetCount > 0)) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_IN_USE);
        }
        try {
            jdbcClient.sql("delete from storage_asset_folder where id = :folderId")
                    .param("folderId", row.id())
                    .update();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_IN_USE);
        }
    }

    private StorageAssetResponse upload(
            AuthenticatedPrincipal principal,
            StorageUploadProfile profile,
            Long folderId,
            String uploadContextType,
            Long uploadContextId,
            LocalDateTime expiresAt,
            MultipartFile file,
            UploadedByType uploadedByType
    ) {
        if (profile.scope() == StorageAssetScope.LIBRARY) {
            if (folderId != null) {
                requireEnabledFolderChain(folderId, false);
            }
        } else if (folderId != null) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String contentType = defaultContentType(file.getContentType());
        uploadPolicy.requireAllowed(profile, originalFilename, contentType, file.getSize(), true);

        byte[] bytes = readBytes(file);
        BufferedImage image = readImageIfNeeded(bytes, profile.mediaKind());
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                profile,
                originalFilename,
                contentType,
                bytes.length,
                profile.mediaKind() != StorageMediaKind.IMAGE || image != null
        );

        String objectKey = storageObjectKeyGenerator.nextKey(profile, decision.extension(), LocalDate.now());
        ResolvedStorageConfig storageConfig = storageRuntimeConfigService.effective();
        StorageObjectLocation objectLocation = objectLocation(storageConfig, objectKey);
        storageProvider.put(
                objectLocation,
                decision.contentType(),
                new ByteArrayInputStream(bytes),
                bytes.length
        );

        String publicUrl = decision.visibility() == FileVisibility.PUBLIC ? publicUrl(storageConfig, objectKey) : null;
        try {
            if (profile.scope() == StorageAssetScope.LIBRARY && folderId != null) {
                lockFolderTree();
                requireEnabledFolderChain(folderId, true);
            }
            jdbcClient.sql("""
                            insert into storage_asset
                                (scope, media_kind, folder_id, visibility, provider, storage_container, storage_region, object_key,
                                 original_filename, content_type, extension, size_bytes, sha256, width, height,
                                 duration_seconds, alt_text, tags_json, public_url, status, uploaded_by_type,
                                 uploaded_by_id, upload_context_type, upload_context_id, expires_at)
                            values
                                (:scope, :mediaKind, :folderId, :visibility, :provider, :storageContainer, :storageRegion, :objectKey,
                                 :originalFilename, :contentType, :extension, :sizeBytes, :sha256, :width, :height,
                                 null, '', null, :publicUrl, 'ACTIVE', :uploadedByType,
                                 :uploadedById, :uploadContextType, :uploadContextId, :expiresAt)
                            """)
                    .param("scope", decision.scope().name())
                    .param("mediaKind", decision.mediaKind().name())
                    .param("folderId", folderId)
                    .param("visibility", decision.visibility().name())
                    .param("provider", storageConfig.provider().name())
                    .param("storageContainer", objectLocation.container())
                    .param("storageRegion", objectLocation.region())
                    .param("objectKey", objectKey)
                    .param("originalFilename", originalFilename)
                    .param("contentType", decision.contentType())
                    .param("extension", decision.extension())
                    .param("sizeBytes", bytes.length)
                    .param("sha256", sha256(bytes))
                    .param("width", image == null ? null : image.getWidth())
                    .param("height", image == null ? null : image.getHeight())
                    .param("publicUrl", publicUrl)
                    .param("uploadedByType", uploadedByType.name())
                    .param("uploadedById", principal.subjectId())
                    .param("uploadContextType", uploadContextType)
                    .param("uploadContextId", uploadContextId)
                    .param("expiresAt", expiresAt)
                    .update();
        } catch (RuntimeException ex) {
            try {
                storageProvider.delete(objectLocation);
            } catch (RuntimeException cleanupFailure) {
                // No metadata row committed, so only provider inventory reconciliation can find this orphan.
                log.warn(
                        "Storage upload compensation failed: provider={}, objectKey={}, exception={}",
                        objectLocation.provider(), objectLocation.objectKey(),
                        cleanupFailure.getClass().getSimpleName()
                );
            }
            throw ex;
        }

        Long assetId = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();
        return toResponse(findAssetRow(assetId), List.of());
    }

    private String assetSelect() {
        return """
                select a.id, a.scope, a.media_kind, a.folder_id, a.visibility, a.provider,
                       a.storage_container, a.storage_region,
                       a.object_key, a.original_filename, a.content_type, a.extension, a.size_bytes,
                       a.sha256, a.width, a.height, a.duration_seconds, a.alt_text, a.tags_json,
                       a.public_url, a.status, a.uploaded_by_type, a.uploaded_by_id, a.expires_at,
                       a.deleted_at, a.created_at, a.updated_at,
                       (select count(*) from storage_asset_usage usage_count
                        where usage_count.asset_id = a.id and usage_count.status = 'ACTIVE') as usage_count
                from storage_asset a
                """;
    }

    private AssetRow findAssetRow(Long assetId) {
        return namedParameterJdbcTemplate.query(
                        assetSelect() + " where a.id = :assetId",
                        new MapSqlParameterSource("assetId", assetId),
                        this::mapAssetRow
                ).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private AssetRow findLibraryAssetRow(Long assetId, boolean forUpdate) {
        if (assetId == null || assetId <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        String sql = assetSelect() + """
                where a.id = :assetId
                  and a.scope = 'LIBRARY'
                  and a.status = 'ACTIVE'
                """ + (forUpdate ? " for update" : "");
        return namedParameterJdbcTemplate.query(
                        sql,
                        new MapSqlParameterSource("assetId", assetId),
                        this::mapAssetRow
                ).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private StorageAssetResponse toResponse(AssetRow row, List<StorageAssetUsageResponse> usages) {
        String visibleUrl = FileVisibility.PUBLIC.name().equals(row.visibility())
                && StorageFileStatus.ACTIVE.name().equals(row.status())
                ? row.publicUrl()
                : null;
        return new StorageAssetResponse(
                row.id(),
                row.scope(),
                row.mediaKind(),
                row.folderId(),
                row.visibility(),
                row.provider(),
                row.originalFilename(),
                row.contentType(),
                row.extension(),
                row.sizeBytes(),
                row.sha256(),
                row.width(),
                row.height(),
                row.durationSeconds(),
                row.altText(),
                parseTags(row.tagsJson()),
                row.status(),
                row.uploadedByType(),
                row.uploadedById(),
                visibleUrl,
                visibleUrl,
                row.usageCount(),
                row.createdAt(),
                row.updatedAt(),
                row.expiresAt(),
                row.deletedAt(),
                usages
        );
    }

    private AssetRow mapAssetRow(ResultSet rs, int rowNum) throws SQLException {
        return new AssetRow(
                rs.getLong("id"),
                rs.getString("scope"),
                rs.getString("media_kind"),
                rs.getObject("folder_id", Long.class),
                rs.getString("visibility"),
                rs.getString("provider"),
                rs.getString("storage_container"),
                rs.getString("storage_region"),
                rs.getString("object_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getString("extension"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("width", Integer.class),
                rs.getObject("height", Integer.class),
                rs.getObject("duration_seconds", Integer.class),
                rs.getString("alt_text"),
                rs.getString("tags_json"),
                rs.getString("public_url"),
                rs.getString("status"),
                rs.getString("uploaded_by_type"),
                rs.getLong("uploaded_by_id"),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getLong("usage_count")
        );
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, new TypeReference<>() {
            });
        } catch (IOException ex) {
            return List.of();
        }
    }

    private List<StorageAssetFolderResponse> buildFolderTree(List<FolderRow> rows) {
        Map<Long, MutableFolder> byId = new LinkedHashMap<>();
        for (FolderRow row : rows) {
            byId.put(row.id(), new MutableFolder(row));
        }
        List<MutableFolder> roots = new ArrayList<>();
        for (FolderRow row : rows) {
            MutableFolder current = byId.get(row.id());
            if (row.parentId() == 0L) {
                roots.add(current);
            } else {
                MutableFolder parent = byId.get(row.parentId());
                if (parent != null) {
                    parent.children.add(current);
                }
            }
        }
        return roots.stream().map(MutableFolder::toResponse).toList();
    }

    private StorageAssetFolderResponse requireFolderResponse(Long folderId) {
        FolderRow row = findFolderRow(folderId, false);
        return new StorageAssetFolderResponse(
                row.id(), row.parentId(), row.name(), row.sortOrder(), row.status(),
                row.createdAt(), row.updatedAt(), List.of()
        );
    }

    private FolderRow findFolderRow(Long folderId, boolean forUpdate) {
        if (folderId == null || folderId <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }
        return jdbcClient.sql("""
                        select id, parent_id, name, sort_order, status, created_at, updated_at
                        from storage_asset_folder
                        where id = :folderId
                        """ + (forUpdate ? " for update" : ""))
                .param("folderId", folderId)
                .query(this::mapFolderRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE));
    }

    private FolderRow mapFolderRow(ResultSet rs, int rowNum) throws SQLException {
        Long databaseParentId = rs.getObject("parent_id", Long.class);
        return new FolderRow(
                rs.getLong("id"),
                databaseParentId == null ? 0L : databaseParentId,
                rs.getString("name"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private Set<Long> descendantFolderIds(Long rootFolderId) {
        if (rootFolderId == null || rootFolderId <= 0) {
            return Set.of();
        }
        List<FolderEdge> edges = jdbcClient.sql("select id, parent_id from storage_asset_folder order by id for update")
                .query((rs, rowNum) -> {
                    Long parentId = rs.getObject("parent_id", Long.class);
                    return new FolderEdge(rs.getLong("id"), parentId == null ? 0L : parentId);
                })
                .list();
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        Set<Long> existingIds = new HashSet<>();
        for (FolderEdge edge : edges) {
            existingIds.add(edge.id());
            childrenByParent.computeIfAbsent(edge.parentId(), ignored -> new ArrayList<>()).add(edge.id());
        }
        if (!existingIds.contains(rootFolderId)) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        queue.add(rootFolderId);
        while (!queue.isEmpty()) {
            Long current = queue.removeFirst();
            if (!result.add(current)) {
                continue;
            }
            queue.addAll(childrenByParent.getOrDefault(current, List.of()));
        }
        return result;
    }

    private void requireEnabledFolderChain(Long folderId, boolean forUpdate) {
        Set<Long> visited = new HashSet<>();
        Long current = folderId;
        while (current != null && current != 0L) {
            if (!visited.add(current)) {
                throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_CYCLE);
            }
            FolderRow row = findFolderRow(current, forUpdate);
            if (!FOLDER_ENABLED.equals(row.status())) {
                throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
            }
            current = row.parentId();
        }
    }

    private boolean hasLocalPublicUrlReferences(String publicUrl) {
        if (!StringUtils.hasText(publicUrl)) {
            return false;
        }
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from (
                            select id from product_category where icon = :publicUrl
                            union all
                            select id from product_spu
                            where purged_at is null
                              and (main_image = :publicUrl or main_video = :publicUrl
                                   or locate(:publicUrl, detail_html) > 0)
                            union all
                            select image.id from product_spu_image image
                            join product_spu spu on spu.id = image.spu_id and spu.purged_at is null
                            where image.url = :publicUrl
                            union all
                            select sku.id from product_sku sku
                            join product_spu spu on spu.id = sku.spu_id and spu.purged_at is null
                            where sku.image = :publicUrl
                            union all
                            select spec_value.id from product_spu_spec_value spec_value
                            join product_spu_spec_group spec_group on spec_group.id = spec_value.group_id
                            join product_spu spu on spu.id = spec_group.spu_id and spu.purged_at is null
                            where spec_value.image = :publicUrl
                            union all
                            select id from product_guarantee_service
                            where deleted_at is null and icon = :publicUrl
                            union all
                            select id from home_banner where image_url = :publicUrl
                            union all
                            select id from order_item
                            where main_image = :publicUrl or sku_image = :publicUrl or display_image = :publicUrl
                        ) local_url_reference
                        """)
                .param("publicUrl", publicUrl)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    private boolean ownsOrder(Long userId, Long orderId) {
        Integer count = jdbcClient.sql("select count(*) from shop_order where id = :orderId and user_id = :userId")
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Integer.class)
                .single();
        return count != null && count == 1;
    }

    private void validateQuery(StorageAssetQueryRequest query) {
        if (query.folderId() != null && query.folderId() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (query.mediaKind() == StorageMediaKind.DOCUMENT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        normalizeReferenceStatus(query.referenceStatus());
        if (query.createdFrom() != null && query.createdTo() != null
                && query.createdFrom().isAfter(query.createdTo())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private String normalizeReferenceStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!REFERENCE_REFERENCED.equals(normalized) && !REFERENCE_UNREFERENCED.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private Long normalizeFolderId(Long folderId) {
        if (folderId == null || folderId == 0L) {
            return null;
        }
        if (folderId < 0) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }
        return folderId;
    }

    private Long normalizeParentId(Long parentId) {
        if (parentId == null || parentId < 0) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }
        return parentId;
    }

    private Long databaseParentId(Long parentId) {
        return parentId != null && parentId == 0L ? null : parentId;
    }

    private void lockFolderTree() {
        Long guardId = jdbcClient.sql("select id from storage_asset_folder_guard where id = 1 for update")
                .query(Long.class)
                .single();
        if (guardId == null || guardId != 1L) {
            throw new IllegalStateException("Storage asset folder guard is unavailable");
        }
    }

    private String normalizeFolderName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = name.trim();
        if (normalized.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private String normalizeFolderStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!FOLDER_ENABLED.equals(normalized) && !FOLDER_DISABLED.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return normalized;
    }

    private int normalizeSortOrder(Integer sortOrder) {
        return sortOrder == null ? 0 : sortOrder;
    }

    private void requirePrincipal(AuthenticatedPrincipal principal, TokenKind kind) {
        if (principal == null || principal.kind() != kind || principal.subjectId() == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private BufferedImage readImageIfNeeded(byte[] bytes, StorageMediaKind mediaKind) {
        if (mediaKind != StorageMediaKind.IMAGE) {
            return null;
        }
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(inputStream);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private String sanitizeFilename(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        String trimmed = originalFilename.trim();
        String fileNameOnly = Paths.get(trimmed).getFileName().toString();
        if (!trimmed.equals(fileNameOnly) || trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return trimmed;
    }

    private String displayFilename(String displayName, String extension) {
        if (!StringUtils.hasText(displayName)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String normalizedName = displayName.trim();
        if (".".equals(normalizedName)
                || "..".equals(normalizedName)
                || normalizedName.contains("/")
                || normalizedName.contains("\\")
                || normalizedName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        String filename = normalizedName + suffix;
        if (filename.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return filename;
    }

    private String defaultContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private String normalizePublicPath(String publicPath) {
        String normalized = publicPath == null ? "" : publicPath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized) || normalized.contains("..") || normalized.contains("\\")) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return normalized;
    }

    private String publicUrl(ResolvedStorageConfig config, String objectKey) {
        String baseUrl = config.publicBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (config.provider() == StorageProviderKind.TENCENT_COS) {
            return baseUrl + "/" + objectKey;
        }
        String relativePath = objectKey.startsWith("public/") ? objectKey.substring("public/".length()) : objectKey;
        return baseUrl + "/files/public/" + relativePath;
    }

    private StorageObjectLocation objectLocation(ResolvedStorageConfig config, String objectKey) {
        if (config.provider() == StorageProviderKind.LOCAL) {
            if (!StringUtils.hasText(config.localRoot())) {
                throw new IllegalStateException("Local storage root is not configured");
            }
            String normalizedRoot = Path.of(config.localRoot()).toAbsolutePath().normalize().toString();
            return new StorageObjectLocation(config.provider(), normalizedRoot, "", objectKey);
        }
        return new StorageObjectLocation(config.provider(), config.cosBucket(), config.cosRegion(), objectKey);
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String escapeKeywordLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Long requireGeneratedId(KeyHolder keyHolder) {
        Number key = keyHolder.getKey();
        if (key != null) {
            return key.longValue();
        }
        Map<String, Object> keys = keyHolder.getKeys();
        if (keys != null && keys.get("id") instanceof Number generatedId) {
            return generatedId.longValue();
        }
        throw new IllegalStateException("Failed to retrieve generated key");
    }

    private record AssetRow(
            Long id,
            String scope,
            String mediaKind,
            Long folderId,
            String visibility,
            String provider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            String originalFilename,
            String contentType,
            String extension,
            Long sizeBytes,
            String sha256,
            Integer width,
            Integer height,
            Integer durationSeconds,
            String altText,
            String tagsJson,
            String publicUrl,
            String status,
            String uploadedByType,
            Long uploadedById,
            LocalDateTime expiresAt,
            LocalDateTime deletedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            Long usageCount
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

    private record FolderRow(
            Long id,
            Long parentId,
            String name,
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private record FolderEdge(Long id, Long parentId) {
    }

    private record PrivateAttachmentRow(
            StorageProviderKind provider,
            String storageContainer,
            String storageRegion,
            String objectKey,
            String contentType,
            long sizeBytes
    ) {
        private StorageObjectLocation objectLocation() {
            return new StorageObjectLocation(provider, storageContainer, storageRegion, objectKey);
        }
    }

    private static final class MutableFolder {
        private final FolderRow row;
        private final List<MutableFolder> children = new ArrayList<>();

        private MutableFolder(FolderRow row) {
            this.row = row;
        }

        private StorageAssetFolderResponse toResponse() {
            return new StorageAssetFolderResponse(
                    row.id(), row.parentId(), row.name(), row.sortOrder(), row.status(),
                    row.createdAt(), row.updatedAt(), children.stream().map(MutableFolder::toResponse).toList()
            );
        }
    }
}
