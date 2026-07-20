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
import org.muybaby.shopserver.storage.dto.StorageAssetFolderPositionRequest;
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
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Duration CUSTOMER_SERVICE_IMAGE_STAGING_TTL = Duration.ofHours(2);
    private static final Duration PAYMENT_SECRET_STAGING_TTL = Duration.ofHours(2);
    private static final long IMAGE_VALIDATION_MAX_DECODED_PIXELS = 1_000_000L;
    private static final int IMAGE_VALIDATION_MAX_FRAMES = 16;
    private static final long IMAGE_VALIDATION_MAX_TOTAL_SOURCE_PIXELS = 100_000_000L;
    private static final String SVG_CONTENT_TYPE = "image/svg+xml";
    private static final String SVG_NAMESPACE = "http://www.w3.org/2000/svg";
    private static final int SVG_VALIDATION_MAX_ELEMENTS = 100_000;
    private static final Set<String> SVG_FORBIDDEN_ELEMENTS = Set.of(
            "script", "foreignobject", "iframe", "object", "embed", "audio", "video",
            "animate", "animatemotion", "animatetransform", "set", "discard", "handler", "listener"
    );
    private static final Pattern SVG_LENGTH = Pattern.compile(
            "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*(px|pt|pc|mm|cm|in)?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SVG_PERCENTAGE_LENGTH = Pattern.compile(
            "[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?%",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SVG_URL_REFERENCE = Pattern.compile(
            "url\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE
    );

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageProvider storageProvider;
    private final UploadPolicy uploadPolicy;
    private final StorageObjectKeyGenerator storageObjectKeyGenerator;
    private final StorageUsageService storageUsageService;
    private final StorageRuntimeConfigService storageRuntimeConfigService;
    private final StorageAssetCleanupService storageAssetCleanupService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransaction;
    private final TransactionTemplate withoutTransaction;
    private final Duration uploadPendingGrace;

    public StorageService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageProvider storageProvider,
            UploadPolicy uploadPolicy,
            StorageObjectKeyGenerator storageObjectKeyGenerator,
            StorageUsageService storageUsageService,
            StorageRuntimeConfigService storageRuntimeConfigService,
            StorageAssetCleanupService storageAssetCleanupService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${shop.storage.cleanup.upload-pending-grace:30m}") Duration uploadPendingGrace
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageProvider = storageProvider;
        this.uploadPolicy = uploadPolicy;
        this.storageObjectKeyGenerator = storageObjectKeyGenerator;
        this.storageUsageService = storageUsageService;
        this.storageRuntimeConfigService = storageRuntimeConfigService;
        this.storageAssetCleanupService = storageAssetCleanupService;
        this.objectMapper = objectMapper;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.withoutTransaction = new TransactionTemplate(transactionManager);
        this.withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.uploadPendingGrace = positiveDuration(uploadPendingGrace, Duration.ofMinutes(30));
    }

    public StorageAssetResponse uploadLibrary(
            AuthenticatedPrincipal principal,
            Long folderId,
            MultipartFile file
    ) {
        return outsideTransaction(() -> {
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
        });
    }

    public StorageAssetResponse uploadAfterSaleEvidence(
            AuthenticatedPrincipal principal,
            Long orderId,
            MultipartFile file
    ) {
        return outsideTransaction(() -> {
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
        });
    }

    public StorageAssetResponse uploadCustomerServiceImage(
            AuthenticatedPrincipal principal,
            Long conversationId,
            MultipartFile file
    ) {
        return outsideTransaction(() -> {
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
                    databaseNow().plus(CUSTOMER_SERVICE_IMAGE_STAGING_TTL),
                    file,
                    principal.kind() == TokenKind.APP ? UploadedByType.APP : UploadedByType.ADMIN
            );
        });
    }

    public StorageAssetResponse uploadPaymentSecret(
            AuthenticatedPrincipal principal,
            MultipartFile file
    ) {
        return outsideTransaction(() -> {
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
        });
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

    public void delete(Long assetId) {
        withoutTransaction.executeWithoutResult(status -> deleteOutsideTransaction(assetId));
    }

    private void deleteOutsideTransaction(Long assetId) {
        Boolean prepared = requiresNewTransaction.execute(status -> prepareLibraryDelete(assetId));
        if (!Boolean.TRUE.equals(prepared)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        cleanupAssetSafely(assetId, "library delete");
    }

    private boolean prepareLibraryDelete(Long assetId) {
        AssetRow row = findLibraryAssetRow(assetId, true);
        if (storageUsageService.hasActiveUsages(assetId) || hasLocalPublicUrlReferences(row.publicUrl())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_IN_USE);
        }

        return jdbcClient.sql("""
                        update storage_asset
                        set status = 'DELETE_PENDING',
                            folder_id = null,
                            public_url = null,
                            cleanup_attempts = 0,
                            cleanup_next_retry_at = current_timestamp,
                            cleanup_lease_token = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and status = 'ACTIVE'
                        """)
                .param("assetId", assetId)
                .update() == 1;
    }

    @Transactional
    public void bindCustomerServiceImage(Long assetId, Long conversationId) {
        int updated = jdbcClient.sql("""
                        update storage_asset
                        set expires_at = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and scope = 'ATTACHMENT'
                          and media_kind = 'IMAGE'
                          and status = 'ACTIVE'
                          and upload_context_type = :contextType
                          and upload_context_id = :conversationId
                          and expires_at is not null
                        """)
                .param("assetId", assetId)
                .param("contextType", CUSTOMER_SERVICE_CONVERSATION_CONTEXT)
                .param("conversationId", conversationId)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
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
        int sortOrder = nextFolderSortOrder(parentId);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            namedParameterJdbcTemplate.update("""
                            insert into storage_asset_folder (parent_id, name, sort_order, status)
                            values (:parentId, :name, :sortOrder, :status)
                            """,
                    new MapSqlParameterSource()
                            .addValue("parentId", databaseParentId(parentId))
                            .addValue("name", name)
                            .addValue("sortOrder", sortOrder)
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
    public StorageAssetFolderResponse updateFolderPosition(
            Long folderId,
            StorageAssetFolderPositionRequest request
    ) {
        lockFolderTree();
        FolderRow existing = findFolderRow(folderId, true);
        Long parentId = normalizeParentId(request.parentId());
        if (descendantFolderIds(folderId).contains(parentId)) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_CYCLE);
        }
        if (parentId != 0L) {
            requireEnabledFolderChain(parentId, true);
        }

        List<Long> sourceOrder = siblingFolderIds(existing.parentId(), folderId);
        List<Long> targetOrder = existing.parentId().equals(parentId)
                ? sourceOrder
                : siblingFolderIds(parentId, folderId);
        int targetIndex = Math.min(request.index(), targetOrder.size());
        targetOrder.add(targetIndex, folderId);

        if (!existing.parentId().equals(parentId)) {
            try {
                jdbcClient.sql("""
                                update storage_asset_folder
                                set parent_id = :parentId,
                                    updated_at = current_timestamp
                                where id = :folderId
                                """)
                        .param("parentId", databaseParentId(parentId))
                        .param("folderId", folderId)
                        .update();
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
            }
            updateFolderOrder(sourceOrder);
        }
        updateFolderOrder(targetOrder);
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
            // The enabled folder chain is locked and revalidated in the pending-row transaction.
        } else if (folderId != null) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_FOLDER_UNAVAILABLE);
        }

        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String contentType = defaultContentType(file.getContentType());
        UploadPolicy.UploadDecision preflight = uploadPolicy.requireAllowed(
                profile, originalFilename, contentType, file.getSize(), true);

        byte[] bytes = readBytes(file);
        ImageMetadata image = readImageMetadataIfNeeded(
                bytes, profile.mediaKind(), preflight.contentType());
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
        String publicUrl = decision.visibility() == FileVisibility.PUBLIC ? publicUrl(storageConfig, objectKey) : null;

        Long assetId = requiresNewTransaction.execute(status -> insertPendingUpload(
                principal,
                decision,
                folderId,
                uploadContextType,
                uploadContextId,
                expiresAt,
                uploadedByType,
                originalFilename,
                objectLocation,
                bytes,
                image
        ));
        if (assetId == null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }

        try {
            storageProvider.put(
                    objectLocation,
                    decision.contentType(),
                    new ByteArrayInputStream(bytes),
                    bytes.length
            );
        } catch (RuntimeException ex) {
            scheduleUploadCleanup(assetId, "provider put failed");
            throw ex;
        }

        try {
            Boolean activated = requiresNewTransaction.execute(status -> activateUpload(assetId, publicUrl));
            if (!Boolean.TRUE.equals(activated)) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
        } catch (RuntimeException ex) {
            scheduleUploadCleanup(assetId, "upload activation failed");
            throw ex;
        }
        return toResponse(findAssetRow(assetId), List.of());
    }

    private Long insertPendingUpload(
            AuthenticatedPrincipal principal,
            UploadPolicy.UploadDecision decision,
            Long folderId,
            String uploadContextType,
            Long uploadContextId,
            LocalDateTime expiresAt,
            UploadedByType uploadedByType,
            String originalFilename,
            StorageObjectLocation objectLocation,
            byte[] bytes,
            ImageMetadata image
    ) {
        if (decision.scope() == StorageAssetScope.LIBRARY && folderId != null) {
            lockFolderTree();
            requireEnabledFolderChain(folderId, true);
        }
        LocalDateTime cleanupNotBefore = databaseNow().plus(uploadPendingGrace);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, folder_id, visibility, provider, storage_container, storage_region, object_key,
                             original_filename, content_type, extension, size_bytes, sha256, width, height,
                             duration_seconds, alt_text, tags_json, public_url, status, uploaded_by_type,
                             uploaded_by_id, upload_context_type, upload_context_id, expires_at,
                             cleanup_attempts, cleanup_next_retry_at, cleanup_lease_token)
                        values
                            (:scope, :mediaKind, :folderId, :visibility, :provider, :storageContainer, :storageRegion, :objectKey,
                             :originalFilename, :contentType, :extension, :sizeBytes, :sha256, :width, :height,
                             null, '', null, null, 'UPLOAD_PENDING', :uploadedByType,
                             :uploadedById, :uploadContextType, :uploadContextId, :expiresAt,
                             0, :cleanupNotBefore, null)
                        """)
                .param("scope", decision.scope().name())
                .param("mediaKind", decision.mediaKind().name())
                .param("folderId", folderId)
                .param("visibility", decision.visibility().name())
                .param("provider", objectLocation.provider().name())
                .param("storageContainer", objectLocation.container())
                .param("storageRegion", objectLocation.region())
                .param("objectKey", objectLocation.objectKey())
                .param("originalFilename", originalFilename)
                .param("contentType", decision.contentType())
                .param("extension", decision.extension())
                .param("sizeBytes", bytes.length)
                .param("sha256", sha256(bytes))
                .param("width", image == null ? null : image.width())
                .param("height", image == null ? null : image.height())
                .param("uploadedByType", uploadedByType.name())
                .param("uploadedById", principal.subjectId())
                .param("uploadContextType", uploadContextType)
                .param("uploadContextId", uploadContextId)
                .param("expiresAt", expiresAt)
                .param("cleanupNotBefore", cleanupNotBefore)
                .update(keyHolder, "id");
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return keyHolder.getKey().longValue();
    }

    private boolean activateUpload(Long assetId, String publicUrl) {
        return jdbcClient.sql("""
                        update storage_asset
                        set status = 'ACTIVE',
                            public_url = :publicUrl,
                            cleanup_attempts = 0,
                            cleanup_next_retry_at = null,
                            cleanup_lease_token = null,
                            updated_at = current_timestamp
                        where id = :assetId
                          and status = 'UPLOAD_PENDING'
                        """)
                .param("publicUrl", publicUrl)
                .param("assetId", assetId)
                .update() == 1;
    }

    private void scheduleUploadCleanup(Long assetId, String reason) {
        try {
            Boolean scheduled = requiresNewTransaction.execute(status -> jdbcClient.sql("""
                            update storage_asset
                            set status = 'DELETE_PENDING',
                                public_url = null,
                                cleanup_attempts = 0,
                                cleanup_next_retry_at = current_timestamp,
                                cleanup_lease_token = null,
                                updated_at = current_timestamp
                            where id = :assetId
                              and status = 'UPLOAD_PENDING'
                            """)
                    .param("assetId", assetId)
                    .update() == 1);
            if (Boolean.TRUE.equals(scheduled)) {
                cleanupAssetSafely(assetId, reason);
            }
        } catch (RuntimeException cleanupStateFailure) {
            log.warn(
                    "Storage upload cleanup state could not be persisted: assetId={}, reason={}, exception={}",
                    assetId, reason, cleanupStateFailure.getClass().getSimpleName()
            );
        }
    }

    private void cleanupAssetSafely(Long assetId, String reason) {
        try {
            storageAssetCleanupService.cleanupAsset(assetId);
        } catch (RuntimeException cleanupFailure) {
            log.warn(
                    "Storage cleanup will be retried by the scheduled worker: assetId={}, reason={}, exception={}",
                    assetId, reason, cleanupFailure.getClass().getSimpleName()
            );
        }
    }

    private <T> T outsideTransaction(Supplier<T> action) {
        return Objects.requireNonNull(withoutTransaction.execute(status -> action.get()));
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
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

    private List<Long> siblingFolderIds(Long parentId, Long excludedFolderId) {
        if (parentId == null || parentId == 0L) {
            return new ArrayList<>(jdbcClient.sql("""
                            select id
                            from storage_asset_folder
                            where parent_id is null and id <> :excludedFolderId
                            order by sort_order asc, id asc
                            for update
                            """)
                    .param("excludedFolderId", excludedFolderId)
                    .query(Long.class)
                    .list());
        }
        return new ArrayList<>(jdbcClient.sql("""
                        select id
                        from storage_asset_folder
                        where parent_id = :parentId and id <> :excludedFolderId
                        order by sort_order asc, id asc
                        for update
                        """)
                .param("parentId", parentId)
                .param("excludedFolderId", excludedFolderId)
                .query(Long.class)
                .list());
    }

    private int nextFolderSortOrder(Long parentId) {
        Integer maximumSortOrder;
        if (parentId == null || parentId == 0L) {
            maximumSortOrder = jdbcClient.sql("""
                            select coalesce(max(sort_order), -1)
                            from storage_asset_folder
                            where parent_id is null
                            """)
                    .query(Integer.class)
                    .single();
        } else {
            maximumSortOrder = jdbcClient.sql("""
                            select coalesce(max(sort_order), -1)
                            from storage_asset_folder
                            where parent_id = :parentId
                            """)
                    .param("parentId", parentId)
                    .query(Integer.class)
                    .single();
        }
        return maximumSortOrder + 1;
    }

    private void updateFolderOrder(List<Long> folderIds) {
        for (int index = 0; index < folderIds.size(); index++) {
            jdbcClient.sql("""
                            update storage_asset_folder
                            set sort_order = :sortOrder,
                                updated_at = current_timestamp
                            where id = :folderId
                            """)
                    .param("sortOrder", index)
                    .param("folderId", folderIds.get(index))
                    .update();
        }
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

    private ImageMetadata readImageMetadataIfNeeded(
            byte[] bytes,
            StorageMediaKind mediaKind,
            String expectedContentType
    ) {
        if (mediaKind != StorageMediaKind.IMAGE) {
            return null;
        }
        if (SVG_CONTENT_TYPE.equals(expectedContentType)) {
            return readSvgMetadata(bytes);
        }
        try (ImageInputStream imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
            ImageReader reader = readers.next();
            try {
                requireExpectedImageFormat(reader.getFormatName(), expectedContentType);
                reader.setInput(imageInput, false, true);
                if ("image/gif".equals(expectedContentType)) {
                    requireAllowedGifCanvas(reader);
                }
                int frameCount = reader.getNumImages(true);
                if (frameCount < 1 || frameCount > IMAGE_VALIDATION_MAX_FRAMES) {
                    throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                }
                int firstWidth = 0;
                int firstHeight = 0;
                long totalSourcePixels = 0;
                for (int frame = 0; frame < frameCount; frame++) {
                    int width = reader.getWidth(frame);
                    int height = reader.getHeight(frame);
                    uploadPolicy.requireAllowedImageDimensions(width, height);
                    long pixels = (long) width * height;
                    if (pixels > IMAGE_VALIDATION_MAX_TOTAL_SOURCE_PIXELS - totalSourcePixels) {
                        throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                    }
                    totalSourcePixels += pixels;
                    if (frame == 0) {
                        firstWidth = width;
                        firstHeight = height;
                    }
                    ImageReadParam readParam = reader.getDefaultReadParam();
                    int subsampling = Math.max(1, (int) Math.ceil(Math.sqrt(
                            (double) pixels / IMAGE_VALIDATION_MAX_DECODED_PIXELS)));
                    readParam.setSourceSubsampling(subsampling, subsampling, 0, 0);
                    reader.read(frame, readParam);
                }
                return new ImageMetadata(firstWidth, firstHeight);
            } finally {
                reader.dispose();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private ImageMetadata readSvgMetadata(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            Element root = document.getDocumentElement();
            if (root == null
                    || !"svg".equalsIgnoreCase(root.getLocalName())
                    || !SVG_NAMESPACE.equals(root.getNamespaceURI())) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }

            requireSafeSvgTree(document);
            double[] viewBox = parseSvgViewBox(root.getAttribute("viewBox"));
            Double declaredWidth = parseSvgLength(root.getAttribute("width"));
            Double declaredHeight = parseSvgLength(root.getAttribute("height"));
            double width = declaredWidth != null ? declaredWidth : 300d;
            double height = declaredHeight != null ? declaredHeight : 150d;
            if (viewBox != null) {
                if (declaredWidth == null && declaredHeight == null) {
                    width = viewBox[0];
                    height = viewBox[1];
                } else if (declaredWidth == null) {
                    width = declaredHeight * viewBox[0] / viewBox[1];
                } else if (declaredHeight == null) {
                    height = declaredWidth * viewBox[1] / viewBox[0];
                }
            }
            if (!Double.isFinite(width) || !Double.isFinite(height)
                    || width <= 0 || height <= 0
                    || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
            int pixelWidth = (int) Math.ceil(width);
            int pixelHeight = (int) Math.ceil(height);
            uploadPolicy.requireAllowedImageDimensions(pixelWidth, pixelHeight);
            return new ImageMetadata(pixelWidth, pixelHeight);
        } catch (BusinessException ex) {
            throw ex;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private void requireSafeSvgTree(Node root) {
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(root);
        int elementCount = 0;
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elementCount++;
                if (elementCount > SVG_VALIDATION_MAX_ELEMENTS) {
                    throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                }
                String localName = node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
                String normalizedName = localName.toLowerCase(Locale.ROOT);
                if (SVG_FORBIDDEN_ELEMENTS.contains(normalizedName)) {
                    throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                }
                NamedNodeMap attributes = node.getAttributes();
                for (int index = 0; index < attributes.getLength(); index++) {
                    Node attribute = attributes.item(index);
                    String attributeName = attribute.getLocalName() == null
                            ? attribute.getNodeName()
                            : attribute.getLocalName();
                    String normalizedAttributeName = attributeName.toLowerCase(Locale.ROOT);
                    String value = attribute.getNodeValue() == null ? "" : attribute.getNodeValue().trim();
                    if (normalizedAttributeName.startsWith("on")
                            || (("href".equals(normalizedAttributeName) || "src".equals(normalizedAttributeName))
                            && !isSafeSvgReference(value))) {
                        throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                    }
                    requireSafeSvgCss(value);
                }
                if ("style".equals(normalizedName)) {
                    requireSafeSvgCss(node.getTextContent());
                }
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.addLast(child);
            }
        }
    }

    private boolean isSafeSvgReference(String reference) {
        return reference.isEmpty() || reference.startsWith("#");
    }

    private void requireSafeSvgCss(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (normalized.contains("javascript:")
                || normalized.contains("@import")
                || normalized.contains("expression(")
                || normalized.contains("-moz-binding")) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        Matcher matcher = SVG_URL_REFERENCE.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (target.length() >= 2
                    && ((target.startsWith("\"") && target.endsWith("\""))
                    || (target.startsWith("'") && target.endsWith("'")))) {
                target = target.substring(1, target.length() - 1).trim();
            }
            if (!target.startsWith("#")) {
                throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
            }
        }
    }

    private double[] parseSvgViewBox(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String[] parts = value.trim().split("[\\s,]+");
        if (parts.length != 4) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        double width = Double.parseDouble(parts[2]);
        double height = Double.parseDouble(parts[3]);
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return new double[]{width, height};
    }

    private Double parseSvgLength(String value) {
        if (!StringUtils.hasText(value) || SVG_PERCENTAGE_LENGTH.matcher(value.trim()).matches()) {
            return null;
        }
        Matcher matcher = SVG_LENGTH.matcher(value.trim());
        if (!matcher.matches()) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        double amount = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
        double pixels = switch (unit) {
            case "", "px" -> amount;
            case "pt" -> amount * 96d / 72d;
            case "pc" -> amount * 16d;
            case "mm" -> amount * 96d / 25.4d;
            case "cm" -> amount * 96d / 2.54d;
            case "in" -> amount * 96d;
            default -> throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        };
        if (!Double.isFinite(pixels) || pixels <= 0) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return pixels;
    }

    private void requireExpectedImageFormat(String readerFormat, String expectedContentType) {
        String actual = readerFormat == null ? "" : readerFormat.trim().toLowerCase(Locale.ROOT);
        String expected = switch (expectedContentType) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "";
        };
        if ("jpg".equals(actual)) {
            actual = "jpeg";
        }
        if (!expected.equals(actual)) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private void requireAllowedGifCanvas(ImageReader reader) throws IOException {
        IIOMetadata metadata = reader.getStreamMetadata();
        if (metadata == null || metadata.getNativeMetadataFormatName() == null) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!"LogicalScreenDescriptor".equals(child.getNodeName())) {
                continue;
            }
            NamedNodeMap attributes = child.getAttributes();
            int width = Integer.parseInt(attributes.getNamedItem("logicalScreenWidth").getNodeValue());
            int height = Integer.parseInt(attributes.getNamedItem("logicalScreenHeight").getNodeValue());
            uploadPolicy.requireAllowedImageDimensions(width, height);
            return;
        }
        throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
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

    private record ImageMetadata(int width, int height) {
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
