package org.muybaby.shopserver.order.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.muybaby.shopserver.storage.StorageProviderKind;
import org.muybaby.shopserver.storage.config.ResolvedStorageConfig;
import org.muybaby.shopserver.storage.config.StorageRuntimeConfigService;
import org.muybaby.shopserver.storage.provider.StorageObjectLocation;
import org.muybaby.shopserver.storage.provider.StorageProvider;
import org.muybaby.shopserver.storage.provider.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archives and removes one complete order aggregate at a time. Provider I/O is deliberately kept
 * outside the short purge transaction. The transaction locks and revalidates the aggregate before
 * it writes immutable identities and removes any hot rows.
 */
@Service
public class OrderAggregateCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OrderAggregateCleanupService.class);
    private static final int MAX_BATCH_SIZE = 100;
    private static final int ARCHIVE_FORMAT_VERSION = 1;
    private static final long FAILURE_RETRY_BASE_MINUTES = 5L;
    private static final long FAILURE_RETRY_MAX_MINUTES = 1_440L;
    private static final int MAX_FAILURE_ERROR_LENGTH = 255;

    private final JdbcClient jdbcClient;
    private final StorageProvider storageProvider;
    private final StorageRuntimeConfigService storageConfigService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public OrderAggregateCleanupService(
            JdbcClient jdbcClient,
            StorageProvider storageProvider,
            StorageRuntimeConfigService storageConfigService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcClient = jdbcClient;
        this.storageProvider = storageProvider;
        this.storageConfigService = storageConfigService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public int cleanupBatch(
            LocalDateTime cutoff,
            int batchSize,
            boolean retainReviews,
            BooleanSupplier leaseActive
    ) {
        if (cutoff == null || batchSize < 1 || batchSize > MAX_BATCH_SIZE || leaseActive == null) {
            throw new IllegalArgumentException("Invalid order cleanup request");
        }
        List<Long> candidates = candidateIds(cutoff, batchSize);
        int purged = 0;
        int failed = 0;
        for (Long orderId : candidates) {
            if (purged >= batchSize || !leaseActive.getAsBoolean()) {
                break;
            }
            CleanupOutcome outcome = archiveAndPurge(orderId, cutoff, retainReviews, leaseActive);
            if (outcome == CleanupOutcome.PURGED) {
                purged++;
            } else if (outcome == CleanupOutcome.FAILED) {
                failed++;
            }
        }
        if (failed > 0 && purged == 0) {
            throw new IllegalStateException(
                    "Order aggregate cleanup failed for " + failed + " candidate(s); "
                            + purged + " candidate(s) were purged");
        }
        if (failed > 0) {
            log.warn(
                    "Order aggregate cleanup completed with deferred failures: failed={}, purged={}",
                    failed, purged);
        }
        return purged;
    }

    private CleanupOutcome archiveAndPurge(
            Long orderId,
            LocalDateTime cutoff,
            boolean retainReviews,
            BooleanSupplier leaseActive
    ) {
        OrderArchiveSnapshot snapshot = loadSnapshot(orderId);
        if (snapshot == null || !leaseActive.getAsBoolean()) {
            return CleanupOutcome.SKIPPED;
        }

        Path archiveFile = null;
        String objectKey = archiveObjectKey(snapshot);
        StorageObjectLocation uploadedLocation = null;
        try {
            archiveFile = Files.createTempFile("shop-order-archive-", ".zip");
            writeArchive(snapshot, archiveFile, leaseActive);
            if (!leaseActive.getAsBoolean()) {
                return CleanupOutcome.SKIPPED;
            }
            long sizeBytes = Files.size(archiveFile);
            String sha256 = fileSha256(archiveFile);
            ResolvedStorageConfig storageConfig = storageConfigService.effective();
            StorageObjectLocation archiveLocation = new StorageObjectLocation(
                    StorageProviderKind.TENCENT_COS,
                    storageConfig.bucket(),
                    storageConfig.region(),
                    objectKey
            );
            uploadedLocation = archiveLocation;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(archiveFile))) {
                storageProvider.put(archiveLocation, "application/zip", input, sizeBytes);
            }
            if (!leaseActive.getAsBoolean()) {
                deleteArchiveIfUncommitted(archiveLocation);
                return CleanupOutcome.SKIPPED;
            }
            ArchiveObject archive = new ArchiveObject(
                    archiveLocation, "application/zip", sha256, sizeBytes);
            Boolean purged = transactionTemplate.execute(status -> purge(snapshot, archive, cutoff, retainReviews));
            if (!Boolean.TRUE.equals(purged)) {
                deleteArchiveIfUncommitted(archiveLocation);
                return CleanupOutcome.SKIPPED;
            }
            return CleanupOutcome.PURGED;
        } catch (RuntimeException | IOException ex) {
            if (uploadedLocation != null) {
                deleteArchiveIfUncommitted(uploadedLocation);
            }
            recordFailure(orderId, ex);
            log.warn("Order archive failed; hot data was retained: orderId={}", orderId, ex);
            return CleanupOutcome.FAILED;
        } finally {
            if (archiveFile != null) {
                try {
                    Files.deleteIfExists(archiveFile);
                } catch (IOException ex) {
                    log.debug("Unable to remove temporary order archive: orderId={}", orderId, ex);
                }
            }
        }
    }

    private List<Long> candidateIds(LocalDateTime cutoff, int limit) {
        return jdbcClient.sql("""
                        select o.id
                        from shop_order o
                        where o.updated_at < :cutoff
                          and not exists (
                              select 1 from payment_order po
                              where po.order_id = o.id and po.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from refund_order ro
                              where ro.order_id = o.id and ro.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from after_sale_request request
                              where request.order_id = o.id and request.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from order_shipment shipment
                              where shipment.order_id = o.id and shipment.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from order_electronic_waybill waybill
                              where waybill.order_id = o.id
                                and (
                                    waybill.updated_at >= :cutoff
                                    or waybill.status in ('CREATING', 'CREATED', 'CANCELING', 'UNKNOWN')
                                )
                          )
                          and not exists (
                              select 1
                              from shipment_waybill_registration registration
                              join order_shipment registered_shipment
                                on registered_shipment.id = registration.shipment_id
                              where registered_shipment.order_id = o.id
                                and (
                                    registration.updated_at >= :cutoff
                                    or registration.status in ('PENDING', 'REGISTERING')
                                )
                          )
                          and not exists (
                              select 1
                              from shipment_tracking_snapshot tracking
                              join order_shipment tracked_shipment
                                on tracked_shipment.id = tracking.shipment_id
                              where tracked_shipment.order_id = o.id
                                and (
                                    tracking.updated_at >= :cutoff
                                    or tracking.claim_token is not null
                                )
                          )
                          and not exists (
                              select 1 from order_status_log status_log
                              where status_log.order_id = o.id and status_log.created_at >= :cutoff
                          )
                          and not exists (
                              select 1
                              from payment_callback_log callback_log
                              where callback_log.updated_at >= :cutoff
                                and (
                                    exists (
                                        select 1 from payment_order callback_payment
                                        where callback_payment.order_id = o.id
                                          and callback_payment.out_trade_no = callback_log.out_trade_no
                                    )
                                    or exists (
                                        select 1 from refund_order callback_refund
                                        where callback_refund.order_id = o.id
                                          and callback_refund.out_refund_no = callback_log.out_refund_no
                                    )
                                )
                          )
                          and not exists (
                              select 1 from payment_order active_payment
                              where active_payment.order_id = o.id
                                and active_payment.status not in ('PAID', 'CLOSED')
                          )
                          and not exists (
                              select 1
                              from wechat_service_card_delivery service_delivery
                              join wechat_service_card service_card
                                on service_card.id = service_delivery.card_id
                              where service_card.order_id = o.id
                                and service_delivery.state in (
                                    'PENDING', 'SENDING', 'UNKNOWN', 'RECONCILING'
                                )
                          )
                          and not exists (
                              select 1 from refund_order active_refund
                              where active_refund.order_id = o.id
                                and not (
                                    active_refund.status = 'SUCCESS'
                                    or (active_refund.status = 'FAILED'
                                        and active_refund.callback_status = 'CLOSED')
                                )
                          )
                          and not exists (
                              select 1 from finance_reconciliation_difference finance_difference
                              where finance_difference.status in ('OPEN', 'INVESTIGATING')
                                and (
                                    finance_difference.order_id = o.id
                                    or finance_difference.payment_order_id in (
                                        select guarded_payment.id from payment_order guarded_payment
                                        where guarded_payment.order_id = o.id
                                    )
                                    or finance_difference.refund_order_id in (
                                        select guarded_refund.id from refund_order guarded_refund
                                        where guarded_refund.order_id = o.id
                                    )
                                )
                          )
                          and not exists (
                              select 1 from order_cleanup_failure cleanup_failure
                              where cleanup_failure.source_order_id = o.id
                                and cleanup_failure.next_retry_at > current_timestamp
                          )
                        order by o.updated_at asc, o.id asc
                        limit :limit
                        """)
                .param("cutoff", cutoff)
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    private boolean remainsEligible(Long orderId, LocalDateTime cutoff) {
        return jdbcClient.sql("""
                        select count(*)
                        from shop_order o
                        where o.id = :orderId
                          and o.updated_at < :cutoff
                          and not exists (
                              select 1 from payment_order po
                              where po.order_id = o.id
                                and (po.updated_at >= :cutoff or po.status not in ('PAID', 'CLOSED'))
                          )
                          and not exists (
                              select 1 from refund_order ro
                              where ro.order_id = o.id
                                and (
                                    ro.updated_at >= :cutoff
                                    or not (
                                        ro.status = 'SUCCESS'
                                        or (ro.status = 'FAILED' and ro.callback_status = 'CLOSED')
                                    )
                                )
                          )
                          and not exists (
                              select 1 from after_sale_request request
                              where request.order_id = o.id and request.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from order_shipment shipment
                              where shipment.order_id = o.id and shipment.updated_at >= :cutoff
                          )
                          and not exists (
                              select 1 from order_electronic_waybill waybill
                              where waybill.order_id = o.id
                                and (
                                    waybill.updated_at >= :cutoff
                                    or waybill.status in ('CREATING', 'CREATED', 'CANCELING', 'UNKNOWN')
                                )
                          )
                          and not exists (
                              select 1
                              from shipment_waybill_registration registration
                              join order_shipment registered_shipment
                                on registered_shipment.id = registration.shipment_id
                              where registered_shipment.order_id = o.id
                                and (
                                    registration.updated_at >= :cutoff
                                    or registration.status in ('PENDING', 'REGISTERING')
                                )
                          )
                          and not exists (
                              select 1
                              from shipment_tracking_snapshot tracking
                              join order_shipment tracked_shipment
                                on tracked_shipment.id = tracking.shipment_id
                              where tracked_shipment.order_id = o.id
                                and (
                                    tracking.updated_at >= :cutoff
                                    or tracking.claim_token is not null
                                )
                          )
                          and not exists (
                              select 1 from order_status_log status_log
                              where status_log.order_id = o.id and status_log.created_at >= :cutoff
                          )
                          and not exists (
                              select 1
                              from payment_callback_log callback_log
                              where callback_log.updated_at >= :cutoff
                                and (
                                    exists (
                                        select 1 from payment_order callback_payment
                                        where callback_payment.order_id = o.id
                                          and callback_payment.out_trade_no = callback_log.out_trade_no
                                    )
                                    or exists (
                                        select 1 from refund_order callback_refund
                                        where callback_refund.order_id = o.id
                                          and callback_refund.out_refund_no = callback_log.out_refund_no
                                    )
                                )
                          )
                          and not exists (
                              select 1 from finance_reconciliation_difference finance_difference
                              where finance_difference.status in ('OPEN', 'INVESTIGATING')
                                and (
                                    finance_difference.order_id = o.id
                                    or finance_difference.payment_order_id in (
                                        select guarded_payment.id from payment_order guarded_payment
                                        where guarded_payment.order_id = o.id
                                    )
                                    or finance_difference.refund_order_id in (
                                        select guarded_refund.id from refund_order guarded_refund
                                        where guarded_refund.order_id = o.id
                                    )
                                )
                          )
                          and not exists (
                              select 1
                              from wechat_service_card_delivery service_delivery
                              join wechat_service_card service_card
                                on service_card.id = service_delivery.card_id
                              where service_card.order_id = o.id
                                and service_delivery.state in (
                                    'PENDING', 'SENDING', 'UNKNOWN', 'RECONCILING'
                                )
                          )
                        """)
                .param("orderId", orderId)
                .param("cutoff", cutoff)
                .query(Long.class)
                .single() == 1L;
    }

    private OrderArchiveSnapshot loadSnapshot(Long orderId) {
        List<Map<String, Object>> orderRows = rows("select * from shop_order where id = :orderId", orderId);
        if (orderRows.isEmpty()) {
            return null;
        }
        Map<String, Object> order = orderRows.getFirst();
        List<Map<String, Object>> items = rows(
                "select * from order_item where order_id = :orderId order by id", orderId);
        List<Map<String, Object>> payments = rows(
                "select * from payment_order where order_id = :orderId order by id", orderId);
        List<Map<String, Object>> refunds = rows(
                "select * from refund_order where order_id = :orderId order by id", orderId);
        List<Map<String, Object>> afterSales = rows(
                "select * from after_sale_request where order_id = :orderId order by id", orderId);
        List<Map<String, Object>> callbacks = rows("""
                select callback_log.*
                from payment_callback_log callback_log
                where exists (
                    select 1 from payment_order po
                    where po.order_id = :orderId
                      and po.out_trade_no = callback_log.out_trade_no
                )
                   or exists (
                    select 1 from refund_order ro
                    where ro.order_id = :orderId
                      and ro.out_refund_no = callback_log.out_refund_no
                )
                order by callback_log.id
                """, orderId);
        List<Map<String, Object>> assetUsages = rows("""
                select usage_ref.*
                from storage_asset_usage usage_ref
                where (usage_ref.owner_type = 'ORDER_ITEM' and exists (
                    select 1 from order_item item
                    where item.order_id = :orderId and item.id = usage_ref.owner_id
                )) or (usage_ref.owner_type = 'AFTER_SALE' and exists (
                    select 1 from after_sale_request request
                    where request.order_id = :orderId and request.id = usage_ref.owner_id
                ))
                order by usage_ref.id
                """, orderId);
        List<Map<String, Object>> assets = rows("""
                select distinct asset.*
                from storage_asset asset
                where exists (
                    select 1
                    from after_sale_evidence evidence
                    join after_sale_request request on request.id = evidence.after_sale_id
                    where request.order_id = :orderId and evidence.file_id = asset.id
                ) or exists (
                    select 1
                    from storage_asset_usage usage_ref
                    join after_sale_request request on request.id = usage_ref.owner_id
                    where request.order_id = :orderId
                      and usage_ref.owner_type = 'AFTER_SALE'
                      and usage_ref.asset_id = asset.id
                ) or exists (
                    select 1
                    from storage_asset_usage usage_ref
                    join order_item item on item.id = usage_ref.owner_id
                    where item.order_id = :orderId
                      and usage_ref.owner_type = 'ORDER_ITEM'
                      and usage_ref.asset_id = asset.id
                )
                order by asset.id
                """, orderId);

        Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
        sections.put("order_items", items);
        sections.put("stock_locks", rows(
                "select * from stock_lock where order_id = :orderId order by id", orderId));
        sections.put("stock_logs", rows(
                "select * from stock_log where order_id = :orderId order by id", orderId));
        sections.put("payment_orders", payments);
        sections.put("payment_attempts", rows(
                "select * from payment_attempt where order_id = :orderId order by id", orderId));
        sections.put("payment_callbacks", callbacks);
        sections.put("wechat_service_cards", rows(
                "select * from wechat_service_card where order_id = :orderId order by id", orderId));
        sections.put("wechat_service_card_deliveries", rows("""
                select delivery.*
                from wechat_service_card_delivery delivery
                join wechat_service_card card on card.id = delivery.card_id
                where card.order_id = :orderId
                order by delivery.id
                """, orderId));
        sections.put("wechat_service_card_callbacks", rows("""
                select callback.*
                from wechat_service_card_callback_log callback
                join wechat_service_card card on card.id = callback.card_id
                where card.order_id = :orderId
                order by callback.id
                """, orderId));
        sections.put("shipments", rows(
                "select * from order_shipment where order_id = :orderId order by id", orderId));
        sections.put("shipment_items", rows("""
                select shipment_item.*
                from order_shipment_item shipment_item
                join order_shipment shipment on shipment.id = shipment_item.shipment_id
                where shipment.order_id = :orderId
                order by shipment_item.id
                """, orderId));
        sections.put("electronic_waybills", rows(
                "select * from order_electronic_waybill where order_id = :orderId order by id", orderId));
        sections.put("electronic_waybill_items", rows("""
                select waybill_item.*
                from order_electronic_waybill_item waybill_item
                join order_electronic_waybill waybill
                  on waybill.id = waybill_item.electronic_waybill_id
                where waybill.order_id = :orderId
                order by waybill_item.id
                """, orderId));
        sections.put("waybill_registrations", rows("""
                select registration.*
                from shipment_waybill_registration registration
                join order_shipment shipment on shipment.id = registration.shipment_id
                where shipment.order_id = :orderId
                order by registration.id
                """, orderId));
        sections.put("tracking_snapshots", rows("""
                select tracking.*
                from shipment_tracking_snapshot tracking
                join order_shipment shipment on shipment.id = tracking.shipment_id
                where shipment.order_id = :orderId
                order by tracking.shipment_id
                """, orderId));
        sections.put("tracking_events", rows("""
                select event.*
                from shipment_tracking_event event
                join order_shipment shipment on shipment.id = event.shipment_id
                where shipment.order_id = :orderId
                order by event.shipment_id, event.display_order, event.id
                """, orderId));
        sections.put("after_sales", afterSales);
        sections.put("after_sale_items", rows("""
                select item.*
                from after_sale_item item
                join after_sale_request request on request.id = item.after_sale_id
                where request.order_id = :orderId
                order by item.id
                """, orderId));
        sections.put("after_sale_returns", rows("""
                select return_entry.*
                from after_sale_return return_entry
                join after_sale_request request on request.id = return_entry.after_sale_id
                where request.order_id = :orderId
                order by return_entry.after_sale_id
                """, orderId));
        sections.put("after_sale_status_logs", rows("""
                select status_log.*
                from after_sale_status_log status_log
                join after_sale_request request on request.id = status_log.after_sale_id
                where request.order_id = :orderId
                order by status_log.id
                """, orderId));
        sections.put("after_sale_evidence", rows("""
                select evidence.*
                from after_sale_evidence evidence
                join after_sale_request request on request.id = evidence.after_sale_id
                where request.order_id = :orderId
                order by evidence.id
                """, orderId));
        sections.put("refunds", refunds);
        sections.put("refund_inventory_restock_items", rows("""
                select restock.*
                from refund_inventory_restock_item restock
                join refund_order refund on refund.id = restock.refund_order_id
                where refund.order_id = :orderId
                order by restock.id
                """, orderId));
        sections.put("status_logs", rows(
                "select * from order_status_log where order_id = :orderId order by id", orderId));
        sections.put("reviews", rows("""
                select review.*
                from product_review review
                join order_item item on item.id = review.order_item_id
                where item.order_id = :orderId
                order by review.id
                """, orderId));
        sections.put("coupon_references", rows("""
                select * from user_coupon
                where locked_order_id = :orderId or used_order_id = :orderId
                order by id
                """, orderId));
        sections.put("customer_service_order_links", rows("""
                select * from customer_service_conversation_order
                where order_id = :orderId order by id
                """, orderId));
        sections.put("customer_service_resources", rows("""
                select * from customer_service_consultation_resource
                where resource_type = 'ORDER' and resource_id = :orderId order by id
                """, orderId));
        sections.put("customer_service_order_cards", rows("""
                select * from customer_service_message
                where message_type = 'ORDER_CARD' and resource_id = :orderId order by id
                """, orderId));
        sections.put("customer_service_order_contexts", rows("""
                select * from customer_service_conversation
                where context_type = 'ORDER' and context_id = :orderId order by id
                """, orderId));
        sections.put("storage_asset_usages", assetUsages);
        sections.put("storage_assets", assets);
        return new OrderArchiveSnapshot(order, sections, assets, payments, refunds, afterSales, items);
    }

    private List<Map<String, Object>> rows(String sql, Long orderId) {
        return jdbcClient.sql(sql)
                .param("orderId", orderId)
                .query(this::mapRow)
                .list();
    }

    private Map<String, Object> mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            row.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), resultSet.getObject(index));
        }
        return row;
    }

    private void writeArchive(
            OrderArchiveSnapshot snapshot,
            Path archiveFile,
            BooleanSupplier leaseActive
    ) throws IOException {
        try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(archiveFile));
                ZipOutputStream zip = new ZipOutputStream(fileOutput)) {
            zip.putNextEntry(new ZipEntry("archive.json"));
            zip.write(objectMapper.writeValueAsBytes(snapshot.document()));
            zip.closeEntry();

            Set<Long> archivedAssetIds = new LinkedHashSet<>();
            for (Map<String, Object> asset : snapshot.archivedAssets()) {
                if (!leaseActive.getAsBoolean()) {
                    throw new IllegalStateException("Order cleanup lease was lost while archiving attachments");
                }
                Long assetId = numberAsLong(asset.get("id"));
                if (assetId == null || !archivedAssetIds.add(assetId)) {
                    continue;
                }
                StorageObjectLocation location = new StorageObjectLocation(
                        StorageProviderKind.valueOf(Objects.toString(asset.get("provider"))),
                        Objects.toString(asset.get("storage_container"), ""),
                        Objects.toString(asset.get("storage_region"), ""),
                        Objects.toString(asset.get("object_key"), "")
                );
                StoredObject stored = storageProvider.open(location);
                zip.putNextEntry(new ZipEntry("assets/" + assetId));
                try (InputStream input = new BufferedInputStream(stored.inputStream())) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }

    private Boolean purge(
            OrderArchiveSnapshot snapshot,
            ArchiveObject archive,
            LocalDateTime cutoff,
            boolean retainReviews
    ) {
        Long orderId = snapshot.orderId();
        Set<Long> customerServiceConversationIds = snapshot.customerServiceConversationIds();
        lockCustomerServiceConversations(customerServiceConversationIds);
        Map<String, Object> lockedOrder = jdbcClient.sql("""
                        select id, order_no, user_id, idempotency_key, status
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(this::mapRow)
                .optional()
                .orElse(null);
        if (lockedOrder == null || !sameOrderIdentity(snapshot.order(), lockedOrder)) {
            return false;
        }
        // Commerce mutations take the aggregate lock before after-sale/refund rows. Keep cleanup
        // on the same order to avoid a cleanup-vs-new-after-sale deadlock.
        lockAfterSales(orderId);
        lockChildRows(orderId);
        if (!remainsEligible(orderId, cutoff)) {
            return false;
        }
        OrderArchiveSnapshot lockedSnapshot = loadSnapshot(orderId);
        if (!snapshot.equals(lockedSnapshot)) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long manifestId = insertManifest(snapshot, archive, now);
        insertOrderTombstone(snapshot, manifestId, now);
        insertPaymentTombstones(snapshot, manifestId, now);
        insertRefundTombstones(snapshot, manifestId, now);
        releaseResidualReservations(orderId, now);

        if (retainReviews) {
            detachReviews(orderId);
        } else {
            releaseReviewImages(orderId);
            delete("""
                    delete from product_review
                    where order_item_id in (select id from order_item where order_id = :orderId)
                    """, orderId);
        }

        deleteAssetUsages(orderId);
        delete("""
                delete from payment_callback_log
                where out_trade_no in (select out_trade_no from payment_order where order_id = :orderId)
                   or out_refund_no in (select out_refund_no from refund_order where order_id = :orderId)
                """, orderId);
        delete("delete from payment_attempt where order_id = :orderId", orderId);
        delete("""
                delete from wechat_service_card_callback_log
                where card_id in (select id from wechat_service_card where order_id = :orderId)
                """, orderId);
        delete("""
                delete from wechat_service_card_delivery
                where card_id in (select id from wechat_service_card where order_id = :orderId)
                """, orderId);
        delete("delete from wechat_service_card where order_id = :orderId", orderId);
        delete("delete from order_status_log where order_id = :orderId", orderId);
        delete("""
                delete from refund_inventory_restock_item
                where refund_order_id in (select id from refund_order where order_id = :orderId)
                """, orderId);
        delete("""
                delete from after_sale_evidence
                where after_sale_id in (select id from after_sale_request where order_id = :orderId)
                """, orderId);
        delete("delete from refund_order where order_id = :orderId", orderId);
        delete("""
                delete from after_sale_status_log
                where after_sale_id in (select id from after_sale_request where order_id = :orderId)
                """, orderId);
        delete("""
                delete from after_sale_return
                where after_sale_id in (select id from after_sale_request where order_id = :orderId)
                """, orderId);
        delete("""
                delete from after_sale_item
                where after_sale_id in (select id from after_sale_request where order_id = :orderId)
                """, orderId);
        delete("delete from after_sale_request where order_id = :orderId", orderId);
        delete("""
                delete from shipment_tracking_event
                where shipment_id in (select id from order_shipment where order_id = :orderId)
                """, orderId);
        delete("""
                delete from shipment_tracking_snapshot
                where shipment_id in (select id from order_shipment where order_id = :orderId)
                """, orderId);
        delete("""
                delete from shipment_waybill_registration
                where shipment_id in (select id from order_shipment where order_id = :orderId)
                """, orderId);
        delete("""
                delete from order_shipment_item
                where shipment_id in (select id from order_shipment where order_id = :orderId)
                """, orderId);
        delete("delete from order_shipment where order_id = :orderId", orderId);
        delete("""
                delete from order_electronic_waybill_item
                where electronic_waybill_id in (
                    select id from order_electronic_waybill where order_id = :orderId
                )
                """, orderId);
        delete("delete from order_electronic_waybill where order_id = :orderId", orderId);
        delete("delete from payment_order where order_id = :orderId", orderId);
        delete("delete from stock_lock where order_id = :orderId", orderId);
        delete("delete from stock_log where order_id = :orderId", orderId);
        delete("""
                delete from customer_service_message
                where message_type = 'ORDER_CARD' and resource_id = :orderId
                """, orderId);
        delete("""
                delete from customer_service_consultation_resource
                where resource_type = 'ORDER' and resource_id = :orderId
                """, orderId);
        delete("delete from customer_service_conversation_order where order_id = :orderId", orderId);
        jdbcClient.sql("""
                        update customer_service_conversation
                        set context_type = 'GENERAL', context_id = null, updated_at = current_timestamp
                        where context_type = 'ORDER' and context_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        refreshCustomerServiceConversationActivity(customerServiceConversationIds);
        jdbcClient.sql("""
                        update user_coupon
                        set locked_at = case when locked_order_id = :orderId then null else locked_at end,
                            locked_order_id = case when locked_order_id = :orderId then null else locked_order_id end,
                            used_order_id = case when used_order_id = :orderId then null else used_order_id end,
                            updated_at = current_timestamp
                        where locked_order_id = :orderId or used_order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
        delete("delete from order_item where order_id = :orderId", orderId);
        return delete("delete from shop_order where id = :orderId", orderId) == 1;
    }

    private void lockChildRows(Long orderId) {
        jdbcClient.sql("select id from refund_order where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select restock.id
                        from refund_inventory_restock_item restock
                        join refund_order refund on refund.id = restock.refund_order_id
                        where refund.order_id = :orderId
                        order by restock.id for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("select id from payment_order where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("select id from wechat_service_card where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select delivery.id
                        from wechat_service_card_delivery delivery
                        join wechat_service_card card on card.id = delivery.card_id
                        where card.order_id = :orderId
                        order by delivery.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select callback.id
                        from wechat_service_card_callback_log callback
                        join wechat_service_card card on card.id = callback.card_id
                        where card.order_id = :orderId
                        order by callback.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select finance_difference.id
                        from finance_reconciliation_difference finance_difference
                        where finance_difference.order_id = :orderId
                           or finance_difference.payment_order_id in (
                               select payment.id from payment_order payment
                               where payment.order_id = :orderId
                           )
                           or finance_difference.refund_order_id in (
                               select refund.id from refund_order refund
                               where refund.order_id = :orderId
                           )
                        order by finance_difference.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("select id from order_electronic_waybill where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("select id from order_shipment where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select tracking.shipment_id
                        from shipment_tracking_snapshot tracking
                        join order_shipment shipment on shipment.id = tracking.shipment_id
                        where shipment.order_id = :orderId
                        order by tracking.shipment_id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select event.id
                        from shipment_tracking_event event
                        join order_shipment shipment on shipment.id = event.shipment_id
                        where shipment.order_id = :orderId
                        order by event.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select registration.id
                        from shipment_waybill_registration registration
                        join order_shipment shipment on shipment.id = registration.shipment_id
                        where shipment.order_id = :orderId
                        order by registration.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("select id from order_item where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select review.id
                        from product_review review
                        join order_item item on item.id = review.order_item_id
                        where item.order_id = :orderId
                        order by review.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
    }

    private void lockCustomerServiceConversations(Set<Long> conversationIds) {
        if (conversationIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("""
                        select id
                        from customer_service_conversation
                        where id in (:conversationIds)
                        order by id
                        for update
                        """)
                .param("conversationIds", conversationIds)
                .query(Long.class)
                .list();
    }

    private void refreshCustomerServiceConversationActivity(Set<Long> conversationIds) {
        for (Long conversationId : conversationIds) {
            jdbcClient.sql("""
                            update customer_service_conversation conversation
                            set last_message_at = (
                                    select max(message.created_at)
                                    from customer_service_message message
                                    where message.conversation_id = conversation.id
                                ),
                                updated_at = current_timestamp
                            where conversation.id = :conversationId
                            """)
                    .param("conversationId", conversationId)
                    .update();
        }
    }

    private void lockAfterSales(Long orderId) {
        jdbcClient.sql("select id from after_sale_request where order_id = :orderId order by id for update")
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select item.id from after_sale_item item
                        join after_sale_request request on request.id = item.after_sale_id
                        where request.order_id = :orderId order by item.id for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select return_entry.after_sale_id from after_sale_return return_entry
                        join after_sale_request request on request.id = return_entry.after_sale_id
                        where request.order_id = :orderId order by return_entry.after_sale_id for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        select status_log.id from after_sale_status_log status_log
                        join after_sale_request request on request.id = status_log.after_sale_id
                        where request.order_id = :orderId order by status_log.id for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
    }

    private void releaseResidualReservations(Long orderId, LocalDateTime now) {
        List<LockedStock> lockedStocks = jdbcClient.sql("""
                        select id, sku_id, quantity
                        from stock_lock
                        where order_id = :orderId and status = 'LOCKED'
                        order by sku_id, id
                        for update
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new LockedStock(
                        rs.getLong("id"),
                        rs.getLong("sku_id"),
                        rs.getInt("quantity")
                ))
                .list();
        for (LockedStock stock : lockedStocks) {
            Integer quantityBefore = jdbcClient.sql("""
                            select stock_available from product_sku
                            where id = :skuId for update
                            """)
                    .param("skuId", stock.skuId())
                    .query(Integer.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "Cannot release a purged order stock lock whose SKU no longer exists"));
            int restored = jdbcClient.sql("""
                            update product_sku
                            set stock_available = stock_available + :quantity,
                                updated_at = :updatedAt
                            where id = :skuId and stock_available = :quantityBefore
                            """)
                    .param("quantity", stock.quantity())
                    .param("updatedAt", now)
                    .param("skuId", stock.skuId())
                    .param("quantityBefore", quantityBefore)
                    .update();
            int released = jdbcClient.sql("""
                            update stock_lock
                            set status = 'RELEASED', released_at = :releasedAt,
                                updated_at = :updatedAt
                            where id = :stockLockId and status = 'LOCKED'
                            """)
                    .param("releasedAt", now)
                    .param("updatedAt", now)
                    .param("stockLockId", stock.id())
                    .update();
            if (restored != 1 || released != 1) {
                throw new IllegalStateException("Unable to release an order stock reservation");
            }
        }

        jdbcClient.sql("""
                        update user_coupon
                        set status = case when valid_end_at <= :releasedAt then 'EXPIRED' else 'CLAIMED' end,
                            locked_order_id = null,
                            locked_at = null,
                            released_at = :releasedAt,
                            updated_at = :updatedAt
                        where locked_order_id = :orderId and status = 'LOCKED'
                        """)
                .param("releasedAt", now)
                .param("updatedAt", now)
                .param("orderId", orderId)
                .update();
    }

    private boolean sameOrderIdentity(Map<String, Object> before, Map<String, Object> locked) {
        return Objects.equals(numberAsLong(before.get("id")), numberAsLong(locked.get("id")))
                && Objects.equals(before.get("order_no"), locked.get("order_no"))
                && Objects.equals(numberAsLong(before.get("user_id")), numberAsLong(locked.get("user_id")))
                && Objects.equals(before.get("idempotency_key"), locked.get("idempotency_key"));
    }

    private Long insertManifest(
            OrderArchiveSnapshot snapshot,
            ArchiveObject archive,
            LocalDateTime now
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcClient.sql("""
                        insert into order_archive_manifest
                            (source_order_id, order_no_digest, object_key, content_type, sha256,
                             provider, storage_container, storage_region, size_bytes,
                             archive_format_version, item_count, payment_count,
                             refund_count, after_sale_count, status, archived_at, purged_at,
                             created_at, updated_at)
                        values
                            (:sourceOrderId, :orderNoDigest, :objectKey, :contentType, :sha256,
                             :provider, :storageContainer, :storageRegion, :sizeBytes,
                             :archiveFormatVersion, :itemCount, :paymentCount,
                             :refundCount, :afterSaleCount, 'PURGED', :archivedAt, :purgedAt,
                             :createdAt, :updatedAt)
                        """)
                .param("sourceOrderId", snapshot.orderId())
                .param("orderNoDigest", PurgedOrderIdentityDigests.value(snapshot.orderNo()))
                .param("objectKey", archive.location().objectKey())
                .param("contentType", archive.contentType())
                .param("sha256", archive.sha256())
                .param("provider", archive.location().provider().name())
                .param("storageContainer", archive.location().container())
                .param("storageRegion", archive.location().region())
                .param("sizeBytes", archive.sizeBytes())
                .param("archiveFormatVersion", ARCHIVE_FORMAT_VERSION)
                .param("itemCount", snapshot.items().size())
                .param("paymentCount", snapshot.payments().size())
                .param("refundCount", snapshot.refunds().size())
                .param("afterSaleCount", snapshot.afterSales().size())
                .param("archivedAt", now)
                .param("purgedAt", now)
                .param("createdAt", now)
                .param("updatedAt", now)
                .update(keyHolder, "id");
        if (inserted != 1 || keyHolder.getKey() == null) {
            throw new IllegalStateException("Order archive manifest was not inserted");
        }
        return keyHolder.getKey().longValue();
    }

    private void insertOrderTombstone(
            OrderArchiveSnapshot snapshot,
            Long manifestId,
            LocalDateTime now
    ) {
        jdbcClient.sql("""
                        insert into purged_order_identity
                            (archive_manifest_id, user_idempotency_digest, order_no_digest,
                             final_status, purged_at, created_at)
                        values
                            (:manifestId, :userIdempotencyDigest, :orderNoDigest,
                             :finalStatus, :purgedAt, :createdAt)
                        """)
                .param("manifestId", manifestId)
                .param("userIdempotencyDigest", PurgedOrderIdentityDigests.userIdempotency(
                        snapshot.userId(), snapshot.idempotencyKey()))
                .param("orderNoDigest", PurgedOrderIdentityDigests.value(snapshot.orderNo()))
                .param("finalStatus", snapshot.status())
                .param("purgedAt", now)
                .param("createdAt", now)
                .update();
    }

    private void insertPaymentTombstones(
            OrderArchiveSnapshot snapshot,
            Long manifestId,
            LocalDateTime now
    ) {
        for (Map<String, Object> payment : snapshot.payments()) {
            jdbcClient.sql("""
                            insert into purged_payment_identity
                                (archive_manifest_id, out_trade_no_digest, transaction_id_digest,
                                 notification_route_digest, payment_config_id,
                                 payment_config_fingerprint, final_status, amount_cent, currency,
                                 purged_at, created_at)
                            values
                                (:manifestId, :outTradeNoDigest, :transactionIdDigest,
                                 :notificationRouteDigest, :paymentConfigId,
                                 :paymentConfigFingerprint, :finalStatus, :amountCent, :currency,
                                 :purgedAt, :createdAt)
                            """)
                    .param("manifestId", manifestId)
                    .param("outTradeNoDigest", PurgedOrderIdentityDigests.value(
                            text(payment, "out_trade_no")))
                    .param("transactionIdDigest", digestOrEmpty(text(payment, "transaction_id")))
                    .param("notificationRouteDigest", PurgedOrderIdentityDigests.value(
                            text(payment, "notification_route_token")))
                    .param("paymentConfigId", numberAsLong(payment.get("payment_config_id")))
                    .param("paymentConfigFingerprint", text(payment, "payment_config_fingerprint"))
                    .param("finalStatus", text(payment, "status"))
                    .param("amountCent", numberAsLong(payment.get("amount_cent")))
                    .param("currency", text(payment, "currency"))
                    .param("purgedAt", now)
                    .param("createdAt", now)
                    .update();
        }
    }

    private void insertRefundTombstones(
            OrderArchiveSnapshot snapshot,
            Long manifestId,
            LocalDateTime now
    ) {
        Map<Long, Map<String, Object>> paymentsById = new LinkedHashMap<>();
        for (Map<String, Object> payment : snapshot.payments()) {
            paymentsById.put(numberAsLong(payment.get("id")), payment);
        }
        for (Map<String, Object> refund : snapshot.refunds()) {
            Map<String, Object> payment = paymentsById.get(numberAsLong(refund.get("payment_order_id")));
            if (payment == null) {
                throw new IllegalStateException("Refund payment identity is missing from order archive");
            }
            jdbcClient.sql("""
                            insert into purged_refund_identity
                                (archive_manifest_id, out_refund_no_digest, out_trade_no_digest,
                                 refund_id_digest, notification_route_digest, payment_config_id,
                                 payment_config_fingerprint, final_status, final_callback_status,
                                 refund_amount_cent, purged_at, created_at)
                            values
                                (:manifestId, :outRefundNoDigest, :outTradeNoDigest,
                                 :refundIdDigest, :notificationRouteDigest, :paymentConfigId,
                                 :paymentConfigFingerprint, :finalStatus, :finalCallbackStatus,
                                 :refundAmountCent, :purgedAt, :createdAt)
                            """)
                    .param("manifestId", manifestId)
                    .param("outRefundNoDigest", PurgedOrderIdentityDigests.value(
                            text(refund, "out_refund_no")))
                    .param("outTradeNoDigest", PurgedOrderIdentityDigests.value(
                            text(payment, "out_trade_no")))
                    .param("refundIdDigest", digestOrEmpty(text(refund, "refund_id")))
                    .param("notificationRouteDigest", PurgedOrderIdentityDigests.value(
                            text(refund, "notification_route_token")))
                    .param("paymentConfigId", numberAsLong(payment.get("payment_config_id")))
                    .param("paymentConfigFingerprint", text(payment, "payment_config_fingerprint"))
                    .param("finalStatus", text(refund, "status"))
                    .param("finalCallbackStatus", text(refund, "callback_status"))
                    .param("refundAmountCent", numberAsLong(refund.get("refund_amount_cent")))
                    .param("purgedAt", now)
                    .param("createdAt", now)
                    .update();
        }
    }

    private void detachReviews(Long orderId) {
        jdbcClient.sql("""
                        update product_review
                        set source_order_item_id = order_item_id,
                            product_title_snapshot = (
                                select item.product_title from order_item item
                                where item.id = product_review.order_item_id
                            ),
                            spec_text_snapshot = (
                                select item.spec_text from order_item item
                                where item.id = product_review.order_item_id
                            ),
                            verified_purchase = true,
                            order_item_id = null,
                            updated_at = current_timestamp
                        where order_item_id in (
                            select item.id from order_item item where item.order_id = :orderId
                        )
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void releaseReviewImages(Long orderId) {
        List<Long> reviewIds = jdbcClient.sql("""
                        select review.id
                        from product_review review
                        where review.order_item_id in (
                            select item.id from order_item item where item.order_id = :orderId
                        )
                        order by review.id
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        if (reviewIds.isEmpty()) {
            return;
        }
        List<Long> assetIds = jdbcClient.sql("""
                        select image.asset_id
                        from product_review_image image
                        where image.review_id in (:reviewIds)
                        order by image.asset_id
                        for update
                        """)
                .param("reviewIds", reviewIds)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        update storage_asset_usage
                        set status = 'REMOVED', updated_at = current_timestamp
                        where owner_type = 'PRODUCT_REVIEW'
                          and owner_id in (:reviewIds)
                          and status = 'ACTIVE'
                        """)
                .param("reviewIds", reviewIds)
                .update();
        if (!assetIds.isEmpty()) {
            jdbcClient.sql("""
                            update storage_asset asset
                            set expires_at = current_timestamp,
                                updated_at = current_timestamp
                            where asset.id in (:assetIds)
                              and asset.status = 'ACTIVE'
                              and not exists (
                                  select 1 from storage_asset_usage usage_ref
                                  where usage_ref.asset_id = asset.id and usage_ref.status = 'ACTIVE'
                              )
                            """)
                    .param("assetIds", assetIds)
                    .update();
        }
    }

    private void deleteAssetUsages(Long orderId) {
        List<Long> detachedAssetIds = jdbcClient.sql("""
                        select distinct usage_ref.asset_id
                        from storage_asset_usage usage_ref
                        where usage_ref.owner_type = 'ORDER_ITEM'
                          and usage_ref.owner_id in (
                              select item.id from order_item item
                              where item.order_id = :orderId
                          )
                        union
                        select distinct usage_ref.asset_id
                        from storage_asset_usage usage_ref
                        where usage_ref.owner_type = 'AFTER_SALE'
                          and usage_ref.owner_id in (
                              select request.id from after_sale_request request
                              where request.order_id = :orderId
                          )
                        union
                        select distinct evidence.file_id
                        from after_sale_evidence evidence
                        where evidence.after_sale_id in (
                              select request.id from after_sale_request request
                              where request.order_id = :orderId
                        )
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .list();
        jdbcClient.sql("""
                        delete from storage_asset_usage
                        where (owner_type = 'ORDER_ITEM' and owner_id in (
                                  select item.id from order_item item where item.order_id = :orderId
                              ))
                           or (owner_type = 'AFTER_SALE' and owner_id in (
                                  select request.id from after_sale_request request
                                  where request.order_id = :orderId
                              ))
                        """)
                .param("orderId", orderId)
                .update();
        for (Long assetId : detachedAssetIds) {
            jdbcClient.sql("""
                            update storage_asset asset
                            set expires_at = current_timestamp,
                                updated_at = current_timestamp
                            where asset.id = :assetId
                              and asset.scope = 'ATTACHMENT'
                              and not exists (
                                  select 1 from storage_asset_usage usage_ref
                                  where usage_ref.asset_id = asset.id and usage_ref.status = 'ACTIVE'
                              )
                              and not exists (
                                  select 1
                                  from after_sale_evidence evidence
                                  join after_sale_request request
                                    on request.id = evidence.after_sale_id
                                  where evidence.file_id = asset.id
                                    and request.order_id <> :orderId
                              )
                            """)
                    .param("assetId", assetId)
                    .param("orderId", orderId)
                    .update();
        }
    }

    private int delete(String sql, Long orderId) {
        return jdbcClient.sql(sql).param("orderId", orderId).update();
    }

    private String archiveObjectKey(OrderArchiveSnapshot snapshot) {
        String orderNoDigest = PurgedOrderIdentityDigests.value(snapshot.orderNo());
        return "private/order-archive/v1/"
                + orderNoDigest.substring(0, 2)
                + "/"
                + orderNoDigest
                + "/"
                + UUID.randomUUID()
                + ".zip";
    }

    private String fileSha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
        try (InputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(path)), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void safeDeleteArchive(StorageObjectLocation location) {
        try {
            storageProvider.delete(location);
        } catch (RuntimeException ex) {
            log.warn("Unable to remove uncommitted order archive object: objectKey={}",
                    location.objectKey(), ex);
        }
    }

    private void deleteArchiveIfUncommitted(StorageObjectLocation location) {
        try {
            boolean committed = jdbcClient.sql("""
                            select count(*) from order_archive_manifest where object_key = :objectKey
                            """)
                    .param("objectKey", location.objectKey())
                    .query(Long.class)
                    .single() > 0L;
            if (!committed) {
                safeDeleteArchive(location);
            }
        } catch (RuntimeException verificationFailure) {
            // An uncertain database commit must never be followed by deleting the only archive.
            log.warn("Unable to verify order archive commit; preserving object: objectKey={}",
                    location.objectKey(), verificationFailure);
        }
    }

    private void recordFailure(Long orderId, Exception failure) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Integer previousFailures = jdbcClient.sql("""
                                select consecutive_failures
                                from order_cleanup_failure
                                where source_order_id = :orderId
                                for update
                                """)
                        .param("orderId", orderId)
                        .query(Integer.class)
                        .optional()
                        .orElse(null);
                int nextFailures = previousFailures == null ? 1 : previousFailures + 1;
                int exponent = Math.min(nextFailures - 1, 8);
                long retryMinutes = Math.min(
                        FAILURE_RETRY_BASE_MINUTES * (1L << exponent),
                        FAILURE_RETRY_MAX_MINUTES);
                LocalDateTime now = jdbcClient.sql("select current_timestamp")
                        .query(LocalDateTime.class)
                        .single();
                String lastError = cleanupFailureMessage(failure);
                if (previousFailures == null) {
                    jdbcClient.sql("""
                                    insert into order_cleanup_failure
                                        (source_order_id, consecutive_failures, next_retry_at,
                                         last_error, created_at, updated_at)
                                    values
                                        (:orderId, :failures, :nextRetryAt,
                                         :lastError, :createdAt, :updatedAt)
                                    """)
                            .param("orderId", orderId)
                            .param("failures", nextFailures)
                            .param("nextRetryAt", now.plusMinutes(retryMinutes))
                            .param("lastError", lastError)
                            .param("createdAt", now)
                            .param("updatedAt", now)
                            .update();
                    return;
                }
                jdbcClient.sql("""
                                update order_cleanup_failure
                                set consecutive_failures = :failures,
                                    next_retry_at = :nextRetryAt,
                                    last_error = :lastError,
                                    updated_at = :updatedAt
                                where source_order_id = :orderId
                                """)
                        .param("failures", nextFailures)
                        .param("nextRetryAt", now.plusMinutes(retryMinutes))
                        .param("lastError", lastError)
                        .param("updatedAt", now)
                        .param("orderId", orderId)
                        .update();
            });
        } catch (RuntimeException persistenceFailure) {
            log.warn("Unable to record order cleanup retry: orderId={}", orderId, persistenceFailure);
        }
    }

    private String cleanupFailureMessage(Exception failure) {
        String message = failure.getClass().getSimpleName() + ": "
                + Objects.toString(failure.getMessage(), "");
        String clean = message.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() <= MAX_FAILURE_ERROR_LENGTH
                ? clean
                : clean.substring(0, MAX_FAILURE_ERROR_LENGTH);
    }

    private String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private String digestOrEmpty(String value) {
        return value == null || value.isBlank() ? "" : PurgedOrderIdentityDigests.value(value.trim());
    }

    private Long numberAsLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private record ArchiveObject(
            StorageObjectLocation location,
            String contentType,
            String sha256,
            long sizeBytes
    ) {
    }

    private enum CleanupOutcome {
        PURGED,
        SKIPPED,
        FAILED
    }

    private record LockedStock(long id, long skuId, int quantity) {
    }

    private record OrderArchiveSnapshot(
            Map<String, Object> order,
            Map<String, List<Map<String, Object>>> sections,
            List<Map<String, Object>> archivedAssets,
            List<Map<String, Object>> payments,
            List<Map<String, Object>> refunds,
            List<Map<String, Object>> afterSales,
            List<Map<String, Object>> items
    ) {
        private Map<String, Object> document() {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("archive_format_version", ARCHIVE_FORMAT_VERSION);
            document.put("archived_at", LocalDateTime.now(ZoneOffset.UTC));
            document.put("order", order);
            document.putAll(sections);
            return document;
        }

        private Long orderId() {
            return ((Number) order.get("id")).longValue();
        }

        private Long userId() {
            return ((Number) order.get("user_id")).longValue();
        }

        private String orderNo() {
            return Objects.toString(order.get("order_no"), "");
        }

        private String idempotencyKey() {
            return Objects.toString(order.get("idempotency_key"), "");
        }

        private String status() {
            return Objects.toString(order.get("status"), "");
        }

        private Set<Long> customerServiceConversationIds() {
            Set<Long> conversationIds = new LinkedHashSet<>();
            addConversationIds(
                    conversationIds, "customer_service_order_links", "conversation_id");
            addConversationIds(
                    conversationIds, "customer_service_resources", "conversation_id");
            addConversationIds(
                    conversationIds, "customer_service_order_cards", "conversation_id");
            addConversationIds(
                    conversationIds, "customer_service_order_contexts", "id");
            return conversationIds;
        }

        private void addConversationIds(
                Set<Long> destination,
                String section,
                String column
        ) {
            for (Map<String, Object> row : sections.getOrDefault(section, List.of())) {
                Object value = row.get(column);
                if (value instanceof Number number) {
                    destination.add(number.longValue());
                }
            }
        }
    }
}
