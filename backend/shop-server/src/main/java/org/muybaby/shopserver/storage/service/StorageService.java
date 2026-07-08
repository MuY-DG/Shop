package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageFileStatus;
import org.muybaby.shopserver.storage.StorageProperties;
import org.muybaby.shopserver.storage.StoragePurpose;
import org.muybaby.shopserver.storage.UploadedByType;
import org.muybaby.shopserver.storage.dto.StorageAssetCategoryRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetCategoryResponse;
import org.muybaby.shopserver.storage.dto.StorageFileQueryRequest;
import org.muybaby.shopserver.storage.dto.StorageFileResponse;
import org.muybaby.shopserver.storage.dto.StorageFileUsageResponse;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StorageService {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final StorageProvider storageProvider;
    private final UploadPolicy uploadPolicy;
    private final StorageObjectKeyGenerator storageObjectKeyGenerator;
    private final StorageUsageService storageUsageService;
    private final StorageProperties storageProperties;

    public StorageService(
            JdbcClient jdbcClient,
            NamedParameterJdbcTemplate namedParameterJdbcTemplate,
            StorageProvider storageProvider,
            UploadPolicy uploadPolicy,
            StorageObjectKeyGenerator storageObjectKeyGenerator,
            StorageUsageService storageUsageService,
            StorageProperties storageProperties
    ) {
        this.jdbcClient = jdbcClient;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.storageProvider = storageProvider;
        this.uploadPolicy = uploadPolicy;
        this.storageObjectKeyGenerator = storageObjectKeyGenerator;
        this.storageUsageService = storageUsageService;
        this.storageProperties = storageProperties;
    }

    @Transactional
    public StorageFileResponse uploadAdmin(AuthenticatedPrincipal principal, StoragePurpose purpose, Long assetCategoryId, MultipartFile file) {
        return upload(principal, purpose, assetCategoryId, file, UploadedByType.ADMIN);
    }

    @Transactional
    public StorageFileResponse uploadAdmin(AuthenticatedPrincipal principal, String purpose, Long assetCategoryId, MultipartFile file) {
        return uploadAdmin(principal, parsePurpose(purpose), assetCategoryId, file);
    }

    @Transactional
    public StorageFileResponse uploadApp(AuthenticatedPrincipal principal, StoragePurpose purpose, Long assetCategoryId, MultipartFile file) {
        if (purpose != StoragePurpose.AFTER_SALE_IMAGE && purpose != StoragePurpose.REFUND_EVIDENCE) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return upload(principal, purpose, assetCategoryId, file, UploadedByType.APP);
    }

    @Transactional
    public StorageFileResponse uploadApp(AuthenticatedPrincipal principal, String purpose, Long assetCategoryId, MultipartFile file) {
        return uploadApp(principal, parsePurpose(purpose), assetCategoryId, file);
    }

    public PageResult<StorageFileResponse> page(StorageFileQueryRequest query) {
        StorageFileQueryRequest normalized = query == null ? new StorageFileQueryRequest(null, null, null, null, null, null) : query;
        long current = normalized.pageCurrent();
        long size = normalized.pageSize();
        long offset = (current - 1) * size;

        Long total = jdbcClient.sql("""
                        select count(*)
                        from storage_file
                        where (:purpose is null or purpose = :purpose)
                          and (:assetCategoryId is null or asset_category_id = :assetCategoryId)
                          and (:visibility is null or visibility = :visibility)
                          and (:status is null or status = :status)
                        """)
                .param("purpose", blankToNull(normalized.purpose()))
                .param("assetCategoryId", normalized.assetCategoryId())
                .param("visibility", blankToNull(normalized.visibility()))
                .param("status", blankToNull(normalized.status()))
                .query(Long.class)
                .single();

        List<StorageFileResponse> records = jdbcClient.sql("""
                        select id, purpose, asset_category_id, visibility, provider, original_filename, content_type,
                               extension, size_bytes, sha256, width, height, status, uploaded_by_type, uploaded_by_id,
                               public_url, created_at, updated_at, deleted_at
                        from storage_file
                        where (:purpose is null or purpose = :purpose)
                          and (:assetCategoryId is null or asset_category_id = :assetCategoryId)
                          and (:visibility is null or visibility = :visibility)
                          and (:status is null or status = :status)
                        order by created_at desc, id desc
                        limit :limit offset :offset
                        """)
                .param("purpose", blankToNull(normalized.purpose()))
                .param("assetCategoryId", normalized.assetCategoryId())
                .param("visibility", blankToNull(normalized.visibility()))
                .param("status", blankToNull(normalized.status()))
                .param("limit", size)
                .param("offset", offset)
                .query((rs, rowNum) -> mapFile(rs, null))
                .list();

        return PageResult.of(records, total == null ? 0L : total, current, size);
    }

    public StorageFileResponse detail(Long fileId) {
        FileRow row = findFileRow(fileId);
        return toResponse(row, storageUsageService.usages(fileId));
    }

    public List<StorageFileUsageResponse> usages(Long fileId) {
        ensureFileExists(fileId);
        return storageUsageService.usages(fileId);
    }

    @Transactional
    public void move(Long fileId, Long assetCategoryId) {
        ensureFileExists(fileId);
        requireCategoryExists(assetCategoryId);
        jdbcClient.sql("""
                        update storage_file
                        set asset_category_id = :assetCategoryId,
                            updated_at = current_timestamp
                        where id = :fileId
                        """)
                .param("assetCategoryId", assetCategoryId)
                .param("fileId", fileId)
                .update();
    }

    @Transactional
    public void delete(Long fileId) {
        FileRow row = findFileRow(fileId);
        if (StorageFileStatus.DELETED.name().equals(row.status())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        if (storageUsageService.hasActiveUsages(fileId) || hasLocalPublicUrlReferences(row.publicUrl())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_IN_USE);
        }

        jdbcClient.sql("""
                        update storage_file
                        set status = 'DELETED',
                            public_url = null,
                            deleted_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :fileId
                        """)
                .param("fileId", fileId)
                .update();
        try {
            storageProvider.delete(row.objectKey());
        } catch (RuntimeException ignored) {
            // Metadata soft-delete is authoritative; best-effort provider cleanup happens after status update.
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
                            where main_image = :publicUrl
                               or detail_html like :detailPattern escape '\\'
                            union all
                            select id from product_spu_image where url = :publicUrl
                            union all
                            select id from product_sku where image = :publicUrl
                            union all
                            select id from home_banner where image_url = :publicUrl
                            union all
                            select id from order_item
                            where main_image = :publicUrl
                               or sku_image = :publicUrl
                               or display_image = :publicUrl
                        ) local_url_reference
                        """)
                .param("publicUrl", publicUrl)
                .param("detailPattern", "%" + escapeLike(publicUrl) + "%")
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public ResponseEntity<InputStreamResource> publicResource(String publicPath) {
        String normalizedPath = normalizePublicPath(publicPath);
        FileRow row = jdbcClient.sql("""
                        select id, purpose, asset_category_id, visibility, provider, object_key, original_filename, content_type,
                               extension, size_bytes, sha256, width, height, status, uploaded_by_type, uploaded_by_id,
                               public_url, created_at, updated_at, deleted_at
                        from storage_file
                        where object_key = :objectKey
                          and visibility = 'PUBLIC'
                          and status = 'ACTIVE'
                        """)
                .param("objectKey", "public/" + normalizedPath)
                .query(this::mapFileRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));

        try {
            StoredObject storedObject = storageProvider.open(row.objectKey());
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

    public List<StorageAssetCategoryResponse> categoryTree() {
        List<CategoryRow> rows = jdbcClient.sql("""
                        select id, parent_id, name, code, description, sort_order, status, created_at, updated_at
                        from storage_asset_category
                        order by parent_id asc, sort_order asc, id asc
                        """)
                .query(this::mapCategory)
                .list();

        Map<Long, MutableCategory> byId = new LinkedHashMap<>();
        for (CategoryRow row : rows) {
            byId.put(row.id(), new MutableCategory(row));
        }

        List<MutableCategory> roots = new ArrayList<>();
        for (CategoryRow row : rows) {
            MutableCategory current = byId.get(row.id());
            if (row.parentId() == 0L) {
                roots.add(current);
                continue;
            }
            MutableCategory parent = byId.get(row.parentId());
            if (parent != null) {
                parent.children.add(current);
            }
        }

        return roots.stream().map(MutableCategory::toResponse).toList();
    }

    @Transactional
    public Long createCategory(StorageAssetCategoryRequest request) {
        requireParentExists(request.parentId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("""
                        insert into storage_asset_category
                            (parent_id, name, code, description, sort_order, status)
                        values
                            (:parentId, :name, :code, :description, :sortOrder, :status)
                        """,
                new MapSqlParameterSource()
                        .addValue("parentId", request.parentId())
                        .addValue("name", request.name().trim())
                        .addValue("code", request.code().trim())
                        .addValue("description", request.description() == null ? "" : request.description().trim())
                        .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                        .addValue("status", request.status().trim()),
                keyHolder,
                new String[]{"id"});
        return requireGeneratedId(keyHolder);
    }

    @Transactional
    public void updateCategory(Long categoryId, StorageAssetCategoryRequest request) {
        requireCategoryExists(categoryId);
        requireParentExists(request.parentId());
        jdbcClient.sql("""
                        update storage_asset_category
                        set parent_id = :parentId,
                            name = :name,
                            code = :code,
                            description = :description,
                            sort_order = :sortOrder,
                            status = :status,
                            updated_at = current_timestamp
                        where id = :categoryId
                        """)
                .param("parentId", request.parentId())
                .param("name", request.name().trim())
                .param("code", request.code().trim())
                .param("description", request.description() == null ? "" : request.description().trim())
                .param("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .param("status", request.status().trim())
                .param("categoryId", categoryId)
                .update();
    }

    private StorageFileResponse upload(
            AuthenticatedPrincipal principal,
            StoragePurpose purpose,
            Long assetCategoryId,
            MultipartFile file,
            UploadedByType uploadedByType
    ) {
        requireCategoryIfPresent(assetCategoryId);
        String originalFilename = sanitizeFilename(file.getOriginalFilename());
        String contentType = defaultContentType(file.getContentType());
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                purpose,
                originalFilename,
                contentType,
                file.getSize(),
                true
        );
        byte[] bytes = readBytes(file);
        BufferedImage image = readImageIfNeeded(bytes, purpose);
        decision = uploadPolicy.requireAllowed(
                purpose,
                originalFilename,
                contentType,
                bytes.length,
                !purpose.image() || image != null
        );

        String objectKey = storageObjectKeyGenerator.nextKey(purpose, decision.extension(), LocalDate.now());
        storageProvider.put(objectKey, decision.contentType(), new ByteArrayInputStream(bytes), bytes.length);

        String publicUrl = decision.visibility() == FileVisibility.PUBLIC ? publicUrl(objectKey) : null;
        jdbcClient.sql("""
                        insert into storage_file
                            (purpose, asset_category_id, visibility, provider, bucket, object_key, original_filename,
                             content_type, extension, size_bytes, sha256, width, height, alt_text, tags_json,
                             public_url, status, uploaded_by_type, uploaded_by_id)
                        values
                            (:purpose, :assetCategoryId, :visibility, :provider, '', :objectKey, :originalFilename,
                             :contentType, :extension, :sizeBytes, :sha256, :width, :height, '', null,
                             :publicUrl, 'ACTIVE', :uploadedByType, :uploadedById)
                        """)
                .param("purpose", purpose.name())
                .param("assetCategoryId", assetCategoryId)
                .param("visibility", decision.visibility().name())
                .param("provider", storageProperties.provider().name())
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
                .update();

        Long fileId = jdbcClient.sql("select id from storage_file where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();

        return detail(fileId);
    }

    private void requireCategoryIfPresent(Long assetCategoryId) {
        if (assetCategoryId != null) {
            requireCategoryExists(assetCategoryId);
        }
    }

    private void requireParentExists(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        requireCategoryExists(parentId);
    }

    private void requireCategoryExists(Long categoryId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from storage_asset_category
                        where id = :categoryId
                        """)
                .param("categoryId", categoryId)
                .query(Integer.class)
                .single();
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.STORAGE_ASSET_CATEGORY_UNAVAILABLE);
        }
    }

    private void ensureFileExists(Long fileId) {
        jdbcClient.sql("""
                        select id
                        from storage_file
                        where id = :fileId
                        """)
                .param("fileId", fileId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private FileRow findFileRow(Long fileId) {
        return jdbcClient.sql("""
                        select id, purpose, asset_category_id, visibility, provider, object_key, original_filename, content_type,
                               extension, size_bytes, sha256, width, height, status, uploaded_by_type, uploaded_by_id,
                               public_url, created_at, updated_at, deleted_at
                        from storage_file
                        where id = :fileId
                        """)
                .param("fileId", fileId)
                .query(this::mapFileRow)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private StorageFileResponse toResponse(FileRow row, List<StorageFileUsageResponse> usages) {
        return new StorageFileResponse(
                row.id(),
                row.purpose(),
                row.assetCategoryId(),
                row.visibility(),
                row.provider(),
                row.originalFilename(),
                row.contentType(),
                row.extension(),
                row.sizeBytes(),
                row.sha256(),
                row.width(),
                row.height(),
                row.status(),
                row.uploadedByType(),
                row.uploadedById(),
                FileVisibility.PUBLIC.name().equals(row.visibility()) && StorageFileStatus.ACTIVE.name().equals(row.status()) ? row.publicUrl() : null,
                FileVisibility.PUBLIC.name().equals(row.visibility()) && StorageFileStatus.ACTIVE.name().equals(row.status()) ? row.publicUrl() : null,
                row.createdAt(),
                row.updatedAt(),
                row.deletedAt(),
                usages
        );
    }

    private StorageFileResponse mapFile(ResultSet rs, List<StorageFileUsageResponse> usages) throws SQLException {
        return toResponse(mapFileRow(rs, 0), usages);
    }

    private FileRow mapFileRow(ResultSet rs, int rowNum) throws SQLException {
        return new FileRow(
                rs.getLong("id"),
                rs.getString("purpose"),
                rs.getObject("asset_category_id", Long.class),
                rs.getString("visibility"),
                rs.getString("provider"),
                hasColumn(rs, "object_key") ? rs.getString("object_key") : null,
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getString("extension"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getObject("width", Integer.class),
                rs.getObject("height", Integer.class),
                rs.getString("status"),
                rs.getString("uploaded_by_type"),
                rs.getLong("uploaded_by_id"),
                rs.getString("public_url"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("deleted_at", LocalDateTime.class)
        );
    }

    private CategoryRow mapCategory(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryRow(
                rs.getLong("id"),
                rs.getLong("parent_id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("description"),
                rs.getInt("sort_order"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private boolean hasColumn(ResultSet rs, String columnName) throws SQLException {
        for (int index = 1; index <= rs.getMetaData().getColumnCount(); index++) {
            if (columnName.equalsIgnoreCase(rs.getMetaData().getColumnLabel(index))) {
                return true;
            }
        }
        return false;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private BufferedImage readImageIfNeeded(byte[] bytes, StoragePurpose purpose) {
        if (!purpose.image()) {
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

    private String defaultContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private StoragePurpose parsePurpose(String purpose) {
        if (!StringUtils.hasText(purpose)) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        try {
            return StoragePurpose.valueOf(purpose.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
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

    private String publicUrl(String objectKey) {
        String baseUrl = storageProperties.publicBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String relativePath = objectKey.startsWith("public/") ? objectKey.substring("public/".length()) : objectKey;
        return baseUrl + "/files/public/" + relativePath;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
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

    private record FileRow(
            Long id,
            String purpose,
            Long assetCategoryId,
            String visibility,
            String provider,
            String objectKey,
            String originalFilename,
            String contentType,
            String extension,
            Long sizeBytes,
            String sha256,
            Integer width,
            Integer height,
            String status,
            String uploadedByType,
            Long uploadedById,
            String publicUrl,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime deletedAt
    ) {
    }

    private record CategoryRow(
            Long id,
            Long parentId,
            String name,
            String code,
            String description,
            Integer sortOrder,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    private static final class MutableCategory {
        private final CategoryRow row;
        private final List<MutableCategory> children = new ArrayList<>();

        private MutableCategory(CategoryRow row) {
            this.row = row;
        }

        private StorageAssetCategoryResponse toResponse() {
            return new StorageAssetCategoryResponse(
                    row.id(),
                    row.parentId(),
                    row.name(),
                    row.code(),
                    row.description(),
                    row.sortOrder(),
                    row.status(),
                    row.createdAt(),
                    row.updatedAt(),
                    children.stream().map(MutableCategory::toResponse).toList()
            );
        }
    }
}
