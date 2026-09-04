package org.muybaby.shopserver.logistics.waybill.registration;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.LogisticsType;
import org.muybaby.shopserver.logistics.ShipmentSource;
import org.muybaby.shopserver.logistics.waybill.provider.WechatProviderOutcome;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillGoodsItem;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationRequest;
import org.muybaby.shopserver.logistics.waybill.provider.WechatWaybillRegistrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class WechatWaybillRegistrationStateStore {

    private static final Logger log = LoggerFactory.getLogger(WechatWaybillRegistrationStateStore.class);
    private static final int MAX_TOKEN_LENGTH = 1024;
    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final java.time.Duration CLAIM_TIMEOUT = java.time.Duration.ofMinutes(5);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public WechatWaybillRegistrationStateStore(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<WaybillRegistrationClaim> claim(long shipmentId) {
        Optional<WaybillRegistrationClaim> claim = transactionTemplate.execute(
                status -> claimInTransaction(shipmentId)
        );
        return claim == null ? Optional.empty() : claim;
    }

    public long requireEligibleShipmentForOwner(long orderId, long userId) {
        Long ownerId = jdbcClient.sql("select user_id from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (ownerId != userId) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return requireEligibleShipmentForOrder(orderId);
    }

    public long requireEligibleShipmentForOwner(long orderId, long shipmentId, long userId) {
        Long ownerId = jdbcClient.sql("select user_id from shop_order where id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (ownerId != userId) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return requireEligibleShipmentForOrder(orderId, shipmentId);
    }

    public long requireEligibleShipmentForOrder(long orderId) {
        ShipmentIdentity shipment = jdbcClient.sql("""
                        select id, logistics_type, express_company_code, tracking_no
                        from order_shipment
                        where order_id = :orderId
                        order by package_no desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ShipmentIdentity(
                        rs.getLong("id"),
                        LogisticsType.fromValue(rs.getInt("logistics_type")),
                        rs.getString("express_company_code"),
                        rs.getString("tracking_no")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!WaybillRegistrationSummary.trackingSupported(
                shipment.logisticsType(), shipment.expressCompanyCode(), shipment.trackingNo()
        )) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return shipment.shipmentId();
    }

    public long requireEligibleShipmentForOrder(long orderId, long shipmentId) {
        ShipmentIdentity shipment = jdbcClient.sql("""
                        select id, logistics_type, express_company_code, tracking_no
                        from order_shipment
                        where order_id = :orderId and id = :shipmentId
                        """)
                .param("orderId", orderId)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new ShipmentIdentity(
                        rs.getLong("id"),
                        LogisticsType.fromValue(rs.getInt("logistics_type")),
                        rs.getString("express_company_code"),
                        rs.getString("tracking_no")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        if (!WaybillRegistrationSummary.trackingSupported(
                shipment.logisticsType(), shipment.expressCompanyCode(), shipment.trackingNo()
        )) {
            throw new BusinessException(ErrorCode.ORDER_STATE_CONFLICT);
        }
        return shipment.shipmentId();
    }

    public Optional<String> registeredToken(long shipmentId) {
        return jdbcClient.sql("""
                        select waybill_token
                        from shipment_waybill_registration
                        where shipment_id = :shipmentId
                          and status = :status
                        """)
                .param("shipmentId", shipmentId)
                .param("status", WaybillRegistrationStatus.REGISTERED.name())
                .query(String.class)
                .optional()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(token -> token.length() <= MAX_TOKEN_LENGTH);
    }

    public boolean complete(
            WaybillRegistrationClaim claim,
            WechatWaybillRegistrationResult providerResult
    ) {
        Completion completion = completion(providerResult);
        Boolean updated = transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            int count = jdbcClient.sql("""
                            update shipment_waybill_registration
                            set status = :status,
                                waybill_token = :waybillToken,
                                last_error_code = :errorCode,
                                last_error_message = :errorMessage,
                                claim_token = null,
                                claimed_at = null,
                                registered_at = case
                                    when :registered then :registeredAt
                                    else registered_at
                                end,
                                updated_at = :updatedAt
                            where shipment_id = :shipmentId
                              and status = :registering
                              and claim_token = :claimToken
                            """)
                    .param("status", completion.status().name())
                    .param("waybillToken", completion.token())
                    .param("errorCode", completion.errorCode())
                    .param("errorMessage", completion.errorMessage())
                    .param("registered", completion.status() == WaybillRegistrationStatus.REGISTERED)
                    .param("registeredAt", now)
                    .param("updatedAt", now)
                    .param("shipmentId", claim.shipmentId())
                    .param("registering", WaybillRegistrationStatus.REGISTERING.name())
                    .param("claimToken", claim.claimToken())
                    .update();
            return count == 1;
        });
        boolean completed = Boolean.TRUE.equals(updated);
        if (!completed) {
            log.warn(
                    "WeChat waybill registration completion lost claim: shipmentId={}, outcome={}",
                    claim.shipmentId(), completion.status()
            );
        }
        return completed;
    }

    private Optional<WaybillRegistrationClaim> claimInTransaction(long shipmentId) {
        RegistrationContext context = lockAndLoadContext(shipmentId);
        if (!WaybillRegistrationSummary.trackingSupported(
                context.logisticsType(), context.deliveryId(), context.waybillId()
        )) {
            return Optional.empty();
        }

        WaybillRegistrationKind initialKind = sourcePolicy(context);
        jdbcClient.sql("""
                        insert into shipment_waybill_registration(
                            shipment_id, registration_kind, status,
                            waybill_token, last_error_code, last_error_message,
                            attempt_count, created_at, updated_at)
                        values (
                            :shipmentId, :registrationKind, :status,
                            '', '', '', 0, :createdAt, :updatedAt)
                        on duplicate key update shipment_id = shipment_id
                        """)
                .param("shipmentId", shipmentId)
                .param("registrationKind", initialKind.name())
                .param("status", WaybillRegistrationStatus.PENDING.name())
                .param("createdAt", LocalDateTime.now(ZoneOffset.UTC))
                .param("updatedAt", LocalDateTime.now(ZoneOffset.UTC))
                .update();

        if (WaybillRegistrationSummary.isSandboxElectronicWaybill(context.electronicWaybillMode())) {
            markSkipped(shipmentId);
            return Optional.empty();
        }

        RegistrationRow row = jdbcClient.sql("""
                        select registration_kind, status
                        from shipment_waybill_registration
                        where shipment_id = :shipmentId
                        for update
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new RegistrationRow(
                        WaybillRegistrationKind.valueOf(rs.getString("registration_kind")),
                        WaybillRegistrationStatus.valueOf(rs.getString("status"))
                ))
                .single();
        if (row.status() == WaybillRegistrationStatus.REGISTERED
                || row.status() == WaybillRegistrationStatus.SKIPPED) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String claimToken = UUID.randomUUID().toString();
        int claimed = jdbcClient.sql("""
                        update shipment_waybill_registration
                        set registration_kind = :registrationKind,
                            status = :registering,
                            waybill_token = '',
                            last_error_code = '',
                            last_error_message = '',
                            claim_token = :claimToken,
                            claimed_at = :claimedAt,
                            attempt_count = attempt_count + 1,
                            last_attempt_at = :lastAttemptAt,
                            updated_at = :updatedAt
                        where shipment_id = :shipmentId
                          and (
                              status in (:claimableStatuses)
                              or (
                                  status = :registering
                                  and (claimed_at is null or claimed_at <= :staleBefore)
                              )
                          )
                        """)
                .param("registrationKind", initialKind.name())
                .param("registering", WaybillRegistrationStatus.REGISTERING.name())
                .param("claimToken", claimToken)
                .param("claimedAt", now)
                .param("lastAttemptAt", now)
                .param("updatedAt", now)
                .param("shipmentId", shipmentId)
                .param("claimableStatuses", List.of(
                        WaybillRegistrationStatus.PENDING.name(),
                        WaybillRegistrationStatus.FAILED.name(),
                        WaybillRegistrationStatus.UNKNOWN.name(),
                        WaybillRegistrationStatus.UNAVAILABLE.name()
                ))
                .param("staleBefore", now.minus(CLAIM_TIMEOUT))
                .update();
        if (claimed != 1) {
            return Optional.empty();
        }

        PaymentIdentity payment = loadPaymentIdentity(context.orderId());
        List<WechatWaybillGoodsItem> goods = loadGoods(context.shipmentId());
        WechatWaybillRegistrationRequest request = new WechatWaybillRegistrationRequest(
                context.shipmentId(),
                payment.openid(),
                context.senderPhone(),
                context.receiverPhone(),
                context.waybillId(),
                context.deliveryId(),
                payment.transactionId(),
                "pages/order/detail/detail?order_id=" + context.orderId(),
                goods
        );
        return Optional.of(new WaybillRegistrationClaim(
                shipmentId, claimToken, initialKind, request
        ));
    }

    private RegistrationContext lockAndLoadContext(long shipmentId) {
        Long orderId = jdbcClient.sql("""
                        select order_id
                        from order_shipment
                        where id = :shipmentId
                        """)
                .param("shipmentId", shipmentId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        jdbcClient.sql("""
                        select id
                        from shop_order
                        where id = :orderId
                        for update
                        """)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        jdbcClient.sql("""
                        select id
                        from order_shipment
                        where id = :shipmentId and order_id = :orderId
                        for update
                        """)
                .param("shipmentId", shipmentId)
                .param("orderId", orderId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
        return jdbcClient.sql("""
                        select sh.id as shipment_id,
                               sh.order_id,
                               sh.logistics_type,
                               sh.shipment_source,
                               sh.express_company_code,
                               sh.tracking_no,
                               coalesce(ew.mode, '') as electronic_waybill_mode,
                               o.receiver_phone,
                               coalesce(nullif(ew.sender_mobile, ''), setting.sender_mobile, '') as sender_phone,
                               coalesce(setting.message_enabled, false) as message_enabled
                        from order_shipment sh
                        join shop_order o on o.id = sh.order_id
                        left join order_electronic_waybill ew on ew.id = sh.electronic_waybill_id
                        left join wechat_express_setting setting on setting.id = 1
                        where sh.id = :shipmentId and sh.order_id = :orderId
                        """)
                .param("shipmentId", shipmentId)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new RegistrationContext(
                        rs.getLong("shipment_id"),
                        rs.getLong("order_id"),
                        LogisticsType.fromValue(rs.getInt("logistics_type")),
                        shipmentSource(rs.getString("shipment_source")),
                        defaultString(rs.getString("express_company_code")),
                        defaultString(rs.getString("tracking_no")),
                        defaultString(rs.getString("electronic_waybill_mode")),
                        defaultString(rs.getString("sender_phone")),
                        defaultString(rs.getString("receiver_phone")),
                        rs.getBoolean("message_enabled")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE_CONFLICT));
    }

    private void markSkipped(long shipmentId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbcClient.sql("""
                        update shipment_waybill_registration
                        set registration_kind = :registrationKind,
                            status = :status,
                            waybill_token = '',
                            last_error_code = '',
                            last_error_message = '',
                            claim_token = null,
                            claimed_at = null,
                            registered_at = null,
                            updated_at = :updatedAt
                        where shipment_id = :shipmentId
                        """)
                .param("registrationKind", WaybillRegistrationKind.TRACE.name())
                .param("status", WaybillRegistrationStatus.SKIPPED.name())
                .param("updatedAt", now)
                .param("shipmentId", shipmentId)
                .update();
    }

    private PaymentIdentity loadPaymentIdentity(long orderId) {
        return jdbcClient.sql("""
                        select payer_openid, transaction_id
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAID'
                        order by paid_at desc, updated_at desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentIdentity(
                        defaultString(rs.getString("payer_openid")),
                        defaultString(rs.getString("transaction_id"))
                ))
                .optional()
                .orElse(new PaymentIdentity("", ""));
    }

    private List<WechatWaybillGoodsItem> loadGoods(long shipmentId) {
        return jdbcClient.sql("""
                        select item.product_title,
                               coalesce(
                                   nullif(item.display_image, ''),
                                   nullif(item.sku_image, ''),
                                   nullif(item.main_image, ''),
                                   ''
                               ) as goods_image_url
                        from order_shipment_item shipment_item
                        join order_item item on item.id = shipment_item.order_item_id
                        where shipment_item.shipment_id = :shipmentId
                        order by item.id
                        """)
                .param("shipmentId", shipmentId)
                .query((rs, rowNum) -> new WechatWaybillGoodsItem(
                        defaultString(rs.getString("product_title")),
                        defaultString(rs.getString("goods_image_url"))
                ))
                .list();
    }

    private WaybillRegistrationKind sourcePolicy(RegistrationContext context) {
        if (context.shipmentSource() == ShipmentSource.WECHAT_WAYBILL) {
            return WaybillRegistrationKind.FOLLOW;
        }
        return context.messageEnabled()
                ? WaybillRegistrationKind.FOLLOW
                : WaybillRegistrationKind.TRACE;
    }

    private Completion completion(WechatWaybillRegistrationResult result) {
        if (result == null || result.outcome() == null) {
            return failureCompletion(
                    WaybillRegistrationStatus.UNKNOWN,
                    "AMBIGUOUS_RESULT",
                    "WeChat waybill registration result is unknown"
            );
        }
        if (result.outcome() == WechatProviderOutcome.SUCCESS) {
            String token = result.waybillToken() == null ? "" : result.waybillToken().trim();
            if (StringUtils.hasText(token) && token.length() <= MAX_TOKEN_LENGTH) {
                return new Completion(WaybillRegistrationStatus.REGISTERED, token, "", "");
            }
            return failureCompletion(
                    WaybillRegistrationStatus.UNKNOWN,
                    "WAYBILL_TOKEN_INVALID",
                    "WeChat waybill registration result is unknown"
            );
        }
        return switch (result.outcome()) {
            case REJECTED -> failureCompletion(
                    WaybillRegistrationStatus.FAILED,
                    result.errorCode(),
                    "WeChat waybill registration was rejected"
            );
            case UNKNOWN -> failureCompletion(
                    WaybillRegistrationStatus.UNKNOWN,
                    result.errorCode(),
                    "WeChat waybill registration result is unknown"
            );
            case UNAVAILABLE -> failureCompletion(
                    WaybillRegistrationStatus.UNAVAILABLE,
                    result.errorCode(),
                    "WeChat waybill registration is unavailable"
            );
            case SUCCESS -> throw new IllegalStateException("Handled above");
        };
    }

    private Completion failureCompletion(
            WaybillRegistrationStatus status,
            String errorCode,
            String errorMessage
    ) {
        return new Completion(status, "", safeErrorCode(errorCode), errorMessage);
    }

    private String safeErrorCode(String value) {
        if (!StringUtils.hasText(value)) {
            return "UNKNOWN";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_ERROR_CODE_LENGTH));
    }

    private ShipmentSource shipmentSource(String value) {
        try {
            return ShipmentSource.valueOf(value);
        } catch (RuntimeException ex) {
            return ShipmentSource.MANUAL;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private record ShipmentIdentity(
            long shipmentId,
            LogisticsType logisticsType,
            String expressCompanyCode,
            String trackingNo
    ) {
    }

    private record RegistrationContext(
            long shipmentId,
            long orderId,
            LogisticsType logisticsType,
            ShipmentSource shipmentSource,
            String deliveryId,
            String waybillId,
            String electronicWaybillMode,
            String senderPhone,
            String receiverPhone,
            boolean messageEnabled
    ) {
    }

    private record RegistrationRow(
            WaybillRegistrationKind kind,
            WaybillRegistrationStatus status
    ) {
    }

    private record PaymentIdentity(String openid, String transactionId) {
    }

    private record Completion(
            WaybillRegistrationStatus status,
            String token,
            String errorCode,
            String errorMessage
    ) {
    }
}
