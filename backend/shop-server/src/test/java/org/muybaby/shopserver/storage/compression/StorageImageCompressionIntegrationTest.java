package org.muybaby.shopserver.storage.compression;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.compression.config.ImageCompressionRuntimeConfigService;
import org.muybaby.shopserver.storage.compression.dto.AdminImageCompressionConfigRequest;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "shop.storage.image-compression.api-key=test-tinify-key")
@ActiveProfiles("test")
class StorageImageCompressionIntegrationTest {

    private static final byte[] WEBP_80_BY_80 = Base64.getDecoder().decode("""
            UklGRs4CAABXRUJQVlA4IMICAACwEACdASpQAFAAPrFMoUmnJKOhLhdMAOAWCWcAzNe0WZELdMOrrI61aJ4BVnXAgrdVIDUrszd8F3VX2yXbaAGkyMq/bTrMcn8qqvdBPY90WFsGRzuIEu16A9LhLMVYBFuZO1+QayEuJZTYbINLrJbjDvCOtlu0FQkV3c3Rzapq1vUgZfVdntqrTOW+M/wgAP71/RhN2HY+CQi/cAaciXdXTNNVno+7dC5IlTdbKEEaqjVvRpdcmD574LWaxCpnPLKZ8w3wXjZhVymH8WWp0zMV3N8cKClrQAqT0HZav3BW9PGlv5KGbj5auZn2wQ68GfUCkMzh1NkgD1/7hs7U+IsXGea/IPy9QG4BdL+AE6SHAJXZ1XO0p05TLvXeMCs1YWSwuHtFlEUNSpcgwrM27txbMv3JVN7xUajcp45qQpxgRdJ7A4ej8y7yZ/DoEyehEt0nQMQ/VgLuMExqj1ZGgxlCGxJ4vZPIqpkRR7T8F9E8MfLH+VqqxQiyJ2devw3K2ZA02otrCnZUv4r3dQIc5GEbn5rugR3FE8ycCP9lppMhGFM33LXEDJapleiER3fx/Rj2ZxexdCzdgywrxCqT7zZVrY3QfRx1DkW0Ge6pk5qgdB9Q4lr2SLfb32ZXvGoZK1QZBHXmd1pnOoWNyqrPPd+XRT4dONb1rwWZ/twSPZm+LuCm/g3+QVugdZHVpils6IjGmdjNPlj/cY0B+Ud9BPW5dsWcSohf6tzzEfG0ti0Dgsf68qo0ZVG45D/k6arvIXUbBiIFU4PB4xDrudWWnTSVhL5WPOqHH+TvLPDFxjvlX0nw8QyuJkPKpoRdogdl9vi8UbUYVH/1GlMRK27etM0N5rFk4owMZZYMgJ6ALf4soeO+5TvrIw5NzR78KeFgtw6Gcm27etqw6VCznf5KPbem07Kp9f2OzaYCivh1yhd2aMAA
            """.replaceAll("\\s", ""));

    @Autowired
    private StorageService storageService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ImageCompressionRuntimeConfigService configService;

    @MockitoBean
    private ImageCompressionService imageCompressionService;

    @MockitoBean
    private StorageProvider storageProvider;

    @BeforeEach
    void clearCompressionRuntimeState() {
        jdbcClient.sql("delete from image_compression_runtime_setting").update();
    }

    @ParameterizedTest
    @CsvSource({
            "catalog.png,image/png,png",
            "catalog.jpg,image/jpeg,jpg"
    })
    void pngAndJpegArePersistedAsTheVerifiedWebpOutput(
            String filename,
            String contentType,
            String imageFormat
    ) {
        byte[] source = noisyImage(imageFormat, 80, 80);
        assertThat(source.length).isGreaterThan(WEBP_80_BY_80.length);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenReturn(new ImageCompressionResult(
                        WEBP_80_BY_80,
                        "image/webp",
                        80,
                        80,
                        OptionalLong.of(17)
                ));

        StorageAssetResponse response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart(filename, contentType, source)
        );

