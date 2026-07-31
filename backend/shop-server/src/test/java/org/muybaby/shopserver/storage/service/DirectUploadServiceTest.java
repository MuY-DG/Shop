package org.muybaby.shopserver.storage.service;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.StorageUploadProfile;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionRequest;
import org.muybaby.shopserver.storage.dto.DirectUploadSessionResponse;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.provider.ProcessedImage;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@SpringBootTest
@ActiveProfiles("test")
class DirectUploadServiceTest {

    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN,
            1L,
            "admin",
            List.of("R_SUPER"),
            List.of("asset:upload")
    );

    @Autowired
    private DirectUploadService directUploadService;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private UploadPolicy uploadPolicy;

    @Autowired
    private StorageObjectKeyGenerator keyGenerator;

    @Autowired
    private StorageRuntimeConfigService configService;

    @Autowired
    private StorageService storageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${shop.storage.direct-upload.max-active-sessions-per-principal:10}")
    private int maxActiveSessionsPerPrincipal;

    @Test
    void completesAnExactPrivateStagingUploadIdempotently() throws Exception {
        byte[] png = png();
        DirectUploadSessionResponse session = directUploadService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "photo.png", "image/png", png.length, null)
        );
        assertThat(session.uploadUrl()).isEqualTo(
                "https://direct-upload.test.invalid");
        SessionObject staging = jdbcClient.sql("""
                        select storage_container, storage_region,
                               staging_object_key, expires_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .query((rs, rowNum) -> new SessionObject(
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("staging_object_key"),
                        rs.getObject("expires_at", LocalDateTime.class)))
                .single();
        LocalDateTime databaseNow = jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
        assertThat(staging.expiresAt()).isAfter(databaseNow.plusMinutes(14));
        assertThat(staging.expiresAt()).isBefore(databaseNow.plusMinutes(16));

        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/png",
                new ByteArrayInputStream(png),
                png.length
        );
        jdbcClient.sql("""
                        update storage_upload_session
                        set public_base_url = 'https://oss.example.test///'
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .update();

        StorageAssetResponse first = directUploadService.completeLibrary(
                ADMIN, session.uploadId());
        StorageAssetResponse retried = directUploadService.completeLibrary(
                ADMIN, session.uploadId());
        assertUnavailable(() -> directUploadService.cancelLibrary(
                ADMIN, session.uploadId()));

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(first.contentType()).isEqualTo("image/webp");
        assertThat(first.extension()).isEqualTo("webp");
        assertThat(first.publicUrl())
                .startsWith("https://oss.example.test/")
                .doesNotContain("oss.example.test//")
                .endsWith(".webp");
        assertThat(first.width()).isEqualTo(1);
        assertThat(first.height()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        select count(*) from storage_asset
                        where object_key = (
                            select final_object_key
                            from storage_upload_session
                            where id = :id
                        )
                        """)
                .param("id", session.uploadId())
                .query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(storageProvider.metadata(
                sessionLocations(session.uploadId()).finalLocation())
                .sizeBytes()).isPositive();
    }

    @Test
    void onlyDedicatedUnavailableCodeAllowsLegacyFallback() {
        assertThatThrownBy(() -> directUploadService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "vector.svg", "image/svg+xml", 100, null)
        ))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_DIRECT_UPLOAD_UNAVAILABLE));
    }

    @Test
    void rejectsPathLikeAndControlCharacterFilenames() {
        for (String filename : List.of(
                "../photo.png",
                "folder/photo.png",
                "folder\\photo.png",
                "photo" + (char) 0 + ".png",
                "photo\n.png"
        )) {
            assertThatThrownBy(() -> directUploadService.createLibrary(
                    ADMIN,
                    new DirectUploadSessionRequest(
                            filename, "image/png", 100, null)
            ))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
        }
    }

    @Test
    void libraryCompletionCannotFinalizePrivateBusinessProfile() {
        DirectUploadSessionResponse session = directUploadService.create(
                ADMIN,
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                null,
                "CUSTOMER_SERVICE_CONVERSATION",
                42L,
                new DirectUploadSessionRequest(
                        "chat.png", "image/png", 100, null)
        );
        try {
            assertThatThrownBy(() -> directUploadService.completeLibrary(
                    ADMIN, session.uploadId()))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_FILE_UNAVAILABLE));
        } finally {
            deleteSession(session.uploadId());
        }
    }

    @Test
    void completionRejectsAnotherOwnerAndWrongContextBeforeProcessing() {
        DirectUploadSessionResponse session = directUploadService.create(
                ADMIN,
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                null,
                "CUSTOMER_SERVICE_CONVERSATION",
                43L,
                new DirectUploadSessionRequest(
                        "chat.png", "image/png", 100, null)
        );
        AuthenticatedPrincipal otherAdmin = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                2L,
                "other-admin",
                List.of("R_SUPER"),
                List.of("asset:upload")
        );
        try {
            assertThatThrownBy(() -> directUploadService.complete(
                    otherAdmin,
                    session.uploadId(),
                    StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                    43L
            ))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_FILE_UNAVAILABLE));
            assertThatThrownBy(() -> directUploadService.complete(
                    ADMIN,
                    session.uploadId(),
                    StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                    999L
            ))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_FILE_UNAVAILABLE));
        } finally {
            deleteSession(session.uploadId());
        }
    }

    @Test
    void cancellationRequiresOwnerProfileAndRouteContext() {
        AuthenticatedPrincipal uploader = adminPrincipal(
                8_000_010L, "cancel-owner");
        AuthenticatedPrincipal otherAdmin = adminPrincipal(
                8_000_011L, "cancel-other");
        DirectUploadSessionResponse session = directUploadService.create(
                uploader,
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                null,
                "CUSTOMER_SERVICE_CONVERSATION",
                43L,
                new DirectUploadSessionRequest(
                        "chat.png", "image/png", 100, null)
        );
        try {
            assertUnavailable(() -> directUploadService.cancel(
                    otherAdmin,
                    session.uploadId(),
                    StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                    43L
            ));
            assertUnavailable(() -> directUploadService.cancel(
                    uploader,
                    session.uploadId(),
                    StorageUploadProfile.USER_AVATAR,
                    43L
            ));
            assertUnavailable(() -> directUploadService.cancel(
                    uploader,
                    session.uploadId(),
                    StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                    999L
            ));
            assertThat(cancelState(session.uploadId()).status())
                    .isEqualTo("INITIATED");

            directUploadService.cancel(
                    uploader,
                    session.uploadId(),
                    StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                    43L
            );
            CancelState cancelled = cancelState(session.uploadId());
            assertThat(cancelled.status()).isEqualTo("FAILED");
            assertThat(cancelled.failureCode()).isEqualTo("CLIENT_ABORTED");
        } finally {
            deleteSession(session.uploadId());
        }
    }

    @Test
    void cancellationIsIdempotentAndImmediatelyReleasesAnActiveSlot() {
        AuthenticatedPrincipal uploader = adminPrincipal(
                8_000_012L, "cancel-rate-limit");
        String cancelledUploadId = null;
        try {
            for (int index = 0;
                    index < maxActiveSessionsPerPrincipal;
                    index++) {
                DirectUploadSessionResponse session =
                        directUploadService.createLibrary(
                                uploader,
                                new DirectUploadSessionRequest(
                                        "photo-" + index + ".png",
                                        "image/png",
                                        100,
                                        null
                                )
                        );
                if (cancelledUploadId == null) {
                    cancelledUploadId = session.uploadId();
                }
            }
            String uploadId = cancelledUploadId;
            assertThatThrownBy(() -> directUploadService.createLibrary(
                    uploader,
                    new DirectUploadSessionRequest(
                            "blocked.png", "image/png", 100, null)
            ))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_DIRECT_UPLOAD_RATE_LIMITED));

            directUploadService.cancelLibrary(uploader, uploadId);
            directUploadService.cancelLibrary(uploader, uploadId);

            CancelState cancelled = cancelState(uploadId);
            assertThat(cancelled.status()).isEqualTo("FAILED");
            assertThat(cancelled.failureCode()).isEqualTo("CLIENT_ABORTED");
            assertThat(cancelled.stagingDeletedAt()).isNotNull();
            assertThat(cancelled.outputsDeletedAt()).isNotNull();
            assertThat(directUploadService.createLibrary(
                    uploader,
                    new DirectUploadSessionRequest(
                            "replacement.png", "image/png", 100, null)
            ).uploadId()).isNotBlank();
        } finally {
            deletePrincipalUploadState(uploader);
        }
    }

    @Test
    void cancellationCannotRevokeProcessingTokenOrDeleteItsObjects()
            throws Exception {
        AuthenticatedPrincipal uploader = adminPrincipal(
                8_000_013L, "cancel-processing");
        byte[] png = png();
        DirectUploadSessionResponse session = directUploadService.createLibrary(
                uploader,
                new DirectUploadSessionRequest(
                        "processing.png", "image/png", png.length, null)
        );
        SessionLocations locations = sessionLocations(session.uploadId());
        StorageObjectLocation staging = locations.stagingLocation();
        StorageObjectLocation output = locations.finalLocation();
        storageProvider.put(
                staging, "image/png",
                new ByteArrayInputStream(png), png.length);
        storageProvider.put(
                output, "image/webp",
                new ByteArrayInputStream(png), png.length);
        jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'PROCESSING',
                            processing_token = 'processing-owner-token',
                            processing_started_at = current_timestamp
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .update();
        try {
            assertUnavailable(() -> directUploadService.cancelLibrary(
                    uploader, session.uploadId()));
            assertThat(cancelState(session.uploadId()).status())
                    .isEqualTo("PROCESSING");
            assertThat(jdbcClient.sql("""
                            select processing_token
                            from storage_upload_session
                            where id = :id
                            """)
                    .param("id", session.uploadId())
                    .query(String.class)
                    .single()).isEqualTo("processing-owner-token");
            assertThat(storageProvider.metadata(staging).sizeBytes())
                    .isEqualTo(png.length);
            assertThat(storageProvider.metadata(output).sizeBytes())
                    .isEqualTo(png.length);
        } finally {
            storageProvider.delete(staging);
            storageProvider.delete(output);
            deletePrincipalUploadState(uploader);
        }
    }

    @Test
    void cleanupFailureDoesNotRollBackCancelledStateAndCanBeRetried() {
        AuthenticatedPrincipal uploader = adminPrincipal(
                8_000_014L, "cancel-cleanup");
        StorageProvider failingDeleteProvider = spy(storageProvider);
        doAnswer(invocation -> {
            throw new IllegalStateException("simulated COS delete failure");
        }).when(failingDeleteProvider).delete(
                any(StorageObjectLocation.class));
        DirectUploadService cancellingService =
                serviceWithProvider(failingDeleteProvider);
        DirectUploadSessionResponse session =
                cancellingService.createLibrary(
                        uploader,
                        new DirectUploadSessionRequest(
                                "cleanup.png", "image/png", 100, null)
                );
        try {
            cancellingService.cancelLibrary(uploader, session.uploadId());

            CancelState persisted = cancelState(session.uploadId());
            assertThat(persisted.status()).isEqualTo("FAILED");
            assertThat(persisted.failureCode()).isEqualTo("CLIENT_ABORTED");
            assertThat(persisted.stagingDeletedAt()).isNull();
            assertThat(persisted.outputsDeletedAt()).isNull();

            directUploadService.cleanupExpiredSessions();
            CancelState retried = cancelState(session.uploadId());
            assertThat(retried.stagingDeletedAt()).isNotNull();
            assertThat(retried.outputsDeletedAt()).isNotNull();
        } finally {
            deletePrincipalUploadState(uploader);
        }
    }

    @Test
    void expiredCancellationIsAnIdempotentCleanupRequest() {
        AuthenticatedPrincipal uploader = adminPrincipal(
                8_000_015L, "cancel-expired");
        DirectUploadSessionResponse session = directUploadService.createLibrary(
                uploader,
                new DirectUploadSessionRequest(
                        "expired.png", "image/png", 100, null)
        );
        jdbcClient.sql("""
                        update storage_upload_session
                        set status = 'EXPIRED'
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .update();
        try {
            directUploadService.cancelLibrary(uploader, session.uploadId());
            directUploadService.cancelLibrary(uploader, session.uploadId());
            CancelState state = cancelState(session.uploadId());
            assertThat(state.status()).isEqualTo("EXPIRED");
            assertThat(state.stagingDeletedAt()).isNotNull();
            assertThat(state.outputsDeletedAt()).isNotNull();
        } finally {
            deletePrincipalUploadState(uploader);
        }
    }

    @Test
    void actualImageFormatMustMatchDeclaredContentType() throws Exception {
        byte[] png = png();
        DirectUploadSessionResponse session = directUploadService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "disguised.jpg", "image/jpeg", png.length, null)
        );
        SessionObject staging = sessionObject(session.uploadId());
        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/jpeg",
                new ByteArrayInputStream(png),
                png.length
        );

        assertThatThrownBy(() -> directUploadService.completeLibrary(
                ADMIN, session.uploadId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
        assertThat(jdbcClient.sql("""
                        select status
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .query(String.class)
                .single()).isEqualTo("FAILED");
    }

    @Test
    void directChatObjectsUseCosEtagsForMainAndThumbnailCaches()
            throws Exception {
        byte[] png = png();
        long conversationId = 7_000_001L;
        DirectUploadSessionResponse session = directUploadService.create(
                ADMIN,
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                null,
                "CUSTOMER_SERVICE_CONVERSATION",
                conversationId,
                new DirectUploadSessionRequest(
                        "chat.png", "image/png", png.length, null)
        );
        SessionObject staging = sessionObject(session.uploadId());
        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/png",
                new ByteArrayInputStream(png),
                png.length
        );
        StorageAssetResponse asset = directUploadService.complete(
                ADMIN,
                session.uploadId(),
                StorageUploadProfile.CUSTOMER_SERVICE_IMAGE,
                conversationId
        ).asset();

        assertThat(storageService.customerServiceImageResource(
                asset.id(), conversationId).getHeaders().getETag())
                .isEqualTo("\"test-etag\"");
        assertThat(storageService.customerServiceThumbnailResource(
                asset.id(), conversationId).getHeaders().getETag())
                .isEqualTo("\"test-etag\"");
    }

    @Test
    void activeSessionLimitIsEnforcedPerPrincipal() {
        AuthenticatedPrincipal uploader = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                8_000_001L,
                "limited-admin",
                List.of("R_SUPER"),
                List.of("asset:upload")
        );
        try {
            for (int index = 0;
                    index < maxActiveSessionsPerPrincipal;
                    index++) {
                directUploadService.createLibrary(
                        uploader,
                        new DirectUploadSessionRequest(
                                "photo-" + index + ".png",
                                "image/png",
                                100,
                                null
                        )
                );
            }
            assertThatThrownBy(() -> directUploadService.createLibrary(
                    uploader,
                    new DirectUploadSessionRequest(
                            "one-too-many.png", "image/png", 100, null)
            ))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(
                                    ErrorCode.STORAGE_DIRECT_UPLOAD_RATE_LIMITED));
        } finally {
            jdbcClient.sql("""
                            delete from storage_upload_session
                            where principal_kind = 'ADMIN'
                              and principal_id = :principalId
                            """)
                    .param("principalId", uploader.subjectId())
                    .update();
            jdbcClient.sql("""
                            delete from storage_upload_principal_guard
                            where principal_kind = 'ADMIN'
                              and principal_id = :principalId
                            """)
                    .param("principalId", uploader.subjectId())
                    .update();
        }
    }

    @Test
    void rejectsAnimatedImagesWhoseAggregatePixelsAreExcessive()
            throws Exception {
        byte[] gif = gif();
        StorageProvider oversizedAnimationProvider = spy(storageProvider);
        doAnswer(invocation -> {
            List<StorageProvider.ImageProcessOutput> outputs =
                    invocation.getArgument(1);
            return outputs.stream()
                    .map(output -> new ProcessedImage(
                            output.objectKey(),
                            "webp",
                            "image/webp",
                            123,
                            800,
                            800,
                            1,
                            "oversized-animation-etag",
                            "gif",
                            1000,
                            1000,
                            300
                    ))
                    .toList();
        }).when(oversizedAnimationProvider).processImage(
                any(StorageObjectLocation.class), anyList());
        DirectUploadService guardedService =
                serviceWithProvider(oversizedAnimationProvider);

        DirectUploadSessionResponse session = guardedService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "animation.gif", "image/gif", gif.length, null)
        );
        SessionObject staging = sessionObject(session.uploadId());
        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/gif",
                new ByteArrayInputStream(gif),
                gif.length
        );

        assertThatThrownBy(() -> guardedService.completeLibrary(
                ADMIN, session.uploadId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }

    @Test
    void rejectsAnimatedWebpWhoseAggregatePixelsAreExcessive()
            throws Exception {
        byte[] imageBytes = png();
        StorageProvider oversizedAnimationProvider = spy(storageProvider);
        doAnswer(invocation -> {
            List<StorageProvider.ImageProcessOutput> outputs =
                    invocation.getArgument(1);
            return outputs.stream()
                    .map(output -> new ProcessedImage(
                            output.objectKey(),
                            "webp",
                            "image/webp",
                            123,
                            800,
                            800,
                            1,
                            "oversized-animation-etag",
                            "webp",
                            1000,
                            1000,
                            300
                    ))
                    .toList();
        }).when(oversizedAnimationProvider).processImage(
                any(StorageObjectLocation.class), anyList());
        DirectUploadService guardedService =
                serviceWithProvider(oversizedAnimationProvider);

        DirectUploadSessionResponse session = guardedService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "animation.webp",
                        "image/webp",
                        imageBytes.length,
                        null
                )
        );
        SessionObject staging = sessionObject(session.uploadId());
        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/webp",
                new ByteArrayInputStream(imageBytes),
                imageBytes.length
        );

        assertThatThrownBy(() -> guardedService.completeLibrary(
                ADMIN, session.uploadId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }

    @Test
    void retryableProviderFailuresBackOffAndStopAfterThreeAttempts()
            throws Exception {
        byte[] png = png();
        AtomicInteger processCalls = new AtomicInteger();
        StorageProvider failingProvider = spy(storageProvider);
        doAnswer(invocation -> {
            processCalls.incrementAndGet();
            invocation.callRealMethod();
            throw new IllegalStateException(
                    "simulated failure after COS persisted output");
        }).when(failingProvider).processImage(
                any(StorageObjectLocation.class), anyList());
        DirectUploadService retryingService =
                serviceWithProvider(failingProvider);

        DirectUploadSessionResponse session = retryingService.createLibrary(
                ADMIN,
                new DirectUploadSessionRequest(
                        "retry.png", "image/png", png.length, null)
        );
        SessionObject staging = sessionObject(session.uploadId());
        StorageObjectLocation stagingLocation = new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                staging.container(),
                staging.region(),
                staging.objectKey()
        );
        storageProvider.put(
                stagingLocation,
                "image/png",
                new ByteArrayInputStream(png),
                png.length
        );

        assertProcessingFailure(retryingService, session.uploadId());
        ProcessingState firstFailure = processingState(session.uploadId());
        assertThat(firstFailure.status()).isEqualTo("INITIATED");
        assertThat(firstFailure.attempts()).isEqualTo(1);
        assertThat(firstFailure.nextAttemptAt()).isAfter(databaseNow());

        assertProcessingFailure(retryingService, session.uploadId());
        assertThat(processCalls).hasValue(1);
        assertThat(processingState(session.uploadId()).attempts()).isEqualTo(1);

        makeProcessingRetryDue(session.uploadId());
        assertProcessingFailure(retryingService, session.uploadId());
        ProcessingState secondFailure = processingState(session.uploadId());
        assertThat(secondFailure.status()).isEqualTo("INITIATED");
        assertThat(secondFailure.attempts()).isEqualTo(2);
        assertThat(secondFailure.nextAttemptAt()).isAfter(databaseNow());
        assertThat(processCalls).hasValue(2);

        makeProcessingRetryDue(session.uploadId());
        assertProcessingFailure(retryingService, session.uploadId());
        ProcessingState exhausted = processingState(session.uploadId());
        assertThat(exhausted.status()).isEqualTo("FAILED");
        assertThat(exhausted.attempts()).isEqualTo(3);
        assertThat(exhausted.nextAttemptAt()).isNull();
        assertThat(exhausted.stagingDeletedAt()).isNotNull();
        assertThat(exhausted.outputsDeletedAt()).isNotNull();
        assertThat(processCalls).hasValue(3);

        assertThatThrownBy(() -> retryingService.completeLibrary(
                ADMIN, session.uploadId()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_FILE_UNAVAILABLE));
        assertThat(processCalls).hasValue(3);
        assertThatThrownBy(() -> storageProvider.metadata(stagingLocation))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedStagingCleanupIsRetriedWithoutDeletingFinalObject()
            throws Exception {
        byte[] png = png();
        AuthenticatedPrincipal uploader = new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                8_000_002L,
                "cleanup-admin",
                List.of("R_SUPER"),
                List.of("asset:upload")
        );
        StorageProvider flakyProvider = spy(storageProvider);
        AtomicBoolean failFirstDelete = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirstDelete.getAndSet(false)) {
                throw new IllegalStateException("simulated COS delete failure");
            }
            return invocation.callRealMethod();
        }).when(flakyProvider).delete(any(StorageObjectLocation.class));
        DirectUploadService flakyService = serviceWithProvider(flakyProvider);

        DirectUploadSessionResponse session = flakyService.createLibrary(
                uploader,
                new DirectUploadSessionRequest(
                        "cleanup.png", "image/png", png.length, null)
        );
        SessionObject staging = sessionObject(session.uploadId());
        storageProvider.put(
                new StorageObjectLocation(
                        StorageProviderKind.TENCENT_COS,
                        staging.container(),
                        staging.region(),
                        staging.objectKey()
                ),
                "image/png",
                new ByteArrayInputStream(png),
                png.length
        );
        StorageAssetResponse asset = flakyService.completeLibrary(
                uploader, session.uploadId());
        assertThat(jdbcClient.sql("""
                        select staging_deleted_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .query(LocalDateTime.class)
                .optional()).isEmpty();

        flakyService.cleanupExpiredSessions();

        assertThat(jdbcClient.sql("""
                        select staging_deleted_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", session.uploadId())
                .query(LocalDateTime.class)
                .optional()).isPresent();
        String finalObjectKey = jdbcClient.sql("""
                        select object_key
                        from storage_asset
                        where id = :assetId
                        """)
                .param("assetId", asset.id())
                .query(String.class)
                .single();
        assertThat(storageProvider.metadata(new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                staging.container(),
                staging.region(),
                finalObjectKey
        )).sizeBytes()).isPositive();
    }

    private SessionObject sessionObject(String uploadId) {
        return jdbcClient.sql("""
                        select storage_container, storage_region,
                               staging_object_key, expires_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", uploadId)
                .query((rs, rowNum) -> new SessionObject(
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("staging_object_key"),
                        rs.getObject("expires_at", LocalDateTime.class)))
                .single();
    }

    private SessionLocations sessionLocations(String uploadId) {
        return jdbcClient.sql("""
                        select storage_container, storage_region,
                               staging_object_key, final_object_key
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", uploadId)
                .query((rs, rowNum) -> new SessionLocations(
                        rs.getString("storage_container"),
                        rs.getString("storage_region"),
                        rs.getString("staging_object_key"),
                        rs.getString("final_object_key")))
                .single();
    }

    private CancelState cancelState(String uploadId) {
        return jdbcClient.sql("""
                        select status, failure_code,
                               staging_deleted_at, outputs_deleted_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", uploadId)
                .query((rs, rowNum) -> new CancelState(
                        rs.getString("status"),
                        rs.getString("failure_code"),
                        rs.getObject(
                                "staging_deleted_at", LocalDateTime.class),
                        rs.getObject(
                                "outputs_deleted_at", LocalDateTime.class)))
                .single();
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_FILE_UNAVAILABLE));
    }

    private AuthenticatedPrincipal adminPrincipal(long id, String username) {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                id,
                username,
                List.of("R_SUPER"),
                List.of("asset:upload")
        );
    }

    private void deletePrincipalUploadState(
            AuthenticatedPrincipal principal
    ) {
        jdbcClient.sql("""
                        delete from storage_upload_session
                        where principal_kind = :principalKind
                          and principal_id = :principalId
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .update();
        jdbcClient.sql("""
                        delete from storage_upload_principal_guard
                        where principal_kind = :principalKind
                          and principal_id = :principalId
                        """)
                .param("principalKind", principal.kind().name())
                .param("principalId", principal.subjectId())
                .update();
    }

    private void deleteSession(String uploadId) {
        jdbcClient.sql("""
                        delete from storage_upload_session
                        where id = :id
                        """)
                .param("id", uploadId)
                .update();
    }

    private void assertProcessingFailure(
            DirectUploadService service,
            String uploadId
    ) {
        assertThatThrownBy(() -> service.completeLibrary(ADMIN, uploadId))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(
                                ErrorCode.STORAGE_IMAGE_PROCESSING_FAILED));
    }

    private ProcessingState processingState(String uploadId) {
        return jdbcClient.sql("""
                        select status, processing_attempts,
                               next_processing_attempt_at,
                               staging_deleted_at, outputs_deleted_at
                        from storage_upload_session
                        where id = :id
                        """)
                .param("id", uploadId)
                .query((rs, rowNum) -> new ProcessingState(
                        rs.getString("status"),
                        rs.getInt("processing_attempts"),
                        rs.getObject(
                                "next_processing_attempt_at",
                                LocalDateTime.class
                        ),
                        rs.getObject(
                                "staging_deleted_at",
                                LocalDateTime.class
                        ),
                        rs.getObject(
                                "outputs_deleted_at",
                                LocalDateTime.class
                        )
                ))
                .single();
    }

    private void makeProcessingRetryDue(String uploadId) {
        jdbcClient.sql("""
                        update storage_upload_session
                        set next_processing_attempt_at = :retryAt
                        where id = :id
                        """)
                .param("retryAt", databaseNow().minusSeconds(1))
                .param("id", uploadId)
                .update();
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();
    }

    private DirectUploadService serviceWithProvider(
            StorageProvider provider
    ) {
        return new DirectUploadService(
                jdbcClient,
                provider,
                uploadPolicy,
                keyGenerator,
                configService,
                storageService,
                transactionManager,
                Duration.ofDays(7),
                10,
                60,
                600
        );
    }

    private record SessionObject(
            String container,
            String region,
            String objectKey,
            LocalDateTime expiresAt
    ) {
    }

    private record SessionLocations(
            String container,
            String region,
            String stagingObjectKey,
            String finalObjectKey
    ) {
        private StorageObjectLocation stagingLocation() {
            return new StorageObjectLocation(
                    StorageProviderKind.TENCENT_COS,
                    container,
                    region,
                    stagingObjectKey
            );
        }

        private StorageObjectLocation finalLocation() {
            return new StorageObjectLocation(
                    StorageProviderKind.TENCENT_COS,
                    container,
                    region,
                    finalObjectKey
            );
        }
    }

    private record CancelState(
            String status,
            String failureCode,
            LocalDateTime stagingDeletedAt,
            LocalDateTime outputsDeletedAt
    ) {
    }

    private record ProcessingState(
            String status,
            int attempts,
            LocalDateTime nextAttemptAt,
            LocalDateTime stagingDeletedAt,
            LocalDateTime outputsDeletedAt
    ) {
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] gif() throws Exception {
        BufferedImage image = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", output);
        return output.toByteArray();
    }
}
