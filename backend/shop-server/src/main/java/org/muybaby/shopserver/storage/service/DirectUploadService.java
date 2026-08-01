package org.muybaby.shopserver.storage.service;

import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.FileVisibility;
import org.muybaby.shopserver.storage.StorageMediaKind;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.StorageUploadProfile;
import org.muybaby.shopserver.storage.UploadedByType;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.provider.DirectObjectMetadata;
import org.muybaby.shopserver.storage.provider.DirectUploadGrant;
import org.muybaby.shopserver.storage.provider.ProcessedImage;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Coordinates browser/mini-program uploads that go straight to a private COS
 * staging key. The application server only signs a tightly-scoped POST policy,
 * verifies object metadata, asks Cloud Infinite to persist normalized WebP
 * outputs, and records the resulting asset.
 */
@Service
public class DirectUploadService {

    private static final Logger log = LoggerFactory.getLogger(DirectUploadService.class);
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);
    private static final Duration PROCESSING_RETRY_BASE_DELAY = Duration.ofSeconds(5);
    private static final int MAX_PROCESSING_ATTEMPTS = 3;
    private static final String CLIENT_ABORTED = "CLIENT_ABORTED";
    private static final Duration AFTER_SALE_ASSET_TTL = Duration.ofHours(24);
    private static final Duration CUSTOMER_SERVICE_ASSET_TTL = Duration.ofHours(2);
    private static final Set<StorageUploadProfile> LIBRARY_PROFILES = Set.of(
            StorageUploadProfile.LIBRARY_IMAGE,
            StorageUploadProfile.LIBRARY_VIDEO
    );
    private static final Set<String> DIRECT_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final Set<String> CI_SOURCE_FORMATS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif"
    );
    private static final int MAX_GIF_FRAMES = 300;
    private static final long MAX_ANIMATED_SOURCE_PIXELS = 250_000_000L;

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;
    private final UploadPolicy uploadPolicy;
    private final StorageObjectKeyGenerator keyGenerator;
    private final StorageRuntimeConfigService configService;
    private final StorageService storageService;
    private final TransactionTemplate transaction;
    private final int maxActiveSessionsPerPrincipal;
    private final int maxSessionsPerHourApp;
    private final int maxSessionsPerHourAdmin;

    public DirectUploadService(
            JdbcClient jdbcClient,
            StorageProvider storageProvider,
            UploadPolicy uploadPolicy,
            StorageObjectKeyGenerator keyGenerator,
            StorageRuntimeConfigService configService,
            StorageService storageService,
            PlatformTransactionManager transactionManager,
            @Value("${shop.storage.direct-upload.max-active-sessions-per-principal:10}")
            int maxActiveSessionsPerPrincipal,
            @Value("${shop.storage.direct-upload.max-sessions-per-hour-app:60}")
            int maxSessionsPerHourApp,
            @Value("${shop.storage.direct-upload.max-sessions-per-hour-admin:600}")
            int maxSessionsPerHourAdmin
    ) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
        this.uploadPolicy = uploadPolicy;
        this.keyGenerator = keyGenerator;
        this.configService = configService;
        this.storageService = storageService;
        this.maxActiveSessionsPerPrincipal = Math.max(
                1, maxActiveSessionsPerPrincipal);
        this.maxSessionsPerHourApp = Math.max(1, maxSessionsPerHourApp);
        this.maxSessionsPerHourAdmin = Math.max(1, maxSessionsPerHourAdmin);
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DirectUploadSessionResponse createLibrary(
            AuthenticatedPrincipal principal,
            DirectUploadSessionRequest request
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String filename = sanitizeFilename(request.originalFilename());
        String contentType = normalizeContentType(request.contentType());
        StorageUploadProfile profile = uploadPolicy.detectLibraryProfile(filename, contentType);
        return create(
                principal,
                profile,
                request.folderId(),
                null,
                null,
                request
        );
    }

    public StorageAssetResponse completeLibrary(
            AuthenticatedPrincipal principal,
            String uploadId
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        return complete(
                principal, uploadId, LIBRARY_PROFILES, null).asset();
    }

    public void cancelLibrary(
            AuthenticatedPrincipal principal,
            String uploadId
    ) {
        requirePrincipal(principal, TokenKind.ADMIN);
        cancelSession(principal, uploadId, LIBRARY_PROFILES, null);
    }

    /**
     * Abandons a direct-upload session before finalization starts. Ownership,
     * profile and route context are checked while the session row is locked.
     * The terminal state is committed before any best-effort COS cleanup, so a
     * provider outage cannot make the session active again.
     */
    public void cancel(
            AuthenticatedPrincipal principal,
            String uploadId,
            StorageUploadProfile expectedProfile,
            Long expectedContextId
    ) {
        if (expectedProfile == null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        cancelSession(
                principal,
                uploadId,
                Set.of(expectedProfile),
                expectedContextId
        );
    }

    private void cancelSession(
            AuthenticatedPrincipal principal,
            String uploadId,
            Set<StorageUploadProfile> expectedProfiles,
            Long expectedContextId
    ) {
        requirePrincipalPresent(principal);
        SessionRow terminal = Objects.requireNonNull(transaction.execute(status ->
                markCancelled(
                        principal,
                        uploadId,
                        expectedProfiles,
                        expectedContextId
                )));
        cleanupTerminalObjects(terminal);
    }

    private SessionRow markCancelled(
            AuthenticatedPrincipal principal,
            String uploadId,
            Set<StorageUploadProfile> expectedProfiles,
            Long expectedContextId
    ) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        SessionRow row = jdbcClient.sql(
                        sessionSelect() + " where id = :id for update")
                .param("id", uploadId)
                .query(this::mapSession)
                .optional()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STORAGE_FILE_UNAVAILABLE));
        requireSessionRoute(
                row, principal, expectedProfiles, expectedContextId);

        if ("FAILED".equals(row.status()) || "EXPIRED".equals(row.status())) {
            return row;
        }
        if (!"INITIATED".equals(row.status())) {
            // PROCESSING and COMPLETED belong to the finalization path. A
            // cancellation must never revoke its token or delete its outputs.
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        int updated = jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'FAILED',
                            folder_id = null,
                            processing_started_at = null,
                            processing_token = null,
                            next_processing_attempt_at = null,
                            failure_code = :failureCode,
                            updated_at = current_timestamp
                        where id = :id
                          and status = 'INITIATED'
                        """)
                .param("failureCode", CLIENT_ABORTED)
                .param("id", row.id())
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return row;
    }

    public DirectUploadSessionResponse create(
            AuthenticatedPrincipal principal,
            StorageUploadProfile profile,
            Long folderId,
            String contextType,
            Long contextId,
            DirectUploadSessionRequest request
    ) {
        if (request == null || profile == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        TokenKind expectedKind = profile == StorageUploadProfile.LIBRARY_IMAGE
                || profile == StorageUploadProfile.LIBRARY_VIDEO
                ? TokenKind.ADMIN
                : profile == StorageUploadProfile.AFTER_SALE_EVIDENCE
                        || profile == StorageUploadProfile.USER_AVATAR
                        ? TokenKind.APP
                        : principal == null ? null : principal.kind();
        requirePrincipal(principal, expectedKind);

        String filename = sanitizeFilename(request.originalFilename());
        String contentType = normalizeContentType(request.contentType());
        UploadPolicy.UploadDecision decision = uploadPolicy.requireAllowed(
                profile, filename, contentType, request.sizeBytes(), true);
        if (profile.mediaKind() == StorageMediaKind.IMAGE
                && !DIRECT_IMAGE_TYPES.contains(decision.contentType())) {
            throw new BusinessException(ErrorCode.STORAGE_DIRECT_UPLOAD_UNAVAILABLE);
        }
        if (profile == StorageUploadProfile.USER_AVATAR
                && request.sizeBytes() > 2L * 1024 * 1024) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        if (profile.scope().name().equals("LIBRARY")) {
            storageService.requireDirectUploadFolder(folderId);
        } else if (folderId != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        ResolvedStorageConfig config = configService.effective();
        String uploadId = UUID.randomUUID().toString();
        String stagingKey = "private/direct-upload/" + uploadId + "/source."
                + decision.extension();
        String finalExtension = profile.mediaKind() == StorageMediaKind.IMAGE
                ? "webp"
                : decision.extension();
        String finalKey = keyGenerator.nextKey(profile, finalExtension, LocalDate.now(java.time.ZoneOffset.UTC));
        String thumbnailKey = profile == StorageUploadProfile.CUSTOMER_SERVICE_IMAGE
                ? finalKey.substring(0, finalKey.length() - ".webp".length()) + ".thumb.webp"
                : null;
        LocalDateTime sessionExpiresAt = databaseNow().plus(SESSION_TTL);

        transaction.executeWithoutResult(status -> reserveSession(
                principal,
                profile,
                folderId,
                contextType,
                contextId,
                filename,
                decision.contentType(),
                request.sizeBytes(),
                config,
                uploadId,
                stagingKey,
                finalKey,
                thumbnailKey,
                sessionExpiresAt
        ));

        try {
            DirectUploadGrant grant = storageProvider.createDirectUploadGrant(
                    location(config, stagingKey),
                    decision.contentType(),
                    request.sizeBytes(),
                    SESSION_TTL
            );
            return new DirectUploadSessionResponse(
                    uploadId, grant.uploadUrl(), grant.formData(), grant.expiresAt());
        } catch (RuntimeException ex) {
            jdbcClient.sql("delete from storage_upload_session where id = :id")
                    .param("id", uploadId)
                    .update();
            throw ex;
        }
    }

    private void reserveSession(
            AuthenticatedPrincipal principal,
            StorageUploadProfile profile,
            Long folderId,
            String contextType,
            Long contextId,
            String filename,
            String contentType,
            long expectedSizeBytes,
            ResolvedStorageConfig config,
            String uploadId,
            String stagingKey,
            String finalKey,
            String thumbnailKey,
            LocalDateTime expiresAt
    ) {
        /*
         * The guard row makes the count-and-insert operation serializable per
         * principal even when that principal currently has zero sessions.
         * Relying only on a COUNT query would allow concurrent requests to
         * exceed the limit.
         */
        jdbcClient.sql("""
                        insert into storage_upload_principal_guard
                            (principal_kind, principal_id, updated_at)
                        values (:principalKind, :principalId, current_timestamp)
                        on duplicate key update updated_at = updated_at
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .update();
        jdbcClient.sql("""
                        select principal_id
                        from storage_upload_principal_guard
                        where principal_kind = :principalKind
                          and principal_id = :principalId
                        for update
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .query(Long.class)
                .single();
        int activeSessions = jdbcClient.sql("""
                        select count(*)
                        from storage_upload_session
                        where principal_kind = :principalKind
                          and principal_id = :principalId
                          and status in ('INITIATED', 'PROCESSING')
                          and (
                              expires_at > current_timestamp
                              or (
                                  status = 'PROCESSING'
                                  and processing_started_at is not null
                                  and processing_started_at > :processingCutoff
                              )
                          )
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .param("processingCutoff", databaseNow().minus(PROCESSING_LEASE))
                .query(Integer.class)
                .single();
        if (activeSessions >= maxActiveSessionsPerPrincipal) {
            throw new BusinessException(
                    ErrorCode.STORAGE_DIRECT_UPLOAD_RATE_LIMITED);
        }
        int sessionsLastHour = jdbcClient.sql("""
                        select count(*)
                        from storage_upload_session
                        where principal_kind = :principalKind
                          and principal_id = :principalId
                          and created_at >= :windowStart
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .param("windowStart", databaseNow().minusHours(1))
                .query(Integer.class)
                .single();
        int hourlyLimit = principal.kind() == TokenKind.APP
                ? maxSessionsPerHourApp
                : maxSessionsPerHourAdmin;
        if (sessionsLastHour >= hourlyLimit) {
            throw new BusinessException(
                    ErrorCode.STORAGE_DIRECT_UPLOAD_RATE_LIMITED);
        }

        jdbcClient.sql("""
                        insert into storage_upload_session
                            (id, profile, principal_kind, principal_id, folder_id,
                             upload_context_type, upload_context_id, original_filename,
                             source_content_type, expected_size_bytes, provider,
                             storage_container, storage_region, public_base_url, staging_object_key,
                             final_object_key, thumbnail_object_key, status, expires_at)
                        values
                            (:id, :profile, :principalKind, :principalId, :folderId,
                             :contextType, :contextId, :originalFilename,
                             :sourceContentType, :expectedSizeBytes, 'TENCENT_COS',
                             :storageContainer, :storageRegion, :publicBaseUrl, :stagingObjectKey,
                             :finalObjectKey, :thumbnailObjectKey, 'INITIATED', :expiresAt)
                        """)
                .param("id", uploadId)
                .param("profile", profile.name())
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .param("folderId", folderId)
                .param("contextType", contextType)
                .param("contextId", contextId)
                .param("originalFilename", filename)
                .param("sourceContentType", contentType)
                .param("expectedSizeBytes", expectedSizeBytes)
                .param("storageContainer", config.bucket())
                .param("storageRegion", config.region())
                .param("publicBaseUrl", config.publicBaseUrl())
                .param("stagingObjectKey", stagingKey)
                .param("finalObjectKey", finalKey)
                .param("thumbnailObjectKey", thumbnailKey)
                .param("expiresAt", expiresAt)
                .update();
    }

    public Completion complete(
            AuthenticatedPrincipal principal,
            String uploadId,
            StorageUploadProfile expectedProfile,
            Long expectedContextId
    ) {
        if (expectedProfile == null) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return complete(
                principal,
                uploadId,
                Set.of(expectedProfile),
                expectedContextId
        );
    }

    private Completion complete(
            AuthenticatedPrincipal principal,
            String uploadId,
            Set<StorageUploadProfile> expectedProfiles,
            Long expectedContextId
    ) {
        requirePrincipalPresent(principal);
        SessionClaim claim = Objects.requireNonNull(transaction.execute(status ->
                claim(principal, uploadId, expectedProfiles, expectedContextId)));
        if (claim.completedAssetId() != null) {
            return new Completion(
                    storageService.directAssetResponse(claim.completedAssetId()),
                    claim.profile(),
                    claim.contextId()
            );
        }

        SessionRow session = claim.session();
        String processingToken = claim.processingToken();
        StorageObjectLocation staging = session.stagingLocation();
        StorageObjectLocation mainOutput = session.finalLocation();
        boolean outputsMayExist = false;
        boolean assetPersisted = false;
        try {
            if (LIBRARY_PROFILES.contains(session.profile())) {
                storageService.requireDirectUploadFolder(
                        session.folderId());
            }
            DirectObjectMetadata staged = storageProvider.metadata(staging);
            validateStagedObject(session, staged);
            if (!renewProcessingClaim(session.id(), processingToken)) {
                throw new BusinessException(
                        ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }

            FinalizedObject finalized;
            if (session.profile().mediaKind() == StorageMediaKind.IMAGE) {
                List<StorageProvider.ImageProcessOutput> outputs =
                        imageOutputs(session);
                outputsMayExist = true;
                List<ProcessedImage> processed =
                        storageProvider.processImage(staging, outputs);
                finalized = validateProcessedImage(session, processed);
            } else {
                outputsMayExist = true;
                storageProvider.copy(
                        staging,
                        mainOutput,
                        session.sourceContentType(),
                        session.profile().visibility() == FileVisibility.PUBLIC
                );
                DirectObjectMetadata copied = storageProvider.metadata(mainOutput);
                if (copied == null
                        || copied.sizeBytes() != session.expectedSizeBytes()
                        || !session.sourceContentType().equals(
                                normalizeContentType(copied.contentType()))) {
                    throw new BusinessException(
                            ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
                }
                finalized = new FinalizedObject(
                        copied.contentType(),
                        copied.sizeBytes(),
                        null,
                        null,
                        copied.etag()
                );
            }

            Long assetId = Objects.requireNonNull(transaction.execute(status ->
                    persistCompletedAsset(
                            session, processingToken, finalized)));
            assetPersisted = true;
            cleanupCompletedStaging(session);
            return new Completion(
                    storageService.directAssetResponse(assetId),
                    session.profile(),
                    session.contextId()
            );
        } catch (BusinessException ex) {
            if (assetPersisted) {
                throw ex;
            }
            boolean owned = failSession(
                    session.id(), processingToken, ex.errorCode().name());
            if (owned) {
                cleanupTerminalObjects(session);
            }
            throw ex;
        } catch (RuntimeException ex) {
            if (assetPersisted) {
                throw ex;
            }
            handleRetryableFailure(
                    session,
                    processingToken,
                    ex.getClass().getSimpleName(),
                    outputsMayExist
            );
            log.warn(
                    "COS direct upload finalization failed: uploadId={}, profile={}, exception={}",
                    session.id(),
                    session.profile(),
                    ex.getClass().getSimpleName(),
                    ex
            );
            throw new BusinessException(ErrorCode.STORAGE_IMAGE_PROCESSING_FAILED);
        }
    }

    /**
     * Serializes the business side effect that follows object finalization
     * (avatar replacement or chat message insertion). The side effect and its
     * result id are committed together, so retrying a lost HTTP response cannot
     * consume an avatar quota twice or emit a duplicate chat message.
     */
    public <T> T completeBusiness(
            AuthenticatedPrincipal principal,
            String uploadId,
            StorageUploadProfile expectedProfile,
            Function<Long, T> completedResultLoader,
            Supplier<BusinessOutcome<T>> action
    ) {
        requirePrincipalPresent(principal);
        return Objects.requireNonNull(transaction.execute(status -> {
            SessionRow row = jdbcClient.sql(sessionSelect() + " where id = :id for update")
                    .param("id", uploadId)
                    .query(this::mapSession)
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.STORAGE_FILE_UNAVAILABLE));
            if (!row.principalKind().equals(principal.kind().name())
                    || !row.principalId().equals(principal.subjectId())
                    || row.profile() != expectedProfile
                    || !"COMPLETED".equals(row.status())
                    || row.assetId() == null) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            BusinessState business = jdbcClient.sql("""
                            select business_status, business_result_id
                            from storage_upload_session
                            where id = :id
                            """)
                    .param("id", uploadId)
                    .query((rs, rowNum) -> new BusinessState(
                            rs.getString("business_status"),
                            rs.getObject("business_result_id", Long.class)))
                    .single();
            if ("COMPLETED".equals(business.status())
                    && business.resultId() != null) {
                return completedResultLoader.apply(business.resultId());
            }
            BusinessOutcome<T> outcome = Objects.requireNonNull(action.get());
            if (outcome.resultId() == null || outcome.resultId() <= 0) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            int updated = jdbcClient.sql("""
                            update storage_upload_session
                            set business_status = 'COMPLETED',
                                business_result_id = :resultId,
                                updated_at = current_timestamp
                            where id = :id
                              and business_status = 'NONE'
                            """)
                    .param("resultId", outcome.resultId())
                    .param("id", uploadId)
                    .update();
            if (updated != 1) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            return outcome.value();
        }));
    }

    public int cleanupExpiredSessions(int batchSize, int retentionDays) {
        return cleanupExpiredSessions(batchSize, retentionDays, 0L);
    }

    public int cleanupExpiredSessions(int batchSize, int retentionDays, long runSequence) {
        return cleanupExpiredSessions(batchSize, retentionDays, runSequence, () -> true);
    }

    public int cleanupExpiredSessions(
            int batchSize,
            int retentionDays,
            long runSequence,
            BooleanSupplier leaseActive
    ) {
        if (batchSize < 1 || batchSize > 1_000 || retentionDays < 1 || retentionDays > 365) {
            throw new IllegalArgumentException("Invalid direct upload cleanup settings");
        }
        if (leaseActive == null) {
            throw new IllegalArgumentException("Direct upload cleanup lease state is required");
        }
        LocalDateTime now = databaseNow();
        LocalDateTime processingCutoff = now.minus(PROCESSING_LEASE);
        LocalDateTime retentionCutoff = now.minusDays(retentionDays);
        int attempted = 0;
        int firstStage = rotationOffset(runSequence, DirectUploadCleanupStage.values().length);
        for (int stageOffset = 0;
                stageOffset < DirectUploadCleanupStage.values().length
                        && remaining(batchSize, attempted) > 0
                        && leaseActive.getAsBoolean();
                stageOffset++) {
            DirectUploadCleanupStage stage = DirectUploadCleanupStage.values()[
                    (firstStage + stageOffset) % DirectUploadCleanupStage.values().length];
            int limit = remaining(batchSize, attempted);
            attempted += switch (stage) {
                case COMPLETED_STAGING -> cleanupCompletedStagingBatch(limit, leaseActive);
                case FAILED_OBJECTS -> cleanupFailedObjectsBatch(limit, leaseActive);
                case EXPIRED_OBJECTS -> cleanupExpiredObjectsBatch(limit, leaseActive);
                case EXPIRE_SESSIONS -> expireSessionsBatch(
                        limit, processingCutoff, leaseActive);
                case RETAINED_SESSIONS -> deleteRetainedSessionsBatch(
                        limit, retentionCutoff, leaseActive);
            };
        }
        return attempted;
    }

    private int cleanupCompletedStagingBatch(int limit, BooleanSupplier leaseActive) {
        List<SessionRow> completedWithStaging = jdbcClient.sql(sessionSelect() + """
                        where status = 'COMPLETED'
                          and staging_deleted_at is null
                        order by completed_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query(this::mapSession)
                .list();
        int attempted = 0;
        for (SessionRow session : completedWithStaging) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            cleanupCompletedStaging(session);
        }
        return attempted;
    }

    private int cleanupFailedObjectsBatch(int limit, BooleanSupplier leaseActive) {
        List<SessionRow> failedWithObjects = jdbcClient.sql(sessionSelect() + """
                        where status = 'FAILED'
                          and (
                              staging_deleted_at is null
                              or outputs_deleted_at is null
                          )
                        order by updated_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query(this::mapSession)
                .list();
        int attempted = 0;
        for (SessionRow session : failedWithObjects) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            cleanupTerminalObjects(session);
        }
        return attempted;
    }

    private int cleanupExpiredObjectsBatch(int limit, BooleanSupplier leaseActive) {
        List<SessionRow> expiredWithObjects = jdbcClient.sql(sessionSelect() + """
                        where status = 'EXPIRED'
                          and (
                              staging_deleted_at is null
                              or outputs_deleted_at is null
                          )
                        order by updated_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query(this::mapSession)
                .list();
        int attempted = 0;
        for (SessionRow session : expiredWithObjects) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            cleanupTerminalObjects(session);
        }
        return attempted;
    }

    private int expireSessionsBatch(
            int limit,
            LocalDateTime processingCutoff,
            BooleanSupplier leaseActive
    ) {
        List<SessionRow> expired = jdbcClient.sql(sessionSelect() + """
                        where status in ('INITIATED', 'FAILED', 'PROCESSING')
                          and expires_at < current_timestamp
                          and (
                              status <> 'PROCESSING'
                              or processing_started_at is null
                              or processing_started_at <= :processingCutoff
                          )
                        order by expires_at
                        limit :limit
                        """)
                .param("processingCutoff", processingCutoff)
                .param("limit", limit)
                .query(this::mapSession)
                .list();
        int attempted = 0;
        for (SessionRow session : expired) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            int updated = jdbcClient.sql("""
                            update storage_upload_session
                            set status = 'EXPIRED',
                                folder_id = null,
                                processing_started_at = null,
                                processing_token = null,
                                next_processing_attempt_at = null,
                                updated_at = current_timestamp
                            where id = :id
                              and status in ('INITIATED', 'FAILED', 'PROCESSING')
                              and expires_at < current_timestamp
                              and (
                                  status <> 'PROCESSING'
                                  or processing_started_at is null
                                  or processing_started_at <= :processingCutoff
                              )
                            """)
                    .param("id", session.id())
                    .param("processingCutoff", processingCutoff)
                    .update();
            if (updated == 1) {
                cleanupTerminalObjects(session);
            }
        }
        return attempted;
    }

    private int deleteRetainedSessionsBatch(
            int limit,
            LocalDateTime retentionCutoff,
            BooleanSupplier leaseActive
    ) {
        List<String> retainedIds = jdbcClient.sql("""
                        select id
                        from storage_upload_session
                        where (
                              (status = 'COMPLETED' and staging_deleted_at is not null)
                              or (
                                  status = 'EXPIRED'
                                  and staging_deleted_at is not null
                                  and outputs_deleted_at is not null
                              )
                          )
                          and updated_at < :retentionCutoff
                        order by updated_at
                        limit :limit
                        """)
                .param("retentionCutoff", retentionCutoff)
                .param("limit", limit)
                .query(String.class)
                .list();
        int attempted = 0;
        for (String retainedId : retainedIds) {
            if (!leaseActive.getAsBoolean()) {
                break;
            }
            attempted++;
            jdbcClient.sql("""
                            delete from storage_upload_session
                            where id = :id
                              and (
                                  (status = 'COMPLETED' and staging_deleted_at is not null)
                                  or (
                                      status = 'EXPIRED'
                                      and staging_deleted_at is not null
                                      and outputs_deleted_at is not null
                                  )
                              )
                              and updated_at < :retentionCutoff
                            """)
                    .param("id", retainedId)
                    .param("retentionCutoff", retentionCutoff)
                    .update();
        }
        return attempted;
    }

    private int rotationOffset(long runSequence, int stageCount) {
        return runSequence <= 0
                ? 0
                : Math.floorMod(runSequence - 1, stageCount);
    }

    private SessionClaim claim(
            AuthenticatedPrincipal principal,
            String uploadId,
            Set<StorageUploadProfile> expectedProfiles,
            Long expectedContextId
    ) {
        if (!StringUtils.hasText(uploadId)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        SessionRow row = jdbcClient.sql(sessionSelect() + " where id = :id for update")
                .param("id", uploadId)
                .query(this::mapSession)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE));
        requireSessionRoute(
                row, principal, expectedProfiles, expectedContextId);
        if ("COMPLETED".equals(row.status()) && row.assetId() != null) {
            return new SessionClaim(
                    row,
                    row.assetId(),
                    row.profile(),
                    row.contextId(),
                    null
            );
        }
        LocalDateTime now = databaseNow();
        if ("PROCESSING".equals(row.status())
                && row.processingStartedAt() != null
                && row.processingStartedAt().plus(PROCESSING_LEASE).isAfter(now)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        if (!row.expiresAt().isAfter(now)) {
            jdbcClient.sql("""
                            update storage_upload_session
                            set status = 'EXPIRED',
                                processing_started_at = null,
                                processing_token = null,
                                next_processing_attempt_at = null,
                                updated_at = current_timestamp
                            where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        if ("FAILED".equals(row.status()) || "EXPIRED".equals(row.status())) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        if (row.processingAttempts() >= MAX_PROCESSING_ATTEMPTS) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        if (row.nextProcessingAttemptAt() != null
                && row.nextProcessingAttemptAt().isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.STORAGE_IMAGE_PROCESSING_FAILED);
        }
        String processingToken = UUID.randomUUID().toString();
        int updated = jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'PROCESSING',
                            processing_started_at = current_timestamp,
                            processing_token = :processingToken,
                            processing_attempts = processing_attempts + 1,
                            next_processing_attempt_at = null,
                            failure_code = null,
                            updated_at = current_timestamp
                        where id = :id
                          and status in ('INITIATED', 'PROCESSING')
                          and processing_attempts < :maxAttempts
                          and (
                              next_processing_attempt_at is null
                              or next_processing_attempt_at <= current_timestamp
                          )
                        """)
                .param("id", row.id())
                .param("processingToken", processingToken)
                .param("maxAttempts", MAX_PROCESSING_ATTEMPTS)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        SessionRow claimed = jdbcClient.sql(
                        sessionSelect() + " where id = :id for update")
                .param("id", row.id())
                .query(this::mapSession)
                .single();
        return new SessionClaim(
                claimed,
                null,
                claimed.profile(),
                claimed.contextId(),
                processingToken
        );
    }

    private void requireSessionRoute(
            SessionRow row,
            AuthenticatedPrincipal principal,
            Set<StorageUploadProfile> expectedProfiles,
            Long expectedContextId
    ) {
        if (!row.principalKind().equals(principal.kind().name())
                || !row.principalId().equals(principal.subjectId())
                || expectedProfiles == null
                || !expectedProfiles.contains(row.profile())
                || (expectedContextId != null
                        && !Objects.equals(
                                row.contextId(), expectedContextId))) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
    }

    private void validateStagedObject(SessionRow session, DirectObjectMetadata metadata) {
        if (metadata == null
                || metadata.sizeBytes() != session.expectedSizeBytes()
                || !session.sourceContentType().equals(
                        normalizeContentType(metadata.contentType()))) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
    }

    private List<StorageProvider.ImageProcessOutput> imageOutputs(SessionRow session) {
        ImageProfileSettings settings = imageProfileSettings(session.profile());
        StorageProvider.ImageProcessOutput main = new StorageProvider.ImageProcessOutput(
                session.finalObjectKey(),
                settings.maxDimension(),
                settings.quality(),
                session.profile().visibility() == FileVisibility.PUBLIC
        );
        if (session.profile() != StorageUploadProfile.CUSTOMER_SERVICE_IMAGE) {
            return List.of(main);
        }
        return List.of(
                main,
                new StorageProvider.ImageProcessOutput(
                        session.thumbnailObjectKey(), 720, 76, false)
        );
    }

    private FinalizedObject validateProcessedImage(
            SessionRow session,
            List<ProcessedImage> processed
    ) {
        int expectedOutputCount = session.profile()
                == StorageUploadProfile.CUSTOMER_SERVICE_IMAGE ? 2 : 1;
        if (processed == null || processed.size() != expectedOutputCount) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        ProcessedImage main = processed.stream()
                .filter(output -> session.finalObjectKey().equals(output.objectKey()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
        String sourceFormat = main.sourceFormat() == null
                ? ""
                : main.sourceFormat().toLowerCase(Locale.ROOT);
        if (!CI_SOURCE_FORMATS.contains(sourceFormat)
                || !sourceFormatMatchesContentType(
                        sourceFormat, session.sourceContentType())
                || main.sourceWidth() <= 0
                || main.sourceHeight() <= 0
                || main.sourceFrameCount() <= 0
                || main.sourceFrameCount() > MAX_GIF_FRAMES
                || (main.sourceFrameCount() > 1
                    && (long) main.sourceWidth()
                            * main.sourceHeight()
                            * main.sourceFrameCount()
                            > MAX_ANIMATED_SOURCE_PIXELS)) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        uploadPolicy.requireAllowedImageDimensions(
                main.sourceWidth(), main.sourceHeight());
        validateProcessedOutput(
                main,
                imageProfileSettings(session.profile()).maxDimension());
        ProcessedImage thumbnail = session.profile()
                == StorageUploadProfile.CUSTOMER_SERVICE_IMAGE
                ? processed.stream()
                        .filter(output -> session.thumbnailObjectKey()
                                .equals(output.objectKey()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED))
                : null;
        if (thumbnail != null) {
            validateProcessedOutput(thumbnail, 720);
        }
        return new FinalizedObject(
                "image/webp",
                main.sizeBytes(),
                main,
                thumbnail,
                main.etag()
        );
    }

    private void validateProcessedOutput(
            ProcessedImage output,
            int maxDimension
    ) {
        String format = output.format() == null
                ? ""
                : output.format().trim().toLowerCase(Locale.ROOT);
        if (!"webp".equals(format)
                || !"image/webp".equals(
                        normalizeContentType(output.contentType()))
                || output.sizeBytes() <= 0
                || output.width() <= 0
                || output.height() <= 0
                || output.width() > maxDimension
                || output.height() > maxDimension
                || output.frameCount() <= 0
                || output.frameCount() > MAX_GIF_FRAMES) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        uploadPolicy.requireAllowedImageDimensions(
                output.width(), output.height());
    }

    private boolean sourceFormatMatchesContentType(
            String sourceFormat,
            String contentType
    ) {
        return switch (sourceFormat) {
            case "jpg", "jpeg" -> "image/jpeg".equals(contentType);
            case "png" -> "image/png".equals(contentType);
            case "webp" -> "image/webp".equals(contentType);
            case "gif" -> "image/gif".equals(contentType);
            default -> false;
        };
    }

    private ImageProfileSettings imageProfileSettings(
            StorageUploadProfile profile
    ) {
        return switch (profile) {
            case AFTER_SALE_EVIDENCE -> new ImageProfileSettings(4096, 90);
            case CUSTOMER_SERVICE_IMAGE -> new ImageProfileSettings(1920, 82);
            case USER_AVATAR -> new ImageProfileSettings(1024, 85);
            default -> new ImageProfileSettings(2560, 85);
        };
    }

    private Long persistCompletedAsset(
            SessionRow session,
            String processingToken,
            FinalizedObject finalized
    ) {
        SessionRow locked = jdbcClient.sql(sessionSelect() + " where id = :id for update")
                .param("id", session.id())
                .query(this::mapSession)
                .single();
        if ("COMPLETED".equals(locked.status()) && locked.assetId() != null) {
            return locked.assetId();
        }
        if (!"PROCESSING".equals(locked.status())
                || !Objects.equals(
                        locked.processingToken(), processingToken)) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }

        String extension = session.profile().mediaKind() == StorageMediaKind.IMAGE
                ? "webp"
                : extensionOf(session.originalFilename());
        String filename = session.profile().mediaKind() == StorageMediaKind.IMAGE
                ? replaceExtension(session.originalFilename(), "webp")
                : session.originalFilename();
        String publicUrl = session.profile().visibility() == FileVisibility.PUBLIC
                ? publicUrl(session)
                : null;
        LocalDateTime assetExpiresAt = switch (session.profile()) {
            case AFTER_SALE_EVIDENCE -> databaseNow().plus(AFTER_SALE_ASSET_TTL);
            case CUSTOMER_SERVICE_IMAGE -> databaseNow().plus(CUSTOMER_SERVICE_ASSET_TTL);
            default -> null;
        };
        ProcessedImage image = finalized.image();
        ProcessedImage thumbnail = finalized.thumbnail();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        Long assetId;
        try {
            int inserted = jdbcClient.sql("""
                            insert into storage_asset
                                (scope, media_kind, folder_id, visibility, provider,
                                 storage_container, storage_region, object_key,
                                 original_filename, content_type, extension, size_bytes,
                                 sha256, object_etag, width, height,
                                 duration_seconds, alt_text, tags_json,
                                 public_url, status, uploaded_by_type, uploaded_by_id,
                                 upload_context_type, upload_context_id, expires_at,
                                 thumbnail_status, thumbnail_object_key,
                                 thumbnail_content_type, thumbnail_size_bytes,
                                 thumbnail_sha256, thumbnail_object_etag,
                                 thumbnail_width, thumbnail_height, cleanup_attempts,
                                 cleanup_next_retry_at, cleanup_lease_token)
                            values
                                (:scope, :mediaKind, :folderId, :visibility, 'TENCENT_COS',
                                 :storageContainer, :storageRegion, :objectKey,
                                 :originalFilename, :contentType, :extension, :sizeBytes,
                                 '', :objectEtag, :width, :height, null, '', null,
                                 :publicUrl, 'ACTIVE', :uploadedByType, :uploadedById,
                                 :contextType, :contextId, :expiresAt,
                                 :thumbnailStatus, :thumbnailObjectKey,
                                 :thumbnailContentType, :thumbnailSizeBytes,
                                 :thumbnailSha256, :thumbnailObjectEtag,
                                 :thumbnailWidth, :thumbnailHeight, 0, null, null)
                            """)
                    .param("scope", session.profile().scope().name())
                    .param("mediaKind", session.profile().mediaKind().name())
                    .param("folderId", session.folderId())
                    .param("visibility", session.profile().visibility().name())
                    .param("storageContainer", session.storageContainer())
                    .param("storageRegion", session.storageRegion())
                    .param("objectKey", session.finalObjectKey())
                    .param("originalFilename", filename)
                    .param("contentType", finalized.contentType())
                    .param("extension", extension)
                    .param("sizeBytes", finalized.sizeBytes())
                    .param("objectEtag", normalizeEtag(finalized.etag()))
                    .param("width", image == null ? null : image.width())
                    .param("height", image == null ? null : image.height())
                    .param("publicUrl", publicUrl)
                    .param("uploadedByType", session.principalKind())
                    .param("uploadedById", session.principalId())
                    .param("contextType", session.contextType())
                    .param("contextId", session.contextId())
                    .param("expiresAt", assetExpiresAt)
                    .param("thumbnailStatus", thumbnail == null ? "NONE" : "READY")
                    .param("thumbnailObjectKey",
                            thumbnail == null ? null : thumbnail.objectKey())
                    .param("thumbnailContentType",
                            thumbnail == null ? null : thumbnail.contentType())
                    .param("thumbnailSizeBytes",
                            thumbnail == null ? null : thumbnail.sizeBytes())
                    .param("thumbnailSha256",
                            thumbnail == null ? null : "")
                    .param("thumbnailObjectEtag",
                            thumbnail == null
                                    ? null
                                    : normalizeEtag(thumbnail.etag()))
                    .param("thumbnailWidth",
                            thumbnail == null ? null : thumbnail.width())
                    .param("thumbnailHeight",
                            thumbnail == null ? null : thumbnail.height())
                    .update(keyHolder, "id");
            if (inserted != 1 || keyHolder.getKey() == null) {
                throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
            }
            assetId = keyHolder.getKey().longValue();
        } catch (DuplicateKeyException ex) {
            assetId = jdbcClient.sql("""
                            select id from storage_asset
                            where object_key = :objectKey
                            """)
                    .param("objectKey", session.finalObjectKey())
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> ex);
        }
        int updated = jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'COMPLETED',
                            asset_id = :assetId,
                            folder_id = null,
                            processing_started_at = null,
                            processing_token = null,
                            next_processing_attempt_at = null,
                            completed_at = current_timestamp,
                            failure_code = null,
                            updated_at = current_timestamp
                        where id = :id
                          and status = 'PROCESSING'
                          and processing_token = :processingToken
                        """)
                .param("assetId", assetId)
                .param("id", session.id())
                .param("processingToken", processingToken)
                .update();
        if (updated != 1) {
            throw new BusinessException(ErrorCode.STORAGE_FILE_UNAVAILABLE);
        }
        return assetId;
    }

    private void handleRetryableFailure(
            SessionRow session,
            String processingToken,
            String reason,
            boolean outputsMayExist
    ) {
        if (session.processingAttempts() >= MAX_PROCESSING_ATTEMPTS) {
            boolean owned = failSession(
                    session.id(), processingToken, reason);
            if (owned) {
                cleanupTerminalObjects(session);
            }
            return;
        }
        /*
         * Renew ownership before deleting a possibly partial output. A worker
         * whose lease was already reclaimed must never delete objects produced
         * by the newer processing token.
         */
        if (!renewProcessingClaim(session.id(), processingToken)) {
            return;
        }
        if (outputsMayExist) {
            deleteQuietly(session.finalLocation());
            deleteQuietly(session.thumbnailLocation());
        }
        LocalDateTime retryAt = databaseNow().plus(
                processingRetryDelay(session.processingAttempts()));
        if (!resetSessionForRetry(
                session.id(), processingToken, reason, retryAt)) {
            log.warn(
                    "Failed to schedule direct upload processing retry: uploadId={}",
                    session.id()
            );
        }
    }

    private boolean renewProcessingClaim(
            String uploadId,
            String processingToken
    ) {
        return jdbcClient.sql("""
                        update storage_upload_session
                        set processing_started_at = current_timestamp,
                            updated_at = current_timestamp
                        where id = :id
                          and status = 'PROCESSING'
                          and processing_token = :processingToken
                        """)
                .param("id", uploadId)
                .param("processingToken", processingToken)
                .update() == 1;
    }

    private Duration processingRetryDelay(int completedAttempt) {
        int exponent = Math.max(0, completedAttempt - 1);
        return PROCESSING_RETRY_BASE_DELAY.multipliedBy(1L << exponent);
    }

    private boolean resetSessionForRetry(
            String uploadId,
            String processingToken,
            String reason,
            LocalDateTime retryAt
    ) {
        try {
            return jdbcClient.sql("""
                            update storage_upload_session
                            set status = 'INITIATED',
                                processing_started_at = null,
                                processing_token = null,
                                next_processing_attempt_at = :retryAt,
                                failure_code = :failureCode,
                                updated_at = current_timestamp
                            where id = :id
                              and status = 'PROCESSING'
                              and processing_token = :processingToken
                              and expires_at > current_timestamp
                            """)
                    .param("id", uploadId)
                    .param("processingToken", processingToken)
                    .param("retryAt", retryAt)
                    .param("failureCode", truncate(reason, 64))
                    .update() == 1;
        } catch (RuntimeException stateFailure) {
            log.warn("Failed to reset direct upload for retry: uploadId={}", uploadId);
            return false;
        }
    }

    private boolean failSession(
            String uploadId,
            String processingToken,
            String reason
    ) {
        return jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'FAILED',
                            folder_id = null,
                            processing_started_at = null,
                            processing_token = null,
                            next_processing_attempt_at = null,
                            failure_code = :failureCode,
                            updated_at = current_timestamp
                        where id = :id
                          and status = 'PROCESSING'
                          and processing_token = :processingToken
                        """)
                .param("id", uploadId)
                .param("processingToken", processingToken)
                .param("failureCode", truncate(reason, 64))
                .update() == 1;
    }

    private void cleanupCompletedStaging(SessionRow session) {
        if (!deleteQuietly(session.stagingLocation())) {
            return;
        }
        markStagingDeleted(session.id());
    }

    private void cleanupTerminalObjects(SessionRow session) {
        if (session.stagingDeletedAt() == null
                && deleteQuietly(session.stagingLocation())) {
            markStagingDeleted(session.id());
        }
        if (session.outputsDeletedAt() == null
                && deleteQuietly(session.finalLocation())
                && deleteQuietly(session.thumbnailLocation())) {
            markOutputsDeleted(session.id());
        }
    }

    private void markStagingDeleted(String uploadId) {
        try {
            jdbcClient.sql("""
                            update storage_upload_session
                            set staging_deleted_at = current_timestamp,
                                updated_at = current_timestamp
                            where id = :id
                              and staging_deleted_at is null
                            """)
                    .param("id", uploadId)
                    .update();
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to record direct upload staging cleanup: uploadId={}",
                    uploadId,
                    ex
            );
        }
    }

    private void markOutputsDeleted(String uploadId) {
        try {
            jdbcClient.sql("""
                            update storage_upload_session
                            set outputs_deleted_at = current_timestamp,
                                updated_at = current_timestamp
                            where id = :id
                              and status in ('FAILED', 'EXPIRED')
                              and outputs_deleted_at is null
                            """)
                    .param("id", uploadId)
                    .update();
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to record terminal direct upload output cleanup: uploadId={}",
                    uploadId,
                    ex
            );
        }
    }

    private boolean deleteQuietly(StorageObjectLocation location) {
        if (location == null) {
            return true;
        }
        try {
            storageProvider.delete(location);
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                    "COS direct upload cleanup will be retried by lifecycle policy: objectKey={}, exception={}",
                    location.objectKey(), ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    private String sessionSelect() {
        return """
                select id, profile, principal_kind, principal_id, folder_id,
                       upload_context_type, upload_context_id, original_filename,
                       source_content_type, expected_size_bytes, provider,
                       storage_container, storage_region, public_base_url, staging_object_key,
                       final_object_key, thumbnail_object_key, status, asset_id,
                       expires_at, processing_started_at, processing_token,
                       processing_attempts, next_processing_attempt_at,
                       staging_deleted_at, outputs_deleted_at
                from storage_upload_session
                """;
    }

    private SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getString("id"),
                StorageUploadProfile.valueOf(rs.getString("profile")),
                rs.getString("principal_kind"),
                rs.getObject("principal_id", Long.class),
                rs.getObject("folder_id", Long.class),
                rs.getString("upload_context_type"),
                rs.getObject("upload_context_id", Long.class),
                rs.getString("original_filename"),
                rs.getString("source_content_type"),
                rs.getLong("expected_size_bytes"),
                StorageProviderKind.valueOf(rs.getString("provider")),
                rs.getString("storage_container"),
                rs.getString("storage_region"),
                rs.getString("public_base_url"),
                rs.getString("staging_object_key"),
                rs.getString("final_object_key"),
                rs.getString("thumbnail_object_key"),
                rs.getString("status"),
                rs.getObject("asset_id", Long.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("processing_started_at", LocalDateTime.class),
                rs.getString("processing_token"),
                rs.getInt("processing_attempts"),
                rs.getObject("next_processing_attempt_at", LocalDateTime.class),
                rs.getObject("staging_deleted_at", LocalDateTime.class),
                rs.getObject("outputs_deleted_at", LocalDateTime.class)
        );
    }

    private StorageObjectLocation location(ResolvedStorageConfig config, String objectKey) {
        return new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                config.bucket(),
                config.region(),
                objectKey
        );
    }

    private String publicUrl(SessionRow session) {
        String base = session.publicBaseUrl();
        if (!StringUtils.hasText(base)) {
            base = "https://" + session.storageContainer() + ".cos."
                    + session.storageRegion() + ".myqcloud.com";
        } else {
            base = base.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
        }
        return base + "/" + session.finalObjectKey();
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private String sanitizeFilename(String rawFilename) {
        String candidate = rawFilename == null ? "" : rawFilename.trim();
        if (!StringUtils.hasText(candidate)
                || candidate.length() > 255
                || ".".equals(candidate)
                || "..".equals(candidate)
                || candidate.indexOf('/') >= 0
                || candidate.indexOf('\\') >= 0
                || candidate.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return candidate;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "";
        }
        int separator = contentType.indexOf(';');
        return (separator >= 0 ? contentType.substring(0, separator) : contentType)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED);
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String replaceExtension(String filename, String extension) {
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return base + "." + extension;
    }

    private String truncate(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }

    private String normalizeEtag(String etag) {
        if (!StringUtils.hasText(etag)) {
            return null;
        }
        String normalized = etag.trim();
        if (normalized.length() >= 2
                && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return StringUtils.hasText(normalized)
                ? truncate(normalized, 128)
                : null;
    }

    private void requirePrincipal(
            AuthenticatedPrincipal principal,
            TokenKind expectedKind
    ) {
        if (principal == null || expectedKind == null || principal.kind() != expectedKind
                || principal.subjectId() == null || principal.subjectId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private void requirePrincipalPresent(AuthenticatedPrincipal principal) {
        if (principal == null || principal.kind() == null
                || principal.subjectId() == null || principal.subjectId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private int remaining(int batchSize, int attempted) {
        return Math.max(0, batchSize - attempted);
    }

    private enum DirectUploadCleanupStage {
        COMPLETED_STAGING,
        FAILED_OBJECTS,
        EXPIRED_OBJECTS,
        EXPIRE_SESSIONS,
        RETAINED_SESSIONS
    }

    public record Completion(
            StorageAssetResponse asset,
            StorageUploadProfile profile,
            Long contextId
    ) {
    }

    public record BusinessOutcome<T>(Long resultId, T value) {
    }

    private record SessionClaim(
            SessionRow session,
            Long completedAssetId,
            StorageUploadProfile profile,
            Long contextId,
            String processingToken
    ) {
    }

    private record FinalizedObject(
            String contentType,
            long sizeBytes,
            ProcessedImage image,
            ProcessedImage thumbnail,
            String etag
    ) {
    }

    private record ImageProfileSettings(int maxDimension, int quality) {
    }

    private record BusinessState(String status, Long resultId) {
    }

    private record SessionRow(
            String id,
            StorageUploadProfile profile,
            String principalKind,
            Long principalId,
            Long folderId,
            String contextType,
            Long contextId,
            String originalFilename,
            String sourceContentType,
            long expectedSizeBytes,
            StorageProviderKind provider,
            String storageContainer,
            String storageRegion,
            String publicBaseUrl,
            String stagingObjectKey,
            String finalObjectKey,
            String thumbnailObjectKey,
            String status,
            Long assetId,
            LocalDateTime expiresAt,
            LocalDateTime processingStartedAt,
            String processingToken,
            int processingAttempts,
            LocalDateTime nextProcessingAttemptAt,
            LocalDateTime stagingDeletedAt,
            LocalDateTime outputsDeletedAt
    ) {
        private StorageObjectLocation stagingLocation() {
            return new StorageObjectLocation(
                    provider, storageContainer, storageRegion, stagingObjectKey);
        }

        private StorageObjectLocation finalLocation() {
            return new StorageObjectLocation(
                    provider, storageContainer, storageRegion, finalObjectKey);
        }

        private StorageObjectLocation thumbnailLocation() {
            return StringUtils.hasText(thumbnailObjectKey)
                    ? new StorageObjectLocation(
                            provider, storageContainer, storageRegion, thumbnailObjectKey)
                    : null;
        }
    }
}