        var requestCaptor =
                org.mockito.ArgumentCaptor.forClass(ImageCompressionRequest.class);
        verify(imageCompressionService).compress(eq("test-tinify-key"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().content()).isEqualTo(source);
        assertThat(requestCaptor.getValue().contentType()).isEqualTo(contentType);
        assertThat(requestCaptor.getValue().maxOutputBytes()).isEqualTo(5L * 1024 * 1024);

        assertThat(providerWrites).singleElement().satisfies(write -> {
            assertThat(write.contentType()).isEqualTo("image/webp");
            assertThat(write.bytes()).isEqualTo(WEBP_80_BY_80);
            assertThat(write.sizeBytes()).isEqualTo(WEBP_80_BY_80.length);
            assertThat(write.location().objectKey()).endsWith(".webp");
        });
        assertThat(response.originalFilename()).isEqualTo("catalog.webp");
        assertThat(response.contentType()).isEqualTo("image/webp");
        assertThat(response.extension()).isEqualTo("webp");
        assertThat(response.sizeBytes()).isEqualTo(WEBP_80_BY_80.length);
        assertThat(response.width()).isEqualTo(80);
        assertThat(response.height()).isEqualTo(80);

        StoredAssetRow row = storedAsset(response.id());
        assertThat(row.originalFilename()).isEqualTo("catalog.webp");
        assertThat(row.contentType()).isEqualTo("image/webp");
        assertThat(row.extension()).isEqualTo("webp");
        assertThat(row.sizeBytes()).isEqualTo(WEBP_80_BY_80.length);
        assertThat(row.sha256()).isEqualTo(sha256(WEBP_80_BY_80));
        assertThat(row.width()).isEqualTo(80);
        assertThat(row.height()).isEqualTo(80);
        assertThat(row.objectKey()).isEqualTo(providerWrites.getFirst().location().objectKey());

        assertThat(configService.current().compressionCount()).isEqualTo(17);
        assertThat(configService.current().remainingCount()).isEqualTo(483);
        assertThat(configService.current().persisted()).isFalse();
        assertThat(configService.effective().enabled()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("passthroughCompressionImages")
    void gifSvgAndWebpBypassCompressionAndKeepTheirOriginalRepresentation(
            String filename,
            String contentType,
            byte[] source
    ) {
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart(filename, contentType, source)
        );

        verifyNoInteractions(imageCompressionService);
        assertThat(providerWrites).singleElement().satisfies(write -> {
            assertThat(write.contentType()).isEqualTo(contentType);
            assertThat(write.bytes()).isEqualTo(source);
            assertThat(write.sizeBytes()).isEqualTo(source.length);
        });
        String extension = filename.substring(filename.lastIndexOf('.') + 1);
        assertThat(response.originalFilename()).isEqualTo(filename);
        assertThat(response.contentType()).isEqualTo(contentType);
        assertThat(response.extension()).isEqualTo(extension);

        StoredAssetRow row = storedAsset(response.id());
        assertThat(row.originalFilename()).isEqualTo(filename);
        assertThat(row.contentType()).isEqualTo(contentType);
        assertThat(row.extension()).isEqualTo(extension);
        assertThat(row.sizeBytes()).isEqualTo(source.length);
        assertThat(row.sha256()).isEqualTo(sha256(source));
    }

    @Test
    void miniProgramAvatarUploadBypassesCompressionAndKeepsTheOriginal() {
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadUserAvatar(
                appPrincipal(1L),
                multipart("avatar.png", "image/png", source)
        );

        verifyNoInteractions(imageCompressionService);
        assertThat(response.originalFilename()).isEqualTo("avatar.png");
        assertOriginalPngWasStored(response, source, providerWrites.getFirst());
    }

    @Test
    void miniProgramAfterSaleUploadBypassesCompressionAndKeepsTheOriginal() {
        long userId = 91001L;
        long orderId = 92001L;
        jdbcClient.sql("""
                        insert into app_user (id, openid, status)
                        values (:userId, 'compression-app-user', 'ENABLED')
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent,
                             coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent)
                        values
                            (:orderId, 'COMPRESSION-APP-ORDER', :userId, 'PAID', 'CART',
                             'compression-app-order', 1000, 1000, 0, 0, 1000, 1000)
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .update();
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadAfterSaleEvidence(
                appPrincipal(userId),
                orderId,
                multipart("after-sale-evidence.png", "image/png", source)
        );

        verifyNoInteractions(imageCompressionService);
        assertThat(response.originalFilename()).isEqualTo("after-sale-evidence.png");
        assertOriginalPngWasStored(response, source, providerWrites.getFirst());
    }

    @Test
    void miniProgramCustomerServiceUploadBypassesCompressionAndKeepsTheOriginal() {
        long userId = 93001L;
        long conversationId = 93002L;
        jdbcClient.sql("""
                        insert into app_user (id, openid, status)
                        values (:userId, 'compression-customer-service-app-user', 'ENABLED')
                        """)
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        insert into customer_service_conversation (id, app_user_id, status)
                        values (:conversationId, :userId, 'ACTIVE')
                        """)
                .param("conversationId", conversationId)
                .param("userId", userId)
                .update();
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadCustomerServiceImageFromApp(
                appPrincipal(userId),
                conversationId,
                multipart("mini-program-customer-service.png", "image/png", source)
        );

        verifyNoInteractions(imageCompressionService);
        assertThat(response.originalFilename())
                .isEqualTo("mini-program-customer-service.png");
        assertOriginalPngWasStored(response, source, providerWrites.getFirst());
    }

    @Test
    void adminCustomerServiceUploadBypassesCompressionAndKeepsTheOriginal() {
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadCustomerServiceImage(
                adminPrincipal(),
                94001L,
                multipart("admin-customer-service.png", "image/png", source)
        );

        verifyNoInteractions(imageCompressionService);
        assertThat(response.originalFilename()).isEqualTo("admin-customer-service.png");
        assertOriginalPngWasStored(response, source, providerWrites.getFirst());
    }

    @Test
    void transientCompressionFailureRetriesTheSameSourceAndStoresWebp() {
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenThrow(new ImageCompressionException(
                        ImageCompressionFailure.UNAVAILABLE,
                        "Tinify unavailable",
                        503,
                        "ServiceUnavailable",
                        null,
                        null
                ))
                .thenReturn(new ImageCompressionResult(
                        WEBP_80_BY_80,
                        "image/webp",
                        80,
                        80,
                        OptionalLong.of(18)
                ));

        StorageAssetResponse response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("retry.png", "image/png", source)
        );

        var retryRequestCaptor =
                org.mockito.ArgumentCaptor.forClass(ImageCompressionRequest.class);
        verify(imageCompressionService, times(2))
                .compress(eq("test-tinify-key"), retryRequestCaptor.capture());
        assertThat(retryRequestCaptor.getAllValues()).allSatisfy(request -> {
            assertThat(request.content()).isEqualTo(source);
            assertThat(request.contentType()).isEqualTo("image/png");
        });
        assertThat(response.originalFilename()).isEqualTo("retry.webp");
        assertThat(response.contentType()).isEqualTo("image/webp");
        assertThat(providerWrites).singleElement().satisfies(write -> {
            assertThat(write.contentType()).isEqualTo("image/webp");
            assertThat(write.bytes()).isEqualTo(WEBP_80_BY_80);
        });
        assertThat(configService.effective().enabled()).isTrue();
        assertThat(configService.current().autoDisabledReason()).isEmpty();
    }

