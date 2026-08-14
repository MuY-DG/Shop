package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.muybaby.shopserver.storage.service.StorageAssetCleanupService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StorageServiceTest {

    @Autowired
    private StorageService storageService;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private MultipartProperties multipartProperties;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageAssetCleanupService cleanupService;

    @MockitoBean
    private StorageProvider storageProvider;

    @Test
    void testProfileConfiguresIndependentFiftyMegabyteVideoLimit() {
        assertThat(storageProperties.limits().videoMaxSize()).isEqualTo(DataSize.ofMegabytes(50));
        assertThat(storageProperties.limits().videoMaxSize())
                .isGreaterThan(storageProperties.limits().imageMaxSize());
        assertThat(multipartProperties.getMaxFileSize())
                .isGreaterThanOrEqualTo(storageProperties.limits().videoMaxSize());
        assertThat(multipartProperties.getMaxRequestSize())
                .isGreaterThan(multipartProperties.getMaxFileSize());
        assertThat(storageProperties.limits().imageMaxWidth()).isEqualTo(8192);
        assertThat(storageProperties.limits().imageMaxHeight()).isEqualTo(8192);
        assertThat(storageProperties.limits().imageMaxPixels()).isEqualTo(25_000_000L);
    }

    @Test
    void advertisedWebpUploadsHaveAnInstalledImageReader() {
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext()).isTrue();
    }

    @Test
    void realWebpImageIsDecodedAndPersisted() {
        byte[] webp = Base64.getDecoder().decode("""
                UklGRs4CAABXRUJQVlA4IMICAACwEACdASpQAFAAPrFMoUmnJKOhLhdMAOAWCWcAzNe0WZELdMOrrI61aJ4BVnXAgrdVIDUrszd8F3VX2yXbaAGkyMq/bTrMcn8qqvdBPY90WFsGRzuIEu16A9LhLMVYBFuZO1+QayEuJZTYbINLrJbjDvCOtlu0FQkV3c3Rzapq1vUgZfVdntqrTOW+M/wgAP71/RhN2HY+CQi/cAaciXdXTNNVno+7dC5IlTdbKEEaqjVvRpdcmD574LWaxCpnPLKZ8w3wXjZhVymH8WWp0zMV3N8cKClrQAqT0HZav3BW9PGlv5KGbj5auZn2wQ68GfUCkMzh1NkgD1/7hs7U+IsXGea/IPy9QG4BdL+AE6SHAJXZ1XO0p05TLvXeMCs1YWSwuHtFlEUNSpcgwrM27txbMv3JVN7xUajcp45qQpxgRdJ7A4ej8y7yZ/DoEyehEt0nQMQ/VgLuMExqj1ZGgxlCGxJ4vZPIqpkRR7T8F9E8MfLH+VqqxQiyJ2devw3K2ZA02otrCnZUv4r3dQIc5GEbn5rugR3FE8ycCP9lppMhGFM33LXEDJapleiER3fx/Rj2ZxexdCzdgywrxCqT7zZVrY3QfRx1DkW0Ge6pk5qgdB9Q4lr2SLfb32ZXvGoZK1QZBHXmd1pnOoWNyqrPPd+XRT4dONb1rwWZ/twSPZm+LuCm/g3+QVugdZHVpils6IjGmdjNPlj/cY0B+Ud9BPW5dsWcSohf6tzzEfG0ti0Dgsf68qo0ZVG45D/k6arvIXUbBiIFU4PB4xDrudWWnTSVhL5WPOqHH+TvLPDFxjvlX0nw8QyuJkPKpoRdogdl9vi8UbUYVH/1GlMRK27etM0N5rFk4owMZZYMgJ6ALf4soeO+5TvrIw5NzR78KeFgtw6Gcm27etqw6VCznf5KPbem07Kp9f2OzaYCivh1yhd2aMAA
                """.replaceAll("\\s", ""));

        var response = storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("avatar.webp", "image/webp", webp));

        assertThat(response.width()).isEqualTo(80);
        assertThat(response.height()).isEqualTo(80);
        assertThat(response.contentType()).isEqualTo("image/webp");
    }

    @Test
    void safeSvgImageIsValidatedAndPersistedWithIntrinsicDimensions() {
        byte[] svg = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd" [
                  <!ENTITY ns_ai "http://ns.adobe.com/AdobeIllustrator/10.0/">
                ]>
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:i="&ns_ai;" width="120" height="80" viewBox="0 0 24 16">
                  <defs><linearGradient id="paint"><stop stop-color="#fff"/></linearGradient></defs>
                  <rect width="24" height="16" fill="url(#paint)"/>
                </svg>
                """.getBytes(StandardCharsets.UTF_8);

        var response = storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("product-icon.svg", "image/svg+xml", svg));

        assertThat(response.width()).isEqualTo(120);
        assertThat(response.height()).isEqualTo(80);
        assertThat(response.extension()).isEqualTo("svg");
        assertThat(response.contentType()).isEqualTo("image/svg+xml");
    }

    @Test
    void svgViewBoxProvidesDimensionsWhenWidthAndHeightAreResponsive() {
        byte[] svg = """
                <!DOCTYPE svg>
                <svg xmlns="http://www.w3.org/2000/svg" width="100%" height="100%" viewBox="0 0 32 18">
                  <path d="M0 0h32v18H0z" fill="#fff"/>
                </svg>
                """.getBytes(StandardCharsets.UTF_8);

        var response = storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("responsive.svg", "image/svg+xml", svg));

        assertThat(response.width()).isEqualTo(32);
        assertThat(response.height()).isEqualTo(18);
    }

    @Test
    void svgWithActiveContentOrExternalReferencesIsRejected() {
        List<String> unsafeSvgFiles = List.of(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"https://example.com/a.png\"/></svg>",
                "<?xml-stylesheet href=\"https://example.com/a.css\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                "<!DOCTYPE svg SYSTEM \"https://example.com/evil.dtd\"><svg xmlns=\"http://www.w3.org/2000/svg\"/>",
                "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&xxe;</svg>"
        );

        for (String svg : unsafeSvgFiles) {
            TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                    "unsafe.svg", "image/svg+xml", svg.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
        }
    }

    @Test
    void unsupportedLibraryTypeIsRejectedBeforeReadingBytes() {
        TrackingMultipartFile file = TrackingMultipartFile.rejectIfRead("not-image.bmp", "image/bmp", 512);

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isFalse();
    }

    @Test
    void mismatchedImageExtensionAndContentTypeAreRejectedBeforeReadingBytes() {
        TrackingMultipartFile file = TrackingMultipartFile.rejectIfRead(
                "mismatch.jpg", "image/png", 512);

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isFalse();
    }

    @Test
    void oversizedImageIsRejectedBeforeReadingBytes() {
        TrackingMultipartFile file = TrackingMultipartFile.rejectIfRead("too-large.png", "image/png", 6L * 1024 * 1024);

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isFalse();
    }

    @Test
    void corruptedImageReadsBytesBeforeRejecting() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes("corrupted.png", "image/png", "broken-image".getBytes());

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isTrue();
    }

    @Test
    void decodedImageFormatMustMatchTheDeclaredType() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "disguised.gif", "image/gif", pngImage(3, 2));

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(file.bytesRead()).isTrue();
    }

    @Test
    void truncatedImageWithValidHeaderIsRejectedBeforeCreatingAnAsset() {
        Long assetsBefore = jdbcClient.sql("select count(*) from storage_asset")
                .query(Long.class)
                .single();
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "truncated.png",
                "image/png",
                pngHeader(3, 2)
        );

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(jdbcClient.sql("select count(*) from storage_asset")
                .query(Long.class)
                .single()).isEqualTo(assetsBefore);
    }

    @Test
    void oversizedDecodedImageIsRejectedFromMetadataBeforeCreatingAnAsset() {
        Long assetsBefore = jdbcClient.sql("select count(*) from storage_asset")
                .query(Long.class)
                .single();
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "pixel-bomb.png",
                "image/png",
                pngHeader(8192, 8192)
        );

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));

        assertThat(jdbcClient.sql("select count(*) from storage_asset")
                .query(Long.class)
                .single()).isEqualTo(assetsBefore);
    }

    @Test
    void validImageDimensionsAreReadFromMetadataAndPersisted() {
        var response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                TrackingMultipartFile.withBytes("dimensions.png", "image/png", pngImage(3, 2))
        );

        assertThat(response.width()).isEqualTo(3);
        assertThat(response.height()).isEqualTo(2);
    }

    @Test
    void everyAnimatedImageFrameIsDecodedAndDimensionChecked() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "oversized-later-frame.gif",
                "image/gif",
                gifAnimation(new int[][]{{3, 2}, {8193, 1}})
        );

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }

    @Test
    void oversizedGifLogicalCanvasIsRejectedEvenWhenItsFrameIsSmall() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "oversized-canvas.gif",
                "image/gif",
                gifWithLogicalScreen(8193, 2, 3, 2)
        );

        assertThatThrownBy(() -> storageService.uploadLibrary(adminPrincipal(), null, file))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.errorCode()).isEqualTo(ErrorCode.STORAGE_UPLOAD_POLICY_REJECTED));
    }

    @Test
    void libraryVideoIsDetectedWithoutImageDecoding() {
        TrackingMultipartFile file = TrackingMultipartFile.withBytes(
                "product-demo.mp4",
                "video/mp4",
                "test-video-bytes".getBytes()
        );

        var response = storageService.uploadLibrary(adminPrincipal(), null, file);

        assertThat(file.bytesRead()).isTrue();
        assertThat(response.scope()).isEqualTo("LIBRARY");
        assertThat(response.mediaKind()).isEqualTo("VIDEO");
        assertThat(response.visibility()).isEqualTo("PUBLIC");
        assertThat(response.contentType()).isEqualTo("video/mp4");
        assertThat(response.width()).isNull();
        assertThat(response.height()).isNull();
        assertThat(response.publicUrl()).contains("/public/library/video/");
    }

    @Test
    void providerPutAndDeleteRunOutsideTransactionsAroundCommittedStateTransitions() {
        when(storageProvider.put(
                any(StorageObjectLocation.class), anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    StorageObjectLocation location = invocation.getArgument(0);
                    return new StoredObject(location.objectKey(), invocation.getArgument(1), InputStream.nullInputStream(),
                            invocation.getArgument(3));
                });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storageProvider).delete(any(StorageObjectLocation.class));

        var response = storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("two-phase.mp4", "video/mp4", "video".getBytes()));

        assertThat(assetStatus(response.id())).isEqualTo("ACTIVE");
        assertThat(jdbcClient.sql("select cleanup_next_retry_at from storage_asset where id = :assetId")
                .param("assetId", response.id())
                .query(LocalDateTime.class)
                .optional()).isEmpty();

        storageService.delete(response.id());

        assertThat(assetStatus(response.id())).isEqualTo("DELETED");
    }

    @Test
    void failedUploadCleanupIsPersistedAndRetriedWithTheSameObjectLocation() {
        AtomicReference<StorageObjectLocation> attemptedLocation = new AtomicReference<>();
        when(storageProvider.put(
                any(StorageObjectLocation.class), anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> {
                    attemptedLocation.set(invocation.getArgument(0));
                    throw new IllegalStateException("provider put failed");
                });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<StorageObjectLocation>getArgument(0)).isEqualTo(attemptedLocation.get());
            throw new IllegalStateException("provider delete failed");
        })
                .doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(invocation.<StorageObjectLocation>getArgument(0)).isEqualTo(attemptedLocation.get());
                    return null;
                })
                .when(storageProvider).delete(any(StorageObjectLocation.class));

        assertThatThrownBy(() -> storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("failed.mp4", "video/mp4", "video".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider put failed");

        Long assetId = jdbcClient.sql("select max(id) from storage_asset")
                .query(Long.class)
                .single();
        assertThat(assetStatus(assetId)).isEqualTo("DELETE_PENDING");
        assertThat(jdbcClient.sql("select cleanup_attempts from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(Integer.class)
                .single()).isEqualTo(1);

        jdbcClient.sql("""
                        update storage_asset
                        set cleanup_next_retry_at = current_timestamp,
                            cleanup_lease_token = null
                        where id = :assetId
                        """)
                .param("assetId", assetId)
                .update();
        assertThat(cleanupService.cleanupExpiredAssets(100, Duration.ofMinutes(30)).cleanedCount())
                .isEqualTo(1);
        assertThat(assetStatus(assetId)).isEqualTo("DELETED");
    }

    @Test
    void failedLibraryDeleteRemainsHiddenAndIsRetried() {
        AtomicReference<StorageObjectLocation> storedLocation = new AtomicReference<>();
        when(storageProvider.put(
                any(StorageObjectLocation.class), anyString(), any(InputStream.class), anyLong()))
                .thenAnswer(invocation -> {
                    StorageObjectLocation location = invocation.getArgument(0);
                    storedLocation.set(location);
                    return new StoredObject(
                            location.objectKey(), "video/mp4", InputStream.nullInputStream(), 5);
                });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<StorageObjectLocation>getArgument(0)).isEqualTo(storedLocation.get());
            throw new IllegalStateException("provider delete failed");
        })
                .doAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(invocation.<StorageObjectLocation>getArgument(0)).isEqualTo(storedLocation.get());
                    return null;
                })
                .when(storageProvider).delete(any(StorageObjectLocation.class));
        var response = storageService.uploadLibrary(
                adminPrincipal(), null,
                TrackingMultipartFile.withBytes("delete-retry.mp4", "video/mp4", "video".getBytes()));

        storageService.delete(response.id());

        assertThat(assetStatus(response.id())).isEqualTo("DELETE_PENDING");
        jdbcClient.sql("""
                        update storage_asset
                        set cleanup_next_retry_at = current_timestamp,
                            cleanup_lease_token = null
                        where id = :assetId
                        """)
                .param("assetId", response.id())
                .update();
        assertThat(cleanupService.cleanupExpiredAssets(100, Duration.ofMinutes(30)).cleanedCount())
                .isEqualTo(1);
        assertThat(assetStatus(response.id())).isEqualTo("DELETED");
    }

    @Test
    void replacedAvatarCleanupDeletesCosObjectUsingRecordedLocation() {
        long userId = 991L;
        String bucket = "avatar-bucket-1250000000";
        String region = "ap-guangzhou";
        String objectKey = "public/library/image/2026/07/28/old-avatar.png";
        String publicUrl = "https://avatar.example.test/" + objectKey;
        jdbcClient.sql("""
                        insert into storage_asset
                            (scope, media_kind, visibility, provider, storage_container, storage_region,
                             object_key, original_filename, content_type, extension, size_bytes, sha256,
                             public_url, status, uploaded_by_type, uploaded_by_id,
                             upload_context_type, upload_context_id)
                        values
                            ('LIBRARY', 'IMAGE', 'PUBLIC', 'TENCENT_COS', :bucket, :region,
                             :objectKey, 'old-avatar.png', 'image/png', 'png', 1, '',
                             :publicUrl, 'ACTIVE', 'APP', :userId, 'APP_USER_AVATAR', :userId)
                        """)
                .param("bucket", bucket)
                .param("region", region)
                .param("objectKey", objectKey)
                .param("publicUrl", publicUrl)
                .param("userId", userId)
                .update();
        Long assetId = jdbcClient.sql("select id from storage_asset where object_key = :objectKey")
                .param("objectKey", objectKey)
                .query(Long.class)
                .single();

        storageService.cleanupReplacedUserAvatar(
                userId,
                publicUrl,
                "https://thirdwx.qlogo.cn/mmopen/new-avatar/132"
        );

        assertThat(assetStatus(assetId)).isEqualTo("DELETED");
        org.mockito.Mockito.verify(storageProvider).delete(new StorageObjectLocation(
                StorageProviderKind.TENCENT_COS,
                bucket,
                region,
                objectKey
        ));
    }

    private String assetStatus(Long assetId) {
        return jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(String.class)
                .single();
    }

    private AuthenticatedPrincipal adminPrincipal() {
        return new AuthenticatedPrincipal(TokenKind.ADMIN, 1L, "Super", List.of("SUPER_ADMIN"), List.of("asset:upload"));
    }

    private byte[] pngHeader(int width, int height) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(output);
            data.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            ByteArrayOutputStream ihdrBuffer = new ByteArrayOutputStream();
            DataOutputStream ihdr = new DataOutputStream(ihdrBuffer);
            ihdr.writeBytes("IHDR");
            ihdr.writeInt(width);
            ihdr.writeInt(height);
            ihdr.writeByte(8);
            ihdr.writeByte(2);
            ihdr.writeByte(0);
            ihdr.writeByte(0);
            ihdr.writeByte(0);
            byte[] chunk = ihdrBuffer.toByteArray();
            data.writeInt(13);
            data.write(chunk);
            CRC32 crc = new CRC32();
            crc.update(chunk);
            data.writeInt((int) crc.getValue());
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] pngImage(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("PNG writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private byte[] gifAnimation(int[][] dimensions) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (int[] dimension : dimensions) {
                BufferedImage frame = new BufferedImage(
                        dimension[0], dimension[1], BufferedImage.TYPE_BYTE_INDEXED);
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
            imageOutput.flush();
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } finally {
            writer.dispose();
        }
    }

    private byte[] gifWithLogicalScreen(int canvasWidth, int canvasHeight, int frameWidth, int frameHeight) {
        byte[] gif = gifAnimation(new int[][]{{frameWidth, frameHeight}});
        gif[6] = (byte) (canvasWidth & 0xff);
        gif[7] = (byte) ((canvasWidth >>> 8) & 0xff);
        gif[8] = (byte) (canvasHeight & 0xff);
        gif[9] = (byte) ((canvasHeight >>> 8) & 0xff);
        return gif;
    }

    private static final class TrackingMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final String contentType;
        private final long size;
        private final byte[] bytes;
        private final boolean rejectOnRead;
        private boolean bytesRead;

        private TrackingMultipartFile(String originalFilename, String contentType, long size, byte[] bytes, boolean rejectOnRead) {
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.size = size;
            this.bytes = bytes;
            this.rejectOnRead = rejectOnRead;
        }

        static TrackingMultipartFile rejectIfRead(String originalFilename, String contentType, long size) {
            return new TrackingMultipartFile(originalFilename, contentType, size, new byte[0], true);
        }

        static TrackingMultipartFile withBytes(String originalFilename, String contentType, byte[] bytes) {
            return new TrackingMultipartFile(originalFilename, contentType, bytes.length, bytes, false);
        }

        boolean bytesRead() {
            return bytesRead;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return size == 0;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public byte[] getBytes() throws IOException {
            bytesRead = true;
            if (rejectOnRead) {
                throw new IOException("getBytes should not be called");
            }
            return bytes;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(getBytes());
        }

        @Override
        public void transferTo(java.io.File dest) {
            throw new UnsupportedOperationException("Not needed in tests");
        }
    }
}
