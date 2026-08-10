package org.muybaby.shopserver.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.muybaby.shopserver.aftersale.dto.AppAfterSaleApplyRequest;
import org.muybaby.shopserver.aftersale.service.AppAfterSaleService;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupConfigService;
import org.muybaby.shopserver.maintenance.cleanup.DataCleanupTaskCode;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupConfigUpdateRequest;
import org.muybaby.shopserver.maintenance.cleanup.dto.DataCleanupTaskUpdateRequest;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.muybaby.shopserver.storage.dto.StorageAssetResponse;
import org.muybaby.shopserver.storage.service.PrivateStorageFileService;
import org.muybaby.shopserver.storage.service.StorageAssetCleanupService;
import org.muybaby.shopserver.storage.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ResourceLock("jvm-default-time-zone")
class StorageAssetTimezoneTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.10")
            .withDatabaseName("asset_timezone")
            .withUsername("shop_test")
            .withPassword("shop_test");

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a4x8AAAAASUVORK5CYII="
    );
    private static final AuthenticatedPrincipal ADMIN = new AuthenticatedPrincipal(
            TokenKind.ADMIN, 1L, "timezone-admin", List.of("R_SUPER"), List.of()
    );
    private static final long APP_USER_ID = 24_001L;
    private static final long ORDER_ID = 24_002L;
    private static final AuthenticatedPrincipal APP_USER = new AuthenticatedPrincipal(
            TokenKind.APP, APP_USER_ID, "timezone-app", List.of(), List.of()
    );

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "set time_zone = '+00:00'");
    }

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageService storageService;

    @Autowired
    private PrivateStorageFileService privateStorageFileService;

    @Autowired
    private StorageAssetCleanupService cleanupService;

    @Autowired
    private AppAfterSaleService appAfterSaleService;

    @Autowired
    private DataCleanupConfigService dataCleanupConfigService;

    @BeforeEach
    void resetRows() {
        jdbcClient.sql("delete from storage_asset_usage").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from payment_config").update();
        jdbcClient.sql("delete from storage_asset").update();
        jdbcClient.sql("delete from shop_order").update();
        jdbcClient.sql("delete from app_user").update();

        jdbcClient.sql("""
                        insert into app_user (id, openid, status)
                        values (:userId, 'timezone-openid', 'ENABLED')
                        """)
                .param("userId", APP_USER_ID)
                .update();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent,
                             coupon_discount_cent, freight_cent, payable_amount_cent, paid_amount_cent)
                        values
                            (:orderId, 'TZ-ASSET-ORDER', :userId, 'PAID', 'CART', 'tz-asset-order',
                             1000, 1000, 0, 0, 1000, 1000)
                        """)
                .param("orderId", ORDER_ID)
                .param("userId", APP_USER_ID)
                .update();
    }

    @Test
    void privateAssetTtlsAndExpiryValidationUseTheDatabaseClock() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
        try {
            StorageAssetResponse secret = storageService.uploadPaymentSecret(
                    ADMIN,
                    new MockMultipartFile(
                            "file", "timezone-secret.pem", "text/plain",
                            "-----BEGIN PRIVATE KEY-----\ntimezone-test\n-----END PRIVATE KEY-----"
                                    .getBytes(StandardCharsets.UTF_8)
                    )
            );
            StorageAssetResponse evidence = storageService.uploadAfterSaleEvidence(
                    APP_USER,
                    ORDER_ID,
                    new MockMultipartFile("file", "timezone-evidence.png", "image/png", TINY_PNG)
            );

            assertTtlMinutes(secret.id(), 119, 120);
            assertTtlMinutes(evidence.id(), 1_439, 1_440);
            var inspectedSecret = privateStorageFileService.inspectPaymentSecrets(List.of(secret.id()));
            assertThatCode(() -> privateStorageFileService.lockAndRevalidatePaymentSecrets(
                    inspectedSecret, List.of())).doesNotThrowAnyException();
            assertThat(cleanupService.cleanupExpiredAssets(100, Duration.ofMinutes(30)).cleanedCount())
                    .isZero();

            jdbcClient.sql("""
                            update storage_asset
                            set expires_at = timestampadd(SECOND, -1, current_timestamp)
                            where id in (:assetIds)
                            """)
                    .param("assetIds", List.of(secret.id(), evidence.id()))
                    .update();

            assertThatThrownBy(() -> privateStorageFileService.inspectPaymentSecrets(List.of(secret.id())))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));

            assertThatThrownBy(() -> appAfterSaleService.apply(
                    APP_USER,
                    ORDER_ID,
                    new AppAfterSaleApplyRequest(
                            "REFUND_ONLY", "timezone expiry", 1000L, "expired evidence",
                            List.of(evidence.id())
                    )
            )).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_FILE_UNAVAILABLE));

            assertThat(cleanupService.cleanupExpiredAssets(100, Duration.ofMinutes(30)).cleanedCount())
                    .isEqualTo(2);
            assertThat(jdbcClient.sql("""
                            select count(*)
                            from storage_asset
                            where id in (:assetIds)
                              and status = 'DELETED'
                            """)
                    .param("assetIds", List.of(secret.id(), evidence.id()))
                    .query(Integer.class)
                    .single()).isEqualTo(2);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void dataCleanupConfigurationCanBeUpdatedWithMySqlRowLocking() {
        var current = dataCleanupConfigService.current();
        List<DataCleanupTaskUpdateRequest> updates = current.tasks().stream()
                .map(task -> new DataCleanupTaskUpdateRequest(
                        task.taskCode(),
                        task.enabled(),
                        task.retentionDays(),
                        task.batchSize(),
                        task.cronExpression(),
                        task.batchIntervalSeconds(),
                        task.uploadPendingGraceMinutes(),
                        task.retainReviews()
                ))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int analyticsIndex = java.util.stream.IntStream.range(0, updates.size())
                .filter(index -> updates.get(index).taskCode()
                        == DataCleanupTaskCode.ANALYTICS_EVENT)
                .findFirst()
                .orElseThrow();
        DataCleanupTaskUpdateRequest analytics = updates.get(analyticsIndex);
        updates.set(analyticsIndex, new DataCleanupTaskUpdateRequest(
                analytics.taskCode(),
                analytics.enabled(),
                analytics.retentionDays(),
                analytics.batchSize() + 1,
                analytics.cronExpression(),
                analytics.batchIntervalSeconds(),
                analytics.uploadPendingGraceMinutes(),
                analytics.retainReviews()
        ));

        var updated = dataCleanupConfigService.update(
                new DataCleanupConfigUpdateRequest(current.revision(), updates),
                1L
        );

        assertThat(updated.revision()).isEqualTo(current.revision() + 1);
        assertThat(updated.tasks()).filteredOn(task ->
                        task.taskCode() == DataCleanupTaskCode.ANALYTICS_EVENT)
                .singleElement()
                .extracting(task -> task.batchSize())
                .isEqualTo(analytics.batchSize() + 1);
    }

    @Test
    void libraryAssetDeleteUsesMySqlCompatibleLegacyUrlReferenceCheck() {
        StorageAssetResponse asset = storageService.uploadLibrary(
                ADMIN,
                null,
                new MockMultipartFile("file", "mysql-delete.png", "image/png", TINY_PNG)
        );

        assertThatCode(() -> storageService.delete(asset.id())).doesNotThrowAnyException();
        assertThat(jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", asset.id())
                .query(String.class)
                .single()).isEqualTo("DELETED");
    }

    private void assertTtlMinutes(Long assetId, long minimum, long maximum) {
        LocalDateTime expiresAt = jdbcClient.sql("select expires_at from storage_asset where id = :assetId")
                .param("assetId", assetId)
                .query(LocalDateTime.class)
                .single();
        LocalDateTime databaseNow = jdbcClient.sql("select current_timestamp")
                .query(LocalDateTime.class)
                .single();

        assertThat(Duration.between(databaseNow, expiresAt).toMinutes())
                .isBetween(minimum, maximum);
        assertThat(jdbcClient.sql("""
                        select count(*)
                        from storage_asset
                        where id = :assetId
                          and expires_at > current_timestamp
                        """)
                .param("assetId", assetId)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }
}