    @Test
    void repeatedTransientCompressionFailureRejectsUploadWithoutStoringTheOriginal() {
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenThrow(new ImageCompressionException(
                        ImageCompressionFailure.TIMEOUT,
                        "Tinify timed out",
                        null,
                        null,
                        null,
                        null
                ));

        assertThatThrownBy(() -> storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("must-compress.png", "image/png", source)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_IMAGE_COMPRESSION_FAILED));

        verify(imageCompressionService, times(2))
                .compress(eq("test-tinify-key"), any(ImageCompressionRequest.class));
        assertThat(providerWrites).isEmpty();
        assertThat(jdbcClient.sql("""
                                select count(*)
                                from storage_asset
                                where original_filename = 'must-compress.png'
                                """)
                .query(Integer.class)
                .single()).isZero();
        assertThat(configService.effective().enabled()).isTrue();
    }

    @Test
    void nonRetryableCompressionFailureRejectsUploadWithoutRetryingOrStoringTheOriginal() {
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenThrow(new ImageCompressionException(
                        ImageCompressionFailure.REJECTED,
                        "Tinify rejected the image",
                        400,
                        "BadRequest",
                        null,
                        null
                ));

        assertThatThrownBy(() -> storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("rejected.png", "image/png", source)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_IMAGE_COMPRESSION_FAILED));

        verify(imageCompressionService, times(1))
                .compress(eq("test-tinify-key"), any(ImageCompressionRequest.class));
        assertThat(providerWrites).isEmpty();
    }

    @Test
    void fixedWebpOutputIsUsedEvenWhenItIsLargerThanTheSource() {
        byte[] source = solidImage("png", 80, 80);
        assertThat(source.length).isLessThan(WEBP_80_BY_80.length);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenReturn(new ImageCompressionResult(
                        WEBP_80_BY_80,
                        "image/webp",
                        80,
                        80,
                        OptionalLong.of(1)
                ));

        StorageAssetResponse response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("fixed-output.png", "image/png", source)
        );

        assertThat(response.originalFilename()).isEqualTo("fixed-output.webp");
        assertThat(response.contentType()).isEqualTo("image/webp");
        assertThat(response.sizeBytes()).isEqualTo(WEBP_80_BY_80.length);
        assertThat(providerWrites).singleElement().satisfies(write -> {
            assertThat(write.contentType()).isEqualTo("image/webp");
            assertThat(write.bytes()).isEqualTo(WEBP_80_BY_80);
        });
    }

    @Test
    void webpFilenameOverflowRejectsUploadInsteadOfStoringTheOriginal() {
        String filename = "a".repeat(251) + ".png";
        assertThat(filename).hasSize(255);
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenReturn(new ImageCompressionResult(
                        WEBP_80_BY_80,
                        "image/webp",
                        80,
                        80,
                        OptionalLong.of(1)
                ));

        assertThatThrownBy(() -> storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart(filename, "image/png", source)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.STORAGE_IMAGE_COMPRESSION_FAILED));

        assertThat(providerWrites).isEmpty();
    }

    @Test
    void administratorCanDisableCompressionWithoutAffectingUploads() {
        configService.update(new AdminImageCompressionConfigRequest(
                false, "ENV", null, 500));
        byte[] source = noisyImage("png", 80, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();

        StorageAssetResponse response = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("disabled.png", "image/png", source)
        );

        verifyNoInteractions(imageCompressionService);
        assertOriginalPngWasStored(response, source, providerWrites.getFirst());
        assertThat(configService.current().requestedEnabled()).isFalse();
        assertThat(configService.current().effectiveEnabled()).isFalse();
    }

    @Test
    void exhaustedQuotaFallsBackAndAutomaticallyStopsSubsequentCompressionCalls() {
        byte[] firstSource = noisyImage("png", 80, 80);
        byte[] secondSource = noisyImage("png", 81, 80);
        List<ProviderWrite> providerWrites = captureProviderWrites();
        when(imageCompressionService.compress(eq("test-tinify-key"), any(ImageCompressionRequest.class)))
                .thenThrow(new ImageCompressionException(
                        ImageCompressionFailure.QUOTA_EXHAUSTED,
                        "Tinify quota exhausted",
                        429,
                        "TooManyRequests",
                        500L,
                        null
                ));

        StorageAssetResponse first = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("quota-first.png", "image/png", firstSource)
        );

        assertOriginalPngWasStored(first, firstSource, providerWrites.getFirst());
        assertThat(configService.current().requestedEnabled()).isTrue();
        assertThat(configService.current().effectiveEnabled()).isFalse();
        assertThat(configService.current().compressionCount()).isEqualTo(500);
        assertThat(configService.current().remainingCount()).isZero();
        assertThat(configService.current().autoDisabledReason()).isEqualTo("QUOTA_EXHAUSTED");

        StorageAssetResponse second = storageService.uploadLibrary(
                adminPrincipal(),
                null,
                multipart("quota-second.png", "image/png", secondSource)
        );

        verify(imageCompressionService, times(1))
                .compress(eq("test-tinify-key"), any(ImageCompressionRequest.class));
        assertThat(providerWrites).hasSize(2);
        assertOriginalPngWasStored(second, secondSource, providerWrites.get(1));
    }

    private List<ProviderWrite> captureProviderWrites() {
        List<ProviderWrite> writes = new ArrayList<>();
        when(storageProvider.put(
                any(StorageObjectLocation.class),
                anyString(),
                any(InputStream.class),
                anyLong()
        )).thenAnswer(invocation -> {
            StorageObjectLocation location = invocation.getArgument(0);
            String contentType = invocation.getArgument(1);
            InputStream inputStream = invocation.getArgument(2);
            long sizeBytes = invocation.getArgument(3);
            byte[] bytes = inputStream.readAllBytes();
            writes.add(new ProviderWrite(location, contentType, bytes, sizeBytes));
            return new StoredObject(
                    location.objectKey(),
                    contentType,
                    InputStream.nullInputStream(),
                    sizeBytes
            );
        });
        return writes;
    }

    private void assertOriginalPngWasStored(
            StorageAssetResponse response,
            byte[] source,
            ProviderWrite providerWrite
    ) {
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.extension()).isEqualTo("png");
        assertThat(response.sizeBytes()).isEqualTo(source.length);
        assertThat(providerWrite.contentType()).isEqualTo("image/png");
        assertThat(providerWrite.bytes()).isEqualTo(source);
        assertThat(providerWrite.sizeBytes()).isEqualTo(source.length);

        StoredAssetRow row = storedAsset(response.id());
        assertThat(row.contentType()).isEqualTo("image/png");
        assertThat(row.extension()).isEqualTo("png");
        assertThat(row.sizeBytes()).isEqualTo(source.length);
        assertThat(row.sha256()).isEqualTo(sha256(source));
        assertThat(row.objectKey()).isEqualTo(providerWrite.location().objectKey());
    }

    private StoredAssetRow storedAsset(Long assetId) {
        return jdbcClient.sql("""
                        select original_filename, content_type, extension, size_bytes,
                               sha256, width, height, object_key
                        from storage_asset
                        where id = :assetId
                        """)
                .param("assetId", assetId)
                .query((rs, rowNum) -> new StoredAssetRow(
                        rs.getString("original_filename"),
                        rs.getString("content_type"),
                        rs.getString("extension"),
                        rs.getLong("size_bytes"),
                        rs.getString("sha256"),
                        rs.getInt("width"),
                        rs.getInt("height"),
                        rs.getString("object_key")
                ))
                .single();
    }

    private static Stream<Arguments> passthroughCompressionImages() {
        byte[] gif = image("gif", 24, 16);
        byte[] svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="16">
                  <rect width="24" height="16" fill="#fff"/>
                </svg>
                """.getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("animated.gif", "image/gif", gif),
                Arguments.of("vector.svg", "image/svg+xml", svg),
                Arguments.of("already.webp", "image/webp", WEBP_80_BY_80)
        );
    }

    private static byte[] noisyImage(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(width * 31L + height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return writeImage(image, format);
    }

    private static byte[] image(String format, int width, int height) {
        return writeImage(
                new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED),
                format
        );
    }

    private static byte[] solidImage(String format, int width, int height) {
        return writeImage(
                new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                format
        );
    }

    private static byte[] writeImage(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, output)) {
                throw new IllegalStateException(format + " writer unavailable");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static MockMultipartFile multipart(
            String filename,
            String contentType,
            byte[] content
    ) {
        return new MockMultipartFile("file", filename, contentType, content);
    }

    private static AuthenticatedPrincipal adminPrincipal() {
        return new AuthenticatedPrincipal(
                TokenKind.ADMIN,
                1L,
                "Super",
                List.of("SUPER_ADMIN"),
                List.of("asset:upload")
        );
    }

    private static AuthenticatedPrincipal appPrincipal(long userId) {
        return new AuthenticatedPrincipal(
                TokenKind.APP,
                userId,
                "wechat-user",
                List.of(),
                List.of()
        );
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record ProviderWrite(
            StorageObjectLocation location,
            String contentType,
            byte[] bytes,
            long sizeBytes
    ) {
    }

    private record StoredAssetRow(
            String originalFilename,
            String contentType,
            String extension,
            long sizeBytes,
            String sha256,
            int width,
            int height,
            String objectKey
    ) {
    }
}
