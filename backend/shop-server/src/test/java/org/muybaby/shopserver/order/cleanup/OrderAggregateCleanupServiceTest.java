package org.muybaby.shopserver.order.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.muybaby.shopserver.order.OrderStatus;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.service.StorageAssetCleanupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderAggregateCleanupServiceTest {

    private static final long ORDER_ID = 9_870_001L;
    private static final long BATCH_SECOND_ORDER_ID = 9_870_040L;
    private static final long ORDER_ITEM_ID = 9_870_002L;
    private static final long SECOND_ORDER_ITEM_ID = 9_870_015L;
    private static final long AFTER_SALE_ID = 9_870_003L;
    private static final long ASSET_ID = 9_870_004L;
    private static final long ORDER_ITEM_ASSET_ID = 9_870_017L;
    private static final long PAYMENT_ID = 9_870_005L;
    private static final long REVIEW_ID = 9_870_006L;
    private static final long CONVERSATION_ID = 9_870_030L;
    private static final long WAYBILL_RECORD_ID = 9_870_041L;
    private static final long SHIPMENT_ID = 9_870_042L;
    private static final long REGISTRATION_ID = 9_870_043L;
    private static final long CATEGORY_ID = 9_870_020L;
    private static final long SPU_ID = 9_870_021L;
    private static final long SKU_ID = 9_870_022L;
    private static final long USER_COUPON_ID = 9_870_023L;
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2023, 1, 1, 0, 0);

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private StorageProvider storageProvider;

    @Autowired
    private OrderAggregateCleanupService cleanupService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageRuntimeConfigService storageConfigService;

    @Autowired
    private StorageAssetCleanupService storageAssetCleanupService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        cleanRows();
    }

    @AfterEach
    void tearDown() {
        deleteStoredObjects();
        cleanRows();
    }

    @Test
    void archivesWholeAggregatePurgesHotRowsAndRetainsDetachedReview() throws Exception {
        seedCompletedRefundedOrder();

        int processed = cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true);

        assertThat(processed).isOne();
        assertThat(count("shop_order", "id", ORDER_ID)).isZero();
        assertThat(count("order_item", "order_id", ORDER_ID)).isZero();
        assertThat(count("payment_order", "order_id", ORDER_ID)).isZero();
        assertThat(count("payment_attempt", "order_id", ORDER_ID)).isZero();
        assertThat(count("refund_order", "order_id", ORDER_ID)).isZero();
        assertThat(count("after_sale_request", "order_id", ORDER_ID)).isZero();
        assertThat(count("after_sale_evidence", "after_sale_id", AFTER_SALE_ID)).isZero();
        assertThat(count("order_status_log", "order_id", ORDER_ID)).isZero();
        assertThat(count("order_electronic_waybill", "order_id", ORDER_ID)).isZero();
        assertThat(count("order_shipment", "order_id", ORDER_ID)).isZero();
        assertThat(count("shipment_waybill_registration", "id", REGISTRATION_ID)).isZero();
        assertThat(count("stock_lock", "order_id", ORDER_ID)).isZero();
        assertThat(count("stock_log", "order_id", ORDER_ID)).isZero();
        assertThat(count("customer_service_conversation_order", "order_id", ORDER_ID)).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from customer_service_consultation_resource
                        where resource_type = 'ORDER' and resource_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("""
                        select count(*) from customer_service_message
                        where message_type = 'ORDER_CARD' and resource_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isZero();
        CustomerServiceContext customerServiceContext = jdbcClient.sql("""
                        select context_type, context_id, last_message_at
                        from customer_service_conversation where id = :conversationId
                        """)
                .param("conversationId", CONVERSATION_ID)
                .query((rs, rowNum) -> new CustomerServiceContext(
                        rs.getString("context_type"),
                        rs.getObject("context_id", Long.class),
                        rs.getObject("last_message_at", LocalDateTime.class)
                ))
                .single();
        assertThat(customerServiceContext).isEqualTo(new CustomerServiceContext(
                "GENERAL", null, oldAt().plusDays(1)));
        assertThat(jdbcClient.sql("select count(*) from payment_callback_log where out_trade_no = 'TRADE-9870001'")
                .query(Integer.class).single()).isZero();

        ReviewSnapshot review = jdbcClient.sql("""
                        select order_item_id, source_order_item_id, product_title_snapshot,
                               spec_text_snapshot, verified_purchase
                        from product_review where id = :reviewId
                        """)
                .param("reviewId", REVIEW_ID)
                .query((rs, rowNum) -> new ReviewSnapshot(
                        rs.getObject("order_item_id", Long.class),
                        rs.getLong("source_order_item_id"),
                        rs.getString("product_title_snapshot"),
                        rs.getString("spec_text_snapshot"),
                        rs.getBoolean("verified_purchase")
                ))
                .single();
        assertThat(review).isEqualTo(new ReviewSnapshot(
                null, ORDER_ITEM_ID, "Archive Item", "Red / XL", true));

        assertThat(jdbcClient.sql("""
                        select count(*) from storage_asset_usage where asset_id = :assetId
                        """)
                .param("assetId", ASSET_ID)
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("select expires_at from storage_asset where id = :assetId")
                .param("assetId", ASSET_ID)
                .query(LocalDateTime.class).single()).isNotNull();
        assertThat(jdbcClient.sql("select expires_at from storage_asset where id = :assetId")
                .param("assetId", ORDER_ITEM_ASSET_ID)
                .query(LocalDateTime.class).single()).isNotNull();

        ArchiveManifest manifest = jdbcClient.sql("""
                        select id, object_key, content_type, sha256, size_bytes,
                               item_count, payment_count, refund_count, after_sale_count
                        from order_archive_manifest where source_order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query((rs, rowNum) -> new ArchiveManifest(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("content_type"),
                        rs.getString("sha256"),
                        rs.getLong("size_bytes"),
                        rs.getInt("item_count"),
                        rs.getInt("payment_count"),
                        rs.getInt("refund_count"),
                        rs.getInt("after_sale_count")
                ))
                .single();
        assertThat(manifest.contentType()).isEqualTo("application/zip");
        assertThat(manifest.sha256()).hasSize(64);
        assertThat(manifest.sizeBytes()).isPositive();
        assertThat(List.of(
                manifest.itemCount(), manifest.paymentCount(),
                manifest.refundCount(), manifest.afterSaleCount()))
                .containsExactly(2, 1, 1, 1);
        assertThat(count("purged_order_identity", "archive_manifest_id", manifest.id())).isOne();
        assertThat(count("purged_payment_identity", "archive_manifest_id", manifest.id())).isOne();
        assertThat(count("purged_refund_identity", "archive_manifest_id", manifest.id())).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from purged_payment_identity
                        where archive_manifest_id = :manifestId
                          and final_status = 'PAID'
                          and amount_cent = 1000
                          and currency = 'CNY'
                        """)
                .param("manifestId", manifest.id())
                .query(Integer.class)
                .single()).isOne();
        assertThat(jdbcClient.sql("""
                        select count(*) from purged_refund_identity
                        where archive_manifest_id = :manifestId
                          and final_status = 'SUCCESS'
                          and final_callback_status = 'SUCCESS'
                          and refund_amount_cent = 1000
                        """)
                .param("manifestId", manifest.id())
                .query(Integer.class)
                .single()).isOne();

        StoredObject archive = storageProvider.open(manifest.objectKey());
        List<String> entries = zipEntryNames(archive.inputStream());
        assertThat(entries).contains(
                "archive.json", "assets/" + ASSET_ID, "assets/" + ORDER_ITEM_ASSET_ID);
        String archiveJson = archiveJson(storageProvider.open(manifest.objectKey()).inputStream());
        assertThat(archiveJson)
                .contains("\"electronic_waybills\"")
                .contains("\"waybill_registrations\"")
                .contains("\"shipments\"");

        assertThat(storageAssetCleanupService.cleanupAsset(ASSET_ID)).isTrue();
        assertThat(storageAssetCleanupService.cleanupAsset(ORDER_ITEM_ASSET_ID)).isTrue();
        assertThat(jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", ASSET_ID)
                .query(String.class).single()).isEqualTo("DELETED");
        assertThat(jdbcClient.sql("select status from storage_asset where id = :assetId")
                .param("assetId", ORDER_ITEM_ASSET_ID)
                .query(String.class).single()).isEqualTo("DELETED");
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void purgesEveryOrderStatusWhenNoFinancialWorkIsActive(OrderStatus status) {
        seedOrderOnly(ORDER_ID, status.name());

        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isOne();
        assertThat(count("shop_order", "id", ORDER_ID)).isZero();
    }

    @Test
    void deletesLinkedReviewWhenReviewRetentionIsDisabled() {
        seedCompletedRefundedOrder();

        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, false, () -> true)).isOne();
        assertThat(count("product_review", "id", REVIEW_ID)).isZero();
    }

    @Test
    void safelySkipsAnOldOrderWhosePaymentIsStillInProgress() {
        seedOrderOnly(ORDER_ID, "PAYING");
        insertPayment("PAYING");

        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();
        assertThat(count("shop_order", "id", ORDER_ID)).isOne();
        assertThat(jdbcClient.sql("select count(*) from order_archive_manifest where source_order_id = :orderId")
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void safelySkipsRecentOrActiveWaybillWork() {
        seedCompletedRefundedOrder();

        jdbcClient.sql("""
                        update order_electronic_waybill
                        set updated_at = current_timestamp
                        where id = :waybillRecordId
                        """)
                .param("waybillRecordId", WAYBILL_RECORD_ID)
                .update();
        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();

        jdbcClient.sql("""
                        update order_electronic_waybill
                        set updated_at = :oldAt
                        where id = :waybillRecordId
                        """)
                .param("oldAt", oldAt())
                .param("waybillRecordId", WAYBILL_RECORD_ID)
                .update();
        jdbcClient.sql("""
                        update shipment_waybill_registration
                        set updated_at = current_timestamp
                        where id = :registrationId
                        """)
                .param("registrationId", REGISTRATION_ID)
                .update();
        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();

        jdbcClient.sql("""
                        update shipment_waybill_registration
                        set updated_at = :oldAt
                        where id = :registrationId
                        """)
                .param("oldAt", oldAt())
                .param("registrationId", REGISTRATION_ID)
                .update();
        jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = 'CREATED', updated_at = :oldAt
                        where id = :waybillRecordId
                        """)
                .param("oldAt", oldAt())
                .param("waybillRecordId", WAYBILL_RECORD_ID)
                .update();
        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();
        assertThat(count("shop_order", "id", ORDER_ID)).isOne();
    }

    @Test
    void retainsChangedAggregateAndUsesAnAttemptUniqueArchiveKeyOnRetry() {
        seedOrderOnly(ORDER_ID, "CLOSED");
        AtomicBoolean changed = new AtomicBoolean();
        List<String> uploadedArchiveKeys = new ArrayList<>();
        StorageProvider mutatingProvider = new StorageProvider() {
            @Override
            public StoredObject put(
                    String objectKey,
                    String contentType,
                    InputStream inputStream,
                    long sizeBytes
            ) {
                return storageProvider.put(objectKey, contentType, inputStream, sizeBytes);
            }

            @Override
            public StoredObject put(
                    StorageObjectLocation location,
                    String contentType,
                    InputStream inputStream,
                    long sizeBytes
            ) {
                StoredObject stored = storageProvider.put(location, contentType, inputStream, sizeBytes);
                uploadedArchiveKeys.add(location.objectKey());
                if (changed.compareAndSet(false, true)) {
                    insertOrderItem(ORDER_ITEM_ID, ORDER_ID, "Late aggregate item");
                }
                return stored;
            }

            @Override
            public StoredObject open(String objectKey) {
                return storageProvider.open(objectKey);
            }

            @Override
            public void delete(String objectKey) {
                storageProvider.delete(objectKey);
            }
        };
        OrderAggregateCleanupService isolated = new OrderAggregateCleanupService(
                jdbcClient, mutatingProvider, storageConfigService, objectMapper, transactionTemplate);

        assertThat(isolated.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();
        assertThat(count("shop_order", "id", ORDER_ID)).isOne();
        assertThat(count("order_item", "order_id", ORDER_ID)).isOne();
        assertThat(jdbcClient.sql("select count(*) from order_archive_manifest where source_order_id = :orderId")
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isZero();

        assertThat(isolated.cleanupBatch(CUTOFF, 20, true, () -> true)).isOne();
        assertThat(uploadedArchiveKeys).hasSize(2).doesNotHaveDuplicates();
        assertThat(jdbcClient.sql("""
                        select object_key from order_archive_manifest where source_order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query(String.class).single()).isEqualTo(uploadedArchiveKeys.get(1));
    }

    @Test
    void archiveProviderFailureNeverDeletesTheOrder() {
        seedOrderOnly(ORDER_ID, "CLOSED");
        StorageProvider failingProvider = mock(StorageProvider.class);
        doThrow(new IllegalStateException("COS unavailable"))
                .when(failingProvider)
                .put(any(StorageObjectLocation.class), any(String.class),
                        any(InputStream.class), anyLong());
        OrderAggregateCleanupService isolated = new OrderAggregateCleanupService(
                jdbcClient, failingProvider, storageConfigService, objectMapper, transactionTemplate);

        assertThatThrownBy(() -> isolated.cleanupBatch(CUTOFF, 20, true, () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed for 1 candidate");
        assertThat(count("shop_order", "id", ORDER_ID)).isOne();
        assertThat(jdbcClient.sql("select count(*) from order_archive_manifest where source_order_id = :orderId")
                .param("orderId", ORDER_ID)
                .query(Integer.class).single()).isZero();
        CleanupFailure failure = jdbcClient.sql("""
                        select consecutive_failures, next_retry_at
                        from order_cleanup_failure where source_order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query((rs, rowNum) -> new CleanupFailure(
                        rs.getInt("consecutive_failures"),
                        rs.getObject("next_retry_at", LocalDateTime.class)
                ))
                .single();
        assertThat(failure.consecutiveFailures()).isOne();
        assertThat(failure.nextRetryAt()).isAfter(
                jdbcClient.sql("select current_timestamp").query(LocalDateTime.class).single());

        assertThat(isolated.cleanupBatch(CUTOFF, 20, true, () -> true)).isZero();
    }

    @Test
    void batchSizeCapsArchiveAttemptsEvenWhenEveryAttemptFails() {
        seedOrderOnly(ORDER_ID, "CLOSED");
        seedOrderOnly(BATCH_SECOND_ORDER_ID, "CLOSED");
        StorageProvider failingProvider = mock(StorageProvider.class);
        doThrow(new IllegalStateException("COS unavailable"))
                .when(failingProvider)
                .put(any(StorageObjectLocation.class), any(String.class),
                        any(InputStream.class), anyLong());
        OrderAggregateCleanupService isolated = new OrderAggregateCleanupService(
                jdbcClient, failingProvider, storageConfigService, objectMapper, transactionTemplate);

        assertThatThrownBy(() -> isolated.cleanupBatch(CUTOFF, 1, true, () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed for 1 candidate");
        assertThat(jdbcClient.sql("""
                        select count(*) from order_cleanup_failure
                        where source_order_id in (:firstOrderId, :secondOrderId)
                        """)
                .param("firstOrderId", ORDER_ID)
                .param("secondOrderId", BATCH_SECOND_ORDER_ID)
                .query(Integer.class)
                .single()).isOne();
        assertThat(count("shop_order", "id", BATCH_SECOND_ORDER_ID)).isOne();
    }

    @Test
    void releasesResidualStockAndCouponReservationsBeforePurgingAnyOrderStatus() {
        seedProductSku();
        seedOrderOnly(ORDER_ID, "CREATED");
        jdbcClient.sql("update shop_order set user_coupon_id = :couponId where id = :orderId")
                .param("couponId", USER_COUPON_ID)
                .param("orderId", ORDER_ID)
                .update();
        jdbcClient.sql("""
                        insert into stock_lock
                            (id, order_id, order_item_id, sku_id, quantity, status,
                             locked_at, created_at, updated_at)
                        values
                            (9870024, :orderId, 9870025, :skuId, 2, 'LOCKED',
                             :oldAt, :oldAt, :oldAt)
                        """)
                .param("orderId", ORDER_ID)
                .param("skuId", SKU_ID)
                .param("oldAt", oldAt())
                .update();
        seedLockedCoupon();

        assertThat(cleanupService.cleanupBatch(CUTOFF, 20, true, () -> true)).isOne();

        assertThat(jdbcClient.sql("select stock_available from product_sku where id = :skuId")
                .param("skuId", SKU_ID)
                .query(Integer.class).single()).isEqualTo(5);
        CouponReservation coupon = jdbcClient.sql("""
                        select status, locked_order_id, locked_at, released_at
                        from user_coupon where id = :couponId
                        """)
                .param("couponId", USER_COUPON_ID)
                .query((rs, rowNum) -> new CouponReservation(
                        rs.getString("status"),
                        rs.getObject("locked_order_id", Long.class),
                        rs.getObject("locked_at", LocalDateTime.class),
                        rs.getObject("released_at", LocalDateTime.class)
                ))
                .single();
        assertThat(coupon.status()).isEqualTo("EXPIRED");
        assertThat(coupon.lockedOrderId()).isNull();
        assertThat(coupon.lockedAt()).isNull();
        assertThat(coupon.releasedAt()).isNotNull();
    }

    private void seedCompletedRefundedOrder() {
        seedOrderOnly(ORDER_ID, "REFUNDED");
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, product_subtitle,
                             main_image, sku_image, display_image, sku_code, spec_text,
                             original_price_cent, unit_price_cent, quantity,
                             line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:id, :orderId, 7001, 8001, 'Archive Item', '', '', '', '',
                             'ARCHIVE-SKU', 'Red / XL', 1000, 1000, 1, 1000, 1000, :oldAt)
                        """)
                .param("id", ORDER_ITEM_ID)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, product_subtitle,
                             main_image, sku_image, display_image, sku_code, spec_text,
                             original_price_cent, unit_price_cent, quantity,
                             line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:id, :orderId, 7002, 8002, 'Second Archive Item', '', '', '', '',
                             'ARCHIVE-SKU-2', 'Blue / M', 500, 500, 2, 1000, 1000, :oldAt)
                        """)
                .param("id", SECOND_ORDER_ITEM_ID)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into product_review
                            (id, user_id, spu_id, order_item_id, source_order_item_id,
                             product_title_snapshot, spec_text_snapshot, verified_purchase,
                             rating, content, anonymous, status, created_at, updated_at)
                        values
                            (:id, 6001, 8001, :itemId, :itemId, '', '', true,
                             5, 'retained review', false, 'PUBLISHED', :oldAt, :oldAt)
                        """)
                .param("id", REVIEW_ID)
                .param("itemId", ORDER_ITEM_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into stock_lock
                            (id, order_id, order_item_id, sku_id, quantity, status,
                             locked_at, released_at, created_at, updated_at)
                        values
                            (9870007, :orderId, :itemId, 7001, 1, 'RELEASED',
                             :oldAt, :oldAt, :oldAt, :oldAt)
                        """)
                .param("orderId", ORDER_ID)
                .param("itemId", ORDER_ITEM_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into stock_log
                            (id, order_id, sku_id, change_type, quantity_before, quantity_delta,
                             quantity_after, reason, operator_type, operator_id, created_at)
                        values
                            (9870008, :orderId, 7001, 'ORDER_LOCK', 10, -1, 9,
                             'Order submit 9870001', 'APP', 6001, :oldAt)
                        """)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        insertPayment("PAID");
        insertWaybillAuditRows();
        jdbcClient.sql("""
                        insert into payment_attempt
                            (id, order_id, payment_order_id, out_trade_no, status, amount_cent,
                             started_at, paid_at, created_at, updated_at)
                        values
                            (9870009, :orderId, :paymentId, 'TRADE-9870001', 'PAID', 1000,
                             :oldAt, :oldAt, :oldAt, :oldAt)
                        """)
                .param("orderId", ORDER_ID)
                .param("paymentId", PAYMENT_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into after_sale_request
                            (id, after_sale_no, order_id, user_id, after_sale_type, status,
                             reason, description, requested_amount_cent, approved_amount_cent,
                             reviewed_at, created_at, updated_at)
                        values
                            (:id, 'AS9870003', :orderId, 6001, 'REFUND', 'COMPLETED',
                             'test', '', 1000, 1000, :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", AFTER_SALE_ID)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        insertAfterSaleAsset();
        insertOrderItemSnapshotAsset();
        jdbcClient.sql("""
                        insert into after_sale_evidence (id, after_sale_id, file_id, sort_order, created_at)
                        values (9870010, :afterSaleId, :assetId, 1, :oldAt)
                        """)
                .param("afterSaleId", AFTER_SALE_ID)
                .param("assetId", ASSET_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (id, asset_id, usage_type, owner_type, owner_id, owner_label,
                             snapshot_url, sort_order, protected, status, created_at, updated_at)
                        values
                            (9870011, :assetId, 'AFTER_SALE_EVIDENCE', 'AFTER_SALE',
                             :afterSaleId, 'evidence', '', 1, true, 'ACTIVE', :oldAt, :oldAt)
                        """)
                .param("assetId", ASSET_ID)
                .param("afterSaleId", AFTER_SALE_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into storage_asset_usage
                            (id, asset_id, usage_type, owner_type, owner_id, owner_label,
                             snapshot_url, sort_order, protected, status, created_at, updated_at)
                        values
                            (9870016, :assetId, 'ORDER_ITEM_SNAPSHOT', 'ORDER_ITEM',
                             :itemId, 'snapshot', '', 1, true, 'ACTIVE', :oldAt, :oldAt)
                        """)
                .param("assetId", ORDER_ITEM_ASSET_ID)
                .param("itemId", SECOND_ORDER_ITEM_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into refund_order
                            (id, after_sale_id, order_id, payment_order_id, out_refund_no,
                             refund_id, refund_amount_cent, status, callback_status,
                             notification_route_token, requested_at, success_at, created_at, updated_at)
                        values
                            (9870012, :afterSaleId, :orderId, :paymentId, 'REFUND-9870001',
                             'WX-REFUND-9870001', 1000, 'SUCCESS', 'SUCCESS',
                             'rrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrrr',
                             :oldAt, :oldAt, :oldAt, :oldAt)
                        """)
                .param("afterSaleId", AFTER_SALE_ID)
                .param("orderId", ORDER_ID)
                .param("paymentId", PAYMENT_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into payment_callback_log
                            (id, callback_type, notify_id, out_trade_no, out_refund_no,
                             transaction_id, refund_id, event_type, resource_digest,
                             raw_body_sha256, status, created_at, updated_at)
                        values
                            (9870013, 'REFUND', 'notify-old', 'TRADE-9870001', 'REFUND-9870001',
                             'WX-TRADE-9870001', 'WX-REFUND-9870001', 'REFUND.SUCCESS', '',
                             'body-sha', 'SUCCESS', :oldAt, :oldAt)
                        """)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into order_status_log
                            (id, order_id, from_status, to_status, event_type, operator_type,
                             description, created_at)
                        values
                            (9870014, :orderId, 'REFUNDING', 'REFUNDED', 'REFUND_SUCCEEDED',
                             'SYSTEM', 'old log', :oldAt)
                        """)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        seedCustomerServiceReferences();
    }

    private void seedCustomerServiceReferences() {
        jdbcClient.sql("""
                        insert into customer_service_conversation
                            (id, app_user_id, status, last_message_at,
                             app_unread_count, admin_unread_count, consultation_no,
                             context_type, context_id, activated_at, created_at, updated_at)
                        values
                            (:id, 9870031, 'CLOSED', :lastMessageAt,
                             0, 1, 1, 'ORDER', :orderId, :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", CONVERSATION_ID)
                .param("orderId", ORDER_ID)
                .param("lastMessageAt", oldAt().plusDays(2))
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into customer_service_message
                            (id, conversation_id, sender_type, sender_id, message_type,
                             content, client_message_id, consultation_no, resource_id, created_at)
                        values
                            (9870032, :conversationId, 'ADMIN', 1, 'TEXT',
                             'remaining message', 'cleanup-text', 1, null, :createdAt),
                            (9870033, :conversationId, 'APP', 9870031, 'ORDER_CARD',
                             'old order card', 'cleanup-order-card', 1, :orderId, :cardCreatedAt)
                        """)
                .param("conversationId", CONVERSATION_ID)
                .param("orderId", ORDER_ID)
                .param("createdAt", oldAt().plusDays(1))
                .param("cardCreatedAt", oldAt().plusDays(2))
                .update();
        jdbcClient.sql("""
                        insert into customer_service_conversation_order
                            (id, conversation_id, order_id, linked_by_type, linked_by_id, created_at)
                        values (9870034, :conversationId, :orderId, 'APP', 9870031, :oldAt)
                        """)
                .param("conversationId", CONVERSATION_ID)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into customer_service_consultation_resource
                            (id, conversation_id, consultation_no, resource_type, resource_id,
                             added_by_type, added_by_id, created_at)
                        values
                            (9870035, :conversationId, 1, 'ORDER', :orderId,
                             'APP', 9870031, :oldAt)
                        """)
                .param("conversationId", CONVERSATION_ID)
                .param("orderId", ORDER_ID)
                .param("oldAt", oldAt())
                .update();
    }

    private void seedOrderOnly(long orderId, String status) {
        jdbcClient.sql("""
                        insert into shop_order
                            (id, order_no, user_id, status, source, idempotency_key,
                             receiver_name, receiver_phone, receiver_address, created_at, updated_at)
                        values
                            (:id, :orderNo, 6001, :status, 'CART', :idempotencyKey,
                             'Archive User', '13800000000', 'Archive Address', :oldAt, :oldAt)
                        """)
                .param("id", orderId)
                .param("orderNo", "ORDER-" + orderId)
                .param("status", status)
                .param("idempotencyKey", "idempotency-" + orderId)
                .param("oldAt", oldAt())
                .update();
    }

    private void insertOrderItem(long itemId, long orderId, String title) {
        jdbcClient.sql("""
                        insert into order_item
                            (id, order_id, sku_id, spu_id, product_title, product_subtitle,
                             main_image, sku_image, display_image, sku_code, spec_text,
                             original_price_cent, unit_price_cent, quantity,
                             line_original_amount_cent, line_amount_cent, created_at)
                        values
                            (:id, :orderId, 7001, 8001, :title, '', '', '', '',
                             'ARCHIVE-SKU', '', 1000, 1000, 1, 1000, 1000, :oldAt)
                        """)
                .param("id", itemId)
                .param("orderId", orderId)
                .param("title", title)
                .param("oldAt", oldAt())
                .update();
    }

    private void insertPayment(String status) {
        jdbcClient.sql("""
                        insert into payment_order
                            (id, order_id, out_trade_no, transaction_id, status, amount_cent,
                             expires_at, notification_route_token, created_at, updated_at)
                        values
                            (:id, :orderId, 'TRADE-9870001', 'WX-TRADE-9870001', :status, 1000,
                             :oldAt, 'pppppppppppppppppppppppppppppppppppppppppppppppp',
                             :oldAt, :oldAt)
                        """)
                .param("id", PAYMENT_ID)
                .param("orderId", ORDER_ID)
                .param("status", status)
                .param("oldAt", oldAt())
                .update();
    }

    private void insertWaybillAuditRows() {
        jdbcClient.sql("""
                        insert into order_electronic_waybill(
                            id, order_id, attempt_no, idempotency_key, request_digest,
                            provider_order_id, mode, delivery_id, delivery_name, biz_id,
                            service_type, service_name, status, pending_operation, waybill_id,
                            parcel_count, weight_kg, length_cm, width_cm, height_cm,
                            sender_name, sender_mobile, sender_company, sender_province,
                            sender_city, sender_district, sender_detail_address,
                            receiver_name, receiver_phone, receiver_province, receiver_city,
                            receiver_district, receiver_detail_address, payment_order_id,
                            payer_openid, created_by, confirmed_by, created_at, updated_at,
                            confirmed_at)
                        values (
                            :id, :orderId, 1, 'cleanup-waybill', :requestDigest,
                            :providerOrderId, 'SANDBOX', 'TEST', '微信官方测试运力',
                            'test_biz_id', 1, 'test_service_name', 'CONFIRMED', 'NONE',
                            :waybillId, 1, 1.000, 20.00, 15.00, 10.00,
                            'Cleanup Sender', '13800138000', 'Shop', '广东省', '深圳市',
                            '南山区', '测试路1号', 'Archive User', '13800000000', '广东省',
                            '深圳市', '南山区', '测试路2号', :paymentId,
                            'cleanup-openid', 1, 1, :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", WAYBILL_RECORD_ID)
                .param("orderId", ORDER_ID)
                .param("requestDigest", "c".repeat(64))
                .param("providerOrderId", "SHOPWB-" + ORDER_ID + "-1")
                .param("waybillId", "TEST-CLEANUP-WAYBILL")
                .param("paymentId", PAYMENT_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into order_shipment(
                            id, order_id, logistics_type, delivery_mode, item_desc,
                            express_company_code, express_company_name, tracking_no,
                            shipment_note, shipment_source, electronic_waybill_id,
                            status, wechat_provider_mode, wechat_upload_status,
                            shipped_at, created_at, updated_at)
                        values (
                            :id, :orderId, 1, 1, 'Archive Item x1', 'TEST',
                            '微信官方测试运力', 'TEST-CLEANUP-WAYBILL', '',
                            'WECHAT_WAYBILL', :waybillRecordId, 'SHIPPED', 'DISABLED',
                            'SKIPPED', :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", SHIPMENT_ID)
                .param("orderId", ORDER_ID)
                .param("waybillRecordId", WAYBILL_RECORD_ID)
                .param("oldAt", oldAt())
                .update();
        jdbcClient.sql("""
                        insert into shipment_waybill_registration(
                            id, shipment_id, registration_kind, status, waybill_token,
                            attempt_count, last_attempt_at, registered_at, created_at, updated_at)
                        values (
                            :id, :shipmentId, 'TRACE', 'REGISTERED', 'archive-token',
                            1, :oldAt, :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", REGISTRATION_ID)
                .param("shipmentId", SHIPMENT_ID)
                .param("oldAt", oldAt())
                .update();
    }

    private void insertAfterSaleAsset() {
        byte[] bytes = "after-sale-evidence".getBytes();
        String objectKey = sourceAssetObjectKey();
        storageProvider.put(objectKey, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container,
                             storage_region, object_key, original_filename, content_type,
                             extension, size_bytes, sha256, status, uploaded_by_type,
                             uploaded_by_id, upload_context_type, upload_context_id,
                             created_at, updated_at)
                        values
                            (:id, 'ATTACHMENT', 'IMAGE', 'PRIVATE', 'TENCENT_COS', 'test-bucket',
                             'ap-test', :objectKey, 'evidence.jpg', 'image/jpeg',
                             'jpg', :sizeBytes, 'asset-sha', 'ACTIVE', 'APP', 6001,
                             'AFTER_SALE', :afterSaleId, :oldAt, :oldAt)
                        """)
                .param("id", ASSET_ID)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("afterSaleId", AFTER_SALE_ID)
                .param("oldAt", oldAt())
                .update();
    }

    private void insertOrderItemSnapshotAsset() {
        byte[] bytes = "order-item-snapshot".getBytes();
        String objectKey = orderItemAssetObjectKey();
        storageProvider.put(objectKey, "image/jpeg", new ByteArrayInputStream(bytes), bytes.length);
        jdbcClient.sql("""
                        insert into storage_asset
                            (id, scope, media_kind, visibility, provider, storage_container,
                             storage_region, object_key, original_filename, content_type,
                             extension, size_bytes, sha256, status, uploaded_by_type,
                             uploaded_by_id, upload_context_type, upload_context_id,
                             created_at, updated_at)
                        values
                            (:id, 'ATTACHMENT', 'IMAGE', 'PRIVATE', 'TENCENT_COS', 'test-bucket',
                             'ap-test', :objectKey, 'order-item.jpg', 'image/jpeg',
                             'jpg', :sizeBytes, 'order-item-asset-sha', 'ACTIVE', 'APP', 6001,
                             'ORDER_ITEM', :orderItemId, :oldAt, :oldAt)
                        """)
                .param("id", ORDER_ITEM_ASSET_ID)
                .param("objectKey", objectKey)
                .param("sizeBytes", bytes.length)
                .param("orderItemId", SECOND_ORDER_ITEM_ID)
                .param("oldAt", oldAt())
                .update();
    }

    private void seedProductSku() {
        jdbcClient.sql("""
                        insert into product_category (id, parent_id, name, status)
                        values (:id, 0, 'Order cleanup category', 'ENABLED')
                        """)
                .param("id", CATEGORY_ID)
                .update();
        jdbcClient.sql("""
                        insert into product_spu
                            (id, category_id, title, selling_points, detail_html, status)
                        values (:id, :categoryId, 'Order cleanup SPU', '', '', 'ENABLED')
                        """)
                .param("id", SPU_ID)
                .param("categoryId", CATEGORY_ID)
                .update();
        jdbcClient.sql("""
                        insert into product_sku
                            (id, spu_id, sku_code, spec_json, spec_text, price_cent,
                             stock_available, status)
                        values (:id, :spuId, 'ORDER-CLEANUP-SKU', '{}', 'cleanup', 1000, 3, 'ENABLED')
                        """)
                .param("id", SKU_ID)
                .param("spuId", SPU_ID)
                .update();
    }

    private void seedLockedCoupon() {
        jdbcClient.sql("""
                        insert into user_coupon
                            (id, user_id, template_id, template_name, coupon_type, discount_type,
                             threshold_cent, discount_cent, scope_type, scope_value,
                             valid_start_at, valid_end_at, status, claimed_at,
                             locked_order_id, locked_at, created_at, updated_at)
                        values
                            (:id, 6001, 7001, 'Old coupon', 'DISCOUNT', 'AMOUNT_OFF',
                             0, 100, 'ALL', '', :validStart, :validEnd, 'LOCKED', :oldAt,
                             :orderId, :oldAt, :oldAt, :oldAt)
                        """)
                .param("id", USER_COUPON_ID)
                .param("validStart", LocalDateTime.of(2019, 1, 1, 0, 0))
                .param("validEnd", LocalDateTime.of(2020, 12, 31, 0, 0))
                .param("oldAt", oldAt())
                .param("orderId", ORDER_ID)
                .update();
    }

    private List<String> zipEntryNames(InputStream input) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                zip.transferTo(java.io.OutputStream.nullOutputStream());
                zip.closeEntry();
            }
        }
        return names;
    }

    private String archiveJson(InputStream input) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("archive.json".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        }
        throw new IllegalStateException("archive.json is missing");
    }

    private int count(String table, String column, long value) {
        return jdbcClient.sql("select count(*) from " + table + " where " + column + " = :value")
                .param("value", value)
                .query(Integer.class)
                .single();
    }

    private LocalDateTime oldAt() {
        return LocalDateTime.of(2020, 1, 1, 0, 0);
    }

    private String sourceAssetObjectKey() {
        return "private/order-cleanup-test/evidence-" + ORDER_ID + ".jpg";
    }

    private String orderItemAssetObjectKey() {
        return "private/order-cleanup-test/order-item-" + ORDER_ID + ".jpg";
    }

    private void deleteStoredObjects() {
        List<String> keys = jdbcClient.sql("""
                        select object_key from order_archive_manifest where source_order_id = :orderId
                        """)
                .param("orderId", ORDER_ID)
                .query(String.class)
                .list();
        keys.add(sourceAssetObjectKey());
        keys.add(orderItemAssetObjectKey());
        for (String key : keys) {
            try {
                storageProvider.delete(key);
            } catch (RuntimeException ignored) {
                // The negative-path provider or a previous assertion may have left no object.
            }
        }
    }

    private void cleanRows() {
        jdbcClient.sql("delete from purged_refund_identity where archive_manifest_id in (select id from order_archive_manifest where source_order_id = :orderId)")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from purged_payment_identity where archive_manifest_id in (select id from order_archive_manifest where source_order_id = :orderId)")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from purged_order_identity where archive_manifest_id in (select id from order_archive_manifest where source_order_id = :orderId)")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from order_archive_manifest where source_order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from customer_service_message where conversation_id = :conversationId")
                .param("conversationId", CONVERSATION_ID).update();
        jdbcClient.sql("delete from customer_service_consultation_resource where conversation_id = :conversationId")
                .param("conversationId", CONVERSATION_ID).update();
        jdbcClient.sql("delete from customer_service_conversation_order where conversation_id = :conversationId")
                .param("conversationId", CONVERSATION_ID).update();
        jdbcClient.sql("delete from customer_service_conversation where id = :conversationId")
                .param("conversationId", CONVERSATION_ID).update();
        jdbcClient.sql("delete from storage_asset_usage where asset_id = :assetId")
                .param("assetId", ASSET_ID).update();
        jdbcClient.sql("delete from storage_asset_usage where asset_id = :assetId")
                .param("assetId", ORDER_ITEM_ASSET_ID).update();
        jdbcClient.sql("delete from after_sale_evidence where after_sale_id = :afterSaleId")
                .param("afterSaleId", AFTER_SALE_ID).update();
        jdbcClient.sql("delete from payment_callback_log where id = 9870013 or out_trade_no = 'TRADE-9870001'").update();
        jdbcClient.sql("delete from product_review where id = :reviewId")
                .param("reviewId", REVIEW_ID).update();
        jdbcClient.sql("delete from payment_attempt where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from refund_order where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from after_sale_request where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from shipment_waybill_registration where id = :registrationId")
                .param("registrationId", REGISTRATION_ID).update();
        jdbcClient.sql("delete from order_shipment where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from order_electronic_waybill where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from payment_order where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from order_status_log where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from stock_lock where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from stock_log where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from order_item where order_id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from shop_order where id = :orderId")
                .param("orderId", ORDER_ID).update();
        jdbcClient.sql("delete from shop_order where id = :orderId")
                .param("orderId", BATCH_SECOND_ORDER_ID).update();
        jdbcClient.sql("delete from user_coupon where id = :couponId")
                .param("couponId", USER_COUPON_ID).update();
        jdbcClient.sql("delete from storage_asset where id = :assetId")
                .param("assetId", ASSET_ID).update();
        jdbcClient.sql("delete from storage_asset where id = :assetId")
                .param("assetId", ORDER_ITEM_ASSET_ID).update();
        jdbcClient.sql("delete from product_sku where id = :skuId")
                .param("skuId", SKU_ID).update();
        jdbcClient.sql("delete from product_spu where id = :spuId")
                .param("spuId", SPU_ID).update();
        jdbcClient.sql("delete from product_category where id = :categoryId")
                .param("categoryId", CATEGORY_ID).update();
    }

    private record ReviewSnapshot(
            Long orderItemId,
            long sourceOrderItemId,
            String productTitle,
            String specText,
            boolean verifiedPurchase
    ) {
    }

    private record ArchiveManifest(
            long id,
            String objectKey,
            String contentType,
            String sha256,
            long sizeBytes,
            int itemCount,
            int paymentCount,
            int refundCount,
            int afterSaleCount
    ) {
    }

    private record CouponReservation(
            String status,
            Long lockedOrderId,
            LocalDateTime lockedAt,
            LocalDateTime releasedAt
    ) {
    }

    private record CleanupFailure(int consecutiveFailures, LocalDateTime nextRetryAt) {
    }

    private record CustomerServiceContext(
            String contextType,
            Long contextId,
            LocalDateTime lastMessageAt
    ) {
    }
}
