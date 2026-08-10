package org.muybaby.shopserver.logistics.waybill;

import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressAccount;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressEffectiveConfig;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressParcel;
import org.muybaby.shopserver.logistics.waybill.config.WechatExpressSender;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class ElectronicWaybillStateStore {

    private final JdbcClient jdbcClient;

    public ElectronicWaybillStateStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public OrderSnapshot loadOrderSnapshot(
            long orderId,
            boolean forUpdate,
            List<String> blockingAfterSaleStatuses
    ) {
        String lock = forUpdate ? " for update" : "";
        OrderRow order = jdbcClient.sql("""
                        select id, order_no, status,
                               receiver_name, receiver_phone,
                               receiver_province, receiver_city, receiver_district,
                               receiver_detail_address, receiver_location_name, receiver_doorplate
                        from shop_order
                        where id = :orderId
                        """ + lock)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderRow(
                        rs.getLong("id"),
                        rs.getString("order_no"),
                        rs.getString("status"),
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("receiver_province"),
                        rs.getString("receiver_city"),
                        rs.getString("receiver_district"),
                        rs.getString("receiver_detail_address"),
                        rs.getString("receiver_location_name"),
                        rs.getString("receiver_doorplate")
                ))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        PaymentSnapshot payment = jdbcClient.sql("""
                        select id, transaction_id, payer_openid
                        from payment_order
                        where order_id = :orderId
                          and status = 'PAID'
                        order by updated_at desc, id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new PaymentSnapshot(
                        rs.getLong("id"),
                        rs.getString("transaction_id"),
                        rs.getString("payer_openid")
                ))
                .optional()
                .orElse(null);

        List<ItemSnapshot> items = jdbcClient.sql("""
                        select id, product_title, product_subtitle,
                               main_image, sku_image, display_image, spec_text,
                               quantity - refunded_quantity as quantity
                        from order_item
                        where order_id = :orderId and quantity > refunded_quantity
                        order by id
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ItemSnapshot(
                        rs.getLong("id"),
                        rs.getString("product_title"),
                        rs.getString("product_subtitle"),
                        rs.getString("main_image"),
                        rs.getString("sku_image"),
                        rs.getString("display_image"),
                        rs.getString("spec_text"),
                        rs.getInt("quantity")
                ))
                .list();

        boolean shipmentExists = count("""
                        select count(*) from order_shipment where order_id = :orderId
                        """, orderId) > 0;
        Integer blockingAfterSaleCount = jdbcClient.sql("""
                        select count(*)
                        from after_sale_request
                        where order_id = :orderId and status in (:statuses)
                        """)
                .param("orderId", orderId)
                .param("statuses", blockingAfterSaleStatuses)
                .query(Integer.class)
                .single();
        return new OrderSnapshot(
                order,
                payment,
                List.copyOf(items),
                shipmentExists,
                blockingAfterSaleCount != null && blockingAfterSaleCount > 0
        );
    }

    public Optional<AttemptRow> findByIdempotency(long orderId, String idempotencyKey) {
        return jdbcClient.sql(ATTEMPT_SELECT + """
                        where order_id = :orderId and idempotency_key = :idempotencyKey
                        """)
                .param("orderId", orderId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapAttempt)
                .optional();
    }

    public AttemptRow requireAttempt(long orderId, long recordId, boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        return jdbcClient.sql(ATTEMPT_SELECT + """
                        where order_id = :orderId and id = :recordId
                        """ + lock)
                .param("orderId", orderId)
                .param("recordId", recordId)
                .query(this::mapAttempt)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public Optional<AttemptRow> currentAttempt(long orderId) {
        return jdbcClient.sql(ATTEMPT_SELECT + """
                        where order_id = :orderId
                        order by case when status in ('CREATING','CREATED','CANCELING','UNKNOWN')
                                      then 0 else 1 end,
                                 id desc
                        limit 1
                        """)
                .param("orderId", orderId)
                .query(this::mapAttempt)
                .optional();
    }

    public List<AttemptRow> list(long orderId) {
        return jdbcClient.sql(ATTEMPT_SELECT + """
                        where order_id = :orderId
                        order by id desc
                        """)
                .param("orderId", orderId)
                .query(this::mapAttempt)
                .list();
    }

    public boolean activeAttemptExists(long orderId) {
        Integer count = jdbcClient.sql("""
                        select count(*)
                        from order_electronic_waybill
                        where order_id = :orderId
                          and status in ('CREATING','CREATED','CANCELING','UNKNOWN')
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public int nextAttemptNo(long orderId) {
        Integer next = jdbcClient.sql("""
                        select coalesce(max(attempt_no), 0) + 1
                        from order_electronic_waybill
                        where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return next == null ? 1 : next;
    }

    public AttemptRow insertCreating(CreateInsert insert, LocalDateTime now) {
        WechatExpressEffectiveConfig config = insert.config();
        WechatExpressAccount account = config.account();
        WechatExpressSender sender = config.sender();
        WechatExpressParcel parcel = insert.parcel();
        OrderRow order = insert.order().order();
        PaymentSnapshot payment = insert.order().payment();
        jdbcClient.sql("""
                        insert into order_electronic_waybill(
                            order_id, attempt_no, idempotency_key, request_digest, provider_order_id,
                            mode, delivery_id, delivery_name, biz_id, service_type, service_name,
                            status, pending_operation, waybill_id,
                            parcel_count, weight_kg, length_cm, width_cm, height_cm,
                            custom_remark, expected_pickup_time,
                            sender_name, sender_mobile, sender_company, sender_province,
                            sender_city, sender_district, sender_detail_address,
                            receiver_name, receiver_phone, receiver_province, receiver_city,
                            receiver_district, receiver_detail_address,
                            receiver_location_name, receiver_doorplate,
                            payment_order_id, payer_openid,
                            upstream_attempt_count, last_attempt_at, created_by,
                            created_at, updated_at)
                        values(
                            :orderId, :attemptNo, :idempotencyKey, :requestDigest, :providerOrderId,
                            :mode, :deliveryId, :deliveryName, :bizId, :serviceType, :serviceName,
                            'CREATING', 'CREATE', '',
                            :parcelCount, :weightKg, :lengthCm, :widthCm, :heightCm,
                            :remark, :expectTime,
                            :senderName, :senderMobile, :senderCompany, :senderProvince,
                            :senderCity, :senderDistrict, :senderDetailAddress,
                            :receiverName, :receiverPhone, :receiverProvince, :receiverCity,
                            :receiverDistrict, :receiverDetailAddress,
                            :receiverLocationName, :receiverDoorplate,
                            :paymentOrderId, :payerOpenid,
                            1, :now, :createdBy, :now, :now)
                        """)
                .param("orderId", order.id())
                .param("attemptNo", insert.attemptNo())
                .param("idempotencyKey", insert.idempotencyKey())
                .param("requestDigest", insert.requestDigest())
                .param("providerOrderId", insert.providerOrderId())
                .param("mode", config.mode().name())
                .param("deliveryId", account.deliveryId())
                .param("deliveryName", account.deliveryName())
                .param("bizId", account.bizId())
                .param("serviceType", account.serviceType())
                .param("serviceName", account.serviceName())
                .param("parcelCount", parcel.count())
                .param("weightKg", parcel.weightKg())
                .param("lengthCm", parcel.lengthCm())
                .param("widthCm", parcel.widthCm())
                .param("heightCm", parcel.heightCm())
                .param("remark", insert.remark())
                .param("expectTime", insert.expectTime())
                .param("senderName", sender.name())
                .param("senderMobile", sender.mobile())
                .param("senderCompany", sender.company())
                .param("senderProvince", sender.province())
                .param("senderCity", sender.city())
                .param("senderDistrict", sender.district())
                .param("senderDetailAddress", sender.detailAddress())
                .param("receiverName", order.receiverName())
                .param("receiverPhone", order.receiverPhone())
                .param("receiverProvince", order.receiverProvince())
                .param("receiverCity", order.receiverCity())
                .param("receiverDistrict", order.receiverDistrict())
                .param("receiverDetailAddress", order.receiverDetailAddress())
                .param("receiverLocationName", order.receiverLocationName())
                .param("receiverDoorplate", order.receiverDoorplate())
                .param("paymentOrderId", payment.id())
                .param("payerOpenid", payment.payerOpenid())
                .param("now", now)
                .param("createdBy", insert.createdBy())
                .update();
        return findByIdempotency(order.id(), insert.idempotencyKey()).orElseThrow();
    }

    public boolean finishCreate(
            long recordId,
            ElectronicWaybillStatus status,
            ElectronicWaybillPendingOperation pendingOperation,
            String waybillId,
            String errorCode,
            String errorMessage,
            LocalDateTime now
    ) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = :status,
                            pending_operation = :pendingOperation,
                            waybill_id = :waybillId,
                            last_error_code = :errorCode,
                            last_error_message = :errorMessage,
                            updated_at = :now
                        where id = :recordId
                          and status = 'CREATING'
                          and pending_operation = 'CREATE'
                        """)
                .param("status", status.name())
                .param("pendingOperation", pendingOperation.name())
                .param("waybillId", waybillId)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("now", now)
                .param("recordId", recordId)
                .update() == 1;
    }

    public boolean claimRefresh(AttemptRow row, LocalDateTime now) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set pending_operation = 'REFRESH',
                            upstream_attempt_count = upstream_attempt_count + 1,
                            last_attempt_at = :now,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :now
                        where id = :recordId
                          and status = :status
                          and pending_operation = :pendingOperation
                          and upstream_attempt_count = :upstreamAttemptCount
                        """)
                .param("now", now)
                .param("recordId", row.id())
                .param("status", row.status().name())
                .param("pendingOperation", row.pendingOperation().name())
                .param("upstreamAttemptCount", row.upstreamAttemptCount())
                .update() == 1;
    }

    public boolean finishRefresh(
            AttemptRow claimed,
            ElectronicWaybillStatus status,
            ElectronicWaybillPendingOperation pendingOperation,
            String waybillId,
            String errorCode,
            String errorMessage,
            LocalDateTime canceledAt,
            LocalDateTime now
    ) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = :newStatus,
                            pending_operation = :newPendingOperation,
                            waybill_id = :waybillId,
                            last_error_code = :errorCode,
                            last_error_message = :errorMessage,
                            canceled_at = case when :newStatus = 'CANCELED' then :canceledAt
                                               else canceled_at end,
                            updated_at = :now
                        where id = :recordId
                          and status = :claimedStatus
                          and pending_operation = 'REFRESH'
                          and upstream_attempt_count = :expectedUpstreamAttemptCount
                        """)
                .param("newStatus", status.name())
                .param("newPendingOperation", pendingOperation.name())
                .param("waybillId", waybillId)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("canceledAt", canceledAt)
                .param("now", now)
                .param("recordId", claimed.id())
                .param("claimedStatus", claimed.status().name())
                .param("expectedUpstreamAttemptCount", claimed.upstreamAttemptCount() + 1)
                .update() == 1;
    }

    public boolean claimCancel(AttemptRow row, LocalDateTime now) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = 'CANCELING',
                            pending_operation = 'CANCEL',
                            upstream_attempt_count = upstream_attempt_count + 1,
                            last_attempt_at = :now,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :now
                        where id = :recordId
                          and status = 'CREATED'
                          and pending_operation = 'NONE'
                        """)
                .param("now", now)
                .param("recordId", row.id())
                .update() == 1;
    }

    public boolean finishCancel(
            long recordId,
            ElectronicWaybillStatus status,
            ElectronicWaybillPendingOperation pendingOperation,
            String errorCode,
            String errorMessage,
            LocalDateTime canceledAt,
            LocalDateTime now
    ) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set status = :status,
                            pending_operation = :pendingOperation,
                            last_error_code = :errorCode,
                            last_error_message = :errorMessage,
                            canceled_at = :canceledAt,
                            updated_at = :now
                        where id = :recordId
                          and status = 'CANCELING'
                          and pending_operation = 'CANCEL'
                        """)
                .param("status", status.name())
                .param("pendingOperation", pendingOperation.name())
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("canceledAt", canceledAt)
                .param("now", now)
                .param("recordId", recordId)
                .update() == 1;
    }

    public boolean incrementPrint(long recordId, LocalDateTime now) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set print_request_count = print_request_count + 1,
                            last_print_requested_at = :now,
                            updated_at = :now
                        where id = :recordId
                          and status in ('CREATED', 'CONFIRMED')
                          and pending_operation = 'NONE'
                        """)
                .param("now", now)
                .param("recordId", recordId)
                .update() == 1;
    }

    public boolean recordSandboxAttempt(long recordId, LocalDateTime now) {
        return jdbcClient.sql("""
                        update order_electronic_waybill
                        set upstream_attempt_count = upstream_attempt_count + 1,
                            last_attempt_at = :now,
                            last_error_code = '',
                            last_error_message = '',
                            updated_at = :now
                        where id = :recordId
                          and status = 'CREATED'
                          and pending_operation = 'NONE'
                          and mode = 'SANDBOX'
                          and delivery_id = 'TEST'
                          and biz_id = 'test_biz_id'
                        """)
                .param("now", now)
                .param("recordId", recordId)
                .update() == 1;
    }

    public void finishSandboxAttempt(
            long recordId,
            String errorCode,
            String errorMessage,
            LocalDateTime now
    ) {
        jdbcClient.sql("""
                        update order_electronic_waybill
                        set last_error_code = :errorCode,
                            last_error_message = :errorMessage,
                            updated_at = :now
                        where id = :recordId
                          and status = 'CREATED'
                        """)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .param("now", now)
                .param("recordId", recordId)
                .update();
    }

    private int count(String sql, long orderId) {
        Integer count = jdbcClient.sql(sql)
                .param("orderId", orderId)
                .query(Integer.class)
                .single();
        return count == null ? 0 : count;
    }

    private AttemptRow mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getInt("attempt_no"),
                rs.getString("idempotency_key"),
                rs.getString("request_digest"),
                rs.getString("provider_order_id"),
                rs.getString("mode"),
                rs.getString("delivery_id"),
                rs.getString("delivery_name"),
                rs.getString("biz_id"),
                rs.getInt("service_type"),
                rs.getString("service_name"),
                ElectronicWaybillStatus.valueOf(rs.getString("status")),
                ElectronicWaybillPendingOperation.valueOf(rs.getString("pending_operation")),
                rs.getString("waybill_id"),
                new WechatExpressParcel(
                        rs.getInt("parcel_count"),
                        rs.getBigDecimal("weight_kg"),
                        rs.getBigDecimal("length_cm"),
                        rs.getBigDecimal("width_cm"),
                        rs.getBigDecimal("height_cm")
                ),
                rs.getString("custom_remark"),
                rs.getObject("expected_pickup_time", Long.class),
                new WechatExpressSender(
                        rs.getString("sender_name"),
                        rs.getString("sender_mobile"),
                        rs.getString("sender_company"),
                        rs.getString("sender_province"),
                        rs.getString("sender_city"),
                        rs.getString("sender_district"),
                        rs.getString("sender_detail_address")
                ),
                new ReceiverSnapshot(
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("receiver_province"),
                        rs.getString("receiver_city"),
                        rs.getString("receiver_district"),
                        rs.getString("receiver_detail_address"),
                        rs.getString("receiver_location_name"),
                        rs.getString("receiver_doorplate")
                ),
                rs.getLong("payment_order_id"),
                rs.getString("payer_openid"),
                rs.getString("last_error_code"),
                rs.getString("last_error_message"),
                rs.getInt("upstream_attempt_count"),
                rs.getObject("last_attempt_at", LocalDateTime.class),
                rs.getInt("print_request_count"),
                rs.getObject("last_print_requested_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getObject("canceled_at", LocalDateTime.class),
                rs.getObject("confirmed_at", LocalDateTime.class)
        );
    }

    private static final String ATTEMPT_SELECT = """
            select id, order_id, attempt_no, idempotency_key, request_digest, provider_order_id,
                   mode, delivery_id, delivery_name, biz_id, service_type, service_name,
                   status, pending_operation, waybill_id,
                   parcel_count, weight_kg, length_cm, width_cm, height_cm,
                   custom_remark, expected_pickup_time,
                   sender_name, sender_mobile, sender_company, sender_province,
                   sender_city, sender_district, sender_detail_address,
                   receiver_name, receiver_phone, receiver_province, receiver_city,
                   receiver_district, receiver_detail_address,
                   receiver_location_name, receiver_doorplate,
                   payment_order_id, payer_openid,
                   last_error_code, last_error_message,
                   upstream_attempt_count, last_attempt_at,
                   print_request_count, last_print_requested_at,
                   created_at, updated_at, canceled_at, confirmed_at
            from order_electronic_waybill
            """;

    public record OrderSnapshot(
            OrderRow order,
            PaymentSnapshot payment,
            List<ItemSnapshot> items,
            boolean shipmentExists,
            boolean blockingAfterSale
    ) {
    }

    public record OrderRow(
            long id,
            String orderNo,
            String status,
            String receiverName,
            String receiverPhone,
            String receiverProvince,
            String receiverCity,
            String receiverDistrict,
            String receiverDetailAddress,
            String receiverLocationName,
            String receiverDoorplate
    ) {
    }

    public record PaymentSnapshot(long id, String transactionId, String payerOpenid) {
    }

    public record ItemSnapshot(
            long id,
            String title,
            String subtitle,
            String mainImage,
            String skuImage,
            String displayImage,
            String specText,
            int quantity
    ) {
    }

    public record ReceiverSnapshot(
            String name,
            String phone,
            String province,
            String city,
            String district,
            String detailAddress,
            String locationName,
            String doorplate
    ) {
    }

    public record CreateInsert(
            OrderSnapshot order,
            WechatExpressEffectiveConfig config,
            WechatExpressParcel parcel,
            int attemptNo,
            String idempotencyKey,
            String requestDigest,
            String providerOrderId,
            String remark,
            Long expectTime,
            long createdBy
    ) {
    }

    public record AttemptRow(
            long id,
            long orderId,
            int attemptNo,
            String idempotencyKey,
            String requestDigest,
            String providerOrderId,
            String mode,
            String deliveryId,
            String deliveryName,
            String bizId,
            int serviceType,
            String serviceName,
            ElectronicWaybillStatus status,
            ElectronicWaybillPendingOperation pendingOperation,
            String waybillId,
            WechatExpressParcel parcel,
            String remark,
            Long expectTime,
            WechatExpressSender sender,
            ReceiverSnapshot receiver,
            long paymentOrderId,
            String payerOpenid,
            String lastErrorCode,
            String lastErrorMessage,
            int upstreamAttemptCount,
            LocalDateTime lastAttemptAt,
            int printCount,
            LocalDateTime lastPrintedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime canceledAt,
            LocalDateTime confirmedAt
    ) {
    }
}
