package org.muybaby.shopserver.aftersale.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.muybaby.shopserver.aftersale.dto.AfterSaleEvidenceFileResponse;
import org.muybaby.shopserver.aftersale.dto.AfterSaleResponse;
import org.muybaby.shopserver.auth.token.TokenKind;
import org.muybaby.shopserver.common.api.PageResult;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.security.AuthenticatedPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
@Import(AppAfterSaleServiceTest.CountingDataSourceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppAfterSaleServiceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(96_000L);

    @Autowired
    private AppAfterSaleService appAfterSaleService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SqlCounter sqlCounter;

    @BeforeEach
    void clearState() {
        jdbcClient.sql("delete from refund_order").update();
        jdbcClient.sql("delete from after_sale_evidence").update();
        jdbcClient.sql("delete from after_sale_request").update();
        jdbcClient.sql("delete from shop_order").update();
    }

    @Test
    void currentUserListPagesStablyFiltersStatusAndUsesOrderOwnershipForCountAndRecords() {
        long ownerId = insertUser("after-sale-page-owner");
        long otherId = insertUser("after-sale-page-other");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 12, 0);
        long ownerOrderA = insertOrder(ownerId, "COMPLETED", createdAt);
        long ownerOrderB = insertOrder(ownerId, "PAID", createdAt.plusMinutes(1));
        long otherOrder = insertOrder(otherId, "PAID", createdAt.plusMinutes(2));

        long olderOwned = insertAfterSale(ownerOrderA, otherId, "REQUESTED", createdAt);
        long newerOwned = insertAfterSale(ownerOrderB, otherId, "REJECTED", createdAt.plusMinutes(1));
        long otherRecord = insertAfterSale(otherOrder, ownerId, "REQUESTED", createdAt.plusMinutes(2));

        PageResult<AfterSaleResponse> first = appAfterSaleService.list(appPrincipal(ownerId), 1L, 1L, null);
        PageResult<AfterSaleResponse> second = appAfterSaleService.list(appPrincipal(ownerId), 2L, 1L, null);
        PageResult<AfterSaleResponse> requested = appAfterSaleService.list(
                appPrincipal(ownerId), 1L, 10L, " REQUESTED ");

        assertThat(first.total()).isEqualTo(2L);
        assertThat(first.current()).isEqualTo(1L);
        assertThat(first.size()).isEqualTo(1L);
        assertThat(first.records()).extracting(AfterSaleResponse::id).containsExactly(newerOwned);
        assertThat(second.records()).extracting(AfterSaleResponse::id).containsExactly(olderOwned);
        assertThat(requested.total()).isEqualTo(1L);
        assertThat(requested.records()).extracting(AfterSaleResponse::id).containsExactly(olderOwned);
        assertThat(first.records()).extracting(AfterSaleResponse::id).doesNotContain(otherRecord);

        PageResult<AfterSaleResponse> otherPage = appAfterSaleService.list(appPrincipal(otherId), 1L, 10L, null);
        assertThat(otherPage.records()).extracting(AfterSaleResponse::id).containsExactly(otherRecord);
    }

    @Test
    void detailUsesOrderOwnershipAndPageArgumentsAreValidatedAndClamped() {
        long ownerId = insertUser("after-sale-detail-owner");
        long otherId = insertUser("after-sale-detail-other");
        long orderId = insertOrder(ownerId, "COMPLETED", LocalDateTime.of(2026, 7, 10, 13, 0));
        long afterSaleId = insertAfterSale(orderId, otherId, "REQUESTED", LocalDateTime.of(2026, 7, 10, 13, 1));

        AfterSaleResponse detail = appAfterSaleService.detail(appPrincipal(ownerId), afterSaleId);
        PageResult<AfterSaleResponse> clamped = appAfterSaleService.list(appPrincipal(ownerId), 1L, 1_000L, null);

        assertThat(detail.id()).isEqualTo(afterSaleId);
        assertThat(clamped.size()).isEqualTo(100L);
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.detail(appPrincipal(otherId), afterSaleId));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.list(appPrincipal(ownerId), 0L, 10L, null));
        assertBusiness(ErrorCode.VALIDATION_FAILED,
                () -> appAfterSaleService.list(appPrincipal(ownerId), 1L, 0L, null));
    }

    @Test
    void listHydratesEvidenceAndLatestRefundWithConstantQueries() {
        long ownerId = insertUser("after-sale-batch-owner");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 14, 0);
        long orderId = insertOrder(ownerId, "COMPLETED", createdAt);
        long afterSaleId = insertAfterSale(orderId, ownerId, "REFUNDING", createdAt.plusMinutes(1));

        long lastEvidenceFileId = insertStorageAsset(ownerId, "last.png", 31L);
        long firstEvidenceFileId = insertStorageAsset(ownerId, "first.png", 32L);
        long secondEvidenceFileId = insertStorageAsset(ownerId, "second.png", 33L);
        insertEvidence(afterSaleId, firstEvidenceFileId, 1);
        insertEvidence(afterSaleId, secondEvidenceFileId, 1);
        insertEvidence(afterSaleId, lastEvidenceFileId, 2);

        insertRefundOrder(afterSaleId, orderId, "FAILED", createdAt.plusMinutes(3));
        long latestRefundId = insertRefundOrder(afterSaleId, orderId, "PROCESSING", createdAt.plusMinutes(2));
        jdbcClient.sql("""
                        update refund_order
                        set last_error_code = 'SensitiveInternalCode',
                            last_error_message = 'internal ticket and operator note'
                        where id = :refundOrderId
                        """)
                .param("refundOrderId", latestRefundId)
                .update();

        sqlCounter.reset();
        PageResult<AfterSaleResponse> single = appAfterSaleService.list(appPrincipal(ownerId), 1L, 100L, null);
        int singleRecordQueries = sqlCounter.count();

        assertThat(single.records()).hasSize(1);
        AfterSaleResponse hydrated = single.records().getFirst();
        assertThat(hydrated.evidenceFileIds())
                .containsExactly(firstEvidenceFileId, secondEvidenceFileId, lastEvidenceFileId);
        assertThat(hydrated.evidenceFiles())
                .extracting(AfterSaleEvidenceFileResponse::fileId)
                .containsExactly(firstEvidenceFileId, secondEvidenceFileId, lastEvidenceFileId);
        assertThat(hydrated.evidenceFiles())
                .extracting(AfterSaleEvidenceFileResponse::originalFilename)
                .containsExactly("first.png", "second.png", "last.png");
        assertThat(hydrated.refundOrder()).isNotNull();
        assertThat(hydrated.refundOrder().id()).isEqualTo(latestRefundId);
        assertThat(hydrated.refundOrder().status()).isEqualTo("PROCESSING");
        assertThat(hydrated.refundOrder().lastErrorCode()).isNull();
        assertThat(hydrated.refundOrder().lastErrorMessage()).isNull();

        for (int index = 1; index < 6; index++) {
            long additionalOrderId = insertOrder(ownerId, "COMPLETED", createdAt.plusHours(index));
            insertAfterSale(
                    additionalOrderId,
                    ownerId,
                    "REQUESTED",
                    createdAt.plusHours(index).plusMinutes(1)
            );
        }

        sqlCounter.reset();
        PageResult<AfterSaleResponse> many = appAfterSaleService.list(appPrincipal(ownerId), 1L, 100L, null);
        int manyRecordQueries = sqlCounter.count();

        assertThat(many.records()).hasSize(6);
        assertThat(singleRecordQueries).isEqualTo(4);
        assertThat(manyRecordQueries).isEqualTo(singleRecordQueries);
        assertThat(many.records())
                .filteredOn(record -> record.id().equals(afterSaleId))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.evidenceFileIds())
                            .containsExactly(firstEvidenceFileId, secondEvidenceFileId, lastEvidenceFileId);
                    assertThat(record.refundOrder()).isNotNull();
                    assertThat(record.refundOrder().id()).isEqualTo(latestRefundId);
                });
    }

    @Test
    void emptyPageSkipsBatchHydrationQueries() {
        long ownerId = insertUser("after-sale-empty-page-owner");

        sqlCounter.reset();
        PageResult<AfterSaleResponse> page = appAfterSaleService.list(appPrincipal(ownerId), 1L, 100L, null);

        assertThat(page.total()).isZero();
        assertThat(page.records()).isEmpty();
        assertThat(sqlCounter.count()).isEqualTo(2);
    }

    private long insertUser(String suffix) {
        long userId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into app_user (id, openid, unionid, status, last_login_at, created_at, updated_at)
                        values (:id, :openid, :unionid, 'ENABLED', :now, :now, :now)
                        """)
                .param("id", userId)
                .param("openid", suffix + "-" + userId)
                .param("unionid", suffix + "-union-" + userId)
                .param("now", now)
                .update();
        return userId;
    }

    private long insertOrder(long userId, String status, LocalDateTime createdAt) {
        long orderId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             product_original_amount_cent, product_amount_cent, coupon_discount_cent,
                             freight_cent, payable_amount_cent, paid_amount_cent, created_at, updated_at)
                        values
                            (:orderId, :orderNo, :userId, :status, 'CART', :idempotencyKey,
                             1000, 1000, 0, 0, 1000, 1000, :createdAt, :createdAt)
                        """)
                .param("orderId", orderId)
                .param("orderNo", "AS-ORDER-" + orderId)
                .param("userId", userId)
                .param("status", status)
                .param("idempotencyKey", "as-order-" + orderId)
                .param("createdAt", createdAt)
                .update();
        return orderId;
    }

    private long insertAfterSale(long orderId, long denormalizedUserId, String status, LocalDateTime createdAt) {
        long afterSaleId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, order_id, user_id, after_sale_type, status, reason,
                             description, requested_amount_cent, created_at, updated_at)
                        values
                            (:afterSaleId, :orderId, :userId, 'REFUND_ONLY', :status, 'page ownership',
                             '', 100, :createdAt, :createdAt)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("userId", denormalizedUserId)
                .param("status", status)
                .param("createdAt", createdAt)
                .update();
        return afterSaleId;
    }

    private long insertStorageAsset(long uploadedById, String filename, long sizeBytes) {
        long assetId = SEQUENCE.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, object_key, original_filename,
                             content_type, extension, size_bytes, status, uploaded_by_type, uploaded_by_id,
                             created_at, updated_at)
                        values
                            (:assetId, 'ATTACHMENT', 'IMAGE', 'PRIVATE', 'LOCAL', :objectKey, :filename,
                             'image/png', 'png', :sizeBytes, 'ACTIVE', 'APP', :uploadedById, :now, :now)
                        """)
                .param("assetId", assetId)
                .param("objectKey", "after-sale-query-count/" + assetId + ".png")
                .param("filename", filename)
                .param("sizeBytes", sizeBytes)
                .param("uploadedById", uploadedById)
                .param("now", now)
                .update();
        return assetId;
    }

    private void insertEvidence(long afterSaleId, long fileId, int sortOrder) {
        jdbcClient.sql("""
                        insert into after_sale_evidence (after_sale_id, file_id, sort_order)
                        values (:afterSaleId, :fileId, :sortOrder)
                        """)
                .param("afterSaleId", afterSaleId)
                .param("fileId", fileId)
                .param("sortOrder", sortOrder)
                .update();
    }

    private long insertRefundOrder(
            long afterSaleId,
            long orderId,
            String status,
            LocalDateTime requestedAt
    ) {
        long refundOrderId = SEQUENCE.incrementAndGet();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id, out_refund_no, refund_id,
                             refund_amount_cent, status, callback_status, last_error_code,
                             last_error_message, requested_at, created_at, updated_at)
                        values
                            (:refundOrderId, :afterSaleId, :orderId, :paymentOrderId, :outRefundNo, '',
                             100, :status, 'PENDING', '', '', :requestedAt, :requestedAt, :requestedAt)
                        """)
                .param("refundOrderId", refundOrderId)
                .param("afterSaleId", afterSaleId)
                .param("orderId", orderId)
                .param("paymentOrderId", SEQUENCE.incrementAndGet())
                .param("outRefundNo", "AS-QUERY-COUNT-REFUND-" + refundOrderId)
                .param("status", status)
                .param("requestedAt", requestedAt)
                .update();
        return refundOrderId;
    }

    private AuthenticatedPrincipal appPrincipal(long userId) {
        return new AuthenticatedPrincipal(TokenKind.APP, userId, "app-user-" + userId, List.of(), List.of());
    }

    private void assertBusiness(ErrorCode expected, Runnable action) {
        BusinessException exception = catchThrowableOfType(action::run, BusinessException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(expected);
    }

    static final class SqlCounter {
        private final AtomicInteger count = new AtomicInteger();

        int count() {
            return count.get();
        }

        void increment() {
            count.incrementAndGet();
        }

        void reset() {
            count.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingDataSourceConfiguration {

        @Bean
        SqlCounter sqlCounter() {
            return new SqlCounter();
        }

        @Bean
        @Primary
        CountingDataSource dataSource(DataSourceProperties properties, SqlCounter sqlCounter) {
            return new CountingDataSource(properties.initializeDataSourceBuilder().build(), sqlCounter);
        }
    }

    static final class CountingDataSource extends AbstractDataSource implements AutoCloseable {
        private final DataSource delegate;
        private final SqlCounter sqlCounter;

        private CountingDataSource(DataSource delegate, SqlCounter sqlCounter) {
            this.delegate = delegate;
            this.sqlCounter = sqlCounter;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return countingConnection(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return countingConnection(delegate.getConnection(username, password));
        }

        private Connection countingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        String methodName = method.getName();
                        if ("prepareStatement".equals(methodName)
                                || "prepareCall".equals(methodName)
                                || "createStatement".equals(methodName)) {
                            sqlCounter.increment();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                    }
            );
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
