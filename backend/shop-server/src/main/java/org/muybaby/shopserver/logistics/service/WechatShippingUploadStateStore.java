package org.muybaby.shopserver.logistics.service;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.DeliveryMode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.WechatProviderMode;
import org.muybaby.shopserver.logistics.WechatShippingUploadStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Component
public class WechatShippingUploadStateStore {

    private static final int MAX_ITEM_DESC_CODE_POINTS = 120;
    private static final String SF_DELIVERY_ID = "SF";
    static final Duration STALE_THRESHOLD = Duration.ofMinutes(10);
    static final String STALE_ERROR_CODE = "ATTEMPT_OUTCOME_UNKNOWN";
    static final String STALE_ERROR_MESSAGE = "Previous WeChat shipping attempt outcome is unknown";

    private final JdbcClient jdbcClient;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    public WechatShippingUploadStateStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.required = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean claimInitial(long shipmentId, LocalDateTime now) {
        return Boolean.TRUE.equals(required.execute(status -> jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :uploading,
                            wechat_error_code = '',
                            wechat_error_message = '',
                            last_attempt_at = :now,
                            updated_at = :now
                        where id = :shipmentId
                          and wechat_upload_status = :skipped
                        """)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("skipped", WechatShippingUploadStatus.SKIPPED.name())
                .param("now", now)
                .param("shipmentId", shipmentId)
                .update() == 1));
    }

    public long claimOperatorRetry(long orderId, boolean uploadEnabled, LocalDateTime now) {
        return Objects.requireNonNull(required.execute(status -> {
            String orderStatus = jdbcClient.sql("""
                            select status from shop_order where id = :orderId for update
                            """)
                    .param("orderId", orderId)
                    .query(String.class)
                    .optional()
                    .orElseThrow(this::conflict);
            if (!"SHIPPED".equals(orderStatus) || !uploadEnabled) {
                throw conflict();
            }

            RetryCandidate candidate = jdbcClient.sql("""
                            select id, logistics_type, delivery_mode, item_desc,
                                   express_company_code, tracking_no,
                                   consignor_contact, receiver_contact, status
                            from order_shipment
                            where order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query((rs, rowNum) -> new RetryCandidate(
                            rs.getLong("id"),
                            rs.getInt("logistics_type"),
                            rs.getInt("delivery_mode"),
                            rs.getString("item_desc"),
                            rs.getString("express_company_code"),
                            rs.getString("tracking_no"),
                            rs.getString("consignor_contact"),
                            rs.getString("receiver_contact"),
                            rs.getString("status")
                    ))
                    .optional()
                    .orElseThrow(this::conflict);
            if (!candidate.reconstructable()) {
                throw conflict();
            }

            int claimed = jdbcClient.sql("""
                            update order_shipment
                            set wechat_upload_status = :uploading,
                                wechat_error_code = '',
                                wechat_error_message = '',
                                retry_count = retry_count + 1,
                                last_attempt_at = :now,
                                updated_at = :now
                            where id = :shipmentId
                              and wechat_upload_status in (:failed, :unavailable, :skipped)
                            """)
                    .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                    .param("failed", WechatShippingUploadStatus.FAILED.name())
                    .param("unavailable", WechatShippingUploadStatus.UNAVAILABLE.name())
                    .param("skipped", WechatShippingUploadStatus.SKIPPED.name())
                    .param("now", now)
                    .param("shipmentId", candidate.shipmentId())
                    .update();
            if (claimed != 1) {
                throw conflict();
            }
            return candidate.shipmentId();
        }));
    }

    public AttemptContext prepareAttempt(long shipmentId, WechatProviderMode providerMode) {
        return Objects.requireNonNull(required.execute(status -> {
            AttemptContext current = loadAttemptContext(shipmentId);
            String uploadTime = nextUploadTime(current.uploadTime());
            LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
            int updated = jdbcClient.sql("""
                            update order_shipment
                            set upload_time = :uploadTime,
                                wechat_provider_mode = :providerMode,
                                last_attempt_at = :now,
                                updated_at = :now
                            where id = :shipmentId
                              and wechat_upload_status = :uploading
                            """)
                    .param("uploadTime", uploadTime)
                    .param("providerMode", providerMode.name())
                    .param("now", now)
                    .param("shipmentId", shipmentId)
                    .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                    .update();
            if (updated != 1) {
                throw conflict();
            }
            return current.withUploadTime(uploadTime);
        }));
    }

    public boolean writeTerminal(
            long shipmentId,
            WechatProviderMode providerMode,
            WechatShippingUploadStatus uploadStatus,
            String errorCode,
            String errorMessage,
            LocalDateTime attemptedAt,
            LocalDateTime uploadedAt
    ) {
        return Boolean.TRUE.equals(required.execute(status -> jdbcClient.sql("""
                        update order_shipment
                        set wechat_provider_mode = :providerMode,
                            wechat_upload_status = :uploadStatus,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = :uploadedAt,
                            last_attempt_at = :attemptedAt,
                            updated_at = :attemptedAt
                        where id = :shipmentId
                          and wechat_upload_status = :uploading
                        """)
                .param("providerMode", providerMode.name())
                .param("uploadStatus", uploadStatus.name())
                .param("errorCode", defaultString(errorCode))
                .param("errorMessage", defaultString(errorMessage))
                .param("uploadedAt", uploadedAt)
                .param("attemptedAt", attemptedAt)
                .param("shipmentId", shipmentId)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .update() == 1));
    }

    public void fallbackUnknown(long shipmentId, WechatProviderMode providerMode, LocalDateTime now) {
        requiresNew.executeWithoutResult(status -> jdbcClient.sql("""
                        update order_shipment
                        set wechat_provider_mode = :providerMode,
                            wechat_upload_status = :unknown,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = null,
                            last_attempt_at = :now,
                            updated_at = :now
                        where id = :shipmentId
                          and wechat_upload_status = :uploading
                        """)
                .param("providerMode", providerMode.name())
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("errorCode", STALE_ERROR_CODE)
                .param("errorMessage", STALE_ERROR_MESSAGE)
                .param("now", now)
                .param("shipmentId", shipmentId)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .update());
    }

    public boolean reconcileStaleByOrder(long orderId, LocalDateTime now) {
        return Boolean.TRUE.equals(required.execute(status -> reconcileByPredicate(
                "order_id = :identifier", orderId, now
        )));
    }

    public boolean reconcileStaleByShipment(long shipmentId, LocalDateTime now) {
        return Boolean.TRUE.equals(required.execute(status -> reconcileByPredicate(
                "id = :identifier", shipmentId, now
        )));
    }

    public int reconcileStaleBatch(LocalDateTime now) {
        return Objects.requireNonNull(required.execute(status -> {
            LocalDateTime cutoff = now.minus(STALE_THRESHOLD);
            List<Long> ids = jdbcClient.sql("""
                            select id
                            from order_shipment
                            where wechat_upload_status = :uploading
                              and coalesce(last_attempt_at, updated_at, created_at) < :cutoff
                            order by coalesce(last_attempt_at, updated_at, created_at), id
                            limit 100
                            """)
                    .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                    .param("cutoff", cutoff)
                    .query(Long.class)
                    .list();
            int reconciled = 0;
            for (Long id : ids) {
                if (reconcileByPredicate("id = :identifier", id, now)) {
                    reconciled++;
                }
            }
            return reconciled;
        }));
    }

    private boolean reconcileByPredicate(String predicate, long identifier, LocalDateTime now) {
        LocalDateTime cutoff = now.minus(STALE_THRESHOLD);
        return jdbcClient.sql("""
                        update order_shipment
                        set wechat_upload_status = :unknown,
                            wechat_error_code = :errorCode,
                            wechat_error_message = :errorMessage,
                            wechat_uploaded_at = null,
                            last_attempt_at = :now,
                            updated_at = :now
                        where %s
                          and wechat_upload_status = :uploading
                          and coalesce(last_attempt_at, updated_at, created_at) < :cutoff
                        """.formatted(predicate))
                .param("identifier", identifier)
                .param("unknown", WechatShippingUploadStatus.UNKNOWN.name())
                .param("errorCode", STALE_ERROR_CODE)
                .param("errorMessage", STALE_ERROR_MESSAGE)
                .param("now", now)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .param("cutoff", cutoff)
                .update() == 1;
    }

    private AttemptContext loadAttemptContext(long shipmentId) {
        AttemptContext shipment = jdbcClient.sql("""
                        select sh.id as shipment_id, sh.order_id, sh.logistics_type, sh.delivery_mode,
                               sh.item_desc, sh.express_company_code, sh.tracking_no,
                               sh.consignor_contact, sh.receiver_contact, sh.upload_time,
                               o.user_id, u.openid
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        join app_user u on u.id = o.user_id
                        where sh.id = :shipmentId
                          and sh.wechat_upload_status = :uploading
                        """)
                .param("shipmentId", shipmentId)
                .param("uploading", WechatShippingUploadStatus.UPLOADING.name())
                .query(this::mapAttemptContext)
                .optional()
                .orElseThrow(this::conflict);
        String transactionId = jdbcClient.sql("""
                        select transaction_id
                        from payment_order
                        where order_id = :orderId and status = 'PAID'
                        order by updated_at desc, id desc
                        limit 1
                        """)
                .param("orderId", shipment.orderId())
                .query(String.class)
                .optional()
                .orElse("");
        return shipment.withTransactionId(transactionId);
    }

    private AttemptContext mapAttemptContext(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptContext(
                rs.getLong("shipment_id"),
                rs.getLong("order_id"),
                LogisticsType.fromValue(rs.getInt("logistics_type")),
                DeliveryMode.fromValue(rs.getInt("delivery_mode")),
                rs.getString("item_desc"),
                rs.getString("express_company_code"),
                rs.getString("tracking_no"),
                rs.getString("consignor_contact"),
                rs.getString("receiver_contact"),
                "",
                rs.getString("openid"),
                rs.getString("upload_time")
        );
    }

    private String nextUploadTime(String previous) {
        OffsetDateTime candidate = OffsetDateTime.now(ZoneOffset.UTC);
        if (StringUtils.hasText(previous)) {
            try {
                OffsetDateTime previousTime = OffsetDateTime.parse(previous, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                if (!candidate.isAfter(previousTime)) {
                    candidate = previousTime.plusNanos(1);
                }
            } catch (DateTimeParseException ignored) {
                // A malformed legacy value is safely replaced before any dispatch.
            }
        }
        return candidate.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record RetryCandidate(
            long shipmentId,
            int logisticsType,
            int deliveryMode,
            String itemDesc,
            String expressCompanyCode,
            String trackingNo,
            String consignorContact,
            String receiverContact,
            String localStatus
    ) {
        boolean reconstructable() {
            try {
                LogisticsType type = LogisticsType.fromValue(logisticsType);
                if (DeliveryMode.fromValue(deliveryMode) != DeliveryMode.UNIFIED
                        || !StringUtils.hasText(itemDesc)
                        || itemDesc.codePointCount(0, itemDesc.length()) > MAX_ITEM_DESC_CODE_POINTS
                        || !"SHIPPED".equals(localStatus)) {
                    return false;
                }
                if (type != LogisticsType.EXPRESS) {
                    return true;
                }
                if (!StringUtils.hasText(expressCompanyCode) || !StringUtils.hasText(trackingNo)) {
                    return false;
                }
                return !SF_DELIVERY_ID.equals(expressCompanyCode)
                        || StringUtils.hasText(consignorContact)
                        || StringUtils.hasText(receiverContact);
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }

    public record AttemptContext(
            long shipmentId,
            long orderId,
            LogisticsType logisticsType,
            DeliveryMode deliveryMode,
            String itemDesc,
            String expressCompanyCode,
            String trackingNo,
            String consignorContact,
            String receiverContact,
            String transactionId,
            String openid,
            String uploadTime
    ) {
        AttemptContext withTransactionId(String value) {
            return new AttemptContext(
                    shipmentId, orderId, logisticsType, deliveryMode, itemDesc,
                    expressCompanyCode, trackingNo, consignorContact, receiverContact,
                    value, openid, uploadTime
            );
        }

        AttemptContext withUploadTime(String value) {
            return new AttemptContext(
                    shipmentId, orderId, logisticsType, deliveryMode, itemDesc,
                    expressCompanyCode, trackingNo, consignorContact, receiverContact,
                    transactionId, openid, value
            );
        }

        List<String> knownSecrets() {
            return Stream.of(
                            transactionId, openid, itemDesc, trackingNo,
                            consignorContact, receiverContact
                    )
                    .filter(StringUtils::hasText)
                    .toList();
        }
    }
}
