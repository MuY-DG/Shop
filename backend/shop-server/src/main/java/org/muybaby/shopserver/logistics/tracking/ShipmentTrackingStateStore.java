package org.muybaby.shopserver.logistics.tracking;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.tracking.dto.ShipmentTrackingEventResponse;
import org.muybaby.shopserver.logistics.tracking.dto.ShipmentTrackingResponse;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathItem;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathRequest;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingPathResult;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryRequest;
import org.muybaby.shopserver.logistics.tracking.provider.WechatTrackingQueryResult;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationKind;
import org.muybaby.shopserver.logistics.waybill.registration.WaybillRegistrationStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ShipmentTrackingStateStore {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 255;
    private static final int MAX_ACTION_MESSAGE_LENGTH = 512;
    private static final Pattern MOBILE_PHONE = Pattern.compile("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)");

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final WechatTrackingProperties properties;

    public ShipmentTrackingStateStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            WechatTrackingProperties properties
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public Optional<ShipmentTrackingClaim> claimForOwner(
            long orderId,
            long userId,
            boolean force
    ) {
        requireOwnedVisibleOrder(orderId, userId);
        return claim(orderId, force);
    }

    public Optional<ShipmentTrackingClaim> claimForOwner(
            long orderId, long shipmentId, long userId, boolean force
    ) {
        requireOwnedVisibleOrder(orderId, userId);
        return claim(orderId, shipmentId, force);
    }

    public Optional<ShipmentTrackingClaim> claimForAdmin(long orderId, boolean force) {
        requireOrder(orderId);
        return claim(orderId, force);
    }

    public Optional<ShipmentTrackingClaim> claimForAdmin(
            long orderId, long shipmentId, boolean force
    ) {
        requireOrder(orderId);
        return claim(orderId, shipmentId, force);
    }

    public ShipmentTrackingResponse snapshotForOwner(long orderId, long userId) {
        requireOwnedVisibleOrder(orderId, userId);
        return snapshot(orderId);
    }

    public ShipmentTrackingResponse snapshotForOwner(long orderId, long shipmentId, long userId) {
        requireOwnedVisibleOrder(orderId, userId);
        return snapshot(orderId, shipmentId);
    }

    public ShipmentTrackingResponse snapshotForAdmin(long orderId) {
        requireOrder(orderId);
        return snapshot(orderId);
    }

    public ShipmentTrackingResponse snapshotForAdmin(long orderId, long shipmentId) {
        requireOrder(orderId);
        return snapshot(orderId, shipmentId);
    }

    public boolean complete(ShipmentTrackingClaim claim, ShipmentTrackingSyncResult result) {
        if (claim == null) {
            return false;
        }
        Boolean completed = transactionTemplate.execute(status -> completeInTransaction(claim, result));
        return Boolean.TRUE.equals(completed);
    }

    private Optional<ShipmentTrackingClaim> claim(long orderId, boolean force) {
        long shipmentId = latestShipmentId(orderId);
        return claim(orderId, shipmentId, force);
    }

    private Optional<ShipmentTrackingClaim> claim(long orderId, long shipmentId, boolean force) {
        Optional<ShipmentTrackingClaim> claim = transactionTemplate.execute(
                status -> claimInTransaction(orderId, shipmentId, force)
        );
        return claim == null ? Optional.empty() : claim;
    }

    private Optional<ShipmentTrackingClaim> claimInTransaction(
            long orderId, long shipmentId, boolean force
    ) {
        TrackingContext context = lockAndLoadContext(orderId, shipmentId);
        boolean querySupported = querySupported(context);
        boolean pathSupported = pathSupported(context);
        ensureSnapshot(context.shipmentId(), querySupported, pathSupported);

        SnapshotRow snapshot = jdbcClient.sql("""
                        select query_supported, query_sync_status, path_supported, path_sync_status,
                               claim_token, claimed_at, last_attempt_at
                        from shipment_tracking_snapshot
                        where shipment_id = :shipmentId
                        for update
                        """)
                .param("shipmentId", context.shipmentId())
                .query((rs, rowNum) -> new SnapshotRow(
                        rs.getBoolean("query_supported"),
                        trackingStatus(rs.getString("query_sync_status")),
                        rs.getBoolean("path_supported"),
                        trackingStatus(rs.getString("path_sync_status")),
                        rs.getString("claim_token"),
                        rs.getObject("claimed_at", LocalDateTime.class),
                        rs.getObject("last_attempt_at", LocalDateTime.class)
                ))
                .single();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (StringUtils.hasText(snapshot.claimToken())
                && snapshot.claimedAt() != null
                && snapshot.claimedAt().isAfter(now.minus(properties.claimTimeout()))) {
            return Optional.empty();
        }
        if (!force
                && snapshot.lastAttemptAt() != null
                && snapshot.lastAttemptAt().isAfter(now.minus(properties.refreshInterval()))) {
            return Optional.empty();
        }
        if (!querySupported && !pathSupported) {
            return Optional.empty();
        }

        String claimToken = UUID.randomUUID().toString();
        jdbcClient.sql("""
                        update shipment_tracking_snapshot
                        set query_supported = :querySupported,
                            query_sync_status = :queryStatus,
                            path_supported = :pathSupported,
                            path_sync_status = :pathStatus,
                            claim_token = :claimToken,
                            claimed_at = :claimedAt,
                            attempt_count = attempt_count + 1,
                            last_attempt_at = :lastAttemptAt,
                            updated_at = :updatedAt
                        where shipment_id = :shipmentId
                        """)
                .param("querySupported", querySupported)
                .param("queryStatus", querySupported
                        ? WechatTrackingSyncStatus.SYNCING.name()
                        : WechatTrackingSyncStatus.UNSUPPORTED.name())
                .param("pathSupported", pathSupported)
                .param("pathStatus", pathSupported
                        ? WechatTrackingSyncStatus.SYNCING.name()
                        : WechatTrackingSyncStatus.UNSUPPORTED.name())
                .param("claimToken", claimToken)
                .param("claimedAt", now)
                .param("lastAttemptAt", now)
                .param("updatedAt", now)
                .param("shipmentId", context.shipmentId())
                .update();

        WechatTrackingQueryRequest queryRequest = querySupported
                ? new WechatTrackingQueryRequest(
                context.shipmentId(), context.registrationKind(), context.waybillToken()
        )
                : null;
        WechatTrackingPathRequest pathRequest = pathSupported
                ? new WechatTrackingPathRequest(
                context.shipmentId(),
                context.providerOrderId(),
                context.payerOpenid(),
                context.electronicDeliveryId(),
                context.electronicWaybillNo()
        )
                : null;
        return Optional.of(new ShipmentTrackingClaim(
                context.shipmentId(), context.orderId(), claimToken, queryRequest, pathRequest
        ));
    }

    private boolean completeInTransaction(
            ShipmentTrackingClaim claim,
            ShipmentTrackingSyncResult syncResult
    ) {
        int owned = jdbcClient.sql("""
                        select count(*)
                        from shipment_tracking_snapshot
                        where shipment_id = :shipmentId
                          and claim_token = :claimToken
                        """)
                .param("shipmentId", claim.shipmentId())
                .param("claimToken", claim.claimToken())
                .query(Integer.class)
                .single();
        if (owned != 1) {
            return false;
        }

        WechatTrackingQueryResult queryResult = syncResult == null
                ? null
                : syncResult.queryResult();
        WechatTrackingPathResult pathResult = syncResult == null
                ? null
                : syncResult.pathResult();
        SourceCompletion query = claim.queryRequest() == null
                ? SourceCompletion.unsupported()
                : queryCompletion(queryResult);
        SourceCompletion path = claim.pathRequest() == null
                ? SourceCompletion.unsupported()
                : pathCompletion(pathResult);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean anySuccess = query.status() == WechatTrackingSyncStatus.SYNCED
                || path.status() == WechatTrackingSyncStatus.SYNCED;

        int updated = jdbcClient.sql("""
                        update shipment_tracking_snapshot
                        set query_sync_status = :queryStatus,
                            logistics_status = case
                                when :querySucceeded then :logisticsStatus
                                else logistics_status
                            end,
                            query_error_code = :queryErrorCode,
                            query_error_message = :queryErrorMessage,
                            path_sync_status = :pathStatus,
                            path_error_code = :pathErrorCode,
                            path_error_message = :pathErrorMessage,
                            claim_token = null,
                            claimed_at = null,
                            last_synced_at = case
                                when :anySuccess then :lastSyncedAt
                                else last_synced_at
                            end,
                            updated_at = :updatedAt
                        where shipment_id = :shipmentId
                          and claim_token = :claimToken
                        """)
                .param("queryStatus", query.status().name())
                .param("querySucceeded", query.status() == WechatTrackingSyncStatus.SYNCED)
                .param("logisticsStatus", query.logisticsStatus())
                .param("queryErrorCode", query.errorCode())
                .param("queryErrorMessage", query.errorMessage())
                .param("pathStatus", path.status().name())
                .param("pathErrorCode", path.errorCode())
                .param("pathErrorMessage", path.errorMessage())
                .param("anySuccess", anySuccess)
                .param("lastSyncedAt", now)
                .param("updatedAt", now)
                .param("shipmentId", claim.shipmentId())
                .param("claimToken", claim.claimToken())
                .update();
        if (updated != 1) {
            return false;
        }
        if (path.status() == WechatTrackingSyncStatus.SYNCED) {
            replacePathItems(claim.shipmentId(), pathResult == null ? List.of() : pathResult.pathItems());
        }
        return true;
    }

    private void ensureSnapshot(long shipmentId, boolean querySupported, boolean pathSupported) {
        Optional<SnapshotCapabilityRow> existing = jdbcClient.sql("""
                        select query_supported, query_sync_status, path_supported, path_sync_status
                        from shipment_tracking_snapshot
                        where shipment_id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new SnapshotCapabilityRow(
                        rs.getBoolean("query_supported"),
                        trackingStatus(rs.getString("query_sync_status")),
                        rs.getBoolean("path_supported"),
                        trackingStatus(rs.getString("path_sync_status"))
                ))
                .optional();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (existing.isEmpty()) {
            jdbcClient.sql("""
                            insert into shipment_tracking_snapshot(
                                shipment_id,
                                query_supported, query_sync_status,
                                path_supported, path_sync_status,
                                created_at, updated_at)
                            values (
                                :shipmentId,
                                :querySupported, :queryStatus,
                                :pathSupported, :pathStatus,
                                :createdAt, :updatedAt)
                            """)
                    .param("shipmentId", shipmentId)
                    .param("querySupported", querySupported)
                    .param("queryStatus", initialStatus(querySupported).name())
                    .param("pathSupported", pathSupported)
                    .param("pathStatus", initialStatus(pathSupported).name())
                    .param("createdAt", now)
                    .param("updatedAt", now)
                    .update();
            return;
        }
        SnapshotCapabilityRow row = existing.get();
        WechatTrackingSyncStatus queryStatus = capabilityStatus(
                querySupported, row.querySupported(), row.queryStatus()
        );
        WechatTrackingSyncStatus pathStatus = capabilityStatus(
                pathSupported, row.pathSupported(), row.pathStatus()
        );
        jdbcClient.sql("""
                        update shipment_tracking_snapshot
                        set query_supported = :querySupported,
                            query_sync_status = :queryStatus,
                            path_supported = :pathSupported,
                            path_sync_status = :pathStatus,
                            updated_at = :updatedAt
                        where shipment_id = :shipmentId
                        """)
                .param("querySupported", querySupported)
                .param("queryStatus", queryStatus.name())
                .param("pathSupported", pathSupported)
                .param("pathStatus", pathStatus.name())
                .param("updatedAt", now)
                .param("shipmentId", shipmentId)
                .update();
    }

    private WechatTrackingSyncStatus initialStatus(boolean supported) {
        return supported
                ? WechatTrackingSyncStatus.NOT_REQUESTED
                : WechatTrackingSyncStatus.UNSUPPORTED;
    }

    private WechatTrackingSyncStatus capabilityStatus(
            boolean supported,
            boolean previouslySupported,
            WechatTrackingSyncStatus previousStatus
    ) {
        if (!supported) {
            return WechatTrackingSyncStatus.UNSUPPORTED;
        }
        if (!previouslySupported || previousStatus == WechatTrackingSyncStatus.UNSUPPORTED) {
            return WechatTrackingSyncStatus.NOT_REQUESTED;
        }
        return previousStatus;
    }

    private TrackingContext lockAndLoadContext(long orderId, long shipmentId) {
        jdbcClient.sql("select id from shop_order where id = :orderId for update")
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Long lockedShipmentId = jdbcClient.sql("""
                        select id
                        from order_shipment
                        where order_id = :orderId and id = :shipmentId
                        for update
                        """)
                .param("orderId", orderId)
                .param("shipmentId", shipmentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        return loadContext(lockedShipmentId);
    }

    private TrackingContext loadContext(long shipmentId) {
        return jdbcClient.sql("""
                        select sh.id as shipment_id,
                               sh.order_id,
                               sh.logistics_type,
                               sh.express_company_code,
                               sh.express_company_name,
                               sh.tracking_no,
                               registration.registration_kind,
                               registration.status as registration_status,
                               registration.waybill_token,
                               electronic_waybill.provider_order_id,
                               electronic_waybill.payer_openid,
                               electronic_waybill.delivery_id as electronic_delivery_id,
                               electronic_waybill.waybill_id as electronic_waybill_no
                        from order_shipment sh
                        left join shipment_waybill_registration registration
                          on registration.shipment_id = sh.id
                        left join order_electronic_waybill electronic_waybill
                          on electronic_waybill.id = sh.electronic_waybill_id
                        where sh.id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new TrackingContext(
                        rs.getLong("shipment_id"),
                        rs.getLong("order_id"),
                        LogisticsType.fromValue(rs.getInt("logistics_type")),
                        defaultString(rs.getString("express_company_code")),
                        defaultString(rs.getString("express_company_name")),
                        defaultString(rs.getString("tracking_no")),
                        registrationKind(rs.getString("registration_kind")),
                        registrationStatus(rs.getString("registration_status")),
                        defaultString(rs.getString("waybill_token")),
                        defaultString(rs.getString("provider_order_id")),
                        defaultString(rs.getString("payer_openid")),
                        defaultString(rs.getString("electronic_delivery_id")),
                        defaultString(rs.getString("electronic_waybill_no"))
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private ShipmentTrackingResponse snapshot(long orderId) {
        return snapshot(orderId, latestShipmentId(orderId));
    }

    private ShipmentTrackingResponse snapshot(long orderId, long shipmentId) {
        Long ownedShipmentId = jdbcClient.sql("""
                        select id from order_shipment
                        where order_id = :orderId and id = :shipmentId
                        """)
                .param("orderId", orderId)
                .param("shipmentId", shipmentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        TrackingContext context = loadContext(ownedShipmentId);
        if (context.logisticsType() != LogisticsType.EXPRESS) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        Optional<SnapshotDetailRow> stored = jdbcClient.sql("""
                        select query_supported, query_sync_status, logistics_status,
                               query_error_code, query_error_message,
                               path_supported, path_sync_status,
                               path_error_code, path_error_message,
                               last_attempt_at, last_synced_at
                        from shipment_tracking_snapshot
                        where shipment_id = :shipmentId
                        """)
                .param("shipmentId", ownedShipmentId)
                .query((rs, rowNum) -> new SnapshotDetailRow(
                        rs.getBoolean("query_supported"),
                        trackingStatus(rs.getString("query_sync_status")),
                        rs.getObject("logistics_status", Integer.class),
                        blankToNull(rs.getString("query_error_code")),
                        blankToNull(rs.getString("query_error_message")),
                        rs.getBoolean("path_supported"),
                        trackingStatus(rs.getString("path_sync_status")),
                        blankToNull(rs.getString("path_error_code")),
                        blankToNull(rs.getString("path_error_message")),
                        rs.getObject("last_attempt_at", LocalDateTime.class),
                        rs.getObject("last_synced_at", LocalDateTime.class)
                ))
                .optional();
        boolean querySupported = querySupported(context);
        boolean pathSupported = pathSupported(context);
        SnapshotDetailRow row = stored.orElse(null);
        WechatTrackingSyncStatus queryStatus = effectiveStatus(
                querySupported,
                row == null ? false : row.querySupported(),
                row == null ? null : row.queryStatus()
        );
        WechatTrackingSyncStatus pathStatus = effectiveStatus(
                pathSupported,
                row == null ? false : row.pathSupported(),
                row == null ? null : row.pathStatus()
        );
        WechatLogisticsStatus logisticsStatus = row == null
                ? null
                : WechatLogisticsStatus.fromCode(row.logisticsStatus());
        List<ShipmentTrackingEventResponse> events = jdbcClient.sql("""
                        select action_time, action_type, action_message
                        from shipment_tracking_event
                        where shipment_id = :shipmentId
                        order by display_order, id
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new ShipmentTrackingEventResponse(
                        rs.getLong("action_time"),
                        rs.getInt("action_type"),
                        rs.getString("action_message")
                ))
                .list();
        return new ShipmentTrackingResponse(
                context.shipmentId(),
                context.orderId(),
                context.carrierCode(),
                context.carrierName(),
                context.trackingNo(),
                querySupported,
                queryStatus,
                logisticsStatus,
                logisticsStatus == null ? null : logisticsStatus.displayText(),
                row == null ? null : row.queryErrorCode(),
                row == null ? null : row.queryErrorMessage(),
                pathSupported,
                pathStatus,
                row == null ? null : row.pathErrorCode(),
                row == null ? null : row.pathErrorMessage(),
                querySupported,
                events,
                row == null ? null : row.lastAttemptAt(),
                row == null ? null : row.lastSyncedAt()
        );
    }

    private long latestShipmentId(long orderId) {
        return jdbcClient.sql("""
                        select id from order_shipment
                        where order_id = :orderId
                        order by package_no desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private WechatTrackingSyncStatus effectiveStatus(
            boolean supported,
            boolean storedSupported,
            WechatTrackingSyncStatus storedStatus
    ) {
        if (!supported) {
            return WechatTrackingSyncStatus.UNSUPPORTED;
        }
        if (!storedSupported || storedStatus == null || storedStatus == WechatTrackingSyncStatus.UNSUPPORTED) {
            return WechatTrackingSyncStatus.NOT_REQUESTED;
        }
        return storedStatus;
    }

    private void replacePathItems(long shipmentId, List<WechatTrackingPathItem> sourceItems) {
        jdbcClient.sql("delete from shipment_tracking_event where shipment_id = :shipmentId")
                .param("shipmentId", shipmentId)
                .update();
        List<StoredPathItem> items = normalizedPathItems(sourceItems);
        for (int index = 0; index < items.size(); index++) {
            StoredPathItem item = items.get(index);
            jdbcClient.sql("""
                            insert into shipment_tracking_event(
                                shipment_id, action_time, action_type, action_message,
                                message_digest, display_order, created_at)
                            values (
                                :shipmentId, :actionTime, :actionType, :actionMessage,
                                :messageDigest, :displayOrder, :createdAt)
                            """)
                    .param("shipmentId", shipmentId)
                    .param("actionTime", item.actionTime())
                    .param("actionType", item.actionType())
                    .param("actionMessage", item.actionMessage())
                    .param("messageDigest", item.messageDigest())
                    .param("displayOrder", index)
                    .param("createdAt", LocalDateTime.now(ZoneOffset.UTC))
                    .update();
        }
    }

    private List<StoredPathItem> normalizedPathItems(List<WechatTrackingPathItem> sourceItems) {
        if (sourceItems == null || sourceItems.isEmpty()) {
            return List.of();
        }
        List<WechatTrackingPathItem> sorted = sourceItems.stream()
                .filter(item -> item != null && item.actionTime() > 0)
                .sorted(Comparator.comparingLong(WechatTrackingPathItem::actionTime).reversed()
                        .thenComparing(Comparator.comparingInt(WechatTrackingPathItem::actionType).reversed()))
                .limit(properties.maxPathItems())
                .toList();
        Map<String, StoredPathItem> unique = new LinkedHashMap<>();
        for (WechatTrackingPathItem item : sorted) {
            String message = maskedMessage(item.actionMessage());
            if (!StringUtils.hasText(message)) {
                continue;
            }
            String digest = sha256(message);
            String identity = item.actionTime() + ":" + item.actionType() + ":" + digest;
            unique.putIfAbsent(identity, new StoredPathItem(
                    item.actionTime(), item.actionType(), message, digest
            ));
        }
        return new ArrayList<>(unique.values());
    }

    private String maskedMessage(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_ACTION_MESSAGE_LENGTH) {
            normalized = normalized.substring(0, MAX_ACTION_MESSAGE_LENGTH);
        }
        return MOBILE_PHONE.matcher(normalized).replaceAll("$1****$2");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private SourceCompletion queryCompletion(WechatTrackingQueryResult result) {
        if (result == null || result.outcome() == null) {
            return SourceCompletion.failure(
                    WechatTrackingSyncStatus.UNKNOWN,
                    null,
                    "AMBIGUOUS_RESULT",
                    "WeChat tracking query result is unknown"
            );
        }
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            WechatLogisticsStatus status = WechatLogisticsStatus.fromCode(result.logisticsStatus());
            if (status == null) {
                return SourceCompletion.failure(
                        WechatTrackingSyncStatus.UNKNOWN,
                        null,
                        "STATUS_INVALID",
                        "WeChat tracking query result is unknown"
                );
            }
            return new SourceCompletion(
                    WechatTrackingSyncStatus.SYNCED, status.code(), "", ""
            );
        }
        return failureCompletion(result.outcome(), result.errorCode(), result.errorMessage());
    }

    private SourceCompletion pathCompletion(WechatTrackingPathResult result) {
        if (result == null || result.outcome() == null) {
            return SourceCompletion.failure(
                    WechatTrackingSyncStatus.UNKNOWN,
                    null,
                    "AMBIGUOUS_RESULT",
                    "WeChat tracking query result is unknown"
            );
        }
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            return new SourceCompletion(WechatTrackingSyncStatus.SYNCED, null, "", "");
        }
        return failureCompletion(result.outcome(), result.errorCode(), result.errorMessage());
    }

    private SourceCompletion failureCompletion(
            WechatProviderOutcome outcome,
            String errorCode,
            String errorMessage
    ) {
        WechatTrackingSyncStatus status = switch (outcome) {
            case REJECTED -> WechatTrackingSyncStatus.FAILED;
            case UNKNOWN -> WechatTrackingSyncStatus.UNKNOWN;
            case UNAVAILABLE -> WechatTrackingSyncStatus.UNAVAILABLE;
            case SUCCESS -> throw new IllegalStateException("Success handled separately");
        };
        return SourceCompletion.failure(status, null, errorCode, errorMessage);
    }

    private boolean querySupported(TrackingContext context) {
        return context.logisticsType() == LogisticsType.EXPRESS
                && context.registrationStatus() == WaybillRegistrationStatus.REGISTERED
                && context.registrationKind() != null
                && StringUtils.hasText(context.waybillToken());
    }

    private boolean pathSupported(TrackingContext context) {
        return context.logisticsType() == LogisticsType.EXPRESS
                && !missing(
                context.providerOrderId(),
                context.payerOpenid(),
                context.electronicDeliveryId(),
                context.electronicWaybillNo()
        );
    }

    private void requireOwnedVisibleOrder(long orderId, long userId) {
        int count = jdbcClient.sql("""
                        select count(*)
                        from shop_order
                        where id = :orderId
                          and user_id = :userId
                          and app_deleted_at is null
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .query(Integer.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void requireOrder(long orderId) {
        int count = jdbcClient.sql("select count(*) from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        if (count != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private WechatTrackingSyncStatus trackingStatus(String value) {
        try {
            return WechatTrackingSyncStatus.valueOf(value);
        } catch (RuntimeException ex) {
            return WechatTrackingSyncStatus.UNKNOWN;
        }
    }

    private WaybillRegistrationKind registrationKind(String value) {
        try {
            return StringUtils.hasText(value) ? WaybillRegistrationKind.valueOf(value) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private WaybillRegistrationStatus registrationStatus(String value) {
        try {
            return StringUtils.hasText(value) ? WaybillRegistrationStatus.valueOf(value) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean missing(String... values) {
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return true;
            }
        }
        return false;
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record TrackingContext(
            long shipmentId,
            long orderId,
            LogisticsType logisticsType,
            String carrierCode,
            String carrierName,
            String trackingNo,
            WaybillRegistrationKind registrationKind,
            WaybillRegistrationStatus registrationStatus,
            String waybillToken,
            String providerOrderId,
            String payerOpenid,
            String electronicDeliveryId,
            String electronicWaybillNo
    ) {
    }

    private record SnapshotRow(
            boolean querySupported,
            WechatTrackingSyncStatus queryStatus,
            boolean pathSupported,
            WechatTrackingSyncStatus pathStatus,
            String claimToken,
            LocalDateTime claimedAt,
            LocalDateTime lastAttemptAt
    ) {
    }

    private record SnapshotCapabilityRow(
            boolean querySupported,
            WechatTrackingSyncStatus queryStatus,
            boolean pathSupported,
            WechatTrackingSyncStatus pathStatus
    ) {
    }

    private record SnapshotDetailRow(
            boolean querySupported,
            WechatTrackingSyncStatus queryStatus,
            Integer logisticsStatus,
            String queryErrorCode,
            String queryErrorMessage,
            boolean pathSupported,
            WechatTrackingSyncStatus pathStatus,
            String pathErrorCode,
            String pathErrorMessage,
            LocalDateTime lastAttemptAt,
            LocalDateTime lastSyncedAt
    ) {
    }

    private record StoredPathItem(
            long actionTime,
            int actionType,
            String actionMessage,
            String messageDigest
    ) {
    }

    private record SourceCompletion(
            WechatTrackingSyncStatus status,
            Integer logisticsStatus,
            String errorCode,
            String errorMessage
    ) {
        private static SourceCompletion unsupported() {
            return new SourceCompletion(
                    WechatTrackingSyncStatus.UNSUPPORTED, null, "", ""
            );
        }

        private static SourceCompletion failure(
                WechatTrackingSyncStatus status,
                Integer logisticsStatus,
                String errorCode,
                String errorMessage
        ) {
            return new SourceCompletion(
                    status,
                    logisticsStatus,
                    safe(errorCode, MAX_ERROR_CODE_LENGTH),
                    safe(errorMessage, MAX_ERROR_MESSAGE_LENGTH)
            );
        }

        private static String safe(String value, int maxLength) {
            if (!StringUtils.hasText(value)) {
                return "UNKNOWN";
            }
            String trimmed = value.trim();
            return trimmed.substring(0, Math.min(trimmed.length(), maxLength));
        }
    }
}
